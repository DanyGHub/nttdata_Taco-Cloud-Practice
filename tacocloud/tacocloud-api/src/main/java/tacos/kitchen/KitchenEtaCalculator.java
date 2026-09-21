package tacos.kitchen;

import java.util.List;

import org.springframework.stereotype.Component;

import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;

@Component
public class KitchenEtaCalculator {

  public static final int DEFAULT_BASE_MINUTES = 3;
  public static final double DEFAULT_MINUTES_PER_TACO = 2.0;
  public static final double DEFAULT_COMPLEXITY_EXTRA_PER_INGREDIENT = 0.5;

  private final int baseMinutes;
  private final double minutesPerTaco;
  private final double complexityExtraPerIngredient;

  public KitchenEtaCalculator() {
    this(DEFAULT_BASE_MINUTES, DEFAULT_MINUTES_PER_TACO, DEFAULT_COMPLEXITY_EXTRA_PER_INGREDIENT);
  }

  public KitchenEtaCalculator(int baseMinutes, double minutesPerTaco, double complexityExtraPerIngredient) {
    this.baseMinutes = baseMinutes;
    this.minutesPerTaco = minutesPerTaco;
    this.complexityExtraPerIngredient = complexityExtraPerIngredient;
  }

  /**
   * Calcula el tiempo intrínseco de preparación de una orden individual.
   */
  public int calculateOrderPrepMinutes(TacoOrder order) {
    if (order == null) {
      return baseMinutes;
    }

    double totalMinutes = baseMinutes;
    int totalTacos = 0;
    double complexityMinutes = 0.0;

    if (order.getItems() != null && !order.getItems().isEmpty()) {
      for (OrderItem item : order.getItems()) {
        if (item != null) {
          int qty = item.getQuantity() > 0 ? item.getQuantity() : 1;
          totalTacos += qty;
          if (item.getTaco() != null && item.getTaco().getIngredients() != null) {
            int ingCount = item.getTaco().getIngredients().size();
            if (ingCount > 3) {
              complexityMinutes += (ingCount - 3) * complexityExtraPerIngredient * qty;
            }
          }
        }
      }
    } else if (order.getTacos() != null && !order.getTacos().isEmpty()) {
      for (Taco taco : order.getTacos()) {
        if (taco != null) {
          totalTacos += 1;
          if (taco.getIngredients() != null) {
            int ingCount = taco.getIngredients().size();
            if (ingCount > 3) {
              complexityMinutes += (ingCount - 3) * complexityExtraPerIngredient;
            }
          }
        }
      }
    }

    totalMinutes += (totalTacos * minutesPerTaco) + complexityMinutes;
    return (int) Math.ceil(totalMinutes);
  }

  /**
   * Calcula el ETA acumulativo para una orden considerando su orden en la cola y los pedidos precedentes.
   */
  public int calculateCumulativeEta(TacoOrder order, List<TacoOrder> precedingOrders) {
    int precedingTime = 0;
    if (precedingOrders != null) {
      for (TacoOrder prec : precedingOrders) {
        precedingTime += calculateOrderPrepMinutes(prec);
      }
    }
    return precedingTime + calculateOrderPrepMinutes(order);
  }

}
