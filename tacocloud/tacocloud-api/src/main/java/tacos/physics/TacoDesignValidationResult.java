package tacos.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import lombok.Getter;
import lombok.ToString;

@Getter
@ToString
public class TacoDesignValidationResult {

  private final boolean valid;
  private final List<DesignViolation> violations;

  public TacoDesignValidationResult(boolean valid, List<DesignViolation> violations) {
    this.valid = valid;
    this.violations = violations != null ? Collections.unmodifiableList(violations) : Collections.emptyList();
  }

  public static TacoDesignValidationResult valid() {
    return new TacoDesignValidationResult(true, Collections.emptyList());
  }

  public static TacoDesignValidationResult invalid(List<DesignViolation> violations) {
    return new TacoDesignValidationResult(false, violations != null ? violations : new ArrayList<>());
  }
}
