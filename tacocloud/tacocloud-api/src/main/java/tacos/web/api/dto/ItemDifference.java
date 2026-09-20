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
public class ItemDifference {
  private String tacoName;
  private int quantity;
  private BigDecimal oldUnitPrice;
  private BigDecimal newUnitPrice;
  private BigDecimal oldLineTotal;
  private BigDecimal newLineTotal;
  private BigDecimal difference;
  private List<String> notes;
}
