package tacos.classification;

import java.io.Serializable;
import java.util.LinkedHashSet;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoClassification implements Serializable {
  private static final long serialVersionUID = 1L;

  public static final String ACADEMIC_DISCLAIMER =
      "DISCLAIMER: Esta información nutricional y de alérgenos se deriva de los ingredientes estándar declarados. En un entorno de cocina real, no sustituye el control de contaminación cruzada ni garantiza la ausencia total de trazas.";

  private Set<DietaryTag> dietaryTags = new LinkedHashSet<>();
  private Set<Allergen> allergens = new LinkedHashSet<>();
  private SpiceLevel spiceLevel = SpiceLevel.NONE;
  private String disclaimer = ACADEMIC_DISCLAIMER;

  public TacoClassification(Set<DietaryTag> dietaryTags, Set<Allergen> allergens, SpiceLevel spiceLevel) {
    this.dietaryTags = dietaryTags != null ? dietaryTags : new LinkedHashSet<>();
    this.allergens = allergens != null ? allergens : new LinkedHashSet<>();
    this.spiceLevel = spiceLevel != null ? spiceLevel : SpiceLevel.NONE;
    this.disclaimer = ACADEMIC_DISCLAIMER;
  }
}
