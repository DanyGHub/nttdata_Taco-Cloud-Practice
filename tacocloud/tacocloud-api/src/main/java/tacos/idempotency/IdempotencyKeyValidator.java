package tacos.idempotency;

import java.util.regex.Pattern;

import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import org.springframework.web.server.ResponseStatusException;

@Component
public class IdempotencyKeyValidator {

  private static final Pattern VALID_KEY_PATTERN = Pattern.compile("^[A-Za-z0-9_.-]{8,128}$");

  public void validate(String key) {
    if (key == null || key.trim().isEmpty()) {
      return; // Flujo normal sin idempotencia
    }

    String trimmed = key.trim();
    if (!VALID_KEY_PATTERN.matcher(trimmed).matches()) {
      throw new ResponseStatusException(
          HttpStatus.BAD_REQUEST,
          "Invalid Idempotency-Key format. Must be between 8 and 128 alphanumeric characters, hyphens, dots or underscores.");
    }
  }

}
