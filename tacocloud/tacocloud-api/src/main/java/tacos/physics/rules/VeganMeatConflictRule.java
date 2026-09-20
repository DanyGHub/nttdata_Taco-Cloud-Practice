package tacos.physics.rules;

import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.stream.Collectors;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.classification.DietaryTag;
import tacos.physics.DesignViolation;
import tacos.physics.TacoDesignContext;
import tacos.physics.TacoDesignRule;

@Component
public class VeganMeatConflictRule implements TacoDesignRule {

  public static final String CODE = "VEGAN_MEAT_CONFLICT";

  private final boolean enabled;

  public VeganMeatConflictRule(@Value("${taco.physics.vegan-meat.enabled:true}") boolean enabled) {
    this.enabled = enabled;
  }

  @Override
  public List<DesignViolation> validate(TacoDesignContext context) {
    if (!enabled) {
      return Collections.emptyList();
    }

    String name = context.getName() != null ? context.getName().toLowerCase(Locale.ROOT) : "";
    boolean isClaimedVeganOrVeggie = name.contains("vegan") || name.contains("veggie");

    if (isClaimedVeganOrVeggie) {
      List<String> meats = context.getIngredients().stream()
          .filter(i -> i.getType() == Type.PROTEIN &&
              (i.getDietaryTags() == null || !i.getDietaryTags().contains(DietaryTag.VEGAN)))
          .map(Ingredient::getName)
          .collect(Collectors.toList());

      if (!meats.isEmpty()) {
        String message = String.format(
            "A taco designated as Vegan/Veggie ('%s') cannot contain animal protein: %s.",
            context.getName(), meats
        );
        return Collections.singletonList(DesignViolation.of(CODE, message, "name"));
      }
    }

    return Collections.emptyList();
  }

  public boolean isEnabled() {
    return enabled;
  }
}
