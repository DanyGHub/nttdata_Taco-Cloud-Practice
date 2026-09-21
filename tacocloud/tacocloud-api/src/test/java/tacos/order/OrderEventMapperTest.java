package tacos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Date;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderMessagingService;
import tacos.messaging.OrderEventType;
import tacos.web.api.dto.OrderCancelRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderStatusUpdateRequest;

public class OrderEventMapperTest {

  private OrderEventMapper mapper;

  @BeforeEach
  public void setUp() {
    mapper = new OrderEventMapper();
  }

  @Test
  @DisplayName("toOrderCreatedEvent debe mapear correctamente los datos seguros de TacoOrder a OrderEvent")
  public void testToOrderCreatedEvent() {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER_999");
    order.setStatus(OrderStatus.CREATED);
    order.setDeliveryName("Alice Wonderland");
    order.setDeliveryStreet("789 Rabbit Hole");
    order.setDeliveryCity("Wonderland");
    order.setDeliveryState("WL");
    order.setDeliveryZip("12345");
    order.setPlacedAt(new Date());

    Taco taco = new Taco();
    taco.setName("Cheesy Taco");
    Ingredient ing1 = new Ingredient("FLTO", "Flour Tortilla", Type.WRAP);
    Ingredient ing2 = new Ingredient("CHED", "Cheddar Cheese", Type.CHEESE);
    taco.setIngredients(Arrays.asList(ing1, ing2));
    order.setTacos(Arrays.asList(taco));

    OrderEvent event = mapper.toOrderCreatedEvent(order);

    assertThat(event).isNotNull();
    assertThat(event.getEventId()).isNotNull();
    assertThat(UUID.fromString(event.getEventId())).isNotNull();
    assertThat(event.getEventType()).isEqualTo(OrderEventType.ORDER_CREATED);
    assertThat(event.getVersion()).isEqualTo(1);
    assertThat(event.getCorrelationId()).isEqualTo("ORDER_999");
    assertThat(event.getPayload()).isNotNull();
    assertThat(event.getPayload().getOrderId()).isEqualTo("ORDER_999");
    assertThat(event.getPayload().getStatus()).isEqualTo("CREATED");
    assertThat(event.getPayload().getCustomerName()).isEqualTo("Alice Wonderland");
    assertThat(event.getPayload().getItems()).hasSize(1);
    assertThat(event.getPayload().getItems().get(0).getTacoName()).isEqualTo("Cheesy Taco");
    assertThat(event.getPayload().getItems().get(0).getIngredients()).hasSize(2);
  }

  @Test
  @DisplayName("toStatusChangedEvent debe mapear el estado anterior y el nuevo estado")
  public void testToStatusChangedEvent() {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER_888");
    order.setStatus(OrderStatus.PREPARING);
    order.setDeliveryName("Bob Builder");

    OrderEvent event = mapper.toStatusChangedEvent(order, OrderStatus.ACCEPTED);

    assertThat(event).isNotNull();
    assertThat(event.getEventType()).isEqualTo(OrderEventType.ORDER_STATUS_CHANGED);
    assertThat(event.getCorrelationId()).isEqualTo("ORDER_888");
    assertThat(event.getPayload().getStatus()).isEqualTo("PREPARING");
    assertThat(event.getPayload().getPreviousStatus()).isEqualTo("ACCEPTED");
  }

  @Test
  @DisplayName("toOrderCancelledEvent debe mapear la razón de cancelación")
  public void testToOrderCancelledEvent() {
    TacoOrder order = new TacoOrder();
    order.setId("ORDER_777");
    order.setStatus(OrderStatus.CANCELLED);
    order.setDeliveryName("Charlie Brown");

    OrderEvent event = mapper.toOrderCancelledEvent(order, "Customer changed mind");

    assertThat(event).isNotNull();
    assertThat(event.getEventType()).isEqualTo(OrderEventType.ORDER_CANCELLED);
    assertThat(event.getCorrelationId()).isEqualTo("ORDER_777");
    assertThat(event.getPayload().getStatus()).isEqualTo("CANCELLED");
    assertThat(event.getPayload().getCancellationReason()).isEqualTo("Customer changed mind");
  }

  @Test
  @DisplayName("OrderWorkflowService debe publicar eventos canónicos al cambiar estado y al cancelar")
  public void testOrderWorkflowServiceEmitsEvents() {
    OrderRepository repo = mock(OrderRepository.class);
    InventoryService inventory = mock(InventoryService.class);
    OrderMessagingService messaging = mock(OrderMessagingService.class);
    OrderMapper orderMapper = new OrderMapper();

    OrderWorkflowService workflowService = new OrderWorkflowService(repo, inventory, orderMapper, messaging, mapper);

    TacoOrder order = new TacoOrder();
    order.setId("ORD-WF-1");
    order.setStatus(OrderStatus.CREATED);
    order.setDeliveryName("Eve Chef");
    User user = new User();
    user.setUsername("eve");
    user.setFullname("Eve Chef");
    order.setUser(user);

    when(repo.findById("ORD-WF-1")).thenReturn(Mono.just(order));
    when(repo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    Authentication kitchenAuth = mock(Authentication.class);
    when(kitchenAuth.getName()).thenReturn("chef1");
    when(kitchenAuth.getAuthorities()).thenAnswer(inv -> Arrays.asList(new SimpleGrantedAuthority("ROLE_KITCHEN")));

    // 1. Actualizar estado CREATED -> ACCEPTED
    OrderStatusUpdateRequest updateReq = new OrderStatusUpdateRequest();
    updateReq.setStatus(OrderStatus.ACCEPTED);
    updateReq.setReason("Kitchen accepted");

    StepVerifier.create(workflowService.updateOrderStatus("ORD-WF-1", updateReq, kitchenAuth))
        .assertNext(resp -> {
          assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
          assertThat(resp.getBody().getStatus()).isEqualTo(OrderStatus.ACCEPTED);
        })
        .verifyComplete();

    verify(messaging, times(1)).sendOrder(argThat(evt ->
        evt.getEventType() == OrderEventType.ORDER_STATUS_CHANGED &&
        "ACCEPTED".equals(evt.getPayload().getStatus()) &&
        "CREATED".equals(evt.getPayload().getPreviousStatus())
    ));

    // 2. Cancelar orden con rol ADMIN
    Authentication adminAuth = mock(Authentication.class);
    when(adminAuth.getName()).thenReturn("admin");
    when(adminAuth.getAuthorities()).thenAnswer(inv -> Arrays.asList(new SimpleGrantedAuthority("ROLE_ADMIN")));
    when(inventory.releaseForOrder("ORD-WF-1")).thenReturn(Mono.empty());

    OrderCancelRequest cancelReq = new OrderCancelRequest();
    cancelReq.setReason("Admin cancellation");

    StepVerifier.create(workflowService.cancelOrder("ORD-WF-1", cancelReq, adminAuth))
        .assertNext(resp -> {
          assertThat(resp.getStatusCode().is2xxSuccessful()).isTrue();
          assertThat(resp.getBody().getStatus()).isEqualTo(OrderStatus.CANCELLED);
        })
        .verifyComplete();

    verify(messaging, times(1)).sendOrder(argThat(evt ->
        evt.getEventType() == OrderEventType.ORDER_CANCELLED &&
        "CANCELLED".equals(evt.getPayload().getStatus()) &&
        "Admin cancellation".equals(evt.getPayload().getCancellationReason())
    ));
  }

}
