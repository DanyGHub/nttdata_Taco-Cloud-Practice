package tacos;

import java.io.Serializable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;
import tacos.order.OrderStatus;
import tacos.order.OrderStatusHistory;

@Data
@Document
public class TacoOrder implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;

  @Version
  private Long version;

  private Date placedAt = new Date();

  private OrderStatus status = OrderStatus.CREATED;
  private List<OrderStatusHistory> statusHistory = new ArrayList<>();

  private String stationId;
  private String cookId;
  private Integer estimatedPrepMinutes;
  private Date acceptedAt;

  @Indexed(sparse = true)
  private String idempotencyKey;

  private User user;

  private String deliveryName;

  private String deliveryStreet;

  private String deliveryCity;

  private String deliveryState;

  private String deliveryZip;

  private String paymentMethodId;
  @com.fasterxml.jackson.annotation.JsonIgnore
  private String paymentToken;
  private String brand;
  private String last4;

  private List<Taco> tacos = new ArrayList<>();
  private List<OrderItem> items = new ArrayList<>();

  private BigDecimal subtotal = BigDecimal.ZERO;
  private BigDecimal total = BigDecimal.ZERO;
  private String currency = "USD";
  private String couponCode;
  private BigDecimal discountAmount = BigDecimal.ZERO;

  public void addTaco(Taco design) {
    this.tacos.add(design);
  }

  public void addOrderItem(OrderItem item) {
    if (item != null) {
      this.items.add(item);
      if (item.getTaco() != null && !this.tacos.contains(item.getTaco())) {
        this.tacos.add(item.getTaco());
      }
    }
  }

  public void recordStatusChange(OrderStatus newStatus, String changedBy, String role, String origin, String reason) {
    this.status = newStatus;
    if (this.statusHistory == null) {
      this.statusHistory = new ArrayList<>();
    }
    this.statusHistory.add(new OrderStatusHistory(newStatus, new Date(), changedBy, role, origin, reason));
  }

}
