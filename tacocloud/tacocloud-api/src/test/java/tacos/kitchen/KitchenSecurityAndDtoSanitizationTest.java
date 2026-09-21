package tacos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.lang.reflect.Field;
import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.kitchen.dto.KitchenClaimRequest;
import tacos.kitchen.dto.KitchenOrderDto;
import tacos.kitchen.dto.KitchenStatusUpdateRequest;
import tacos.order.OrderStatus;
import tacos.order.OrderWorkflowService;
import tacos.web.api.dto.OrderResponse;

@DisplayName("TC-26: Pruebas de seguridad")
public class KitchenSecurityAndDtoSanitizationTest {

  private ReactiveMongoTemplate mongoTemplate;
  private KitchenEtaCalculator etaCalculator;
  private OrderWorkflowService workflowService;
  private KitchenQueueService queueService;
  private KitchenApiController controller;

  private Authentication authKitchen;
  private Authentication authUser;

  @BeforeEach
  public void setUp() {
    mongoTemplate = mock(ReactiveMongoTemplate.class);
    etaCalculator = new KitchenEtaCalculator();
    workflowService = mock(OrderWorkflowService.class);
    queueService = new KitchenQueueService(mongoTemplate, etaCalculator, workflowService);
    controller = new KitchenApiController(queueService);

    authKitchen = new UsernamePasswordAuthenticationToken("chef_gordon", "pass",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_KITCHEN")));
    authUser = new UsernamePasswordAuthenticationToken("alice", "pass",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_USER")));
  }

  private WebTestClient buildClientWithAuth(Authentication auth) {
    return WebTestClient.bindToController(controller)
        .webFilter((exchange, chain) ->
            chain.filter(exchange.mutate().principal(Mono.just(auth)).build()))
        .controllerAdvice(new Object() {
          @ExceptionHandler(ResponseStatusException.class)
          public ResponseEntity<String> handleResponseStatus(ResponseStatusException ex) {
            return ResponseEntity.status(ex.getStatus()).body(ex.getReason());
          }
        })
        .build();
  }

  @Test
  @DisplayName("1. Estación de cocina no puede reclamar dos órdenes a la vez si ya tiene una activa (409 Conflict)")
  public void testStationCannotClaimTwiceConcurrently() {
    // La estación STATION_1 ya tiene una orden activa
    when(mongoTemplate.exists(any(Query.class), eq(TacoOrder.class))).thenReturn(Mono.just(true));

    KitchenClaimRequest req = KitchenClaimRequest.builder()
        .stationId("STATION_1")
        .cookId("chef_gordon")
        .build();

    StepVerifier.create(queueService.claimNext(req, authKitchen))
        .expectErrorMatches(err -> err instanceof ResponseStatusException &&
            ((ResponseStatusException) err).getStatus() == HttpStatus.CONFLICT &&
            ((ResponseStatusException) err).getReason().contains("already has an active order"))
        .verify();
  }

  @Test
  @DisplayName("2. KitchenOrderDto no expone información sensible (sin tarjetas, tokens, passwords ni dirección)")
  public void testKitchenDtoSanitization() {
    User user = new User("alice", "superSecretPassword123", "Alice", "123 Secret Street",
        "Beverly Hills", "CA", "90210", "555-1234", "alice@secret.com", Collections.emptyList());

    TacoOrder fullOrder = new TacoOrder();
    fullOrder.setId("ord-sensitive-1");
    fullOrder.setStatus(OrderStatus.CREATED);
    fullOrder.setUser(user);
    fullOrder.setDeliveryName("Alice Client");
    fullOrder.setDeliveryStreet("123 Secret Street");
    fullOrder.setDeliveryCity("Beverly Hills");
    fullOrder.setDeliveryState("CA");
    fullOrder.setDeliveryZip("90210");
    fullOrder.setPaymentToken("tok_super_secret_pci_token_8888");
    fullOrder.setLast4("9999");
    fullOrder.setBrand("MasterCard");

    Taco taco = new Taco();
    taco.setName("Carnitas Taco");
    taco.setIngredients(Collections.singletonList(new Ingredient("CARN", "Carnitas", Ingredient.Type.PROTEIN)));
    fullOrder.addOrderItem(new OrderItem(taco, 2));

    KitchenOrderDto dto = queueService.toDto(fullOrder, 1, 7);

    // Verificar datos presentes
    assertThat(dto.getId()).isEqualTo("ord-sensitive-1");
    assertThat(dto.getCustomerName()).isEqualTo("Alice Client");
    assertThat(dto.getItems()).hasSize(1);
    assertThat(dto.getItems().get(0).getTacoName()).isEqualTo("Carnitas Taco");
    assertThat(dto.getItems().get(0).getIngredients()).contains("Carnitas");

    // Verificar mediante introspección que los campos sensibles NO existen en el DTO
    Field[] fields = KitchenOrderDto.class.getDeclaredFields();
    for (Field f : fields) {
      String name = f.getName().toLowerCase();
      assertThat(name).doesNotContain("card");
      assertThat(name).doesNotContain("token");
      assertThat(name).doesNotContain("cvv");
      assertThat(name).doesNotContain("password");
      assertThat(name).doesNotContain("street");
      assertThat(name).doesNotContain("zip");
    }
  }

  @Test
  @DisplayName("3. GET /api/kitchen/queue devuelve 200 OK con rol KITCHEN")
  public void testQueueAccessibleByKitchenRole() {
    TacoOrder order = new TacoOrder();
    order.setId("ord-k-1");
    order.setStatus(OrderStatus.CREATED);
    order.setPlacedAt(new Date());

    when(mongoTemplate.find(any(Query.class), eq(TacoOrder.class))).thenReturn(Flux.just(order));

    WebTestClient client = buildClientWithAuth(authKitchen);

    client.get()
        .uri("/api/kitchen/queue")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[0].id").isEqualTo("ord-k-1")
        .jsonPath("$[0].queuePosition").isEqualTo(1);
  }

  @Test
  @DisplayName("4. PATCH /api/kitchen/orders/{id}/status avanza estado delegando a workflow service")
  public void testKitchenStatusAdvanceDelegation() {
    TacoOrder order = new TacoOrder();
    order.setId("ord-k-2");
    order.setStatus(OrderStatus.PREPARING);

    OrderResponse workflowResponse = new OrderResponse();
    workflowResponse.setId("ord-k-2");
    workflowResponse.setStatus(OrderStatus.PREPARING);

    when(workflowService.updateOrderStatus(eq("ord-k-2"), any(), any()))
        .thenReturn(Mono.just(ResponseEntity.ok(workflowResponse)));
    when(mongoTemplate.findById("ord-k-2", TacoOrder.class)).thenReturn(Mono.just(order));

    WebTestClient client = buildClientWithAuth(authKitchen);

    client.patch()
        .uri("/api/kitchen/orders/ord-k-2/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"PREPARING\",\"reason\":\"Started cooking\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.id").isEqualTo("ord-k-2")
        .jsonPath("$.status").isEqualTo("PREPARING");
  }

  @Test
  @DisplayName("5. PATCH /api/kitchen/orders/{id}/status rechaza estados inválidos para cocina (ej. DELIVERED)")
  public void testKitchenStatusRejectsNonKitchenStatus() {
    WebTestClient client = buildClientWithAuth(authKitchen);

    client.patch()
        .uri("/api/kitchen/orders/ord-k-3/status")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"status\":\"DELIVERED\"}")
        .exchange()
        .expectStatus().isBadRequest();
  }

}
