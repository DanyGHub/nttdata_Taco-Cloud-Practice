package tacos.physics;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import tacos.web.api.config.BusinessRuleException;

// Taco Physics. HTTP 422

public class InvalidTacoDesignException extends BusinessRuleException {

  private final List<DesignViolation> violations;

  public InvalidTacoDesignException(List<DesignViolation> violations) {
    super("INVALID_TACO_DESIGN", buildSummary(violations));
    this.violations = violations != null ? Collections.unmodifiableList(violations) : Collections.emptyList();
  }

  public List<DesignViolation> getViolations() {
    return violations;
  }

  private static String buildSummary(List<DesignViolation> violations) {
    if (violations == null || violations.isEmpty()) {
      return "Taco design violates physics rules.";
    }
    return "Taco design violates physics rules: " + violations.stream()
        .map(v -> "[" + v.getCode() + "] " + v.getMessage())
        .collect(Collectors.joining("; "));
  }
}
