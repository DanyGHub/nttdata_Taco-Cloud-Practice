package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TacoDesignRequest {

  private String name;
  private List<String> ingredientIds = new ArrayList<>();
  private List<IngredientRequest> ingredients = new ArrayList<>();

  public List<String> extractIngredientIds() {
    List<String> result = new ArrayList<>();
    if (ingredientIds != null && !ingredientIds.isEmpty()) {
      result.addAll(ingredientIds.stream().filter(Objects::nonNull).collect(Collectors.toList()));
    } else if (ingredients != null && !ingredients.isEmpty()) {
      result.addAll(ingredients.stream()
          .filter(Objects::nonNull)
          .map(IngredientRequest::getId)
          .filter(Objects::nonNull)
          .collect(Collectors.toList()));
    }
    return result;
  }
}
