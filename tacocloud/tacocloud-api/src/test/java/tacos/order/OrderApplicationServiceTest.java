package tacos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.OrderItem;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.inventory.InsufficientStockException;
import tacos.inventory.InventoryService;
import tacos.inventory.StockReservation;
import tacos.messaging.OrderMessagingService;
import tacos.physics.TacoDesignValidator;
import tacos.pricing.PricingService;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.ReorderRequest;
import tacos.web.api.dto.ReorderResponse;

@ExtendWith(MockitoExtension.class)
public class OrderApplicationServiceTest {

  @Mock
  private OrderRepository orderRepo;

  @Mock
  private OrderMessagingService orderMessages;

  @Mock
  private UserRepository userRepo;

  @Mock
  private PaymentMethodRepository paymentMethodRepo;

  @Mock
  private PricingService pricingService;

  @Mock
  private InventoryService inventoryService;

  @Mock
  private TacoDesignValidator designValidator;

  @Mock
  private IngredientRepository ingredientRepo;

  private OrderMapper orderMapper = new OrderMapper();
  private OrderApplicationService service;

  private User userA;
  private User userB;
  private TacoOrder originalOrder;
  private PaymentMethod paymentMethodA;
  private Authentication authA;
  private Authentication authB;

  @BeforeEach
  public void setUp() {
    service = new OrderApplicationService(
        orderRepo,
        orderMessages,
        null,
        orderMapper,
        userRepo,
        paymentMethodRepo,
        pricingService,
        inventoryService,
        designValidator,
        ingredientRepo
    );

    userA = new User("userA", "passA", "User A", "Street A", "City", "State", "12345", "111", "a@test.com", Collections.singletonList("ROLE_USER"));
    userA.setId("userA-id");

    userB = new User("userB", "passB", "User B", "Street B", "City", "State", "54321", "222", "b@test.com", Collections.singletonList("ROLE_USER"));
    userB.setId("userB-id");

    authA = new UsernamePasswordAuthenticationToken("userA", "passA", Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    authB = new UsernamePasswordAuthenticationToken("userB", "passB", Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

    paymentMethodA = new PaymentMethod(userA, "tok_userA_123", "VISA", "4242", "12/28");
    paymentMethodA.setId("pm-A-1");

    Ingredient flourTortilla = new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP);
    flourTortilla.setUnitPrice(new BigDecimal("1.50"));
    Ingredient carnitas = new Ingredient("CARN", "Carnitas", Ingredient.Type.PROTEIN);
    carnitas.setUnitPrice(new BigDecimal("3.50"));

    Taco taco = new Taco();
    taco.setName("Carnitas Classic");
    taco.setIngredients(Arrays.asList(flourTortilla, carnitas));

    originalOrder = new TacoOrder();
    originalOrder.setId("orig-order-1");
    originalOrder.setPlacedAt(new Date(System.currentTimeMillis() - 86400000L)); // 1 día atrás
    originalOrder.setUser(userA);
    originalOrder.setDeliveryName("User A");
    originalOrder.setDeliveryStreet("Street A");
    originalOrder.setDeliveryCity("City");
    originalOrder.setDeliveryState("State");
    originalOrder.setDeliveryZip("12345");
    originalOrder.setSubtotal(new BigDecimal("10.00"));
    originalOrder.setTotal(new BigDecimal("10.00"));

    OrderItem item = new OrderItem(taco, 2);
    item.setUnitPriceAtPurchase(new BigDecimal("5.00"));
    item.setSubtotal(new BigDecimal("10.00"));
    originalOrder.addOrderItem(item);
  }

  @Test
  @DisplayName("1. TC-24 Nueva Identidad y Estado: Reordenar genera ID, fecha y método de pago nuevos sin alterar orden original")
  public void testReorder_NewIdentityAndState() {
    when(orderRepo.findById("orig-order-1")).thenReturn(Mono.just(originalOrder));
    when(userRepo.findByUsername("userA")).thenReturn(Mono.just(userA));
    when(paymentMethodRepo.findById("pm-A-1")).thenReturn(Mono.just(paymentMethodA));
    when(designValidator.validateTacoAndThrow(any())).thenReturn(Mono.empty());

    // Pricing sin cambios: sigue siendo 10.00
    when(pricingService.calculateAndApplyPricing(any())).thenAnswer(inv -> {
      TacoOrder ord = inv.getArgument(0);
      ord.setSubtotal(new BigDecimal("10.00"));
      ord.setTotal(new BigDecimal("10.00"));
      return Mono.just(ord);
    });

    when(inventoryService.reserve(any())).thenReturn(Mono.just(new StockReservation()));
    when(orderRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    ReorderRequest req = ReorderRequest.builder()
        .paymentMethodId("pm-A-1")
        .build();

    Mono<ResponseEntity<ReorderResponse>> result = service.reorder("orig-order-1", req, authA, null);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
          ReorderResponse body = resp.getBody();
          assertThat(body).isNotNull();
          assertThat(body.getStatus()).isEqualTo("CREATED");
          assertThat(body.isPriceChanged()).isFalse();
          assertThat(body.getOrder()).isNotNull();

          // Nueva identidad
          assertThat(body.getOrder().getId()).isNotEqualTo("orig-order-1");
          // Nueva fecha (mayor a la original)
          assertThat(body.getOrder().getPlacedAt().getTime()).isGreaterThan(originalOrder.getPlacedAt().getTime());
          // Nuevo pago asignado
          assertThat(body.getOrder().getBrand()).isEqualTo("VISA");
          assertThat(body.getOrder().getLast4()).isEqualTo("4242");

          // Orden original permanece INMUTABLE
          assertThat(originalOrder.getId()).isEqualTo("orig-order-1");
          assertThat(originalOrder.getTotal()).isEqualByComparingTo(new BigDecimal("10.00"));
        })
        .verifyComplete();

    verify(orderMessages).sendOrder(any());
  }

  @Test
  @DisplayName("2a. TC-24 Cambio de precio sin confirmación: Retorna 409 CONFLICT con quote y diferencias; no persiste orden")
  public void testReorder_PriceChangeRequiresConfirmation() {
    when(orderRepo.findById("orig-order-1")).thenReturn(Mono.just(originalOrder));
    when(userRepo.findByUsername("userA")).thenReturn(Mono.just(userA));
    when(designValidator.validateTacoAndThrow(any())).thenReturn(Mono.empty());

    // Precios aumentaron: de 10.00 a 12.50
    when(pricingService.calculateAndApplyPricing(any())).thenAnswer(inv -> {
      TacoOrder ord = inv.getArgument(0);
      ord.setSubtotal(new BigDecimal("12.50"));
      ord.setTotal(new BigDecimal("12.50"));
      return Mono.just(ord);
    });

    ReorderRequest req = ReorderRequest.builder()
        .paymentMethodId("pm-A-1")
        .confirmPriceChange(false)
        .build();

    Mono<ResponseEntity<ReorderResponse>> result = service.reorder("orig-order-1", req, authA, null);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
          ReorderResponse body = resp.getBody();
          assertThat(body).isNotNull();
          assertThat(body.getStatus()).isEqualTo("PRICE_CHANGE_REQUIRED");
          assertThat(body.isPriceChanged()).isTrue();
          assertThat(body.getOldTotal()).isEqualByComparingTo(new BigDecimal("10.00"));
          assertThat(body.getNewTotal()).isEqualByComparingTo(new BigDecimal("12.50"));
          assertThat(body.getPriceDifference()).isEqualByComparingTo(new BigDecimal("2.50"));
          assertThat(body.getNewQuote()).isNotNull();
          assertThat(body.getNewQuote().getTotal()).isEqualByComparingTo(new BigDecimal("12.50"));
          assertThat(body.getOrder()).isNull(); // No se creó orden
        })
        .verifyComplete();

    // No se reservó inventario ni se guardó en repo
    verify(inventoryService, never()).reserve(any());
    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("2b. TC-24 Cambio de precio confirmado: Con confirmPriceChange=true crea nueva orden con precios vigentes (201 CREATED)")
  public void testReorder_PriceChangeConfirmed() {
    when(orderRepo.findById("orig-order-1")).thenReturn(Mono.just(originalOrder));
    when(userRepo.findByUsername("userA")).thenReturn(Mono.just(userA));
    when(paymentMethodRepo.findById("pm-A-1")).thenReturn(Mono.just(paymentMethodA));
    when(designValidator.validateTacoAndThrow(any())).thenReturn(Mono.empty());

    // Precios aumentaron: de 10.00 a 12.50
    when(pricingService.calculateAndApplyPricing(any())).thenAnswer(inv -> {
      TacoOrder ord = inv.getArgument(0);
      ord.setSubtotal(new BigDecimal("12.50"));
      ord.setTotal(new BigDecimal("12.50"));
      return Mono.just(ord);
    });

    when(inventoryService.reserve(any())).thenReturn(Mono.just(new StockReservation()));
    when(orderRepo.save(any())).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    ReorderRequest req = ReorderRequest.builder()
        .paymentMethodId("pm-A-1")
        .confirmPriceChange(true) // Confirmado explícitamente
        .build();

    Mono<ResponseEntity<ReorderResponse>> result = service.reorder("orig-order-1", req, authA, null);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.CREATED);
          ReorderResponse body = resp.getBody();
          assertThat(body).isNotNull();
          assertThat(body.getStatus()).isEqualTo("CREATED");
          assertThat(body.isPriceChanged()).isTrue();
          assertThat(body.getOldTotal()).isEqualByComparingTo(new BigDecimal("10.00"));
          assertThat(body.getNewTotal()).isEqualByComparingTo(new BigDecimal("12.50"));
          assertThat(body.getOrder()).isNotNull();
          assertThat(body.getOrder().getTotal()).isEqualByComparingTo(new BigDecimal("12.50"));
        })
        .verifyComplete();

    verify(orderRepo).save(any());
    verify(orderMessages).sendOrder(any());
  }

  @Test
  @DisplayName("3. TC-24 Stock agotado: Ingrediente sin stock rechaza la reordenación con InsufficientStockException")
  public void testReorder_InsufficientStock() {
    when(orderRepo.findById("orig-order-1")).thenReturn(Mono.just(originalOrder));
    when(userRepo.findByUsername("userA")).thenReturn(Mono.just(userA));
    when(paymentMethodRepo.findById("pm-A-1")).thenReturn(Mono.just(paymentMethodA));
    when(designValidator.validateTacoAndThrow(any())).thenReturn(Mono.empty());

    when(pricingService.calculateAndApplyPricing(any())).thenAnswer(inv -> {
      TacoOrder ord = inv.getArgument(0);
      ord.setSubtotal(new BigDecimal("10.00"));
      ord.setTotal(new BigDecimal("10.00"));
      return Mono.just(ord);
    });

    // Stock agotado al intentar reservar
    when(inventoryService.reserve(any()))
        .thenReturn(Mono.error(new InsufficientStockException("CARN", 2, 0)));

    ReorderRequest req = ReorderRequest.builder()
        .paymentMethodId("pm-A-1")
        .build();

    Mono<ResponseEntity<ReorderResponse>> result = service.reorder("orig-order-1", req, authA, null);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof InsufficientStockException &&
            ((InsufficientStockException) err).getIngredientId().equals("CARN"))
        .verify();

    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("4. TC-24 Ownership: Usuario B intentando reordenar orden ajena de Usuario A recibe 404 Not Found")
  public void testReorder_OwnershipDenied() {
    when(orderRepo.findById("orig-order-1")).thenReturn(Mono.just(originalOrder));

    ReorderRequest req = ReorderRequest.builder()
        .paymentMethodId("pm-B-1")
        .build();

    // userB intentando reordenar la orden de userA
    Mono<ResponseEntity<ReorderResponse>> result = service.reorder("orig-order-1", req, authB, null);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof ResponseStatusException &&
            ((ResponseStatusException) err).getStatus() == HttpStatus.NOT_FOUND)
        .verify();

    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("5. TC-24 Idempotencia: Llamadas sucesivas con la misma Idempotency-Key retornan la orden existente sin duplicar")
  public void testReorder_IdempotentRetry() {
    TacoOrder previouslyCreated = new TacoOrder();
    previouslyCreated.setId("reordered-ord-99");
    previouslyCreated.setUser(userA);
    previouslyCreated.setTotal(new BigDecimal("10.00"));
    previouslyCreated.setIdempotencyKey("idem-key-abc");

    when(orderRepo.findByIdempotencyKey("idem-key-abc")).thenReturn(Mono.just(previouslyCreated));

    ReorderRequest req = ReorderRequest.builder()
        .paymentMethodId("pm-A-1")
        .idempotencyKey("idem-key-abc")
        .build();

    Mono<ResponseEntity<ReorderResponse>> result = service.reorder("orig-order-1", req, authA, "idem-key-abc");

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
          ReorderResponse body = resp.getBody();
          assertThat(body).isNotNull();
          assertThat(body.getStatus()).isEqualTo("CREATED");
          assertThat(body.getMessage()).contains("idempotent");
          assertThat(body.getOrder().getId()).isEqualTo("reordered-ord-99");
        })
        .verifyComplete();

    // No se reservó ni se guardó nada nuevo
    verify(inventoryService, never()).reserve(any());
    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("6. TC-24 Validación de Método de Pago: paymentMethodId inválido o ajeno es rechazado con 400 Bad Request")
  public void testReorder_InvalidPaymentMethod() {
    when(orderRepo.findById("orig-order-1")).thenReturn(Mono.just(originalOrder));
    when(userRepo.findByUsername("userA")).thenReturn(Mono.just(userA));
    when(designValidator.validateTacoAndThrow(any())).thenReturn(Mono.empty());

    when(pricingService.calculateAndApplyPricing(any())).thenAnswer(inv -> {
      TacoOrder ord = inv.getArgument(0);
      ord.setSubtotal(new BigDecimal("10.00"));
      ord.setTotal(new BigDecimal("10.00"));
      return Mono.just(ord);
    });

    // Método de pago inexistente
    when(paymentMethodRepo.findById("non-existent-pm")).thenReturn(Mono.empty());

    ReorderRequest req = ReorderRequest.builder()
        .paymentMethodId("non-existent-pm")
        .build();

    Mono<ResponseEntity<ReorderResponse>> result = service.reorder("orig-order-1", req, authA, null);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof ResponseStatusException &&
            ((ResponseStatusException) err).getStatus() == HttpStatus.BAD_REQUEST)
        .verify();

    verify(orderRepo, never()).save(any());
  }

}
