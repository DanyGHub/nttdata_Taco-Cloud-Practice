package tacos.web.api.config;

import java.util.LinkedHashMap;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.boot.web.error.ErrorAttributeOptions;
import org.springframework.boot.web.servlet.error.DefaultErrorAttributes;
import org.springframework.stereotype.Component;
import org.springframework.web.context.request.WebRequest;

/**
  Adaptador de atributos de error para BasicErrorController en Spring Boot Servlet (Tomcat).
  Garantiza que incluso los errores a nivel de filtro (404, 401, 403) compartan la misma estructura uniforme (RFC 9457).
 */
@Component
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class ProblemDetailsErrorAttributes extends DefaultErrorAttributes {

  @Override
  public Map<String, Object> getErrorAttributes(WebRequest webRequest, ErrorAttributeOptions options) {
    Map<String, Object> defaultAttrs = super.getErrorAttributes(webRequest, options);

    Map<String, Object> problemAttrs = new LinkedHashMap<>();
    Integer status = (Integer) defaultAttrs.get("status");
    String error = (String) defaultAttrs.get("error");
    String message = (String) defaultAttrs.get("message");
    String path = (String) defaultAttrs.get("path");

    problemAttrs.put("type", "about:blank");
    problemAttrs.put("title", error != null ? error : "Error");
    problemAttrs.put("status", status != null ? status : 500);
    problemAttrs.put("detail", message != null && !message.isEmpty() && !"No message available".equals(message) ? message : error);
    problemAttrs.put("instance", path != null ? path : "/");
    problemAttrs.put("code", resolveCode(status));
    problemAttrs.put("timestamp", defaultAttrs.get("timestamp"));

    return problemAttrs;
  }

  private String resolveCode(Integer status) {
    if (status == null) return "INTERNAL_SERVER_ERROR";
    switch (status) {
      case 400: return "BAD_REQUEST";
      case 401: return "UNAUTHORIZED";
      case 403: return "ACCESS_DENIED";
      case 404: return "RESOURCE_NOT_FOUND";
      case 409: return "RESOURCE_CONFLICT";
      case 422: return "UNPROCESSABLE_ENTITY";
      default: return status >= 500 ? "INTERNAL_SERVER_ERROR" : "ERROR_" + status;
    }
  }
}
