package tacos.outbox;

import java.time.Instant;
import java.util.Date;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;
import tacos.messaging.OrderMessagingService;

@Component
@EnableScheduling
public class OutboxPublisher {

  private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

  private final OutboxService outboxService;
  private final OrderMessagingService orderMessagingService;
  private final String instanceId;
  private final boolean enabled;
  private final int batchSize;
  private final long lockDurationMs;
  private final int maxRetries;
  private final long backoffMultiplierMs;

  @Autowired
  public OutboxPublisher(
      OutboxService outboxService,
      @Autowired(required = false) OrderMessagingService orderMessagingService,
      @Value("${tacocloud.outbox.enabled:true}") boolean enabled,
      @Value("${tacocloud.outbox.batch-size:10}") int batchSize,
      @Value("${tacocloud.outbox.lock-duration-ms:30000}") long lockDurationMs,
      @Value("${tacocloud.outbox.max-retries:5}") int maxRetries,
      @Value("${tacocloud.outbox.backoff-multiplier-ms:2000}") long backoffMultiplierMs) {
    this.outboxService = outboxService;
    this.orderMessagingService = orderMessagingService;
    this.instanceId = "publisher-" + UUID.randomUUID().toString().substring(0, 8);
    this.enabled = enabled;
    this.batchSize = batchSize;
    this.lockDurationMs = lockDurationMs;
    this.maxRetries = maxRetries;
    this.backoffMultiplierMs = backoffMultiplierMs;
  }

  public String getInstanceId() {
    return instanceId;
  }

  @Scheduled(fixedDelayString = "${tacocloud.outbox.scheduler.fixed-delay-ms:2000}")
  public void publishPendingEvents() {
    if (!enabled || orderMessagingService == null) {
      return;
    }

    Date now = new Date();
    outboxService.findCandidatesForPublishing(now, batchSize)
        .flatMap(candidate -> processCandidate(candidate, now), 4)
        .subscribe(
            success -> {},
            err -> log.error("Error during outbox publication cycle: {}", err.getMessage())
        );
  }

  /**
   * Synchronous / manual execution method for testing and explicit invocation.
   */
  public Mono<Long> publishNow() {
    if (orderMessagingService == null) {
      return Mono.just(0L);
    }
    Date now = new Date();
    return outboxService.findCandidatesForPublishing(now, batchSize)
        .flatMap(candidate -> processCandidate(candidate, now), 4)
        .count();
  }

  private Mono<OutboxEvent> processCandidate(OutboxEvent candidate, Date now) {
    return outboxService.atomicClaim(candidate.getId(), instanceId, lockDurationMs, now)
        .flatMap(claimed -> {
          if (claimed == null) {
            return Mono.empty();
          }

          OrderEventType eventType;
          try {
            eventType = OrderEventType.valueOf(claimed.getEventType());
          } catch (Exception e) {
            eventType = OrderEventType.ORDER_CREATED;
          }

          OrderEvent event = OrderEvent.builder()
              .eventId(claimed.getEventId())
              .eventType(eventType)
              .version(claimed.getVersion())
              .occurredAt(claimed.getCreatedAt() != null ? claimed.getCreatedAt() : new Date())
              .correlationId(claimed.getCorrelationId())
              .payload(claimed.getPayload())
              .build();

          try {
            orderMessagingService.sendOrder(event);
            log.info("Publisher [{}] successfully dispatched outbox event: id={}, eventId={}",
                instanceId, claimed.getId(), claimed.getEventId());
            return outboxService.markPublished(claimed.getId(), new Date());
          } catch (Throwable ex) {
            log.warn("Publisher [{}] failed to dispatch outbox event {}: {}",
                instanceId, claimed.getId(), ex.getMessage());
            int max = claimed.getMaxAttempts() > 0 ? claimed.getMaxAttempts() : maxRetries;
            return outboxService.markFailed(claimed.getId(), ex, claimed.getAttempts(), max, backoffMultiplierMs, new Date());
          }
        });
  }

}
