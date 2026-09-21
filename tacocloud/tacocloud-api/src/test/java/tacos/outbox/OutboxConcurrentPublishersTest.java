package tacos.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderMessagingService;

public class OutboxConcurrentPublishersTest {

  private OutboxService outboxService;
  private OrderMessagingService messagingService;
  private OutboxPublisher publisher1;
  private OutboxPublisher publisher2;

  @BeforeEach
  void setUp() {
    outboxService = mock(OutboxService.class);
    messagingService = mock(OrderMessagingService.class);

    publisher1 = new OutboxPublisher(outboxService, messagingService, true, 10, 30000, 3, 1000);
    publisher2 = new OutboxPublisher(outboxService, messagingService, true, 10, 30000, 3, 1000);
  }

  @Test
  @DisplayName("Dos publishers concurrentes: el claim atómico asegura que solo una instancia despache el evento")
  void whenTwoPublishersCompete_thenOnlyOneClaimsAndDispatchesEvent() {
    OutboxEvent event = OutboxEvent.builder()
        .id("outbox-race-1")
        .eventId("evt-race-1")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("order-race-1")
        .payload(OrderEventPayload.builder().orderId("order-race-1").status("CREATED").build())
        .status(OutboxStatus.NEW)
        .attempts(0)
        .maxAttempts(3)
        .createdAt(new Date())
        .build();

    // Both publishers see candidate event
    when(outboxService.findCandidatesForPublishing(any(Date.class), eq(10)))
        .thenReturn(Flux.just(event));

    OutboxEvent claimedByPub1 = OutboxEvent.builder()
        .id("outbox-race-1")
        .eventId("evt-race-1")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("order-race-1")
        .payload(event.getPayload())
        .status(OutboxStatus.PUBLISHING)
        .lockedBy(publisher1.getInstanceId())
        .attempts(1)
        .maxAttempts(3)
        .createdAt(event.getCreatedAt())
        .build();

    // Publisher 1 wins atomic claim
    when(outboxService.atomicClaim(eq("outbox-race-1"), eq(publisher1.getInstanceId()), eq(30000L), any(Date.class)))
        .thenReturn(Mono.just(claimedByPub1));

    // Publisher 2 loses atomic claim (returns empty because findAndModify matched 0 due to status already PUBLISHING)
    when(outboxService.atomicClaim(eq("outbox-race-1"), eq(publisher2.getInstanceId()), eq(30000L), any(Date.class)))
        .thenReturn(Mono.empty());

    when(outboxService.markPublished(eq("outbox-race-1"), any(Date.class)))
        .thenReturn(Mono.just(claimedByPub1));

    // Execute publisher 1
    StepVerifier.create(publisher1.publishNow())
        .expectNext(1L)
        .verifyComplete();

    // Execute publisher 2
    StepVerifier.create(publisher2.publishNow())
        .expectNext(0L)
        .verifyComplete();

    // Verify only ONE dispatch occurred across both publishers
    verify(messagingService, times(1)).sendOrder(any(OrderEvent.class));
    verify(outboxService, times(1)).markPublished(eq("outbox-race-1"), any(Date.class));
  }
}
