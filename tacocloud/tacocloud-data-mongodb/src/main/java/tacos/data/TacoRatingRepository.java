package tacos.data;

import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.rating.TacoRating;

public interface TacoRatingRepository extends ReactiveMongoRepository<TacoRating, String> {

  Mono<TacoRating> findByUserIdAndTacoId(String userId, String tacoId);

  Flux<TacoRating> findByTacoId(String tacoId);

  Flux<TacoRating> findByUserId(String userId);

  Mono<Long> countByTacoId(String tacoId);

  Mono<Void> deleteByUserIdAndTacoId(String userId, String tacoId);

  Mono<Void> deleteByTacoId(String tacoId);

  Mono<Boolean> existsByUserIdAndTacoId(String userId, String tacoId);

}
