package tacos.web.api.dto;

import org.springframework.stereotype.Component;

import tacos.Ingredient;

@Component
public class IngredientMapper {

  public Ingredient toDomain(IngredientRequest request) {
    if (request == null) {
      return null;
    }
    Ingredient ingredient = new Ingredient(request.getId(), request.getName(), request.getType());
    if (request.getUnitPrice() != null) {
      ingredient.setUnitPrice(request.getUnitPrice());
    }
    if (request.getAvailable() != null) {
      ingredient.setAvailable(request.getAvailable());
    }
    if (request.getStockOnHand() != null) {
      ingredient.setStockOnHand(request.getStockOnHand());
    }
    if (request.getReorderLevel() != null) {
      ingredient.setReorderLevel(request.getReorderLevel());
    }
    return ingredient;
  }

  public Ingredient toDomain(IngredientResponse response) {
    if (response == null) {
      return null;
    }
    Ingredient ingredient = new Ingredient(response.getId(), response.getName(), response.getType());
    if (response.getUnitPrice() != null) {
      ingredient.setUnitPrice(response.getUnitPrice());
    }
    ingredient.setAvailable(response.isAvailable());
    return ingredient;
  }

  public IngredientRequest toRequest(Ingredient entity) {
    if (entity == null) {
      return null;
    }
    IngredientRequest req = new IngredientRequest(entity.getId(), entity.getName(), entity.getType());
    req.setUnitPrice(entity.getUnitPrice());
    req.setAvailable(entity.isAvailable());
    req.setStockOnHand(entity.getStockOnHand());
    req.setReorderLevel(entity.getReorderLevel());
    return req;
  }

  public IngredientResponse toResponse(Ingredient entity) {
    if (entity == null) {
      return null;
    }
    return new IngredientResponse(
        entity.getId(),
        entity.getName(),
        entity.getType(),
        entity.getUnitPrice(),
        entity.isAvailable()
    );
  }

  public AdminIngredientResponse toAdminResponse(Ingredient entity) {
    if (entity == null) {
      return null;
    }
    return new AdminIngredientResponse(
        entity.getId(),
        entity.getName(),
        entity.getType(),
        entity.getUnitPrice(),
        entity.isAvailable(),
        entity.getStockOnHand(),
        entity.getReorderLevel(),
        entity.getVersion()
    );
  }

}
