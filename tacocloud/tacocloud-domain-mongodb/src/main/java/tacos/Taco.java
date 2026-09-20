package tacos;

import java.util.Date;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

import javax.validation.constraints.NotNull;
import javax.validation.constraints.Size;

import org.springframework.data.annotation.Id;
import org.springframework.data.mongodb.core.index.CompoundIndex;
import org.springframework.data.mongodb.core.index.CompoundIndexes;
import org.springframework.data.mongodb.core.index.Indexed;
import org.springframework.data.mongodb.core.mapping.Document;
import org.springframework.data.rest.core.annotation.RestResource;

import lombok.Data;
import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;

@Data
@RestResource(rel = "tacos", path = "tacos")
@Document
@CompoundIndexes({
  @CompoundIndex(name = "taco_diet_spice_created_idx", def = "{'dietaryTags': 1, 'spiceLevel': 1, 'createdAt': -1}"),
  @CompoundIndex(name = "taco_name_created_idx", def = "{'name': 1, 'createdAt': -1}"),
  @CompoundIndex(name = "taco_ingredients_id_idx", def = "{'ingredients._id': 1}")
})
public class Taco {

  @Id
  private String id;
  
  @NotNull
  @Size(min = 5, message = "Name must be at least 5 characters long")
  @Indexed
  private String name;
  
  @Indexed
  private Date createdAt = new Date();
  
  @Size(min=1, message="You must choose at least 1 ingredient")
  private List<Ingredient> ingredients;

  @Indexed
  private Set<DietaryTag> dietaryTags = new LinkedHashSet<>();

  @Indexed
  private Set<Allergen> allergens = new LinkedHashSet<>();

  @Indexed
  private SpiceLevel spiceLevel = SpiceLevel.NONE;

  private boolean published = true;

}
