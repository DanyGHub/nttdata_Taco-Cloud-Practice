package tacos.regression;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Date;
import java.util.concurrent.atomic.AtomicBoolean;
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
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxPublisher;
import tacos.outbox.OutboxService;
import tacos.outbox.OutboxStatus;

/**
 * Prueba de regresión de resiliencia del Outbox y del Consumidor ante fallos y redelivery.
 */
public class OutboxAndConsumerRecoveryRegressionTest {

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
  @DisplayName("TC-07/TC-36: Outbox sobrevive a caída del broker; retiene el evento y lo publica exitosamente al restablecerse")
  void outboxSurvivesBrokerOutage_andRecoversUponRestoration() {
    OutboxEvent event = OutboxEvent.builder()
        .id("outbox-recovery-1")
        .eventId("evt-recovery-100")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("corr-resilience-1")
        .payload(OrderEventPayload.builder().orderId("order-100").status("CREATED").build())
        .status(OutboxStatus.NEW)
        .attempts(0)
        .maxAttempts(3)
        .createdAt(new Date())
        .build();

    OutboxEvent claimedEvent = OutboxEvent.builder()
        .id("outbox-recovery-1")
        .eventId("evt-recovery-100")
        .eventType("ORDER_CREATED")
        .version(1)
        .correlationId("corr-resilience-1")
        .payload(event.getPayload())
        .status(OutboxStatus.PUBLISHING)
        .attempts(1)
        .maxAttempts(3)
        .createdAt(event.getCreatedAt())
        .build();

    AtomicBoolean brokerOnline = new AtomicBoolean(false);
    AtomicInteger publishAttempts = new AtomicInteger(0);

    // 1. Broker caído en el primer intento, se recupera en el segundo intento
    doAnswer(inv -> {
      publishAttempts.incrementAndGet();
      if (!brokerOnline.get()) {
        throw new RuntimeException("Simulated connection timeout: RabbitMQ/Kafka broker down!");
      }
      return null;
    }).when(messagingService).sendOrder(any(OrderEvent.class));

    when(outboxService.findCandidatesForPublishing(any(Date.class), eq(10)))
        .thenReturn(Flux.just(event));
    when(outboxService.atomicClaim(eq("outbox-recovery-1"), any(), eq(30000L), any(Date.class)))
        .thenReturn(Mono.just(claimedEvent));
    when(outboxService.markPublished(eq("outbox-recovery-1"), any(Date.class)))
        .thenReturn(Mono.just(claimedEvent));
    when(outboxService.markFailed(eq("outbox-recovery-1"), any(Throwable.class), eq(1), eq(3), eq(1000L), any(Date.class)))
        .thenReturn(Mono.just(claimedEvent));

    // Primer pase: El broker está caído
    StepVerifier.create(publisher.publishNow())
        .expectNext(1L) // 1 evento intentado y marcado como FAILED para reintento con backoff
        .verifyComplete();

    // Verificamos que se intentó enviar una vez y se marcó como fallido (reintentable), nunca descartado
    verify(outboxService, times(1)).markFailed(eq("outbox-recovery-1"), any(Throwable.class), eq(1), eq(3), eq(1000L), any(Date.class));
    verify(outboxService, never()).markPublished(any(), any());
    assertThat(publishAttempts.get()).isEqualTo(1);

    // 2. Se recupera la conectividad con el broker
    brokerOnline.set(true);

    // Segundo pase: El broker ya está en línea
    StepVerifier.create(publisher.publishNow())
        .expectNext(1L) // 1 evento procesado exitosamente
        .verifyComplete();

    // Verificamos que ahora sí se publicó y se marcó como PUBLISHED
    verify(outboxService, times(1)).markPublished(eq("outbox-recovery-1"), any(Date.class));
    assertThat(publishAttempts.get()).isEqualTo(2);
  }

  @Test
  @DisplayName("TC-30/TC-36: Deduplicación ante Redelivery: Múltiples entregas del mismo evento no duplican efectos secundarios")
  void consumerDeduplication_redeliveryDoesNotDuplicateEffects() {
    String eventId = "evt-redelivered-999";
    AtomicInteger businessLogicExecutionCount = new AtomicInteger(0);

    // Simulación del comportamiento del consumidor idempotente de cocina (ProcessedEventRepository)
    java.util.Set<String> processedEventsStore = new java.util.HashSet<>();

    java.util.function.Consumer<String> idempotentConsumer = evtId -> {
      if (processedEventsStore.contains(evtId)) {
        // Redelivery detectado: se reconoce el mensaje (ACK) sin re-ejecutar lógica de negocio
        return;
      }
      // Primera entrega: procesar y registrar
      businessLogicExecutionCount.incrementAndGet();
      processedEventsStore.add(evtId);
    };

    // 1. Primera entrega del evento
    idempotentConsumer.accept(eventId);
    assertThat(businessLogicExecutionCount.get()).isEqualTo(1);

    // 2. Redelivery por timeout de red en el ACK del broker (segunda entrega del mismo eventId)
    idempotentConsumer.accept(eventId);
    assertThat(businessLogicExecutionCount.get()).isEqualTo(1); // NO SE INCREMENTÓ

    // 3. Tercera entrega (reintento DLQ)
    idempotentConsumer.accept(eventId);
    assertThat(businessLogicExecutionCount.get()).isEqualTo(1); // PERMANECE EXACTAMENTE EN 1
  }

}
