package tacos.messaging;

import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.jms.support.converter.MappingJackson2MessageConverter;

@Configuration
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "jms")
public class JmsMessagingConfig {

  @Bean
  public MappingJackson2MessageConverter messageConverter() {
    MappingJackson2MessageConverter messageConverter = new MappingJackson2MessageConverter();
    messageConverter.setTypeIdPropertyName("_typeId");

    Map<String, Class<?>> typeIdMappings = new HashMap<>();
    typeIdMappings.put("orderEvent", OrderEvent.class);
    typeIdMappings.put("order", OrderEvent.class);
    messageConverter.setTypeIdMappings(typeIdMappings);

    return messageConverter;
  }

}
