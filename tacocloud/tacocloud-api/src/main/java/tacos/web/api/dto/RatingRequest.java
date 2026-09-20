package tacos.web.api.dto;

import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class RatingRequest {

  @NotNull(message = "Score is required")
  @Min(value = 1, message = "Score must be between 1 and 5")
  @Max(value = 5, message = "Score must be between 1 and 5")
  private Integer score;

}
