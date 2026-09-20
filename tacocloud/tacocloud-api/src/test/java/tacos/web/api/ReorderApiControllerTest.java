package tacos.web.api;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.web.reactive.server.WebTestClient;

import reactor.core.publisher.Mono;
import tacos.data.OrderRepository;
import tacos.order.OrderApplicationService;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.ReorderRequest;
import tacos.web.api.dto.ReorderResponse;

public class ReorderApiControllerTest {

  private OrderApplicationService appService;
  private OrderRepository orderRepo;
  private OrderApiController controller;
  private WebTestClient client;

  @BeforeEach
  public void setUp() {
    appService = mock(OrderApplicationService.class);
    orderRepo = mock(OrderRepository.class);

    controller = new OrderApiController(
        orderRepo,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        null,
        appService
    );

    client = WebTestClient.bindToController(controller).build();
  }

  @Test
  @DisplayName("POST /api/orders/{id}/reorder: 201 CREATED cuando la reordenación es exitosa")
  public void testReorderEndpoint_Success() {
    OrderResponse ordResp = new OrderResponse();
    ordResp.setId("new-order-99");
    ordResp.setTotal(new BigDecimal("15.00"));

    ReorderResponse responseDto = ReorderResponse.builder()
        .status("CREATED")
        .message("Order successfully reordered.")
        .oldTotal(new BigDecimal("15.00"))
        .newTotal(new BigDecimal("15.00"))
        .priceDifference(BigDecimal.ZERO)
        .priceChanged(false)
        .itemDifferences(Collections.emptyList())
        .order(ordResp)
        .build();

    when(appService.reorder(eq("orig-123"), any(ReorderRequest.class), any(), any()))
        .thenReturn(Mono.just(ResponseEntity.status(HttpStatus.CREATED).body(responseDto)));

    client.post()
        .uri("/api/orders/orig-123/reorder")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"paymentMethodId\": \"pm-1\"}")
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.status").isEqualTo("CREATED")
        .jsonPath("$.priceChanged").isEqualTo(false)
        .jsonPath("$.order.id").isEqualTo("new-order-99")
        .jsonPath("$.order.total").isEqualTo(15.00);
  }

  @Test
  @DisplayName("POST /api/orders/{id}/reorder: 409 CONFLICT cuando hay cambio de precio pendiente de confirmación")
  public void testReorderEndpoint_PriceChangeConflict() {
    ReorderResponse conflictDto = ReorderResponse.builder()
        .status("PRICE_CHANGE_REQUIRED")
        .message("Order price changed from $10.00 to $12.50. Review differences.")
        .oldTotal(new BigDecimal("10.00"))
        .newTotal(new BigDecimal("12.50"))
        .priceDifference(new BigDecimal("2.50"))
        .priceChanged(true)
        .itemDifferences(Collections.emptyList())
        .build();

    when(appService.reorder(eq("orig-123"), any(ReorderRequest.class), any(), any()))
        .thenReturn(Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(conflictDto)));

    client.post()
        .uri("/api/orders/orig-123/reorder")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"paymentMethodId\": \"pm-1\", \"confirmPriceChange\": false}")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT)
        .expectBody()
        .jsonPath("$.status").isEqualTo("PRICE_CHANGE_REQUIRED")
        .jsonPath("$.priceChanged").isEqualTo(true)
        .jsonPath("$.oldTotal").isEqualTo(10.00)
        .jsonPath("$.newTotal").isEqualTo(12.50)
        .jsonPath("$.priceDifference").isEqualTo(2.50);
  }

  @Test
  @DisplayName("POST /api/orders/{id}/reorder: Header Idempotency-Key es propagado correctamente")
  public void testReorderEndpoint_WithIdempotencyHeader() {
    OrderResponse ordResp = new OrderResponse();
    ordResp.setId("existing-order-42");
    ordResp.setTotal(new BigDecimal("10.00"));

    ReorderResponse responseDto = ReorderResponse.builder()
        .status("CREATED")
        .message("Order previously reordered (idempotent)")
        .oldTotal(new BigDecimal("10.00"))
        .newTotal(new BigDecimal("10.00"))
        .priceDifference(BigDecimal.ZERO)
        .priceChanged(false)
        .order(ordResp)
        .build();

    when(appService.reorder(eq("orig-123"), any(), any(), eq("idem-header-999")))
        .thenReturn(Mono.just(ResponseEntity.ok(responseDto)));

    client.post()
        .uri("/api/orders/orig-123/reorder")
        .header("Idempotency-Key", "idem-header-999")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"paymentMethodId\": \"pm-1\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.status").isEqualTo("CREATED")
        .jsonPath("$.message").isEqualTo("Order previously reordered (idempotent)")
        .jsonPath("$.order.id").isEqualTo("existing-order-42");
  }

}
