package tacos.messaging;

import java.util.Date;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * Envelope canónico versionado para eventos del ciclo de vida de una orden.
 * Independiente de brokers de mensajería específicos y bases de datos.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderEvent {

  @Builder.Default
  private String eventId = UUID.randomUUID().toString();

  private OrderEventType eventType;

  @Builder.Default
  private int version = 1;

  @JsonFormat(shape = JsonFormat.Shape.STRING, pattern = "yyyy-MM-dd'T'HH:mm:ss.SSSXXX", timezone = "UTC")
  @Builder.Default
  private Date occurredAt = new Date();

  private String correlationId;

  private OrderEventPayload payload;

  /**
   * Método factoría estático para instanciar eventos canónicos de versión 1.
   */
  public static OrderEvent of(OrderEventType eventType, String correlationId, OrderEventPayload payload) {
    return OrderEvent.builder()
        .eventId(UUID.randomUUID().toString())
        .eventType(eventType)
        .version(1)
        .occurredAt(new Date())
        .correlationId(correlationId)
        .payload(payload)
        .build();
  }

}
