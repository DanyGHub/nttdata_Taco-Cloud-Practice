package tacos.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.JmsAutoConfiguration;
import org.springframework.boot.autoconfigure.jms.artemis.ArtemisAutoConfiguration;
import org.springframework.boot.autoconfigure.kafka.KafkaAutoConfiguration;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;

import tacos.messaging.config.MessagingTransportConfiguration;

public class MessagingTransportContextTest {

  private final ApplicationContextRunner contextRunner = new ApplicationContextRunner()
      .withConfiguration(AutoConfigurations.of(
          ArtemisAutoConfiguration.class,
          JmsAutoConfiguration.class,
          RabbitAutoConfiguration.class,
          KafkaAutoConfiguration.class
      ))
      .withUserConfiguration(
          MessagingTransportConfiguration.class,
          NoOpOrderMessagingService.class,
          JmsOrderMessagingService.class,
          JmsMessagingConfig.class,
          RabbitOrderMessagingService.class,
          RabbitMessagingConfig.class,
          KafkaOrderMessagingService.class
      );

  @Test
  void whenTransportIsNoop_thenOnlyNoOpServiceIsLoaded() {
    contextRunner
        .withPropertyValues("tacocloud.messaging.transport=noop")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(OrderMessagingService.class);
          assertThat(context).hasSingleBean(NoOpOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(JmsOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(RabbitOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(KafkaOrderMessagingService.class);
        });
  }

  @Test
  void whenTransportIsJms_thenOnlyJmsServiceIsLoaded() {
    contextRunner
        .withPropertyValues(
            "tacocloud.messaging.transport=jms",
            "spring.artemis.embedded.enabled=false"
        )
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(OrderMessagingService.class);
          assertThat(context).hasSingleBean(JmsOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(NoOpOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(RabbitOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(KafkaOrderMessagingService.class);
        });
  }

  @Test
  void whenTransportIsRabbit_thenOnlyRabbitServiceIsLoaded() {
    contextRunner
        .withPropertyValues("tacocloud.messaging.transport=rabbit")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(OrderMessagingService.class);
          assertThat(context).hasSingleBean(RabbitOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(NoOpOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(JmsOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(KafkaOrderMessagingService.class);
        });
  }

  @Test
  void whenTransportIsKafka_thenOnlyKafkaServiceIsLoaded() {
    contextRunner
        .withPropertyValues("tacocloud.messaging.transport=kafka")
        .run(context -> {
          assertThat(context).hasNotFailed();
          assertThat(context).hasSingleBean(OrderMessagingService.class);
          assertThat(context).hasSingleBean(KafkaOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(NoOpOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(JmsOrderMessagingService.class);
          assertThat(context).doesNotHaveBean(RabbitOrderMessagingService.class);
        });
  }

  @Test
  void whenTransportIsInvalid_thenContextFailsWithExplicitMessage() {
    contextRunner
        .withPropertyValues("tacocloud.messaging.transport=unknown")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(IllegalStateException.class)
              .hasMessageContaining("Unknown messaging transport 'unknown'");
        });
  }

  @Test
  void whenInProdAndTransportIsMissing_thenContextFails() {
    contextRunner
        .withPropertyValues("spring.profiles.active=prod")
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(IllegalStateException.class)
              .hasMessageContaining("tacocloud.messaging.transport must be explicitly configured in production");
        });
  }

  @Test
  void whenInProdAndTransportIsNoop_thenContextFails() {
    contextRunner
        .withPropertyValues(
            "spring.profiles.active=prod",
            "tacocloud.messaging.transport=noop"
        )
        .run(context -> {
          assertThat(context).hasFailed();
          assertThat(context.getStartupFailure())
              .hasRootCauseInstanceOf(IllegalStateException.class)
              .hasMessageContaining("'noop' is not permitted in production");
        });
  }

}
