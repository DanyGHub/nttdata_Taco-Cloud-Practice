package tacos.outbox;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.doThrow;
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

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventPayload;
import tacos.messaging.OrderMessagingService;

public class OutboxBrokerFailureAndRecoveryTest {

  private OutboxService outboxService;
  private OrderMessagingService messagingService;
  private OutboxPublisher publisher;

  @BeforeEach
  void setUp() {
    outboxService = mock(OutboxService.class);
    messagingService = mock(OrderMessagingService.class);

    publisher = new OutboxPublisher(
        outboxService,
        messagingService,
        true,   // enabled
        10,     // batchSize
        30000,  // lockDurationMs
        3,      // maxRetries
        1000    // backoffMultiplierMs
    );
  }

  @Test
  @DisplayName("Fallo del broker mantiene el evento reintentable con estado FAILED y nextAttemptAt con backoff")
  void whenBrokerFails_thenEventRemainsRetryableAndMarkedFailed() {
    OutboxEvent event = OutboxEvent.builder()
        .id("outbox-1")
        .eventId("evt-1")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("order-1")
        .payload(OrderEventPayload.builder().orderId("order-1").status("CREATED").build())
        .status(OutboxStatus.NEW)
        .attempts(0)
        .maxAttempts(3)
        .createdAt(new Date())
        .build();

    when(outboxService.findCandidatesForPublishing(any(Date.class), eq(10)))
        .thenReturn(Flux.just(event));

    OutboxEvent claimedEvent = OutboxEvent.builder()
        .id("outbox-1")
        .eventId("evt-1")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("order-1")
        .payload(event.getPayload())
        .status(OutboxStatus.PUBLISHING)
        .attempts(1)
        .maxAttempts(3)
        .createdAt(event.getCreatedAt())
        .build();

    when(outboxService.atomicClaim(eq("outbox-1"), any(String.class), eq(30000L), any(Date.class)))
        .thenReturn(Mono.just(claimedEvent));

    // Broker fails with network exception
    doThrow(new RuntimeException("RabbitMQ connection refused: broker down"))
        .when(messagingService).sendOrder(any(OrderEvent.class));

    when(outboxService.markFailed(eq("outbox-1"), any(Throwable.class), eq(1), eq(3), eq(1000L), any(Date.class)))
        .thenReturn(Mono.just(claimedEvent));

    StepVerifier.create(publisher.publishNow())
        .expectNext(1L)
        .verifyComplete();

    // Verify broker was attempted and markFailed was recorded
    verify(messagingService, times(1)).sendOrder(any(OrderEvent.class));
    verify(outboxService, times(1)).markFailed(eq("outbox-1"), any(Throwable.class), eq(1), eq(3), eq(1000L), any(Date.class));
    verify(outboxService, never()).markPublished(any(), any());
  }

  @Test
  @DisplayName("Broker recuperado despacha el evento y actualiza estado a PUBLISHED")
  void whenBrokerRecovers_thenEventIsSuccessfullyDispatchedAndMarkedPublished() {
    OutboxEvent event = OutboxEvent.builder()
        .id("outbox-2")
        .eventId("evt-2")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("order-2")
        .payload(OrderEventPayload.builder().orderId("order-2").status("CREATED").build())
        .status(OutboxStatus.FAILED)
        .attempts(1)
        .maxAttempts(3)
        .createdAt(new Date())
        .build();

    when(outboxService.findCandidatesForPublishing(any(Date.class), eq(10)))
        .thenReturn(Flux.just(event));

    OutboxEvent claimedEvent = OutboxEvent.builder()
        .id("outbox-2")
        .eventId("evt-2")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("order-2")
        .payload(event.getPayload())
        .status(OutboxStatus.PUBLISHING)
        .attempts(2)
        .maxAttempts(3)
        .createdAt(event.getCreatedAt())
        .build();

    when(outboxService.atomicClaim(eq("outbox-2"), any(String.class), eq(30000L), any(Date.class)))
        .thenReturn(Mono.just(claimedEvent));

    OutboxEvent publishedEvent = OutboxEvent.builder()
        .id("outbox-2")
        .status(OutboxStatus.PUBLISHED)
        .build();

    when(outboxService.markPublished(eq("outbox-2"), any(Date.class)))
        .thenReturn(Mono.just(publishedEvent));

    StepVerifier.create(publisher.publishNow())
        .expectNext(1L)
        .verifyComplete();

    // Verify successful publish
    verify(messagingService, times(1)).sendOrder(any(OrderEvent.class));
    verify(outboxService, times(1)).markPublished(eq("outbox-2"), any(Date.class));
  }
}
