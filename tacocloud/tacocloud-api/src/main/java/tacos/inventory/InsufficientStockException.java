package tacos.inventory;

import tacos.web.api.config.ResourceConflictException;

//Mapeada a HTTP 409 Conflict con código INSUFFICIENT_STOCK.

public class InsufficientStockException extends ResourceConflictException {
  private static final long serialVersionUID = 1L;

  private final String ingredientId;
  private final int requestedQuantity;
  private final int availableStock;

  public InsufficientStockException(String ingredientId, int requestedQuantity, int availableStock) {
    super("INSUFFICIENT_STOCK", String.format(
        "Insufficient stock for ingredient '%s': requested %d, available %d",
        ingredientId, requestedQuantity, availableStock));
    this.ingredientId = ingredientId;
    this.requestedQuantity = requestedQuantity;
    this.availableStock = availableStock;
  }

  public InsufficientStockException(String ingredientId, int requestedQuantity) {
    this(ingredientId, requestedQuantity, 0);
  }

  public String getIngredientId() {
    return ingredientId;
  }

  public int getRequestedQuantity() {
    return requestedQuantity;
  }

  public int getAvailableStock() {
    return availableStock;
  }
}
