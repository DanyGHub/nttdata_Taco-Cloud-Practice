package tacos.payment;

import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import reactor.core.publisher.Mono;

@Component
public class FakePaymentGateway implements PaymentGateway {

  private static final Logger log = LoggerFactory.getLogger(FakePaymentGateway.class);

  @Override
  public Mono<TokenizeResult> tokenize(String cardNumber, String expiration, String cvv) {
    if (cardNumber == null || cardNumber.trim().isEmpty()) {
      return Mono.error(new IllegalArgumentException("Card number is required"));
    }

    String digitsOnly = cardNumber.replaceAll("\\D", "");
    if (digitsOnly.length() < 4) {
      return Mono.error(new IllegalArgumentException("Card number must contain at least 4 digits"));
    }

    String brand = determineBrand(digitsOnly);
    String last4 = digitsOnly.substring(digitsOnly.length() - 4);
    String token = "tok_" + brand.toLowerCase() + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);

    // Explicit security rule: CVV is never stored, logged, or returned
    log.info("Tokenizing payment method: brand={}, last4={}", brand, last4);

    return Mono.just(new TokenizeResult(token, brand, last4, expiration));
  }

  private String determineBrand(String digits) {
    if (digits.startsWith("4")) {
      return "VISA";
    } else if (digits.startsWith("5") || digits.startsWith("2")) {
      return "MASTERCARD";
    } else if (digits.startsWith("34") || digits.startsWith("37")) {
      return "AMEX";
    } else if (digits.startsWith("6")) {
      return "DISCOVER";
    }
    return "GENERIC";
  }
}
