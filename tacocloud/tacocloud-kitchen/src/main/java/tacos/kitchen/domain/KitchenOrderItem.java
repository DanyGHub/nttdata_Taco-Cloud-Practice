package tacos.kitchen.domain;

import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class KitchenOrderItem {

  private String name;

  private int quantity;

  private List<String> ingredients;

}
