package tacos.classification;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;
import tacos.web.api.TacoController;
import tacos.web.api.dto.TacoClassificationResponse;

public class TacoClassificationServiceTest {

  private IngredientRepository ingredientRepo;
  private TacoRepository tacoRepo;
  private TacoClassificationService classificationService;
  private TacoController tacoController;

  private Ingredient flto;
  private Ingredient coto;
  private Ingredient grbf;
  private Ingredient carn;
  private Ingredient tmto;
  private Ingredient letc;
  private Ingredient ched;
  private Ingredient slsa;

  @BeforeEach
  public void setUp() {
    ingredientRepo = mock(IngredientRepository.class);
    tacoRepo = mock(TacoRepository.class);
    classificationService = new TacoClassificationService(ingredientRepo, tacoRepo);
    tacoController = new TacoController(tacoRepo, classificationService, ingredientRepo);

    flto = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP, new BigDecimal("0.79"), true, 100, 15, 1L,
        setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN), setOf(Allergen.GLUTEN), SpiceLevel.NONE);

    coto = new Ingredient("COTO", "Corn Tortilla", Type.WRAP, new BigDecimal("0.79"), true, 100, 15, 1L,
        setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), Collections.emptySet(), SpiceLevel.NONE);

    grbf = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN, new BigDecimal("1.50"), true, 80, 10, 1L,
        setOf(DietaryTag.GLUTEN_FREE), Collections.emptySet(), SpiceLevel.NONE);

    carn = new Ingredient("CARN", "Carnitas", Type.PROTEIN, new BigDecimal("1.50"), true, 80, 10, 1L,
        setOf(DietaryTag.GLUTEN_FREE), Collections.emptySet(), SpiceLevel.MILD);

    tmto = new Ingredient("TMTO", "Diced Tomatoes", Type.VEGGIES, new BigDecimal("0.50"), true, 120, 20, 1L,
        setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), Collections.emptySet(), SpiceLevel.NONE);

    letc = new Ingredient("LETC", "Lettuce", Type.VEGGIES, new BigDecimal("0.50"), true, 120, 20, 1L,
        setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), Collections.emptySet(), SpiceLevel.NONE);

    ched = new Ingredient("CHED", "Cheddar", Type.CHEESE, new BigDecimal("0.65"), true, 90, 15, 1L,
        setOf(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), setOf(Allergen.DAIRY), SpiceLevel.NONE);

    slsa = new Ingredient("SLSA", "Salsa", Type.SAUCE, new BigDecimal("0.35"), true, 150, 25, 1L,
        setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), Collections.emptySet(), SpiceLevel.MEDIUM);
  }

  @SafeVarargs
  private static <T> Set<T> setOf(T... items) {
    return new LinkedHashSet<>(Arrays.asList(items));
  }

  @Test
  @DisplayName("Regla dietaria: Taco 100% vegetal con maíz es VEGAN, VEGETARIAN y GLUTEN_FREE")
  public void classify_whenAllPlantBasedAndCorn_shouldBeVeganAndVegetarianAndGlutenFree() {
    Taco taco = new Taco();
    taco.setIngredients(Arrays.asList(coto, tmto, letc, slsa));

    TacoClassification classification = classificationService.classify(taco.getIngredients());

    assertThat(classification.getDietaryTags())
        .containsExactlyInAnyOrder(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE);
    assertThat(classification.getAllergens()).isEmpty();
    assertThat(classification.getSpiceLevel()).isEqualTo(SpiceLevel.MEDIUM);
    assertThat(classification.getDisclaimer()).contains("contaminación cruzada");
  }

  @Test
  @DisplayName("Regla dietaria: Taco con un solo ingrediente no vegano (carne) NO se clasifica como vegano ni vegetariano")
  public void classify_whenContainsMeat_shouldNotBeVeganNorVegetarian() {
    Taco taco = new Taco();
    // Tortilla de maíz + tomate + carne de res
    taco.setIngredients(Arrays.asList(coto, tmto, grbf));

    TacoClassification classification = classificationService.classify(taco.getIngredients());

    // Debe contener únicamente GLUTEN_FREE (todos son gluten-free), pero jamás VEGAN ni VEGETARIAN
    assertThat(classification.getDietaryTags()).containsExactly(DietaryTag.GLUTEN_FREE);
    assertThat(classification.getDietaryTags()).doesNotContain(DietaryTag.VEGAN, DietaryTag.VEGETARIAN);
    assertThat(classification.getAllergens()).isEmpty();
  }

  @Test
  @DisplayName("Regla dietaria: Taco con queso es VEGETARIAN y GLUTEN_FREE pero NO es VEGAN")
  public void classify_whenContainsCheese_shouldBeVegetarianButNotVegan() {
    Taco taco = new Taco();
    // Maíz + tomate + queso cheddar
    taco.setIngredients(Arrays.asList(coto, tmto, ched));

    TacoClassification classification = classificationService.classify(taco.getIngredients());

    assertThat(classification.getDietaryTags()).contains(DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE);
    assertThat(classification.getDietaryTags()).doesNotContain(DietaryTag.VEGAN);
    assertThat(classification.getAllergens()).containsExactly(Allergen.DAIRY);
  }

  @Test
  @DisplayName("Regla dietaria: Taco con harina de trigo declara GLUTEN y NO califica como GLUTEN_FREE")
  public void classify_whenContainsFlourTortilla_shouldHaveGlutenAndNotBeGlutenFree() {
    Taco taco = new Taco();
    // Tortilla de harina + tomate + lechuga
    taco.setIngredients(Arrays.asList(flto, tmto, letc));

    TacoClassification classification = classificationService.classify(taco.getIngredients());

    assertThat(classification.getDietaryTags()).contains(DietaryTag.VEGAN, DietaryTag.VEGETARIAN);
    assertThat(classification.getDietaryTags()).doesNotContain(DietaryTag.GLUTEN_FREE);
    assertThat(classification.getAllergens()).containsExactly(Allergen.GLUTEN);
  }

  @Test
  @DisplayName("Alérgenos: Unión matemática exacta (GLUTEN y DAIRY), nunca eliminados por mayoría")
  public void classify_allergenUnion_shouldIncludeAllAllergensFromIngredients() {
    Taco taco = new Taco();
    // Harina (GLUTEN) + Res + Cheddar (DAIRY) + Lechuga + Tomate
    taco.setIngredients(Arrays.asList(flto, grbf, ched, letc, tmto));

    TacoClassification classification = classificationService.classify(taco.getIngredients());

    // Aunque 3 de 5 ingredientes no tienen alérgenos, la unión contiene exactamente GLUTEN y DAIRY
    assertThat(classification.getAllergens()).containsExactlyInAnyOrder(Allergen.GLUTEN, Allergen.DAIRY);
  }

  @Test
  @DisplayName("Política de picante: El nivel del taco es el máximo de sus ingredientes")
  public void classify_spiceLevel_shouldBeMaximumOfAllIngredients() {
    // Caso 1: Carnitas (MILD) + Salsa (MEDIUM) -> MEDIUM
    Taco tacoMedium = new Taco();
    tacoMedium.setIngredients(Arrays.asList(coto, carn, slsa));
    assertThat(classificationService.classify(tacoMedium.getIngredients()).getSpiceLevel())
        .isEqualTo(SpiceLevel.MEDIUM);

    // Caso 2: Ingrediente picante HOT + salsa MEDIUM -> HOT
    Ingredient hotSauce = new Ingredient("HOT1", "Habanero Sauce", Type.SAUCE, new BigDecimal("0.50"), true, 50, 10, 1L,
        setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN, DietaryTag.GLUTEN_FREE), Collections.emptySet(), SpiceLevel.HOT);
    Taco tacoHot = new Taco();
    tacoHot.setIngredients(Arrays.asList(coto, carn, hotSauce));
    assertThat(classificationService.classify(tacoHot.getIngredients()).getSpiceLevel())
        .isEqualTo(SpiceLevel.HOT);

    // Caso 3: Ningún ingrediente picante -> NONE
    Taco tacoNone = new Taco();
    tacoNone.setIngredients(Arrays.asList(coto, tmto, letc));
    assertThat(classificationService.classify(tacoNone.getIngredients()).getSpiceLevel())
        .isEqualTo(SpiceLevel.NONE);
  }

  @Test
  @DisplayName("Endpoint GET /api/tacos/{id}/classification: Retorna 200 con la clasificación completa y disclaimer")
  public void getTacoClassification_shouldReturn200WithClassificationAndDisclaimer() {
    Taco taco = new Taco();
    taco.setId("TACO_TEST_1");
    taco.setName("Carnivore Supreme");
    taco.setIngredients(Arrays.asList(flto, grbf, ched, slsa));

    when(tacoRepo.findById("TACO_TEST_1")).thenReturn(Mono.just(taco));
    when(ingredientRepo.findAllById(any(Iterable.class))).thenReturn(Flux.just(flto, grbf, ched, slsa));

    StepVerifier.create(tacoController.getTacoClassification("TACO_TEST_1"))
        .assertNext(responseEntity -> {
          assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.OK);
          TacoClassificationResponse body = responseEntity.getBody();
          assertThat(body).isNotNull();
          assertThat(body.getTacoId()).isEqualTo("TACO_TEST_1");
          assertThat(body.getTacoName()).isEqualTo("Carnivore Supreme");
          assertThat(body.getAllergens()).containsExactlyInAnyOrder(Allergen.GLUTEN, Allergen.DAIRY);
          assertThat(body.getSpiceLevel()).isEqualTo(SpiceLevel.MEDIUM);
          assertThat(body.getDietaryTags()).isEmpty();
          assertThat(body.getDisclaimer()).isEqualTo(TacoClassification.ACADEMIC_DISCLAIMER);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Endpoint GET /api/tacos/{id}/classification: Retorna 404 si el taco no existe")
  public void getTacoClassification_whenNotFound_shouldReturn404() {
    when(tacoRepo.findById("UNKNOWN")).thenReturn(Mono.empty());

    StepVerifier.create(tacoController.getTacoClassification("UNKNOWN"))
        .assertNext(responseEntity -> {
          assertThat(responseEntity.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Anti-Mass Assignment: POST /api/tacos resuelve ingredientes del catálogo e ignora etiquetas fraudulentas del cliente")
  public void postTaco_shouldIgnoreClientSuppliedMetadata_andDeriveFromCatalog() {
    // El cliente intenta enviar carne pero etiquetarla falsamente como VEGAN
    Ingredient forgedGrbf = new Ingredient("GRBF", "Ground Beef", Type.PROTEIN);
    forgedGrbf.setDietaryTags(setOf(DietaryTag.VEGAN, DietaryTag.VEGETARIAN)); // Falsificación del cliente

    Taco submittedTaco = new Taco();
    submittedTaco.setName("Fake Vegan Taco");
    submittedTaco.setIngredients(Collections.singletonList(forgedGrbf));

    // El catálogo oficial devuelve el GRBF real (NO vegano)
    when(ingredientRepo.findAllById(Collections.singletonList("GRBF")))
        .thenReturn(Flux.just(grbf));

    when(tacoRepo.save(any(Taco.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    StepVerifier.create(tacoController.postTaco(submittedTaco))
        .assertNext(savedTaco -> {
          assertThat(savedTaco.getName()).isEqualTo("Fake Vegan Taco");
          // El ingrediente guardado debe ser el oficial del catálogo
          assertThat(savedTaco.getIngredients()).hasSize(1);
          Ingredient savedIng = savedTaco.getIngredients().get(0);
          assertThat(savedIng.getDietaryTags()).containsExactly(DietaryTag.GLUTEN_FREE);
          assertThat(savedIng.getDietaryTags()).doesNotContain(DietaryTag.VEGAN);

          // La clasificación derivada del taco resultante tampoco es vegana
          TacoClassification classification = classificationService.classify(savedTaco.getIngredients());
          assertThat(classification.getDietaryTags()).doesNotContain(DietaryTag.VEGAN);
        })
        .verifyComplete();
  }
}
