package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tacos.Ingredient;
import tacos.Taco;
import tacos.TacoOrder;

@Component
public class OrderMapper {

  private final IngredientMapper ingredientMapper;

  public OrderMapper(IngredientMapper ingredientMapper) {
    this.ingredientMapper = ingredientMapper;
  }

  public OrderMapper() {
    this.ingredientMapper = new IngredientMapper();
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
    order.setCcNumber(request.getCcNumber());
    order.setCcExpiration(request.getCcExpiration());
    order.setCcCVV(request.getCcCVV());

    if (request.getTacos() != null) {
      for (TacoRequest tacoReq : request.getTacos()) {
        if (tacoReq != null) {
          Taco taco = new Taco();
          taco.setName(tacoReq.getName());
          if (tacoReq.getIngredients() != null) {
            List<Ingredient> ingredients = tacoReq.getIngredients().stream()
                .filter(Objects::nonNull)
                .map(ingredientMapper::toDomain)
                .collect(Collectors.toList());
            taco.setIngredients(ingredients);
          }
          order.addTaco(taco);
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

    if (order.getTacos() != null) {
      List<TacoResponse> tacoResponses = new ArrayList<>();
      for (Taco taco : order.getTacos()) {
        if (taco != null) {
          TacoResponse tacoResp = new TacoResponse();
          tacoResp.setId(taco.getId());
          tacoResp.setName(taco.getName());
          tacoResp.setCreatedAt(taco.getCreatedAt());
          if (taco.getIngredients() != null) {
            List<IngredientResponse> ingResponses = taco.getIngredients().stream()
                .filter(Objects::nonNull)
                .map(ingredientMapper::toResponse)
                .collect(Collectors.toList());
            tacoResp.setIngredients(ingResponses);
          }
          tacoResponses.add(tacoResp);
        }
      }
      response.setTacos(tacoResponses);
    }

    return response;
  }

  public Taco toTacoDomain(TacoRequest request) {
    if (request == null) {
      return null;
    }
    Taco taco = new Taco();
    taco.setName(request.getName());
    if (request.getIngredients() != null) {
      List<Ingredient> ingredients = request.getIngredients().stream()
          .filter(Objects::nonNull)
          .map(ingredientMapper::toDomain)
          .collect(Collectors.toList());
      taco.setIngredients(ingredients);
    }
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
    }
    return response;
  }

}
