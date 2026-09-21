package tacos.messaging;

/**
 * Tipos de eventos canónicos emitidos en el ciclo de vida de una orden.
 */
public enum OrderEventType {
  ORDER_CREATED,
  ORDER_STATUS_CHANGED,
  ORDER_CANCELLED
}
