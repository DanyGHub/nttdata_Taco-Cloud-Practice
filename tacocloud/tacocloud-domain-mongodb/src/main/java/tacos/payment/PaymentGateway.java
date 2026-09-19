package tacos.payment;

import reactor.core.publisher.Mono;

public interface PaymentGateway {
  Mono<TokenizeResult> tokenize(String cardNumber, String expiration, String cvv);
}
