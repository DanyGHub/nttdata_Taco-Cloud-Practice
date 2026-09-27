package tacos.idempotency;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderResponse;

/**
 * Servicio de Idempotencia para creación de órdenes.
 */
@Service
public class IdempotencyService {

  private static final Logger log = LoggerFactory.getLogger(IdempotencyService.class);

  public static final long DEFAULT_TTL_SECONDS = 86400; // 24 horas
  public static final int MAX_CONCURRENT_POLL_ATTEMPTS = 15;
  public static final long POLL_DELAY_MS = 200;

  private final IdempotencyRecordRepository repo;
  private final CanonicalPayloadHasher hasher;
  private final IdempotencyKeyValidator validator;
  private Clock clock;

  @Autowired
  public IdempotencyService(
      IdempotencyRecordRepository repo,
      CanonicalPayloadHasher hasher,
      IdempotencyKeyValidator validator,
      @Autowired(required = false) Clock clock) {
    this.repo = repo;
    this.hasher = hasher != null ? hasher : new CanonicalPayloadHasher();
    this.validator = validator != null ? validator : new IdempotencyKeyValidator();
    this.clock = clock != null ? clock : Clock.systemUTC();
  }

  public void setClock(Clock clock) {
    this.clock = clock != null ? clock : Clock.systemUTC();
  }

  /**
   * Ejecuta el pipeline de creación de orden protegiéndolo con Idempotency-Key.
   */
  public Mono<OrderResponse> executeIdempotent(
      String rawKey,
      String username,
      OrderCreateRequest request,
      Supplier<Mono<OrderResponse>> orderExecutionPipeline) {

    // 1. Si no hay clave, continúa el flujo estándar sin idempotencia
    if (rawKey == null || rawKey.trim().isEmpty()) {
      return orderExecutionPipeline.get();
    }

    // 2. Validación de formato y límites del header
    validator.validate(rawKey);

    final String key = rawKey.trim();
    final String userId = (username != null && !username.trim().isEmpty()) ? username.trim() : "anonymous";
    final String currentHash = hasher.computeHash(request);
    final Instant now = clock.instant();

    return repo.findByUserIdAndKey(userId, key)
        .flatMap(existingRecord -> handleExistingRecord(existingRecord, userId, key, currentHash, orderExecutionPipeline))
        .switchIfEmpty(Mono.defer(() -> claimAndExecute(userId, key, currentHash, now, orderExecutionPipeline)));
  }

  private Mono<OrderResponse> handleExistingRecord(
      IdempotencyRecord record,
      String userId,
      String key,
      String currentHash,
      Supplier<Mono<OrderResponse>> pipeline) {

    // Caso A: Misma clave con payload diferente -> 409 Conflict
    if (!currentHash.equals(record.getRequestHash())) {
      log.warn("Idempotency conflict for user '{}' and key '{}': payload hash mismatch. Expected {}, got {}",
          userId, key, record.getRequestHash(), currentHash);
      return Mono.error(new ResponseStatusException(
          HttpStatus.CONFLICT,
          "Idempotency-Key '" + key + "' was already used with different request parameters."));
    }

    // Caso B: Misma clave con mismo payload completado -> Retornar respuesta guardada (Replay sin side-effects)
    if (record.getStatus() == IdempotencyStatus.COMPLETED) {
      log.info("Idempotent replay: returning cached OrderResponse for user='{}', key='{}', orderId='{}'",
          userId, key, record.getOrderId());
      return Mono.justOrEmpty(record.getResponse());
    }

    // Caso C: Petición concurrente en progreso -> Esperar reactivamente a que termine
    if (record.getStatus() == IdempotencyStatus.IN_PROGRESS) {
      log.info("Concurrent request detected for user='{}', key='{}'. Waiting for completion...", userId, key);
      return pollForCompletion(userId, key, currentHash);
    }

    // Caso D: Falló anteriormente -> Permitir reintento
    log.info("Retrying previously failed idempotent request for user='{}', key='{}'", userId, key);
    record.setStatus(IdempotencyStatus.IN_PROGRESS);
    record.setErrorMessage(null);
    return repo.save(record)
        .flatMap(saved -> executeAndFinalize(saved, pipeline));
  }

  private Mono<OrderResponse> claimAndExecute(
      String userId,
      String key,
      String currentHash,
      Instant now,
      Supplier<Mono<OrderResponse>> pipeline) {

    IdempotencyRecord initial = IdempotencyRecord.builder()
        .key(key)
        .userId(userId)
        .requestHash(currentHash)
        .status(IdempotencyStatus.IN_PROGRESS)
        .createdAt(now)
        .expiresAt(now.plusSeconds(DEFAULT_TTL_SECONDS))
        .build();

    return repo.save(initial)
        .flatMap(savedRecord -> executeAndFinalize(savedRecord, pipeline))
        .onErrorResume(DuplicateKeyException.class, ex -> {
          // Colisión de inserción concurrente atómica: otra petición ganó la inserción milimétrica
          log.info("Concurrent insertion race lost for user='{}', key='{}'. Fetching existing...", userId, key);
          return repo.findByUserIdAndKey(userId, key)
              .flatMap(existing -> handleExistingRecord(existing, userId, key, currentHash, pipeline));
        });
  }

  private Mono<OrderResponse> executeAndFinalize(
      IdempotencyRecord record,
      Supplier<Mono<OrderResponse>> pipeline) {

    return pipeline.get()
        .flatMap(orderResponse -> {
          record.setStatus(IdempotencyStatus.COMPLETED);
          record.setOrderId(orderResponse.getId());
          record.setResponse(orderResponse);
          return repo.save(record).thenReturn(orderResponse);
        })
        .onErrorResume(error -> {
          record.setStatus(IdempotencyStatus.FAILED);
          record.setErrorMessage(error.getMessage());
          return repo.save(record)
              .then(Mono.error(error));
        });
  }

  private Mono<OrderResponse> pollForCompletion(String userId, String key, String currentHash) {
    return Mono.defer(() -> repo.findByUserIdAndKey(userId, key))
        .repeatWhenEmpty(repeat -> repeat.delayElements(Duration.ofMillis(POLL_DELAY_MS)).take(MAX_CONCURRENT_POLL_ATTEMPTS))
        .flatMap(rec -> {
          if (!currentHash.equals(rec.getRequestHash())) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.CONFLICT,
                "Idempotency-Key '" + key + "' was already used with different request parameters."));
          }
          if (rec.getStatus() == IdempotencyStatus.COMPLETED) {
            return Mono.justOrEmpty(rec.getResponse());
          }
          if (rec.getStatus() == IdempotencyStatus.FAILED) {
            return Mono.error(new ResponseStatusException(
                HttpStatus.INTERNAL_SERVER_ERROR,
                "Concurrent request failed: " + rec.getErrorMessage()));
          }
          return Mono.empty(); // Sigue IN_PROGRESS
        })
        .repeatWhenEmpty(repeat -> repeat.delayElements(Duration.ofMillis(POLL_DELAY_MS)).take(MAX_CONCURRENT_POLL_ATTEMPTS))
        .switchIfEmpty(Mono.error(new ResponseStatusException(
            HttpStatus.CONFLICT,
            "Request with Idempotency-Key '" + key + "' is currently in progress. Please retry later.")));
  }

}
