package tacos.kitchen.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.order.OrderStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KitchenOrderDto implements Serializable {
  private static final long serialVersionUID = 1L;

  private String id;
  private Date placedAt;
  private OrderStatus status;
  private String stationId;
  private String cookId;
  private Integer queuePosition;
  private Integer estimatedPrepMinutes;
  private String customerName;
  @Builder.Default
  private List<KitchenItemDto> items = new ArrayList<>();
  private Long version;
}
