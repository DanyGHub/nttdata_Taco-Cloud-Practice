package tacos.kitchen;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import tacos.kitchen.domain.ProcessedEvent;
import tacos.kitchen.domain.ProcessedStatus;
import tacos.kitchen.metrics.KitchenConsumerMetrics;
import tacos.kitchen.repository.KitchenOrderRepository;
import tacos.kitchen.repository.ProcessedEventRepository;
import tacos.kitchen.web.KitchenDlqController;
import tacos.kitchen.workflow.OrderProcessingWorkflow;

class ControlledReplayTest {

  private ProcessedEventRepository processedEventRepository;
  private KitchenOrderRepository kitchenOrderRepository;
  private KitchenUI kitchenUI;
  private KitchenConsumerMetrics metrics;
  private OrderProcessingWorkflow workflow;
  private KitchenDlqController controller;

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
    controller = new KitchenDlqController(workflow, processedEventRepository, metrics);
  }

  @Test
  @DisplayName("Replay de un evento ya exitoso: confirma de forma idempotente sin duplicar efectos de negocio")
  void testReplayAlreadyProcessedEventDoesNotDuplicateEffects() {
    ProcessedEvent existing = ProcessedEvent.builder()
        .eventId("EVT-SUCCESS-1")
        .status(ProcessedStatus.PROCESSED)
        .orderId("ORD-111")
        .processedAt(new Date())
        .build();

    when(processedEventRepository.findByEventId("EVT-SUCCESS-1")).thenReturn(Optional.of(existing));

    boolean replayed = workflow.replayEvent("EVT-SUCCESS-1");

    assertTrue(replayed);
    // No se vuelve a mutar orden ni a persistir duplicados
    verify(kitchenOrderRepository, never()).save(any());
    assertEquals(0.0, metrics.getReplayedCount());
  }

  @Test
  @DisplayName("Replay de un evento fallido: actualiza el estado a REPLAYED e incrementa la métrica")
  void testReplayFailedEventUpdatesStatusToReplayed() {
    ProcessedEvent failedEvent = ProcessedEvent.builder()
        .eventId("EVT-FAIL-1")
        .status(ProcessedStatus.FAILED)
        .orderId("ORD-222")
        .errorMessage("Connection timeout previously")
        .processedAt(new Date())
        .build();

    when(processedEventRepository.findByEventId("EVT-FAIL-1")).thenReturn(Optional.of(failedEvent));

    boolean replayed = workflow.replayEvent("EVT-FAIL-1");

    assertTrue(replayed);
    assertEquals(ProcessedStatus.REPLAYED, failedEvent.getStatus());
    verify(processedEventRepository, times(1)).save(failedEvent);
    assertEquals(1.0, metrics.getReplayedCount());
  }

  @Test
  @DisplayName("Endpoint REST de replay: POST /api/kitchen/dlq/replay/{eventId} responde 200 OK")
  void testControllerReplayEndpoint() {
    ProcessedEvent failedEvent = ProcessedEvent.builder()
        .eventId("EVT-REST-1")
        .status(ProcessedStatus.FAILED)
        .orderId("ORD-333")
        .build();

    when(processedEventRepository.findByEventId("EVT-REST-1")).thenReturn(Optional.of(failedEvent));

    ResponseEntity<Map<String, Object>> response = controller.replayEvent("EVT-REST-1");

    assertEquals(HttpStatus.OK, response.getStatusCode());
    assertTrue((Boolean) response.getBody().get("replayed"));
    assertEquals("EVT-REST-1", response.getBody().get("eventId"));
  }

  @Test
  @DisplayName("Endpoint REST de replay con evento inexistente responde 404 NOT FOUND")
  void testControllerReplayNotFound() {
    when(processedEventRepository.findByEventId("EVT-NON-EXISTENT")).thenReturn(Optional.empty());

    ResponseEntity<Map<String, Object>> response = controller.replayEvent("EVT-NON-EXISTENT");

    assertEquals(HttpStatus.NOT_FOUND, response.getStatusCode());
    assertFalse((Boolean) response.getBody().get("replayed"));
  }

}
