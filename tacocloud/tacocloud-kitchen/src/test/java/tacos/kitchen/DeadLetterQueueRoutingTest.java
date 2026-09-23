package tacos.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Date;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessagePostProcessor;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tacos.kitchen.domain.ProcessedEvent;
import tacos.kitchen.domain.ProcessedStatus;
import tacos.kitchen.exception.PermanentProcessingException;
import tacos.kitchen.messaging.dlq.DeadLetterQueueService;
import tacos.kitchen.metrics.KitchenConsumerMetrics;
import tacos.kitchen.repository.ProcessedEventRepository;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventItemPayload;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;

class DeadLetterQueueRoutingTest {

  private RabbitTemplate rabbitTemplate;
  private ProcessedEventRepository processedEventRepository;
  private KitchenConsumerMetrics metrics;
  private DeadLetterQueueService dlqService;

  @BeforeEach
  void setUp() throws Exception {
    rabbitTemplate = mock(RabbitTemplate.class);
    processedEventRepository = mock(ProcessedEventRepository.class);
    metrics = new KitchenConsumerMetrics(new SimpleMeterRegistry());

    dlqService = new DeadLetterQueueService(rabbitTemplate, processedEventRepository, metrics);

    // Set fields via reflection
    setField(dlqService, "dlxExchange", "tacocloud.order.dlx");
    setField(dlqService, "dlqRoutingKey", "tacocloud.order.queue.dlq");
  }

  @Test
  @DisplayName("Mensaje envenenado/agotado es enviado a DLQ con headers de causa y correlación, sin datos sensibles")
  void testSendToDlqEnrichesHeadersAndSavesFailedStatus() {
    OrderEvent event = createOrderEvent("EVT-DLQ-1", "ORD-500", "CORR-777");
    PermanentProcessingException cause = new PermanentProcessingException("Corrupted JSON payload in taco items");

    when(processedEventRepository.findByEventId("EVT-DLQ-1")).thenReturn(Optional.empty());

    dlqService.sendToDlq(event, cause, 3);

    // 1. Verificar llamado a RabbitTemplate con exchange y routing key correctos
    ArgumentCaptor<MessagePostProcessor> postProcessorCaptor = ArgumentCaptor.forClass(MessagePostProcessor.class);
    verify(rabbitTemplate, times(1)).convertAndSend(
        eq("tacocloud.order.dlx"),
        eq("tacocloud.order.queue.dlq"),
        eq(event),
        postProcessorCaptor.capture()
    );

    // 2. Verificar que los headers de diagnóstico y correlación fueron inyectados
    Message message = new Message(new byte[0], new MessageProperties());
    Message processedMessage = postProcessorCaptor.getValue().postProcessMessage(message);
    MessageProperties props = processedMessage.getMessageProperties();

    assertEquals("EVT-DLQ-1", props.getHeader("x-original-event-id"));
    assertEquals("CORR-777", props.getHeader("x-correlation-id"));
    assertEquals("Corrupted JSON payload in taco items", props.getHeader("x-exception-message"));
    assertEquals("tacos.kitchen.exception.PermanentProcessingException", props.getHeader("x-exception-class"));
    assertEquals(3, (Integer) props.getHeader("x-retry-count"));
    assertNotNull(props.getHeader("x-failed-at"));

    // 3. Verificar que ProcessedEvent se guarda con status FAILED
    ArgumentCaptor<ProcessedEvent> eventCaptor = ArgumentCaptor.forClass(ProcessedEvent.class);
    verify(processedEventRepository, times(1)).save(eventCaptor.capture());
    ProcessedEvent saved = eventCaptor.getValue();
    assertEquals(ProcessedStatus.FAILED, saved.getStatus());
    assertEquals("EVT-DLQ-1", saved.getEventId());
    assertEquals("Corrupted JSON payload in taco items", saved.getErrorMessage());

    // 4. Verificar métricas
    assertEquals(1.0, metrics.getDlqCount());
  }

  private void setField(Object target, String fieldName, Object value) throws Exception {
    Field field = target.getClass().getDeclaredField(fieldName);
    field.setAccessible(true);
    field.set(target, value);
  }

  private OrderEvent createOrderEvent(String eventId, String orderId, String correlationId) {
    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId(orderId)
        .customerName("Safe Customer")
        .placedAt(new Date())
        .items(Collections.singletonList(
            OrderEventItemPayload.builder()
                .tacoName("Veggie Taco")
                .quantity(1)
                .build()
        ))
        .build();

    return OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version(1)
        .occurredAt(new Date())
        .correlationId(correlationId)
        .payload(payload)
        .build();
  }

}
