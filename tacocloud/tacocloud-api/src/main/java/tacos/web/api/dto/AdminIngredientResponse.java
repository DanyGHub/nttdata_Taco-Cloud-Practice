package tacos.web.api.dto;

import java.math.BigDecimal;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Ingredient.Type;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class AdminIngredientResponse {

  private String id;
  private String name;
  private Type type;
  private BigDecimal unitPrice;
  private boolean available;
  private int stockOnHand;
  private int reorderLevel;
  private Long version;
  private java.util.Set<tacos.classification.DietaryTag> dietaryTags = new java.util.LinkedHashSet<>();
  private java.util.Set<tacos.classification.Allergen> allergens = new java.util.LinkedHashSet<>();
  private tacos.classification.SpiceLevel spiceLevel = tacos.classification.SpiceLevel.NONE;

  public AdminIngredientResponse(String id, String name, Type type, BigDecimal unitPrice, boolean available, int stockOnHand, int reorderLevel, Long version) {
    this(id, name, type, unitPrice, available, stockOnHand, reorderLevel, version, java.util.Collections.emptySet(), java.util.Collections.emptySet(), tacos.classification.SpiceLevel.NONE);
  }

}
