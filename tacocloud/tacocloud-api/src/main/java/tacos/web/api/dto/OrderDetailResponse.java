package tacos.web.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderDetailResponse {

  private String id;
  private Date placedAt;
  private String deliveryName;
  private String deliveryStreet;
  private String deliveryCity;
  private String deliveryState;
  private String deliveryZip;
  private String username;
  private String brand;
  private String last4;

  @Builder.Default
  private List<OrderItemResponse> items = new ArrayList<>();

  @Builder.Default
  private List<TacoResponse> tacos = new ArrayList<>();

  private BigDecimal subtotal;
  private BigDecimal discountAmount;
  private BigDecimal total;
  @Builder.Default
  private String currency = "USD";
  private String couponCode;
  private String status;

}
