package tacos.web.api.config;

/**
  Excepción de dominio para violaciones de reglas o invariantes de negocio.
  Estado HTTP 422.
 */
public class BusinessRuleException extends RuntimeException {

  private final String code;

  public BusinessRuleException(String code, String message) {
    super(message);
    this.code = code != null ? code : "BUSINESS_RULE_VIOLATION";
  }

  public BusinessRuleException(String message) {
    this("BUSINESS_RULE_VIOLATION", message);
  }

  public String getCode() {
    return code;
  }
}
