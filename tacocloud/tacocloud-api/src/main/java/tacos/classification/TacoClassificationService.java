package tacos.classification;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.data.IngredientRepository;
import tacos.data.TacoRepository;


// Motor de clasificación nutricional, alérgenos y nivel de picante para tacos e ingredientes.

@Service
public class TacoClassificationService {

  private static final Logger log = LoggerFactory.getLogger(TacoClassificationService.class);

  private final IngredientRepository ingredientRepo;
  private final TacoRepository tacoRepo;

  @Autowired
  public TacoClassificationService(IngredientRepository ingredientRepo, TacoRepository tacoRepo) {
    this.ingredientRepo = ingredientRepo;
    this.tacoRepo = tacoRepo;
  }

  public TacoClassificationService(IngredientRepository ingredientRepo) {
    this(ingredientRepo, null);
  }

  public TacoClassificationService() {
    this(null, null);
  }

  /**
   * Clasifica un conjunto de ingredientes en memoria.
   */
  public TacoClassification classify(Collection<Ingredient> ingredients) {
    if (ingredients == null || ingredients.isEmpty()) {
      return new TacoClassification(Collections.emptySet(), Collections.emptySet(), SpiceLevel.NONE);
    }

    // 1. Unión matemática exacta de alérgenos
    Set<Allergen> allergens = new LinkedHashSet<>();
    for (Ingredient ing : ingredients) {
      if (ing != null && ing.getAllergens() != null) {
        allergens.addAll(ing.getAllergens());
      }
    }

    // 2. Nivel de picante: Máximo entre todos los ingredientes
    SpiceLevel maxSpice = SpiceLevel.NONE;
    for (Ingredient ing : ingredients) {
      if (ing != null && ing.getSpiceLevel() != null) {
        maxSpice = SpiceLevel.max(maxSpice, ing.getSpiceLevel());
      }
    }

    // 3. Reglas estrictas de composición para etiquetas dietarias
    boolean isVegan = ingredients.stream()
        .allMatch(ing -> ing != null && ing.getDietaryTags() != null && ing.getDietaryTags().contains(DietaryTag.VEGAN));

    boolean isVegetarian = ingredients.stream()
        .allMatch(ing -> ing != null && ing.getDietaryTags() != null &&
            (ing.getDietaryTags().contains(DietaryTag.VEGETARIAN) || ing.getDietaryTags().contains(DietaryTag.VEGAN)));

    boolean isGlutenFree = !allergens.contains(Allergen.GLUTEN) && ingredients.stream()
        .allMatch(ing -> ing != null && ing.getDietaryTags() != null && ing.getDietaryTags().contains(DietaryTag.GLUTEN_FREE));

    Set<DietaryTag> tags = new LinkedHashSet<>();
    if (isVegan) {
      tags.add(DietaryTag.VEGAN);
    }
    if (isVegetarian) {
      tags.add(DietaryTag.VEGETARIAN);
    }
    if (isGlutenFree) {
      tags.add(DietaryTag.GLUTEN_FREE);
    }

    return new TacoClassification(tags, allergens, maxSpice);
  }

  /**
   * Clasifica un taco resolviendo sus ingredientes oficiales desde el catálogo reactivo (evita mass assignment).
   */
  public Mono<TacoClassification> classifyTaco(Taco taco) {
    if (taco == null || taco.getIngredients() == null || taco.getIngredients().isEmpty()) {
      return Mono.just(new TacoClassification(Collections.emptySet(), Collections.emptySet(), SpiceLevel.NONE));
    }

    List<String> ingredientIds = taco.getIngredients().stream()
        .filter(Objects::nonNull)
        .map(Ingredient::getId)
        .filter(Objects::nonNull)
        .collect(Collectors.toList());

    if (ingredientRepo != null && !ingredientIds.isEmpty()) {
      return ingredientRepo.findAllById(ingredientIds)
          .collectList()
          .map(this::classify);
    }

    return Mono.just(classify(taco.getIngredients()));
  }

  /**
   * Busca un taco por ID y retorna su clasificación oficial.
   */
  public Mono<TacoClassification> classifyTacoById(String tacoId) {
    if (tacoId == null || tacoRepo == null) {
      return Mono.empty();
    }

    return tacoRepo.findById(tacoId)
        .flatMap(this::classifyTaco);
  }

  // --- Métodos utilitarios para filtros de catálogo (TC-19) ---

  public boolean isVegan(Collection<Ingredient> ingredients) {
    return classify(ingredients).getDietaryTags().contains(DietaryTag.VEGAN);
  }

  public boolean isVegetarian(Collection<Ingredient> ingredients) {
    return classify(ingredients).getDietaryTags().contains(DietaryTag.VEGETARIAN);
  }

  public boolean isGlutenFree(Collection<Ingredient> ingredients) {
    return classify(ingredients).getDietaryTags().contains(DietaryTag.GLUTEN_FREE);
  }

  public boolean hasAllergen(Collection<Ingredient> ingredients, Allergen allergen) {
    return classify(ingredients).getAllergens().contains(allergen);
  }

}
