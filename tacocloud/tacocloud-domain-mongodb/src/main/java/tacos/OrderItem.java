package tacos;

import java.io.Serializable;
import java.math.BigDecimal;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderItem implements Serializable {
  private static final long serialVersionUID = 1L;

  private Taco taco;
  private int quantity = 1;
  private BigDecimal unitPriceAtPurchase = BigDecimal.ZERO;
  private BigDecimal subtotal = BigDecimal.ZERO;

  public OrderItem(Taco taco, int quantity) {
    this.taco = taco;
    this.quantity = quantity;
    this.unitPriceAtPurchase = BigDecimal.ZERO;
    this.subtotal = BigDecimal.ZERO;
  }
}
