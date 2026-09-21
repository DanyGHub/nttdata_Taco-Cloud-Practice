package tacos.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "kafka")
public class KafkaOrderMessagingService implements OrderMessagingService {

  private final KafkaTemplate<String, OrderEvent> kafkaTemplate;
  private final String topic;

  @Autowired
  @SuppressWarnings({"rawtypes", "unchecked"})
  public KafkaOrderMessagingService(
      KafkaTemplate kafkaTemplate,
      @Value("${tacocloud.messaging.kafka.topic:tacocloud.orders.topic}") String topic) {
    this.kafkaTemplate = (KafkaTemplate<String, OrderEvent>) kafkaTemplate;
    this.topic = topic;
  }

  @Override
  public void sendOrder(OrderEvent event) {
    String key = (event != null && event.getCorrelationId() != null)
        ? event.getCorrelationId()
        : (event != null ? event.getEventId() : null);
    kafkaTemplate.send(topic, key, event);
  }

}
