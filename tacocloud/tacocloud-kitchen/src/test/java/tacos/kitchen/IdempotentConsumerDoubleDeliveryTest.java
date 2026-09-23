package tacos.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.clearInvocations;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tacos.kitchen.domain.KitchenOrder;
import tacos.kitchen.domain.ProcessedEvent;
import tacos.kitchen.metrics.KitchenConsumerMetrics;
import tacos.kitchen.repository.KitchenOrderRepository;
import tacos.kitchen.repository.ProcessedEventRepository;
import tacos.kitchen.workflow.OrderProcessingWorkflow;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventItemPayload;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;

class IdempotentConsumerDoubleDeliveryTest {

  private ProcessedEventRepository processedEventRepository;
  private KitchenOrderRepository kitchenOrderRepository;
  private KitchenUI kitchenUI;
  private KitchenConsumerMetrics metrics;
  private OrderProcessingWorkflow workflow;

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
  }

  @Test
  @DisplayName("Entrega única: procesa negocio, persiste ProcessedEvent y llama UI")
  void testSingleDeliveryProcessesSuccessfully() {
    OrderEvent event = createOrderEvent("EVT-001", "ORD-100", OrderEventType.ORDER_CREATED);
    when(processedEventRepository.existsByEventId("EVT-001")).thenReturn(false);
    when(kitchenOrderRepository.findByOrderId("ORD-100")).thenReturn(Optional.empty());

    workflow.processOrderEvent(event);

    verify(kitchenOrderRepository, times(1)).save(any(KitchenOrder.class));
    verify(kitchenUI, times(1)).displayOrder(event);
    verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    assertEquals(1.0, metrics.getReceivedCount());
    assertEquals(1.0, metrics.getProcessedCount());
    assertEquals(0.0, metrics.getDuplicateCount());
  }

  @Test
  @DisplayName("Doble entrega: mismo eventId entregado 2 veces solo cambia estado 1 vez y se confirma de forma idempotente")
  void testDoubleDeliveryOnlyChangesStateOnce() {
    OrderEvent event = createOrderEvent("EVT-001", "ORD-100", OrderEventType.ORDER_CREATED);

    // Primera entrega: no existe en BD
    when(processedEventRepository.existsByEventId("EVT-001")).thenReturn(false);
    when(kitchenOrderRepository.findByOrderId("ORD-100")).thenReturn(Optional.empty());

    workflow.processOrderEvent(event);

    verify(kitchenOrderRepository, times(1)).save(any(KitchenOrder.class));
    verify(kitchenUI, times(1)).displayOrder(event);
    verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));

    clearInvocations(kitchenOrderRepository, kitchenUI, processedEventRepository);

    // Segunda entrega (redelivery con mismo eventId): ya existe en BD
    when(processedEventRepository.existsByEventId("EVT-001")).thenReturn(true);

    workflow.processOrderEvent(event);

    // Verificaciones: NO se vuelve a ejecutar lógica de negocio ni cambio de estado
    verify(kitchenOrderRepository, never()).save(any(KitchenOrder.class));
    verify(kitchenUI, never()).displayOrder(any(OrderEvent.class));
    verify(processedEventRepository, never()).save(any(ProcessedEvent.class));

    assertEquals(2.0, metrics.getReceivedCount());
    assertEquals(1.0, metrics.getProcessedCount());
    assertEquals(1.0, metrics.getDuplicateCount());
  }

  @Test
  @DisplayName("La llave de idempotencia es eventId, no orderId: dos eventos distintos para la misma orden se procesan")
  void testIdempotencyKeyIsEventIdNotOrderId() {
    OrderEvent createdEvent = createOrderEvent("EVT-001", "ORD-100", OrderEventType.ORDER_CREATED);
    OrderEvent statusChangedEvent = createOrderEvent("EVT-002", "ORD-100", OrderEventType.ORDER_STATUS_CHANGED);

    when(processedEventRepository.existsByEventId("EVT-001")).thenReturn(false);
    when(processedEventRepository.existsByEventId("EVT-002")).thenReturn(false);
    when(kitchenOrderRepository.findByOrderId("ORD-100")).thenReturn(Optional.of(
        KitchenOrder.builder().orderId("ORD-100").status("RECEIVED").build()
    ));

    // Evento 1: ORDER_CREATED
    workflow.processOrderEvent(createdEvent);
    assertEquals(1.0, metrics.getProcessedCount());

    // Evento 2: ORDER_STATUS_CHANGED para la misma orden pero diferente eventId
    workflow.processOrderEvent(statusChangedEvent);
    assertEquals(2.0, metrics.getProcessedCount());
    assertEquals(0.0, metrics.getDuplicateCount());

    verify(kitchenUI, times(1)).displayStatusTransition("ORD-100", "RECEIVED", "UPDATED");
  }

  private OrderEvent createOrderEvent(String eventId, String orderId, OrderEventType eventType) {
    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId(orderId)
        .customerName("Customer Test")
        .placedAt(new Date())
        .items(Collections.singletonList(
            OrderEventItemPayload.builder()
                .tacoName("Carnitas Taco")
                .quantity(2)
                .build()
        ))
        .build();

    return OrderEvent.builder()
        .eventId(eventId)
        .eventType(eventType)
        .version(1)
        .occurredAt(new Date())
        .correlationId("CORR-999")
        .payload(payload)
        .build();
  }

}
