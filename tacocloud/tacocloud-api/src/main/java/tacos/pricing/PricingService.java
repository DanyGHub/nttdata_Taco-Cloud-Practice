package tacos.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.IngredientRepository;

@Service
public class PricingService {

  private static final Logger log = LoggerFactory.getLogger(PricingService.class);

  public static final RoundingMode DEFAULT_ROUNDING_MODE = RoundingMode.HALF_UP;
  public static final String DEFAULT_CURRENCY = "USD";
  public static final BigDecimal BASE_TACO_PRICE = BigDecimal.ZERO;

  private final IngredientRepository ingredientRepo;
  private final CouponService couponService;

  @Autowired
  public PricingService(IngredientRepository ingredientRepo, CouponService couponService) {
    this.ingredientRepo = ingredientRepo;
    this.couponService = couponService;
  }

  public PricingService(IngredientRepository ingredientRepo) {
    this(ingredientRepo, null);
  }

  public Mono<TacoOrder> calculateAndApplyPricing(TacoOrder order) {
    if (order == null) {
      return Mono.empty();
    }

    if (order.getItems() == null || order.getItems().isEmpty()) {
      order.setSubtotal(BigDecimal.ZERO.setScale(2, DEFAULT_ROUNDING_MODE));
      order.setDiscountAmount(BigDecimal.ZERO.setScale(2, DEFAULT_ROUNDING_MODE));
      order.setTotal(BigDecimal.ZERO.setScale(2, DEFAULT_ROUNDING_MODE));
      order.setCurrency(DEFAULT_CURRENCY);
      return Mono.just(order);
    }

    // Recolectar todos los IDs de ingredientes presentes en la orden
    List<String> ingredientIds = order.getItems().stream()
        .map(OrderItem::getTaco)
        .filter(Objects::nonNull)
        .filter(taco -> taco.getIngredients() != null)
        .flatMap(taco -> taco.getIngredients().stream())
        .filter(Objects::nonNull)
        .map(Ingredient::getId)
        .filter(Objects::nonNull)
        .distinct()
        .collect(Collectors.toList());

    Mono<Map<String, BigDecimal>> priceMapMono;
    if (ingredientRepo != null && !ingredientIds.isEmpty()) {
      priceMapMono = ingredientRepo.findAllById(ingredientIds)
          .collectMap(Ingredient::getId, ing -> ing.getUnitPrice() != null ? ing.getUnitPrice() : BigDecimal.ZERO)
          .defaultIfEmpty(Collections.emptyMap());
    } else {
      priceMapMono = Mono.just(Collections.emptyMap());
    }

    return priceMapMono.map(priceMap -> {
      BigDecimal orderSubtotal = BigDecimal.ZERO;

      for (OrderItem item : order.getItems()) {
        BigDecimal tacoPrice = BASE_TACO_PRICE;
        Taco taco = item.getTaco();

        if (taco != null && taco.getIngredients() != null) {
          for (Ingredient ing : taco.getIngredients()) {
            if (ing != null) {
              BigDecimal ingPrice = null;
              if (ing.getId() != null && priceMap.containsKey(ing.getId())) {
                ingPrice = priceMap.get(ing.getId());
              } else if (ing.getUnitPrice() != null) {
                ingPrice = ing.getUnitPrice();
              }
              if (ingPrice != null) {
                tacoPrice = tacoPrice.add(ingPrice);
              }
            }
          }
        }

        tacoPrice = tacoPrice.setScale(2, DEFAULT_ROUNDING_MODE);
        int qty = item.getQuantity() > 0 ? item.getQuantity() : 1;
        BigDecimal lineSubtotal = tacoPrice.multiply(BigDecimal.valueOf(qty)).setScale(2, DEFAULT_ROUNDING_MODE);

        item.setQuantity(qty);
        item.setUnitPriceAtPurchase(tacoPrice);
        item.setSubtotal(lineSubtotal);

        orderSubtotal = orderSubtotal.add(lineSubtotal);
      }

      orderSubtotal = orderSubtotal.setScale(2, DEFAULT_ROUNDING_MODE);
      order.setSubtotal(orderSubtotal);

      BigDecimal discount = BigDecimal.ZERO.setScale(2, DEFAULT_ROUNDING_MODE);
      if (couponService != null && order.getCouponCode() != null && !order.getCouponCode().trim().isEmpty()) {
        CouponValidationResult couponResult = couponService.validateAndCalculate(order.getCouponCode(), orderSubtotal);
        if (couponResult.isValid()) {
          discount = couponResult.getDiscountAmount();
          order.setCouponCode(couponResult.getCode());
          order.setDiscountAmount(discount);
        } else {
          log.info("Coupon code '{}' could not be applied: {}", order.getCouponCode(), couponResult.getMessage());
          order.setDiscountAmount(BigDecimal.ZERO.setScale(2, DEFAULT_ROUNDING_MODE));
        }
      } else {
        order.setDiscountAmount(BigDecimal.ZERO.setScale(2, DEFAULT_ROUNDING_MODE));
      }

      BigDecimal finalTotal = orderSubtotal.subtract(discount).max(BigDecimal.ZERO).setScale(2, DEFAULT_ROUNDING_MODE);
      order.setTotal(finalTotal);
      order.setCurrency(DEFAULT_CURRENCY);

      log.info("Server-calculated pricing applied: subtotal={}, discount={}, total={}, currency={}, items={}",
          orderSubtotal, discount, finalTotal, DEFAULT_CURRENCY, order.getItems().size());

      return order;
    });
  }

}
