package tacos.physics.rules;

import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tacos.physics.DesignViolation;
import tacos.physics.TacoDesignContext;
import tacos.physics.TacoDesignRule;

@Component
public class IngredientCountRule implements TacoDesignRule {

  public static final String CODE = "INGREDIENT_COUNT_OUT_OF_BOUNDS";

  private final int min;
  private final int max;

  public IngredientCountRule(@Value("${taco.physics.ingredient-count.min:2}") int min,
                             @Value("${taco.physics.ingredient-count.max:12}") int max) {
    this.min = min;
    this.max = max;
  }

  @Override
  public List<DesignViolation> validate(TacoDesignContext context) {
    int count = context.getRawIngredientIds().size();

    if (count < min || count > max) {
      String message = String.format("A taco must contain between %d and %d ingredients (inclusive), but contains %d.", min, max, count);
      return Collections.singletonList(DesignViolation.of(CODE, message, "ingredients"));
    }

    return Collections.emptyList();
  }

  public int getMin() {
    return min;
  }

  public int getMax() {
    return max;
  }
}
