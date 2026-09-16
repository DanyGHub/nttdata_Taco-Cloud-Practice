package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.EmailOrder;

public class OrderApiControllerTest {

  // WebTestClient con Principal autenticado y ExceptionHandler
  private WebTestClient buildClientWithUser(String user, OrderApiController controller) {
    return WebTestClient.bindToController(controller)
        .webFilter((exchange, chain) -> chain.filter(exchange.mutate().principal(Mono.just(() -> user)).build()))
        .controllerAdvice(new Object() {
          @ExceptionHandler({
              WebExchangeBindException.class,
              HttpMessageNotReadableException.class,
              org.springframework.web.server.ServerWebInputException.class,
              org.springframework.core.codec.DecodingException.class
          })
          public ResponseEntity<Void> handleBadRequest() {
            return ResponseEntity.badRequest().build();
          }

          @ExceptionHandler(ResponseStatusException.class)
          public ResponseEntity<Void> handleResponseStatus(ResponseStatusException ex) {
            return ResponseEntity.status(ex.getStatus()).build();
          }
        })
        .build();
  }

  // TC-07: Una sola suscripción para guardar y publicar

  @Test
  public void shouldConvertPersistAndPublishOrderExactlyOnce() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    TacoOrder convertedOrder = new TacoOrder();
    convertedOrder.setDeliveryName("Craig Walls");

    TacoOrder savedOrder = new TacoOrder();
    savedOrder.setId("SAVED_ORDER_1");
    savedOrder.setDeliveryName("Craig Walls");

    when(emailService.convertEmailOrderToDomainOrder(any())).thenReturn(Mono.just(convertedOrder));
    when(repo.save(convertedOrder)).thenReturn(Mono.just(savedOrder));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = WebTestClient.bindToController(controller).build();

    String json = "{\"email\":\"craig@habuma.com\",\"tacos\":[]}";

    testClient.post()
        .uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.id").isEqualTo("SAVED_ORDER_1")
        .jsonPath("$.deliveryName").isEqualTo("Craig Walls");

    verify(repo, times(1)).save(convertedOrder);
    verify(messagingService, times(1)).sendOrder(savedOrder);
  }

  @Test
  public void shouldNotPersistNorPublishWhenConversionFails() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    when(emailService.convertEmailOrderToDomainOrder(any()))
        .thenReturn(Mono.error(new IllegalArgumentException("Unknown ingredient ID: BAD")));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = WebTestClient.bindToController(controller).build();

    String json = "{\"email\":\"craig@habuma.com\",\"tacos\":[]}";

    testClient.post()
        .uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus().is5xxServerError();

    verify(repo, never()).save(any());
    verify(messagingService, never()).sendOrder(any());
  }

  @Test
  public void shouldNotPublishWhenPersistenceFails() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    TacoOrder convertedOrder = new TacoOrder();

    when(emailService.convertEmailOrderToDomainOrder(any())).thenReturn(Mono.just(convertedOrder));
    when(repo.save(convertedOrder)).thenReturn(Mono.error(new RuntimeException("Mongo connection timeout")));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = WebTestClient.bindToController(controller).build();

    String json = "{\"email\":\"craig@habuma.com\",\"tacos\":[]}";

    testClient.post()
        .uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus().is5xxServerError();

    verify(repo, times(1)).save(convertedOrder);
    verify(messagingService, never()).sendOrder(any());
  }

  @Test
  public void shouldNotDuplicateSubscriptionOnColdPublisher() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    AtomicInteger subscriptionCount = new AtomicInteger(0);
    TacoOrder convertedOrder = new TacoOrder();
    TacoOrder savedOrder = new TacoOrder();
    savedOrder.setId("SAVED_ORDER_1");

    // Verificar cada sub
    Mono<TacoOrder> coldConversionMono = Mono.defer(() -> {
      subscriptionCount.incrementAndGet();
      return Mono.just(convertedOrder);
    });

    when(emailService.convertEmailOrderToDomainOrder(any())).thenReturn(coldConversionMono);
    when(repo.save(convertedOrder)).thenReturn(Mono.just(savedOrder));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = WebTestClient.bindToController(controller).build();

    String json = "{\"email\":\"craig@habuma.com\",\"tacos\":[]}";

    testClient.post()
        .uri("/api/orders/fromEmail")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus().isCreated();

    assertThat(subscriptionCount.get()).isEqualTo(1);
    verify(messagingService, times(1)).sendOrder(savedOrder);
  }

  // TC-04: PATCH de órdenes con lista blanca y sin ZIP mutante

  @Test
  public void shouldPatchOrderOk() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    User user = new User(
      "testuser",
      "pass123",
      "Angel Lopez",
      "street123",
      "Ags",
      "goodstate",
      "12345",
      "123-456-7890",
      "testuser@example.com"
    );
    
    TacoOrder order = new TacoOrder();
    order.setId("ORDER1");
    order.setUser(user);
    order.setDeliveryZip("11111");

    when(repo.findById("ORDER1")).thenReturn(Mono.just(order));
    when(repo.save(any(TacoOrder.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser", controller);

    testClient.patch()
        .uri("/api/orders/ORDER1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryZip\":\"22222\"}")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.deliveryZip").isEqualTo("22222")
        .jsonPath("$.id").isEqualTo("ORDER1");
  }

  @Test
  public void shouldPatchOrderBadRequest() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    User user = new User(
      "testuser",
      "pass123",
      "Angel Lopez",
      "street123",
      "Ags",
      "goodstate",
      "12345",
      "123-456-7890",
      "testuser@example.com"
    );
    TacoOrder order = new TacoOrder();
    order.setId("ORDER1");
    order.setUser(user);
    order.setDeliveryZip("11111");

    when(repo.findById("ORDER1")).thenReturn(Mono.just(order));
    when(repo.save(any(TacoOrder.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser", controller);

    testClient.patch()
        .uri("/api/orders/ORDER1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"id\":\"ORDER2\",\"deliveryZip\":\"22222\"}")
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  public void shouldPatchOrderNotFound() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    when(repo.findById("UNKNOWN")).thenReturn(Mono.empty());

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser", controller);

    testClient.patch()
        .uri("/api/orders/UNKNOWN")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryZip\":\"22222\"}")
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test
  public void shouldPatchOrderForbidden() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    User user = new User(
      "testuser",
      "pass123",
      "Angel Lopez",
      "street123",
      "Ags",
      "goodstate",
      "12345",
      "123-456-7890",
      "testuser@example.com"
    );
    TacoOrder order = new TacoOrder();
    order.setId("ORDER1");
    order.setUser(user);
    order.setDeliveryZip("11111");

    when(repo.findById("ORDER1")).thenReturn(Mono.just(order));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("otheruser", controller);

    testClient.patch()
        .uri("/api/orders/ORDER1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue("{\"deliveryZip\":\"22222\"}")
        .exchange()
        .expectStatus().isForbidden();
  }

  // TC-05: PUT y DELETE de órdenes con validación de dueño y códigos HTTP

  @Test
  public void shouldPutOrderOk() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    User user = new User(
      "testuser",
      "pass123",
      "Angel Lopez",
      "street123",
      "Ags",
      "goodstate",
      "12345",
      "123-456-7890",
      "testuser@example.com"
    );
    TacoOrder order = new TacoOrder();
    order.setId("ORDER1");
    order.setUser(user);
    order.setDeliveryCity("City");

    when(repo.findById("ORDER1")).thenReturn(Mono.just(order));
    when(repo.save(any(TacoOrder.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser", controller);

    String json = "{"
        + "\"deliveryName\":\"New Name\","
        + "\"deliveryStreet\":\"New Street\","
        + "\"deliveryCity\":\"New City\","
        + "\"deliveryState\":\"New State\","
        + "\"deliveryZip\":\"New Zip\","
        + "\"tacos\":[{\"name\":\"Taco Nuevo\", \"ingredients\":[{\"id\":\"FLTO\",\"name\":\"Flour Tortilla\",\"type\":\"WRAP\"}]}]"
        + "}";

    testClient.put()
        .uri("/api/orders/ORDER1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$.deliveryName").isEqualTo("New Name")
        .jsonPath("$.deliveryStreet").isEqualTo("New Street");
  }

  @Test
  public void shouldPutOrderBadRequest() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    User user = new User(
      "testuser",
      "pass123",
      "Angel Lopez",
      "street123",
      "Ags",
      "goodstate",
      "12345",
      "123-456-7890",
      "testuser@example.com"
    );
    TacoOrder order = new TacoOrder();
    order.setId("ORDER1");
    order.setUser(user);
    order.setDeliveryCity("City");

    when(repo.findById("ORDER1")).thenReturn(Mono.just(order));
    when(repo.save(any(TacoOrder.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser", controller);

    String json = "{\"id\":\"ORDER2\", \"deliveryCity\":\"New City\"}";

    testClient.put()
        .uri("/api/orders/ORDER1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus().isBadRequest();
  }

  @Test
  public void shouldPutOrderNotFound() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    when(repo.findById("UNKNOWN")).thenReturn(Mono.empty());

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser", controller);

    String json = "{\"deliveryCity\":\"New City\"}";

    testClient.put()
        .uri("/api/orders/UNKNOWN")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test
  public void shouldPutOrderForbidden() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    User user = new User(
      "testuser",
      "pass123",
      "Angel Lopez",
      "street123",
      "Ags",
      "goodstate",
      "12345",
      "123-456-7890",
      "testuser@example.com"
    );
    TacoOrder order = new TacoOrder();
    order.setId("ORDER1");
    order.setUser(user);
    order.setDeliveryCity("City");

    when(repo.findById("ORDER1")).thenReturn(Mono.just(order));
    when(repo.save(any(TacoOrder.class))).thenAnswer(i -> Mono.just(i.getArgument(0)));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser-2", controller);

    String json = "{\"deliveryCity\":\"New City\"}";

    testClient.put()
        .uri("/api/orders/ORDER1")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(json)
        .exchange()
        .expectStatus().isForbidden();
  }

  @Test
  public void shouldDeleteOrderNoContent() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    User user = new User(
      "testuser",
      "pass123",
      "Angel Lopez",
      "street123",
      "Ags",
      "goodstate",
      "12345",
      "123-456-7890",
      "testuser@example.com"
    );
    TacoOrder order = new TacoOrder();
    order.setId("ORDER1");
    order.setUser(user);
    order.setDeliveryCity("City");

    when(repo.findById("ORDER1")).thenReturn(Mono.just(order));
    when(repo.deleteById("ORDER1")).thenReturn(Mono.empty());

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser", controller);

    testClient.delete()
        .uri("/api/orders/ORDER1")
        .exchange()
        .expectStatus().isNoContent();
  }

  @Test
  public void shouldDeleteOrderNotFound() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    when(repo.findById("ORDER2")).thenReturn(Mono.empty());

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser", controller);

    testClient.delete()
        .uri("/api/orders/ORDER2")
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test
  public void shouldDeleteOrderForbidden() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    User user = new User(
      "testuser",
      "pass123",
      "Angel Lopez",
      "street123",
      "Ags",
      "goodstate",
      "12345",
      "123-456-7890",
      "testuser@example.com"
    );
    TacoOrder order = new TacoOrder();
    order.setId("ORDER1");
    order.setUser(user);
    order.setDeliveryCity("City");

    when(repo.findById("ORDER1")).thenReturn(Mono.just(order));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser-2", controller);

    testClient.delete()
        .uri("/api/orders/ORDER1")
        .exchange()
        .expectStatus().isForbidden();
  }
}