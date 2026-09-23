package tacos.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

class CrashBetweenEffectAndAckTest {

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
  @DisplayName("Simulación de crash entre efecto durable y ACK: redelivery es reconocido sin duplicar efectos de negocio")
  void testCrashBetweenEffectAndAckIsIdempotentlyHandledOnRedelivery() {
    String eventId = "EVT-CRASH-001";
    String orderId = "ORD-CRASH-999";
    OrderEvent event = createOrderEvent(eventId, orderId);

    // PASO 1: Primer procesamiento antes del crash
    // El evento no existe aún en BD
    when(processedEventRepository.existsByEventId(eventId)).thenReturn(false);
    when(kitchenOrderRepository.findByOrderId(orderId)).thenReturn(Optional.empty());

    // Se ejecuta el workflow y se persiste el efecto y ProcessedEvent
    workflow.processOrderEvent(event);

    verify(kitchenOrderRepository, times(1)).save(any(KitchenOrder.class));
    verify(processedEventRepository, times(1)).save(any(ProcessedEvent.class));
    verify(kitchenUI, times(1)).displayOrder(event);

    assertEquals(1.0, metrics.getProcessedCount());
    assertEquals(0.0, metrics.getDuplicateCount());

    // SIMULACIÓN DE CRASH:
    // El efecto y ProcessedEvent ya están confirmados en la BD de Mongo,
    // pero el proceso cae antes de enviar el ACK de red al broker de mensajería.
    // Al levantarse el consumidor, el broker re-entrega (redelivers) el mensaje.
    clearInvocations(kitchenOrderRepository, processedEventRepository, kitchenUI);

    // PASO 2: Redelivery del broker tras el crash
    // Ahora en BD el registro ya existe
    when(processedEventRepository.existsByEventId(eventId)).thenReturn(true);

    // El consumidor recibe la re-entrega
    workflow.processOrderEvent(event);

    // Verificación: NO se vuelve a ejecutar la lógica de cocina ni se muta el estado
    verify(kitchenOrderRepository, never()).save(any(KitchenOrder.class));
    verify(processedEventRepository, never()).save(any(ProcessedEvent.class));
    verify(kitchenUI, never()).displayOrder(any(OrderEvent.class));

    // El mensaje es absorbido/confirmado limpiamente
    assertEquals(2.0, metrics.getReceivedCount());
    assertEquals(1.0, metrics.getProcessedCount());
    assertEquals(1.0, metrics.getDuplicateCount(), "El redelivery debe ser detectado como duplicado");
  }

  private OrderEvent createOrderEvent(String eventId, String orderId) {
    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId(orderId)
        .customerName("Crash Test User")
        .placedAt(new Date())
        .items(Collections.singletonList(
            OrderEventItemPayload.builder()
                .tacoName("Al Pastor Taco")
                .quantity(3)
                .build()
        ))
        .build();

    return OrderEvent.builder()
        .eventId(eventId)
        .eventType(OrderEventType.ORDER_CREATED)
        .version(1)
        .occurredAt(new Date())
        .correlationId("CORR-CRASH")
        .payload(payload)
        .build();
  }

}
