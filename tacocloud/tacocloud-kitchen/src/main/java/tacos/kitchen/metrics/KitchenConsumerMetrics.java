package tacos.kitchen.metrics;

import java.util.HashMap;
import java.util.Map;

import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;

@Component
public class KitchenConsumerMetrics {

  private final Counter receivedCounter;
  private final Counter processedCounter;
  private final Counter duplicateCounter;
  private final Counter retriedCounter;
  private final Counter dlqCounter;
  private final Counter replayedCounter;

  public KitchenConsumerMetrics(MeterRegistry meterRegistry) {
    this.receivedCounter = Counter.builder("kitchen.orders.received")
        .description("Total messages received by kitchen consumer listeners")
        .register(meterRegistry);

    this.processedCounter = Counter.builder("kitchen.orders.processed")
        .description("Total new order events successfully processed")
        .register(meterRegistry);

    this.duplicateCounter = Counter.builder("kitchen.orders.duplicate")
        .description("Total duplicate order events detected and skipped idempotently")
        .register(meterRegistry);

    this.retriedCounter = Counter.builder("kitchen.orders.retried")
        .description("Total retry attempts performed for transient failures")
        .register(meterRegistry);

    this.dlqCounter = Counter.builder("kitchen.orders.dlq")
        .description("Total poisoned or exhausted events routed to DLQ")
        .register(meterRegistry);

    this.replayedCounter = Counter.builder("kitchen.orders.replayed")
        .description("Total order events replayed via controlled procedure")
        .register(meterRegistry);
  }

  public void incrementReceived() {
    receivedCounter.increment();
  }

  public void incrementProcessed() {
    processedCounter.increment();
  }

  public void incrementDuplicate() {
    duplicateCounter.increment();
  }

  public void incrementRetried() {
    retriedCounter.increment();
  }

  public void incrementDlq() {
    dlqCounter.increment();
  }

  public void incrementReplayed() {
    replayedCounter.increment();
  }

  public double getReceivedCount() {
    return receivedCounter.count();
  }

  public double getProcessedCount() {
    return processedCounter.count();
  }

  public double getDuplicateCount() {
    return duplicateCounter.count();
  }

  public double getRetriedCount() {
    return retriedCounter.count();
  }

  public double getDlqCount() {
    return dlqCounter.count();
  }

  public double getReplayedCount() {
    return replayedCounter.count();
  }

  public Map<String, Double> getSummary() {
    Map<String, Double> summary = new HashMap<>();
    summary.put("received", getReceivedCount());
    summary.put("processed", getProcessedCount());
    summary.put("duplicate", getDuplicateCount());
    summary.put("retried", getRetriedCount());
    summary.put("dlq", getDlqCount());
    summary.put("replayed", getReplayedCount());
    return summary;
  }

}
