package tacos.kitchen.dto;

import java.io.Serializable;

import javax.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.order.OrderStatus;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KitchenStatusUpdateRequest implements Serializable {
  private static final long serialVersionUID = 1L;

  @NotNull(message = "Target status is required")
  private OrderStatus status;

  private String reason;
}
