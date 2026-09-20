package tacos.rating;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.aggregation.Aggregation;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.data.TacoRatingRepository;
import tacos.data.TacoRepository;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.TacoRatingSummaryResponse;
import tacos.web.api.dto.TopTacoResponse;

@DisplayName("TC-22: TacoRatingService")
public class TacoRatingServiceTest {

  private TacoRatingRepository ratingRepo;
  private TacoRepository tacoRepo;
  private ReactiveMongoTemplate mongoTemplate;
  private TacoRatingService ratingService;

  @BeforeEach
  public void setUp() {
    ratingRepo = mock(TacoRatingRepository.class);
    tacoRepo = mock(TacoRepository.class);
    mongoTemplate = mock(ReactiveMongoTemplate.class);

    ratingService = new TacoRatingService(
        ratingRepo,
        tacoRepo,
        mongoTemplate,
        new IngredientMapper(),
        null,
        2 // defaultMinVotes = 2
    );
  }

  @Test
  @DisplayName("Rechaza calificación con puntuación fuera del rango 1 a 5 con 400 Bad Request")
  public void shouldRejectRatingWhenScoreOutOfRange() {
    StepVerifier.create(ratingService.submitRating("user-1", "taco-1", 0))
        .expectErrorMatches(ex -> ex instanceof ResponseStatusException &&
            ((ResponseStatusException) ex).getRawStatusCode() == 400)
        .verify();

    StepVerifier.create(ratingService.submitRating("user-1", "taco-1", 6))
        .expectErrorMatches(ex -> ex instanceof ResponseStatusException &&
            ((ResponseStatusException) ex).getRawStatusCode() == 400)
        .verify();
  }

  @Test
  @DisplayName("Rechaza calificación cuando el taco no existe en catálogo con 404 Not Found")
  public void shouldRejectRatingWhenTacoDoesNotExist() {
    when(tacoRepo.findById("missing-taco")).thenReturn(Mono.empty());

    StepVerifier.create(ratingService.submitRating("user-1", "missing-taco", 5))
        .expectErrorMatches(ex -> ex instanceof ResponseStatusException &&
            ((ResponseStatusException) ex).getRawStatusCode() == 404)
        .verify();
  }

  @Test
  @DisplayName("Rechaza calificación cuando el taco no está publicado con 400 Bad Request")
  public void shouldRejectRatingWhenTacoIsNotPublished() {
    Taco unpublishedTaco = new Taco();
    unpublishedTaco.setId("unpub-taco");
    unpublishedTaco.setName("Secret Recipe");
    unpublishedTaco.setPublished(false);

    when(tacoRepo.findById("unpub-taco")).thenReturn(Mono.just(unpublishedTaco));

    StepVerifier.create(ratingService.submitRating("user-1", "unpub-taco", 5))
        .expectErrorMatches(ex -> ex instanceof ResponseStatusException &&
            ((ResponseStatusException) ex).getRawStatusCode() == 400)
        .verify();
  }

  @Test
  @DisplayName("Primer voto crea nueva calificación con inserción exitosa")
  public void shouldSubmitNewRatingSuccessfully() {
    Taco taco = new Taco();
    taco.setId("taco-1");
    taco.setName("Carnitas Supreme");
    taco.setPublished(true);

    when(tacoRepo.findById("taco-1")).thenReturn(Mono.just(taco));
    when(ratingRepo.findByUserIdAndTacoId("user-1", "taco-1"))
        .thenReturn(Mono.empty())
        .thenReturn(Mono.just(new TacoRating("user-1", "taco-1", 5)));
    when(ratingRepo.insert(any(TacoRating.class))).thenAnswer(inv -> {
      TacoRating tr = inv.getArgument(0);
      tr.setId("rating-101");
      return Mono.just(tr);
    });

    TacoRatingService.TacoRatingAggregationResult agg = new TacoRatingService.TacoRatingAggregationResult();
    agg.setId("taco-1");
    agg.setTotalVotes(1L);
    agg.setAverageScore(5.0);
    agg.setScore5(1L);

    when(mongoTemplate.aggregate(any(Aggregation.class), eq(TacoRatingService.RATINGS_COLLECTION), eq(TacoRatingService.TacoRatingAggregationResult.class)))
        .thenReturn(Flux.just(agg));

    StepVerifier.create(ratingService.submitRating("user-1", "taco-1", 5))
        .assertNext(summary -> {
          assertThat(summary.getTacoId()).isEqualTo("taco-1");
          assertThat(summary.getAverageScore()).isEqualTo(5.0);
          assertThat(summary.getTotalVotes()).isEqualTo(1L);
          assertThat(summary.getDistribution().get(5)).isEqualTo(1L);
          assertThat(summary.getUserScore()).isEqualTo(5);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Repetir PUT actualiza voto del usuario y NO aumenta el conteo total")
  public void shouldUpdateExistingRatingWithoutIncreasingTotalCount() {
    Taco taco = new Taco();
    taco.setId("taco-1");
    taco.setName("Carnitas Supreme");
    taco.setPublished(true);

    TacoRating existingRating = new TacoRating("user-1", "taco-1", 3);
    existingRating.setId("rating-101");

    when(tacoRepo.findById("taco-1")).thenReturn(Mono.just(taco));
    when(ratingRepo.findByUserIdAndTacoId("user-1", "taco-1")).thenReturn(Mono.just(existingRating));
    when(ratingRepo.save(any(TacoRating.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    TacoRatingService.TacoRatingAggregationResult agg = new TacoRatingService.TacoRatingAggregationResult();
    agg.setId("taco-1");
    agg.setTotalVotes(1L); // total count stays 1!
    agg.setAverageScore(4.0);
    agg.setScore4(1L);

    when(mongoTemplate.aggregate(any(Aggregation.class), eq(TacoRatingService.RATINGS_COLLECTION), eq(TacoRatingService.TacoRatingAggregationResult.class)))
        .thenReturn(Flux.just(agg));

    StepVerifier.create(ratingService.submitRating("user-1", "taco-1", 4))
        .assertNext(summary -> {
          assertThat(summary.getTacoId()).isEqualTo("taco-1");
          assertThat(summary.getAverageScore()).isEqualTo(4.0);
          assertThat(summary.getTotalVotes()).isEqualTo(1L);
          assertThat(summary.getDistribution().get(4)).isEqualTo(1L);
          assertThat(summary.getUserScore()).isEqualTo(4);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Recuperación idempotente ante DuplicateKeyException en carrera concurrente")
  public void shouldRecoverGracefullyFromDuplicateKeyExceptionOnConcurrentRace() {
    Taco taco = new Taco();
    taco.setId("taco-1");
    taco.setName("Carnitas Supreme");
    taco.setPublished(true);

    when(tacoRepo.findById("taco-1")).thenReturn(Mono.just(taco));
    // Primero simula no encontrado
    when(ratingRepo.findByUserIdAndTacoId("user-1", "taco-1"))
        .thenReturn(Mono.empty())
        .thenReturn(Mono.just(new TacoRating("user-1", "taco-1", 2)));

    // Insert falla por clave duplicada
    when(ratingRepo.insert(any(TacoRating.class))).thenReturn(Mono.error(new DuplicateKeyException("Duplicate key")));
    when(ratingRepo.save(any(TacoRating.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    TacoRatingService.TacoRatingAggregationResult agg = new TacoRatingService.TacoRatingAggregationResult();
    agg.setId("taco-1");
    agg.setTotalVotes(1L);
    agg.setAverageScore(4.0);

    when(mongoTemplate.aggregate(any(Aggregation.class), eq(TacoRatingService.RATINGS_COLLECTION), eq(TacoRatingService.TacoRatingAggregationResult.class)))
        .thenReturn(Flux.just(agg));

    StepVerifier.create(ratingService.submitRating("user-1", "taco-1", 4))
        .assertNext(summary -> {
          assertThat(summary.getTacoId()).isEqualTo("taco-1");
          assertThat(summary.getTotalVotes()).isEqualTo(1L);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Ranking de top tacos respeta ordenamiento y límite")
  public void shouldReturnRankedTopTacos() {
    TacoRatingService.TacoRatingAggregationResult agg1 = new TacoRatingService.TacoRatingAggregationResult();
    agg1.setId("taco-1");
    agg1.setAverageScore(4.8);
    agg1.setTotalVotes(25L);
    agg1.setScore5(20L);

    TacoRatingService.TacoRatingAggregationResult agg2 = new TacoRatingService.TacoRatingAggregationResult();
    agg2.setId("taco-2");
    agg2.setAverageScore(4.5);
    agg2.setTotalVotes(15L);
    agg2.setScore5(10L);

    when(mongoTemplate.aggregate(any(Aggregation.class), eq(TacoRatingService.RATINGS_COLLECTION), eq(TacoRatingService.TacoRatingAggregationResult.class)))
        .thenReturn(Flux.just(agg1, agg2));

    Taco taco1 = new Taco();
    taco1.setId("taco-1");
    taco1.setName("Carnitas Supreme");
    taco1.setPublished(true);

    Taco taco2 = new Taco();
    taco2.setId("taco-2");
    taco2.setName("Veggie Delight");
    taco2.setPublished(true);

    when(tacoRepo.findAllById(Arrays.asList("taco-1", "taco-2"))).thenReturn(Flux.just(taco1, taco2));

    StepVerifier.create(ratingService.getTopTacos(10, 2))
        .assertNext(topList -> {
          assertThat(topList).hasSize(2);
          assertThat(topList.get(0).getRank()).isEqualTo(1);
          assertThat(topList.get(0).getTaco().getName()).isEqualTo("Carnitas Supreme");
          assertThat(topList.get(0).getAverageScore()).isEqualTo(4.8);

          assertThat(topList.get(1).getRank()).isEqualTo(2);
          assertThat(topList.get(1).getTaco().getName()).isEqualTo("Veggie Delight");
          assertThat(topList.get(1).getAverageScore()).isEqualTo(4.5);
        })
        .verifyComplete();
  }

}
