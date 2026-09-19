package tacos.web.api.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class CouponValidateRequest {

  @NotBlank(message = "Coupon code is required")
  private String code;

  @NotNull(message = "Subtotal is required")
  @DecimalMin(value = "0.00", message = "Subtotal cannot be negative")
  private BigDecimal subtotal;
}
