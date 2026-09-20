package tacos.physics;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.classification.Allergen;
import tacos.classification.DietaryTag;
import tacos.classification.SpiceLevel;
import tacos.physics.rules.AvailableIngredientsRule;
import tacos.physics.rules.ExtremeHeatRequiresCoolingRule;
import tacos.physics.rules.IngredientCountRule;
import tacos.physics.rules.NoDuplicateIngredientsRule;
import tacos.physics.rules.SingleBaseRule;
import tacos.physics.rules.SoggyTacoPhysicsRule;
import tacos.physics.rules.VeganMeatConflictRule;

@DisplayName("TC-18: Taco Physics")
public class TacoDesignRulesTest {

  private Ingredient ing(String id, String name, Type type) {
    return new Ingredient(id, name, type, BigDecimal.ONE, true, 50, 5, null);
  }

  private Ingredient ingWithSpice(String id, String name, Type type, SpiceLevel spice) {
    return new Ingredient(id, name, type, BigDecimal.ONE, true, 50, 5, null,
        Collections.emptySet(), Collections.emptySet(), spice);
  }

  private Ingredient ingWithDairy(String id, String name, Type type) {
    return new Ingredient(id, name, type, BigDecimal.ONE, true, 50, 5, null,
        Collections.emptySet(), Collections.singleton(Allergen.DAIRY), SpiceLevel.NONE);
  }

  private Ingredient ingWithDiet(String id, String name, Type type, DietaryTag... tags) {
    Set<DietaryTag> tagSet = new HashSet<>(Arrays.asList(tags));
    return new Ingredient(id, name, type, BigDecimal.ONE, true, 50, 5, null,
        tagSet, Collections.emptySet(), SpiceLevel.NONE);
  }

  // 1. SingleBaseRule
  @Test
  @DisplayName("SingleBaseRule: Falla con 0 bases")
  void singleBaseRule_zeroBases_shouldViolate() {
    SingleBaseRule rule = new SingleBaseRule();
    List<Ingredient> ings = Arrays.asList(
        ing("GRBF", "Beef", Type.PROTEIN),
        ing("TMTO", "Tomato", Type.VEGGIES)
    );
    TacoDesignContext ctx = new TacoDesignContext("No Base Taco", Arrays.asList("GRBF", "TMTO"), ings);

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(SingleBaseRule.CODE);
    assertThat(violations.get(0).getMessage()).contains("found 0 bases");
  }

  @Test
  @DisplayName("SingleBaseRule: Pasa con exactamente 1 base")
  void singleBaseRule_oneBase_shouldPass() {
    SingleBaseRule rule = new SingleBaseRule();
    List<Ingredient> ings = Arrays.asList(
        ing("FLTO", "Flour Tortilla", Type.WRAP),
        ing("GRBF", "Beef", Type.PROTEIN)
    );
    TacoDesignContext ctx = new TacoDesignContext("Standard Taco", Arrays.asList("FLTO", "GRBF"), ings);

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("SingleBaseRule: Falla con 2 bases (doble tortilla innecesaria)")
  void singleBaseRule_multipleBases_shouldViolate() {
    SingleBaseRule rule = new SingleBaseRule();
    List<Ingredient> ings = Arrays.asList(
        ing("FLTO", "Flour Tortilla", Type.WRAP),
        ing("COTO", "Corn Tortilla", Type.WRAP),
        ing("GRBF", "Beef", Type.PROTEIN)
    );
    TacoDesignContext ctx = new TacoDesignContext("Double Base Taco", Arrays.asList("FLTO", "COTO", "GRBF"), ings);

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(SingleBaseRule.CODE);
    assertThat(violations.get(0).getMessage()).contains("found 2 bases");
  }

  // 2. IngredientCountRule
  @Test
  @DisplayName("IngredientCountRule: Falla con 1 ingrediente (< 2)")
  void ingredientCountRule_oneIngredient_shouldViolate() {
    IngredientCountRule rule = new IngredientCountRule(2, 12);
    List<Ingredient> ings = Collections.singletonList(ing("FLTO", "Flour Tortilla", Type.WRAP));
    TacoDesignContext ctx = new TacoDesignContext("Only Tortilla", Collections.singletonList("FLTO"), ings);

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(IngredientCountRule.CODE);
    assertThat(violations.get(0).getMessage()).contains("contains 1");
  }

  @ParameterizedTest
  @ValueSource(ints = {2, 5, 8, 12})
  @DisplayName("IngredientCountRule: Pasa con conteos válidos entre 2 y 12")
  void ingredientCountRule_validCounts_shouldPass(int count) {
    IngredientCountRule rule = new IngredientCountRule(2, 12);
    List<String> rawIds = Collections.nCopies(count, "ING");
    TacoDesignContext ctx = new TacoDesignContext("Taco", rawIds, Collections.emptyList());

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).isEmpty();
  }

  @Test
  @DisplayName("IngredientCountRule: Falla con 13 ingredientes (> 12)")
  void ingredientCountRule_thirteenIngredients_shouldViolate() {
    IngredientCountRule rule = new IngredientCountRule(2, 12);
    List<String> rawIds = Collections.nCopies(13, "ING");
    TacoDesignContext ctx = new TacoDesignContext("Giant Taco", rawIds, Collections.emptyList());

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(IngredientCountRule.CODE);
    assertThat(violations.get(0).getMessage()).contains("contains 13");
  }

  // 3. NoDuplicateIngredientsRule
  @Test
  @DisplayName("NoDuplicateIngredientsRule: Falla ante ingredientes duplicados")
  void duplicateIngredients_shouldViolate() {
    NoDuplicateIngredientsRule rule = new NoDuplicateIngredientsRule();
    List<String> rawIds = Arrays.asList("FLTO", "GRBF", "FLTO");
    TacoDesignContext ctx = new TacoDesignContext("Dup Taco", rawIds, Collections.emptyList());

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(NoDuplicateIngredientsRule.CODE);
    assertThat(violations.get(0).getMessage()).contains("FLTO");
  }

  @Test
  @DisplayName("NoDuplicateIngredientsRule: Pasa con ingredientes distintos")
  void uniqueIngredients_shouldPass() {
    NoDuplicateIngredientsRule rule = new NoDuplicateIngredientsRule();
    List<String> rawIds = Arrays.asList("FLTO", "GRBF", "TMTO");
    TacoDesignContext ctx = new TacoDesignContext("Unique Taco", rawIds, Collections.emptyList());

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).isEmpty();
  }

  // 4. AvailableIngredientsRule
  @Test
  @DisplayName("AvailableIngredientsRule: Falla si un ingrediente está no disponible")
  void availableIngredientsRule_unavailable_shouldViolate() {
    AvailableIngredientsRule rule = new AvailableIngredientsRule();
    Ingredient unavail = ing("GRBF", "Beef", Type.PROTEIN);
    unavail.setAvailable(false);
    Ingredient wrap = ing("FLTO", "Flour", Type.WRAP);

    TacoDesignContext ctx = new TacoDesignContext("Taco", Arrays.asList("FLTO", "GRBF"), Arrays.asList(wrap, unavail));

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(AvailableIngredientsRule.CODE_UNAVAILABLE);
    assertThat(violations.get(0).getMessage()).contains("currently unavailable");
  }

  @Test
  @DisplayName("AvailableIngredientsRule: Falla si un ID no existe en el catálogo")
  void availableIngredientsRule_unknownId_shouldViolate() {
    AvailableIngredientsRule rule = new AvailableIngredientsRule();
    Ingredient wrap = ing("FLTO", "Flour", Type.WRAP);
    // Cliente pidió "FLTO" y "GHOST_ID", pero la base de datos sólo devolvió "FLTO"
    TacoDesignContext ctx = new TacoDesignContext("Taco", Arrays.asList("FLTO", "GHOST_ID"), Collections.singletonList(wrap));

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(AvailableIngredientsRule.CODE_UNKNOWN);
    assertThat(violations.get(0).getMessage()).contains("GHOST_ID");
  }

  // 5. ExtremeHeatRequiresCoolingRule (Fun Rule 1)
  @Test
  @DisplayName("ExtremeHeatRequiresCoolingRule: Picante HOT sin queso ni lácteo debe violar")
  void extremeHeat_withoutCooling_shouldViolate() {
    ExtremeHeatRequiresCoolingRule rule = new ExtremeHeatRequiresCoolingRule(true);
    Ingredient wrap = ing("FLTO", "Flour", Type.WRAP);
    Ingredient hotPepper = ingWithSpice("GHST", "Ghost Pepper", Type.SAUCE, SpiceLevel.HOT);

    TacoDesignContext ctx = new TacoDesignContext("Fire Taco", Arrays.asList("FLTO", "GHST"), Arrays.asList(wrap, hotPepper));

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(ExtremeHeatRequiresCoolingRule.CODE);
  }

  @Test
  @DisplayName("ExtremeHeatRequiresCoolingRule: Picante HOT con queso o crema láctea pasa")
  void extremeHeat_withDairyCooling_shouldPass() {
    ExtremeHeatRequiresCoolingRule rule = new ExtremeHeatRequiresCoolingRule(true);
    Ingredient wrap = ing("FLTO", "Flour", Type.WRAP);
    Ingredient hotPepper = ingWithSpice("GHST", "Ghost Pepper", Type.SAUCE, SpiceLevel.HOT);
    Ingredient cheese = ingWithDairy("CHED", "Cheddar", Type.CHEESE);

    TacoDesignContext ctx = new TacoDesignContext("Balanced Fire Taco", Arrays.asList("FLTO", "GHST", "CHED"), Arrays.asList(wrap, hotPepper, cheese));

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).isEmpty();
  }

  // 6. SoggyTacoPhysicsRule (Fun Rule 2)
  @Test
  @DisplayName("SoggyTacoPhysicsRule: Más de 2 salsas líquidas viola por sobrehumedad")
  void soggyTaco_moreThanMaxSauces_shouldViolate() {
    SoggyTacoPhysicsRule rule = new SoggyTacoPhysicsRule(2, true);
    Ingredient wrap = ing("FLTO", "Flour", Type.WRAP);
    Ingredient s1 = ing("SLSA", "Salsa", Type.SAUCE);
    Ingredient s2 = ing("SRCR", "Sour Cream", Type.SAUCE);
    Ingredient s3 = ing("CHLI", "Chili Sauce", Type.SAUCE);

    TacoDesignContext ctx = new TacoDesignContext("Soggy Taco", Arrays.asList("FLTO", "SLSA", "SRCR", "CHLI"), Arrays.asList(wrap, s1, s2, s3));

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(SoggyTacoPhysicsRule.CODE);
    assertThat(violations.get(0).getMessage()).contains("found 3 sauces");
  }

  @Test
  @DisplayName("SoggyTacoPhysicsRule: 2 salsas o menos pasa la física estructural")
  void soggyTaco_withinLimit_shouldPass() {
    SoggyTacoPhysicsRule rule = new SoggyTacoPhysicsRule(2, true);
    Ingredient wrap = ing("FLTO", "Flour", Type.WRAP);
    Ingredient s1 = ing("SLSA", "Salsa", Type.SAUCE);
    Ingredient s2 = ing("SRCR", "Sour Cream", Type.SAUCE);

    TacoDesignContext ctx = new TacoDesignContext("Crisp Taco", Arrays.asList("FLTO", "SLSA", "SRCR"), Arrays.asList(wrap, s1, s2));

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).isEmpty();
  }

  // 7. VeganMeatConflictRule (Fun Rule 3)
  @Test
  @DisplayName("VeganMeatConflictRule: Taco titulado vegano con carne de res debe violar")
  void veganMeatConflict_withAnimalProtein_shouldViolate() {
    VeganMeatConflictRule rule = new VeganMeatConflictRule(true);
    Ingredient wrap = ingWithDiet("COTO", "Corn Tortilla", Type.WRAP, DietaryTag.VEGAN);
    Ingredient meat = ing("GRBF", "Ground Beef", Type.PROTEIN); // No tiene DietaryTag.VEGAN

    TacoDesignContext ctx = new TacoDesignContext("Super Vegan Taco", Arrays.asList("COTO", "GRBF"), Arrays.asList(wrap, meat));

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).hasSize(1);
    assertThat(violations.get(0).getCode()).isEqualTo(VeganMeatConflictRule.CODE);
    assertThat(violations.get(0).getMessage()).contains("Ground Beef");
  }

  @Test
  @DisplayName("VeganMeatConflictRule: Taco no titulado vegano con carne pasa sin problema")
  void veganMeatConflict_regularTacoWithMeat_shouldPass() {
    VeganMeatConflictRule rule = new VeganMeatConflictRule(true);
    Ingredient wrap = ing("FLTO", "Flour", Type.WRAP);
    Ingredient meat = ing("GRBF", "Ground Beef", Type.PROTEIN);

    TacoDesignContext ctx = new TacoDesignContext("Carnivore Delight", Arrays.asList("FLTO", "GRBF"), Arrays.asList(wrap, meat));

    List<DesignViolation> violations = rule.validate(ctx);
    assertThat(violations).isEmpty();
  }
}
