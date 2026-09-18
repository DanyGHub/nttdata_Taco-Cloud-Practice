package tacos.web.api.config;

/**
 * Excepción de dominio para conflictos de estado o recursos duplicados.
 * Estado HTTP 409.
 */
public class ResourceConflictException extends RuntimeException {

  private final String code;

  public ResourceConflictException(String code, String message) {
    super(message);
    this.code = code != null ? code : "RESOURCE_CONFLICT";
  }

  public ResourceConflictException(String message) {
    this("RESOURCE_CONFLICT", message);
  }

  public String getCode() {
    return code;
  }
}
