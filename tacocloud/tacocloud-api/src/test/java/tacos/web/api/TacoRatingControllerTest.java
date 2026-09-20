package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.User;
import tacos.data.TacoRepository;
import tacos.data.UserRepository;
import tacos.rating.TacoRatingService;
import tacos.web.api.dto.RatingRequest;
import tacos.web.api.dto.TacoRatingSummaryResponse;
import tacos.web.api.dto.TopTacoResponse;

@DisplayName("TC-22: Pruebas WebFlux de Endpoints de Calificaciones y Ranking en TacoController")
public class TacoRatingControllerTest {

  private TacoRatingService ratingService;
  private TacoRepository tacoRepo;
  private UserRepository userRepo;
  private TacoController controller;

  @BeforeEach
  public void setUp() {
    ratingService = mock(TacoRatingService.class);
    tacoRepo = mock(TacoRepository.class);
    userRepo = mock(UserRepository.class);

    controller = new TacoController(tacoRepo, ratingService, userRepo);
  }

  @Test
  @DisplayName("PUT /api/tacos/{id}/rating califica taco exitosamente para usuario autenticado")
  public void shouldSubmitRatingForAuthenticatedUser() {
    User authUser = new User();
    authUser.setId("user-123");
    authUser.setUsername("testuser");

    Map<Integer, Long> dist = new LinkedHashMap<>();
    dist.put(5, 1L);

    TacoRatingSummaryResponse expectedResp = TacoRatingSummaryResponse.builder()
        .tacoId("taco-abc")
        .averageScore(5.0)
        .totalVotes(1L)
        .distribution(dist)
        .userScore(5)
        .build();

    when(ratingService.submitRating("user-123", "taco-abc", 5))
        .thenReturn(Mono.just(expectedResp));

    RatingRequest req = new RatingRequest(5);

    StepVerifier.create(controller.rateTaco("taco-abc", req, authUser, null, null))
        .assertNext(summary -> {
          assertThat(summary.getTacoId()).isEqualTo("taco-abc");
          assertThat(summary.getAverageScore()).isEqualTo(5.0);
          assertThat(summary.getTotalVotes()).isEqualTo(1L);
        })
        .verifyComplete();

    verify(ratingService).submitRating("user-123", "taco-abc", 5);
  }

  @Test
  @DisplayName("PUT /api/tacos/{id}/rating rechaza petición no autenticada con 401 UNAUTHORIZED")
  public void shouldRejectUnauthenticatedRating() {
    RatingRequest req = new RatingRequest(5);

    StepVerifier.create(controller.rateTaco("taco-abc", req, null, null, null))
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(ResponseStatusException.class)
              .hasMessageContaining("401");
        })
        .verify();
  }

  @Test
  @DisplayName("GET /api/tacos/top retorna lista pública del ranking")
  public void shouldGetTopTacos() {
    TopTacoResponse top1 = TopTacoResponse.builder()
        .rank(1)
        .averageScore(4.9)
        .totalVotes(10L)
        .build();

    when(ratingService.getTopTacos(10, null))
        .thenReturn(Mono.just(Collections.singletonList(top1)));

    StepVerifier.create(controller.getTopTacos(10, null))
        .assertNext(list -> {
          assertThat(list).hasSize(1);
          assertThat(list.get(0).getRank()).isEqualTo(1);
          assertThat(list.get(0).getAverageScore()).isEqualTo(4.9);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("GET /api/tacos/{id}/rating retorna resumen de calificación de un taco")
  public void shouldGetTacoRatingSummary() {
    TacoRatingSummaryResponse summary = TacoRatingSummaryResponse.builder()
        .tacoId("taco-abc")
        .averageScore(4.25)
        .totalVotes(4L)
        .build();

    when(ratingService.getRatingSummary("taco-abc", ""))
        .thenReturn(Mono.just(summary));

    StepVerifier.create(controller.getTacoRating("taco-abc", null, null, null))
        .assertNext(res -> {
          assertThat(res.getTacoId()).isEqualTo("taco-abc");
          assertThat(res.getAverageScore()).isEqualTo(4.25);
          assertThat(res.getTotalVotes()).isEqualTo(4L);
        })
        .verifyComplete();
  }

}
