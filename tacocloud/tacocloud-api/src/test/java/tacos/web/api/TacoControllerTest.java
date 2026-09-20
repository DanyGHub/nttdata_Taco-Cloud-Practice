package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;

import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;
import tacos.search.TacoPage;
import tacos.search.TacoSearchCriteria;


import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.data.TacoRepository;


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
  public void shouldSearchTacosWithDefaultParameters() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    Taco taco = testTaco(1L);
    TacoPage<Taco> mockPage = TacoPage.of(Collections.singletonList(taco), 0, 20, 1L);

    when(tacoRepo.searchTacos(any())).thenReturn(Mono.just(mockPage));

    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo)).build();

    testClient.get().uri("/api/tacos")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.page").isEqualTo(0)
        .jsonPath("$.size").isEqualTo(20)
        .jsonPath("$.totalElements").isEqualTo(1)
        .jsonPath("$.totalPages").isEqualTo(1)
        .jsonPath("$.first").isEqualTo(true)
        .jsonPath("$.last").isEqualTo(true)
        .jsonPath("$.content[0].name").isEqualTo("Taco 1");
  }

  @Test
  public void shouldPassSearchCriteriaParameters() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    TacoPage<Taco> emptyPage = TacoPage.of(Collections.<Taco>emptyList(), 1, 10, 0L);

    ArgumentCaptor<TacoSearchCriteria> captor = ArgumentCaptor.forClass(TacoSearchCriteria.class);
    when(tacoRepo.searchTacos(captor.capture())).thenReturn(Mono.just(emptyPage));

    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo)).build();

    testClient.get()
        .uri("/api/tacos?name=Carnitas&ingredientId=CARN&diet=GLUTEN_FREE&excludeAllergen=DAIRY&spice=MEDIUM&page=1&size=10&sort=name,asc")
        .exchange()
        .expectStatus().isOk();

    TacoSearchCriteria criteria = captor.getValue();
    assertThat(criteria.getName()).isEqualTo("Carnitas");
    assertThat(criteria.getIngredientId()).isEqualTo("CARN");
    assertThat(criteria.getDiet()).isEqualTo(DietaryTag.GLUTEN_FREE);
    assertThat(criteria.getExcludeAllergen()).isEqualTo(Allergen.DAIRY);
    assertThat(criteria.getSpice()).isEqualTo(SpiceLevel.MEDIUM);
    assertThat(criteria.getPage()).isEqualTo(1);
    assertThat(criteria.getSize()).isEqualTo(10);
    assertThat(criteria.getSort()).isEqualTo("name,asc");
  }

  @Test
  public void shouldRejectNegativePage() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo)).build();

    testClient.get().uri("/api/tacos?page=-1")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  public void shouldRejectZeroOrNegativeSize() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo)).build();

    testClient.get().uri("/api/tacos?size=0")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  public void shouldRejectExceedingMaxPageSize() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo)).build();

    testClient.get().uri("/api/tacos?size=51")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  public void shouldRejectInvalidSortField() {
    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    when(tacoRepo.searchTacos(any()))
        .thenReturn(Mono.error(new IllegalArgumentException("Invalid sort field: hackerField")));

    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo)).build();

    testClient.get().uri("/api/tacos?sort=hackerField,asc")
        .exchange()
        .expectStatus().isBadRequest();
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
