package tacos.web.api.dto;

import org.springframework.stereotype.Component;

import tacos.Ingredient;

@Component
public class IngredientMapper {

  public Ingredient toDomain(IngredientRequest request) {
    if (request == null) {
      return null;
    }
    return new Ingredient(request.getId(), request.getName(), request.getType());
  }

  public Ingredient toDomain(IngredientResponse response) {
    if (response == null) {
      return null;
    }
    return new Ingredient(response.getId(), response.getName(), response.getType());
  }

  public IngredientRequest toRequest(Ingredient entity) {
    if (entity == null) {
      return null;
    }
    return new IngredientRequest(entity.getId(), entity.getName(), entity.getType());
  }

  public IngredientResponse toResponse(Ingredient entity) {
    if (entity == null) {
      return null;
    }
    return new IngredientResponse(entity.getId(), entity.getName(), entity.getType());
  }

}
