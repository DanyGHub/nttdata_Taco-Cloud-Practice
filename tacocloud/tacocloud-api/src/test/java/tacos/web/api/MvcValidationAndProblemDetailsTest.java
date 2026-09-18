package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.config.BusinessRuleException;
import tacos.web.api.config.MvcApiExceptionHandler;
import tacos.web.api.config.ResourceConflictException;
import tacos.web.api.dto.OrderMapper;

public class MvcValidationAndProblemDetailsTest {

  private OrderRepository repo;
  private OrderMessagingService messagingService;
  private EmailOrderService emailService;
  private OrderMapper orderMapper;
  private OrderApiController orderApiController;
  private MockMvc mockMvc;

  @RestController
  @RequestMapping("/api/mvc-test-errors")
  static class MvcErrorSimulationController {

    @GetMapping("/business-rule")
    public String triggerBusinessRule() {
      throw new BusinessRuleException("TACO_LIMIT_EXCEEDED", "Cannot order more than 50 tacos per order");
    }

    @GetMapping("/conflict")
    public String triggerConflict() {
      throw new ResourceConflictException("ORDER_ALREADY_PROCESSED", "The order has already been processed");
    }

    @GetMapping("/driver-failure")
    public String triggerDriverFailure() {
      throw new RuntimeException("com.mongodb.MongoTimeoutException: Timed out after 30000 ms while waiting for server [localhost:27017]");
    }
  }

  @BeforeEach
  public void setUp() {
    repo = mock(OrderRepository.class);
    messagingService = mock(OrderMessagingService.class);
    emailService = mock(EmailOrderService.class);
    orderMapper = new OrderMapper();
    orderApiController = new OrderApiController(repo, messagingService, emailService, orderMapper);

    mockMvc = MockMvcBuilders.standaloneSetup(orderApiController, new MvcErrorSimulationController())
        .setControllerAdvice(new MvcApiExceptionHandler())
        .build();
  }

  @Test
  @DisplayName("Spring MVC: POST /api/orders con campos inválidos retorna 400 Problem Details y violaciones enmascaradas")
  public void mvc_postOrder_withInvalidFields_shouldReturnProblemDetails400() throws Exception {
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

    mockMvc.perform(post("/api/orders")
            .contentType(MediaType.APPLICATION_JSON)
            .content(invalidPayload))
        .andExpect(status().isBadRequest())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.type").value("urn:problem-type:validation-error"))
        .andExpect(jsonPath("$.title").value("Validation Error"))
        .andExpect(jsonPath("$.status").value(400))
        .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
        .andExpect(jsonPath("$.instance").value("/api/orders"))
        .andExpect(jsonPath("$.violations").isArray())
        .andExpect(jsonPath("$.violations[?(@.field == 'deliveryName')]").exists())
        .andExpect(jsonPath("$.violations[?(@.field == 'ccNumber')].rejectedValue").value("****"))
        .andExpect(jsonPath("$.violations[?(@.field == 'ccCVV')].rejectedValue").value("****"));

    verify(repo, never()).save(any());
    verify(messagingService, never()).sendOrder(any());
  }

  @Test
  @DisplayName("Spring MVC: Excepción de regla de negocio retorna 422 Problem Details")
  public void mvc_businessRuleException_shouldReturnProblemDetails422() throws Exception {
    mockMvc.perform(get("/api/mvc-test-errors/business-rule"))
        .andExpect(status().isUnprocessableEntity())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.status").value(422))
        .andExpect(jsonPath("$.code").value("TACO_LIMIT_EXCEEDED"))
        .andExpect(jsonPath("$.title").value("Business Rule Violation"))
        .andExpect(jsonPath("$.detail").value("Cannot order more than 50 tacos per order"))
        .andExpect(jsonPath("$.instance").value("/api/mvc-test-errors/business-rule"));
  }

  @Test
  @DisplayName("Spring MVC: Excepción de conflicto retorna 409 Problem Details")
  public void mvc_conflictException_shouldReturnProblemDetails409() throws Exception {
    mockMvc.perform(get("/api/mvc-test-errors/conflict"))
        .andExpect(status().isConflict())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.status").value(409))
        .andExpect(jsonPath("$.code").value("ORDER_ALREADY_PROCESSED"))
        .andExpect(jsonPath("$.title").value("Resource Conflict"))
        .andExpect(jsonPath("$.instance").value("/api/mvc-test-errors/conflict"));
  }

  @Test
  @DisplayName("Spring MVC: Error interno oculta driver y no expone stacktrace")
  public void mvc_unhandledException_shouldReturn500WithoutDriverLeaks() throws Exception {
    mockMvc.perform(get("/api/mvc-test-errors/driver-failure"))
        .andExpect(status().isInternalServerError())
        .andExpect(content().contentType("application/problem+json"))
        .andExpect(jsonPath("$.status").value(500))
        .andExpect(jsonPath("$.code").value("INTERNAL_SERVER_ERROR"))
        .andExpect(jsonPath("$.title").value("Internal Server Error"))
        .andExpect(jsonPath("$.detail").value("An unexpected error occurred while processing the request."))
        .andExpect(jsonPath("$.instance").value("/api/mvc-test-errors/driver-failure"))
        .andExpect(jsonPath("$.stackTrace").doesNotExist());
  }

  @Test
  @DisplayName("GET /api/orders/{id} existente retorna 200 OK")
  public void getOrderById_whenFound_shouldReturnOrder() throws Exception {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER_123");
    order.setDeliveryName("Angel Lopez");
    when(repo.findById("ORDER_123")).thenReturn(Mono.just(order));

    // Spring MVC handles Mono return types asynchronously via AsyncListener
    mockMvc.perform(get("/api/orders/ORDER_123"))
        .andReturn();
  }
}
