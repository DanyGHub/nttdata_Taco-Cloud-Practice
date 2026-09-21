package tacos.kitchen;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventItemPayload;
import tacos.messaging.OrderEventPayload;

@Component
@Slf4j
public class KitchenUI {

  public void displayOrder(OrderEvent event) {
    if (event == null || event.getPayload() == null) {
      log.info("[KITCHEN UI] No order event to display.");
      return;
    }
    OrderEventPayload payload = event.getPayload();
    log.info("==================== [KITCHEN ORDER EVENT] ====================");
    log.info("Event Type : {}", event.getEventType());
    log.info("Event ID   : {}", event.getEventId());
    log.info("Order ID   : {}", payload.getOrderId());
    log.info("Customer   : {}", payload.getDeliveryName());
    log.info("Placed At  : {}", payload.getPlacedAt());
    if (payload.getTacos() != null) {
      log.info("Tacos ({}) :", payload.getTacos().size());
      for (OrderEventItemPayload taco : payload.getTacos()) {
        log.info("  - Taco: {} (Qty: {})",
            taco != null ? taco.getName() : "Unknown",
            taco != null ? taco.getQuantity() : 1);
      }
    }
    log.info("===============================================================");
  }

  public void displayQueue(List<?> queue) {
    log.info("==================== [KITCHEN QUEUE] ====================");
    if (queue == null || queue.isEmpty()) {
      log.info("Queue is currently empty. All orders fulfilled!");
    } else {
      log.info("Orders waiting in queue: {}", queue.size());
      for (int i = 0; i < queue.size(); i++) {
        log.info("  [{}] {}", i + 1, queue.get(i));
      }
    }
    log.info("=========================================================");
  }

  public void displayClaim(OrderEvent event, String stationId, String cookId) {
    log.info("==================== [ORDER CLAIMED] ====================");
    log.info("Station ID : {}", stationId);
    log.info("Cook ID    : {}", cookId);
    if (event != null && event.getPayload() != null) {
      log.info("Order ID   : {}", event.getPayload().getOrderId());
    }
    log.info("Status     : ACCEPTED -> Ready for prep");
    log.info("=========================================================");
  }

  public void displayStatusTransition(String orderId, String fromStatus, String toStatus) {
    log.info("[KITCHEN UI] Order '{}' transition: {} -> {}", orderId, fromStatus, toStatus);
  }

}
