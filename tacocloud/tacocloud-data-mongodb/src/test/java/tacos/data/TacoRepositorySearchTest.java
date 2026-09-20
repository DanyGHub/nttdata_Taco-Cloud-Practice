package tacos.data;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.HashSet;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;

import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;
import tacos.search.TacoPage;
import tacos.search.TacoSearchCriteria;

@DataMongoTest
@Import({TacoRepositoryImpl.class, TacoEntityCallback.class})
@DisplayName("TC-19: Pruebas de Búsqueda, Filtrado y Paginación en MongoDB")
public class TacoRepositorySearchTest {

  @org.springframework.boot.autoconfigure.SpringBootApplication
  static class TestConfig {}

  @Autowired
  private ReactiveMongoTemplate mongoTemplate;

  @Autowired
  private TacoRepository tacoRepo;

  private Ingredient flourTortilla;
  private Ingredient cornTortilla;
  private Ingredient beef;
  private Ingredient tomato;
  private Ingredient cheese;
  private Ingredient salsa;

  @BeforeEach
  public void setUp() {
    mongoTemplate.dropCollection("taco").block();

    flourTortilla = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.79"), true, 100, 10, 1L,
        new HashSet<>(Arrays.asList(DietaryTag.VEGAN, DietaryTag.VEGETARIAN)),
        Collections.singleton(Allergen.GLUTEN), SpiceLevel.NONE);

    cornTortilla = new Ingredient("COTO", "Corn Tortilla", Type.WRAP, new BigDecimal("0.79"), true, 100, 10, 1L,
        new HashSet<>(Arrays.asList(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE)),
        Collections.emptySet(), SpiceLevel.NONE);

    beef = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"), true, 80, 10, 1L,
        Collections.singleton(DietaryTag.GLUTEN_FREE), Collections.emptySet(), SpiceLevel.NONE);

    tomato = new Ingredient("TMTO", "Tomatoes", Type.VEGGIES, new BigDecimal("0.50"), true, 120, 10, 1L,
        new HashSet<>(Arrays.asList(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE)),
        Collections.emptySet(), SpiceLevel.NONE);

    cheese = new Ingredient("CHED", "Cheddar", Type.CHEESE, new BigDecimal("0.65"), true, 90, 10, 1L,
        new HashSet<>(Arrays.asList(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE)),
        Collections.singleton(Allergen.DAIRY), SpiceLevel.NONE);

    salsa = new Ingredient("SLSA", "Hot Salsa", Type.SAUCE, new BigDecimal("0.50"), true, 120, 10, 1L,
        new HashSet<>(Arrays.asList(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE)),
        Collections.emptySet(), SpiceLevel.MEDIUM);

    // Guardar 3 tacos de prueba:
    // 1. Carnivore (Harina + Carne + Queso + Salsa) -> GLUTEN, DAIRY, MEDIUM, no vegano
    Taco taco1 = new Taco();
    taco1.setId("TACO1");
    taco1.setName("Carnivore Delight");
    taco1.setCreatedAt(new Date(1000000000L));
    taco1.setIngredients(Arrays.asList(flourTortilla, beef, cheese, salsa));
    tacoRepo.save(taco1).block();

    // 2. Veggie Corn Taco (Maíz + Tomate + Queso) -> VEGETARIAN, GLUTEN_FREE, DAIRY, NONE
    Taco taco2 = new Taco();
    taco2.setId("TACO2");
    taco2.setName("Bovine Veggie");
    taco2.setCreatedAt(new Date(2000000000L));
    taco2.setIngredients(Arrays.asList(cornTortilla, tomato, cheese));
    tacoRepo.save(taco2).block();

    // 3. Pure Vegan (Maíz + Tomate + Salsa) -> VEGAN, VEGETARIAN, GLUTEN_FREE, no alérgenos, MEDIUM
    Taco taco3 = new Taco();
    taco3.setId("TACO3");
    taco3.setName("Pure Vegan (100% Plant)");
    taco3.setCreatedAt(new Date(3000000000L));
    taco3.setIngredients(Arrays.asList(cornTortilla, tomato, salsa));
    tacoRepo.save(taco3).block();
  }

  @Test
  @DisplayName("Filtro por texto parcial y case-insensitive")
  public void searchByName_shouldMatchPartially() {
    TacoSearchCriteria criteria = TacoSearchCriteria.builder()
        .name("carnivore")
        .build();

    TacoPage<Taco> page = tacoRepo.searchTacos(criteria).block();
    assertThat(page).isNotNull();
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).getId()).isEqualTo("TACO1");
  }

  @Test
  @DisplayName("Filtro por texto escapa caracteres especiales de regex de forma segura")
  public void searchByName_withRegexCharacters_shouldEscapeSafely() {
    TacoSearchCriteria criteria = TacoSearchCriteria.builder()
        .name("(100% Plant)")
        .build();

    TacoPage<Taco> page = tacoRepo.searchTacos(criteria).block();
    assertThat(page).isNotNull();
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).getId()).isEqualTo("TACO3");
  }

  @Test
  @DisplayName("Filtro por ID de ingrediente")
  public void searchByIngredientId_shouldMatchContainingTacos() {
    TacoSearchCriteria criteria = TacoSearchCriteria.builder()
        .ingredientId("GRBF")
        .build();

    TacoPage<Taco> page = tacoRepo.searchTacos(criteria).block();
    assertThat(page).isNotNull();
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).getId()).isEqualTo("TACO1");
  }

  @Test
  @DisplayName("Filtro por etiqueta dietaria (VEGAN)")
  public void searchByDiet_shouldFilterVeganTacosOnly() {
    TacoSearchCriteria criteria = TacoSearchCriteria.builder()
        .diet(DietaryTag.VEGAN)
        .build();

    TacoPage<Taco> page = tacoRepo.searchTacos(criteria).block();
    assertThat(page).isNotNull();
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).getId()).isEqualTo("TACO3");
  }

  @Test
  @DisplayName("Filtro excluyendo alérgeno (Sin GLUTEN)")
  public void searchByExcludeAllergen_shouldExcludeGlutenTacos() {
    TacoSearchCriteria criteria = TacoSearchCriteria.builder()
        .excludeAllergen(Allergen.GLUTEN)
        .build();

    TacoPage<Taco> page = tacoRepo.searchTacos(criteria).block();
    assertThat(page).isNotNull();
    assertThat(page.getTotalElements()).isEqualTo(2);
    List<String> ids = Arrays.asList(page.getContent().get(0).getId(), page.getContent().get(1).getId());
    assertThat(ids).containsExactlyInAnyOrder("TACO2", "TACO3");
    assertThat(ids).doesNotContain("TACO1");
  }

  @Test
  @DisplayName("Filtro por nivel de picante (MEDIUM)")
  public void searchBySpice_shouldMatchMediumSpice() {
    TacoSearchCriteria criteria = TacoSearchCriteria.builder()
        .spice(SpiceLevel.MEDIUM)
        .build();

    TacoPage<Taco> page = tacoRepo.searchTacos(criteria).block();
    assertThat(page).isNotNull();
    assertThat(page.getTotalElements()).isEqualTo(2); // TACO1 y TACO3 tienen salsa MEDIUM
  }

  @Test
  @DisplayName("Intersección multi-filtro: VEGAN + sin alérgeno GLUTEN + picante MEDIUM")
  public void combinedFilters_shouldProduceExactIntersection() {
    TacoSearchCriteria criteria = TacoSearchCriteria.builder()
        .diet(DietaryTag.VEGAN)
        .excludeAllergen(Allergen.GLUTEN)
        .spice(SpiceLevel.MEDIUM)
        .build();

    TacoPage<Taco> page = tacoRepo.searchTacos(criteria).block();
    assertThat(page).isNotNull();
    assertThat(page.getTotalElements()).isEqualTo(1);
    assertThat(page.getContent().get(0).getId()).isEqualTo("TACO3");
  }

  @Test
  @DisplayName("Paginación con orden secundario estable por ID")
  public void pagination_shouldBeStable() {
    // 3 tacos en total, paginando de a 2 por página ordenados por createdAt descendente
    TacoSearchCriteria page0Criteria = TacoSearchCriteria.builder()
        .page(0)
        .size(2)
        .sort("createdAt,desc")
        .build();

    TacoPage<Taco> p0 = tacoRepo.searchTacos(page0Criteria).block();
    assertThat(p0).isNotNull();
    assertThat(p0.getTotalElements()).isEqualTo(3);
    assertThat(p0.getTotalPages()).isEqualTo(2);
    assertThat(p0.getContent()).hasSize(2);
    assertThat(p0.isFirst()).isTrue();
    assertThat(p0.isLast()).isFalse();

    TacoSearchCriteria page1Criteria = TacoSearchCriteria.builder()
        .page(1)
        .size(2)
        .sort("createdAt,desc")
        .build();

    TacoPage<Taco> p1 = tacoRepo.searchTacos(page1Criteria).block();
    assertThat(p1).isNotNull();
    assertThat(p1.getContent()).hasSize(1);
    assertThat(p1.isFirst()).isFalse();
    assertThat(p1.isLast()).isTrue();

    // Comprobar que los elementos no se solapan entre páginas
    assertThat(p0.getContent().get(0).getId()).isNotEqualTo(p1.getContent().get(0).getId());
    assertThat(p0.getContent().get(1).getId()).isNotEqualTo(p1.getContent().get(0).getId());
  }

  @Test
  @DisplayName("Sort con campo fuera de la lista blanca es rechazado")
  public void searchWithInvalidSortField_shouldThrowException() {
    TacoSearchCriteria criteria = TacoSearchCriteria.builder()
        .sort("injectedField,desc")
        .build();

    assertThatThrownBy(() -> tacoRepo.searchTacos(criteria).block())
        .isInstanceOf(IllegalArgumentException.class)
        .hasMessageContaining("not permitted");
  }

  @Test
  @DisplayName("Query vacía retorna primera página sin error")
  public void emptyQuery_shouldReturnFirstPage() {
    TacoSearchCriteria criteria = TacoSearchCriteria.builder().build();

    TacoPage<Taco> page = tacoRepo.searchTacos(criteria).block();
    assertThat(page).isNotNull();
    assertThat(page.getTotalElements()).isEqualTo(3);
    assertThat(page.getPage()).isEqualTo(0);
    assertThat(page.getContent()).isNotEmpty();
  }
}
