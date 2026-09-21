package tacos.messaging.config;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.env.EnvironmentPostProcessor;
import org.springframework.core.Ordered;
import org.springframework.core.env.ConfigurableEnvironment;
import org.springframework.core.env.MapPropertySource;
import org.springframework.core.env.MutablePropertySources;

/**
 * Dynamically configures Actuator health indicators based on the selected messaging transport.
 * Prevents inactive messaging brokers (JMS, RabbitMQ, Kafka) from attempting network connections
 * during health checks and causing false 'DOWN' statuses in Spring Boot Admin and /actuator/health.
 */
public class MessagingTransportEnvironmentPostProcessor implements EnvironmentPostProcessor, Ordered {

  public static final String PROPERTY_SOURCE_NAME = "messagingTransportHealthDefaults";

  @Override
  public void postProcessEnvironment(ConfigurableEnvironment environment, SpringApplication application) {
    String rawTransport = environment.getProperty("tacocloud.messaging.transport");
    String transport = (rawTransport != null) ? rawTransport.trim().toLowerCase() : "noop";

    Map<String, Object> props = new HashMap<>();

    // Inactive broker health indicators must be disabled so they do not attempt TCP connections
    props.put("management.health.rabbit.enabled", "false");
    props.put("management.health.jms.enabled", "false");
    props.put("management.health.kafka.enabled", "false");

    if ("jms".equals(transport)) {
      props.put("management.health.jms.enabled", "true");
    } else if ("rabbit".equals(transport)) {
      props.put("management.health.rabbit.enabled", "true");
    } else if ("kafka".equals(transport)) {
      props.put("management.health.kafka.enabled", "true");
    }

    MutablePropertySources propertySources = environment.getPropertySources();
    if (propertySources.contains(PROPERTY_SOURCE_NAME)) {
      propertySources.replace(PROPERTY_SOURCE_NAME, new MapPropertySource(PROPERTY_SOURCE_NAME, props));
    } else {
      if (propertySources.contains("systemProperties")) {
        propertySources.addAfter("systemProperties", new MapPropertySource(PROPERTY_SOURCE_NAME, props));
      } else {
        propertySources.addFirst(new MapPropertySource(PROPERTY_SOURCE_NAME, props));
      }
    }
  }

  @Override
  public int getOrder() {
    return Ordered.HIGHEST_PRECEDENCE + 20;
  }
}
