package tacos.outbox;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;

public interface OutboxEventRepository extends ReactiveMongoRepository<OutboxEvent, String> {

  Flux<OutboxEvent> findByStatus(OutboxStatus status);

  Flux<OutboxEvent> findByCorrelationId(String correlationId);

  Mono<OutboxEvent> findByEventId(String eventId);

}
