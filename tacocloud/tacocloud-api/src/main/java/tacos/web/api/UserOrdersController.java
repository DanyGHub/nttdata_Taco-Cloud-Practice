package tacos.web.api;

import java.security.Principal;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

import lombok.AllArgsConstructor;
import lombok.Data;
import reactor.core.publisher.Mono;
import tacos.User;
import tacos.data.UserRepository;
import tacos.order.OrderHistoryService;
import tacos.search.TacoPage;
import tacos.web.api.dto.OrderDetailResponse;
import tacos.web.api.dto.OrderSummaryResponse;

@RestController
@RequestMapping(path = "/api/users/me/orders", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class UserOrdersController {

  private final OrderHistoryService orderHistoryService;
  private final UserRepository userRepo;

  @Autowired
  public UserOrdersController(OrderHistoryService orderHistoryService, UserRepository userRepo) {
    this.orderHistoryService = orderHistoryService;
    this.userRepo = userRepo;
  }

  public UserOrdersController(OrderHistoryService orderHistoryService) {
    this(orderHistoryService, null);
  }

  @GetMapping
  public Mono<TacoPage<OrderSummaryResponse>> getMyOrders(
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size,
      @AuthenticationPrincipal User user,
      Principal principal,
      Authentication authentication) {

    return resolveUserIdentity(user, principal, authentication)
        .flatMap(identity -> orderHistoryService.getUserOrders(identity.getUserId(), identity.getUsername(), page, size));
  }

  @GetMapping("/{id}")
  public Mono<OrderDetailResponse> getMyOrderDetail(
      @PathVariable("id") String orderId,
      @AuthenticationPrincipal User user,
      Principal principal,
      Authentication authentication) {

    return resolveUserIdentity(user, principal, authentication)
        .flatMap(identity -> orderHistoryService.getUserOrderDetail(identity.getUserId(), identity.getUsername(), orderId));
  }

  private Mono<UserIdentity> resolveUserIdentity(User user, Principal principal, Authentication authentication) {
    if (user != null && user.getId() != null && !user.getId().trim().isEmpty()) {
      return Mono.just(new UserIdentity(user.getId(), user.getUsername()));
    }

    String username = null;
    if (user != null && user.getUsername() != null) {
      username = user.getUsername();
    } else if (principal != null && principal.getName() != null) {
      username = principal.getName();
    } else if (authentication != null && authentication.getName() != null) {
      username = authentication.getName();
    }

    if (username == null || username.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.UNAUTHORIZED, "User is not authenticated"));
    }

    final String finalUsername = username;
    if (userRepo != null) {
      return userRepo.findByUsername(finalUsername)
          .map(u -> new UserIdentity(u.getId(), u.getUsername()))
          .defaultIfEmpty(new UserIdentity(finalUsername, finalUsername));
    }

    return Mono.just(new UserIdentity(finalUsername, finalUsername));
  }

  @Data
  @AllArgsConstructor
  public static class UserIdentity {
    private String userId;
    private String username;
  }

}
