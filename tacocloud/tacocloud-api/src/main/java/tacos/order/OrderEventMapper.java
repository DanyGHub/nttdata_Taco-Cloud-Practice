package tacos.order;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventIngredientPayload;
import tacos.messaging.OrderEventItemPayload;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;

/**
 * Mapper para transformar entidades de dominio TacoOrder a eventos canónicos versionados OrderEvent.
 * Garantiza el aislamiento de persistencia y la sanitización de datos sensibles.
 */
@Component
public class OrderEventMapper {

  public OrderEvent toOrderCreatedEvent(TacoOrder order) {
    return toEvent(order, OrderEventType.ORDER_CREATED, null, null);
  }

  public OrderEvent toStatusChangedEvent(TacoOrder order, OrderStatus previousStatus) {
    String prev = previousStatus != null ? previousStatus.name() : null;
    return toEvent(order, OrderEventType.ORDER_STATUS_CHANGED, prev, null);
  }

  public OrderEvent toOrderCancelledEvent(TacoOrder order, String reason) {
    return toEvent(order, OrderEventType.ORDER_CANCELLED, null, reason);
  }

  public OrderEvent toEvent(TacoOrder order, OrderEventType eventType, String previousStatus, String reason) {
    if (order == null) {
      return null;
    }

    String customerName = order.getDeliveryName();
    if (customerName == null && order.getUser() != null) {
      customerName = (order.getUser().getFullname() != null && !order.getUser().getFullname().trim().isEmpty())
          ? order.getUser().getFullname()
          : order.getUser().getUsername();
    }

    List<OrderEventItemPayload> items = new ArrayList<>();
    if (order.getTacos() != null) {
      for (Taco taco : order.getTacos()) {
        List<OrderEventIngredientPayload> ingredients = new ArrayList<>();
        if (taco.getIngredients() != null) {
          for (Ingredient ing : taco.getIngredients()) {
            ingredients.add(OrderEventIngredientPayload.builder()
                .name(ing.getName() != null ? ing.getName() : ing.getId())
                .type(ing.getType() != null ? ing.getType().name() : null)
                .build());
          }
        }
        items.add(OrderEventItemPayload.builder()
            .tacoName(taco.getName())
            .quantity(1)
            .ingredients(ingredients)
            .build());
      }
    }

    OrderEventPayload payload = OrderEventPayload.builder()
        .orderId(order.getId())
        .status(order.getStatus() != null ? order.getStatus().name() : OrderStatus.CREATED.name())
        .customerName(customerName)
        .deliveryStreet(order.getDeliveryStreet())
        .deliveryCity(order.getDeliveryCity())
        .deliveryState(order.getDeliveryState())
        .deliveryZip(order.getDeliveryZip())
        .placedAt(order.getPlacedAt())
        .items(items)
        .stationId(order.getStationId())
        .cookId(order.getCookId())
        .estimatedPrepMinutes(order.getEstimatedPrepMinutes())
        .previousStatus(previousStatus)
        .cancellationReason(reason)
        .build();

    return OrderEvent.of(eventType, order.getId(), payload);
  }

}
