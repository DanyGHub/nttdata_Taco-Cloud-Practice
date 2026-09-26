package tacos.order;

import java.util.ArrayList;
import java.util.List;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.correlation.CorrelationContext;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventIngredientPayload;
import tacos.messaging.OrderEventItemPayload;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderEventType;


  //Transformar entidades de dominio TacoOrder a eventos canónicos versionados OrderEvent.

@Component
public class OrderEventMapper {

  public OrderEvent toOrderCreatedEvent(TacoOrder order) {
    return toOrderCreatedEvent(order, CorrelationContext.getOrGenerate());
  }

  public OrderEvent toOrderCreatedEvent(TacoOrder order, String correlationId) {
    return toEvent(order, OrderEventType.ORDER_CREATED, null, null, correlationId);
  }

  public OrderEvent toStatusChangedEvent(TacoOrder order, OrderStatus previousStatus) {
    return toStatusChangedEvent(order, previousStatus, CorrelationContext.getOrGenerate());
  }

  public OrderEvent toStatusChangedEvent(TacoOrder order, OrderStatus previousStatus, String correlationId) {
    String prev = previousStatus != null ? previousStatus.name() : null;
    return toEvent(order, OrderEventType.ORDER_STATUS_CHANGED, prev, null, correlationId);
  }

  public OrderEvent toOrderCancelledEvent(TacoOrder order, String reason) {
    return toOrderCancelledEvent(order, reason, CorrelationContext.getOrGenerate());
  }

  public OrderEvent toOrderCancelledEvent(TacoOrder order, String reason, String correlationId) {
    return toEvent(order, OrderEventType.ORDER_CANCELLED, null, reason, correlationId);
  }

  public OrderEvent toEvent(TacoOrder order, OrderEventType eventType, String previousStatus, String reason) {
    return toEvent(order, eventType, previousStatus, reason, CorrelationContext.getOrGenerate());
  }

  public OrderEvent toEvent(TacoOrder order, OrderEventType eventType, String previousStatus, String reason, String correlationId) {
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

    String corrId = (correlationId != null && !correlationId.trim().isEmpty())
        ? correlationId.trim()
        : CorrelationContext.getOrGenerate();

    return OrderEvent.of(eventType, corrId, payload);
  }

}
