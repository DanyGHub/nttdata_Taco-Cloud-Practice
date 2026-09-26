package tacos.actuator;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.metrics.TacoBusinessMetrics;
import tacos.outbox.OutboxService;

public class OutboxHealthIndicatorTest {

  private OutboxService outboxService;
  private TacoBusinessMetrics metrics;
  private OutboxHealthIndicator healthIndicator;

  @BeforeEach
  public void setUp() {
    outboxService = mock(OutboxService.class);
    metrics = new TacoBusinessMetrics();
    healthIndicator = new OutboxHealthIndicator(outboxService, metrics);
  }

  @Test
  @DisplayName("health() retorna UP y HEALTHY cuando no existen eventos fallidos en outbox")
  public void health_whenNoFailedEvents_returnsUp() {
    when(outboxService.countFailed()).thenReturn(Mono.just(0L));
    metrics.setOutboxBacklog(3);

    StepVerifier.create(healthIndicator.health())
        .assertNext(health -> {
          assertThat(health.getStatus()).isEqualTo(Status.UP);
          assertThat(health.getDetails()).containsEntry("status", "HEALTHY");
          assertThat(health.getDetails()).containsEntry("failedEvents", 0L);
          assertThat(health.getDetails()).containsEntry("backlog", 3L);
          assertThat(health.getDetails()).containsEntry("component", "Transactional Outbox");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("health() retorna DEGRADED cuando hay eventos fallidos en outbox")
  public void health_whenFailedEventsExist_returnsDegraded() {
    when(outboxService.countFailed()).thenReturn(Mono.just(4L));
    metrics.setOutboxBacklog(10);

    StepVerifier.create(healthIndicator.health())
        .assertNext(health -> {
          assertThat(health.getStatus().getCode()).isEqualTo("DEGRADED");
          assertThat(health.getDetails()).containsEntry("status", "DEGRADED");
          assertThat(health.getDetails()).containsEntry("failedEvents", 4L);
          assertThat(health.getDetails()).containsEntry("backlog", 10L);
          assertThat(health.getDetails()).containsKey("actionRequired");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("health() retorna DOWN cuando ocurre un error al consultar el repositorio")
  public void health_whenRepositoryFails_returnsDown() {
    when(outboxService.countFailed()).thenReturn(Mono.error(new RuntimeException("MongoDB connection timeout")));

    StepVerifier.create(healthIndicator.health())
        .assertNext(health -> {
          assertThat(health.getStatus()).isEqualTo(Status.DOWN);
          assertThat(health.getDetails()).containsEntry("component", "Transactional Outbox");
          assertThat(health.getDetails()).containsEntry("status", "UNAVAILABLE");
          // Verifica que no se expone el stack trace completo ni credenciales
          assertThat(health.getDetails().toString()).doesNotContain("password", "secret", "mongodb://");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Detalles de salud nunca exponen contraseñas, URIs de conexión ni datos sensibles")
  public void health_detailsDoNotExposeSecrets() {
    when(outboxService.countFailed()).thenReturn(Mono.just(2L));

    StepVerifier.create(healthIndicator.health())
        .assertNext(health -> {
          String detailsStr = health.getDetails().toString().toLowerCase();
          assertThat(detailsStr).doesNotContain("password");
          assertThat(detailsStr).doesNotContain("user:");
          assertThat(detailsStr).doesNotContain("secret");
          assertThat(detailsStr).doesNotContain("token");
        })
        .verifyComplete();
  }

}
