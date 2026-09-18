package tacos.web.api.dto;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Ingredient.Type;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IngredientRequest {

  @NotBlank(message = "Ingredient ID is required")
  private String id;

  @NotBlank(message = "Ingredient name is required")
  private String name;

  @NotNull(message = "Ingredient type is required")
  private Type type;

}
