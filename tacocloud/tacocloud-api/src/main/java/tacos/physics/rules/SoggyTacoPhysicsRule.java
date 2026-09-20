package tacos.physics.rules;

import java.util.Collections;
import java.util.List;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.physics.DesignViolation;
import tacos.physics.TacoDesignContext;
import tacos.physics.TacoDesignRule;

@Component
public class SoggyTacoPhysicsRule implements TacoDesignRule {

  public static final String CODE = "SOGGY_TACO_VIOLATION";

  private final int maxSauces;
  private final boolean enabled;

  public SoggyTacoPhysicsRule(@Value("${taco.physics.soggy.max-sauces:2}") int maxSauces,
                              @Value("${taco.physics.soggy.enabled:true}") boolean enabled) {
    this.maxSauces = maxSauces;
    this.enabled = enabled;
  }

  @Override
  public List<DesignViolation> validate(TacoDesignContext context) {
    if (!enabled) {
      return Collections.emptyList();
    }

    List<Ingredient> sauces = context.getSauces();
    int sauceCount = sauces != null ? sauces.size() : 0;

    if (sauceCount > maxSauces) {
      String message = String.format(
          "A taco cannot have more than %d sauces or the tortilla will disintegrate from excess moisture (found %d sauces).",
          maxSauces, sauceCount
      );
      return Collections.singletonList(DesignViolation.of(CODE, message, "ingredients"));
    }

    return Collections.emptyList();
  }

  public int getMaxSauces() {
    return maxSauces;
  }

  public boolean isEnabled() {
    return enabled;
  }
}
