package tacos.messaging;

import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;

import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
@Builder
@JsonIgnoreProperties(ignoreUnknown = true)
@JsonInclude(JsonInclude.Include.NON_NULL)
public class OrderEventItemPayload {

  private String tacoName;

  @Builder.Default
  private int quantity = 1;

  @Builder.Default
  private List<OrderEventIngredientPayload> ingredients = new ArrayList<>();

  /**
   * Alias de compatibilidad para plantillas de cocina o consumidores heredados.
   */
  public String getName() {
    return tacoName;
  }

  public void setName(String name) {
    this.tacoName = name;
  }

}
