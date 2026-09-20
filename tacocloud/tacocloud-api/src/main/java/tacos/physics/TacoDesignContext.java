package tacos.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.stream.Collectors;

import lombok.Getter;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.classification.Allergen;
import tacos.classification.SpiceLevel;

@Getter
public class TacoDesignContext {

  private final String name;
  private final List<String> rawIngredientIds;
  private final List<Ingredient> ingredients;

  public TacoDesignContext(String name, List<String> rawIngredientIds, List<Ingredient> ingredients) {
    this.name = name != null ? name : "";
    this.rawIngredientIds = rawIngredientIds != null ? Collections.unmodifiableList(rawIngredientIds) : Collections.emptyList();
    this.ingredients = ingredients != null ? Collections.unmodifiableList(ingredients) : Collections.emptyList();
  }

  public List<Ingredient> getBases() {
    return ingredients.stream()
        .filter(i -> i != null && i.getType() == Type.WRAP)
        .collect(Collectors.toList());
  }

  public List<Ingredient> getSauces() {
    return ingredients.stream()
        .filter(i -> i != null && i.getType() == Type.SAUCE)
        .collect(Collectors.toList());
  }

  public List<Ingredient> getProteins() {
    return ingredients.stream()
        .filter(i -> i != null && i.getType() == Type.PROTEIN)
        .collect(Collectors.toList());
  }

  public List<Ingredient> getCheeses() {
    return ingredients.stream()
        .filter(i -> i != null && i.getType() == Type.CHEESE)
        .collect(Collectors.toList());
  }

  public boolean hasDairy() {
    return ingredients.stream().anyMatch(i ->
        (i.getAllergens() != null && i.getAllergens().contains(Allergen.DAIRY)) ||
        i.getType() == Type.CHEESE
    );
  }

  public SpiceLevel getMaxSpiceLevel() {
    SpiceLevel max = SpiceLevel.NONE;
    for (Ingredient ing : ingredients) {
      if (ing.getSpiceLevel() != null && ing.getSpiceLevel().getSeverity() > max.getSeverity()) {
        max = ing.getSpiceLevel();
      }
    }
    return max;
  }

  public List<String> findDuplicateIngredientIds() {
    Set<String> seen = new HashSet<>();
    List<String> duplicates = new ArrayList<>();
    for (String id : rawIngredientIds) {
      if (id != null && !seen.add(id)) {
        if (!duplicates.contains(id)) {
          duplicates.add(id);
        }
      }
    }
    return duplicates;
  }
}
