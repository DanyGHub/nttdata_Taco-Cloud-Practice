package tacos.messaging.config;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.SpringApplication;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.StandardEnvironment;
import org.springframework.mock.env.MockEnvironment;

public class MessagingTransportEnvironmentPostProcessorTest {

  private final MessagingTransportEnvironmentPostProcessor processor =
      new MessagingTransportEnvironmentPostProcessor();

  @Test
  void whenTransportIsNoop_thenBrokerHealthIndicatorsAreDisabled() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("tacocloud.messaging.transport", "noop");

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("management.health.rabbit.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.health.jms.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.health.kafka.enabled")).isEqualTo("false");
  }

  @Test
  void whenTransportIsDefault_thenBrokerHealthIndicatorsAreDisabled() {
    MockEnvironment env = new MockEnvironment();

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("management.health.rabbit.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.health.jms.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.health.kafka.enabled")).isEqualTo("false");
  }

  @Test
  void whenTransportIsJms_thenOnlyJmsHealthIndicatorIsEnabled() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("tacocloud.messaging.transport", "jms");

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("management.health.jms.enabled")).isEqualTo("true");
    assertThat(env.getProperty("management.health.rabbit.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.health.kafka.enabled")).isEqualTo("false");
  }

  @Test
  void whenTransportIsRabbit_thenOnlyRabbitHealthIndicatorIsEnabled() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("tacocloud.messaging.transport", "rabbit");

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("management.health.rabbit.enabled")).isEqualTo("true");
    assertThat(env.getProperty("management.health.jms.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.health.kafka.enabled")).isEqualTo("false");
  }

  @Test
  void whenTransportIsKafka_thenOnlyKafkaHealthIndicatorIsEnabled() {
    MockEnvironment env = new MockEnvironment();
    env.setProperty("tacocloud.messaging.transport", "kafka");

    processor.postProcessEnvironment(env, new SpringApplication());

    assertThat(env.getProperty("management.health.kafka.enabled")).isEqualTo("true");
    assertThat(env.getProperty("management.health.rabbit.enabled")).isEqualTo("false");
    assertThat(env.getProperty("management.health.jms.enabled")).isEqualTo("false");
  }
}
