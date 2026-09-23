package tacos.kitchen.exception;

// Este tipo de error (evento desconocido o datos corruptos) NO debe ser reintentado. 
// Deben ser enrutados directamente a la Dead Letter Queue (DLQ).

public class PermanentProcessingException extends RuntimeException {

  public PermanentProcessingException(String message) {
    super(message);
  }

  public PermanentProcessingException(String message, Throwable cause) {
    super(message, cause);
  }

}
