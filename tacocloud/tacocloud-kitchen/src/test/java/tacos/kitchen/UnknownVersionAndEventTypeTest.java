package tacos.kitchen;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;

import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tacos.kitchen.exception.PermanentProcessingException;
import tacos.kitchen.messaging.dlq.DeadLetterQueueService;
import tacos.kitchen.messaging.rabbit.listener.OrderListener;
import tacos.kitchen.metrics.KitchenConsumerMetrics;
import tacos.kitchen.repository.KitchenOrderRepository;
import tacos.kitchen.repository.ProcessedEventRepository;
import tacos.kitchen.workflow.OrderProcessingWorkflow;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventItemPayload;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;

class UnknownVersionAndEventTypeTest {

  private ProcessedEventRepository processedEventRepository;
  private KitchenOrderRepository kitchenOrderRepository;
  private KitchenUI kitchenUI;
  private KitchenConsumerMetrics metrics;
  private OrderProcessingWorkflow workflow;
  private DeadLetterQueueService dlqService;
  private OrderListener orderListener;

  @BeforeEach
  void setUp() {
    processedEventRepository = mock(ProcessedEventRepository.class);
    kitchenOrderRepository = mock(KitchenOrderRepository.class);
    kitchenUI = mock(KitchenUI.class);
    metrics = new KitchenConsumerMetrics(new SimpleMeterRegistry());
    workflow = new OrderProcessingWorkflow(
        processedEventRepository,
        kitchenOrderRepository,
        kitchenUI,
        metrics
    );
    dlqService = mock(DeadLetterQueueService.class);
    orderListener = new OrderListener(workflow, dlqService, metrics);
  }

  @Test
  @DisplayName("Versión no soportada (v99) lanza PermanentProcessingException de forma explícita")
  void testUnsupportedVersionThrowsPermanentException() {
    OrderEvent event = createOrderEvent("EVT-VER-99", 99, OrderEventType.ORDER_CREATED);

    PermanentProcessingException ex = assertThrows(PermanentProcessingException.class, () -> {
      workflow.processOrderEvent(event);
    });

    assertTrue(ex.getMessage().contains("Unsupported event version: 99"));
    verify(kitchenOrderRepository, never()).save(any());
    verify(kitchenUI, never()).displayOrder(any());
  }

  @Test
  @DisplayName("Tipo de evento desconocido/nulo lanza PermanentProcessingException de forma explícita")
  void testMissingEventTypeThrowsPermanentException() {
    OrderEvent event = createOrderEvent("EVT-NO-TYPE", 1, null);

    PermanentProcessingException ex = assertThrows(PermanentProcessingException.class, () -> {
      workflow.processOrderEvent(event);
    });

    assertTrue(ex.getMessage().contains("Missing eventType"));
    verify(kitchenOrderRepository, never()).save(any());
  }

  @Test
  @DisplayName("En el Listener de RabbitMQ, el error permanente va a DLQ de inmediato sin ciclo infinito de reintentos")
  void testPermanentErrorInListenerRoutesDirectlyToDlqWithoutRethrowing() {
    OrderEvent unsupportedVersionEvent = createOrderEvent("EVT-V88", 88, OrderEventType.ORDER_CREATED);

    // El listener no debe lanzar excepción al contenedor (para no activar reintentos infinitos)
    assertDoesNotThrow(() -> {
      orderListener.receiveOrder(unsupportedVersionEvent);
    });

    // Se enruta directamente a la DLQ
    verify(dlqService, times(1)).sendToDlq(
        eq(unsupportedVersionEvent),
        any(PermanentProcessingException.class),
        eq(1)
    );

    // No se ejecuta la lógica de cocina
    verify(kitchenOrderRepository, never()).save(any());
    verify(kitchenUI, never()).displayOrder(any());
  }

  private OrderEvent createOrderEvent(String eventId, int version, OrderEventType eventType) {
    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId("ORD-UNKNOWN")
        .customerName("Version Test User")
        .placedAt(new Date())
        .items(Collections.singletonList(
            OrderEventItemPayload.builder()
                .tacoName("Test Taco")
                .quantity(1)
                .build()
        ))
        .build();

    return OrderEvent.builder()
        .eventId(eventId)
        .eventType(eventType)
        .version(version)
        .occurredAt(new Date())
        .correlationId("CORR-VER")
        .payload(payload)
        .build();
  }

}
