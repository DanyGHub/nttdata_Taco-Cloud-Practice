package tacos.web.api.dto;

import java.math.BigDecimal;
import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderSummaryResponse {

  private String id;
  private Date placedAt;
  private String deliveryName;
  private String deliveryCity;
  private String deliveryState;
  private int itemCount;
  private BigDecimal total;
  private String currency;
  private String brand;
  private String last4;
  private tacos.order.OrderStatus status;
  private Long version;

}
