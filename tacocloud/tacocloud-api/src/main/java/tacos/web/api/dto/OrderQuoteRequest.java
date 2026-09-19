package tacos.web.api.dto;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

import javax.validation.Valid;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderQuoteRequest {

  @Valid
  private List<OrderItemRequest> items = new ArrayList<>();

  @Valid
  private List<TacoRequest> tacos = new ArrayList<>();

  private BigDecimal subtotal;
  private String couponCode;

  public void addItem(OrderItemRequest item) {
    if (this.items == null) {
      this.items = new ArrayList<>();
    }
    this.items.add(item);
  }
}
