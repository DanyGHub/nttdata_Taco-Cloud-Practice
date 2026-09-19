package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class OrderResponse {

  private String id;
  private Date placedAt;
  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;
  private List<TacoResponse> tacos = new ArrayList<>();
  private String username;
  private String brand;
  private String last4;

  private List<OrderItemResponse> items = new ArrayList<>();
  private java.math.BigDecimal subtotal;
  private java.math.BigDecimal discountAmount;
  private java.math.BigDecimal total;
  private String currency = "USD";
  private String couponCode;

}
