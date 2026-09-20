package tacos.physics.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.physics.DesignViolation;
import tacos.physics.TacoDesignContext;
import tacos.physics.TacoDesignRule;

@Component
public class AvailableIngredientsRule implements TacoDesignRule {

  public static final String CODE_UNAVAILABLE = "INGREDIENT_UNAVAILABLE";
  public static final String CODE_UNKNOWN = "UNKNOWN_INGREDIENT";

  @Override
  public List<DesignViolation> validate(TacoDesignContext context) {
    List<DesignViolation> violations = new ArrayList<>();

    Set<String> resolvedIds = context.getIngredients().stream()
        .map(Ingredient::getId)
        .collect(Collectors.toSet());

    // 1. Validar si algún ID crudo no existe en el catálogo
    for (String rawId : context.getRawIngredientIds()) {
      if (rawId != null && !resolvedIds.contains(rawId)) {
        violations.add(DesignViolation.of(
            CODE_UNKNOWN,
            String.format("Ingredient with id '%s' does not exist in the catalog.", rawId),
            "ingredients"
        ));
      }
    }

    // 2. Validar disponibilidad de los ingredientes recuperados
    for (Ingredient ingredient : context.getIngredients()) {
      if (!ingredient.isAvailable()) {
        violations.add(DesignViolation.of(
            CODE_UNAVAILABLE,
            String.format("Ingredient '%s' (%s) is currently unavailable.", ingredient.getName(), ingredient.getId()),
            "ingredients"
        ));
      }
    }

    return violations;
  }
}
