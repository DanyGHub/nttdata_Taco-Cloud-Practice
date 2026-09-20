package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.when;

import java.security.Principal;
import java.util.Arrays;
import java.util.Collections;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.data.domain.Pageable;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.data.UserRepository;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderPatchDTO;
import tacos.web.api.dto.OrderPutDTO;
import tacos.web.api.dto.OrderResponse;

@ExtendWith(MockitoExtension.class)
public class OrderOwnershipSecurityTest {

  @Mock
  private OrderRepository orderRepo;

  @Mock
  private OrderMessagingService messagingService;

  @Mock
  private EmailOrderService emailOrderService;

  @Mock
  private UserRepository userRepo;

  private OrderMapper orderMapper = new OrderMapper();

  private OrderApiController controller;

  private User userA;
  private User userB;
  private TacoOrder orderOfA;
  private TacoOrder orderOfB;

  @BeforeEach
  public void setUp() {
    controller = new OrderApiController(orderRepo, messagingService, emailOrderService, orderMapper, userRepo);

    userA = new User("userA", "passA", "User A", "Street A", "City", "State", "12345", "111", "a@test.com", Arrays.asList("ROLE_USER"));
    userA.setId("userA-id");

    userB = new User("userB", "passB", "User B", "Street B", "City", "State", "54321", "222", "b@test.com", Arrays.asList("ROLE_USER"));
    userB.setId("userB-id");

    orderOfA = new TacoOrder();
    orderOfA.setId("order-A-1");
    orderOfA.setUser(userA);
    orderOfA.setDeliveryName("User A");

    orderOfB = new TacoOrder();
    orderOfB.setId("order-B-1");
    orderOfB.setUser(userB);
    orderOfB.setDeliveryName("User B");
  }

  @Test
  @DisplayName("TC-11 Ownership: Usuario A consulta su propia orden exitosamente (200 OK)")
  public void userACanAccessOwnOrder() {
    when(orderRepo.findById("order-A-1")).thenReturn(Mono.just(orderOfA));

    Authentication authA = new UsernamePasswordAuthenticationToken("userA", "credentials",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

    Mono<ResponseEntity<OrderResponse>> result = controller.getOrderById("order-A-1", authA);

    StepVerifier.create(result)
        .assertNext(response -> {
          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(response.getBody().getId()).isEqualTo("order-A-1");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("TC-11 Ownership: Usuario A intentando consultar orden de Usuario B recibe 403 Forbidden")
  public void userACannotAccessUserBOrder() {
    when(orderRepo.findById("order-B-1")).thenReturn(Mono.just(orderOfB));

    Authentication authA = new UsernamePasswordAuthenticationToken("userA", "credentials",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

    Mono<ResponseEntity<OrderResponse>> result = controller.getOrderById("order-B-1", authA);

    StepVerifier.create(result)
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException &&
            ((ResponseStatusException) throwable).getStatus() == HttpStatus.FORBIDDEN)
        .verify();
  }

  @Test
  @DisplayName("TC-11 Ownership: Admin puede consultar la orden de cualquier usuario (200 OK)")
  public void adminCanAccessAnyOrder() {
    when(orderRepo.findById("order-B-1")).thenReturn(Mono.just(orderOfB));

    Authentication adminAuth = new UsernamePasswordAuthenticationToken("admin", "credentials",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

    Mono<ResponseEntity<OrderResponse>> result = controller.getOrderById("order-B-1", adminAuth);

    StepVerifier.create(result)
        .assertNext(response -> {
          assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(response.getBody().getId()).isEqualTo("order-B-1");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("TC-23 Reemplazo allOrders")
  public void userACannotAccessGlobalAllOrders() {
    Authentication authA = new UsernamePasswordAuthenticationToken("userA", "credentials",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));

    Flux<OrderResponse> result = controller.allOrders(authA);

    StepVerifier.create(result)
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException &&
            ((ResponseStatusException) throwable).getStatus() == HttpStatus.FORBIDDEN)
        .verify();
  }

  @Test
  @DisplayName("TC-11 Ownership: Admin listando órdenes obtiene todas las órdenes")
  public void adminGetsAllOrders() {
    when(orderRepo.findAll()).thenReturn(Flux.just(orderOfA, orderOfB));

    Authentication adminAuth = new UsernamePasswordAuthenticationToken("admin", "credentials",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_ADMIN")));

    Flux<OrderResponse> result = controller.allOrders(adminAuth);

    StepVerifier.create(result)
        .expectNextCount(2)
        .verifyComplete();
  }

  @Test
  @DisplayName("TC-11 Ownership: Usuario A no puede modificar vía PATCH orden de Usuario B (403 Forbidden)")
  public void userACannotPatchUserBOrder() {
    when(orderRepo.findById("order-B-1")).thenReturn(Mono.just(orderOfB));

    OrderPatchDTO patch = new OrderPatchDTO();
    patch.setDeliveryStreet("New Street");

    Principal principalA = () -> "userA";

    Mono<ResponseEntity<OrderResponse>> result = controller.patchOrder("order-B-1", patch, principalA);

    StepVerifier.create(result)
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException &&
            ((ResponseStatusException) throwable).getStatus() == HttpStatus.FORBIDDEN)
        .verify();
  }

  @Test
  @DisplayName("TC-11 Ownership: Usuario A no puede modificar vía PUT orden de Usuario B (403 Forbidden)")
  public void userACannotPutUserBOrder() {
    when(orderRepo.findById("order-B-1")).thenReturn(Mono.just(orderOfB));

    OrderPutDTO put = new OrderPutDTO();
    put.setDeliveryName("Hacked Name");
    put.setDeliveryStreet("Street");
    put.setDeliveryCity("City");
    put.setDeliveryState("State");
    put.setDeliveryZip("12345");

    Principal principalA = () -> "userA";

    Mono<ResponseEntity<OrderResponse>> result = controller.putOrder("order-B-1", put, principalA);

    StepVerifier.create(result)
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException &&
            ((ResponseStatusException) throwable).getStatus() == HttpStatus.FORBIDDEN)
        .verify();
  }

  @Test
  @DisplayName("TC-11 Ownership: Usuario A no puede eliminar vía DELETE orden de Usuario B (403 Forbidden)")
  public void userACannotDeleteUserBOrder() {
    when(orderRepo.findById("order-B-1")).thenReturn(Mono.just(orderOfB));

    Principal principalA = () -> "userA";

    Mono<ResponseEntity<Void>> result = controller.deleteOrder("order-B-1", principalA);

    StepVerifier.create(result)
        .expectErrorMatches(throwable -> throwable instanceof ResponseStatusException &&
            ((ResponseStatusException) throwable).getStatus() == HttpStatus.FORBIDDEN)
        .verify();
  }

}
