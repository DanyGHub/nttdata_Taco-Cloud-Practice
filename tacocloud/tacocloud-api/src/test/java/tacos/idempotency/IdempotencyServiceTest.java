package tacos.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.TacoRequest;

public class IdempotencyServiceTest {

  private IdempotencyRecordRepository repo;
  private CanonicalPayloadHasher hasher;
  private IdempotencyKeyValidator validator;
  private IdempotencyService service;
  private Clock testClock;

  @BeforeEach
  public void setUp() {
    repo = mock(IdempotencyRecordRepository.class);
    hasher = new CanonicalPayloadHasher();
    validator = new IdempotencyKeyValidator();
    testClock = Clock.fixed(Instant.parse("2026-09-26T20:00:00Z"), ZoneOffset.UTC);
    service = new IdempotencyService(repo, hasher, validator, testClock);
  }

  private OrderCreateRequest createSampleRequest(String name) {
    TacoRequest taco = new TacoRequest();
    taco.setName("Taco " + name);
    taco.setIngredientIds(Arrays.asList("FLTO", "GRBF"));
    OrderItemRequest item = new OrderItemRequest();
    item.setTaco(taco);
    item.setQuantity(1);

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Customer " + name);
    req.setDeliveryStreet("100 Street");
    req.setDeliveryCity("City");
    req.setDeliveryState("TX");
    req.setDeliveryZip("75001");
    req.setItems(Arrays.asList(item));
    return req;
  }

  @Test
  @DisplayName("Petición secuencial idéntica devuelve la misma orden y NO vuelve a ejecutar el pipeline")
  public void sequentialIdenticalRequest_returnsSameOrderWithoutReExecution() {
    String key = "order-key-12345678";
    String user = "alice";
    OrderCreateRequest request = createSampleRequest("A");

    OrderResponse initialResponse = new OrderResponse();
    initialResponse.setId("ORDER-999");
    initialResponse.setDeliveryName("Customer A");
    initialResponse.setTotal(new BigDecimal("15.50"));

    AtomicInteger pipelineCalls = new AtomicInteger(0);

    // Primera llamada: no existe registro previo, se inserta IN_PROGRESS y luego COMPLETED
    when(repo.findByUserIdAndKey(user, key))
        .thenReturn(Mono.empty()) // Primera búsqueda
        .thenReturn(Mono.just(IdempotencyRecord.builder() // Segunda búsqueda si se llama
            .key(key)
            .userId(user)
            .requestHash(hasher.computeHash(request))
            .status(IdempotencyStatus.COMPLETED)
            .orderId("ORDER-999")
            .response(initialResponse)
            .build()));

    when(repo.save(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    OrderResponse first = service.executeIdempotent(
        key, user, request,
        () -> {
          pipelineCalls.incrementAndGet();
          return Mono.just(initialResponse);
        }
    ).block();

    assertThat(first).isNotNull();
    assertThat(first.getId()).isEqualTo("ORDER-999");
    assertThat(pipelineCalls.get()).isEqualTo(1);

    // Segunda llamada (reintento del cliente con la misma clave y payload)
    when(repo.findByUserIdAndKey(user, key)).thenReturn(Mono.just(IdempotencyRecord.builder()
        .key(key)
        .userId(user)
        .requestHash(hasher.computeHash(request))
        .status(IdempotencyStatus.COMPLETED)
        .orderId("ORDER-999")
        .response(initialResponse)
        .build()));

    OrderResponse second = service.executeIdempotent(
        key, user, request,
        () -> {
          pipelineCalls.incrementAndGet(); // No debe llamarse
          return Mono.just(initialResponse);
        }
    ).block();

    assertThat(second).isNotNull();
    assertThat(second.getId()).isEqualTo("ORDER-999");
    assertThat(pipelineCalls.get()).isEqualTo(1); // Pipeline no se llamó de nuevo
  }

  @Test
  @DisplayName("Misma clave con payload diferente arroja 409 Conflict")
  public void sameKeyDifferentPayload_throws409Conflict() {
    String key = "key-reused-123456";
    String user = "bob";

    OrderCreateRequest req1 = createSampleRequest("Order1");
    OrderCreateRequest req2 = createSampleRequest("Order2-Modified");

    IdempotencyRecord existingRecord = IdempotencyRecord.builder()
        .key(key)
        .userId(user)
        .requestHash(hasher.computeHash(req1))
        .status(IdempotencyStatus.COMPLETED)
        .orderId("ORIGINAL-ORDER")
        .build();

    when(repo.findByUserIdAndKey(user, key)).thenReturn(Mono.just(existingRecord));

    StepVerifier.create(service.executeIdempotent(key, user, req2, () -> Mono.empty()))
        .expectErrorMatches(err -> err instanceof ResponseStatusException
            && ((ResponseStatusException) err).getStatus() == HttpStatus.CONFLICT
            && err.getMessage().contains("was already used with different request parameters"))
        .verify();
  }

  @Test
  @DisplayName("Usuarios distintos pueden usar la misma clave sin conflicto ni colisión")
  public void differentUsersSameKey_noCollision() {
    String commonKey = "shared-uuid-88888888";
    OrderCreateRequest request = createSampleRequest("Shared");

    // Alice
    when(repo.findByUserIdAndKey("alice", commonKey)).thenReturn(Mono.empty());
    when(repo.save(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    OrderResponse aliceOrder = new OrderResponse();
    aliceOrder.setId("ORD-ALICE");

    OrderResponse resAlice = service.executeIdempotent(commonKey, "alice", request, () -> Mono.just(aliceOrder)).block();
    assertThat(resAlice.getId()).isEqualTo("ORD-ALICE");

    // Bob con la MISMA clave
    when(repo.findByUserIdAndKey("bob", commonKey)).thenReturn(Mono.empty());
    OrderResponse bobOrder = new OrderResponse();
    bobOrder.setId("ORD-BOB");

    OrderResponse resBob = service.executeIdempotent(commonKey, "bob", request, () -> Mono.just(bobOrder)).block();
    assertThat(resBob.getId()).isEqualTo("ORD-BOB");
  }

  @Test
  @DisplayName("Formato de Idempotency-Key inválido arroja 400 Bad Request")
  public void invalidKeyFormat_throws400BadRequest() {
    OrderCreateRequest request = createSampleRequest("X");

    // Demasiado corta (< 8 caracteres)
    assertThatThrownBy(() -> service.executeIdempotent("short", "alice", request, () -> Mono.empty()).block())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Invalid Idempotency-Key format");

    // Caracteres ilegales
    assertThatThrownBy(() -> service.executeIdempotent("key with spaces!!", "alice", request, () -> Mono.empty()).block())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("Invalid Idempotency-Key format");
  }

  @Test
  @DisplayName("Orden fallida permite reintento posterior sin quedar bloqueada")
  public void failedOrderPlacement_allowsSubsequentRetry() {
    String key = "retry-after-fail-123";
    String user = "charlie";
    OrderCreateRequest request = createSampleRequest("FailRetry");

    IdempotencyRecord failedRecord = IdempotencyRecord.builder()
        .key(key)
        .userId(user)
        .requestHash(hasher.computeHash(request))
        .status(IdempotencyStatus.FAILED)
        .errorMessage("Payment service unavailable")
        .build();

    when(repo.findByUserIdAndKey(user, key)).thenReturn(Mono.just(failedRecord));
    when(repo.save(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    OrderResponse successResponse = new OrderResponse();
    successResponse.setId("ORDER-RECOVERED");

    OrderResponse recovered = service.executeIdempotent(key, user, request, () -> Mono.just(successResponse)).block();
    assertThat(recovered).isNotNull();
    assertThat(recovered.getId()).isEqualTo("ORDER-RECOVERED");
  }

  @Test
  @DisplayName("Solicitudes concurrentes con la misma clave ejecutan el pedido una sola vez")
  public void concurrentRequests_singleExecution() throws InterruptedException {
    String key = "concurrent-key-99999999";
    String user = "dave";
    OrderCreateRequest request = createSampleRequest("Concurrent");

    ConcurrentHashMap<String, IdempotencyRecord> db = new ConcurrentHashMap<>();
    AtomicInteger orderCreationCount = new AtomicInteger(0);

    when(repo.findByUserIdAndKey(user, key)).thenAnswer(inv -> Mono.justOrEmpty(db.get(key)));

    when(repo.save(any(IdempotencyRecord.class))).thenAnswer(inv -> {
      IdempotencyRecord rec = inv.getArgument(0);
      // Simulación de atomic unique constraint
      if (rec.getId() == null && db.containsKey(key)) {
        throw new DuplicateKeyException("E11000 duplicate key error");
      }
      rec.setId("rec-id-1");
      db.put(key, rec);
      return Mono.just(rec);
    });

    int threadCount = 4;
    ExecutorService executor = Executors.newFixedThreadPool(threadCount);
    CountDownLatch latch = new CountDownLatch(threadCount);
    List<OrderResponse> results = Collections.synchronizedList(new ArrayList<>());

    OrderResponse targetResponse = new OrderResponse();
    targetResponse.setId("ORDER-CONCURRENT-WINNER");

    for (int i = 0; i < threadCount; i++) {
      executor.submit(() -> {
        try {
          OrderResponse resp = service.executeIdempotent(
              key, user, request,
              () -> {
                orderCreationCount.incrementAndGet();
                try {
                  Thread.sleep(50); // Simula tiempo de procesamiento de orden
                } catch (InterruptedException ignored) {}
                return Mono.just(targetResponse);
              }
          ).block();
          if (resp != null) {
            results.add(resp);
          }
        } catch (Exception ignored) {
        } finally {
          latch.countDown();
        }
      });
    }

    boolean finished = latch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(finished).isTrue();
    // La lógica de orden se ejecutó EXACTAMENTE 1 vez
    assertThat(orderCreationCount.get()).isEqualTo(1);
    // Todos los hilos que completaron obtuvieron el mismo resultado
    assertThat(results).isNotEmpty();
    for (OrderResponse r : results) {
      assertThat(r.getId()).isEqualTo("ORDER-CONCURRENT-WINNER");
    }
  }

}
