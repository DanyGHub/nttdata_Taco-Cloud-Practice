package tacos.inventory;

import java.io.Serializable;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class ReservedItem implements Serializable {
  private static final long serialVersionUID = 1L;

  private String ingredientId;
  private int quantity;
}
