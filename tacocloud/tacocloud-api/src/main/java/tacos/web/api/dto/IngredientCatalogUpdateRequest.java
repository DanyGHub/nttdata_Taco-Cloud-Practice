package tacos.web.api.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class IngredientCatalogUpdateRequest {

  @DecimalMin(value = "0.00", message = "Unit price cannot be negative")
  private BigDecimal unitPrice;

  private Boolean available;

  private Long version;

}
