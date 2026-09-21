package tacos.outbox;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderMessagingService;

public class OutboxRestartResumptionTest {

  private OutboxService outboxService;
  private OrderMessagingService messagingService;
  private OutboxPublisher publisher;

  @BeforeEach
  void setUp() {
    outboxService = mock(OutboxService.class);
    messagingService = mock(OrderMessagingService.class);
    publisher = new OutboxPublisher(outboxService, messagingService, true, 10, 30000, 3, 1000);
  }

  @Test
  @DisplayName("Reinicio de aplicación: procesa pendientes NEW, FAILED reintentables y PUBLISHING con lock expirado")
  void whenApplicationRestarts_thenPendingAndExpiredLockEventsAreResumedAndPublished() {
    Date pastDate = new Date(System.currentTimeMillis() - 60000);

    OutboxEvent event1 = OutboxEvent.builder()
        .id("outbox-new")
        .eventId("evt-new")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("order-1")
        .payload(OrderEventPayload.builder().orderId("order-1").build())
        .status(OutboxStatus.NEW)
        .attempts(0)
        .maxAttempts(3)
        .createdAt(pastDate)
        .build();

    OutboxEvent event2 = OutboxEvent.builder()
        .id("outbox-failed-retryable")
        .eventId("evt-failed")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("order-2")
        .payload(OrderEventPayload.builder().orderId("order-2").build())
        .status(OutboxStatus.FAILED)
        .attempts(1)
        .maxAttempts(3)
        .nextAttemptAt(pastDate)
        .createdAt(pastDate)
        .build();

    OutboxEvent event3 = OutboxEvent.builder()
        .id("outbox-expired-lock")
        .eventId("evt-expired")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("order-3")
        .payload(OrderEventPayload.builder().orderId("order-3").build())
        .status(OutboxStatus.PUBLISHING)
        .lockedBy("dead-publisher-999")
        .lockedUntil(pastDate)
        .attempts(1)
        .maxAttempts(3)
        .createdAt(pastDate)
        .build();

    when(outboxService.findCandidatesForPublishing(any(Date.class), eq(10)))
        .thenReturn(Flux.just(event1, event2, event3));

    when(outboxService.atomicClaim(eq("outbox-new"), any(String.class), eq(30000L), any(Date.class)))
        .thenReturn(Mono.just(event1));
    when(outboxService.atomicClaim(eq("outbox-failed-retryable"), any(String.class), eq(30000L), any(Date.class)))
        .thenReturn(Mono.just(event2));
    when(outboxService.atomicClaim(eq("outbox-expired-lock"), any(String.class), eq(30000L), any(Date.class)))
        .thenReturn(Mono.just(event3));

    when(outboxService.markPublished(any(String.class), any(Date.class)))
        .thenAnswer(inv -> Mono.just(OutboxEvent.builder().id(inv.getArgument(0)).status(OutboxStatus.PUBLISHED).build()));

    // When publisher executes after reboot
    StepVerifier.create(publisher.publishNow())
        .expectNext(3L)
        .verifyComplete();

    // All 3 events dispatched to messaging service
    verify(messagingService, times(3)).sendOrder(any(OrderEvent.class));
    verify(outboxService, times(1)).markPublished(eq("outbox-new"), any(Date.class));
    verify(outboxService, times(1)).markPublished(eq("outbox-failed-retryable"), any(Date.class));
    verify(outboxService, times(1)).markPublished(eq("outbox-expired-lock"), any(Date.class));
  }
}
