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

import tacos.favorites.Favorite;

@DataMongoTest
@DisplayName("TC-21: Pruebas de Persistencia e Índice Único de Favoritos en MongoDB")
public class FavoriteRepositoryTest {

  @org.springframework.boot.autoconfigure.SpringBootApplication
  static class TestConfig {}

  @Autowired
  private ReactiveMongoTemplate mongoTemplate;

  @Autowired
  private FavoriteRepository favoriteRepo;

  @BeforeEach
  public void setup() {
    mongoTemplate.dropCollection(Favorite.class).block();

    // índice compuesto userId + tacoId
    mongoTemplate.indexOps(Favorite.class)
        .ensureIndex(new CompoundIndexDefinition(new Document("userId", 1).append("tacoId", 1)).unique())
        .block();
  }

  @Test
  @DisplayName("Índice único compuesto rechaza duplicados concurrentes de userId y tacoId")
  public void shouldEnforceUniqueCompoundIndexOnUserIdAndTacoId() {
    Favorite fav1 = new Favorite("user-1", "taco-1");
    Favorite favDuplicate = new Favorite("user-1", "taco-1");

    favoriteRepo.save(fav1).block();

    assertThatThrownBy(() -> favoriteRepo.save(favDuplicate).block())
        .isInstanceOf(DuplicateKeyException.class);
  }

  @Test
  @DisplayName("Permite que múltiples usuarios marquen el mismo taco como favorito")
  public void shouldAllowDifferentUsersToFavoriteSameTaco() {
    Favorite favUserA = new Favorite("user-A", "taco-1");
    Favorite favUserB = new Favorite("user-B", "taco-1");

    Favorite savedA = favoriteRepo.save(favUserA).block();
    Favorite savedB = favoriteRepo.save(favUserB).block();

    assertThat(savedA).isNotNull();
    assertThat(savedB).isNotNull();
    assertThat(savedA.getId()).isNotEqualTo(savedB.getId());

    Long count = favoriteRepo.count().block();
    assertThat(count).isEqualTo(2L);
  }

  @Test
  @DisplayName("Permite que un usuario marque múltiples tacos distintos como favoritos")
  public void shouldAllowSameUserToFavoriteDifferentTacos() {
    Favorite fav1 = new Favorite("user-A", "taco-1");
    Favorite fav2 = new Favorite("user-A", "taco-2");

    favoriteRepo.save(fav1).block();
    favoriteRepo.save(fav2).block();

    Long userCount = favoriteRepo.countByUserId("user-A").block();
    assertThat(userCount).isEqualTo(2L);
  }

  @Test
  @DisplayName("Eliminar favorito por userId y tacoId es idempotente")
  public void shouldDeleteByUserIdAndTacoIdIdempotently() {
    Favorite fav = new Favorite("user-A", "taco-1");
    favoriteRepo.save(fav).block();

    assertThat(favoriteRepo.existsByUserIdAndTacoId("user-A", "taco-1").block()).isTrue();

    favoriteRepo.deleteByUserIdAndTacoId("user-A", "taco-1").block();
    assertThat(favoriteRepo.existsByUserIdAndTacoId("user-A", "taco-1").block()).isFalse();

    favoriteRepo.deleteByUserIdAndTacoId("user-A", "taco-1").block();
    assertThat(favoriteRepo.existsByUserIdAndTacoId("user-A", "taco-1").block()).isFalse();
  }

}
