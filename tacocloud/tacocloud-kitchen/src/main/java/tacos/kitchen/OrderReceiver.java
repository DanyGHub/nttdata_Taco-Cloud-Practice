package tacos.kitchen;

import tacos.messaging.OrderEvent;

public interface OrderReceiver {

  OrderEvent receiveOrder();

}