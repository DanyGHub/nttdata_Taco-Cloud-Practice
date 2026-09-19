package tacos.web.api.dto;

import java.math.BigDecimal;

import javax.validation.Valid;
import javax.validation.constraints.Max;
import javax.validation.constraints.Min;
import javax.validation.constraints.NotNull;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderItemRequest {

  @NotNull(message = "Taco specification is required")
  @Valid
  private TacoRequest taco;

  @Min(value = 1, message = "Quantity must be at least 1")
  @Max(value = 100, message = "Quantity cannot exceed 100")
  private int quantity = 1;

  private BigDecimal unitPrice;
  private BigDecimal subtotal;

  public OrderItemRequest(TacoRequest taco, int quantity) {
    this.taco = taco;
    this.quantity = quantity;
  }
}
