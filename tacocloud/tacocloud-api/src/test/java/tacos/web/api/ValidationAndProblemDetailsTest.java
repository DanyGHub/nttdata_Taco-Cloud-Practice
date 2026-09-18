package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.config.ApiExceptionHandler;
import tacos.web.api.config.BusinessRuleException;
import tacos.web.api.config.ResourceConflictException;
import tacos.web.api.dto.OrderMapper;

public class ValidationAndProblemDetailsTest {

  private OrderRepository repo;
  private OrderMessagingService messagingService;
  private EmailOrderService emailService;
  private OrderMapper orderMapper;
  private OrderApiController orderApiController;
  private WebTestClient webClient;

  // Test controller
  @RestController
  @RequestMapping("/api/test-errors")
  static class ErrorSimulationController {

    @GetMapping("/business-rule")
    public Mono<String> triggerBusinessRule() {
      return Mono.error(new BusinessRuleException("TACO_LIMIT_EXCEEDED", "Cannot order more than 50 tacos per order"));
    }

    @GetMapping("/conflict")
    public Mono<String> triggerConflict() {
      return Mono.error(new ResourceConflictException("ORDER_ALREADY_PROCESSED", "The order has already been processed"));
    }

    @GetMapping("/not-found")
    public Mono<String> triggerNotFound() {
      return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Resource with ID 999 was not found"));
    }

    @GetMapping("/driver-failure")
    public Mono<String> triggerDriverFailure() {
      return Mono.error(new RuntimeException("com.mongodb.MongoTimeoutException: Timed out after 30000 ms while waiting for server"));
    }
  }

  @BeforeEach
  public void setUp() {
    repo = mock(OrderRepository.class);
    messagingService = mock(OrderMessagingService.class);
    emailService = mock(EmailOrderService.class);
    orderMapper = new OrderMapper();
    orderApiController = new OrderApiController(repo, messagingService, emailService, orderMapper);

    webClient = WebTestClient.bindToController(orderApiController, new ErrorSimulationController())
        .controllerAdvice(new ApiExceptionHandler())
        .build();
  }

  @Test
  @DisplayName("POST /api/orders with multiple invalid fields, should return problem details 400")
  public void postOrder_withMultipleInvalidFields_shouldReturnProblemDetails400() {
    String invalidPayload = "{"
        + "\"deliveryName\":\"\","
        + "\"deliveryStreet\":\"\","
        + "\"deliveryCity\":\"Springfield\","
        + "\"deliveryState\":\"IL\","
        + "\"deliveryZip\":\"62701\","
        + "\"ccNumber\":\"123456\","
        + "\"ccExpiration\":\"99/99\","
        + "\"ccCVV\":\"99\","
        + "\"tacos\":[]"
        + "}";

    webClient.post()
        .uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(invalidPayload)
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.type").isEqualTo("urn:problem-type:validation-error")
        .jsonPath("$.title").isEqualTo("Validation Error")
        .jsonPath("$.status").isEqualTo(400)
        .jsonPath("$.code").isEqualTo("VALIDATION_ERROR")
        .jsonPath("$.instance").isEqualTo("/api/orders")
        .jsonPath("$.timestamp").isNotEmpty()
        .jsonPath("$.violations").isArray()
        .jsonPath("$.violations[?(@.field == 'deliveryName')]").exists()
        .jsonPath("$.violations[?(@.field == 'deliveryStreet')]").exists()
        .jsonPath("$.violations[?(@.field == 'ccNumber')]").exists()
        .jsonPath("$.violations[?(@.field == 'ccExpiration')]").exists()
        .jsonPath("$.violations[?(@.field == 'ccCVV')]").exists()
        .jsonPath("$.violations[?(@.field == 'tacos')]").exists();

    verify(repo, never()).save(any());
    verify(messagingService, never()).sendOrder(any());
  }

  @Test
  @DisplayName("sensitive field values must be masked in Violations")
  public void postOrder_sensitiveFieldValuesMustBeMaskedInViolations() {
    String invalidPayload = "{"
        + "\"deliveryName\":\"Jane Doe\","
        + "\"deliveryStreet\":\"Main St\","
        + "\"deliveryCity\":\"Springfield\","
        + "\"deliveryState\":\"IL\","
        + "\"deliveryZip\":\"62701\","
        + "\"ccNumber\":\"1234\","
        + "\"ccExpiration\":\"05/30\","
        + "\"ccCVV\":\"9\","
        + "\"tacos\":[]"
        + "}";

    webClient.post()
        .uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(invalidPayload)
        .exchange()
        .expectStatus().isBadRequest()
        .expectBody()
        .jsonPath("$.violations[?(@.field == 'ccNumber')].rejectedValue").isEqualTo("****")
        .jsonPath("$.violations[?(@.field == 'ccCVV')].rejectedValue").isEqualTo("****");
  }

  @Test
  @DisplayName("business rule Exception, should return problem details 422")
  public void businessRuleException_shouldReturnProblemDetails422() {
    webClient.get()
        .uri("/api/test-errors/business-rule")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY)
        .expectHeader().contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.type").isEqualTo("urn:problem-type:business-rule-violation")
        .jsonPath("$.title").isEqualTo("Business Rule Violation")
        .jsonPath("$.status").isEqualTo(422)
        .jsonPath("$.code").isEqualTo("TACO_LIMIT_EXCEEDED")
        .jsonPath("$.detail").isEqualTo("Cannot order more than 50 tacos per order")
        .jsonPath("$.instance").isEqualTo("/api/test-errors/business-rule");
  }

  @Test
  @DisplayName("conflict exception, should return problem details 409")
  public void conflictException_shouldReturnProblemDetails409() {
    webClient.get()
        .uri("/api/test-errors/conflict")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT)
        .expectHeader().contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.type").isEqualTo("urn:problem-type:resource-conflict")
        .jsonPath("$.title").isEqualTo("Resource Conflict")
        .jsonPath("$.status").isEqualTo(409)
        .jsonPath("$.code").isEqualTo("ORDER_ALREADY_PROCESSED")
        .jsonPath("$.detail").isEqualTo("The order has already been processed")
        .jsonPath("$.instance").isEqualTo("/api/test-errors/conflict");
  }

  @Test
  @DisplayName("not Found Exception, should Return Problem Details 404")
  public void notFoundException_shouldReturnProblemDetails404() {
    webClient.get()
        .uri("/api/test-errors/not-found")
        .exchange()
        .expectStatus().isNotFound()
        .expectHeader().contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.status").isEqualTo(404)
        .jsonPath("$.code").isEqualTo("RESOURCE_NOT_FOUND")
        .jsonPath("$.title").isEqualTo("Not Found")
        .jsonPath("$.detail").isEqualTo("Resource with ID 999 was not found")
        .jsonPath("$.instance").isEqualTo("/api/test-errors/not-found");
  }

  @Test
  @DisplayName("unhandled Exception, should Return 500 Without Driver Leaks Or StackTrace")
  public void unhandledException_shouldReturn500WithoutDriverLeaksOrStackTrace() {
    webClient.get()
        .uri("/api/test-errors/driver-failure")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR)
        .expectHeader().contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.status").isEqualTo(500)
        .jsonPath("$.code").isEqualTo("INTERNAL_SERVER_ERROR")
        .jsonPath("$.title").isEqualTo("Internal Server Error")
        .jsonPath("$.detail").isEqualTo("An unexpected error occurred while processing the request.")
        .jsonPath("$.instance").isEqualTo("/api/test-errors/driver-failure")
        .jsonPath("$.detail").value(val -> {
          String s = (String) val;
          org.junit.jupiter.api.Assertions.assertFalse(s.contains("Mongo"));
          org.junit.jupiter.api.Assertions.assertFalse(s.contains("Exception"));
        })
        .jsonPath("$.stackTrace").doesNotExist()
        .jsonPath("$.cause").doesNotExist();
  }

  @Test
  @DisplayName("malformed Json, should Return 400 BadRequest")
  public void malformedJson_shouldReturn400BadRequest() {
    webClient.post()
        .uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{malformed json: true,")
        .exchange()
        .expectStatus().isBadRequest()
        .expectHeader().contentType("application/problem+json")
        .expectBody()
        .jsonPath("$.status").isEqualTo(400)
        .jsonPath("$.code").isEqualTo("BAD_REQUEST")
        .jsonPath("$.title").isEqualTo("Bad Request")
        .jsonPath("$.instance").isEqualTo("/api/orders");
  }
}
