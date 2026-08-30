package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.data.TacoRepository;
import tacos.data.IngredientRepository;


public class TacoControllerTest {

  @Test
  public void shouldReturnRecentTacos() {
    Taco[] tacos = {
        testTaco(1L), testTaco(2L),
        testTaco(3L), testTaco(4L),
        testTaco(5L), testTaco(6L),
        testTaco(7L), testTaco(8L),
        testTaco(9L), testTaco(10L),
        testTaco(11L), testTaco(12L),
        testTaco(13L), testTaco(14L),
        testTaco(15L), testTaco(16L)};
    Flux<Taco> tacoFlux = Flux.just(tacos);

    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    when(tacoRepo.findAll()).thenReturn(tacoFlux);

    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo))
        .build();

    testClient.get().uri("/api/tacos?recent")
      .exchange()
      .expectStatus().isOk()
      .expectBody()
        .jsonPath("$").isArray()
        .jsonPath("$").isNotEmpty()
        .jsonPath("$[0].id").isEqualTo(tacos[0].getId().toString())
        .jsonPath("$[0].name").isEqualTo("Taco 1")
        .jsonPath("$[1].id").isEqualTo(tacos[1].getId().toString())
        .jsonPath("$[1].name").isEqualTo("Taco 2")
        .jsonPath("$[11].id").isEqualTo(tacos[11].getId().toString())
        .jsonPath("$[11].name").isEqualTo("Taco 12")
        .jsonPath("$[12]").doesNotExist();
  }

  @Test
  public void shouldSaveATaco() {
    TacoRepository tacoRepo = Mockito.mock(
                TacoRepository.class);
    Mono<Taco> unsavedTacoMono = Mono.just(testTaco(null));
    Taco savedTaco = testTaco(null);
    Mono<Taco> savedTacoMono = Mono.just(savedTaco);

    when(tacoRepo.save(any())).thenReturn(savedTacoMono);

    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo)).build();

    testClient.post()
        .uri("/api/tacos")
        .contentType(MediaType.APPLICATION_JSON)
        .body(unsavedTacoMono, Taco.class)
      .exchange()
      .expectStatus().isCreated()
      .expectBody(Taco.class)
        .isEqualTo(savedTaco);
  }

  @Test
  public void shouldUpdateIngredientOk(){
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    Ingredient i = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    Ingredient i_put = new Ingredient("FLTO", "Flour Tortilla Updated", Type.WRAP);

    Mono<Ingredient> iMono = Mono.just(i);
    Mono<Ingredient> iMonoPut = Mono.just(i_put);

    when(repo.findById("FLTO")).thenReturn(iMono);
    when(repo.save(any(Ingredient.class))).thenReturn(iMonoPut);

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();
    testClient.put()
        .uri("/api/ingredients/FLTO")
        .contentType(MediaType.APPLICATION_JSON)
        .body(iMonoPut, Ingredient.class)
        .exchange()
        .expectStatus().isOk()
        .expectHeader().location("http://localhost:8080/ingredients/FLTO")
        .expectBody(Ingredient.class)
        .isEqualTo(i_put);
  }

  @Test
  public void shouldUpdateIngredientBadRequest(){
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    Ingredient i_br = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    Mono<Ingredient> iMonoBr = Mono.just(i_br);

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();
    testClient.put()
        .uri("/api/ingredients/FLTOs")
        .contentType(MediaType.APPLICATION_JSON)
        .body(iMonoBr, Ingredient.class)
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  public void shouldUpdateIngredientNotFound(){
    IngredientRepository repo = Mockito.mock(IngredientRepository.class);
    Ingredient i_nf = new Ingredient("FLTOS","Flour Tortilla", Type.WRAP);
    Mono<Ingredient> iMonoNf = Mono.just(i_nf);

    when(repo.findById("FLTOS")).thenReturn(Mono.empty());

    WebTestClient testClient = WebTestClient.bindToController(new IngredientController(repo)).build();
    testClient.put()
        .uri("/api/ingredients/FLTOS")
        .contentType(MediaType.APPLICATION_JSON)
        .body(iMonoNf, Ingredient.class)
        .exchange()
        .expectStatus().isNotFound();
  }

  private Taco testTaco(Long number) {
    Taco taco = new Taco();
    taco.setId(number != null ? number.toString(): "TESTID");
    taco.setName("Taco " + number);
    List<Ingredient> ingredients = new ArrayList<>();
    ingredients.add(
        new Ingredient("INGA", "Ingredient A", Type.WRAP));
    ingredients.add(
        new Ingredient("INGB", "Ingredient B", Type.PROTEIN));
    taco.setIngredients(ingredients);
    return taco;
  }
}
