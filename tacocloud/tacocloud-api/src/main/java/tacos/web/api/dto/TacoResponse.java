package tacos.web.api.dto;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;

import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class TacoResponse {

  private String id;
  private String name;
  private Date createdAt;
  private List<IngredientResponse> ingredients = new ArrayList<>();

}
