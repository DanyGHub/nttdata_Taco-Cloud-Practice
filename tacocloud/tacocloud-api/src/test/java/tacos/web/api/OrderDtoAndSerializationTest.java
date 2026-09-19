package tacos.web.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Date;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.reactive.server.WebTestClient;

import com.fasterxml.jackson.databind.ObjectMapper;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.dto.IngredientMapper;
import tacos.web.api.dto.IngredientRequest;
import tacos.web.api.dto.IngredientResponse;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.TacoRequest;
import tacos.web.api.dto.TacoResponse;

public class OrderDtoAndSerializationTest {

  private final ObjectMapper objectMapper = new ObjectMapper();
  private final IngredientMapper ingredientMapper = new IngredientMapper();
  private final OrderMapper orderMapper = new OrderMapper(ingredientMapper);

  // 1. TC-08: Separar DTOs de entrada, respuesta y persistencia

  @Test
  public void negativeSerialization_shouldNotContainPasswordCcNumberCcCvvNorAuthorities() throws Exception {
    OrderResponse response = new OrderResponse();
    response.setId("ORDER_123");
    response.setPlacedAt(new Date());
    response.setDeliveryName("Craig Walls");
    response.setDeliveryStreet("123 Oak Street");
    response.setDeliveryCity("Dallas");
    response.setDeliveryState("TX");
    response.setDeliveryZip("75001");
    response.setUsername("cwalls");

    TacoResponse taco = new TacoResponse();
    taco.setId("TACO_1");
    taco.setName("Carnitas Taco");
    taco.setCreatedAt(new Date());
    taco.setIngredients(Arrays.asList(
        new IngredientResponse("FLTO", "Flour Tortilla", Type.WRAP),
        new IngredientResponse("CARN", "Carnitas", Type.PROTEIN)
    ));
    response.setTacos(Arrays.asList(taco));

    String json = objectMapper.writeValueAsString(response);

    assertThat(json).doesNotContain("password");
    assertThat(json).doesNotContain("ccNumber");
    assertThat(json).doesNotContain("ccCVV");
    assertThat(json).doesNotContain("authorities");
    assertThat(json).doesNotContain("accountNonExpired");
    assertThat(json).doesNotContain("credentialsNonExpired");
    assertThat(json).doesNotContain("enabled");

    assertThat(json).contains("ORDER_123");
    assertThat(json).contains("Craig Walls");
    assertThat(json).contains("Carnitas Taco");
    assertThat(json).contains("cwalls");
  }

  @Test
  public void orderMapper_shouldCorrectlyMapOrderCreateRequestToDomain() {
    OrderCreateRequest request = new OrderCreateRequest();
    request.setDeliveryName("Angel Lopez");
    request.setDeliveryStreet("Av. Universidad 940");
    request.setDeliveryCity("Aguascalientes");
    request.setDeliveryState("AGS");
    request.setDeliveryZip("20100");
    request.setCcNumber("1234567812345678");
    request.setCcExpiration("11/29");
    request.setCcCVV("987");

    TacoRequest tacoReq = new TacoRequest();
    tacoReq.setName("Super Taco");
    tacoReq.setIngredients(Arrays.asList(
        new IngredientRequest("FLTO", "Flour Tortilla", Type.WRAP),
        new IngredientRequest("GRBF", "Ground Beef", Type.PROTEIN)
    ));
    request.addTaco(tacoReq);

    TacoOrder domain = orderMapper.toDomain(request);

    assertThat(domain).isNotNull();
    assertThat(domain.getId()).isNull();
    assertThat(domain.getUser()).isNull();
    assertThat(domain.getDeliveryName()).isEqualTo("Angel Lopez");
    assertThat(domain.getDeliveryStreet()).isEqualTo("Av. Universidad 940");
    assertThat(domain.getDeliveryCity()).isEqualTo("Aguascalientes");
    assertThat(domain.getDeliveryState()).isEqualTo("AGS");
    assertThat(domain.getDeliveryZip()).isEqualTo("20100");
    assertThat(domain.getLast4()).isEqualTo("5678");

    assertThat(domain.getTacos()).hasSize(1);
    Taco taco = domain.getTacos().get(0);
    assertThat(taco.getName()).isEqualTo("Super Taco");
    assertThat(taco.getIngredients()).hasSize(2);
    assertThat(taco.getIngredients().get(0).getId()).isEqualTo("FLTO");
    assertThat(taco.getIngredients().get(1).getId()).isEqualTo("GRBF");
  }

  @Test
  public void orderMapper_shouldCorrectlyMapDomainToOrderResponseExcludingSensitiveData() {
    User user = new User(
      "admin", 
      "secretHashPass123", 
      "Admin User", 
      "Admin St", 
      "City", 
      "ST", 
      "12345", 
      "555", 
      "admin@tacos.com"
    );
    TacoOrder domain = new TacoOrder();
    domain.setId("ORDER_999");
    domain.setPlacedAt(new Date());
    domain.setUser(user);
    domain.setDeliveryName("Customer Name");
    domain.setDeliveryStreet("Delivery Street");
    domain.setDeliveryCity("Delivery City");
    domain.setDeliveryState("DS");
    domain.setDeliveryZip("99999");
    domain.setPaymentToken("tok_visa_12345678");
    domain.setBrand("VISA");
    domain.setLast4("1111");

    Taco taco = new Taco();
    taco.setId("T1");
    taco.setName("Taco Al Pastor");
    taco.setCreatedAt(new Date());
    taco.setIngredients(Arrays.asList(new Ingredient("CORN", "Corn Tortilla", Type.WRAP)));
    domain.addTaco(taco);

    OrderResponse response = orderMapper.toResponse(domain);

    assertThat(response).isNotNull();
    assertThat(response.getId()).isEqualTo("ORDER_999");
    assertThat(response.getDeliveryName()).isEqualTo("Customer Name");
    assertThat(response.getDeliveryStreet()).isEqualTo("Delivery Street");
    assertThat(response.getDeliveryCity()).isEqualTo("Delivery City");
    assertThat(response.getDeliveryState()).isEqualTo("DS");
    assertThat(response.getDeliveryZip()).isEqualTo("99999");
    assertThat(response.getUsername()).isEqualTo("admin");

    assertThat(response.getTacos()).hasSize(1);
    assertThat(response.getTacos().get(0).getName()).isEqualTo("Taco Al Pastor");
    assertThat(response.getTacos().get(0).getIngredients().get(0).getName()).isEqualTo("Corn Tortilla");
  }

  @Test
  public void ingredientMapper_shouldBidirectionallyMapPureFunctions() {
    IngredientRequest req = new IngredientRequest("TMTO", "Diced Tomatoes", Type.VEGGIES);
    Ingredient domain = ingredientMapper.toDomain(req);

    assertThat(domain.getId()).isEqualTo("TMTO");
    assertThat(domain.getName()).isEqualTo("Diced Tomatoes");
    assertThat(domain.getType()).isEqualTo(Type.VEGGIES);

    IngredientResponse resp = ingredientMapper.toResponse(domain);
    assertThat(resp.getId()).isEqualTo("TMTO");
    assertThat(resp.getName()).isEqualTo("Diced Tomatoes");
    assertThat(resp.getType()).isEqualTo(Type.VEGGIES);

    assertThat(ingredientMapper.toDomain((IngredientRequest) null)).isNull();
    assertThat(ingredientMapper.toResponse(null)).isNull();
  }

  @Test
  public void massAssignment_shouldIgnoreServerOwnedFieldsInRequestBody() throws Exception {
    String maliciousPayload = "{"
        + "\"id\":\"HACKED_ID\","
        + "\"placedAt\":\"2000-01-01T00:00:00.000Z\","
        + "\"status\":\"AUTHORIZED_FRAUD\","
        + "\"total\":0.0,"
        + "\"user\":{\"id\":\"VICTIM_ID\",\"username\":\"victim_user\",\"password\":\"stolen\"},"
        + "\"deliveryName\":\"Attacker Name\","
        + "\"deliveryStreet\":\"Shadow Street\","
        + "\"deliveryCity\":\"Ghost City\","
        + "\"deliveryState\":\"NA\","
        + "\"deliveryZip\":\"00000\","
        + "\"ccNumber\":\"4000000000000000\","
        + "\"ccExpiration\":\"01/30\","
        + "\"ccCVV\":\"000\","
        + "\"tacos\":[]"
        + "}";

    OrderCreateRequest request = objectMapper.readValue(maliciousPayload, OrderCreateRequest.class);

    TacoOrder domainOrder = orderMapper.toDomain(request);

    assertThat(domainOrder.getId()).isNull();
    assertThat(domainOrder.getUser()).isNull();
    assertThat(domainOrder.getDeliveryName()).isEqualTo("Attacker Name");
    assertThat(domainOrder.getDeliveryStreet()).isEqualTo("Shadow Street");
  }


  @Test
  public void controller_postOrder_shouldAcceptOrderCreateRequestAndReturnOrderResponse() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    TacoOrder savedOrder = new TacoOrder();
    savedOrder.setId("SAVED_ORDER_100");
    savedOrder.setDeliveryName("Jane Doe");
    savedOrder.setBrand("VISA");
    savedOrder.setLast4("1111");

    when(repo.save(any(TacoOrder.class))).thenReturn(Mono.just(savedOrder));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService, orderMapper);
    WebTestClient client = WebTestClient.bindToController(controller).build();

    String requestJson = "{"
        + "\"deliveryName\":\"Jane Doe\","
        + "\"deliveryStreet\":\"Main St 10\","
        + "\"deliveryCity\":\"Springfield\","
        + "\"deliveryState\":\"IL\","
        + "\"deliveryZip\":\"62701\","
        + "\"ccNumber\":\"4111111111111111\","
        + "\"ccExpiration\":\"08/29\","
        + "\"ccCVV\":\"123\","
        + "\"tacos\":[{\"name\":\"Carnitas Taco\",\"ingredients\":[{\"id\":\"FLTO\",\"name\":\"Flour Tortilla\",\"type\":\"WRAP\"}]}]"
        + "}";

    client.post()
        .uri("/api/orders")
        .contentType(MediaType.APPLICATION_JSON)
        .bodyValue(requestJson)
        .exchange()
        .expectStatus().isCreated()
        .expectBody()
        .jsonPath("$.id").isEqualTo("SAVED_ORDER_100")
        .jsonPath("$.deliveryName").isEqualTo("Jane Doe")
        .jsonPath("$.ccNumber").doesNotExist()
        .jsonPath("$.ccCVV").doesNotExist()
        .jsonPath("$.password").doesNotExist()
        .jsonPath("$.authorities").doesNotExist();

    verify(repo, times(1)).save(any(TacoOrder.class));
    verify(messagingService, times(1)).sendOrder(any(TacoOrder.class));
  }

  @Test
  public void controller_allOrders_shouldReturnFluxOfOrderResponsesWithoutSensitiveData() {
    OrderRepository repo = mock(OrderRepository.class);
    OrderMessagingService messagingService = mock(OrderMessagingService.class);
    EmailOrderService emailService = mock(EmailOrderService.class);

    User user = new User(
      "craig", 
      "myPasswordHash", 
      "Craig Walls", 
      "Street", 
      "City", 
      "ST", 
      "123", 
      "phone", 
      "email"
    );
    TacoOrder order1 = new TacoOrder();
    order1.setId("ORD_1");
    order1.setUser(user);
    order1.setDeliveryName("Craig Walls");
    order1.setBrand("VISA");
    order1.setLast4("4444");

    when(repo.findAll()).thenReturn(Flux.just(order1));

    OrderApiController controller = new OrderApiController(repo, messagingService, emailService, orderMapper);
    WebTestClient client = WebTestClient.bindToController(controller).build();

    client.get()
        .uri("/api/orders")
        .exchange()
        .expectStatus().isOk()
        .expectBody()
        .jsonPath("$[0].id").isEqualTo("ORD_1")
        .jsonPath("$[0].deliveryName").isEqualTo("Craig Walls")
        .jsonPath("$[0].username").isEqualTo("craig")
        .jsonPath("$[0].password").doesNotExist()
        .jsonPath("$[0].ccNumber").doesNotExist()
        .jsonPath("$[0].ccCVV").doesNotExist()
        .jsonPath("$[0].authorities").doesNotExist();
  }

}
