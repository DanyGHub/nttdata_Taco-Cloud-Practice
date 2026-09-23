package tacos.kitchen.domain;

import java.util.Date;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Document(collection = "processed_events")
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ProcessedEvent {

  @Id
  private String id;

  @Indexed(unique = true)
  private String eventId;

  private String eventType;

  private Integer version;

  private String orderId;

  private String correlationId;

  private Date processedAt;

  private ProcessedStatus status;

  private String resultSummary;

  private String errorMessage;

}
