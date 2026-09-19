package tacos.web.api.dto;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.pricing.DiscountType;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class CouponValidateResponse {

  private boolean valid;
  private String code;
  private String status;
  private String message;
  private DiscountType discountType;
  private BigDecimal discountValue;
  private BigDecimal discountAmount;
  private BigDecimal subtotal;
  private BigDecimal finalTotal;
  private String currency;
}
