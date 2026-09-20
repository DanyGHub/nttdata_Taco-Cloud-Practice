package tacos.favorites;

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

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@Document(collection = "favorites")
@CompoundIndexes({
    @CompoundIndex(name = "user_taco_unique_idx", def = "{'userId': 1, 'tacoId': 1}", unique = true)
})
public class Favorite {

  @Id
  private String id;

  @Indexed
  private String userId;

  @Indexed
  private String tacoId;

  @Builder.Default
  private Date createdAt = new Date();

  public Favorite(String userId, String tacoId) {
    this.userId = userId;
    this.tacoId = tacoId;
    this.createdAt = new Date();
  }

}
