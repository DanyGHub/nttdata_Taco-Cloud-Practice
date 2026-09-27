package tacos.idempotency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;
import tacos.order.OrderPlacementService;
import tacos.pricing.PricingService;
import tacos.web.api.OrderApiController;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.TacoRequest;

public class OrderApiControllerIdempotencyIntegrationTest {

  private OrderRepository orderRepo;
  private InventoryService inventoryService;
  private PricingService pricingService;
  private OrderPlacementService placementService;
  private IdempotencyRecordRepository idempotencyRepo;
  private IdempotencyService idempotencyService;
  private OrderApiController controller;
  private Authentication auth;

  @BeforeEach
  public void setUp() {
    orderRepo = mock(OrderRepository.class);
    inventoryService = mock(InventoryService.class);
    pricingService = mock(PricingService.class);
    placementService = mock(OrderPlacementService.class);
    idempotencyRepo = mock(IdempotencyRecordRepository.class);

    CanonicalPayloadHasher hasher = new CanonicalPayloadHasher();
    IdempotencyKeyValidator validator = new IdempotencyKeyValidator();
    idempotencyService = new IdempotencyService(idempotencyRepo, hasher, validator, null);

    controller = new OrderApiController(
        orderRepo,
        null,
        null,
        new OrderMapper(),
        null,
        null,
        null,
        pricingService,
        inventoryService,
        null,
        null,
        null,
        null,
        placementService,
        idempotencyService
    );

    auth = new UsernamePasswordAuthenticationToken("alice", "pass");
  }

  private OrderCreateRequest buildRequest() {
    TacoRequest taco = new TacoRequest();
    taco.setName("Carnitas");
    taco.setIngredientIds(Arrays.asList("FLTO", "CARN"));
    OrderItemRequest item = new OrderItemRequest();
    item.setTaco(taco);
    item.setQuantity(1);

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Alice");
    req.setDeliveryStreet("Main St 10");
    req.setDeliveryCity("Austin");
    req.setDeliveryState("TX");
    req.setDeliveryZip("78701");
    req.setPaymentMethodId("pm-1");
    req.setItems(Arrays.asList(item));
    return req;
  }

  @Test
  @DisplayName("Doble request con Idempotency-Key reserva stock y coloca orden sólo UNA vez")
  public void doubleRequestWithKey_reservesAndPlacesOrderOnlyOnce() {
    String key = "idem-test-key-12345";
    OrderCreateRequest req = buildRequest();

    when(pricingService.calculateAndApplyPricing(any(TacoOrder.class)))
        .thenAnswer(inv -> {
          TacoOrder o = inv.getArgument(0);
          o.setTotal(new BigDecimal("12.00"));
          return Mono.just(o);
        });

    when(inventoryService.reserve(any(TacoOrder.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    when(placementService.placeOrder(any(TacoOrder.class)))
        .thenAnswer(inv -> {
          TacoOrder o = inv.getArgument(0);
          o.setId("PERSISTED-ORDER-1");
          return Mono.just(o);
        });

    // Mock del repo de idempotencia: primera vez vacío, segunda vez retorna el registro completado
    when(idempotencyRepo.findByUserIdAndKey("alice", key))
        .thenReturn(Mono.empty());
    when(idempotencyRepo.save(any(IdempotencyRecord.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // 1. Primera llamada: Procesa la orden
    OrderResponse firstResp = controller.postOrder(key, req, auth).block();
    assertThat(firstResp).isNotNull();
    assertThat(firstResp.getId()).isEqualTo("PERSISTED-ORDER-1");

    // 2. Segunda llamada con la MISMA clave:
    when(idempotencyRepo.findByUserIdAndKey("alice", key))
        .thenReturn(Mono.just(IdempotencyRecord.builder()
            .key(key)
            .userId("alice")
            .requestHash(new CanonicalPayloadHasher().computeHash(req))
            .status(IdempotencyStatus.COMPLETED)
            .orderId("PERSISTED-ORDER-1")
            .response(firstResp)
            .build()));

    OrderResponse secondResp = controller.postOrder(key, req, auth).block();
    assertThat(secondResp).isNotNull();
    assertThat(secondResp.getId()).isEqualTo("PERSISTED-ORDER-1");

    // Verificación estricta: ¡reserve() y placeOrder() se invocaron EXACTAMENTE UNA VEZ!
    verify(inventoryService, times(1)).reserve(any(TacoOrder.class));
    verify(placementService, times(1)).placeOrder(any(TacoOrder.class));
  }

}
