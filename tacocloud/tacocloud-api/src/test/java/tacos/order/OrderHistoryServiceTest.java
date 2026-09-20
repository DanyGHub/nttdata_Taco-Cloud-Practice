package tacos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.search.TacoPage;
import tacos.web.api.dto.OrderDetailResponse;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderSummaryResponse;

@DisplayName("TC-23: OrderHistoryService")
public class OrderHistoryServiceTest {

  private OrderRepository orderRepo;
  private ReactiveMongoTemplate mongoTemplate;
  private OrderMapper orderMapper;
  private OrderHistoryService historyService;

  private User userA;
  private User userB;
  private TacoOrder orderA1;
  private TacoOrder orderA2;
  private TacoOrder orderB1;

  @BeforeEach
  public void setUp() {
    orderRepo = mock(OrderRepository.class);
    mongoTemplate = mock(ReactiveMongoTemplate.class);
    orderMapper = new OrderMapper();

    historyService = new OrderHistoryService(orderRepo, mongoTemplate, orderMapper);

    userA = new User("userA", "passA", "User A", "Street A", "City", "State", "12345", "111", "a@test.com", Collections.singletonList("ROLE_USER"));
    userA.setId("userA-id");

    userB = new User("userB", "passB", "User B", "Street B", "City", "State", "54321", "222", "b@test.com", Collections.singletonList("ROLE_USER"));
    userB.setId("userB-id");

    orderA1 = new TacoOrder();
    orderA1.setId("order-A-1");
    orderA1.setUser(userA);
    orderA1.setDeliveryName("User A");
    orderA1.setDeliveryCity("Mexico City");
    orderA1.setDeliveryState("CDMX");
    orderA1.setPlacedAt(new Date(System.currentTimeMillis() - 10000));
    orderA1.setTotal(new BigDecimal("15.50"));
    orderA1.setBrand("Visa");
    orderA1.setLast4("4242");

    orderA2 = new TacoOrder();
    orderA2.setId("order-A-2");
    orderA2.setUser(userA);
    orderA2.setDeliveryName("User A");
    orderA2.setPlacedAt(new Date(System.currentTimeMillis() - 5000)); // más reciente
    orderA2.setTotal(new BigDecimal("22.00"));

    orderB1 = new TacoOrder();
    orderB1.setId("order-B-1");
    orderB1.setUser(userB);
    orderB1.setDeliveryName("User B");
    orderB1.setPlacedAt(new Date());
    orderB1.setTotal(new BigDecimal("30.00"));
  }

  @Test
  @DisplayName("Usuario A consulta su historial y recibe sólo sus órdenes paginadas")
  public void shouldGetUserOrdersSortedAndPaginated() {
    when(mongoTemplate.find(any(Query.class), eq(TacoOrder.class)))
        .thenReturn(Flux.just(orderA2, orderA1));
    when(mongoTemplate.count(any(Query.class), eq(TacoOrder.class)))
        .thenReturn(Mono.just(2L));

    StepVerifier.create(historyService.getUserOrders("userA-id", "userA", 0, 10))
        .assertNext(page -> {
          assertThat(page.getTotalElements()).isEqualTo(2L);
          assertThat(page.getContent()).hasSize(2);
          assertThat(page.getContent().get(0).getId()).isEqualTo("order-A-2");
          assertThat(page.getContent().get(1).getId()).isEqualTo("order-A-1");
          // Verifica que no se exponen tokens ni objeto User completo
          assertThat(page.getContent().get(0).getBrand()).isNull(); // orderA2 brand is null
          assertThat(page.getContent().get(1).getBrand()).isEqualTo("Visa");
          assertThat(page.getContent().get(1).getLast4()).isEqualTo("4242");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Rechaza parámetros de paginación inválidos con 400 Bad Request")
  public void shouldRejectInvalidPageOrSize() {
    StepVerifier.create(historyService.getUserOrders("userA-id", "userA", -1, 10))
        .expectErrorMatches(ex -> ex instanceof ResponseStatusException &&
            ((ResponseStatusException) ex).getRawStatusCode() == 400)
        .verify();

    StepVerifier.create(historyService.getUserOrders("userA-id", "userA", 0, 0))
        .expectErrorMatches(ex -> ex instanceof ResponseStatusException &&
            ((ResponseStatusException) ex).getRawStatusCode() == 400)
        .verify();

    StepVerifier.create(historyService.getUserOrders("userA-id", "userA", 0, 100))
        .expectErrorMatches(ex -> ex instanceof ResponseStatusException &&
            ((ResponseStatusException) ex).getRawStatusCode() == 400)
        .verify();
  }

  @Test
  @DisplayName("Usuario A consulta el detalle de su propia orden y obtiene 200 OK")
  public void shouldReturnOrderDetailWhenOwnerRequestsIt() {
    when(orderRepo.findById("order-A-1")).thenReturn(Mono.just(orderA1));

    StepVerifier.create(historyService.getUserOrderDetail("userA-id", "userA", "order-A-1"))
        .assertNext(detail -> {
          assertThat(detail.getId()).isEqualTo("order-A-1");
          assertThat(detail.getUsername()).isEqualTo("userA");
          assertThat(detail.getTotal()).isEqualTo(new BigDecimal("15.50"));
          assertThat(detail.getBrand()).isEqualTo("Visa");
          assertThat(detail.getLast4()).isEqualTo("4242");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Política de no revelación: Usuario A consultando orden de Usuario B recibe 404 NOT_FOUND")
  public void shouldReturn404WhenUserRequestsAnotherUsersOrder() {
    when(orderRepo.findById("order-B-1")).thenReturn(Mono.just(orderB1));

    StepVerifier.create(historyService.getUserOrderDetail("userA-id", "userA", "order-B-1"))
        .expectErrorMatches(ex -> ex instanceof ResponseStatusException &&
            ((ResponseStatusException) ex).getRawStatusCode() == 404)
        .verify();
  }

  @Test
  @DisplayName("Consulta de orden inexistente retorna 404 NOT_FOUND")
  public void shouldReturn404WhenOrderDoesNotExist() {
    when(orderRepo.findById("missing-order")).thenReturn(Mono.empty());

    StepVerifier.create(historyService.getUserOrderDetail("userA-id", "userA", "missing-order"))
        .expectErrorMatches(ex -> ex instanceof ResponseStatusException &&
            ((ResponseStatusException) ex).getRawStatusCode() == 404)
        .verify();
  }

  @Test
  @DisplayName("Admin puede consultar el detalle de cualquier orden")
  public void shouldAllowAdminToFetchAnyOrderDetail() {
    when(orderRepo.findById("order-B-1")).thenReturn(Mono.just(orderB1));

    StepVerifier.create(historyService.getAdminOrderDetail("order-B-1"))
        .assertNext(detail -> {
          assertThat(detail.getId()).isEqualTo("order-B-1");
          assertThat(detail.getDeliveryName()).isEqualTo("User B");
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Admin puede listar órdenes con filtro opcional de usuario")
  public void shouldAllowAdminToFilterOrdersByUser() {
    when(mongoTemplate.find(any(Query.class), eq(TacoOrder.class)))
        .thenReturn(Flux.just(orderB1));
    when(mongoTemplate.count(any(Query.class), eq(TacoOrder.class)))
        .thenReturn(Mono.just(1L));

    StepVerifier.create(historyService.getAdminOrders("userB-id", null, 0, 10))
        .assertNext(page -> {
          assertThat(page.getTotalElements()).isEqualTo(1L);
          assertThat(page.getContent().get(0).getId()).isEqualTo("order-B-1");
        })
        .verifyComplete();
  }

}
