package tacos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
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
import tacos.web.api.dto.OrderCancelRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.OrderStatusUpdateRequest;

@ExtendWith(MockitoExtension.class)
public class OrderWorkflowSecurityAndOwnershipTest {

  @Mock
  private OrderRepository orderRepo;

  @Mock
  private InventoryService inventoryService;

  private OrderMapper orderMapper = new OrderMapper();
  private OrderWorkflowService workflowService;

  private User userAlice;
  private User userBob;
  private Authentication authAlice;
  private Authentication authBob;
  private Authentication authKitchen;
  private Authentication authDelivery;
  private Authentication authAdmin;

  @BeforeEach
  public void setUp() {
    workflowService = new OrderWorkflowService(orderRepo, inventoryService, orderMapper);

    userAlice = new User("alice", "pass", "Alice", "Street", "City", "State", "12345", "111", "a@test.com", Collections.singletonList("ROLE_USER"));
    userAlice.setId("alice-id");

    userBob = new User("bob", "pass", "Bob", "Street", "City", "State", "12345", "222", "b@test.com", Collections.singletonList("ROLE_USER"));
    userBob.setId("bob-id");

    authAlice = new UsernamePasswordAuthenticationToken("alice", "pass", Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    authBob = new UsernamePasswordAuthenticationToken("bob", "pass", Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
    authKitchen = new UsernamePasswordAuthenticationToken("chef", "pass", Collections.singletonList(new SimpleGrantedAuthority("ROLE_KITCHEN")));
    authDelivery = new UsernamePasswordAuthenticationToken("driver", "pass", Collections.singletonList(new SimpleGrantedAuthority("ROLE_DELIVERY")));
    authAdmin = new UsernamePasswordAuthenticationToken("admin", "pass", Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));
  }

  private TacoOrder buildOrder(String id, OrderStatus status, User user) {
    TacoOrder order = new TacoOrder();
    order.setId(id);
    order.setStatus(status);
    order.setUser(user);
    order.setPlacedAt(new Date());
    order.setVersion(1L);
    return order;
  }

  @Test
  @DisplayName("1. Rol no autorizado para una transición recibe 403 Forbidden")
  public void testUnauthorizedRoleReceivesForbidden() {
    TacoOrder order = buildOrder("ord-sec-1", OrderStatus.CREATED, userAlice);
    when(orderRepo.findById("ord-sec-1")).thenReturn(Mono.just(order));

    // Regular USER intentando marcar ACCEPTED
    OrderStatusUpdateRequest req = OrderStatusUpdateRequest.builder()
        .status(OrderStatus.ACCEPTED)
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.updateOrderStatus("ord-sec-1", req, authAlice);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof ResponseStatusException &&
            ((ResponseStatusException) err).getStatus() == HttpStatus.FORBIDDEN)
        .verify();

    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("2. Rol KITCHEN no puede saltar a OUT_FOR_DELIVERY (recibe 403 Forbidden)")
  public void testKitchenRoleCannotDispatchOrder() {
    TacoOrder order = buildOrder("ord-sec-2", OrderStatus.READY, userAlice);
    when(orderRepo.findById("ord-sec-2")).thenReturn(Mono.just(order));

    OrderStatusUpdateRequest req = OrderStatusUpdateRequest.builder()
        .status(OrderStatus.OUT_FOR_DELIVERY)
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.updateOrderStatus("ord-sec-2", req, authKitchen);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof ResponseStatusException &&
            ((ResponseStatusException) err).getStatus() == HttpStatus.FORBIDDEN)
        .verify();

    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("3. Cancelación de usuario en estado CREATED es exitosa y libera inventario (200 OK)")
  public void testUserCancelSuccessBeforeCutoff() {
    TacoOrder order = buildOrder("ord-cancel-1", OrderStatus.CREATED, userAlice);
    when(orderRepo.findById("ord-cancel-1")).thenReturn(Mono.just(order));
    when(inventoryService.releaseForOrder("ord-cancel-1")).thenReturn(Mono.empty());
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    OrderCancelRequest req = OrderCancelRequest.builder()
        .reason("Changed my mind")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.cancelOrder("ord-cancel-1", req, authAlice);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(resp.getBody()).isNotNull();
          assertThat(resp.getBody().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        })
        .verifyComplete();

    verify(inventoryService).releaseForOrder("ord-cancel-1");
    verify(orderRepo).save(any(TacoOrder.class));
  }

  @Test
  @DisplayName("4. Cancelación de usuario tras punto de corte (PREPARING) rechaza con 409 Conflict")
  public void testUserCancelPastCutoffRejected() {
    TacoOrder order = buildOrder("ord-cancel-2", OrderStatus.PREPARING, userAlice);
    when(orderRepo.findById("ord-cancel-2")).thenReturn(Mono.just(order));

    OrderCancelRequest req = OrderCancelRequest.builder()
        .reason("Cancel too late")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.cancelOrder("ord-cancel-2", req, authAlice);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof ResponseStatusException &&
            ((ResponseStatusException) err).getStatus() == HttpStatus.CONFLICT)
        .verify();

    verify(inventoryService, never()).releaseForOrder(any());
    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("5. Cancelación de usuario en estado ACCEPTED rechaza con 409 Conflict")
  public void testUserCancelAcceptedRejected() {
    TacoOrder order = buildOrder("ord-cancel-3", OrderStatus.ACCEPTED, userAlice);
    when(orderRepo.findById("ord-cancel-3")).thenReturn(Mono.just(order));

    OrderCancelRequest req = OrderCancelRequest.builder()
        .reason("Kitchen already accepted")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.cancelOrder("ord-cancel-3", req, authAlice);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof ResponseStatusException &&
            ((ResponseStatusException) err).getStatus() == HttpStatus.CONFLICT)
        .verify();

    verify(inventoryService, never()).releaseForOrder(any());
    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("6. Usuario ajeno intentando cancelar orden recibe 404 Not Found (política de no revelación)")
  public void testNonOwnerCancelReturnsNotFound() {
    TacoOrder order = buildOrder("ord-alice", OrderStatus.CREATED, userAlice);
    when(orderRepo.findById("ord-alice")).thenReturn(Mono.just(order));

    // Bob intentando cancelar orden de Alice
    OrderCancelRequest req = OrderCancelRequest.builder()
        .reason("I want to cancel someone else's order")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.cancelOrder("ord-alice", req, authBob);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof ResponseStatusException &&
            ((ResponseStatusException) err).getStatus() == HttpStatus.NOT_FOUND)
        .verify();

    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("7. Admin puede cancelar una orden en estado PREPARING (200 OK)")
  public void testAdminCanCancelInPreparing() {
    TacoOrder order = buildOrder("ord-admin-cancel", OrderStatus.PREPARING, userAlice);
    when(orderRepo.findById("ord-admin-cancel")).thenReturn(Mono.just(order));
    when(inventoryService.releaseForOrder("ord-admin-cancel")).thenReturn(Mono.empty());
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    OrderCancelRequest req = OrderCancelRequest.builder()
        .reason("Kitchen emergency - cancelled by supervisor")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.cancelOrder("ord-admin-cancel", req, authAdmin);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(resp.getBody()).isNotNull();
          assertThat(resp.getBody().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        })
        .verifyComplete();

    verify(inventoryService).releaseForOrder("ord-admin-cancel");
    verify(orderRepo).save(any(TacoOrder.class));
  }

  @Test
  @DisplayName("8. Re-cancelar orden ya CANCELLED es idempotente (200 OK)")
  public void testCancelAlreadyCancelledIsIdempotent() {
    TacoOrder order = buildOrder("ord-already-cancelled", OrderStatus.CANCELLED, userAlice);
    when(orderRepo.findById("ord-already-cancelled")).thenReturn(Mono.just(order));

    OrderCancelRequest req = OrderCancelRequest.builder().reason("Retry").build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.cancelOrder("ord-already-cancelled", req, authAlice);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(resp.getBody()).isNotNull();
          assertThat(resp.getBody().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        })
        .verifyComplete();

    verify(inventoryService, never()).releaseForOrder(any());
    verify(orderRepo, never()).save(any());
  }

}
