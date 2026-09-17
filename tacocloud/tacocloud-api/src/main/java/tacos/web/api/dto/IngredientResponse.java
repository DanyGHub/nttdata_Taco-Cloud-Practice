package tacos.web.api.dto;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Ingredient.Type;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngredientResponse {

  private String id;
  private String name;
  private Type type;

}
