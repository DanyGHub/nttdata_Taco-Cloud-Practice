package tacos.web.api.dto;

import javax.validation.constraints.NotNull;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StockAdjustmentRequest {

  @NotNull(message = "Adjustment amount is required")
  private Integer amount;

  private String reason;

  private Long expectedVersion;

}
