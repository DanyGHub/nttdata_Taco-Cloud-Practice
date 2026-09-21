package tacos.kitchen.messaging.jms;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.core.JmsTemplate;
import org.springframework.stereotype.Component;

import tacos.kitchen.OrderReceiver;
import tacos.messaging.OrderEvent;

@Profile("jms-template")
@Component("templateOrderReceiver")
public class JmsOrderReceiver implements OrderReceiver {

  private final JmsTemplate jms;
  private final String destination;

  public JmsOrderReceiver(
      JmsTemplate jms,
      @Value("${tacocloud.messaging.jms.destination:tacocloud.order.queue}") String destination) {
    this.jms = jms;
    this.destination = destination;
  }

  @Override
  public OrderEvent receiveOrder() {
    return (OrderEvent) jms.receiveAndConvert(destination);
  }

}
