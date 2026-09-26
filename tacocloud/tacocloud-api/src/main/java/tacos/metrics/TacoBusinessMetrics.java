package tacos.metrics;

import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;

/**
 * Garantiza tags de baja cardinalidad y previene log/tag injection y fuga de datos sensibles.
 */
@Component
public class TacoBusinessMetrics {

  public static final String METRIC_ORDERS_CREATED = "tacocloud.orders.created";
  public static final String METRIC_ORDERS_FAILED = "tacocloud.orders.failed";
  public static final String METRIC_ORDERS_CANCELLED = "tacocloud.orders.cancelled";
  public static final String METRIC_COUPONS_APPLIED = "tacocloud.coupons.applied";
  public static final String METRIC_STOCK_REJECTED = "tacocloud.inventory.stock.rejected";
  public static final String METRIC_DLQ_EVENTS = "tacocloud.messaging.dlq.events";
  public static final String METRIC_PLACEMENT_TIME = "tacocloud.orders.placement.time";
  public static final String METRIC_KITCHEN_TIME = "tacocloud.kitchen.processing.time";
  public static final String METRIC_OUTBOX_BACKLOG = "tacocloud.outbox.backlog";

  private final MeterRegistry registry;
  private final AtomicLong outboxBacklog = new AtomicLong(0);

  public TacoBusinessMetrics() {
    this(new SimpleMeterRegistry());
  }

  @Autowired
  public TacoBusinessMetrics(@Autowired(required = false) MeterRegistry registry) {
    this.registry = registry != null ? registry : new SimpleMeterRegistry();
    initGauges();
  }

  private void initGauges() {
    Gauge.builder(METRIC_OUTBOX_BACKLOG, outboxBacklog, AtomicLong::get)
        .description("Current number of pending outbox events awaiting publication")
        .tag("status", "PENDING")
        .register(this.registry);

    // Pre-registro de métricas para garantizar su presencia inmediata en /actuator/metrics
    Counter.builder(METRIC_ORDERS_CREATED)
        .description("Total number of orders successfully placed")
        .tags("source", "API", "status", "CREATED")
        .register(this.registry);

    Counter.builder(METRIC_ORDERS_FAILED)
        .description("Total number of orders failed during placement")
        .tags("source", "API", "reason", "UNKNOWN")
        .register(this.registry);

    Counter.builder(METRIC_ORDERS_CANCELLED)
        .description("Total number of orders cancelled")
        .tags("reason", "USER_REQUEST")
        .register(this.registry);

    Counter.builder(METRIC_COUPONS_APPLIED)
        .description("Total number of coupon application attempts")
        .tags("type", "PERCENTAGE", "result", "SUCCESS")
        .register(this.registry);

    Counter.builder(METRIC_STOCK_REJECTED)
        .description("Total number of stock reservation rejections")
        .tags("ingredient", "GENERAL")
        .register(this.registry);

    Counter.builder(METRIC_DLQ_EVENTS)
        .description("Total number of events routed to DLQ")
        .tags("transport", "NOOP", "reason", "UNKNOWN")
        .register(this.registry);

    Timer.builder(METRIC_PLACEMENT_TIME)
        .description("Time taken to place an order end-to-end")
        .tag("result", "SUCCESS")
        .register(this.registry);

    Timer.builder(METRIC_KITCHEN_TIME)
        .description("Time taken by kitchen to process and prepare an order")
        .tag("result", "SUCCESS")
        .register(this.registry);
  }

  public MeterRegistry getRegistry() {
    return registry;
  }

  // --- Counters ---

  public void recordOrderCreated(String source, String status) {
    String safeSource = sanitizeTag(source, "API");
    String safeStatus = sanitizeTag(status, "CREATED");
    registry.counter(METRIC_ORDERS_CREATED, "source", safeSource, "status", safeStatus).increment();
  }

  public void recordOrderFailed(String source, String reason) {
    String safeSource = sanitizeTag(source, "API");
    String safeReason = sanitizeTag(reason, "SYSTEM_ERROR");
    registry.counter(METRIC_ORDERS_FAILED, "source", safeSource, "reason", safeReason).increment();
  }

  public void recordOrderCancelled(String reason) {
    String safeReason = sanitizeTag(reason, "USER_REQUEST");
    registry.counter(METRIC_ORDERS_CANCELLED, "reason", safeReason).increment();
  }

  public void recordCouponApplied(String type, String result) {
    String safeType = sanitizeTag(type, "NONE");
    String safeResult = sanitizeTag(result, "SUCCESS");
    registry.counter(METRIC_COUPONS_APPLIED, "type", safeType, "result", safeResult).increment();
  }

  public void recordStockRejected(String ingredient) {
    String safeIngredient = sanitizeTag(ingredient, "UNKNOWN");
    registry.counter(METRIC_STOCK_REJECTED, "ingredient", safeIngredient).increment();
  }

  public void recordDlqEvent(String transport, String reason) {
    String safeTransport = sanitizeTag(transport, "RABBIT");
    String safeReason = sanitizeTag(reason, "PERMANENT_VALIDATION");
    registry.counter(METRIC_DLQ_EVENTS, "transport", safeTransport, "reason", safeReason).increment();
  }

  // --- Timers ---

  public Timer.Sample startPlacementTimer() {
    return Timer.start(registry);
  }

  public void stopPlacementTimer(Timer.Sample sample, String result) {
    if (sample != null) {
      String safeResult = sanitizeTag(result, "SUCCESS");
      sample.stop(Timer.builder(METRIC_PLACEMENT_TIME)
          .description("Latency of order placement operation")
          .tag("result", safeResult)
          .register(registry));
    }
  }

  public void recordPlacementTime(long durationMillis, String result) {
    String safeResult = sanitizeTag(result, "SUCCESS");
    Timer.builder(METRIC_PLACEMENT_TIME)
        .description("Latency of order placement operation")
        .tag("result", safeResult)
        .register(registry)
        .record(durationMillis, TimeUnit.MILLISECONDS);
  }

  public void recordKitchenProcessingTime(long durationMillis, String result) {
    String safeResult = sanitizeTag(result, "SUCCESS");
    Timer.builder(METRIC_KITCHEN_TIME)
        .description("Latency of kitchen order processing")
        .tag("result", safeResult)
        .register(registry)
        .record(durationMillis, TimeUnit.MILLISECONDS);
  }

  // --- Gauges ---

  public void setOutboxBacklog(long count) {
    outboxBacklog.set(Math.max(0, count));
  }

  public void incrementOutboxBacklog() {
    outboxBacklog.incrementAndGet();
  }

  public void decrementOutboxBacklog() {
    outboxBacklog.updateAndGet(val -> Math.max(0, val - 1));
  }

  public long getOutboxBacklog() {
    return outboxBacklog.get();
  }

  // --- Normalizador y sanitizador de tags de baja cardinalidad ---
  public String sanitizeTag(String value, String defaultValue) {
    if (value == null || value.trim().isEmpty()) {
      return defaultValue;
    }
    String cleaned = value.trim().replaceAll("[^a-zA-Z0-9_-]", "_");
    if (cleaned.length() > 32) {
      cleaned = cleaned.substring(0, 32);
    }
    return cleaned.toUpperCase();
  }

}
