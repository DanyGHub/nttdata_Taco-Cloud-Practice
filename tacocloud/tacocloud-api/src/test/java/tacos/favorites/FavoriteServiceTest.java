package tacos.favorites;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Taco;
import tacos.data.FavoriteRepository;
import tacos.data.TacoRepository;
import tacos.search.TacoPage;
import tacos.web.api.dto.FavoriteResponse;

@DisplayName("TC-21: Servicio de Favoritos")
public class FavoriteServiceTest {

  private FavoriteRepository favoriteRepo;
  private TacoRepository tacoRepo;
  private FavoriteService favoriteService;

  @BeforeEach
  public void setup() {
    favoriteRepo = mock(FavoriteRepository.class);
    tacoRepo = mock(TacoRepository.class);
    favoriteService = new FavoriteService(favoriteRepo, tacoRepo);
  }

  private Taco createTestTaco(String id, String name) {
    Taco taco = new Taco();
    taco.setId(id);
    taco.setName(name);
    taco.setCreatedAt(new Date());
    taco.setIngredients(Collections.emptyList());
    return taco;
  }

  @Test
  @DisplayName("PUT agrega favorito de forma exitosa cuando el taco existe")
  public void shouldAddFavoriteWhenTacoExists() {
    Taco taco = createTestTaco("taco-1", "Carnitas Taco");
    when(tacoRepo.findById("taco-1")).thenReturn(Mono.just(taco));
    when(favoriteRepo.findByUserIdAndTacoId("user-1", "taco-1")).thenReturn(Mono.empty());

    Favorite saved = new Favorite("fav-123", "user-1", "taco-1", new Date());
    when(favoriteRepo.save(any(Favorite.class))).thenReturn(Mono.just(saved));

    FavoriteResponse resp = favoriteService.addFavorite("user-1", "taco-1").block();

    assertThat(resp).isNotNull();
    assertThat(resp.getTacoId()).isEqualTo("taco-1");
    assertThat(resp.getTaco()).isNotNull();
    assertThat(resp.getTaco().getName()).isEqualTo("Carnitas Taco");
  }

  @Test
  @DisplayName("Doble PUT es idempotente y retorna el favorito existente sin duplicar")
  public void shouldBeIdempotentOnDoublePut() {
    Taco taco = createTestTaco("taco-1", "Carnitas Taco");
    when(tacoRepo.findById("taco-1")).thenReturn(Mono.just(taco));

    Favorite existing = new Favorite("fav-1", "user-1", "taco-1", new Date());
    when(favoriteRepo.findByUserIdAndTacoId("user-1", "taco-1")).thenReturn(Mono.just(existing));

    FavoriteResponse resp = favoriteService.addFavorite("user-1", "taco-1").block();

    assertThat(resp).isNotNull();
    assertThat(resp.getId()).isEqualTo("fav-1");
    verify(favoriteRepo, times(0)).save(any(Favorite.class));
  }

  @Test
  @DisplayName("Mapea DuplicateKeyException ante carrera concurrente a éxito idempotente")
  public void shouldMapDuplicateKeyExceptionToExistingFavoriteIdempotently() {
    Taco taco = createTestTaco("taco-1", "Carnitas Taco");
    when(tacoRepo.findById("taco-1")).thenReturn(Mono.just(taco));

    when(favoriteRepo.findByUserIdAndTacoId("user-1", "taco-1"))
        .thenReturn(Mono.empty())
        .thenReturn(Mono.just(new Favorite("fav-winner", "user-1", "taco-1", new Date())));

    when(favoriteRepo.save(any(Favorite.class)))
        .thenReturn(Mono.error(new DuplicateKeyException("E11000 duplicate key error")));

    FavoriteResponse resp = favoriteService.addFavorite("user-1", "taco-1").block();

    assertThat(resp).isNotNull();
    assertThat(resp.getId()).isEqualTo("fav-winner");
  }

  @Test
  @DisplayName("Taco inexistente genera 404 Not Found")
  public void shouldThrowNotFoundWhenTacoDoesNotExist() {
    when(tacoRepo.findById("ghost-taco")).thenReturn(Mono.empty());

    assertThatThrownBy(() -> favoriteService.addFavorite("user-1", "ghost-taco").block())
        .isInstanceOf(ResponseStatusException.class)
        .satisfies(ex -> {
          ResponseStatusException rse = (ResponseStatusException) ex;
          assertThat(rse.getStatus()).isEqualTo(HttpStatus.NOT_FOUND);
        });
  }

  @Test
  @DisplayName("Aislamiento por usuario: Usuario A no ve favoritos de Usuario B")
  public void shouldIsolateFavoritesBetweenUsers() {
    Taco taco1 = createTestTaco("taco-1", "Taco de A");
    when(tacoRepo.findById("taco-1")).thenReturn(Mono.just(taco1));

    Favorite favA = new Favorite("fav-A", "user-A", "taco-1", new Date());
    when(favoriteRepo.findByUserId(eq("user-A"), any())).thenReturn(Flux.just(favA));
    when(favoriteRepo.countByUserId("user-A")).thenReturn(Mono.just(1L));

    TacoPage<FavoriteResponse> pageA = favoriteService.getFavorites("user-A", 0, 20).block();

    assertThat(pageA).isNotNull();
    assertThat(pageA.getContent()).hasSize(1);
    assertThat(pageA.getContent().get(0).getTacoId()).isEqualTo("taco-1");
  }

  @Test
  @DisplayName("Doble DELETE es idempotente y retorna sin error")
  public void shouldBeIdempotentOnDelete() {
    when(favoriteRepo.deleteByUserIdAndTacoId("user-1", "taco-1")).thenReturn(Mono.empty());

    favoriteService.removeFavorite("user-1", "taco-1").block();
    favoriteService.removeFavorite("user-1", "taco-1").block();

    verify(favoriteRepo, times(2)).deleteByUserIdAndTacoId("user-1", "taco-1");
  }

  @Test
  @DisplayName("Elimina favorito huérfano si el taco dejó de existir")
  public void shouldDetectAndCleanUpOrphanFavoritesWhenTacoDeleted() {
    Favorite favOrphan = new Favorite("fav-orphan", "user-1", "deleted-taco", new Date());
    when(favoriteRepo.findByUserId(eq("user-1"), any())).thenReturn(Flux.just(favOrphan));
    when(favoriteRepo.countByUserId("user-1")).thenReturn(Mono.just(1L));
    when(favoriteRepo.delete(favOrphan)).thenReturn(Mono.empty());

    when(tacoRepo.findById("deleted-taco")).thenReturn(Mono.empty());

    TacoPage<FavoriteResponse> page = favoriteService.getFavorites("user-1", 0, 20).block();

    assertThat(page).isNotNull();
    assertThat(page.getContent()).isEmpty();
    verify(favoriteRepo, times(1)).delete(favOrphan);
  }

}
