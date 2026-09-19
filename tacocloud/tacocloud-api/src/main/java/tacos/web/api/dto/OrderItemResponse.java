package tacos.web.api.dto;

import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItemResponse {

  private TacoResponse taco;
  private int quantity;
  private BigDecimal unitPriceAtPurchase;
  private BigDecimal subtotal;

}
