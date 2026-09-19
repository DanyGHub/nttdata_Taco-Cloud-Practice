package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Set;

import javax.validation.ConstraintViolation;
import javax.validation.Validation;
import javax.validation.Validator;
import javax.validation.ValidatorFactory;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.payment.PaymentGateway;
import tacos.pricing.PricingService;
import tacos.web.api.dto.IngredientRequest;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.TacoRequest;

public class ServerPricingAndOrderLinesTest {

  private IngredientRepository ingredientRepo;
  private OrderRepository orderRepo;
  private UserRepository userRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private PaymentGateway paymentGateway;
  private OrderMessagingService messagingService;
  private EmailOrderService emailOrderService;
  private OrderMapper orderMapper;
  private PricingService pricingService;
  private OrderApiController controller;

  private Validator validator;

  @BeforeEach
  public void setUp() {
    ingredientRepo = mock(IngredientRepository.class);
    orderRepo = mock(OrderRepository.class);
    userRepo = mock(UserRepository.class);
    paymentMethodRepo = mock(PaymentMethodRepository.class);
    paymentGateway = mock(PaymentGateway.class);
    messagingService = mock(OrderMessagingService.class);
    emailOrderService = mock(EmailOrderService.class);

    orderMapper = new OrderMapper();
    pricingService = new PricingService(ingredientRepo);

    controller = new OrderApiController(
        orderRepo,
        messagingService,
        emailOrderService,
        orderMapper,
        userRepo,
        paymentMethodRepo,
        paymentGateway,
        pricingService
    );

    ValidatorFactory factory = Validation.buildDefaultValidatorFactory();
    validator = factory.getValidator();
  }

  @Test
  @DisplayName("PricingService calcula subtotal y total de líneas con BigDecimal y RoundingMode.HALF_UP")
  public void pricingService_shouldCalculateLineSubtotalsAndOrderTotal() {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.79"), true, 100, 10, 1L);
    Ingredient grbf = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.95"), true, 50, 5, 1L);

    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(flto, grbf));

    Taco taco = new Taco();
    taco.setName("Carnivore");
    taco.setIngredients(Arrays.asList(new Ingredient("FLTO", null, null), new Ingredient("GRBF", null, null)));

    OrderItem item = new OrderItem(taco, 2);
    TacoOrder order = new TacoOrder();
    order.addOrderItem(item);

    StepVerifier.create(pricingService.calculateAndApplyPricing(order))
        .assertNext(pricedOrder -> {
          // Taco unit price = 0.79 + 1.95 = 2.74
          assertThat(item.getUnitPriceAtPurchase()).isEqualByComparingTo(new BigDecimal("2.74"));
          // Subtotal for 2 items = 2.74 * 2 = 5.48
          assertThat(item.getSubtotal()).isEqualByComparingTo(new BigDecimal("5.48"));
          assertThat(pricedOrder.getSubtotal()).isEqualByComparingTo(new BigDecimal("5.48"));
          assertThat(pricedOrder.getTotal()).isEqualByComparingTo(new BigDecimal("5.48"));
          assertThat(pricedOrder.getCurrency()).isEqualTo("USD");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("El servidor ignora y sobreescribe cualquier total o subtotal manipulado por el cliente")
  public void serverPricing_shouldIgnoreClientCalculatedTotals() {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("1.00"), true, 100, 10, 1L);
    Ingredient chnz = new Ingredient("CHED", "Cheddar Cheese", Type.CHEESE, new BigDecimal("0.50"), true, 100, 10, 1L);

    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(flto, chnz));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(invocation -> {
      TacoOrder ord = invocation.getArgument(0);
      ord.setId("order-123");
      return Mono.just(ord);
    });

    // Cliente intenta alterar precios enviando total $0.01 y subtotal de línea $0.01
    OrderItemRequest itemReq = new OrderItemRequest();
    TacoRequest tacoReq = new TacoRequest();
    tacoReq.setName("Cheesy Taco");
    tacoReq.setIngredientIds(Arrays.asList("FLTO", "CHED"));
    itemReq.setTaco(tacoReq);
    itemReq.setQuantity(3);
    itemReq.setUnitPrice(new BigDecimal("0.01")); // Intento de fraude
    itemReq.setSubtotal(new BigDecimal("0.01"));  // Intento de fraude

    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("John Doe");
    request.setDeliveryStreet("123 Elm St");
    request.setDeliveryCity("Springfield");
    request.setDeliveryState("IL");
    request.setDeliveryZip("62701");
    request.setItems(Collections.singletonList(itemReq));
    request.setTotal(new BigDecimal("0.01"));    // Intento de fraude
    request.setSubtotal(new BigDecimal("0.01")); // Intento de fraude

    StepVerifier.create(controller.postOrder(request, null))
        .assertNext(resp -> {
          // Precio taco = 1.00 + 0.50 = 1.50. Cantidad 3 -> subtotal = 4.50. Total = 4.50
          assertThat(resp.getSubtotal()).isEqualByComparingTo(new BigDecimal("4.50"));
          assertThat(resp.getTotal()).isEqualByComparingTo(new BigDecimal("4.50"));
          assertThat(resp.getCurrency()).isEqualTo("USD");

          assertThat(resp.getItems()).hasSize(1);
          assertThat(resp.getItems().get(0).getUnitPriceAtPurchase()).isEqualByComparingTo(new BigDecimal("1.50"));
          assertThat(resp.getItems().get(0).getSubtotal()).isEqualByComparingTo(new BigDecimal("4.50"));
          assertThat(resp.getItems().get(0).getQuantity()).isEqualTo(3);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Persistencia del snapshot de precio: órdenes históricas no cambian cuando cambia el catálogo")
  public void historicalOrder_shouldRetainPriceSnapshot_whenCatalogChangesLater() {
    // 1. Crear y guardar orden con precio inicial
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("1.00"), true, 100, 10, 1L);
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(flto));

    Taco taco = new Taco();
    taco.setName("Simple Taco");
    taco.setIngredients(Collections.singletonList(new Ingredient("FLTO", null, null)));
    OrderItem item = new OrderItem(taco, 1);
    TacoOrder order = new TacoOrder();
    order.addOrderItem(item);

    pricingService.calculateAndApplyPricing(order).block();

    assertThat(order.getTotal()).isEqualByComparingTo(new BigDecimal("1.00"));
    assertThat(item.getUnitPriceAtPurchase()).isEqualByComparingTo(new BigDecimal("1.00"));

    // 2. Simular cambio posterior de precio en catálogo (ej: inflación a $5.00)
    Ingredient fltoInflated = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("5.00"), true, 100, 10, 2L);
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(fltoInflated));

    // El snapshot guardado en 'order' permanece intacto
    assertThat(order.getTotal()).isEqualByComparingTo(new BigDecimal("1.00"));
    assertThat(item.getUnitPriceAtPurchase()).isEqualByComparingTo(new BigDecimal("1.00"));
    assertThat(item.getSubtotal()).isEqualByComparingTo(new BigDecimal("1.00"));
  }

  @Test
  @DisplayName("Validación de cantidad: rechaza cantidad <= 0 o > 100")
  public void validation_shouldRejectInvalidQuantities() {
    TacoRequest validTaco = new TacoRequest("Good Taco", Collections.singletonList(new IngredientRequest("FLTO", "Flour Tortilla", Type.WRAP)));

    // Qty = 0
    OrderItemRequest zeroQty = new OrderItemRequest(validTaco, 0);
    Set<ConstraintViolation<OrderItemRequest>> zeroViolations = validator.validate(zeroQty);
    assertThat(zeroViolations).anyMatch(v -> v.getPropertyPath().toString().equals("quantity"));

    // Qty = -5
    OrderItemRequest negQty = new OrderItemRequest(validTaco, -5);
    Set<ConstraintViolation<OrderItemRequest>> negViolations = validator.validate(negQty);
    assertThat(negViolations).anyMatch(v -> v.getPropertyPath().toString().equals("quantity"));

    // Qty = 101
    OrderItemRequest excessQty = new OrderItemRequest(validTaco, 101);
    Set<ConstraintViolation<OrderItemRequest>> excessViolations = validator.validate(excessQty);
    assertThat(excessViolations).anyMatch(v -> v.getPropertyPath().toString().equals("quantity"));

    // Qty = 5 (Válido)
    OrderItemRequest validQty = new OrderItemRequest(validTaco, 5);
    Set<ConstraintViolation<OrderItemRequest>> validViolations = validator.validate(validQty);
    assertThat(validViolations).isEmpty();
  }

  @Test
  @DisplayName("Compatibilidad retroactiva: solicitud con 'tacos' asigna cantidad 1 y calcula precios correctamente")
  public void backwardCompatibility_withTacosList_shouldAssignQuantity1AndPrice() {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.80"), true, 100, 10, 1L);
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(flto));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(invocation -> Mono.just(invocation.getArgument(0)));

    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Legacy User");
    request.setDeliveryStreet("Legacy Way");
    request.setDeliveryCity("City");
    request.setDeliveryState("TX");
    request.setDeliveryZip("75001");

    TacoRequest legacyTaco = new TacoRequest();
    legacyTaco.setName("Legacy Taco");
    legacyTaco.setIngredients(Collections.singletonList(new IngredientRequest("FLTO", "Flour Tortilla", Type.WRAP)));
    request.setTacos(Collections.singletonList(legacyTaco));

    StepVerifier.create(controller.postOrder(request, null))
        .assertNext(resp -> {
          assertThat(resp.getTotal()).isEqualByComparingTo(new BigDecimal("0.80"));
          assertThat(resp.getItems()).hasSize(1);
          assertThat(resp.getItems().get(0).getQuantity()).isEqualTo(1);
          assertThat(resp.getItems().get(0).getUnitPriceAtPurchase()).isEqualByComparingTo(new BigDecimal("0.80"));
          assertThat(resp.getTacos()).hasSize(1);
        })
        .verifyComplete();
  }
}
