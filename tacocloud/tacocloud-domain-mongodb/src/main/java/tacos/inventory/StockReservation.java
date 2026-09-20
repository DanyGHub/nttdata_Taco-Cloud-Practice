package tacos.inventory;

import java.io.Serializable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@Document(collection = "stockReservations")
@NoArgsConstructor
@AllArgsConstructor
public class StockReservation implements Serializable {
  private static final long serialVersionUID = 1L;

  @Id
  private String id;

  @Indexed(unique = true)
  private String orderId;

  private ReservationStatus status = ReservationStatus.PENDING;

  private List<ReservedItem> items = new ArrayList<>();

  private Instant createdAt = Instant.now();
  private Instant updatedAt = Instant.now();

  public StockReservation(String id, String orderId, ReservationStatus status, List<ReservedItem> items) {
    this.id = id;
    this.orderId = orderId;
    this.status = status;
    this.items = items != null ? items : new ArrayList<>();
    this.createdAt = Instant.now();
    this.updatedAt = Instant.now();
  }
}
