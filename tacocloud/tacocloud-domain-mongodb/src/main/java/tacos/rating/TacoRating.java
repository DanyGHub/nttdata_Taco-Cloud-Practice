package tacos.rating;

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
@Document(collection = "taco_ratings")
@CompoundIndexes({
    @CompoundIndex(name = "user_taco_rating_unique_idx", def = "{'userId': 1, 'tacoId': 1}", unique = true)
})
public class TacoRating {

  @Id
  private String id;

  @Indexed
  private String userId;

  @Indexed
  private String tacoId;

  private int score;

  @Builder.Default
  private Date createdAt = new Date();

  @Builder.Default
  private Date updatedAt = new Date();

  public TacoRating(String userId, String tacoId, int score) {
    this.userId = userId;
    this.tacoId = tacoId;
    this.score = score;
    this.createdAt = new Date();
    this.updatedAt = new Date();
  }

}
