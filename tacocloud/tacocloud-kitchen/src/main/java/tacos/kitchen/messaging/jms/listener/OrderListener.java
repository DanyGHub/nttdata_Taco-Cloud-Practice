package tacos.kitchen.messaging.jms.listener;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.jms.annotation.JmsListener;
import org.springframework.stereotype.Component;

import tacos.kitchen.KitchenUI;
import tacos.messaging.OrderEvent;

@Profile("jms-listener")
@Component
public class OrderListener {

  private final KitchenUI ui;

  @Autowired
  public OrderListener(KitchenUI ui) {
    this.ui = ui;
  }

  @JmsListener(destination = "${tacocloud.messaging.jms.destination:tacocloud.order.queue}")
  public void receiveOrder(OrderEvent event) {
    ui.displayOrder(event);
  }

}
