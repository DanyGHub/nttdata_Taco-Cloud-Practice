package tacos.metrics;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.Meter;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.micrometer.core.instrument.Timer;

public class TacoBusinessMetricsTest {

  private SimpleMeterRegistry meterRegistry;
  private TacoBusinessMetrics metrics;

  @BeforeEach
  public void setUp() {
    meterRegistry = new SimpleMeterRegistry();
    metrics = new TacoBusinessMetrics(meterRegistry);
  }

  @Test
  @DisplayName("Counters de negocio incrementan correctamente con SimpleMeterRegistry")
  public void testCountersIncrement() {
    // 1. Órdenes creadas
    metrics.recordOrderCreated("api", "CREATED");
    metrics.recordOrderCreated("api", "CREATED");
    metrics.recordOrderCreated("email", "CREATED");

    Counter apiOrders = meterRegistry.find(TacoBusinessMetrics.METRIC_ORDERS_CREATED)
        .tag("source", "API")
        .tag("status", "CREATED")
        .counter();
    assertThat(apiOrders).isNotNull();
    assertThat(apiOrders.count()).isEqualTo(2.0);

    Counter emailOrders = meterRegistry.find(TacoBusinessMetrics.METRIC_ORDERS_CREATED)
        .tag("source", "EMAIL")
        .counter();
    assertThat(emailOrders).isNotNull();
    assertThat(emailOrders.count()).isEqualTo(1.0);

    // 2. Órdenes fallidas
    metrics.recordOrderFailed("api", "PAYMENT_FAILED");
    Counter failedOrders = meterRegistry.find(TacoBusinessMetrics.METRIC_ORDERS_FAILED)
        .tag("reason", "PAYMENT_FAILED")
        .counter();
    assertThat(failedOrders).isNotNull();
    assertThat(failedOrders.count()).isEqualTo(1.0);

    // 3. Órdenes canceladas
    metrics.recordOrderCancelled("USER_REQUEST");
    Counter cancelledOrders = meterRegistry.find(TacoBusinessMetrics.METRIC_ORDERS_CANCELLED)
        .tag("reason", "USER_REQUEST")
        .counter();
    assertThat(cancelledOrders).isNotNull();
    assertThat(cancelledOrders.count()).isEqualTo(1.0);

    // 4. Cupones aplicados
    metrics.recordCouponApplied("PERCENTAGE", "SUCCESS");
    metrics.recordCouponApplied("FIXED", "REJECTED");
    Counter successCoupons = meterRegistry.find(TacoBusinessMetrics.METRIC_COUPONS_APPLIED)
        .tag("type", "PERCENTAGE")
        .tag("result", "SUCCESS")
        .counter();
    assertThat(successCoupons).isNotNull();
    assertThat(successCoupons.count()).isEqualTo(1.0);

    // 5. Rechazos de inventario
    metrics.recordStockRejected("FLTO");
    Counter stockRejected = meterRegistry.find(TacoBusinessMetrics.METRIC_STOCK_REJECTED)
        .tag("ingredient", "FLTO")
        .counter();
    assertThat(stockRejected).isNotNull();
    assertThat(stockRejected.count()).isEqualTo(1.0);

    // 6. Eventos DLQ
    metrics.recordDlqEvent("rabbit", "PERMANENT_VALIDATION");
    Counter dlqEvents = meterRegistry.find(TacoBusinessMetrics.METRIC_DLQ_EVENTS)
        .tag("transport", "RABBIT")
        .tag("reason", "PERMANENT_VALIDATION")
        .counter();
    assertThat(dlqEvents).isNotNull();
    assertThat(dlqEvents.count()).isEqualTo(1.0);
  }

  @Test
  @DisplayName("Timers de placement y cocina miden latencia correctamente")
  public void testTimers() throws InterruptedException {
    // Timer directo
    metrics.recordPlacementTime(150, "SUCCESS");
    Timer placementTimer = meterRegistry.find(TacoBusinessMetrics.METRIC_PLACEMENT_TIME)
        .tag("result", "SUCCESS")
        .timer();
    assertThat(placementTimer).isNotNull();
    assertThat(placementTimer.count()).isEqualTo(1L);
    assertThat(placementTimer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(149.0);

    // Timer sample (start/stop)
    Timer.Sample sample = metrics.startPlacementTimer();
    Thread.sleep(10);
    metrics.stopPlacementTimer(sample, "SUCCESS");
    assertThat(placementTimer.count()).isEqualTo(2L);

    // Kitchen timer
    metrics.recordKitchenProcessingTime(250, "SUCCESS");
    Timer kitchenTimer = meterRegistry.find(TacoBusinessMetrics.METRIC_KITCHEN_TIME)
        .tag("result", "SUCCESS")
        .timer();
    assertThat(kitchenTimer).isNotNull();
    assertThat(kitchenTimer.count()).isEqualTo(1L);
    assertThat(kitchenTimer.totalTime(TimeUnit.MILLISECONDS)).isGreaterThanOrEqualTo(249.0);
  }

  @Test
  @DisplayName("Gauge de outbox backlog refleja el estado en tiempo real sin bloquear")
  public void testOutboxBacklogGauge() {
    Gauge gauge = meterRegistry.find(TacoBusinessMetrics.METRIC_OUTBOX_BACKLOG)
        .tag("status", "PENDING")
        .gauge();

    assertThat(gauge).isNotNull();
    assertThat(gauge.value()).isEqualTo(0.0);

    metrics.setOutboxBacklog(5);
    assertThat(gauge.value()).isEqualTo(5.0);

    metrics.incrementOutboxBacklog();
    assertThat(gauge.value()).isEqualTo(6.0);

    metrics.decrementOutboxBacklog();
    assertThat(gauge.value()).isEqualTo(5.0);

    metrics.setOutboxBacklog(0);
    assertThat(gauge.value()).isEqualTo(0.0);

    // No permite valores negativos
    metrics.decrementOutboxBacklog();
    assertThat(gauge.value()).isEqualTo(0.0);
  }

  @Test
  @DisplayName("Sanitización de tags restringe cardinalidad y previene injection")
  public void testTagSanitization() {
    // Caracteres extraños y espacios
    metrics.recordOrderFailed("api endpoint/v1", "DB Connection Failed! \nInjection");

    Counter counter = meterRegistry.find(TacoBusinessMetrics.METRIC_ORDERS_FAILED)
        .tag("source", "API_ENDPOINT_V1")
        .tag("reason", "DB_CONNECTION_FAILED___INJECTION")
        .counter();

    assertThat(counter).isNotNull();
    assertThat(counter.count()).isEqualTo(1.0);

    // Verificación de que NUNCA aparecen orderId, userId ni correlationId como tags
    for (Meter meter : meterRegistry.getMeters()) {
      meter.getId().getTags().forEach(tag -> {
        assertThat(tag.getKey()).isNotIn("orderId", "userId", "correlationId", "user", "order");
        assertThat(tag.getValue()).doesNotContain("\n", "\r", "\t");
      });
    }
  }

}
