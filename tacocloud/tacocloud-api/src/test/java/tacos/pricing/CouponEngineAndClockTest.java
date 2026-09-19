package tacos.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.IngredientRepository;
import tacos.web.api.CouponController;
import tacos.web.api.OrderApiController;
import tacos.web.api.dto.CouponValidateRequest;
import tacos.web.api.dto.IngredientRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderQuoteRequest;
import tacos.web.api.dto.TacoRequest;

public class CouponEngineAndClockTest {

  private CouponProperties properties;
  private Clock fixedClock;
  private CouponService couponService;
  private IngredientRepository ingredientRepo;
  private PricingService pricingService;
  private OrderMapper orderMapper;
  private CouponController couponController;
  private OrderApiController orderApiController;

  private final Instant BASE_TIME = Instant.parse("2026-06-15T12:00:00Z");

  @BeforeEach
  public void setUp() {
    properties = new CouponProperties();
    Map<String, CouponRule> couponMap = new HashMap<>();

    // 10% descuento con mínimo $10 y tope $5
    couponMap.put("TACO10", new CouponRule(
        "TACO10",
        DiscountType.PERCENTAGE,
        new BigDecimal("10.00"),
        new BigDecimal("10.00"),
        new BigDecimal("5.00")
    ));

    // $5 fijo con mínimo $15
    couponMap.put("SAVE5", new CouponRule(
        "SAVE5",
        DiscountType.FIXED,
        new BigDecimal("5.00"),
        new BigDecimal("15.00"),
        null
    ));

    // $20 fijo sin mínimo
    couponMap.put("BIG20", new CouponRule(
        "BIG20",
        DiscountType.FIXED,
        new BigDecimal("20.00"),
        BigDecimal.ZERO,
        null
    ));

    // 50% con tope $10
    couponMap.put("HALF50", new CouponRule(
        "HALF50",
        DiscountType.PERCENTAGE,
        new BigDecimal("50.00"),
        BigDecimal.ZERO,
        new BigDecimal("10.00")
    ));

    // Cupón con ventana de vigencia del 1 de Junio al 30 de Junio 2026
    CouponRule timedRule = new CouponRule(
        "SUMMER26",
        DiscountType.PERCENTAGE,
        new BigDecimal("15.00"),
        BigDecimal.ZERO,
        null
    );
    timedRule.setValidFrom(Instant.parse("2026-06-01T00:00:00Z"));
    timedRule.setValidTo(Instant.parse("2026-06-30T23:59:59Z"));
    couponMap.put("SUMMER26", timedRule);

    properties.setCoupons(couponMap);

    fixedClock = Clock.fixed(BASE_TIME, ZoneId.of("UTC"));
    couponService = new CouponService(properties, fixedClock);

    ingredientRepo = mock(IngredientRepository.class);
    pricingService = new PricingService(ingredientRepo, couponService);
    orderMapper = new OrderMapper();

    couponController = new CouponController(couponService);
    orderApiController = new OrderApiController(
        null, null, null, orderMapper, null, null, null, pricingService
    );
  }

  @ParameterizedTest(name = "Tipo {0}: subtotal={1} produce descuento={2} y total={3}")
  @CsvSource({
      "TACO10, 20.00, 2.00, 18.00",
      "TACO10, 35.50, 3.55, 31.95",
      "SAVE5,  15.00, 5.00, 10.00",
      "SAVE5,  25.00, 5.00, 20.00"
  })
  @DisplayName("Pruebas parametrizadas: cupones PERCENTAGE y FIXED calculan valores exactos")
  public void parameterizedDiscountCalculation_shouldProduceExactValues(
      String code, String subtotalStr, String expectedDiscountStr, String expectedTotalStr) {

    BigDecimal subtotal = new BigDecimal(subtotalStr);
    CouponValidationResult result = couponService.validateAndCalculate(code, subtotal);

    assertThat(result.isValid()).isTrue();
    assertThat(result.getStatus()).isEqualTo(CouponStatus.VALID);
    assertThat(result.getDiscountAmount()).isEqualByComparingTo(new BigDecimal(expectedDiscountStr));
    assertThat(result.getFinalTotal()).isEqualByComparingTo(new BigDecimal(expectedTotalStr));
  }

  @Test
  @DisplayName("Prueba de Clock en frontera de día: segundo previo es válido, segundo posterior está expirado")
  public void clockBoundary_shouldDistinguishValidFromExpiredAtExactSecond() {
    // Cupón SUMMER26 expira en 2026-06-30T23:59:59Z
    Instant lastValidSecond = Instant.parse("2026-06-30T23:59:59Z");
    Clock clockAtDeadline = Clock.fixed(lastValidSecond, ZoneId.of("UTC"));
    CouponService serviceAtDeadline = new CouponService(properties, clockAtDeadline);

    CouponValidationResult validResult = serviceAtDeadline.validateAndCalculate("SUMMER26", new BigDecimal("50.00"));
    assertThat(validResult.isValid()).isTrue();
    assertThat(validResult.getStatus()).isEqualTo(CouponStatus.VALID);

    // 1 segundo después: medianoche del 1 de Julio
    Instant expiredSecond = Instant.parse("2026-07-01T00:00:00Z");
    Clock clockExpired = Clock.fixed(expiredSecond, ZoneId.of("UTC"));
    CouponService serviceExpired = new CouponService(properties, clockExpired);

    CouponValidationResult expiredResult = serviceExpired.validateAndCalculate("SUMMER26", new BigDecimal("50.00"));
    assertThat(expiredResult.isValid()).isFalse();
    assertThat(expiredResult.getStatus()).isEqualTo(CouponStatus.EXPIRED);
    assertThat(expiredResult.getMessage()).contains("expired");
  }

  @Test
  @DisplayName("Prueba de Clock previo a inicio de vigencia: retorna NOT_STARTED")
  public void clockBoundary_shouldRejectBeforeValidFrom() {
    Instant beforeStart = Instant.parse("2026-05-31T23:59:59Z");
    Clock clockBefore = Clock.fixed(beforeStart, ZoneId.of("UTC"));
    CouponService serviceBefore = new CouponService(properties, clockBefore);

    CouponValidationResult result = serviceBefore.validateAndCalculate("SUMMER26", new BigDecimal("50.00"));
    assertThat(result.isValid()).isFalse();
    assertThat(result.getStatus()).isEqualTo(CouponStatus.NOT_STARTED);
    assertThat(result.getMessage()).contains("not yet active");
  }

  @Test
  @DisplayName("Límite de descuento máximo: el tope acota porcentajes altos")
  public void maxDiscountAmount_shouldCapHighPercentages() {
    // HALF50 es 50% con maxDiscount = $10.00
    // 50% de $100.00 = $50.00, pero debe toparse a $10.00
    CouponValidationResult result = couponService.validateAndCalculate("HALF50", new BigDecimal("100.00"));

    assertThat(result.isValid()).isTrue();
    assertThat(result.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("10.00"));
    assertThat(result.getFinalTotal()).isEqualByComparingTo(new BigDecimal("90.00"));
  }

  @Test
  @DisplayName("Límite de total no negativo: descuento mayor al subtotal nunca vuelve el total negativo")
  public void discountExceedingSubtotal_shouldNeverProduceNegativeTotal() {
    // BIG20 descuenta $20 fijo. Subtotal es $12.00
    CouponValidationResult result = couponService.validateAndCalculate("BIG20", new BigDecimal("12.00"));

    assertThat(result.isValid()).isTrue();
    assertThat(result.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("12.00"));
    assertThat(result.getFinalTotal()).isEqualByComparingTo(BigDecimal.ZERO);
  }

  @Test
  @DisplayName("Mínimo de compra: subtotal menor al mínimo requerido es rechazado con MIN_PURCHASE_NOT_MET")
  public void minPurchaseNotMet_shouldBeRejected() {
    // SAVE5 requiere mínimo $15.00
    CouponValidationResult result = couponService.validateAndCalculate("SAVE5", new BigDecimal("14.99"));

    assertThat(result.isValid()).isFalse();
    assertThat(result.getStatus()).isEqualTo(CouponStatus.MIN_PURCHASE_NOT_MET);
    assertThat(result.getMessage()).contains("Minimum order amount");
    assertThat(result.getFinalTotal()).isEqualByComparingTo(new BigDecimal("14.99"));
  }

  @Test
  @DisplayName("Normalización de código: espacios y minúsculas se normalizan correctamente")
  public void codeNormalization_shouldBeCaseInsensitiveAndTrimmed() {
    CouponValidationResult resultLower = couponService.validateAndCalculate("  taco10  ", new BigDecimal("20.00"));
    assertThat(resultLower.isValid()).isTrue();
    assertThat(resultLower.getCode()).isEqualTo("TACO10");
    assertThat(resultLower.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("2.00"));
  }

  @Test
  @DisplayName("Seguridad: código desconocido produce respuesta genérica sin enumerar cupones existentes")
  public void unknownCode_shouldReturnGenericInvalidMessage_withoutEnumeratingCodes() {
    CouponValidationResult result = couponService.validateAndCalculate("SECRET_HACK_CODE", new BigDecimal("30.00"));

    assertThat(result.isValid()).isFalse();
    assertThat(result.getStatus()).isEqualTo(CouponStatus.INVALID_CODE);
    assertThat(result.getMessage()).isEqualTo("Invalid or unrecognized coupon code");
    // No debe contener nombres de cupones reales como TACO10 o SAVE5
    assertThat(result.getMessage()).doesNotContain("TACO10");
    assertThat(result.getMessage()).doesNotContain("SAVE5");
  }

  @Test
  @DisplayName("Endpoint POST /api/coupons/validate: valida cupón reactivamente")
  public void couponController_validateCoupon_shouldReturnDetailedValidationResponse() {
    CouponValidateRequest request = new CouponValidateRequest("SAVE5", new BigDecimal("25.00"));

    StepVerifier.create(couponController.validateCoupon(request))
        .assertNext(response -> {
          assertThat(response.isValid()).isTrue();
          assertThat(response.getCode()).isEqualTo("SAVE5");
          assertThat(response.getStatus()).isEqualTo("VALID");
          assertThat(response.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("5.00"));
          assertThat(response.getFinalTotal()).isEqualByComparingTo(new BigDecimal("20.00"));
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Endpoint POST /api/orders/quote: cotiza orden con cupón sin persistir en base de datos")
  public void orderApiController_quoteOrder_shouldCalculateQuoteWithoutSaving() {
    Ingredient flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("1.00"), true, 100, 10, 1L);
    Ingredient grbf = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("2.00"), true, 50, 5, 1L);
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(flto, grbf));

    TacoRequest taco = new TacoRequest("Taco Supreme", Arrays.asList(
        new IngredientRequest("FLTO", "Flour Tortilla", Type.WRAP),
        new IngredientRequest("GRBF", "Ground Beef", Type.PROTEIN)
    ));
    // Taco cuesta 1.00 + 2.00 = 3.00. 10 tacos = 30.00.
    OrderItemRequest item = new OrderItemRequest(taco, 10);

    OrderQuoteRequest quoteRequest = new OrderQuoteRequest();
    quoteRequest.addItem(item);
    quoteRequest.setCouponCode("TACO10"); // 10% de 30.00 = 3.00 de descuento

    StepVerifier.create(orderApiController.quoteOrder(quoteRequest))
        .assertNext(quoteResp -> {
          assertThat(quoteResp.getSubtotal()).isEqualByComparingTo(new BigDecimal("30.00"));
          assertThat(quoteResp.getDiscountAmount()).isEqualByComparingTo(new BigDecimal("3.00"));
          assertThat(quoteResp.getTotal()).isEqualByComparingTo(new BigDecimal("27.00"));
          assertThat(quoteResp.getCurrency()).isEqualTo("USD");
          assertThat(quoteResp.getCouponCode()).isEqualTo("TACO10");
          assertThat(quoteResp.isCouponApplied()).isTrue();
          assertThat(quoteResp.getItems()).hasSize(1);
        })
        .verifyComplete();
  }
}
