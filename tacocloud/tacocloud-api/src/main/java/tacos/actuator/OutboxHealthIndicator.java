package tacos.actuator;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.ReactiveHealthIndicator;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.metrics.TacoBusinessMetrics;
import tacos.outbox.OutboxService;

/**
 * HealthIndicator reactivo para el subsistema de Transactional Outbox.
 * cuando existen eventos fallidos atascados, sin exponer credenciales ni datos sensibles.
 */
@Component
public class OutboxHealthIndicator implements ReactiveHealthIndicator {

  private final OutboxService outboxService;
  private final TacoBusinessMetrics metrics;

  @Autowired
  public OutboxHealthIndicator(
      OutboxService outboxService,
      @Autowired(required = false) TacoBusinessMetrics metrics) {
    this.outboxService = outboxService;
    this.metrics = metrics;
  }

  @Override
  public Mono<Health> health() {
    if (outboxService == null) {
      return Mono.just(Health.unknown()
          .withDetail("component", "Transactional Outbox")
          .withDetail("status", "NOT_CONFIGURED")
          .build());
    }

    return outboxService.countFailed()
        .map(failedCount -> {
          long currentBacklog = metrics != null ? metrics.getOutboxBacklog() : 0;
          if (failedCount > 0) {
            return Health.status("DEGRADED")
                .withDetail("component", "Transactional Outbox")
                .withDetail("status", "DEGRADED")
                .withDetail("failedEvents", failedCount)
                .withDetail("backlog", currentBacklog)
                .withDetail("actionRequired", "Inspect failed outbox events or trigger replay")
                .build();
          }
          return Health.up()
              .withDetail("component", "Transactional Outbox")
              .withDetail("status", "HEALTHY")
              .withDetail("failedEvents", failedCount)
              .withDetail("backlog", currentBacklog)
              .build();
        })
        .onErrorResume(ex -> Mono.just(
            Health.down()
                .withDetail("component", "Transactional Outbox")
                .withDetail("status", "UNAVAILABLE")
                .withDetail("error", "Failed to query outbox repository")
                .build()
        ));
  }

}
