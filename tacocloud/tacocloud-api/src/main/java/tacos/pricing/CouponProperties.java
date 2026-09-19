package tacos.pricing;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.Map;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

import lombok.Data;

@Component
@ConfigurationProperties(prefix = "taco.discount")
@Data
public class CouponProperties {

  private Map<String, CouponRule> coupons = new HashMap<>();
  private Map<String, Integer> codes = new HashMap<>();

  public CouponRule findCoupon(String code) {
    if (code == null || code.trim().isEmpty()) {
      return null;
    }
    String normalized = code.trim().toUpperCase();

    for (Map.Entry<String, CouponRule> entry : coupons.entrySet()) {
      if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(normalized)) {
        CouponRule rule = entry.getValue();
        if (rule.getCode() == null) {
          rule.setCode(entry.getKey().trim().toUpperCase());
        }
        return rule;
      }
    }

    for (Map.Entry<String, Integer> entry : codes.entrySet()) {
      if (entry.getKey() != null && entry.getKey().trim().equalsIgnoreCase(normalized)) {
        return new CouponRule(
            normalized,
            DiscountType.PERCENTAGE,
            BigDecimal.valueOf(entry.getValue())
        );
      }
    }

    return null;
  }
}
