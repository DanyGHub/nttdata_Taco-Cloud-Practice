package tacos.idempotency;

import java.time.Instant;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.web.api.dto.OrderResponse;

/**
 * Registro duradero de idempotencia en MongoDB para evitar duplicación de órdenes.
 */
@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "idempotency_records")
@CompoundIndexes({
    @CompoundIndex(name = "user_key_unique_idx", def = "{'userId': 1, 'key': 1}", unique = true)
})
public class IdempotencyRecord {

  @Id
  private String id;

  private String key;

  private String userId;

  private String requestHash;

  private String orderId;

  private IdempotencyStatus status;

  private OrderResponse response;

  private Instant createdAt;

  private Instant expiresAt;

  private String errorMessage;

}
