package tacos.kitchen.exception;

// Este tipo de error (intermitencia de red o timeout) SI debe ser reintentado. 
// Backoff exponencial hasta alcanzar el límite configurado.

public class TransientProcessingException extends RuntimeException {

  public TransientProcessingException(String message) {
    super(message);
  }

  public TransientProcessingException(String message, Throwable cause) {
    super(message, cause);
  }

}
