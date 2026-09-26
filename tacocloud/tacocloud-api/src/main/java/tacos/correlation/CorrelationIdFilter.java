package tacos.correlation;

import java.io.IOException;
import java.util.UUID;

import javax.servlet.FilterChain;
import javax.servlet.ServletException;
import javax.servlet.http.HttpServletRequest;
import javax.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Filtro HTTP que extrae, valida o genera el Correlation ID en cada petición.
 * Lo propaga en los headers de respuesta, lo inyecta en MDC/ThreadLocal y garantiza su limpieza.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

  @Override
  protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
      throws ServletException, IOException {

    String incomingHeader = request.getHeader(CorrelationContext.HEADER_NAME);
    String correlationId;

    if (CorrelationIdValidator.isValid(incomingHeader)) {
      correlationId = incomingHeader.trim();
    } else {
      correlationId = UUID.randomUUID().toString();
    }

    CorrelationContext.set(correlationId);
    response.setHeader(CorrelationContext.HEADER_NAME, correlationId);

    try {
      filterChain.doFilter(request, response);
    } finally {
      CorrelationContext.clear();
    }
  }

}
