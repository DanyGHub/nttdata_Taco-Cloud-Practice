package tacos.kitchen.messaging.dlq;

import java.util.Date;

import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tacos.kitchen.domain.ProcessedEvent;
import tacos.kitchen.domain.ProcessedStatus;
import tacos.kitchen.metrics.KitchenConsumerMetrics;
import tacos.kitchen.repository.ProcessedEventRepository;
import tacos.messaging.OrderEvent;

@Service
@Slf4j
public class DeadLetterQueueService {

  private final RabbitTemplate rabbitTemplate;
  private final ProcessedEventRepository processedEventRepository;
  private final KitchenConsumerMetrics metrics;

  @Value("${tacocloud.messaging.rabbit.dlx:tacocloud.order.dlx}")
  private String dlxExchange;

  @Value("${tacocloud.messaging.rabbit.dlq:tacocloud.order.queue.dlq}")
  private String dlqRoutingKey;

  @Autowired
  public DeadLetterQueueService(
      @Autowired(required = false) RabbitTemplate rabbitTemplate,
      ProcessedEventRepository processedEventRepository,
      KitchenConsumerMetrics metrics) {
    this.rabbitTemplate = rabbitTemplate;
    this.processedEventRepository = processedEventRepository;
    this.metrics = metrics;
  }

  public void sendToDlq(OrderEvent event, Throwable cause, int retryCount) {
    String eventId = event != null ? event.getEventId() : "UNKNOWN";
    String correlationId = event != null ? event.getCorrelationId() : "UNKNOWN";
    String exceptionMessage = cause != null ? cause.getMessage() : "Unknown processing failure";
    String exceptionClass = cause != null ? cause.getClass().getName() : "UnknownException";

    log.warn("[DLQ] Routing poisoned or exhausted event to DLQ '{}' (exchange: '{}'). eventId={}, correlationId={}, cause={}",
        dlqRoutingKey, dlxExchange, eventId, correlationId, exceptionMessage);

    // 1. Publish to RabbitMQ DLQ if template is configured
    if (rabbitTemplate != null && event != null) {
      try {
        rabbitTemplate.convertAndSend(dlxExchange, dlqRoutingKey, event, message -> {
          message.getMessageProperties().setHeader("x-original-event-id", eventId);
          message.getMessageProperties().setHeader("x-correlation-id", correlationId);
          message.getMessageProperties().setHeader("x-exception-message", exceptionMessage);
          message.getMessageProperties().setHeader("x-exception-class", exceptionClass);
          message.getMessageProperties().setHeader("x-failed-at", new Date().toString());
          message.getMessageProperties().setHeader("x-retry-count", retryCount);
          return message;
        });
        log.info("[DLQ] Successfully published event {} to DLQ", eventId);
      } catch (Exception e) {
        log.error("[DLQ] Error publishing event {} to DLQ broker: {}", eventId, e.getMessage(), e);
      }
    }

    // 2. Persist or update ProcessedEvent with status FAILED for audit and controlled replay
    if (event != null && event.getEventId() != null) {
      try {
        ProcessedEvent processedEvent = processedEventRepository.findByEventId(event.getEventId())
            .orElseGet(() -> ProcessedEvent.builder()
                .eventId(event.getEventId())
                .eventType(event.getEventType() != null ? event.getEventType().name() : "UNKNOWN")
                .version(event.getVersion())
                .orderId(event.getPayload() != null ? event.getPayload().getOrderId() : null)
                .correlationId(event.getCorrelationId())
                .build());

        processedEvent.setStatus(ProcessedStatus.FAILED);
        processedEvent.setProcessedAt(new Date());
        processedEvent.setErrorMessage(exceptionMessage);
        processedEvent.setResultSummary("Failed and routed to DLQ after " + retryCount + " attempts");
        processedEventRepository.save(processedEvent);
      } catch (Exception e) {
        log.error("[DLQ] Could not persist failure in processed_events: {}", e.getMessage());
      }
    }

    // 3. Increment metric
    metrics.incrementDlq();
  }

}
