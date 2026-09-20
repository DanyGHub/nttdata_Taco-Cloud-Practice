package tacos.web.api.dto;

import java.util.LinkedHashSet;
import java.util.Set;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;
import tacos.classification.TacoClassification;

/**
 * DTO GET /api/tacos/{id}/classification.
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
public class TacoClassificationResponse {

  private String tacoId;
  private String tacoName;
  @Builder.Default
  private Set<DietaryTag> dietaryTags = new LinkedHashSet<>();
  @Builder.Default
  private Set<Allergen> allergens = new LinkedHashSet<>();
  @Builder.Default
  private SpiceLevel spiceLevel = SpiceLevel.NONE;
  @Builder.Default
  private String disclaimer = TacoClassification.ACADEMIC_DISCLAIMER;

  public static TacoClassificationResponse from(String tacoId, String tacoName, TacoClassification classification) {
    if (classification == null) {
      return TacoClassificationResponse.builder()
          .tacoId(tacoId)
          .tacoName(tacoName)
          .build();
    }
    return TacoClassificationResponse.builder()
        .tacoId(tacoId)
        .tacoName(tacoName)
        .dietaryTags(classification.getDietaryTags())
        .allergens(classification.getAllergens())
        .spiceLevel(classification.getSpiceLevel())
        .disclaimer(classification.getDisclaimer())
        .build();
  }
}
