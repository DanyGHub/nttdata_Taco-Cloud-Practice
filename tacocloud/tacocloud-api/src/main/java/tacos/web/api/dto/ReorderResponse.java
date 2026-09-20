package tacos.web.api.dto;

import java.math.BigDecimal;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class ReorderResponse {
  private String status;
  private String message;
  private BigDecimal oldTotal;
  private BigDecimal newTotal;
  private BigDecimal priceDifference;
  private boolean priceChanged;
  private List<ItemDifference> itemDifferences;
  private OrderQuoteResponse newQuote;
  private OrderResponse order;
}
