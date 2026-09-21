package tacos.messaging;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.stereotype.Service;

@Service
public class KafkaOrderMessagingService implements OrderMessagingService {

  private final KafkaTemplate<String, OrderEvent> kafkaTemplate;

  @Autowired
  public KafkaOrderMessagingService(KafkaTemplate<String, OrderEvent> kafkaTemplate) {
    this.kafkaTemplate = kafkaTemplate;
  }

  @Override
  public void sendOrder(OrderEvent event) {
    String key = (event != null && event.getCorrelationId() != null)
        ? event.getCorrelationId()
        : (event != null ? event.getEventId() : null);
    kafkaTemplate.send("tacocloud.orders.topic", key, event);
  }

}
