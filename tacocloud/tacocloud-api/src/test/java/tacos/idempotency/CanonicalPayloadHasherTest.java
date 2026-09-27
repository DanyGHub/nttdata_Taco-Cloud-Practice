package tacos.idempotency;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.TacoRequest;

public class CanonicalPayloadHasherTest {

  private CanonicalPayloadHasher hasher;

  @BeforeEach
  public void setUp() {
    hasher = new CanonicalPayloadHasher();
  }

  @Test
  @DisplayName("Solicitudes con mismos datos y espacios variantes generan el mismo hash canónico")
  public void computeHash_whitespaceInvariance() {
    TacoRequest taco1 = new TacoRequest();
    taco1.setName("Carnitas Especial");
    taco1.setIngredientIds(Arrays.asList("FLTO", "CARN", "CHED"));

    OrderItemRequest item1 = new OrderItemRequest();
    item1.setTaco(taco1);
    item1.setQuantity(2);

    OrderCreateRequest req1 = new OrderCreateRequest();
    req1.setDeliveryName("Alice Smith");
    req1.setDeliveryStreet("123 Main St");
    req1.setDeliveryCity("Springfield");
    req1.setDeliveryState("IL");
    req1.setDeliveryZip("62701");
    req1.setPaymentMethodId("pm-100");
    req1.setCouponCode("DISCOUNT10");
    req1.setItems(Arrays.asList(item1));

    // req2 tiene espacios adicionales en blanco en campos de texto y los mismos ingredientes en orden distinto
    TacoRequest taco2 = new TacoRequest();
    taco2.setName("  Carnitas Especial  ");
    taco2.setIngredientIds(Arrays.asList("CHED", "FLTO", "CARN")); // Mismos ingredientes, orden distinto

    OrderItemRequest item2 = new OrderItemRequest();
    item2.setTaco(taco2);
    item2.setQuantity(2);

    OrderCreateRequest req2 = new OrderCreateRequest();
    req2.setDeliveryName("Alice Smith ");
    req2.setDeliveryStreet(" 123 Main St");
    req2.setDeliveryCity("Springfield ");
    req2.setDeliveryState("IL");
    req2.setDeliveryZip("62701 ");
    req2.setPaymentMethodId("pm-100");
    req2.setCouponCode("DISCOUNT10 ");
    req2.setItems(Arrays.asList(item2));

    String hash1 = hasher.computeHash(req1);
    String hash2 = hasher.computeHash(req2);

    assertThat(hash1).isNotEmpty();
    assertThat(hash1).isEqualTo(hash2);
  }

  @Test
  @DisplayName("Modificar cualquier campo relevante cambia el hash canónico")
  public void computeHash_changesOnPayloadVariation() {
    TacoRequest taco = new TacoRequest();
    taco.setName("Taco Original");
    taco.setIngredientIds(Arrays.asList("FLTO", "GRBF"));

    OrderItemRequest item = new OrderItemRequest();
    item.setTaco(taco);
    item.setQuantity(1);

    OrderCreateRequest base = new OrderCreateRequest();
    base.setDeliveryName("Bob");
    base.setDeliveryStreet("456 Oak St");
    base.setDeliveryCity("Austin");
    base.setDeliveryState("TX");
    base.setDeliveryZip("73301");
    base.setItems(Arrays.asList(item));

    String baseHash = hasher.computeHash(base);

    // Variación 1: Dirección distinta
    OrderCreateRequest diffAddress = new OrderCreateRequest();
    diffAddress.setDeliveryName("Bob");
    diffAddress.setDeliveryStreet("789 Pine St");
    diffAddress.setDeliveryCity("Austin");
    diffAddress.setDeliveryState("TX");
    diffAddress.setDeliveryZip("73301");
    diffAddress.setItems(Arrays.asList(item));
    assertThat(hasher.computeHash(diffAddress)).isNotEqualTo(baseHash);

    // Variación 2: Cantidad distinta
    OrderItemRequest diffQtyItem = new OrderItemRequest();
    diffQtyItem.setTaco(taco);
    diffQtyItem.setQuantity(3);
    OrderCreateRequest diffQty = new OrderCreateRequest();
    diffQty.setDeliveryName("Bob");
    diffQty.setDeliveryStreet("456 Oak St");
    diffQty.setDeliveryCity("Austin");
    diffQty.setDeliveryState("TX");
    diffQty.setDeliveryZip("73301");
    diffQty.setItems(Arrays.asList(diffQtyItem));
    assertThat(hasher.computeHash(diffQty)).isNotEqualTo(baseHash);

    // Variación 3: Ingrediente adicional
    TacoRequest extraIngTaco = new TacoRequest();
    extraIngTaco.setName("Taco Original");
    extraIngTaco.setIngredientIds(Arrays.asList("FLTO", "GRBF", "CHED"));
    OrderItemRequest extraIngItem = new OrderItemRequest();
    extraIngItem.setTaco(extraIngTaco);
    extraIngItem.setQuantity(1);
    OrderCreateRequest diffIng = new OrderCreateRequest();
    diffIng.setDeliveryName("Bob");
    diffIng.setDeliveryStreet("456 Oak St");
    diffIng.setDeliveryCity("Austin");
    diffIng.setDeliveryState("TX");
    diffIng.setDeliveryZip("73301");
    diffIng.setItems(Arrays.asList(extraIngItem));
    assertThat(hasher.computeHash(diffIng)).isNotEqualTo(baseHash);
  }

}
