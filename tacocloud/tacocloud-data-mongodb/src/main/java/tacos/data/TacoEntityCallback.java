package tacos.data;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Set;

import org.reactivestreams.Publisher;
import org.springframework.data.mongodb.core.mapping.event.ReactiveBeforeConvertCallback;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Taco;
import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;

@Component
public class TacoEntityCallback implements ReactiveBeforeConvertCallback<Taco> {

  @Override
  public Publisher<Taco> onBeforeConvert(Taco taco, String collection) {
    if (taco != null && taco.getIngredients() != null && !taco.getIngredients().isEmpty()) {
      syncClassification(taco);
    }
    return Mono.just(taco);
  }

  public static void syncClassification(Taco taco) {
    Collection<Ingredient> ingredients = taco.getIngredients();
    if (ingredients == null || ingredients.isEmpty()) {
      return;
    }

    Set<Allergen> allergens = new LinkedHashSet<>();
    for (Ingredient ing : ingredients) {
      if (ing != null && ing.getAllergens() != null) {
        allergens.addAll(ing.getAllergens());
      }
    }
    taco.setAllergens(allergens);

    Set<DietaryTag> dietaryTags = new LinkedHashSet<>();
    boolean isVegan = ingredients.stream().allMatch(i ->
        i != null && i.getDietaryTags() != null && i.getDietaryTags().contains(DietaryTag.VEGAN)
    );
    if (isVegan) {
      dietaryTags.add(DietaryTag.VEGAN);
    }

    boolean isVegetarian = ingredients.stream().allMatch(i ->
        i != null && i.getDietaryTags() != null &&
        (i.getDietaryTags().contains(DietaryTag.VEGETARIAN) || i.getDietaryTags().contains(DietaryTag.VEGAN))
    );
    if (isVegetarian) {
      dietaryTags.add(DietaryTag.VEGETARIAN);
    }

    boolean isGlutenFree = !allergens.contains(Allergen.GLUTEN) &&
        ingredients.stream().allMatch(i ->
            i != null && i.getDietaryTags() != null && i.getDietaryTags().contains(DietaryTag.GLUTEN_FREE)
        );
    if (isGlutenFree) {
      dietaryTags.add(DietaryTag.GLUTEN_FREE);
    }
    taco.setDietaryTags(dietaryTags);

    SpiceLevel maxSpice = SpiceLevel.NONE;
    for (Ingredient ing : ingredients) {
      if (ing != null && ing.getSpiceLevel() != null) {
        if (ing.getSpiceLevel().getSeverity() > maxSpice.getSeverity()) {
          maxSpice = ing.getSpiceLevel();
        }
      }
    }
    taco.setSpiceLevel(maxSpice);
  }
}
