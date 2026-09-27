package tacos.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
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
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.idempotency.CanonicalPayloadHasher;
import tacos.idempotency.IdempotencyKeyValidator;
import tacos.idempotency.IdempotencyRecord;
import tacos.idempotency.IdempotencyRecordRepository;
import tacos.idempotency.IdempotencyService;
import tacos.idempotency.IdempotencyStatus;
import tacos.order.OrderStatus;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.TacoRequest;

/**
 * Prueba de regresión de idempotencia y concurrencia.
 */
public class IdempotencyAndConcurrencyRegressionTest {

  private IdempotencyRecordRepository idempotencyRepo;
  private CanonicalPayloadHasher hasher;
  private IdempotencyKeyValidator validator;
  private IdempotencyService service;

  @BeforeEach
  public void setUp() {
    idempotencyRepo = mock(IdempotencyRecordRepository.class);
    hasher = new CanonicalPayloadHasher();
    validator = new IdempotencyKeyValidator();
    service = new IdempotencyService(idempotencyRepo, hasher, validator, null);
  }

  private OrderCreateRequest buildSampleRequest(String name) {
    TacoRequest taco = new TacoRequest();
    taco.setName("Taco " + name);
    taco.setIngredientIds(Arrays.asList("FLTO", "CARN"));
    OrderItemRequest item = new OrderItemRequest();
    item.setTaco(taco);
    item.setQuantity(2);

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Customer " + name);
    req.setDeliveryStreet("100 Street");
    req.setDeliveryCity("Austin");
    req.setDeliveryState("TX");
    req.setDeliveryZip("78701");
    req.setItems(Arrays.asList(item));
    return req;
  }

  @Test
  @DisplayName("TC-34/TC-36: Doble llamada con misma Idempotency-Key ejecuta el pedido UNA sola vez y retorna caché")
  public void doubleRequestWithSameKey_neverCreatesTwoOrders() {
    String key = "idem-order-key-55555";
    String user = "alice";
    OrderCreateRequest request = buildSampleRequest("Alpha");

    OrderResponse expectedOrder = new OrderResponse();
    expectedOrder.setId("ORD-ONCE-ONLY-999");
    expectedOrder.setDeliveryName("Customer Alpha");
    expectedOrder.setTotal(new BigDecimal("18.00"));
    expectedOrder.setStatus(OrderStatus.CREATED);

    AtomicInteger orderCreationCounter = new AtomicInteger(0);

    // Primera búsqueda: registro no existe
    when(idempotencyRepo.findByUserIdAndKey(user, key))
        .thenReturn(Mono.empty());
    when(idempotencyRepo.save(any(IdempotencyRecord.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // 1. Primera ejecución: ejecuta la lógica de creación de orden
    OrderResponse first = service.executeIdempotent(
        key, user, request,
        () -> {
          orderCreationCounter.incrementAndGet();
          return Mono.just(expectedOrder);
        }
    ).block();

    assertThat(first).isNotNull();
    assertThat(first.getId()).isEqualTo("ORD-ONCE-ONLY-999");
    assertThat(orderCreationCounter.get()).isEqualTo(1);

    // 2. Segunda ejecución (reintento del cliente con la misma clave)
    // El repositorio ahora retorna el registro en estado COMPLETED
    when(idempotencyRepo.findByUserIdAndKey(user, key))
        .thenReturn(Mono.just(IdempotencyRecord.builder()
            .key(key)
            .userId(user)
            .requestHash(hasher.computeHash(request))
            .status(IdempotencyStatus.COMPLETED)
            .orderId("ORD-ONCE-ONLY-999")
            .response(expectedOrder)
            .build()));

    OrderResponse second = service.executeIdempotent(
        key, user, request,
        () -> {
          orderCreationCounter.incrementAndGet(); // NO DEBE LLAMARSE
          return Mono.just(expectedOrder);
        }
    ).block();

    assertThat(second).isNotNull();
    assertThat(second.getId()).isEqualTo("ORD-ONCE-ONLY-999");
    // CRITERIO CLAVE: El pipeline de negocio se ejecutó EXACTAMENTE 1 vez
    assertThat(orderCreationCounter.get()).isEqualTo(1);
  }

  @Test
  @DisplayName("TC-34/TC-36: Múltiples solicitudes concurrentes con misma Idempotency-Key coordinan atómicamente y crean 1 orden")
  public void concurrentRequestsWithSameKey_executesPipelineExactlyOnce() throws InterruptedException {
    String key = "concurrent-key-88888888";
    String user = "bob";
    OrderCreateRequest request = buildSampleRequest("Beta");

    ConcurrentHashMap<String, IdempotencyRecord> mockDb = new ConcurrentHashMap<>();
    AtomicInteger pipelineInvocations = new AtomicInteger(0);

    when(idempotencyRepo.findByUserIdAndKey(user, key))
        .thenAnswer(inv -> Mono.justOrEmpty(mockDb.get(key)));

    when(idempotencyRepo.save(any(IdempotencyRecord.class))).thenAnswer(inv -> {
      IdempotencyRecord rec = inv.getArgument(0);
      // Simular restricción de clave única de base de datos
      if (rec.getId() == null && mockDb.containsKey(key)) {
        throw new DuplicateKeyException("E11000 duplicate key error on collection idempotencyRecord");
      }
      rec.setId("rec-id-generated");
      mockDb.put(key, rec);
      return Mono.just(rec);
    });

    int threads = 5;
    ExecutorService executor = Executors.newFixedThreadPool(threads);
    CountDownLatch latch = new CountDownLatch(threads);
    List<OrderResponse> responses = Collections.synchronizedList(new ArrayList<>());

    OrderResponse sharedResponse = new OrderResponse();
    sharedResponse.setId("ORDER-CONCURRENT-WINNER");

    for (int i = 0; i < threads; i++) {
      executor.submit(() -> {
        try {
          OrderResponse resp = service.executeIdempotent(
              key, user, request,
              () -> {
                pipelineInvocations.incrementAndGet();
                try {
                  Thread.sleep(40); // Simula el tiempo de procesamiento de orden
                } catch (InterruptedException ignored) {}
                return Mono.just(sharedResponse);
              }
          ).block();
          if (resp != null) {
            responses.add(resp);
          }
        } catch (Exception ignored) {
        } finally {
          latch.countDown();
        }
      });
    }

    boolean completedInTime = latch.await(5, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(completedInTime).isTrue();
    // La creación de orden se ejecutó EXACTAMENTE 1 vez a pesar de los hilos simultáneos
    assertThat(pipelineInvocations.get()).isEqualTo(1);
    assertThat(responses).isNotEmpty();
    for (OrderResponse r : responses) {
      assertThat(r.getId()).isEqualTo("ORDER-CONCURRENT-WINNER");
    }
  }

  @Test
  @DisplayName("TC-34/TC-36: Misma Idempotency-Key con payload diferente arroja 409 Conflict")
  public void sameKeyWithDifferentPayload_throws409Conflict() {
    String key = "key-reused-with-conflict";
    String user = "charlie";

    OrderCreateRequest req1 = buildSampleRequest("Original");
    OrderCreateRequest req2 = buildSampleRequest("Modified-Diff-Street");
    req2.setDeliveryStreet("999 New Street");

    IdempotencyRecord existingRecord = IdempotencyRecord.builder()
        .key(key)
        .userId(user)
        .requestHash(hasher.computeHash(req1))
        .status(IdempotencyStatus.COMPLETED)
        .orderId("ORIGINAL-ORDER-ID")
        .build();

    when(idempotencyRepo.findByUserIdAndKey(user, key))
        .thenReturn(Mono.just(existingRecord));

    assertThatThrownBy(() -> service.executeIdempotent(key, user, req2, () -> Mono.empty()).block())
        .isInstanceOf(ResponseStatusException.class)
        .hasMessageContaining("409 CONFLICT")
        .hasMessageContaining("was already used with different request parameters");
  }

}
