package tacos.pricing;

import java.math.BigDecimal;
import java.time.Instant;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class CouponRule {

  private String code;
  private DiscountType type = DiscountType.PERCENTAGE;
  private BigDecimal value = BigDecimal.ZERO;
  private BigDecimal minOrderAmount = BigDecimal.ZERO;
  private BigDecimal maxDiscountAmount;
  private Instant validFrom;
  private Instant validTo;

  public CouponRule(String code, DiscountType type, BigDecimal value) {
    this.code = code;
    this.type = type;
    this.value = value;
    this.minOrderAmount = BigDecimal.ZERO;
  }

  public CouponRule(String code, DiscountType type, BigDecimal value, BigDecimal minOrderAmount, BigDecimal maxDiscountAmount) {
    this.code = code;
    this.type = type;
    this.value = value;
    this.minOrderAmount = minOrderAmount != null ? minOrderAmount : BigDecimal.ZERO;
    this.maxDiscountAmount = maxDiscountAmount;
  }
}
