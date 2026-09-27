package tacos.versioning;

import java.io.IOException;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtro HTTP que detecta el uso de prefijos de API legados (/api/...)
 * y añade los encabezados estandarizados de deprecación RFC 8594 (Sunset) y Link
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE + 10)
public class ApiDeprecationFilter extends OncePerRequestFilter {

  public static final String HEADER_DEPRECATION = "Deprecation";
  public static final String HEADER_SUNSET = "Sunset";
  public static final String HEADER_LINK = "Link";
  public static final String HEADER_WARNING = "Warning";

  public static final String DEFAULT_SUNSET_DATE = "Sun, 31 Dec 2028 23:59:59 GMT";

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String uri = request.getRequestURI();

    if (isLegacyApiPath(uri)) {
      String v1Path = toV1Path(uri);
      response.setHeader(HEADER_DEPRECATION, "true");
      response.setHeader(HEADER_SUNSET, DEFAULT_SUNSET_DATE);
      response.setHeader(HEADER_LINK, "<" + v1Path + ">; rel=\"successor-version\"");
      response.setHeader(HEADER_WARNING, "299 - \"The /api legacy prefix is deprecated. Please migrate to " + v1Path + "\"");
    }

    filterChain.doFilter(request, response);
  }

  private boolean isLegacyApiPath(String uri) {
    if (uri == null) {
      return false;
    }
    // Aplica a rutas que inician con /api/ pero que NO sean /api/v1/ ni documentación OpenAPI
    return uri.startsWith("/api/")
        && !uri.startsWith("/api/v1/")
        && !uri.startsWith("/api/docs")
        && !uri.startsWith("/api/v1/api-docs")
        && !uri.equals("/api/v1")
        && !uri.equals("/openapi.yaml");
  }

  private String toV1Path(String uri) {
    return "/api/v1" + uri.substring(4);
  }

}
