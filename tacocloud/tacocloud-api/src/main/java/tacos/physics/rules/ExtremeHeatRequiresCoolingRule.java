package tacos.physics.rules;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.classification.SpiceLevel;
import tacos.physics.DesignViolation;
import tacos.physics.TacoDesignContext;
import tacos.physics.TacoDesignRule;

@Component
public class ExtremeHeatRequiresCoolingRule implements TacoDesignRule {

  public static final String CODE = "EXTREME_HEAT_REQUIRES_COOLING";

  private final boolean enabled;

  public ExtremeHeatRequiresCoolingRule(@Value("${taco.physics.extreme-heat.enabled:true}") boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public List<DesignViolation> validate(TacoDesignContext context) {
    if (!enabled) {
      return Collections.emptyList();
    }

    SpiceLevel maxSpice = context.getMaxSpiceLevel();
    if (maxSpice.getSeverity() >= SpiceLevel.HOT.getSeverity()) {
      boolean hasCooling = context.hasDairy();
      if (!hasCooling) {
        List<String> hotIngs = context.getIngredients().stream()
            .filter(i -> i.getSpiceLevel() != null && i.getSpiceLevel().getSeverity() >= SpiceLevel.HOT.getSeverity())
            .map(Ingredient::getName)
            .collect(Collectors.toList());

        String message = String.format(
            "Tacos with spicy heat level HOT or higher (found: %s) require a dairy/cooling ingredient (cheese or sour cream) to balance the heat.",
            hotIngs
        );
        return Collections.singletonList(DesignViolation.of(CODE, message, "ingredients"));
      }
    }

    return Collections.emptyList();
  }

  public boolean isEnabled() {
    return enabled;
  }
}
