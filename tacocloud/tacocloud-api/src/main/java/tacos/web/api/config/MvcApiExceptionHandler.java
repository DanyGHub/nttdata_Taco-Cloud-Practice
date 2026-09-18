package tacos.web.api.config;

import java.net.URI;
import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletRequest;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnWebApplication;
import org.springframework.core.annotation.Order;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.validation.BindException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.server.ResponseStatusException;

import tacos.web.api.dto.ApiProblem;
import tacos.web.api.dto.ApiProblem.Violation;

/**
  Manejador global de excepciones para el entorno Servlet / Spring MVC (Tomcat).
  Garantiza que en ejecución real en contenedor Servlet las respuestas sigan el estándar RFC 9457.
 */
@RestControllerAdvice
@Order(-2)
@ConditionalOnWebApplication(type = ConditionalOnWebApplication.Type.SERVLET)
public class MvcApiExceptionHandler {

  private static final Logger log = LoggerFactory.getLogger(MvcApiExceptionHandler.class);
  public static final MediaType PROBLEM_JSON_MEDIA_TYPE = MediaType.parseMediaType("application/problem+json");

  @ExceptionHandler({MethodArgumentNotValidException.class, BindException.class})
  public ResponseEntity<ApiProblem> handleValidationException(Exception ex, HttpServletRequest request) {
    String instance = request != null ? request.getRequestURI() : "/";
    List<Violation> violations;

    if (ex instanceof MethodArgumentNotValidException) {
      violations = ((MethodArgumentNotValidException) ex).getBindingResult().getFieldErrors().stream()
          .map(error -> new Violation(
              error.getField(),
              error.getDefaultMessage(),
              maskSensitiveValue(error.getField(), error.getRejectedValue())
          ))
          .collect(Collectors.toList());
    } else if (ex instanceof BindException) {
      violations = ((BindException) ex).getFieldErrors().stream()
          .map(error -> new Violation(
              error.getField(),
              error.getDefaultMessage(),
              maskSensitiveValue(error.getField(), error.getRejectedValue())
          ))
          .collect(Collectors.toList());
    } else {
      violations = Collections.emptyList();
    }

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
  public ResponseEntity<ApiProblem> handleBusinessRuleException(BusinessRuleException ex, HttpServletRequest request) {
    String instance = request != null ? request.getRequestURI() : "/";
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
  public ResponseEntity<ApiProblem> handleResourceConflictException(ResourceConflictException ex, HttpServletRequest request) {
    String instance = request != null ? request.getRequestURI() : "/";
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
  public ResponseEntity<ApiProblem> handleResponseStatusException(ResponseStatusException ex, HttpServletRequest request) {
    HttpStatus status = ex.getStatus();
    String instance = request != null ? request.getRequestURI() : "/";
    String code = resolveCodeForStatus(status);
    String title = status.getReasonPhrase();
    String detail = ex.getReason() != null ? ex.getReason() : status.getReasonPhrase();

    ApiProblem problem = ApiProblem.of(status, code, title, detail, instance);

    return ResponseEntity.status(status)
        .contentType(PROBLEM_JSON_MEDIA_TYPE)
        .body(problem);
  }

  @ExceptionHandler(HttpMessageNotReadableException.class)
  public ResponseEntity<ApiProblem> handleBadRequestException(HttpMessageNotReadableException ex, HttpServletRequest request) {
    String instance = request != null ? request.getRequestURI() : "/";
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

  @ExceptionHandler({DuplicateKeyException.class, DataIntegrityViolationException.class})
  public ResponseEntity<ApiProblem> handleDuplicateKeyException(Exception ex, HttpServletRequest request) {
    String instance = request != null ? request.getRequestURI() : "/";
    log.warn("Database unique constraint violation at {}: {}", instance, ex.getMessage());
    ApiProblem problem = ApiProblem.of(
        HttpStatus.CONFLICT,
        "RESOURCE_CONFLICT",
        "Resource Conflict",
        "A resource with the specified unique attributes already exists.",
        instance
    );
    problem.setType(URI.create("urn:problem-type:resource-conflict"));

    return ResponseEntity.status(HttpStatus.CONFLICT)
        .contentType(PROBLEM_JSON_MEDIA_TYPE)
        .body(problem);
  }

  @ExceptionHandler(Throwable.class)
  public ResponseEntity<ApiProblem> handleGeneralThrowable(Throwable ex, HttpServletRequest request) {
    // Desenvolver excepciones envueltas (ej. NestedServletException, CompletionException, ExecutionException)
    Throwable current = ex;
    while (current != null) {
      if (current instanceof ResponseStatusException) {
        return handleResponseStatusException((ResponseStatusException) current, request);
      }
      if (current instanceof ResourceConflictException) {
        return handleResourceConflictException((ResourceConflictException) current, request);
      }
      if (current instanceof BusinessRuleException) {
        return handleBusinessRuleException((BusinessRuleException) current, request);
      }
      if (current instanceof MethodArgumentNotValidException) {
        return handleValidationException((MethodArgumentNotValidException) current, request);
      }
      if (current instanceof BindException) {
        return handleValidationException((BindException) current, request);
      }
      if (current instanceof DuplicateKeyException || current instanceof DataIntegrityViolationException) {
        return handleDuplicateKeyException((Exception) current, request);
      }
      if (current.getMessage() != null && (current.getMessage().contains("duplicate key") || current.getMessage().contains("E11000"))) {
        return handleDuplicateKeyException(new RuntimeException(current.getMessage(), current), request);
      }
      current = current.getCause();
    }

    String instance = request != null ? request.getRequestURI() : "/";
    log.error("Unhandled internal server error at {}: {}", instance, ex.getMessage(), ex);

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
