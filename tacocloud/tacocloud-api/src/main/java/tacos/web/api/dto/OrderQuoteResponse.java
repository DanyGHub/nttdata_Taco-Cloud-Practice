package tacos.web.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderQuoteResponse {

  private BigDecimal subtotal;
  private BigDecimal discountAmount;
  private BigDecimal total;
  private String currency;
  private String couponCode;
  private boolean couponApplied;
  private String couponMessage;
  private List<OrderItemResponse> items = new ArrayList<>();
}
