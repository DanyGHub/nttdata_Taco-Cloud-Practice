package tacos.web.api.dto;

import java.math.BigDecimal;
import java.util.Set;

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
  private BigDecimal unitPrice;
  private boolean available = true;
  private Set<tacos.classification.DietaryTag> dietaryTags = new java.util.LinkedHashSet<>();
  private Set<tacos.classification.Allergen> allergens = new java.util.LinkedHashSet<>();
  private tacos.classification.SpiceLevel spiceLevel = tacos.classification.SpiceLevel.NONE;

  public IngredientResponse(String id, String name, Type type) {
    this(id, name, type, BigDecimal.ZERO, true, java.util.Collections.emptySet(), java.util.Collections.emptySet(), tacos.classification.SpiceLevel.NONE);
  }

  public IngredientResponse(String id, String name, Type type, BigDecimal unitPrice, boolean available) {
    this(id, name, type, unitPrice, available, java.util.Collections.emptySet(), java.util.Collections.emptySet(), tacos.classification.SpiceLevel.NONE);
  }

}
