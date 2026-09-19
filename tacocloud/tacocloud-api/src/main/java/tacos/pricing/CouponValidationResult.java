package tacos.pricing;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class CouponValidationResult {

  private boolean valid;
  private CouponStatus status;
  private String message;
  private String code;
  private DiscountType discountType;
  private BigDecimal discountValue;
  private BigDecimal discountAmount;
  private BigDecimal subtotal;
  private BigDecimal finalTotal;

  public static CouponValidationResult invalid(CouponStatus status, String message, String code, BigDecimal subtotal) {
    return CouponValidationResult.builder()
        .valid(false)
        .status(status)
        .message(message)
        .code(code)
        .discountAmount(BigDecimal.ZERO.setScale(2))
        .subtotal(subtotal != null ? subtotal.setScale(2) : BigDecimal.ZERO.setScale(2))
        .finalTotal(subtotal != null ? subtotal.setScale(2) : BigDecimal.ZERO.setScale(2))
        .build();
  }
}
