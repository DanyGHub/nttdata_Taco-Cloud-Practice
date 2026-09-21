package tacos.messaging;

import javax.jms.JMSException;
import javax.jms.Message;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "jms")
public class JmsOrderMessagingService implements OrderMessagingService {

  private final JmsTemplate jms;
  private final String destination;

  @Autowired
  public JmsOrderMessagingService(
      JmsTemplate jms,
      @Value("${tacocloud.messaging.jms.destination:tacocloud.order.queue}") String destination) {
    this.jms = jms;
    this.destination = destination;
  }

  @Override
  public void sendOrder(OrderEvent event) {
    jms.convertAndSend(destination, event, this::addOrderSource);
  }

  private Message addOrderSource(Message message) throws JMSException {
    message.setStringProperty("X_ORDER_SOURCE", "WEB");
    return message;
  }

}
