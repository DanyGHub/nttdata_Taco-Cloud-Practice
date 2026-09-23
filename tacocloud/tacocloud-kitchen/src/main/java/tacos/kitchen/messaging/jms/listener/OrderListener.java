package tacos.kitchen.messaging.jms.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import tacos.kitchen.workflow.OrderProcessingWorkflow;
import tacos.messaging.OrderEvent;

@Profile("jms-listener")
@Component
public class OrderListener {

  private final OrderProcessingWorkflow workflow;

  @Autowired
  public OrderListener(OrderProcessingWorkflow workflow) {
    this.workflow = workflow;
  }

  @JmsListener(destination = "${tacocloud.messaging.jms.destination:tacocloud.order.queue}")
  public void receiveOrder(OrderEvent event) {
    workflow.processOrderEvent(event);
  }

}
