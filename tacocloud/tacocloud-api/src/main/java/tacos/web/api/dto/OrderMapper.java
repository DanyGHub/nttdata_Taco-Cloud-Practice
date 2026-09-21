package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;

import tacos.classification.TacoClassification;
import tacos.classification.TacoClassificationService;

@Component
public class OrderMapper {

  private final IngredientMapper ingredientMapper;
  private final TacoClassificationService classificationService;

  public OrderMapper(IngredientMapper ingredientMapper, TacoClassificationService classificationService) {
    this.ingredientMapper = ingredientMapper != null ? ingredientMapper : new IngredientMapper();
    this.classificationService = classificationService != null ? classificationService : new TacoClassificationService();
  }

  public OrderMapper(IngredientMapper ingredientMapper) {
    this(ingredientMapper, new TacoClassificationService());
  }

  public OrderMapper() {
    this(new IngredientMapper(), new TacoClassificationService());
  }

  public TacoOrder toDomain(OrderCreateRequest request) {
    if (request == null) {
      return null;
    }

    TacoOrder order = new TacoOrder();
    order.setDeliveryName(request.getDeliveryName());
    order.setDeliveryStreet(request.getDeliveryStreet());
    order.setDeliveryCity(request.getDeliveryCity());
    order.setDeliveryState(request.getDeliveryState());
    order.setDeliveryZip(request.getDeliveryZip());
    order.setPaymentMethodId(request.getPaymentMethodId());
    order.setPaymentToken(request.getPaymentToken());
    order.setBrand(request.getBrand());
    if (request.getLast4() != null) {
      order.setLast4(request.getLast4());
    } else if (request.getCcNumber() != null && request.getCcNumber().length() >= 4) {
      order.setLast4(request.getCcNumber().substring(request.getCcNumber().length() - 4));
    }
    order.setCouponCode(request.getCouponCode());
    order.setStatus(tacos.order.OrderStatus.CREATED);
    order.recordStatusChange(tacos.order.OrderStatus.CREATED, "system", "SYSTEM", "API_CREATE", "Order created");

    if (request.getItems() != null && !request.getItems().isEmpty()) {
      for (OrderItemRequest itemReq : request.getItems()) {
        if (itemReq != null && itemReq.getTaco() != null) {
          Taco taco = toTacoDomain(itemReq.getTaco());
          int qty = itemReq.getQuantity() > 0 ? itemReq.getQuantity() : 1;
          OrderItem item = new OrderItem(taco, qty);
          order.addOrderItem(item);
        }
      }
    } else if (request.getTacos() != null) {
      for (TacoRequest tacoReq : request.getTacos()) {
        if (tacoReq != null) {
          Taco taco = toTacoDomain(tacoReq);
          OrderItem item = new OrderItem(taco, 1);
          order.addOrderItem(item);
        }
      }
    }

    return order;
  }

  public OrderResponse toResponse(TacoOrder order) {
    if (order == null) {
      return null;
    }

    OrderResponse response = new OrderResponse();
    response.setId(order.getId());
    response.setPlacedAt(order.getPlacedAt());
    response.setDeliveryName(order.getDeliveryName());
    response.setDeliveryStreet(order.getDeliveryStreet());
    response.setDeliveryCity(order.getDeliveryCity());
    response.setDeliveryState(order.getDeliveryState());
    response.setDeliveryZip(order.getDeliveryZip());
    response.setUsername(order.getUser() != null ? order.getUser().getUsername() : null);
    response.setBrand(order.getBrand());
    response.setLast4(order.getLast4());
    response.setSubtotal(order.getSubtotal());
    response.setDiscountAmount(order.getDiscountAmount());
    response.setTotal(order.getTotal());
    response.setCurrency(order.getCurrency() != null ? order.getCurrency() : "USD");
    response.setCouponCode(order.getCouponCode());
    response.setStatus(order.getStatus() != null ? order.getStatus() : tacos.order.OrderStatus.CREATED);
    response.setVersion(order.getVersion());
    if (order.getStatusHistory() != null) {
      response.setStatusHistory(new ArrayList<>(order.getStatusHistory()));
    }

    if (order.getItems() != null && !order.getItems().isEmpty()) {
      List<OrderItemResponse> itemResponses = new ArrayList<>();
      for (OrderItem item : order.getItems()) {
        if (item != null) {
          OrderItemResponse itemResp = new OrderItemResponse(
              toTacoResponse(item.getTaco()),
              item.getQuantity(),
              item.getUnitPriceAtPurchase(),
              item.getSubtotal()
          );
          itemResponses.add(itemResp);
        }
      }
      response.setItems(itemResponses);
    }

    if (order.getTacos() != null) {
      List<TacoResponse> tacoResponses = new ArrayList<>();
      for (Taco taco : order.getTacos()) {
        if (taco != null) {
          tacoResponses.add(toTacoResponse(taco));
        }
      }
      response.setTacos(tacoResponses);
    }

    return response;
  }

  public OrderSummaryResponse toSummaryResponse(TacoOrder order) {
    if (order == null) {
      return null;
    }

    int itemCount = 0;
    if (order.getItems() != null && !order.getItems().isEmpty()) {
      itemCount = order.getItems().stream()
          .filter(Objects::nonNull)
          .mapToInt(item -> item.getQuantity() > 0 ? item.getQuantity() : 1)
          .sum();
    } else if (order.getTacos() != null) {
      itemCount = order.getTacos().size();
    }

    return OrderSummaryResponse.builder()
        .id(order.getId())
        .placedAt(order.getPlacedAt())
        .deliveryName(order.getDeliveryName())
        .deliveryCity(order.getDeliveryCity())
        .deliveryState(order.getDeliveryState())
        .itemCount(itemCount)
        .total(order.getTotal())
        .currency(order.getCurrency() != null ? order.getCurrency() : "USD")
        .brand(order.getBrand())
        .last4(order.getLast4())
        .status(order.getStatus() != null ? order.getStatus() : tacos.order.OrderStatus.CREATED)
        .version(order.getVersion())
        .build();
  }

  public OrderDetailResponse toDetailResponse(TacoOrder order) {
    if (order == null) {
      return null;
    }

    List<OrderItemResponse> itemResponses = new ArrayList<>();
    if (order.getItems() != null && !order.getItems().isEmpty()) {
      for (OrderItem item : order.getItems()) {
        if (item != null) {
          itemResponses.add(new OrderItemResponse(
              toTacoResponse(item.getTaco()),
              item.getQuantity(),
              item.getUnitPriceAtPurchase(),
              item.getSubtotal()
          ));
        }
      }
    }

    List<TacoResponse> tacoResponses = new ArrayList<>();
    if (order.getTacos() != null) {
      for (Taco taco : order.getTacos()) {
        if (taco != null) {
          tacoResponses.add(toTacoResponse(taco));
        }
      }
    }

    String username = order.getUser() != null ? order.getUser().getUsername() : null;

    return OrderDetailResponse.builder()
        .id(order.getId())
        .placedAt(order.getPlacedAt())
        .deliveryName(order.getDeliveryName())
        .deliveryStreet(order.getDeliveryStreet())
        .deliveryCity(order.getDeliveryCity())
        .deliveryState(order.getDeliveryState())
        .deliveryZip(order.getDeliveryZip())
        .username(username)
        .brand(order.getBrand())
        .last4(order.getLast4())
        .items(itemResponses)
        .tacos(tacoResponses)
        .subtotal(order.getSubtotal())
        .discountAmount(order.getDiscountAmount())
        .total(order.getTotal())
        .currency(order.getCurrency() != null ? order.getCurrency() : "USD")
        .couponCode(order.getCouponCode())
        .status(order.getStatus() != null ? order.getStatus() : tacos.order.OrderStatus.CREATED)
        .version(order.getVersion())
        .statusHistory(order.getStatusHistory() != null ? new ArrayList<>(order.getStatusHistory()) : new ArrayList<>())
        .build();
  }

  public Taco toTacoDomain(TacoRequest request) {
    if (request == null) {
      return null;
    }
    Taco taco = new Taco();
    taco.setName(request.getName());
    List<Ingredient> ingredients = new ArrayList<>();

    if (request.getIngredients() != null && !request.getIngredients().isEmpty()) {
      ingredients.addAll(request.getIngredients().stream()
          .filter(Objects::nonNull)
          .map(ingredientMapper::toDomain)
          .collect(Collectors.toList()));
    }

    if (request.getIngredientIds() != null && !request.getIngredientIds().isEmpty()) {
      for (String id : request.getIngredientIds()) {
        if (id != null && !id.trim().isEmpty()) {
          ingredients.add(new Ingredient(id, id, null));
        }
      }
    }

    taco.setIngredients(ingredients);
    return taco;
  }

  public TacoResponse toTacoResponse(Taco taco) {
    if (taco == null) {
      return null;
    }
    TacoResponse response = new TacoResponse();
    response.setId(taco.getId());
    response.setName(taco.getName());
    response.setCreatedAt(taco.getCreatedAt());
    if (taco.getIngredients() != null) {
      List<IngredientResponse> ingResponses = taco.getIngredients().stream()
          .filter(Objects::nonNull)
          .map(ingredientMapper::toResponse)
          .collect(Collectors.toList());
      response.setIngredients(ingResponses);

      if (classificationService != null) {
        TacoClassification classification = classificationService.classify(taco.getIngredients());
        response.setDietaryTags(classification.getDietaryTags());
        response.setAllergens(classification.getAllergens());
        response.setSpiceLevel(classification.getSpiceLevel());
        response.setDisclaimer(classification.getDisclaimer());
      }
    }
    return response;
  }

}
