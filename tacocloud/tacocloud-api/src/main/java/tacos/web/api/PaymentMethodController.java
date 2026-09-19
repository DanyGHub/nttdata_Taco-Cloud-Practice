package tacos.web.api;

import javax.validation.Valid;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.PaymentMethod;
import tacos.User;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.payment.PaymentGateway;
import tacos.payment.TokenizeResult;
import tacos.web.api.dto.PaymentMethodResponse;
import tacos.web.api.dto.TokenizeRequest;

@RestController
@RequestMapping(path = "/api/payment-methods", produces = "application/json")
@CrossOrigin(origins = "*")
public class PaymentMethodController {

  private static final Logger log = LoggerFactory.getLogger(PaymentMethodController.class);

  private final PaymentGateway paymentGateway;
  private final PaymentMethodRepository paymentMethodRepo;
  private final UserRepository userRepo;

  public PaymentMethodController(PaymentGateway paymentGateway,
                                 PaymentMethodRepository paymentMethodRepo,
                                 UserRepository userRepo) {
    this.paymentGateway = paymentGateway;
    this.paymentMethodRepo = paymentMethodRepo;
    this.userRepo = userRepo;
  }

  @PostMapping(path = "/tokenize", consumes = "application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<PaymentMethodResponse> tokenize(@Valid @RequestBody TokenizeRequest request,
                                              Authentication authentication) {
    log.info("Received tokenization request (CVV is omitted from logs): {}", request);

    return paymentGateway.tokenize(request.getCardNumber(), request.getExpiration(), request.getCvv())
        .flatMap(tokenResult -> {
          String maskedToken = "tok_***" + tokenResult.getLast4();
          if (authentication != null && authentication.getName() != null && userRepo != null) {
            return userRepo.findByUsername(authentication.getName())
                .flatMap(user -> {
                  PaymentMethod pm = new PaymentMethod(user, tokenResult.getPaymentToken(),
                      tokenResult.getBrand(), tokenResult.getLast4(), tokenResult.getExpiration());
                  return paymentMethodRepo.save(pm)
                      .map(saved -> new PaymentMethodResponse(
                          saved.getId(), saved.getBrand(), saved.getLast4(),
                          saved.getExpiration(), maskedToken, user.getUsername()
                      ));
                })
                .switchIfEmpty(Mono.just(new PaymentMethodResponse(
                    null, tokenResult.getBrand(), tokenResult.getLast4(),
                    tokenResult.getExpiration(), maskedToken, null
                )));
          }

          return Mono.just(new PaymentMethodResponse(
              null, tokenResult.getBrand(), tokenResult.getLast4(),
              tokenResult.getExpiration(), maskedToken, null
          ));
        });
  }

  @GetMapping
  public Flux<PaymentMethodResponse> getPaymentMethods(Authentication authentication) {
    if (authentication == null || authentication.getName() == null || userRepo == null) {
      return Flux.empty();
    }

    boolean isAdmin = authentication.getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));

    if (isAdmin) {
      return paymentMethodRepo.findAll()
          .map(this::toResponse);
    }

    return userRepo.findByUsername(authentication.getName())
        .flatMapMany(user -> paymentMethodRepo.findByUser(user)
            .switchIfEmpty(paymentMethodRepo.findByUserId(user.getId())))
        .map(this::toResponse);
  }

  private PaymentMethodResponse toResponse(PaymentMethod pm) {
    String username = pm.getUser() != null ? pm.getUser().getUsername() : null;
    String maskedToken = "tok_***" + (pm.getLast4() != null ? pm.getLast4() : "xxxx");
    return new PaymentMethodResponse(
        pm.getId(), pm.getBrand(), pm.getLast4(), pm.getExpiration(), maskedToken, username
    );
  }
}
