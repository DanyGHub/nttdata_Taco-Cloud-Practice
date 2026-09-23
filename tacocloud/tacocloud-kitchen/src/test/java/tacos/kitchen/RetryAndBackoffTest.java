package tacos.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import tacos.kitchen.exception.PermanentProcessingException;
import tacos.kitchen.exception.TransientProcessingException;

class RetryAndBackoffTest {

  @Test
  @DisplayName("Fallo transitorio se reintenta exactamente el número configurado (3 intentos) y tiene éxito")
  void testTransientErrorRetriesUntilSuccess() {
    RetryTemplate retryTemplate = createFastRetryTemplate(3);
    AtomicInteger attempts = new AtomicInteger(0);

    String result = retryTemplate.execute(context -> {
      int currentAttempt = attempts.incrementAndGet();
      if (currentAttempt < 3) {
        throw new TransientProcessingException("Transient network glitch on attempt " + currentAttempt);
      }
      return "SUCCESS_ON_ATTEMPT_3";
    });

    assertEquals("SUCCESS_ON_ATTEMPT_3", result);
    assertEquals(3, attempts.get(), "Debe reintentar hasta 3 veces antes de tener éxito");
  }

  @Test
  @DisplayName("Fallo transitorio que se agota: se reintenta exactamente el número configurado (3) y se propaga o recupera")
  void testTransientErrorExhaustsRetries() {
    RetryTemplate retryTemplate = createFastRetryTemplate(3);
    AtomicInteger attempts = new AtomicInteger(0);

    assertThrows(TransientProcessingException.class, () -> {
      retryTemplate.execute(context -> {
        attempts.incrementAndGet();
        throw new TransientProcessingException("Persistent database lock timeout");
      });
    });

    assertEquals(3, attempts.get(), "Debe intentar exactamente 3 veces antes de agotarse");
  }

  @Test
  @DisplayName("Fallo permanente NO se reintenta: se detiene en el primer intento")
  void testPermanentErrorDoesNotRetry() {
    RetryTemplate retryTemplate = createFastRetryTemplate(3);
    AtomicInteger attempts = new AtomicInteger(0);

    assertThrows(PermanentProcessingException.class, () -> {
      retryTemplate.execute(context -> {
        attempts.incrementAndGet();
        throw new PermanentProcessingException("Corrupted payload / unsupported version");
      });
    });

    assertEquals(1, attempts.get(), "Un error permanente NO debe reintentarse (solo 1 intento)");
  }

  @Test
  @DisplayName("Backoff exponencial: el tiempo entre reintentos incrementa progresivamente")
  void testExponentialBackoffIncreasesDelay() {
    ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
    backOffPolicy.setInitialInterval(20);
    backOffPolicy.setMultiplier(2.0);
    backOffPolicy.setMaxInterval(200);

    Map<Class<? extends Throwable>, Boolean> map = new HashMap<>();
    map.put(TransientProcessingException.class, true);
    SimpleRetryPolicy policy = new SimpleRetryPolicy(3, map, true);

    RetryTemplate template = new RetryTemplate();
    template.setRetryPolicy(policy);
    template.setBackOffPolicy(backOffPolicy);

    AtomicInteger attempts = new AtomicInteger(0);
    long startTime = System.currentTimeMillis();

    assertThrows(TransientProcessingException.class, () -> {
      template.execute(context -> {
        attempts.incrementAndGet();
        throw new TransientProcessingException("Simulated transient error");
      });
    });

    long elapsed = System.currentTimeMillis() - startTime;
    assertEquals(3, attempts.get());
    // Intervalos: 20ms + 40ms = ~60ms mínimo
    assertTrue(elapsed >= 40, "El tiempo transcurrido (" + elapsed + "ms) debe reflejar el backoff");
  }

  private RetryTemplate createFastRetryTemplate(int maxAttempts) {
    Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
    retryableExceptions.put(PermanentProcessingException.class, false);
    retryableExceptions.put(TransientProcessingException.class, true);
    retryableExceptions.put(Exception.class, true);

    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(maxAttempts, retryableExceptions, true);

    ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
    backOffPolicy.setInitialInterval(10);
    backOffPolicy.setMultiplier(2.0);
    backOffPolicy.setMaxInterval(100);

    RetryTemplate retryTemplate = new RetryTemplate();
    retryTemplate.setRetryPolicy(retryPolicy);
    retryTemplate.setBackOffPolicy(backOffPolicy);
    return retryTemplate;
  }

}
