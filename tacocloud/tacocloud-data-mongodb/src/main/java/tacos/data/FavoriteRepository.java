package tacos.data;

import org.springframework.data.domain.Pageable;
import org.springframework.data.mongodb.repository.ReactiveMongoRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.favorites.Favorite;

public interface FavoriteRepository extends ReactiveMongoRepository<Favorite, String> {

  Mono<Favorite> findByUserIdAndTacoId(String userId, String tacoId);

  Flux<Favorite> findByUserId(String userId, Pageable pageable);

  Flux<Favorite> findByUserId(String userId);

  Mono<Long> countByUserId(String userId);

  Mono<Void> deleteByUserIdAndTacoId(String userId, String tacoId);

  Mono<Boolean> existsByUserIdAndTacoId(String userId, String tacoId);

  Mono<Long> deleteByTacoId(String tacoId);

}
