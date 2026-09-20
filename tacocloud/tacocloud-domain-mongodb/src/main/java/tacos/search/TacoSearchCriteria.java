package tacos.search;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TacoSearchCriteria {

  private String name;
  private String ingredientId;
  private DietaryTag diet;
  private Allergen excludeAllergen;
  private SpiceLevel spice;

  @Builder.Default
  private int page = 0;

  @Builder.Default
  private int size = 20;

  @Builder.Default
  private String sort = "createdAt,desc";

  public boolean hasNameFilter() {
    return name != null && !name.trim().isEmpty();
  }

  public boolean hasIngredientFilter() {
    return ingredientId != null && !ingredientId.trim().isEmpty();
  }

  public boolean hasDietFilter() {
    return diet != null;
  }

  public boolean hasExcludeAllergenFilter() {
    return excludeAllergen != null;
  }

  public boolean hasSpiceFilter() {
    return spice != null;
  }
}
