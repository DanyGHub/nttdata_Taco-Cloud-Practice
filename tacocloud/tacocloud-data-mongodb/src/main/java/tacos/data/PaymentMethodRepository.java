package tacos.data;

import org.springframework.data.repository.reactive.ReactiveCrudRepository;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.PaymentMethod;
import tacos.User;

public interface PaymentMethodRepository 
         extends ReactiveCrudRepository<PaymentMethod, String> {
  Mono<PaymentMethod> findByUserId(String userId);
  Flux<PaymentMethod> findByUser(User user);
}
