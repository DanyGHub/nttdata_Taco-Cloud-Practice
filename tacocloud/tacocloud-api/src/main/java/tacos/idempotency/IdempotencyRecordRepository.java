package tacos.idempotency;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;
import org.springframework.stereotype.Repository;

import reactor.core.publisher.Mono;

@Repository
public interface IdempotencyRecordRepository extends ReactiveMongoRepository<IdempotencyRecord, String> {

  Mono<IdempotencyRecord> findByUserIdAndKey(String userId, String key);

  Mono<Void> deleteByUserIdAndKey(String userId, String key);

}
