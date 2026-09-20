package tacos.inventory;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;


// Inventory Logic

public interface InventoryService {

  /**
   * Reserva atómicamente el inventario requerido para una orden o borrador de orden.
   * Utiliza actualización atómica condicionada (stockOnHand >= requested).
   * En caso de falla parcial en una orden multi-ingrediente, compensa los ítems ya descontados
   * y lanza InsufficientStockException.
   * Si ya existe una reserva activa para esta orden, retorna la reserva existente (idempotencia).
   *
   * @param orderDraft orden o borrador a reservar
   * @return Mono con la StockReservation generada
   */
  Mono<StockReservation> reserve(TacoOrder orderDraft);

  /**
   * Libera atómicamente el inventario reservado para una orden específica.
   * Si la reserva ya fue liberada previamente o no existe, es una operación idempotente (no-op).
   *
   * @param orderId ID de la orden cuyo stock debe ser restituido
   * @return Mono con la StockReservation liberada, o vacío si no existía o ya estaba liberada
   */
  Mono<StockReservation> releaseForOrder(String orderId);

  /**
   * Confirma una reserva de inventario existente pasando su estado a CONFIRMED.
   *
   * @param orderId ID de la orden
   * @return Mono con la StockReservation confirmada
   */
  Mono<StockReservation> confirmReservation(String orderId);

  /**
   * Busca la reserva de stock asociada a una orden.
   *
   * @param orderId ID de la orden
   * @return Mono con la reserva o vacío si no existe
   */
  Mono<StockReservation> findReservationByOrderId(String orderId);

}
