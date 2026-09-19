package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.List;

import javax.validation.Valid;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class TacoRequest {

  @NotBlank(message = "Taco name is required")
  @Size(min = 5, message = "Name must be at least 5 characters long")
  private String name;

  @Valid
  private List<IngredientRequest> ingredients = new ArrayList<>();

  private List<String> ingredientIds;

  @javax.validation.constraints.AssertTrue(message = "You must choose at least 1 ingredient")
  public boolean isIngredientsPresent() {
    return (ingredients != null && !ingredients.isEmpty())
        || (ingredientIds != null && !ingredientIds.isEmpty());
  }

  public TacoRequest(String name, List<IngredientRequest> ingredients) {
    this.name = name;
    this.ingredients = ingredients;
  }
}
