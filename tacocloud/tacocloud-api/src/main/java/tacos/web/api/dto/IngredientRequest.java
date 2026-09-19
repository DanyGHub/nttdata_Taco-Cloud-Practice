package tacos.web.api.dto;

import java.math.BigDecimal;
import javax.validation.constraints.DecimalMin;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import tacos.Ingredient.Type;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class IngredientRequest {

  @NotBlank(message = "Ingredient ID is required")
  private String id;

  @NotBlank(message = "Ingredient name is required")
  private String name;

  @NotNull(message = "Ingredient type is required")
  private Type type;

  @DecimalMin(value = "0.00", message = "Unit price cannot be negative")
  private BigDecimal unitPrice;

  private Boolean available;

  @Min(value = 0, message = "Stock cannot be negative")
  private Integer stockOnHand;

  @Min(value = 0, message = "Reorder level cannot be negative")
  private Integer reorderLevel;

  public IngredientRequest(String id, String name, Type type) {
    this(id, name, type, null, null, null, null);
  }

}
