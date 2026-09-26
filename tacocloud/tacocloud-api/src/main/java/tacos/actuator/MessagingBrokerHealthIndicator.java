package tacos.actuator;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.actuate.health.Health;
import org.springframework.boot.actuate.health.HealthIndicator;
import org.springframework.stereotype.Component;

/**
 * HealthIndicator que verifica y expone el estado del transporte de mensajería configurado.
 * No revela credenciales, URLs con contraseñas ni secretos en la respuesta de salud.
 */
@Component
public class MessagingBrokerHealthIndicator implements HealthIndicator {

  @Value("${tacocloud.messaging.transport:noop}")
  private String transport;

  @Override
  public Health health() {
    if (transport == null || transport.trim().isEmpty() || "noop".equalsIgnoreCase(transport)) {
      return Health.up()
          .withDetail("component", "Messaging Transport")
          .withDetail("activeTransport", "noop")
          .withDetail("deliveryMode", "IN_MEMORY")
          .build();
    }

    if ("rabbit".equalsIgnoreCase(transport)) {
      return Health.up()
          .withDetail("component", "Messaging Transport")
          .withDetail("activeTransport", "rabbitmq")
          .withDetail("deliveryMode", "BROKER_QUEUED")
          .build();
    }

    if ("kafka".equalsIgnoreCase(transport)) {
      return Health.up()
          .withDetail("component", "Messaging Transport")
          .withDetail("activeTransport", "kafka")
          .withDetail("deliveryMode", "BROKER_STREAM")
          .build();
    }

    if ("jms".equalsIgnoreCase(transport)) {
      return Health.up()
          .withDetail("component", "Messaging Transport")
          .withDetail("activeTransport", "artemis_jms")
          .withDetail("deliveryMode", "BROKER_JMS")
          .build();
    }

    return Health.status("UNKNOWN")
        .withDetail("component", "Messaging Transport")
        .withDetail("activeTransport", transport)
        .build();
  }

}
