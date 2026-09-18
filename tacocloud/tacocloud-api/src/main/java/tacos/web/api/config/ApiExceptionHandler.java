package tacos.web.api.config;

import java.net.URI;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.web.server.ServerWebExchange;
import org.springframework.web.server.ServerWebInputException;

import tacos.web.api.dto.ApiProblem;
import tacos.web.api.dto.ApiProblem.Violation;

/**
  Manejador global de excepciones para el entorno reactivo WebFlux.
  Garantiza respuestas de error uniformes compatibles con RFC 9457.
 */
@RestControllerAdvice
@Order(-2)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.REACTIVE)
public class ApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(ApiExceptionHandler.class);
  public static final MediaType PROBLEM_JSON_MEDIA_TYPE = MediaType.parseMediaType("application/problem+json");

  @ExceptionHandler(WebExchangeBindException.class)
  public ResponseEntity<ApiProblem> handleValidationException(WebExchangeBindException ex, ServerWebExchange exchange) {
    String instance = getPath(exchange);
    List<Violation> violations = ex.getFieldErrors().stream()
        .map(error -> new Violation(
            error.getField(),
            error.getDefaultMessage(),
            maskSensitiveValue(error.getField(), error.getRejectedValue())
        ))
        .collect(Collectors.toList());

    ApiProblem problem = ApiProblem.of(
        HttpStatus.BAD_REQUEST,
        "VALIDATION_ERROR",
        "Validation Error",
        "Request validation failed for one or more fields.",
        instance,
        violations
    );
    problem.setType(URI.create("urn:problem-type:validation-error"));

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(PROBLEM_JSON_MEDIA_TYPE)
        .body(problem);
  }

  @ExceptionHandler(BusinessRuleException.class)
  public ResponseEntity<ApiProblem> handleBusinessRuleException(BusinessRuleException ex, ServerWebExchange exchange) {
    String instance = getPath(exchange);
    ApiProblem problem = ApiProblem.of(
        HttpStatus.UNPROCESSABLE_ENTITY,
        ex.getCode(),
        "Business Rule Violation",
        ex.getMessage(),
        instance
    );
    problem.setType(URI.create("urn:problem-type:business-rule-violation"));

    return ResponseEntity.status(HttpStatus.UNPROCESSABLE_ENTITY)
        .contentType(PROBLEM_JSON_MEDIA_TYPE)
        .body(problem);
  }

  @ExceptionHandler(ResourceConflictException.class)
  public ResponseEntity<ApiProblem> handleResourceConflictException(ResourceConflictException ex, ServerWebExchange exchange) {
    String instance = getPath(exchange);
    ApiProblem problem = ApiProblem.of(
        HttpStatus.CONFLICT,
        ex.getCode(),
        "Resource Conflict",
        ex.getMessage(),
        instance
    );
    problem.setType(URI.create("urn:problem-type:resource-conflict"));

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(PROBLEM_JSON_MEDIA_TYPE)
        .body(problem);
  }

  @ExceptionHandler(ResponseStatusException.class)
  public ResponseEntity<ApiProblem> handleResponseStatusException(ResponseStatusException ex, ServerWebExchange exchange) {
    HttpStatus status = ex.getStatus();
    String instance = getPath(exchange);
    String code = resolveCodeForStatus(status);
    String title = status.getReasonPhrase();
    String detail = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();

    ApiProblem problem = ApiProblem.of(status, code, title, detail, instance);

    return ResponseEntity.status(status)
        .contentType(PROBLEM_JSON_MEDIA_TYPE)
        .body(problem);
  }

  @ExceptionHandler({ServerWebInputException.class, IllegalArgumentException.class})
  public ResponseEntity<ApiProblem> handleBadRequestException(Exception ex, ServerWebExchange exchange) {
    String instance = getPath(exchange);
    ApiProblem problem = ApiProblem.of(
        HttpStatus.BAD_REQUEST,
        "BAD_REQUEST",
        "Bad Request",
        "Malformed request syntax or invalid parameters.",
        instance
    );

    return ResponseEntity.status(HttpStatus.BAD_REQUEST)
        .contentType(PROBLEM_JSON_MEDIA_TYPE)
        .body(problem);
  }

  @ExceptionHandler(Throwable.class)
  public ResponseEntity<ApiProblem> handleGeneralThrowable(Throwable ex, ServerWebExchange exchange) {
    log.error("Unhandled internal server error at {}: {}", getPath(exchange), ex.getMessage(), ex);

    String instance = getPath(exchange);
    ApiProblem problem = ApiProblem.of(
        HttpStatus.INTERNAL_SERVER_ERROR,
        "INTERNAL_SERVER_ERROR",
        "Internal Server Error",
        "An unexpected error occurred while processing the request.",
        instance
    );

    return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
        .contentType(PROBLEM_JSON_MEDIA_TYPE)
        .body(problem);
  }

  private String getPath(ServerWebExchange exchange) {
    if (exchange != null && exchange.getRequest() != null && exchange.getRequest().getPath() != null) {
      return exchange.getRequest().getPath().value();
    }
    return "/";
  }

  private String resolveCodeForStatus(HttpStatus status) {
    switch (status) {
      case NOT_FOUND:
        return "RESOURCE_NOT_FOUND";
      case FORBIDDEN:
        return "ACCESS_DENIED";
      case BAD_REQUEST:
        return "BAD_REQUEST";
      case CONFLICT:
        return "RESOURCE_CONFLICT";
      case UNPROCESSABLE_ENTITY:
        return "UNPROCESSABLE_ENTITY";
      default:
        return "ERROR_" + status.value();
    }
  }

  private Object maskSensitiveValue(String field, Object value) {
    if (field == null) {
      return null;
    }
    String lower = field.toLowerCase();
    if (lower.contains("password") || lower.contains("ccnumber") || lower.contains("cccvv") || lower.contains("cvv")) {
      return "****";
    }
    return value;
  }
}
