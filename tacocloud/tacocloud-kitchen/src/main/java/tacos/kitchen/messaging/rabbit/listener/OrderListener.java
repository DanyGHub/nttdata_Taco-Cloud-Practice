package tacos.kitchen.messaging.rabbit.listener;

import org.springframework.amqp.rabbit.annotation.RabbitListener;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Profile;
import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tacos.kitchen.exception.PermanentProcessingException;
import tacos.kitchen.exception.TransientProcessingException;
import tacos.kitchen.messaging.dlq.DeadLetterQueueService;
import tacos.kitchen.metrics.KitchenConsumerMetrics;
import tacos.kitchen.workflow.OrderProcessingWorkflow;
import tacos.messaging.OrderEvent;

@Profile("rabbitmq-listener")
@Component
@Slf4j
public class OrderListener {

  private final OrderProcessingWorkflow workflow;
  private final DeadLetterQueueService dlqService;
  private final KitchenConsumerMetrics metrics;

  @Autowired
  public OrderListener(
      OrderProcessingWorkflow workflow,
      DeadLetterQueueService dlqService,
      KitchenConsumerMetrics metrics) {
    this.workflow = workflow;
    this.dlqService = dlqService;
    this.metrics = metrics;
  }

  @RabbitListener(
      queues = "${tacocloud.messaging.rabbit.queue:${tacocloud.messaging.rabbit.destination:tacocloud.order.queue}}",
      containerFactory = "rabbitListenerContainerFactory"
  )
  public void receiveOrder(OrderEvent event) {
    try {
      workflow.processOrderEvent(event);
    } catch (PermanentProcessingException ppe) {
      log.error("[RABBIT CONSUMER] Permanent failure for event '{}': {}. Enrouting directly to DLQ.",
          event != null ? event.getEventId() : "NULL", ppe.getMessage());
      dlqService.sendToDlq(event, ppe, 1);
      // Termina limpiamente para que el contenedor confirme el mensaje venenoso fuera de la cola principal
    } catch (TransientProcessingException tpe) {
      log.warn("[RABBIT CONSUMER] Transient failure for event '{}': {}. Forwarding to retry interceptor.",
          event != null ? event.getEventId() : "NULL", tpe.getMessage());
      metrics.incrementRetried();
      throw tpe;
    } catch (Exception ex) {
      log.warn("[RABBIT CONSUMER] Unexpected failure for event '{}': {}. Forwarding to retry interceptor.",
          event != null ? event.getEventId() : "NULL", ex.getMessage());
      metrics.incrementRetried();
      throw ex;
    }
  }

}
