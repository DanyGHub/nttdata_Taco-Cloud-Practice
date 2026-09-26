package tacos.actuator;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.Status;
import org.springframework.test.util.ReflectionTestUtils;

public class MessagingBrokerHealthIndicatorTest {

  @Test
  @DisplayName("Transport noop retorna UP con modo en memoria")
  public void testNoopTransport() {
    MessagingBrokerHealthIndicator indicator = new MessagingBrokerHealthIndicator();
    ReflectionTestUtils.setField(indicator, "transport", "noop");

    Health health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("activeTransport", "noop");
    assertThat(health.getDetails()).containsEntry("deliveryMode", "IN_MEMORY");
  }

  @Test
  @DisplayName("Transport rabbit retorna UP con delivery BROKER_QUEUED")
  public void testRabbitTransport() {
    MessagingBrokerHealthIndicator indicator = new MessagingBrokerHealthIndicator();
    ReflectionTestUtils.setField(indicator, "transport", "rabbit");

    Health health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("activeTransport", "rabbitmq");
    assertThat(health.getDetails()).containsEntry("deliveryMode", "BROKER_QUEUED");
  }

  @Test
  @DisplayName("Transport kafka retorna UP con delivery BROKER_STREAM")
  public void testKafkaTransport() {
    MessagingBrokerHealthIndicator indicator = new MessagingBrokerHealthIndicator();
    ReflectionTestUtils.setField(indicator, "transport", "kafka");

    Health health = indicator.health();
    assertThat(health.getStatus()).isEqualTo(Status.UP);
    assertThat(health.getDetails()).containsEntry("activeTransport", "kafka");
    assertThat(health.getDetails()).containsEntry("deliveryMode", "BROKER_STREAM");
  }

  @Test
  @DisplayName("Transport desconocido retorna estado UNKNOWN")
  public void testUnknownTransport() {
    MessagingBrokerHealthIndicator indicator = new MessagingBrokerHealthIndicator();
    ReflectionTestUtils.setField(indicator, "transport", "unknown_transport");

    Health health = indicator.health();
    assertThat(health.getStatus().getCode()).isEqualTo("UNKNOWN");
    assertThat(health.getDetails()).containsEntry("activeTransport", "unknown_transport");
  }

}
