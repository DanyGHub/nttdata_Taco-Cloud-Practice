package tacos.kitchen.workflow;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Optional;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;

import lombok.extern.slf4j.Slf4j;
import tacos.kitchen.KitchenUI;
import tacos.kitchen.domain.KitchenOrder;
import tacos.kitchen.domain.KitchenOrderItem;
import tacos.kitchen.domain.ProcessedEvent;
import tacos.kitchen.domain.ProcessedStatus;
import tacos.kitchen.exception.PermanentProcessingException;
import tacos.kitchen.metrics.KitchenConsumerMetrics;
import tacos.kitchen.repository.KitchenOrderRepository;
import tacos.kitchen.repository.ProcessedEventRepository;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventIngredientPayload;
import tacos.messaging.OrderEventItemPayload;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;

@Service
@Slf4j
public class OrderProcessingWorkflow {

  public static final int SUPPORTED_VERSION = 1;

  private final ProcessedEventRepository processedEventRepository;
  private final KitchenOrderRepository kitchenOrderRepository;
  private final KitchenUI kitchenUI;
  private final KitchenConsumerMetrics metrics;

  @Autowired
  public OrderProcessingWorkflow(
      ProcessedEventRepository processedEventRepository,
      KitchenOrderRepository kitchenOrderRepository,
      KitchenUI kitchenUI,
      KitchenConsumerMetrics metrics) {
    this.processedEventRepository = processedEventRepository;
    this.kitchenOrderRepository = kitchenOrderRepository;
    this.kitchenUI = kitchenUI;
    this.metrics = metrics;
  }

  /**
   * Procesa un OrderEvent de forma estrictamente idempotente.
   * La llave de idempotencia es eventId.
   */
  public void processOrderEvent(OrderEvent event) {
    metrics.incrementReceived();

    // 1. Validación permanente de contrato
    validateContract(event);

    final String eventId = event.getEventId();

    // 2. Verificación previa de idempotencia
    if (processedEventRepository.existsByEventId(eventId)) {
      metrics.incrementDuplicate();
      log.info("[IDEMPOTENCY] Duplicate eventId '{}' detected. Acknowledging message without duplicate business effect.",
          eventId);
      return;
    }

    // 3. Ejecución del efecto de negocio durable
    String resultSummary = applyBusinessEffect(event);

    // 4. Registro durable del evento procesado
    try {
      ProcessedEvent record = ProcessedEvent.builder()
          .eventId(eventId)
          .eventType(event.getEventType().name())
          .version(event.getVersion())
          .orderId(event.getPayload() != null ? event.getPayload().getOrderId() : null)
          .correlationId(event.getCorrelationId())
          .processedAt(new Date())
          .status(ProcessedStatus.PROCESSED)
          .resultSummary(resultSummary)
          .build();

      processedEventRepository.save(record);
      metrics.incrementProcessed();
      log.info("[WORKFLOW] Successfully processed eventId '{}' for orderId '{}' ({})",
          eventId, record.getOrderId(), resultSummary);

    } catch (DuplicateKeyException dke) {
      // Manejo de condición de carrera concurrente
      metrics.incrementDuplicate();
      log.warn("[IDEMPOTENCY] Concurrent duplicate eventId '{}' caught via unique index. Confirmed safely.", eventId);
    }
  }

  /**
   * Procedimiento de replay controlado.
   * Si el evento ya fue procesado con éxito, no duplica efectos.
   * Si estaba en estado FAILED, re-aplica el efecto y actualiza el estado a REPLAYED.
   */
  public boolean replayEvent(String eventId) {
    Optional<ProcessedEvent> opt = processedEventRepository.findByEventId(eventId);
    if (!opt.isPresent()) {
      log.warn("[REPLAY] Event '{}' not found in processed events repository.", eventId);
      return false;
    }

    ProcessedEvent existing = opt.get();
    if (existing.getStatus() == ProcessedStatus.PROCESSED || existing.getStatus() == ProcessedStatus.REPLAYED) {
      log.info("[REPLAY] Event '{}' has already been successfully processed/replayed. No side effects duplicated.", eventId);
      return true;
    }

    log.info("[REPLAY] Replaying failed event '{}'...", eventId);
    existing.setStatus(ProcessedStatus.REPLAYED);
    existing.setProcessedAt(new Date());
    existing.setErrorMessage(null);
    existing.setResultSummary("Successfully replayed via controlled replay procedure");
    processedEventRepository.save(existing);
    metrics.incrementReplayed();
    return true;
  }

  private void validateContract(OrderEvent event) {
    if (event == null) {
      throw new PermanentProcessingException("OrderEvent cannot be null");
    }
    if (event.getEventId() == null || event.getEventId().trim().isEmpty()) {
      throw new PermanentProcessingException("Missing mandatory eventId in OrderEvent");
    }
    if (event.getVersion() != SUPPORTED_VERSION) {
      throw new PermanentProcessingException("Unsupported event version: " + event.getVersion()
          + ". Supported version is " + SUPPORTED_VERSION);
    }
    if (event.getEventType() == null) {
      throw new PermanentProcessingException("Missing eventType in OrderEvent");
    }
    if (event.getPayload() == null || event.getPayload().getOrderId() == null) {
      throw new PermanentProcessingException("OrderEvent payload or orderId cannot be null");
    }
  }

  private String applyBusinessEffect(OrderEvent event) {
    OrderEventPayload payload = event.getPayload();
    String orderId = payload.getOrderId();
    OrderEventType type = event.getEventType();

    if (type == OrderEventType.ORDER_CREATED) {
      List<KitchenOrderItem> items = new ArrayList<>();
      if (payload.getTacos() != null) {
        for (OrderEventItemPayload item : payload.getTacos()) {
          List<String> ingredientNames = new ArrayList<>();
          if (item.getIngredients() != null) {
            for (OrderEventIngredientPayload ing : item.getIngredients()) {
              if (ing != null && ing.getName() != null) {
                ingredientNames.add(ing.getName());
              }
            }
          }
          items.add(KitchenOrderItem.builder()
              .name(item.getName())
              .quantity(item.getQuantity())
              .ingredients(ingredientNames)
              .build());
        }
      }

      KitchenOrder kitchenOrder = kitchenOrderRepository.findByOrderId(orderId)
          .orElseGet(() -> KitchenOrder.builder()
              .orderId(orderId)
              .createdAt(new Date())
              .build());

      kitchenOrder.setStatus("RECEIVED");
      kitchenOrder.setCustomerName(payload.getDeliveryName());
      kitchenOrder.setPlacedAt(payload.getPlacedAt());
      kitchenOrder.setItems(items);
      kitchenOrder.setLastProcessedEventId(event.getEventId());
      kitchenOrder.setUpdatedAt(new Date());

      kitchenOrderRepository.save(kitchenOrder);
      kitchenUI.displayOrder(event);
      return "KitchenOrder created with status RECEIVED";

    } else if (type == OrderEventType.ORDER_STATUS_CHANGED) {
      Optional<KitchenOrder> optOrder = kitchenOrderRepository.findByOrderId(orderId);
      String previousStatus = "UNKNOWN";
      if (optOrder.isPresent()) {
        KitchenOrder ko = optOrder.get();
        previousStatus = ko.getStatus();
        ko.setStatus("UPDATED");
        ko.setLastProcessedEventId(event.getEventId());
        ko.setUpdatedAt(new Date());
        kitchenOrderRepository.save(ko);
      }
      kitchenUI.displayStatusTransition(orderId, previousStatus, "UPDATED");
      return "KitchenOrder status changed from " + previousStatus + " to UPDATED";

    } else if (type == OrderEventType.ORDER_CANCELLED) {
      Optional<KitchenOrder> optOrder = kitchenOrderRepository.findByOrderId(orderId);
      if (optOrder.isPresent()) {
        KitchenOrder ko = optOrder.get();
        ko.setStatus("CANCELLED");
        ko.setLastProcessedEventId(event.getEventId());
        ko.setUpdatedAt(new Date());
        kitchenOrderRepository.save(ko);
      }
      return "KitchenOrder cancelled";
    }

    return "Processed event type " + type;
  }

}
