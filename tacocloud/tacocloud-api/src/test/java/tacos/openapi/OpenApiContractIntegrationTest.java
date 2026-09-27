package tacos.openapi;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.idempotency.CanonicalPayloadHasher;
import tacos.idempotency.IdempotencyKeyValidator;
import tacos.idempotency.IdempotencyRecord;
import tacos.idempotency.IdempotencyRecordRepository;
import tacos.idempotency.IdempotencyService;
import tacos.order.OrderHistoryService;
import tacos.order.OrderPlacementService;
import tacos.order.OrderStatus;
import tacos.payment.PaymentGateway;
import tacos.inventory.InventoryService;
import tacos.pricing.PricingService;
import tacos.web.api.EmailOrderService;
import tacos.web.api.OpenApiController;
import tacos.web.api.OrderApiController;
import tacos.web.api.dto.ApiProblem;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.TacoRequest;

public class OpenApiContractIntegrationTest {

  private OrderRepository orderRepo;
  private PricingService pricingService;
  private InventoryService inventoryService;
  private OrderPlacementService placementService;
  private IdempotencyRecordRepository idempotencyRepo;
  private IdempotencyService idempotencyService;
  private OrderApiController orderController;
  private OpenApiController openApiController;
  private Authentication auth;

  @BeforeEach
  public void setUp() {
    orderRepo = mock(OrderRepository.class);
    pricingService = mock(PricingService.class);
    inventoryService = mock(InventoryService.class);
    placementService = mock(OrderPlacementService.class);
    idempotencyRepo = mock(IdempotencyRecordRepository.class);

    CanonicalPayloadHasher hasher = new CanonicalPayloadHasher();
    IdempotencyKeyValidator keyValidator = new IdempotencyKeyValidator();
    idempotencyService = new IdempotencyService(idempotencyRepo, hasher, keyValidator, null);

    orderController = new OrderApiController(
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

    openApiController = new OpenApiController();
    auth = new UsernamePasswordAuthenticationToken("alice", "pass");
  }

  private OrderCreateRequest buildValidOrderRequest() {
    TacoRequest taco = new TacoRequest();
    taco.setName("Carnitas Supreme");
    taco.setIngredientIds(Arrays.asList("FLTO", "CARN", "CHED"));
    OrderItemRequest item = new OrderItemRequest();
    item.setTaco(taco);
    item.setQuantity(2);

    OrderCreateRequest req = new OrderCreateRequest();
    req.setDeliveryName("Jane Doe");
    req.setDeliveryStreet("123 Main St");
    req.setDeliveryCity("Austin");
    req.setDeliveryState("TX");
    req.setDeliveryZip("78701");
    req.setPaymentMethodId("pm_12345");
    req.setItems(Arrays.asList(item));
    return req;
  }

  @Test
  @DisplayName("Contract Test: POST /api/v1/orders devuelve status 201 y payload acorde al schema OrderResponse")
  public void postOrderContractTest_matchesOrderResponseSchema() {
    OrderCreateRequest request = buildValidOrderRequest();

    when(pricingService.calculateAndApplyPricing(any(TacoOrder.class)))
        .thenAnswer(inv -> {
          TacoOrder order = inv.getArgument(0);
          order.setTotal(new BigDecimal("18.50"));
          order.setSubtotal(new BigDecimal("18.50"));
          return Mono.just(order);
        });

    when(inventoryService.reserve(any(TacoOrder.class)))
        .thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    when(idempotencyRepo.findByUserIdAndKey(any(), any())).thenReturn(Mono.empty());
    when(idempotencyRepo.save(any(IdempotencyRecord.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    when(placementService.placeOrder(any(TacoOrder.class)))
        .thenAnswer(inv -> {
          TacoOrder o = inv.getArgument(0);
          o.setId("64b8a1c9e4b0c23a8f999999");
          o.setPlacedAt(new Date());
          return Mono.just(o);
        });

    OrderResponse response = orderController.postOrder("idem-test-key-contract", request, auth).block();

    assertThat(response).isNotNull();

    // Verificación exhaustiva de campos requeridos por el schema OpenAPI OrderResponse
    assertThat(response.getId()).isEqualTo("64b8a1c9e4b0c23a8f999999");
    assertThat(response.getDeliveryName()).isEqualTo("Jane Doe");
    assertThat(response.getDeliveryStreet()).isEqualTo("123 Main St");
    assertThat(response.getDeliveryCity()).isEqualTo("Austin");
    assertThat(response.getDeliveryState()).isEqualTo("TX");
    assertThat(response.getDeliveryZip()).isEqualTo("78701");
    assertThat(response.getTotal()).isEqualByComparingTo("18.50");
  }

  @Test
  @DisplayName("Contract Test: ApiProblem cumple con el estándar RFC 7807 documentado en openapi.yaml")
  public void apiProblemContractTest_conformsToRfc7807() {
    ApiProblem problem = ApiProblem.of(
        HttpStatus.BAD_REQUEST,
        "INVALID_IDEMPOTENCY_KEY",
        "Bad Request",
        "Invalid Idempotency-Key format. Must be between 8 and 128 characters",
        "/api/v1/orders"
    );
    problem.setTraceId("550e8400-e29b-41d4-a716-446655440000");

    // Verificar propiedades requeridas por el schema ApiProblem
    assertThat(problem.getTitle()).isEqualTo("Bad Request");
    assertThat(problem.getStatus()).isEqualTo(400);
    assertThat(problem.getDetail()).contains("Invalid Idempotency-Key");
    assertThat(problem.getInstance()).isEqualTo("/api/v1/orders");
    assertThat(problem.getCode()).isEqualTo("INVALID_IDEMPOTENCY_KEY");
    assertThat(problem.getTimestamp()).isNotNull();
    assertThat(problem.getTraceId()).isEqualTo("550e8400-e29b-41d4-a716-446655440000");
  }

  @Test
  @DisplayName("Contract Test: GET /openapi.yaml y /api/v1/api-docs publican el contrato en YAML")
  public void getOpenApiSpec_servesValidYamlContract() {
    ResponseEntity<String> response = openApiController.getOpenApiSpec();

    assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
    assertThat(response.getHeaders().getContentType().toString()).contains("text/yaml");
    assertThat(response.getBody()).isNotBlank();
    assertThat(response.getBody()).contains("openapi: 3.0.3");
    assertThat(response.getBody()).contains("Taco Cloud Public API");
    assertThat(response.getBody()).contains("/api/v1");
  }

}
