package tacos.order;

import java.util.Date;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.reactive.TransactionalOperator;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderEvent;
import tacos.metrics.TacoBusinessMetrics;
import tacos.outbox.OutboxEvent;
import tacos.outbox.OutboxService;
import tacos.outbox.OutboxStatus;

@Service
public class OrderPlacementService {

  private static final Logger log = LoggerFactory.getLogger(OrderPlacementService.class);

  private final OrderRepository orderRepo;
  private final OutboxService outboxService;
  private final TransactionalOperator transactionalOperator;
  private final OrderEventMapper orderEventMapper;
  private final boolean transactionsEnabled;
  private final int maxRetries;
  private final TacoBusinessMetrics metrics;

  @Autowired
  public OrderPlacementService(
      OrderRepository orderRepo,
      OutboxService outboxService,
      @Autowired(required = false) TransactionalOperator transactionalOperator,
      @Autowired(required = false) OrderEventMapper orderEventMapper,
      @Value("${tacocloud.mongo.transactions.enabled:true}") boolean transactionsEnabled,
      @Value("${tacocloud.outbox.max-retries:5}") int maxRetries,
      @Autowired(required = false) TacoBusinessMetrics metrics) {
    this.orderRepo = orderRepo;
    this.outboxService = outboxService;
    this.transactionalOperator = transactionalOperator;
    this.orderEventMapper = orderEventMapper != null ? orderEventMapper : new OrderEventMapper();
    this.transactionsEnabled = transactionsEnabled;
    this.maxRetries = maxRetries;
    this.metrics = metrics;
  }

  public OrderPlacementService(
      OrderRepository orderRepo,
      OutboxService outboxService,
      TransactionalOperator transactionalOperator,
      OrderEventMapper orderEventMapper,
      boolean transactionsEnabled,
      int maxRetries) {
    this(orderRepo, outboxService, transactionalOperator, orderEventMapper, transactionsEnabled, maxRetries, null);
  }

  public Mono<TacoOrder> placeOrder(TacoOrder order) {
    String correlationId = tacos.correlation.CorrelationContext.getOrGenerate();
    OrderEvent event = orderEventMapper.toOrderCreatedEvent(order, correlationId);
    return placeOrder(order, event);
  }

  public Mono<TacoOrder> placeOrder(TacoOrder order, OrderEvent event) {
    final long startTime = System.currentTimeMillis();
    String correlationId = (event != null && event.getCorrelationId() != null && !event.getCorrelationId().trim().isEmpty())
        ? event.getCorrelationId()
        : tacos.correlation.CorrelationContext.getOrGenerate();
    Date now = new Date();
    OutboxEvent outbox = OutboxEvent.builder()
        .eventId(event.getEventId())
        .eventType(event.getEventType().name())
        .version(event.getVersion())
        .correlationId(correlationId)
        .payload(event.getPayload())
        .status(OutboxStatus.NEW)
        .attempts(0)
        .maxAttempts(maxRetries)
        .createdAt(now)
        .updatedAt(now)
        .nextAttemptAt(now)
        .build();

    Mono<TacoOrder> savePipeline = orderRepo.save(order)
        .flatMap(savedOrder -> {
          outbox.setCorrelationId(correlationId);
          if (outbox.getPayload() != null && outbox.getPayload().getOrderId() == null) {
            outbox.getPayload().setOrderId(savedOrder.getId());
          }
          return outboxService.save(outbox)
              .doOnSuccess(savedOutbox -> log.info("Outbox event created with status NEW: id={}, eventId={}, correlationId={}",
                  savedOutbox.getId(), savedOutbox.getEventId(), savedOutbox.getCorrelationId()))
              .thenReturn(savedOrder);
        })
        .doOnSuccess(saved -> {
          if (metrics != null) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordPlacementTime(duration, "SUCCESS");
            metrics.recordOrderCreated("API", "CREATED");
            metrics.incrementOutboxBacklog();
          }
        })
        .doOnError(err -> {
          if (metrics != null) {
            long duration = System.currentTimeMillis() - startTime;
            metrics.recordPlacementTime(duration, "FAILURE");
            metrics.recordOrderFailed("API", err.getClass().getSimpleName());
          }
        })
        .contextWrite(ctx -> tacos.correlation.CorrelationContext.withCorrelationId(ctx, correlationId));

    if (transactionsEnabled && transactionalOperator != null) {
      log.debug("Executing placeOrder within reactive MongoDB transaction");
      return transactionalOperator.transactional(savePipeline);
    }

    return savePipeline;
  }

}
