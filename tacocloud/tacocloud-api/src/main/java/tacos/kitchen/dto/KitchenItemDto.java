package tacos.kitchen.dto;

import java.io.Serializable;
import java.util.ArrayList;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KitchenItemDto implements Serializable {
  private static final long serialVersionUID = 1L;

  private String tacoName;
  private int quantity;
  @Builder.Default
  private List<String> ingredients = new ArrayList<>();
  private String notes;
}
