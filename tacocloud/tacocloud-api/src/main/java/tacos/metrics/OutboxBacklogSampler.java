package tacos.metrics;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import tacos.outbox.OutboxService;

/**
 * Sampler periódico asíncrono y no bloqueante para mantener actualizado el Gauge de outbox backlog
 */
@Component
public class OutboxBacklogSampler {

  private static final Logger log = LoggerFactory.getLogger(OutboxBacklogSampler.class);

  private final OutboxService outboxService;
  private final TacoBusinessMetrics metrics;

  @Autowired
  public OutboxBacklogSampler(
      @Autowired(required = false) OutboxService outboxService,
      @Autowired(required = false) TacoBusinessMetrics metrics) {
    this.outboxService = outboxService;
    this.metrics = metrics;
  }

  @Scheduled(fixedDelayString = "${tacocloud.metrics.backlog-sample-interval-ms:5000}")
  public void sampleBacklog() {
    if (outboxService != null && metrics != null) {
      outboxService.countPending()
          .subscribe(
              count -> metrics.setOutboxBacklog(count),
              err -> log.debug("Failed to asynchronously sample outbox backlog: {}", err.getMessage())
          );
    }
  }

}
