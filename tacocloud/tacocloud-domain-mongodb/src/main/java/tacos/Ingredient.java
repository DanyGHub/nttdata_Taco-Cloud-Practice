package tacos;

import java.math.BigDecimal;

import org.springframework.data.annotation.Id;
import org.springframework.data.annotation.Version;
import org.springframework.data.mongodb.core.mapping.Document;

import lombok.Data;

@Data
@Document
public class Ingredient {

  @Id
  private String id;
  private String name;
  private Type type;

  private BigDecimal unitPrice = BigDecimal.ZERO;
  private boolean available = true;
  private int stockOnHand = 0;
  private int reorderLevel = 0;

  @Version
  private Long version;

  public Ingredient() {
  }

  public Ingredient(String id, String name, Type type) {
    this(id, name, type, BigDecimal.ZERO, true, 0, 0, null);
  }

  public Ingredient(String id, String name, Type type, BigDecimal unitPrice, boolean available, int stockOnHand, int reorderLevel, Long version) {
    this.id = id;
    this.name = name;
    this.type = type;
    this.unitPrice = unitPrice != null ? unitPrice : BigDecimal.ZERO;
    this.available = available;
    this.stockOnHand = stockOnHand;
    this.reorderLevel = reorderLevel;
    this.version = version;
  }

  public enum Type {
    WRAP, PROTEIN, VEGGIES, CHEESE, SAUCE
  }

}
