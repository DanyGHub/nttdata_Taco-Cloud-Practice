package tacos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.stream.Stream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.OrderStatusUpdateRequest;

@ExtendWith(MockitoExtension.class)
public class OrderWorkflowMatrixParameterizedTest {

  @Mock
  private OrderRepository orderRepo;

  @Mock
  private InventoryService inventoryService;

  private OrderMapper orderMapper = new OrderMapper();
  private OrderWorkflowService workflowService;
  private User orderOwner;

  @BeforeEach
  public void setUp() {
    workflowService = new OrderWorkflowService(orderRepo, inventoryService, orderMapper);
    orderOwner = new User("alice", "pass", "Alice", "Street", "City", "State", "12345", "111", "a@test.com", Collections.singletonList("ROLE_USER"));
    orderOwner.setId("user-alice-id");
  }

  private Authentication createAuth(String username, String role) {
    return new UsernamePasswordAuthenticationToken(username, "pass", Collections.singletonList(new SimpleGrantedAuthority(role)));
  }

  private TacoOrder createOrder(String orderId, OrderStatus status) {
    TacoOrder order = new TacoOrder();
    order.setId(orderId);
    order.setStatus(status);
    order.setUser(orderOwner);
    order.setPlacedAt(new Date());
    order.setVersion(1L);
    return order;
  }

  static Stream<Arguments> validTransitions() {
    return Stream.of(
        Arguments.of(OrderStatus.CREATED, OrderStatus.ACCEPTED, "ROLE_KITCHEN", "chef"),
        Arguments.of(OrderStatus.CREATED, OrderStatus.ACCEPTED, "ROLE_ADMIN", "admin"),
        Arguments.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING, "ROLE_KITCHEN", "chef"),
        Arguments.of(OrderStatus.ACCEPTED, OrderStatus.PREPARING, "ROLE_ADMIN", "admin"),
        Arguments.of(OrderStatus.PREPARING, OrderStatus.READY, "ROLE_KITCHEN", "chef"),
        Arguments.of(OrderStatus.PREPARING, OrderStatus.READY, "ROLE_ADMIN", "admin"),
        Arguments.of(OrderStatus.READY, OrderStatus.OUT_FOR_DELIVERY, "ROLE_DELIVERY", "driver"),
        Arguments.of(OrderStatus.READY, OrderStatus.OUT_FOR_DELIVERY, "ROLE_ADMIN", "admin"),
        Arguments.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, "ROLE_DELIVERY", "driver"),
        Arguments.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.DELIVERED, "ROLE_ADMIN", "admin"),
        Arguments.of(OrderStatus.CREATED, OrderStatus.CANCELLED, "ROLE_USER", "alice"),
        Arguments.of(OrderStatus.CREATED, OrderStatus.CANCELLED, "ROLE_ADMIN", "admin")
    );
  }

  @ParameterizedTest(name = "{index} => Transición válida: {0} -> {1} con rol {2} ({3})")
  @MethodSource("validTransitions")
  @DisplayName("1. Transiciones válidas en la máquina de estados con roles autorizados retornan 200 OK")
  public void testValidTransitions(OrderStatus from, OrderStatus to, String role, String username) {
    TacoOrder order = createOrder("ord-valid-1", from);
    when(orderRepo.findById("ord-valid-1")).thenReturn(Mono.just(order));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));
    if (to == OrderStatus.CANCELLED) {
      when(inventoryService.releaseForOrder("ord-valid-1")).thenReturn(Mono.empty());
    }

    Authentication auth = createAuth(username, role);
    OrderStatusUpdateRequest req = OrderStatusUpdateRequest.builder()
        .status(to)
        .reason("Testing valid transition")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.updateOrderStatus("ord-valid-1", req, auth);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(resp.getBody()).isNotNull();
          assertThat(resp.getBody().getStatus()).isEqualTo(to);
        })
        .verifyComplete();
  }

  static Stream<Arguments> invalidTransitions() {
    return Stream.of(
        Arguments.of(OrderStatus.CREATED, OrderStatus.DELIVERED, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.CREATED, OrderStatus.PREPARING, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.CREATED, OrderStatus.READY, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.CREATED, OrderStatus.OUT_FOR_DELIVERY, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.ACCEPTED, OrderStatus.DELIVERED, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.ACCEPTED, OrderStatus.READY, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.PREPARING, OrderStatus.DELIVERED, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.READY, OrderStatus.PREPARING, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.READY, OrderStatus.CREATED, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.OUT_FOR_DELIVERY, OrderStatus.READY, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.DELIVERED, OrderStatus.PREPARING, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.DELIVERED, OrderStatus.CANCELLED, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.CANCELLED, OrderStatus.ACCEPTED, "ROLE_ADMIN"),
        Arguments.of(OrderStatus.CANCELLED, OrderStatus.CREATED, "ROLE_ADMIN")
    );
  }

  @ParameterizedTest(name = "{index} => Transición inválida: {0} -> {1} debe retornar 409 Conflict")
  @MethodSource("invalidTransitions")
  @DisplayName("2. Transiciones ilegales en la máquina de estados fallan con HTTP 409 Conflict")
  public void testInvalidTransitions(OrderStatus from, OrderStatus to, String role) {
    TacoOrder order = createOrder("ord-inv-1", from);
    when(orderRepo.findById("ord-inv-1")).thenReturn(Mono.just(order));

    Authentication auth = createAuth("admin", role);
    OrderStatusUpdateRequest req = OrderStatusUpdateRequest.builder()
        .status(to)
        .reason("Testing invalid transition")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.updateOrderStatus("ord-inv-1", req, auth);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof ResponseStatusException &&
            ((ResponseStatusException) err).getStatus() == HttpStatus.CONFLICT)
        .verify();
  }

  @ParameterizedTest(name = "{index} => Transición idempotente: {0} -> {0}")
  @CsvSource({
      "CREATED",
      "ACCEPTED",
      "PREPARING",
      "READY",
      "OUT_FOR_DELIVERY",
      "DELIVERED",
      "CANCELLED"
  })
  @DisplayName("3. Transición idempotente al mismo estado no genera error ni duplica auditoría (200 OK)")
  public void testIdempotentSameStateTransition(OrderStatus status) {
    TacoOrder order = createOrder("ord-idem-1", status);
    order.recordStatusChange(status, "initial", "SYSTEM", "TEST", "initial setup");
    int initialHistorySize = order.getStatusHistory().size();

    when(orderRepo.findById("ord-idem-1")).thenReturn(Mono.just(order));

    Authentication auth = createAuth("admin", "ROLE_ADMIN");
    OrderStatusUpdateRequest req = OrderStatusUpdateRequest.builder()
        .status(status)
        .reason("Re-requesting same status")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.updateOrderStatus("ord-idem-1", req, auth);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(resp.getBody()).isNotNull();
          assertThat(resp.getBody().getStatus()).isEqualTo(status);
          assertThat(order.getStatusHistory().size()).isEqualTo(initialHistorySize);
        })
        .verifyComplete();
  }

}
