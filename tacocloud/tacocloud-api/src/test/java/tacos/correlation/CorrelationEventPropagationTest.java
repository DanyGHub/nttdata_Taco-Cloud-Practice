package tacos.correlation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Ingredient;
import tacos.Ingredient.Type;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;
import tacos.order.OrderEventMapper;
import tacos.order.OrderPlacementService;
import tacos.order.OrderStatus;
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxService;

public class CorrelationEventPropagationTest {

  private OrderRepository orderRepo;
  private OutboxService outboxService;
  private OrderEventMapper eventMapper;
  private OrderPlacementService placementService;

  @BeforeEach
  public void setUp() {
    CorrelationContext.clear();
    orderRepo = mock(OrderRepository.class);
    outboxService = mock(OutboxService.class);
    eventMapper = new OrderEventMapper();
    placementService = new OrderPlacementService(
        orderRepo,
        outboxService,
        null, // transactionalOperator
        eventMapper,
        false, // transactionsEnabled
        5 // maxRetries
    );
  }

  @AfterEach
  public void tearDown() {
    CorrelationContext.clear();
  }

  @Test
  @DisplayName("OrderEventMapper propaga correlationId del contexto y NO usa el orderId")
  public void orderEventMapper_shouldUseCorrelationIdAndNotOrderId() {
    String expectedCorrelationId = "corr-event-mapper-12345";
    CorrelationContext.set(expectedCorrelationId);

    TacoOrder order = new TacoOrder();
    order.setId("ORDER_DO_NOT_USE_AS_CORRELATION");
    order.setStatus(OrderStatus.CREATED);
    order.setDeliveryName("John Doe");

    OrderEvent createdEvent = eventMapper.toOrderCreatedEvent(order);

    assertThat(createdEvent.getCorrelationId()).isEqualTo(expectedCorrelationId);
    assertThat(createdEvent.getCorrelationId()).isNotEqualTo(order.getId());
    assertThat(createdEvent.getPayload().getOrderId()).isEqualTo("ORDER_DO_NOT_USE_AS_CORRELATION");

    OrderEvent statusEvent = eventMapper.toStatusChangedEvent(order, OrderStatus.CREATED);
    assertThat(statusEvent.getCorrelationId()).isEqualTo(expectedCorrelationId);
    assertThat(statusEvent.getCorrelationId()).isNotEqualTo(order.getId());

    OrderEvent cancelledEvent = eventMapper.toOrderCancelledEvent(order, "Cancelled by user");
    assertThat(cancelledEvent.getCorrelationId()).isEqualTo(expectedCorrelationId);
    assertThat(cancelledEvent.getCorrelationId()).isNotEqualTo(order.getId());
  }

  @Test
  @DisplayName("OrderPlacementService persiste OutboxEvent con correlationId y lo adjunta a Reactor Context")
  public void orderPlacementService_shouldPropagateCorrelationIdToOutboxAndReactorContext() {
    String expectedCorrelationId = "corr-outbox-chain-9999";
    CorrelationContext.set(expectedCorrelationId);

    TacoOrder order = new TacoOrder();
    order.setId("ORDER_OUTBOX_1");
    order.setStatus(OrderStatus.CREATED);
    order.setDeliveryName("Alice Smith");

    when(orderRepo.save(any(TacoOrder.class))).thenReturn(Mono.just(order));

    ArgumentCaptor<OutboxEvent> outboxCaptor = ArgumentCaptor.forClass(OutboxEvent.class);
    when(outboxService.save(outboxCaptor.capture())).thenAnswer(inv -> {
      OutboxEvent evt = inv.getArgument(0);
      evt.setId("outbox-id-123");
      return Mono.just(evt);
    });

    AtomicReference<String> reactorContextCorrId = new AtomicReference<>();

    Mono<TacoOrder> pipeline = placementService.placeOrder(order)
        .flatMap(saved -> Mono.deferContextual(ctx -> {
          reactorContextCorrId.set(CorrelationContext.extractFrom(ctx));
          return Mono.just(saved);
        }));

    StepVerifier.create(pipeline)
        .expectNext(order)
        .verifyComplete();

    // 1. Verifica que el OutboxEvent guardado contiene el correlationId y NO el orderId
    OutboxEvent savedOutbox = outboxCaptor.getValue();
    assertThat(savedOutbox).isNotNull();
    assertThat(savedOutbox.getCorrelationId()).isEqualTo(expectedCorrelationId);
    assertThat(savedOutbox.getCorrelationId()).isNotEqualTo(order.getId());
    assertThat(savedOutbox.getPayload().getOrderId()).isEqualTo("ORDER_OUTBOX_1");

    // 2. Verifica que el Reactor Context preservó el correlationId en la cadena reactiva
    assertThat(reactorContextCorrId.get()).isEqualTo(expectedCorrelationId);
  }

  @Test
  @DisplayName("OrderEventMapper genera nuevo UUID cuando el contexto está vacío")
  public void orderEventMapper_shouldGenerateUuidWhenContextEmpty() {
    CorrelationContext.clear();

    TacoOrder order = new TacoOrder();
    order.setId("ORDER_RANDOM_UUID");

    OrderEvent event = eventMapper.toOrderCreatedEvent(order);

    assertThat(event.getCorrelationId()).isNotNull();
    assertThat(event.getCorrelationId()).isNotEqualTo(order.getId());
    assertThat(java.util.UUID.fromString(event.getCorrelationId())).isNotNull();
  }

}
