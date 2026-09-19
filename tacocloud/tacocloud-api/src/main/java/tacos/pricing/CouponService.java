package tacos.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Clock;
import java.time.Instant;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

@Service
public class CouponService {

  private static final Logger log = LoggerFactory.getLogger(CouponService.class);
  public static final RoundingMode DEFAULT_ROUNDING_MODE = RoundingMode.HALF_UP;

  private final CouponProperties properties;
  private final Clock clock;

  @Autowired
  public CouponService(CouponProperties properties, Clock clock) {
    this.properties = properties != null ? properties : new CouponProperties();
    this.clock = clock != null ? clock : Clock.systemDefaultZone();
  }

  public CouponService(CouponProperties properties) {
    this(properties, Clock.systemDefaultZone());
  }

  public String normalizeCode(String code) {
    return code != null ? code.trim().toUpperCase() : "";
  }

  public CouponValidationResult validateAndCalculate(String rawCode, BigDecimal subtotal) {
    BigDecimal orderSubtotal = (subtotal != null ? subtotal : BigDecimal.ZERO).setScale(2, DEFAULT_ROUNDING_MODE);

    if (rawCode == null || rawCode.trim().isEmpty()) {
      return CouponValidationResult.invalid(
          CouponStatus.INVALID_CODE,
          "Coupon code is required",
          "",
          orderSubtotal
      );
    }

    String code = normalizeCode(rawCode);
    CouponRule rule = properties.findCoupon(code);

    if (rule == null) {
      log.debug("Unrecognized coupon code attempt: {}", code);
      return CouponValidationResult.invalid(
          CouponStatus.INVALID_CODE,
          "Invalid or unrecognized coupon code",
          code,
          orderSubtotal
      );
    }

    Instant now = clock.instant();

    // 1. Vigencia: No iniciado
    if (rule.getValidFrom() != null && now.isBefore(rule.getValidFrom())) {
      log.info("Coupon {} rejected: not active yet (validFrom={})", code, rule.getValidFrom());
      return CouponValidationResult.invalid(
          CouponStatus.NOT_STARTED,
          "Coupon is not yet active",
          code,
          orderSubtotal
      );
    }

    // 2. Vigencia: Expirado
    if (rule.getValidTo() != null && now.isAfter(rule.getValidTo())) {
      log.info("Coupon {} rejected: expired (validTo={})", code, rule.getValidTo());
      return CouponValidationResult.invalid(
          CouponStatus.EXPIRED,
          "Coupon has expired",
          code,
          orderSubtotal
      );
    }

    // 3. Mínimo de compra
    if (rule.getMinOrderAmount() != null && orderSubtotal.compareTo(rule.getMinOrderAmount()) < 0) {
      log.info("Coupon {} rejected: subtotal {} < minOrderAmount {}", code, orderSubtotal, rule.getMinOrderAmount());
      return CouponValidationResult.invalid(
          CouponStatus.MIN_PURCHASE_NOT_MET,
          "Minimum order amount of $" + rule.getMinOrderAmount().setScale(2, DEFAULT_ROUNDING_MODE) + " not reached",
          code,
          orderSubtotal
      );
    }

    // 4. Cálculo de descuento según tipo
    BigDecimal discountAmount;
    if (rule.getType() == DiscountType.PERCENTAGE) {
      BigDecimal percentage = rule.getValue() != null ? rule.getValue() : BigDecimal.ZERO;
      discountAmount = orderSubtotal.multiply(percentage)
          .divide(BigDecimal.valueOf(100), 2, DEFAULT_ROUNDING_MODE);

      // Descuento máximo aplicable a porcentaje
      if (rule.getMaxDiscountAmount() != null && discountAmount.compareTo(rule.getMaxDiscountAmount()) > 0) {
        discountAmount = rule.getMaxDiscountAmount().setScale(2, DEFAULT_ROUNDING_MODE);
      }
    } else { // FIXED
      discountAmount = (rule.getValue() != null ? rule.getValue() : BigDecimal.ZERO)
          .setScale(2, DEFAULT_ROUNDING_MODE);
    }

    // 5. El descuento nunca vuelve negativo el total
    if (discountAmount.compareTo(orderSubtotal) > 0) {
      discountAmount = orderSubtotal;
    }

    BigDecimal finalTotal = orderSubtotal.subtract(discountAmount)
        .max(BigDecimal.ZERO)
        .setScale(2, DEFAULT_ROUNDING_MODE);

    log.info("Coupon {} applied: discount={}, subtotal={}, finalTotal={}",
        code, discountAmount, orderSubtotal, finalTotal);

    return CouponValidationResult.builder()
        .valid(true)
        .status(CouponStatus.VALID)
        .message("Coupon applied successfully")
        .code(code)
        .discountType(rule.getType())
        .discountValue(rule.getValue())
        .discountAmount(discountAmount)
        .subtotal(orderSubtotal)
        .finalTotal(finalTotal)
        .build();
  }
}
