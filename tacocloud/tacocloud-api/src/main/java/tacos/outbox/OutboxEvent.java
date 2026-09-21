package tacos.outbox;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.messaging.OrderEventPayload;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "outbox_events")
@CompoundIndexes({
    @CompoundIndex(name = "status_nextAttempt_idx", def = "{'status': 1, 'nextAttemptAt': 1}"),
    @CompoundIndex(name = "status_lockedUntil_idx", def = "{'status': 1, 'lockedUntil': 1}")
})
public class OutboxEvent {

  @Id
  private String id;

  @Indexed
  private String eventId;

  private String eventType;

  private int version;

  @Indexed
  private String correlationId;

  private OrderEventPayload payload;

  @Indexed
  private OutboxStatus status;

  private int attempts;

  private int maxAttempts;

  private Date createdAt;

  private Date updatedAt;

  @Indexed
  private Date nextAttemptAt;

  @Indexed
  private Date lockedUntil;

  private String lockedBy;

  private String lastError;

}
