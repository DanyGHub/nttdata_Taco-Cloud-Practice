package tacos.kitchen.domain;

import java.util.Date;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collection = "kitchen_orders")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KitchenOrder {

  @Id
  private String id;

  @Indexed(unique = true)
  private String orderId;

  private String status;

  private String customerName;

  private Date placedAt;

  private List<KitchenOrderItem> items;

  private String stationId;

  private String cookId;

  private String lastProcessedEventId;

  private Date createdAt;

  private Date updatedAt;

}
