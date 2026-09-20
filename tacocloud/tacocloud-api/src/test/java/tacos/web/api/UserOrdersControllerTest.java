package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.User;
import tacos.data.UserRepository;
import tacos.order.OrderHistoryService;
import tacos.search.TacoPage;
import tacos.web.api.dto.OrderDetailResponse;
import tacos.web.api.dto.OrderSummaryResponse;

@DisplayName("TC-23: Pruebas WebFlux de UserOrdersController")
public class UserOrdersControllerTest {

  private OrderHistoryService historyService;
  private UserRepository userRepo;
  private UserOrdersController controller;

  @BeforeEach
  public void setUp() {
    historyService = mock(OrderHistoryService.class);
    userRepo = mock(UserRepository.class);

    controller = new UserOrdersController(historyService, userRepo);
  }

  @Test
  @DisplayName("GET /api/users/me/orders lista órdenes del usuario autenticado")
  public void shouldGetMyOrdersForAuthenticatedUser() {
    User authUser = new User();
    authUser.setId("user-123");
    authUser.setUsername("testuser");

    OrderSummaryResponse summary = OrderSummaryResponse.builder()
        .id("order-1")
        .placedAt(new Date())
        .deliveryName("Test User")
        .total(new BigDecimal("18.00"))
        .build();

    TacoPage<OrderSummaryResponse> page = TacoPage.of(Collections.singletonList(summary), 0, 20, 1L);

    when(historyService.getUserOrders("user-123", "testuser", 0, 20))
        .thenReturn(Mono.just(page));

    StepVerifier.create(controller.getMyOrders(0, 20, authUser, null, null))
        .assertNext(res -> {
          assertThat(res.getTotalElements()).isEqualTo(1L);
          assertThat(res.getContent().get(0).getId()).isEqualTo("order-1");
        })
        .verifyComplete();

    verify(historyService).getUserOrders("user-123", "testuser", 0, 20);
  }

  @Test
  @DisplayName("GET /api/users/me/orders/{id} obtiene el detalle de la orden del usuario")
  public void shouldGetMyOrderDetailForAuthenticatedUser() {
    User authUser = new User();
    authUser.setId("user-123");
    authUser.setUsername("testuser");

    OrderDetailResponse detail = OrderDetailResponse.builder()
        .id("order-1")
        .deliveryName("Test User")
        .total(new BigDecimal("18.00"))
        .brand("Visa")
        .last4("4242")
        .build();

    when(historyService.getUserOrderDetail("user-123", "testuser", "order-1"))
        .thenReturn(Mono.just(detail));

    StepVerifier.create(controller.getMyOrderDetail("order-1", authUser, null, null))
        .assertNext(res -> {
          assertThat(res.getId()).isEqualTo("order-1");
          assertThat(res.getDeliveryName()).isEqualTo("Test User");
          assertThat(res.getBrand()).isEqualTo("Visa");
        })
        .verifyComplete();

    verify(historyService).getUserOrderDetail("user-123", "testuser", "order-1");
  }

  @Test
  @DisplayName("Rechaza peticiones no autenticadas con 401 UNAUTHORIZED")
  public void shouldRejectUnauthenticatedRequestForOrders() {
    StepVerifier.create(controller.getMyOrders(0, 20, null, null, null))
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(ResponseStatusException.class)
              .hasMessageContaining("401");
        })
        .verify();

    StepVerifier.create(controller.getMyOrderDetail("order-1", null, null, null))
        .expectErrorSatisfies(ex -> {
          assertThat(ex).isInstanceOf(ResponseStatusException.class)
              .hasMessageContaining("401");
        })
        .verify();
  }

}
