package tacos.data;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Mono;
import tacos.inventory.StockReservation;

public interface StockReservationRepository 
         extends ReactiveCrudRepository<StockReservation, String> {

  Mono<StockReservation> findByOrderId(String orderId);

  Mono<Boolean> existsByOrderId(String orderId);

  Mono<Void> deleteByOrderId(String orderId);

}
