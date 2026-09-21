package tacos.kitchen;

import java.util.List;

import org.springframework.stereotype.Component;

import lombok.extern.slf4j.Slf4j;
import tacos.Taco;
import tacos.TacoOrder;

@Component
@Slf4j
public class KitchenUI {

  public void displayOrder(TacoOrder order) {
    if (order == null) {
      log.info("[KITCHEN UI] No order to display.");
      return;
    }
    log.info("==================== [KITCHEN ORDER] ====================");
    log.info("Customer   : {}", order.getDeliveryName());
    log.info("Placed At  : {}", order.getPlacedAt());
    if (order.getTacos() != null) {
      log.info("Tacos ({}) :", order.getTacos().size());
      for (Taco taco : order.getTacos()) {
        log.info("  - Taco: {}", taco != null ? taco.getName() : "Unknown");
      }
    }
    log.info("=========================================================");
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

  public void displayClaim(TacoOrder order, String stationId, String cookId) {
    log.info("==================== [ORDER CLAIMED] ====================");
    log.info("Station ID : {}", stationId);
    log.info("Cook ID    : {}", cookId);
    if (order != null) {
      log.info("Order ID   : {}", order.getDeliveryName() != null ? order.getDeliveryName() : "N/A");
    }
    log.info("Status     : ACCEPTED -> Ready for prep");
    log.info("=========================================================");
  }

  public void displayStatusTransition(String orderId, String fromStatus, String toStatus) {
    log.info("[KITCHEN UI] Order '{}' transition: {} -> {}", orderId, fromStatus, toStatus);
  }

}
