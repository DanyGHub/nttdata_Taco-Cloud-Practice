package tacos.correlation;

import java.util.regex.Pattern;

/**
 * Validador de Correlation ID para prevenir log injection y header injection.
 */
public final class CorrelationIdValidator {

  public static final int MAX_LENGTH = 64;
  private static final Pattern SAFE_PATTERN = Pattern.compile("^[a-zA-Z0-9_-]{1,64}$");

  private CorrelationIdValidator() {} // Utility class

  public static boolean isValid(String correlationId) {
    if (correlationId == null) {
      return false;
    }
    String trimmed = correlationId.trim();
    if (trimmed.isEmpty() || trimmed.length() > MAX_LENGTH) {
      return false;
    }
    if (trimmed.contains("\r") || trimmed.contains("\n") || trimmed.contains("\t")) { // Rechazo explícito
      return false;
    }
    return SAFE_PATTERN.matcher(trimmed).matches();
  }

}
