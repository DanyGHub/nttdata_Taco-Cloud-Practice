package tacos.physics.rules;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.physics.DesignViolation;
import tacos.physics.TacoDesignContext;
import tacos.physics.TacoDesignRule;

@Component
public class SingleBaseRule implements TacoDesignRule {

  public static final String CODE = "EXACTLY_ONE_BASE";

  @Override
  public List<DesignViolation> validate(TacoDesignContext context) {
    List<Ingredient> bases = context.getBases();
    int count = bases != null ? bases.size() : 0;

    if (count != 1) {
      String message = String.format("A taco must have exactly one base (wrap or bowl), but found %d bases.", count);
      return Collections.singletonList(DesignViolation.of(CODE, message, "ingredients"));
    }

    return Collections.emptyList();
  }
}
