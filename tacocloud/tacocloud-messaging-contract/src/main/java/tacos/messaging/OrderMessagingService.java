package tacos.messaging;

/**
 * Puerto unificado de salida para publicación de eventos de orden.
 * Desacoplado de frameworks de persistencia y brokers específicos.
 */
public interface OrderMessagingService {

  /**
   * Publica un evento de orden en el canal de mensajería configurado.
   *
   * @param event el evento canónico a publicar
   */
  void sendOrder(OrderEvent event);

}
