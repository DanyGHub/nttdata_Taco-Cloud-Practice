package tacos.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.bson.Document;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.index.CompoundIndexDefinition;

import tacos.rating.TacoRating;

@DataMongoTest
@DisplayName("TC-22: Pruebas de Persistencia e Índice Único de TacoRating en MongoDB")
public class TacoRatingRepositoryTest {

  @org.springframework.boot.autoconfigure.SpringBootApplication
  static class TestConfig {}

  @Autowired
  private ReactiveMongoTemplate mongoTemplate;

  @Autowired
  private TacoRatingRepository ratingRepo;

  @BeforeEach
  public void setup() {
    mongoTemplate.dropCollection(TacoRating.class).block();

    // Asegurar índice compuesto único userId + tacoId
    mongoTemplate.indexOps(TacoRating.class)
        .ensureIndex(new CompoundIndexDefinition(new Document("userId", 1).append("tacoId", 1)).unique())
        .block();
  }

  @Test
  @DisplayName("Índice compuesto único rechaza creación duplicada de calificación por el mismo usuario y taco")
  public void shouldEnforceUniqueCompoundIndexOnUserIdAndTacoId() {
    TacoRating rating1 = new TacoRating("user-1", "taco-1", 5);
    TacoRating duplicate = new TacoRating("user-1", "taco-1", 3);

    ratingRepo.save(rating1).block();

    assertThatThrownBy(() -> ratingRepo.save(duplicate).block())
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("Permite actualizar la calificación de un usuario existente sin duplicar documentos")
  public void shouldUpdateExistingRatingWithoutDuplicate() {
    TacoRating rating1 = new TacoRating("user-1", "taco-1", 3);
    TacoRating saved = ratingRepo.save(rating1).block();
    assertThat(saved).isNotNull();

    saved.setScore(5);
    TacoRating updated = ratingRepo.save(saved).block();

    assertThat(updated.getScore()).isEqualTo(5);
    Long total = ratingRepo.countByTacoId("taco-1").block();
    assertThat(total).isEqualTo(1L);
  }

  @Test
  @DisplayName("Permite que múltiples usuarios califiquen el mismo taco de forma independiente")
  public void shouldAllowMultipleUsersToRateSameTaco() {
    TacoRating r1 = new TacoRating("user-A", "taco-1", 4);
    TacoRating r2 = new TacoRating("user-B", "taco-1", 5);
    TacoRating r3 = new TacoRating("user-C", "taco-1", 3);

    ratingRepo.save(r1).block();
    ratingRepo.save(r2).block();
    ratingRepo.save(r3).block();

    Long total = ratingRepo.countByTacoId("taco-1").block();
    assertThat(total).isEqualTo(3L);

    TacoRating userARating = ratingRepo.findByUserIdAndTacoId("user-A", "taco-1").block();
    assertThat(userARating).isNotNull();
    assertThat(userARating.getScore()).isEqualTo(4);
  }

  @Test
  @DisplayName("Eliminar calificación por userId y tacoId es idempotente")
  public void shouldDeleteByUserIdAndTacoIdIdempotently() {
    TacoRating r = new TacoRating("user-A", "taco-1", 5);
    ratingRepo.save(r).block();

    assertThat(ratingRepo.existsByUserIdAndTacoId("user-A", "taco-1").block()).isTrue();

    ratingRepo.deleteByUserIdAndTacoId("user-A", "taco-1").block();
    assertThat(ratingRepo.existsByUserIdAndTacoId("user-A", "taco-1").block()).isFalse();

    // Segunda eliminación idempotente
    ratingRepo.deleteByUserIdAndTacoId("user-A", "taco-1").block();
    assertThat(ratingRepo.existsByUserIdAndTacoId("user-A", "taco-1").block()).isFalse();
  }

}
