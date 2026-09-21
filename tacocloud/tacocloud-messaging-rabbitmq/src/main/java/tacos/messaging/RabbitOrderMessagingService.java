package tacos.messaging;

import org.springframework.amqp.AmqpException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

@Service
@ConditionalOnProperty(name = "tacocloud.messaging.transport", havingValue = "rabbit")
public class RabbitOrderMessagingService implements OrderMessagingService {

  private final RabbitTemplate rabbit;
  private final String exchange;
  private final String routingKey;

  @Autowired
  public RabbitOrderMessagingService(
      RabbitTemplate rabbit,
      @Value("${tacocloud.messaging.rabbit.exchange:}") String exchange,
      @Value("${tacocloud.messaging.rabbit.routing-key:${tacocloud.messaging.rabbit.queue:tacocloud.order.queue}}") String routingKey) {
    this.rabbit = rabbit;
    this.exchange = exchange;
    this.routingKey = routingKey;
  }

  @Override
  public void sendOrder(OrderEvent event) {
    rabbit.convertAndSend(exchange, routingKey, event, new MessagePostProcessor() {
      @Override
      public Message postProcessMessage(Message message) throws AmqpException {
        MessageProperties props = message.getMessageProperties();
        props.setHeader("X_ORDER_SOURCE", "WEB");
        return message;
      }
    });
  }

}
