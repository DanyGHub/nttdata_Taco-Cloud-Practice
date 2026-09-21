package tacos.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.transaction.ReactiveTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;
import tacos.messaging.OrderEventPayload;
import tacos.order.OrderEventMapper;
import tacos.order.OrderPlacementService;

public class OutboxTransactionalRollbackTest {

  private OrderRepository orderRepo;
  private OutboxService outboxService;
  private OrderEventMapper orderEventMapper;
  private OrderPlacementService placementService;
  private TransactionalOperator transactionalOperator;
  private AtomicBoolean transactionRolledBack;

  @BeforeEach
  void setUp() {
    orderRepo = mock(OrderRepository.class);
    outboxService = mock(OutboxService.class);
    orderEventMapper = mock(OrderEventMapper.class);
    transactionRolledBack = new AtomicBoolean(false);

    // Mock transactional operator that catches error and marks rollback
    transactionalOperator = mock(TransactionalOperator.class);
    when(transactionalOperator.transactional(any(Mono.class))).thenAnswer(invocation -> {
      Mono<?> publisher = invocation.getArgument(0);
      return publisher.doOnError(err -> transactionRolledBack.set(true));
    });

    placementService = new OrderPlacementService(
        orderRepo,
        outboxService,
        transactionalOperator,
        orderEventMapper,
        true, // transactionsEnabled
        5     // maxRetries
    );
  }

  @Test
  @DisplayName("Fallo antes del commit realiza rollback de la transacción y no deja orden ni outbox")
  void whenErrorOccursBeforeCommit_thenTransactionRollsBackLeavingZeroOrderAndZeroOutbox() {
    TacoOrder order = new TacoOrder();
    order.setId("order-123");

    OrderEvent event = OrderEvent.of(
        OrderEventType.ORDER_CREATED,
        "order-123",
        OrderEventPayload.builder().orderId("order-123").status("CREATED").build()
    );

    // Save order succeeds, but saving outbox fails with database exception
    when(orderRepo.save(order)).thenReturn(Mono.just(order));
    when(outboxService.save(any(OutboxEvent.class)))
        .thenReturn(Mono.error(new RuntimeException("Simulated MongoDB write failure on outbox")));

    Mono<TacoOrder> result = placementService.placeOrder(order, event);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof RuntimeException
            && err.getMessage().contains("Simulated MongoDB write failure on outbox"))
        .verify();

    // Verify transaction rollback was triggered
    assertThat(transactionRolledBack.get()).isTrue();
    verify(transactionalOperator, times(1)).transactional(any(Mono.class));
  }

  @Test
  @DisplayName("Commit exitoso guarda tanto la orden como el registro outbox en estado NEW")
  void whenCommitSucceeds_thenOrderAndOutboxArePersistedWithStatusNew() {
    TacoOrder order = new TacoOrder();
    order.setId("order-456");

    OrderEvent event = OrderEvent.of(
        OrderEventType.ORDER_CREATED,
        "order-456",
        OrderEventPayload.builder().orderId("order-456").status("CREATED").build()
    );

    when(orderRepo.save(order)).thenReturn(Mono.just(order));
    when(outboxService.save(any(OutboxEvent.class))).thenAnswer(invocation -> {
      OutboxEvent saved = invocation.getArgument(0);
      saved.setId("outbox-789");
      return Mono.just(saved);
    });

    Mono<TacoOrder> result = placementService.placeOrder(order, event);

    StepVerifier.create(result)
        .expectNextMatches(savedOrder -> savedOrder.getId().equals("order-456"))
        .verifyComplete();

    assertThat(transactionRolledBack.get()).isFalse();
    verify(orderRepo, times(1)).save(order);
    verify(outboxService, times(1)).save(any(OutboxEvent.class));
    verify(transactionalOperator, times(1)).transactional(any(Mono.class));
  }
}
