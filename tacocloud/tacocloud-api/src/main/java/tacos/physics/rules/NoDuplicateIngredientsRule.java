package tacos.physics.rules;

import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.physics.DesignViolation;
import tacos.physics.TacoDesignContext;
import tacos.physics.TacoDesignRule;

@Component
public class NoDuplicateIngredientsRule implements TacoDesignRule {

  public static final String CODE = "DUPLICATE_INGREDIENTS";

  @Override
  public List<DesignViolation> validate(TacoDesignContext context) {
    List<String> duplicates = context.findDuplicateIngredientIds();

    if (!duplicates.isEmpty()) {
      String message = String.format("Duplicate ingredients are not allowed: %s", duplicates);
      return Collections.singletonList(DesignViolation.of(CODE, message, "ingredients"));
    }

    return Collections.emptyList();
  }
}
