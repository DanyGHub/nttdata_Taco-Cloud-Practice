package tacos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tacos.Ingredient;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;

@DisplayName("TC-26: Cálculo de tiempo estimado")
public class KitchenEtaCalculationTest {

  private KitchenEtaCalculator calculator;

  @BeforeEach
  public void setUp() {
    calculator = new KitchenEtaCalculator(3, 2.0, 0.5);
  }

  @Test
  @DisplayName("1. El cálculo de ETA es completamente determinista para los mismos datos")
  public void testEtaCalculationIsDeterministic() {
    TacoOrder order = new TacoOrder();
    Taco taco = new Taco();
    taco.setName("Simple Taco");
    taco.setIngredients(Arrays.asList(
        new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP),
        new Ingredient("CARN", "Carnitas", Ingredient.Type.PROTEIN)
    ));
    order.addOrderItem(new OrderItem(taco, 3));

    int eta1 = calculator.calculateOrderPrepMinutes(order);
    int eta2 = calculator.calculateOrderPrepMinutes(order);
    int eta3 = calculator.calculateOrderPrepMinutes(order);

    assertThat(eta1).isEqualTo(eta2);
    assertThat(eta2).isEqualTo(eta3);
    // Base 3 + (3 tacos * 2 min) = 9 min
    assertThat(eta1).isEqualTo(9);
  }

  @Test
  @DisplayName("2. El tiempo estimado aumenta en función de la cantidad de tacos")
  public void testEtaIncreasesWithTacoQuantity() {
    Taco taco = new Taco();
    taco.setName("Taco");

    TacoOrder orderSmall = new TacoOrder();
    orderSmall.addOrderItem(new OrderItem(taco, 1)); // 3 + 2 = 5 min

    TacoOrder orderLarge = new TacoOrder();
    orderLarge.addOrderItem(new OrderItem(taco, 5)); // 3 + 10 = 13 min

    int etaSmall = calculator.calculateOrderPrepMinutes(orderSmall);
    int etaLarge = calculator.calculateOrderPrepMinutes(orderLarge);

    assertThat(etaLarge).isGreaterThan(etaSmall);
    assertThat(etaSmall).isEqualTo(5);
    assertThat(etaLarge).isEqualTo(13);
  }

  @Test
  @DisplayName("3. La complejidad de ingredientes adicionales incrementa el tiempo estimado")
  public void testEtaIncreasesWithIngredientComplexity() {
    // Taco simple con 3 ingredientes (sin penalización por complejidad)
    Taco simpleTaco = new Taco();
    simpleTaco.setName("Simple");
    simpleTaco.setIngredients(Arrays.asList(
        new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP),
        new Ingredient("CARN", "Carnitas", Ingredient.Type.PROTEIN),
        new Ingredient("CHED", "Cheddar", Ingredient.Type.CHEESE)
    ));

    // Taco complejo con 6 ingredientes (3 ingredientes extra = 3 * 0.5 = +1.5 min)
    Taco complexTaco = new Taco();
    complexTaco.setName("Loaded");
    complexTaco.setIngredients(Arrays.asList(
        new Ingredient("FLTO", "Flour Tortilla", Ingredient.Type.WRAP),
        new Ingredient("CARN", "Carnitas", Ingredient.Type.PROTEIN),
        new Ingredient("CHED", "Cheddar", Ingredient.Type.CHEESE),
        new Ingredient("LETC", "Lettuce", Ingredient.Type.VEGGIES),
        new Ingredient("TMTO", "Tomatoes", Ingredient.Type.VEGGIES),
        new Ingredient("SLSA", "Salsa", Ingredient.Type.SAUCE)
    ));

    TacoOrder orderSimple = new TacoOrder();
    orderSimple.addOrderItem(new OrderItem(simpleTaco, 2)); // 3 + (2*2) = 7 min

    TacoOrder orderComplex = new TacoOrder();
    orderComplex.addOrderItem(new OrderItem(complexTaco, 2)); // 3 + (2*2) + (2 * 1.5) = 10 min

    int etaSimple = calculator.calculateOrderPrepMinutes(orderSimple);
    int etaComplex = calculator.calculateOrderPrepMinutes(orderComplex);

    assertThat(etaComplex).isGreaterThan(etaSimple);
    assertThat(etaSimple).isEqualTo(7);
    assertThat(etaComplex).isEqualTo(10);
  }

  @Test
  @DisplayName("4. El ETA acumulativo incluye el tiempo de todas las órdenes precedentes en cola")
  public void testCumulativeQueueEta() {
    Taco taco = new Taco();
    taco.setName("Taco");

    TacoOrder order1 = new TacoOrder();
    order1.addOrderItem(new OrderItem(taco, 2)); // 3 + 4 = 7 min

    TacoOrder order2 = new TacoOrder();
    order2.addOrderItem(new OrderItem(taco, 1)); // 3 + 2 = 5 min

    TacoOrder order3 = new TacoOrder();
    order3.addOrderItem(new OrderItem(taco, 3)); // 3 + 6 = 9 min

    List<TacoOrder> preceding = Arrays.asList(order1, order2);

    int cumulativeEtaOrder3 = calculator.calculateCumulativeEta(order3, preceding);
    // 7 + 5 + 9 = 21 min
    assertThat(cumulativeEtaOrder3).isEqualTo(21);
  }

}
