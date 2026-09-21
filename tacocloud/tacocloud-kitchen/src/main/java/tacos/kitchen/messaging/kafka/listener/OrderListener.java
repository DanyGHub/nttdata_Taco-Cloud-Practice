package tacos.kitchen.messaging.kafka.listener;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tacos.kitchen.KitchenUI;
import tacos.messaging.OrderEvent;

@Profile("kafka-listener")
@Component
@Slf4j
public class OrderListener {

  private final KitchenUI ui;

  @Autowired
  public OrderListener(KitchenUI ui) {
    this.ui = ui;
  }

  @KafkaListener(topics = "tacocloud.orders.topic")
  public void handle(OrderEvent event, ConsumerRecord<String, OrderEvent> record) {
    log.info("Received OrderEvent from partition {} with timestamp {}",
        record.partition(), record.timestamp());
    ui.displayOrder(event);
  }

}
