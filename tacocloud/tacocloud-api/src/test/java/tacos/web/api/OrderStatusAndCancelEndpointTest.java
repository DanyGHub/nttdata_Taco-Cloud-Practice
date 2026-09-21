package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;
import tacos.order.OrderStatus;
import tacos.order.OrderWorkflowService;
import tacos.web.api.dto.OrderMapper;

@DisplayName("TC-25: Pruebas WebTestClient de endpoints PATCH /status y POST /cancel")
public class OrderStatusAndCancelEndpointTest {

  private OrderRepository repo;
  private InventoryService inventoryService;
  private OrderMapper orderMapper;
  private OrderWorkflowService workflowService;
  private OrderApiController controller;

  private User alice;

  @BeforeEach
  public void setUp() {
    repo = mock(OrderRepository.class);
    inventoryService = mock(InventoryService.class);
    orderMapper = new OrderMapper();
    workflowService = new OrderWorkflowService(repo, inventoryService, orderMapper);

    alice = new User("alice", "pass", "Alice", "Street", "City", "State", "12345", "111", "a@test.com",
        Collections.singletonList("ROLE_USER"));
    alice.setId("alice-id");

    controller = new OrderApiController(
        repo,
        null,
        null,
        orderMapper,
        null,
        null,
        null,
        null,
        inventoryService,
        null,
        null,
        workflowService
    );
  }

  private WebTestClient buildClientWithAuth(String username, String role) {
    Authentication auth = new UsernamePasswordAuthenticationToken(
        username,
        "pass",
        Collections.singletonList(new SimpleGrantedAuthority(role))
    );

    return WebTestClient.bindToController(controller)
        .webFilter((exchange, chain) ->
            chain.filter(exchange.mutate().principal(Mono.just(auth)).build()))
        .controllerAdvice(new Object() {
          @ExceptionHandler(ResponseStatusException.class)
          public ResponseEntity<String> handleResponseStatus(ResponseStatusException ex) {
            return ResponseEntity.status(ex.getStatus()).body(ex.getReason());
          }
          @ExceptionHandler(org.springframework.dao.OptimisticLockingFailureException.class)
          public ResponseEntity<String> handleOptimisticLock(Exception ex) {
            return ResponseEntity.status(HttpStatus.CONFLICT).body(ex.getMessage());
          }
        })
        .build();
  }

  @Test
  @DisplayName("1. PATCH /api/orders/{id}/status por KITCHEN transiciona a ACCEPTED exitosamente (200 OK)")
  public void testPatchStatusByKitchenSuccess() {
    TacoOrder order = new TacoOrder();
    order.setId("order-100");
    order.setStatus(OrderStatus.CREATED);
    order.setUser(alice);
    order.setPlacedAt(new Date());

    when(repo.findById("order-100")).thenReturn(Mono.just(order));
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    WebTestClient client = buildClientWithAuth("chef_gordon", "ROLE_KITCHEN");

    client.patch()
        .uri("/api/orders/order-100/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"ACCEPTED\",\"reason\":\"Chef acknowledged order\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("order-100")
        .jsonPath("$.status").isEqualTo("ACCEPTED")
        .jsonPath("$.statusHistory[0].status").isEqualTo("ACCEPTED")
        .jsonPath("$.statusHistory[0].changedBy").isEqualTo("chef_gordon");
  }

  @Test
  @DisplayName("2. PATCH /api/orders/{id}/status por USER intentando ACCEPTED es rechazado con 403 Forbidden")
  public void testPatchStatusByUserForbidden() {
    TacoOrder order = new TacoOrder();
    order.setId("order-101");
    order.setStatus(OrderStatus.CREATED);
    order.setUser(alice);

    when(repo.findById("order-101")).thenReturn(Mono.just(order));

    WebTestClient client = buildClientWithAuth("alice", "ROLE_USER");

    client.patch()
        .uri("/api/orders/order-101/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"ACCEPTED\"}")
        .exchange()
        .expectStatus().isForbidden();
  }

  @Test
  @DisplayName("3. PATCH /api/orders/{id}/status salto inválido CREATED -> DELIVERED retorna 409 Conflict")
  public void testPatchStatusInvalidJumpConflict() {
    TacoOrder order = new TacoOrder();
    order.setId("order-102");
    order.setStatus(OrderStatus.CREATED);
    order.setUser(alice);

    when(repo.findById("order-102")).thenReturn(Mono.just(order));

    WebTestClient client = buildClientWithAuth("admin", "ROLE_ADMIN");

    client.patch()
        .uri("/api/orders/order-102/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"DELIVERED\"}")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  @DisplayName("4. POST /api/orders/{id}/cancel por dueño en estado CREATED cancela y libera stock (200 OK)")
  public void testCancelOrderSuccess() {
    TacoOrder order = new TacoOrder();
    order.setId("order-103");
    order.setStatus(OrderStatus.CREATED);
    order.setUser(alice);
    order.setPlacedAt(new Date());

    when(repo.findById("order-103")).thenReturn(Mono.just(order));
    when(inventoryService.releaseForOrder("order-103")).thenReturn(Mono.empty());
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    WebTestClient client = buildClientWithAuth("alice", "ROLE_USER");

    client.post()
        .uri("/api/orders/order-103/cancel")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"reason\":\"I ordered by mistake\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("order-103")
        .jsonPath("$.status").isEqualTo("CANCELLED")
        .jsonPath("$.statusHistory[0].status").isEqualTo("CANCELLED")
        .jsonPath("$.statusHistory[0].changedBy").isEqualTo("alice");
  }

  @Test
  @DisplayName("5. POST /api/orders/{id}/cancel tras aceptación de cocina (PREPARING) rechaza con 409 Conflict")
  public void testCancelOrderPastCutoffConflict() {
    TacoOrder order = new TacoOrder();
    order.setId("order-104");
    order.setStatus(OrderStatus.PREPARING);
    order.setUser(alice);

    when(repo.findById("order-104")).thenReturn(Mono.just(order));

    WebTestClient client = buildClientWithAuth("alice", "ROLE_USER");

    client.post()
        .uri("/api/orders/order-104/cancel")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"reason\":\"Please cancel\"}")
        .exchange()
        .expectStatus().isEqualTo(HttpStatus.CONFLICT);
  }

  @Test
  @DisplayName("6. POST /api/orders/{id}/cancel por usuario ajeno retorna 404 Not Found")
  public void testCancelOrderNonOwnerNotFound() {
    TacoOrder order = new TacoOrder();
    order.setId("order-105");
    order.setStatus(OrderStatus.CREATED);
    order.setUser(alice);

    when(repo.findById("order-105")).thenReturn(Mono.just(order));

    WebTestClient client = buildClientWithAuth("charlie", "ROLE_USER");

    client.post()
        .uri("/api/orders/order-105/cancel")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"reason\":\"Not my order\"}")
        .exchange()
        .expectStatus().isNotFound();
  }

}
