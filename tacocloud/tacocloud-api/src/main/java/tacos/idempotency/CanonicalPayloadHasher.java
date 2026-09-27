package tacos.idempotency;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import org.springframework.stereotype.Component;

import tacos.web.api.dto.IngredientRequest;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.TacoRequest;

/**
 * Generador de representación canónica y hashing SHA-256 para OrderCreateRequest.
 */
@Component
public class CanonicalPayloadHasher {

  public String computeHash(OrderCreateRequest request) {
    if (request == null) {
      return "EMPTY_REQUEST";
    }

    StringBuilder sb = new StringBuilder();

    sb.append("DELIVERY:")
      .append(clean(request.getDeliveryName())).append("|")
      .append(clean(request.getDeliveryStreet())).append("|")
      .append(clean(request.getDeliveryCity())).append("|")
      .append(clean(request.getDeliveryState())).append("|")
      .append(clean(request.getDeliveryZip())).append(";");

    sb.append("PAYMENT:")
      .append(clean(request.getPaymentMethodId())).append("|")
      .append(clean(request.getPaymentToken())).append("|")
      .append(clean(request.getCcNumber())).append("|")
      .append(clean(request.getLast4())).append("|")
      .append(clean(request.getBrand())).append(";");

    sb.append("COUPON:").append(clean(request.getCouponCode())).append(";");

    List<String> canonicalTacos = new ArrayList<>();

    if (request.getItems() != null && !request.getItems().isEmpty()) {
      for (OrderItemRequest item : request.getItems()) {
        if (item != null && item.getTaco() != null) {
          String tacoName = clean(item.getTaco().getName());
          int qty = item.getQuantity() > 0 ? item.getQuantity() : 1;
          List<String> ingredients = extractIngredients(item.getTaco());
          canonicalTacos.add("TACO:" + tacoName + ",QTY:" + qty + ",INGS:[" + String.join(",", ingredients) + "]");
        }
      }
    } else if (request.getTacos() != null && !request.getTacos().isEmpty()) {
      for (TacoRequest taco : request.getTacos()) {
        if (taco != null) {
          String tacoName = clean(taco.getName());
          List<String> ingredients = extractIngredients(taco);
          canonicalTacos.add("TACO:" + tacoName + ",QTY:1,INGS:[" + String.join(",", ingredients) + "]");
        }
      }
    }

    Collections.sort(canonicalTacos);
    sb.append("ITEMS:[").append(String.join(";", canonicalTacos)).append("]");

    return sha256Hex(sb.toString());
  }

  private List<String> extractIngredients(TacoRequest taco) {
    List<String> ids = new ArrayList<>();
    if (taco.getIngredients() != null) {
      for (IngredientRequest ir : taco.getIngredients()) {
        if (ir != null && ir.getId() != null) {
          ids.add(clean(ir.getId()));
        }
      }
    }
    if (taco.getIngredientIds() != null) {
      for (String id : taco.getIngredientIds()) {
        if (id != null) {
          ids.add(clean(id));
        }
      }
    }
    return ids.stream()
        .filter(s -> !s.isEmpty())
        .sorted()
        .collect(Collectors.toList());
  }

  private String clean(String str) {
    if (str == null) {
      return "";
    }
    return str.trim();
  }

  private String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] hashBytes = md.digest(input.getBytes(StandardCharsets.UTF_8));
      StringBuilder hexString = new StringBuilder();
      for (byte b : hashBytes) {
        String hex = Integer.toHexString(0xff & b);
        if (hex.length() == 1) {
          hexString.append('0');
        }
        hexString.append(hex);
      }
      return hexString.toString();
    } catch (NoSuchAlgorithmException e) {
      throw new IllegalStateException("SHA-256 algorithm not available", e);
    }
  }

}
