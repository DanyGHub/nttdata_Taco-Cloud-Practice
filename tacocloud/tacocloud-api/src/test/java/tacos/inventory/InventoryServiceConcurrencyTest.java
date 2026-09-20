package tacos.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.data.StockReservationRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.pricing.PricingService;
import tacos.web.api.OrderApiController;
import tacos.web.api.dto.IngredientRequest;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.TacoRequest;

@DataMongoTest
@Import({MongoInventoryService.class})
public class InventoryServiceConcurrencyTest {

  @org.springframework.boot.autoconfigure.SpringBootApplication
  @org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories(basePackages = "tacos.data")
  static class TestConfig {}

  @Autowired
  private ReactiveMongoTemplate mongoTemplate;

  @Autowired
  private StockReservationRepository reservationRepo;

  @Autowired
  private MongoInventoryService inventoryService;

  @BeforeEach
  public void setUp() {
    mongoTemplate.dropCollection("ingredient").block();
    mongoTemplate.dropCollection("stockReservations").block();
    mongoTemplate.dropCollection("tacoOrder").block();
  }

  @Test
  @DisplayName("Concurrencia: Dos o más órdenes compitiendo por 1 unidad de stock -> exactamente 1 triunfa y las demás fallan sin saldo negativo")
  public void concurrentReservation_competingForSingleUnit_shouldNeverSellAir() throws Exception {
    // Inicializar ingrediente con exactamente 1 unidad en stock
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("1.50"), true, 1, 0, null);
    mongoTemplate.save(flto).block();

    int concurrency = 10;
    ExecutorService executor = Executors.newFixedThreadPool(concurrency);
    CountDownLatch startGate = new CountDownLatch(1);
    CountDownLatch endGate = new CountDownLatch(concurrency);

    AtomicInteger successCount = new AtomicInteger(0);
    AtomicInteger failureCount = new AtomicInteger(0);
    ConcurrentLinkedQueue<Throwable> exceptions = new ConcurrentLinkedQueue<>();

    for (int i = 0; i < concurrency; i++) {
      final String orderId = "order_conc_" + i;
      executor.submit(() -> {
        try {
          startGate.await(); // Esperar a que todos los hilos arranquen simultáneamente
          Taco taco = new Taco();
          taco.setName("Taco " + orderId);
          taco.setIngredients(Collections.singletonList(new Ingredient("FLTO", "Flour Tortilla", Type.WRAP)));

          TacoOrder order = new TacoOrder();
          order.setId(orderId);
          order.addOrderItem(new OrderItem(taco, 1));

          inventoryService.reserve(order).block();
          successCount.incrementAndGet();
        } catch (Throwable t) {
          failureCount.incrementAndGet();
          exceptions.add(t);
        } finally {
          endGate.countDown();
        }
      });
    }

    // Disparar la carrera concurrente
    startGate.countDown();
    boolean completed = endGate.await(10, TimeUnit.SECONDS);
    executor.shutdown();

    assertThat(completed).isTrue();
    // Exactamente 1 orden reservó con éxito
    assertThat(successCount.get()).isEqualTo(1);
    // Exactamente 9 órdenes fallaron
    assertThat(failureCount.get()).isEqualTo(9);

    // Todas las excepciones deben ser InsufficientStockException
    for (Throwable t : exceptions) {
      Throwable cause = (t.getCause() != null) ? t.getCause() : t;
      assertThat(cause).isInstanceOf(InsufficientStockException.class);
      InsufficientStockException ise = (InsufficientStockException) cause;
      assertThat(ise.getCode()).isEqualTo("INSUFFICIENT_STOCK");
      assertThat(ise.getIngredientId()).isEqualTo("FLTO");
    }

    // El stock final en base de datos debe ser exactamente 0, JAMÁS negativo
    Ingredient finalFlto = mongoTemplate.findById("FLTO", Ingredient.class).block();
    assertThat(finalFlto).isNotNull();
    assertThat(finalFlto.getStockOnHand()).isEqualTo(0);
  }

  @Test
  @DisplayName("Compensación: Orden multi-ingrediente que falla a mitad restituye automáticamente los ingredientes previos")
  public void partialFailure_shouldTriggerAutomaticVerifiableCompensation() {
    // FLTO tiene 5 en stock, GRBF tiene 0 en stock
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("1.00"), true, 5, 0, null);
    Ingredient grbf = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("3.00"), true, 0, 0, null);
    mongoTemplate.save(flto).block();
    mongoTemplate.save(grbf).block();

    TacoOrder order = new TacoOrder();
    order.setId("order_partial_fail");

    Taco taco = new Taco();
    taco.setName("Beef Taco");
    // FLTO está primero alfabéticamente que GRBF
    taco.setIngredients(Arrays.asList(flto, grbf));
    order.addOrderItem(new OrderItem(taco, 2));

    StepVerifier.create(inventoryService.reserve(order))
        .expectErrorMatches(t -> {
          assertThat(t).isInstanceOf(InsufficientStockException.class);
          InsufficientStockException ise = (InsufficientStockException) t;
          assertThat(ise.getCode()).isEqualTo("INSUFFICIENT_STOCK");
          assertThat(ise.getIngredientId()).isEqualTo("GRBF");
          return true;
        })
        .verify();

    // Verificación de compensación: FLTO fue reservado primero, pero al fallar GRBF, FLTO debió ser restituido a 5
    Ingredient restoredFlto = mongoTemplate.findById("FLTO", Ingredient.class).block();
    assertThat(restoredFlto).isNotNull();
    assertThat(restoredFlto.getStockOnHand()).isEqualTo(5);

    // GRBF sigue en 0
    Ingredient untouchedGrbf = mongoTemplate.findById("GRBF", Ingredient.class).block();
    assertThat(untouchedGrbf).isNotNull();
    assertThat(untouchedGrbf.getStockOnHand()).isEqualTo(0);
  }

  @Test
  @DisplayName("Idempotencia: Reservar dos veces la misma orden no descuenta doblemente el inventario")
  public void idempotentReserve_shouldNotDecrementStockTwice() {
    Ingredient chkd = new Ingredient("CHKD", "Chicken", Type.PROTEIN, new BigDecimal("2.50"), true, 10, 0, null);
    mongoTemplate.save(chkd).block();

    TacoOrder order = new TacoOrder();
    order.setId("order_idempotent_1");

    Taco taco = new Taco();
    taco.setName("Chicken Taco");
    taco.setIngredients(Collections.singletonList(chkd));
    order.addOrderItem(new OrderItem(taco, 3));

    // Primera reserva
    StockReservation res1 = inventoryService.reserve(order).block();
    assertThat(res1).isNotNull();
    assertThat(res1.getStatus()).isEqualTo(ReservationStatus.RESERVED);

    Ingredient afterFirst = mongoTemplate.findById("CHKD", Ingredient.class).block();
    assertThat(afterFirst.getStockOnHand()).isEqualTo(7);

    // Segunda llamada con la misma orden
    StockReservation res2 = inventoryService.reserve(order).block();
    assertThat(res2).isNotNull();
    assertThat(res2.getId()).isEqualTo(res1.getId());

    // El stock debe permanecer en 7, no en 4
    Ingredient afterSecond = mongoTemplate.findById("CHKD", Ingredient.class).block();
    assertThat(afterSecond.getStockOnHand()).isEqualTo(7);
  }

  @Test
  @DisplayName("Idempotencia de liberación: releaseForOrder restituye stock una sola vez; reintentos no inflan inventario")
  public void idempotentRelease_shouldNotIncrementStockTwice() {
    Ingredient corn = new Ingredient("COTO", "Corn Tortilla", Type.WRAP, new BigDecimal("0.80"), true, 10, 0, null);
    mongoTemplate.save(corn).block();

    TacoOrder order = new TacoOrder();
    order.setId("order_cancel_1");

    Taco taco = new Taco();
    taco.setName("Corn Taco");
    taco.setIngredients(Collections.singletonList(corn));
    order.addOrderItem(new OrderItem(taco, 4));

    // Reservar 4 unidades -> stock pasa de 10 a 6
    inventoryService.reserve(order).block();
    Ingredient afterReserve = mongoTemplate.findById("COTO", Ingredient.class).block();
    assertThat(afterReserve.getStockOnHand()).isEqualTo(6);

    // Primera liberación -> stock vuelve a 10
    StockReservation released1 = inventoryService.releaseForOrder("order_cancel_1").block();
    assertThat(released1).isNotNull();
    assertThat(released1.getStatus()).isEqualTo(ReservationStatus.RELEASED);

    Ingredient afterFirstRelease = mongoTemplate.findById("COTO", Ingredient.class).block();
    assertThat(afterFirstRelease.getStockOnHand()).isEqualTo(10);

    // Segunda liberación (reintento o DELETE concurrente)
    StockReservation released2 = inventoryService.releaseForOrder("order_cancel_1").block();
    // Es un no-op
    assertThat(released2).isNull();

    // El stock sigue siendo 10 (no se infló a 14)
    Ingredient afterSecondRelease = mongoTemplate.findById("COTO", Ingredient.class).block();
    assertThat(afterSecondRelease.getStockOnHand()).isEqualTo(10);
  }

  @Test
  @DisplayName("Integración OrderApiController: postOrder reserva inventario y deleteOrder lo libera")
  public void controllerLifecycle_orderCreationAndCancellation_shouldManageStockAccurately() {
    Ingredient jack = new Ingredient("JACK", "Monterrey Jack", Type.CHEESE, new BigDecimal("1.20"), true, 5, 0, null);
    mongoTemplate.save(jack).block();

    OrderRepository orderRepo = org.mockito.Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = org.mockito.Mockito.mock(OrderMessagingService.class);
    PricingService pricingService = org.mockito.Mockito.mock(PricingService.class);

    org.mockito.Mockito.when(pricingService.calculateAndApplyPricing(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    org.mockito.Mockito.when(orderRepo.save(org.mockito.ArgumentMatchers.any()))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    org.mockito.Mockito.when(orderRepo.findById("order_ctrl_1"))
        .thenAnswer(inv -> {
          TacoOrder o = new TacoOrder();
          o.setId("order_ctrl_1");
          return Mono.just(o);
        });

    org.mockito.Mockito.when(orderRepo.deleteById("order_ctrl_1"))
        .thenReturn(Mono.empty());

    OrderApiController controller = new OrderApiController(
        orderRepo,
        messagingService,
        null,
        new OrderMapper(),
        null,
        null,
        null,
        pricingService,
        inventoryService
    );

    // 1. Crear orden con 2 unidades de JACK
    OrderCreateRequest createReq = new OrderCreateRequest();
    createReq.setDeliveryName("Order User");
    createReq.setDeliveryStreet("Main 123");
    createReq.setDeliveryCity("CDMX");
    createReq.setDeliveryState("CDMX");
    createReq.setDeliveryZip("12345");

    TacoRequest tacoReq = new TacoRequest();
    tacoReq.setName("Cheesy Taco");
    tacoReq.setIngredientIds(Collections.singletonList("JACK"));

    OrderItemRequest itemReq = new OrderItemRequest(tacoReq, 2);
    createReq.setItems(Collections.singletonList(itemReq));

    OrderResponse response = controller.postOrder(createReq, null).block();
    assertThat(response).isNotNull();
    String createdOrderId = response.getId();
    assertThat(createdOrderId).isNotNull();

    // Stock de JACK bajó de 5 a 3
    Ingredient afterPost = mongoTemplate.findById("JACK", Ingredient.class).block();
    assertThat(afterPost.getStockOnHand()).isEqualTo(3);

    // 2. Intentar crear otra orden solicitando 4 unidades de JACK (solo quedan 3)
    OrderCreateRequest excessiveReq = new OrderCreateRequest();
    excessiveReq.setDeliveryName("Greedy User");
    excessiveReq.setDeliveryStreet("Main 123");
    excessiveReq.setDeliveryCity("CDMX");
    excessiveReq.setDeliveryState("CDMX");
    excessiveReq.setDeliveryZip("12345");
    excessiveReq.setItems(Collections.singletonList(new OrderItemRequest(tacoReq, 4)));

    StepVerifier.create(controller.postOrder(excessiveReq, null))
        .expectErrorMatches(err -> {
          assertThat(err).isInstanceOf(InsufficientStockException.class);
          InsufficientStockException ise = (InsufficientStockException) err;
          assertThat(ise.getCode()).isEqualTo("INSUFFICIENT_STOCK");
          assertThat(ise.getIngredientId()).isEqualTo("JACK");
          return true;
        })
        .verify();

    // Stock de JACK sigue intacto en 3
    Ingredient afterExcessive = mongoTemplate.findById("JACK", Ingredient.class).block();
    assertThat(afterExcessive.getStockOnHand()).isEqualTo(3);

    // 3. Cancelar la primera orden con deleteOrder
    org.mockito.Mockito.when(orderRepo.findById(createdOrderId))
        .thenAnswer(inv -> {
          TacoOrder o = new TacoOrder();
          o.setId(createdOrderId);
          return Mono.just(o);
        });
    org.mockito.Mockito.when(orderRepo.deleteById(createdOrderId))
        .thenReturn(Mono.empty());

    controller.deleteOrder(createdOrderId, () -> "admin").block();

    // Stock de JACK regresa a 5
    Ingredient afterDelete = mongoTemplate.findById("JACK", Ingredient.class).block();
    assertThat(afterDelete.getStockOnHand()).isEqualTo(5);
  }
}
