package tacos.web.api.dto;

import java.util.Collections;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.physics.DesignViolation;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class TacoDesignValidationResponse {

  private boolean valid;

  @JsonInclude(JsonInclude.Include.ALWAYS)
  private List<DesignViolation> violations;

  public static TacoDesignValidationResponse of(boolean valid, List<DesignViolation> violations) {
    return new TacoDesignValidationResponse(valid, violations != null ? violations : Collections.emptyList());
  }
}
