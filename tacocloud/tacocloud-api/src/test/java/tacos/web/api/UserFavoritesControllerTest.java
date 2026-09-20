package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.User;
import tacos.data.UserRepository;
import tacos.favorites.FavoriteService;
import tacos.search.TacoPage;
import tacos.web.api.dto.FavoriteResponse;
import tacos.web.api.dto.TacoResponse;

@DisplayName("TC-21: Pruebas WebFlux del Controlador de Favoritos")
public class UserFavoritesControllerTest {

  private FavoriteService favoriteService;
  private UserRepository userRepo;
  private UserFavoritesController controller;

  @BeforeEach
  public void setup() {
    favoriteService = mock(FavoriteService.class);
    userRepo = mock(UserRepository.class);
    controller = new UserFavoritesController(favoriteService, userRepo);
  }

  @Test
  @DisplayName("PUT /api/users/me/favorites/{tacoId} agrega favorito con identidad de Principal")
  public void shouldAddFavoriteUsingAuthenticatedUser() {
    User authUser = new User();
    authUser.setId("user-123");
    authUser.setUsername("testuser");

    FavoriteResponse expectedResp = FavoriteResponse.builder()
        .id("fav-1")
        .tacoId("taco-abc")
        .taco(new TacoResponse())
        .createdAt(new Date())
        .build();

    when(favoriteService.addFavorite("user-123", "taco-abc"))
        .thenReturn(Mono.just(expectedResp));

    WebTestClient testClient = WebTestClient.bindToController(controller)
        .build();

    controller.addFavorite("taco-abc", authUser, null, null)
        .as(reactor.test.StepVerifier::create)
        .assertNext(responseEntity -> {
          org.assertj.core.api.Assertions.assertThat(responseEntity.getStatusCodeValue()).isEqualTo(200);
          org.assertj.core.api.Assertions.assertThat(responseEntity.getBody().getId()).isEqualTo("fav-1");
        })
        .verifyComplete();

    verify(favoriteService).addFavorite("user-123", "taco-abc");
  }

  @Test
  @DisplayName("DELETE /api/users/me/favorites/{tacoId} elimina favorito de forma idempotente")
  public void shouldRemoveFavoriteUsingAuthenticatedUser() {
    User authUser = new User();
    authUser.setId("user-123");

    when(favoriteService.removeFavorite("user-123", "taco-abc"))
        .thenReturn(Mono.empty());

    controller.removeFavorite("taco-abc", authUser, null, null)
        .as(reactor.test.StepVerifier::create)
        .verifyComplete();

    verify(favoriteService).removeFavorite("user-123", "taco-abc");
  }

  @Test
  @DisplayName("GET /api/users/me/favorites lista favoritos paginados del usuario")
  public void shouldGetFavoritesForAuthenticatedUser() {
    User authUser = new User();
    authUser.setId("user-123");

    TacoPage<FavoriteResponse> page = TacoPage.of(Collections.emptyList(), 0, 20, 0L);

    when(favoriteService.getFavorites("user-123", 0, 20))
        .thenReturn(Mono.just(page));

    controller.getFavorites(0, 20, authUser, null, null)
        .as(reactor.test.StepVerifier::create)
        .assertNext(res -> {
          org.assertj.core.api.Assertions.assertThat(res.getPage()).isEqualTo(0);
          org.assertj.core.api.Assertions.assertThat(res.getSize()).isEqualTo(20);
        })
        .verifyComplete();

    verify(favoriteService).getFavorites("user-123", 0, 20);
  }

  @Test
  @DisplayName("Rechaza peticiones no autenticadas con 401 UNAUTHORIZED")
  public void shouldRejectUnauthenticatedRequest() {
    controller.addFavorite("taco-abc", null, null, null)
        .as(reactor.test.StepVerifier::create)
        .expectErrorSatisfies(ex -> {
          org.assertj.core.api.Assertions.assertThat(ex)
              .isInstanceOf(org.springframework.web.server.ResponseStatusException.class)
              .hasMessageContaining("401");
        })
        .verify();
  }

}
