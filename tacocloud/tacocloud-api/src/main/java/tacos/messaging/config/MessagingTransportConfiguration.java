package tacos.messaging.config;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.SmartInitializingSingleton;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;

import tacos.messaging.OrderMessagingService;

@Configuration
public class MessagingTransportConfiguration {

  public static final String PROPERTY_TRANSPORT = "tacocloud.messaging.transport";
  public static final Set<String> SUPPORTED_TRANSPORTS =
      Collections.unmodifiableSet(new HashSet<>(Arrays.asList("noop", "jms", "rabbit", "kafka")));

  private final Environment environment;

  public MessagingTransportConfiguration(Environment environment) {
    this.environment = environment;
    validateTransportProperty();
  }

  private void validateTransportProperty() {
    String transport = environment.getProperty(PROPERTY_TRANSPORT);
    boolean isProd = Arrays.stream(environment.getActiveProfiles())
        .anyMatch(p -> p.equalsIgnoreCase("prod") || p.equalsIgnoreCase("production"));

    if (isProd) {
      if (transport == null || transport.trim().isEmpty()) {
        throw new IllegalStateException(
            "tacocloud.messaging.transport must be explicitly configured in production environment (noop is not permitted by default)");
      }
      if ("noop".equalsIgnoreCase(transport.trim())) {
        throw new IllegalStateException(
            "tacocloud.messaging.transport='noop' is not permitted in production environment. Choose one of: jms, rabbit, kafka");
      }
    }

    if (transport != null && !transport.trim().isEmpty()) {
      String normalized = transport.trim().toLowerCase();
      if (!SUPPORTED_TRANSPORTS.contains(normalized)) {
        throw new IllegalStateException(
            "Unknown messaging transport '" + transport + "'. Supported transports are: " + SUPPORTED_TRANSPORTS);
      }
    }
  }

  @Bean
  public SmartInitializingSingleton orderMessagingServiceValidator(
      ObjectProvider<OrderMessagingService> messagingServices) {
    return () -> {
      List<OrderMessagingService> services = messagingServices.orderedStream().collect(Collectors.toList());
      if (services.isEmpty()) {
        throw new IllegalStateException(
            "No OrderMessagingService bean was activated for transport: '" 
            + environment.getProperty(PROPERTY_TRANSPORT) + "'. Expected exactly one adapter.");
      }
      if (services.size() > 1) {
        List<String> names = services.stream().map(s -> s.getClass().getSimpleName()).collect(Collectors.toList());
        throw new IllegalStateException(
            "Multiple OrderMessagingService beans are active (" + names 
            + "). Exactly one transport adapter must be active.");
      }
    };
  }

}
