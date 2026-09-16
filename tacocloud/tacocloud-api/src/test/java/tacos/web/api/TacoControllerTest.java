package tacos.web.api;

import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.reactive.server.WebTestClient;
import org.springframework.validation.beanvalidation.LocalValidatorFactoryBean;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.support.WebExchangeBindException;
import org.springframework.http.HttpHeaders;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.data.TacoRepository;
import tacos.data.IngredientRepository;

import tacos.User;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.converter.HttpMessageNotReadableException;
import tacos.messaging.OrderMessagingService;


public class TacoControllerTest {

  @Test
  public void shouldReturnRecentTacos() {
    Taco[] tacos = {
        testTaco(1L), testTaco(2L),
        testTaco(3L), testTaco(4L),
        testTaco(5L), testTaco(6L),
        testTaco(7L), testTaco(8L),
        testTaco(9L), testTaco(10L),
        testTaco(11L), testTaco(12L),
        testTaco(13L), testTaco(14L),
        testTaco(15L), testTaco(16L)};
    Flux<Taco> tacoFlux = Flux.just(tacos);

    TacoRepository tacoRepo = Mockito.mock(TacoRepository.class);
    when(tacoRepo.findAll()).thenReturn(tacoFlux);

    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo))
        .build();

    testClient.get().uri("/api/tacos?recent")
      .exchange()
      .expectStatus().isOk()
      .expectBody()
        .jsonPath("$").isArray()
        .jsonPath("$").isNotEmpty()
        .jsonPath("$[0].id").isEqualTo(tacos[0].getId().toString())
        .jsonPath("$[0].name").isEqualTo("Taco 1")
        .jsonPath("$[1].id").isEqualTo(tacos[1].getId().toString())
        .jsonPath("$[1].name").isEqualTo("Taco 2")
        .jsonPath("$[11].id").isEqualTo(tacos[11].getId().toString())
        .jsonPath("$[11].name").isEqualTo("Taco 12")
        .jsonPath("$[12]").doesNotExist();
  }

  @Test
  public void shouldSaveATaco() {
    TacoRepository tacoRepo = Mockito.mock(
                TacoRepository.class);
    Mono<Taco> unsavedTacoMono = Mono.just(testTaco(null));
    Taco savedTaco = testTaco(null);
    Mono<Taco> savedTacoMono = Mono.just(savedTaco);

    when(tacoRepo.save(any())).thenReturn(savedTacoMono);

    WebTestClient testClient = WebTestClient.bindToController(
        new TacoController(tacoRepo)).build();

    testClient.post()
        .uri("/api/tacos")
        .contentType(MediaType.APPLICATION_JSON)
        .body(unsavedTacoMono, Taco.class)
      .exchange()
      .expectStatus().isCreated()
      .expectBody(Taco.class)
        .isEqualTo(savedTaco);
  }


  private Taco testTaco(Long number) {
    Taco taco = new Taco();
    taco.setId(number != null ? number.toString(): "TESTID");
    taco.setName("Taco " + number);
    List<Ingredient> ingredients = new ArrayList<>();
    ingredients.add(
        new Ingredient("INGA", "Ingredient A", Type.WRAP));
    ingredients.add(
        new Ingredient("INGB", "Ingredient B", Type.PROTEIN));
    taco.setIngredients(ingredients);
    return taco;
  }

  //OrderApiController tests
  
  // Create a WebTestClient with a mock principal for testing OrderApiController
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

  @Test
  public void shouldPatchOrderOk() {
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

    User user = new User(
      "testuser", 
      "pass123", 
      "Angel Lopez", 
      "street123", 
      "Ags", 
      "goodstate", 
      "12345", 
      "123-456-7890", 
      "testuser@example.com");
    
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
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

    User user = new User(
      "testuser", 
      "pass123", 
      "Angel Lopez", 
      "street123", 
      "Ags", 
      "goodstate", 
      "12345", 
      "123-456-7890", 
      "testuser@example.com");

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
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

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
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

    User user = new User(
      "testuser", 
      "pass123", 
      "Angel Lopez", 
      "street123", 
      "Ags", 
      "goodstate", 
      "12345", 
      "123-456-7890", 
      "testuser@example.com");

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

  @Test 
  public void shouldPutOrderOk(){
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

    User user = new User(
      "testuser", 
      "pass123", 
      "Angel Lopez", 
      "street123", 
      "Ags", 
      "goodstate", 
      "12345", 
      "123-456-7890", 
      "testuser@example.com");

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
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

    User user = new User(
      "testuser", 
      "pass123", 
      "Angel Lopez", 
      "street123", 
      "Ags", 
      "goodstate", 
      "12345", 
      "123-456-7890", 
      "testuser@example.com");

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
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

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
  public void shouldPutOrderForbidden(){
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

    User user = new User(
      "testuser", 
      "pass123", 
      "Angel Lopez", 
      "street123", 
      "Ags", 
      "goodstate", 
      "12345", 
      "123-456-7890", 
      "testuser@example.com");

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
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

    User user = new User(
      "testuser", 
      "pass123", 
      "Angel Lopez", 
      "street123", 
      "Ags", 
      "goodstate", 
      "12345", 
      "123-456-7890", 
      "testuser@example.com");

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
  public void shouldDeleteOrderNotFound(){
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

    User user = new User(
      "testuser", 
      "pass123", 
      "Angel Lopez", 
      "street123", 
      "Ags", 
      "goodstate", 
      "12345", 
      "123-456-7890", 
      "testuser@example.com");

    TacoOrder order = new TacoOrder();
    order.setId("ORDER1");
    order.setUser(user);
    order.setDeliveryCity("City");

    when(repo.findById("ORDER2")).thenReturn(Mono.empty());

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService);
    WebTestClient testClient = buildClientWithUser("testuser", controller);

    testClient.delete()
        .uri("/api/orders/ORDER2")
        .exchange()
        .expectStatus().isNotFound();
  }

  @Test 
  public void shouldDeleteOrderForbidden(){
    OrderRepository repo = Mockito.mock(OrderRepository.class);
    OrderMessagingService messagingService = Mockito.mock(OrderMessagingService.class);
    EmailOrderService emailService = Mockito.mock(EmailOrderService.class);

    User user = new User(
      "testuser", 
      "pass123", 
      "Angel Lopez", 
      "street123", 
      "Ags", 
      "goodstate", 
      "12345", 
      "123-456-7890", 
      "testuser@example.com");

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
