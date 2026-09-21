package tacos.order;

import java.io.Serializable;
import java.util.Date;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class OrderStatusHistory implements Serializable {
  private static final long serialVersionUID = 1L;

  private OrderStatus status;
  private Date timestamp;
  private String changedBy;
  private String role;
  private String origin;
  private String reason;
}
