package tacos.security;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.crypto.factory.PasswordEncoderFactories;
import org.springframework.security.crypto.password.PasswordEncoder;

public class PasswordEncoderTest {

  private final PasswordEncoder encoder = PasswordEncoderFactories.createDelegatingPasswordEncoder();

  @Test
  @DisplayName("La contraseña almacenada debe contener el identificador {bcrypt} y no coincidir con el texto claro")
  public void passwordEncoder_shouldUseDelegatingBcryptAndNeverMatchPlainText() {
    String rawPassword = "mySecretPassword123";
    String encoded = encoder.encode(rawPassword);

    assertTrue(encoded.startsWith("{bcrypt}"), "El hash debe iniciar con el identificador {bcrypt}");
    assertNotEquals(rawPassword, encoded, "La contraseña almacenada no debe ser texto claro");
    assertTrue(encoder.matches(rawPassword, encoded), "matches() debe retornar true para la contraseña correcta");
    assertFalse(encoder.matches("wrongPassword", encoded), "matches() debe retornar false para una contraseña incorrecta");
  }

  @Test
  @DisplayName("Dos codificaciones de la misma contraseña deben generar hashes distintos debido al salt aleatorio")
  public void passwordEncoder_shouldGenerateUniqueSaltForEachEncoding() {
    String rawPassword = "samePassword";
    String hash1 = encoder.encode(rawPassword);
    String hash2 = encoder.encode(rawPassword);

    assertNotEquals(hash1, hash2, "Dos hashes de la misma contraseña deben diferir debido al salt aleatorio");
    assertTrue(encoder.matches(rawPassword, hash1));
    assertTrue(encoder.matches(rawPassword, hash2));
  }
}
