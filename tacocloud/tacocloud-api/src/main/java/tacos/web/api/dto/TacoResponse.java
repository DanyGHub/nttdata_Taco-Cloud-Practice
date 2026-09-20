package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;
import tacos.classification.TacoClassification;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoResponse {

  private String id;
  private String name;
  private Date createdAt;
  private List<IngredientResponse> ingredients = new ArrayList<>();

  private Set<DietaryTag> dietaryTags = new LinkedHashSet<>();
  private Set<Allergen> allergens = new LinkedHashSet<>();
  private SpiceLevel spiceLevel = SpiceLevel.NONE;
  private String disclaimer = TacoClassification.ACADEMIC_DISCLAIMER;

}
