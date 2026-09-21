package tacos.order;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;
import tacos.web.api.dto.OrderCancelRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.OrderStatusUpdateRequest;

import tacos.messaging.OrderMessagingService;

@Service
public class OrderWorkflowService {

  private static final Logger log = LoggerFactory.getLogger(OrderWorkflowService.class);

  private final OrderRepository orderRepo;
  private final InventoryService inventoryService;
  private final OrderMapper orderMapper;
  private final OrderMessagingService orderMessages;
  private final OrderEventMapper orderEventMapper;

  private static final Map<OrderStatus, Map<OrderStatus, Set<String>>> TRANSITION_MATRIX = new HashMap<>();

  static {
    // Desde CREATED
    Map<OrderStatus, Set<String>> fromCreated = new HashMap<>();
    fromCreated.put(OrderStatus.ACCEPTED, new HashSet<>(Arrays.asList("ROLE_KITCHEN", "ROLE_ADMIN")));
    fromCreated.put(OrderStatus.CANCELLED, new HashSet<>(Arrays.asList("ROLE_USER", "ROLE_ADMIN")));
    TRANSITION_MATRIX.put(OrderStatus.CREATED, Collections.unmodifiableMap(fromCreated));

    // Desde ACCEPTED
    Map<OrderStatus, Set<String>> fromAccepted = new HashMap<>();
    fromAccepted.put(OrderStatus.PREPARING, new HashSet<>(Arrays.asList("ROLE_KITCHEN", "ROLE_ADMIN")));
    fromAccepted.put(OrderStatus.CANCELLED, new HashSet<>(Arrays.asList("ROLE_ADMIN")));
    TRANSITION_MATRIX.put(OrderStatus.ACCEPTED, Collections.unmodifiableMap(fromAccepted));

    // Desde PREPARING
    Map<OrderStatus, Set<String>> fromPreparing = new HashMap<>();
    fromPreparing.put(OrderStatus.READY, new HashSet<>(Arrays.asList("ROLE_KITCHEN", "ROLE_ADMIN")));
    fromPreparing.put(OrderStatus.CANCELLED, new HashSet<>(Arrays.asList("ROLE_ADMIN")));
    TRANSITION_MATRIX.put(OrderStatus.PREPARING, Collections.unmodifiableMap(fromPreparing));

    // Desde READY
    Map<OrderStatus, Set<String>> fromReady = new HashMap<>();
    fromReady.put(OrderStatus.OUT_FOR_DELIVERY, new HashSet<>(Arrays.asList("ROLE_DELIVERY", "ROLE_ADMIN")));
    fromReady.put(OrderStatus.CANCELLED, new HashSet<>(Arrays.asList("ROLE_ADMIN")));
    TRANSITION_MATRIX.put(OrderStatus.READY, Collections.unmodifiableMap(fromReady));

    // Desde OUT_FOR_DELIVERY
    Map<OrderStatus, Set<String>> fromOutForDelivery = new HashMap<>();
    fromOutForDelivery.put(OrderStatus.DELIVERED, new HashSet<>(Arrays.asList("ROLE_DELIVERY", "ROLE_ADMIN")));
    TRANSITION_MATRIX.put(OrderStatus.OUT_FOR_DELIVERY, Collections.unmodifiableMap(fromOutForDelivery));

    // DELIVERED y CANCELLED son estados terminales
    TRANSITION_MATRIX.put(OrderStatus.DELIVERED, Collections.emptyMap());
    TRANSITION_MATRIX.put(OrderStatus.CANCELLED, Collections.emptyMap());
  }

  @Autowired
  public OrderWorkflowService(
      OrderRepository orderRepo,
      InventoryService inventoryService,
      OrderMapper orderMapper,
      @Autowired(required = false) OrderMessagingService orderMessages,
      @Autowired(required = false) OrderEventMapper orderEventMapper) {
    this.orderRepo = orderRepo;
    this.inventoryService = inventoryService;
    this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
    this.orderMessages = orderMessages;
    this.orderEventMapper = orderEventMapper != null ? orderEventMapper : new OrderEventMapper();
  }

  public OrderWorkflowService(
      OrderRepository orderRepo,
      InventoryService inventoryService,
      OrderMapper orderMapper) {
    this(orderRepo, inventoryService, orderMapper, null, null);
  }

  public Mono<ResponseEntity<OrderResponse>> updateOrderStatus(
      String orderId,
      OrderStatusUpdateRequest request,
      Authentication auth) {

    if (request == null || request.getStatus() == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target status is required"));
    }

    final OrderStatus targetStatus = request.getStatus();

    return orderRepo.findById(orderId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")))
        .flatMap(order -> {
          if (request.getVersion() != null && order.getVersion() != null &&
              !request.getVersion().equals(order.getVersion())) {
            log.warn("Optimistic lock conflict on order {}: expected version {}, but DB has {}",
                orderId, request.getVersion(), order.getVersion());
            return Mono.error(new OptimisticLockingFailureException(
                "Stale version: order version is " + order.getVersion() + ", but request specified " + request.getVersion()));
          }

          OrderStatus currentStatus = order.getStatus() != null ? order.getStatus() : OrderStatus.CREATED;

          if (currentStatus == targetStatus) {
            log.info("Idempotent status update for order {}: already in state {}", orderId, targetStatus);
            return Mono.just(ResponseEntity.ok(orderMapper.toResponse(order)));
          }

          Map<OrderStatus, Set<String>> allowedTargets = TRANSITION_MATRIX.getOrDefault(currentStatus, Collections.emptyMap());
          if (!allowedTargets.containsKey(targetStatus)) {
            log.warn("Invalid state transition attempted for order {}: {} -> {}", orderId, currentStatus, targetStatus);
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                "Invalid status transition from " + currentStatus + " to " + targetStatus));
          }

          Set<String> authorizedRoles = allowedTargets.get(targetStatus);
          if (!hasAnyRole(auth, authorizedRoles)) {
            log.warn("User '{}' unauthorized for status transition {} -> {} on order {}",
                auth != null ? auth.getName() : "anonymous", currentStatus, targetStatus, orderId);
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN,
                "User not authorized to perform transition from " + currentStatus + " to " + targetStatus));
          }

          if (targetStatus == OrderStatus.CANCELLED && !hasRole(auth, "ADMIN")) {
            String currentUsername = auth != null ? auth.getName() : null;
            boolean isOwner = order.getUser() != null &&
                ((order.getUser().getUsername() != null && order.getUser().getUsername().equals(currentUsername)) ||
                 (order.getUser().getId() != null && order.getUser().getId().equals(currentUsername)));
            if (!isOwner) {
              return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
            }
          }

          String changedBy = auth != null && auth.getName() != null ? auth.getName() : "system";
          String role = resolvePrimaryRole(auth);
          String reason = request.getReason() != null && !request.getReason().trim().isEmpty()
              ? request.getReason().trim()
              : "Status updated to " + targetStatus;

          order.recordStatusChange(targetStatus, changedBy, role, "API_STATUS_UPDATE", reason);

          Mono<Void> releaseStockMono = (targetStatus == OrderStatus.CANCELLED && inventoryService != null)
              ? inventoryService.releaseForOrder(order.getId()).then()
              : Mono.empty();

          return releaseStockMono
              .then(orderRepo.save(order))
              .map(saved -> {
                log.info("Order {} transitioned from {} to {} by '{}' ({})",
                    saved.getId(), currentStatus, targetStatus, changedBy, role);
                if (orderMessages != null && orderEventMapper != null) {
                  orderMessages.sendOrder(orderEventMapper.toStatusChangedEvent(saved, currentStatus));
                }
                return ResponseEntity.ok(orderMapper.toResponse(saved));
              });
        });
  }

  public Mono<ResponseEntity<OrderResponse>> cancelOrder(
      String orderId,
      OrderCancelRequest request,
      Authentication auth) {

    return orderRepo.findById(orderId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")))
        .flatMap(order -> {
          boolean isAdmin = hasRole(auth, "ADMIN");
          String currentUsername = auth != null ? auth.getName() : null;
          boolean isOwner = order.getUser() != null &&
              ((order.getUser().getUsername() != null && order.getUser().getUsername().equals(currentUsername)) ||
               (order.getUser().getId() != null && order.getUser().getId().equals(currentUsername)));

          if (!isAdmin && !isOwner) {
            log.warn("Ownership check failed on cancel: user '{}' attempted to cancel order '{}'",
                currentUsername, orderId);
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
          }

          OrderStatus currentStatus = order.getStatus() != null ? order.getStatus() : OrderStatus.CREATED;

          if (currentStatus == OrderStatus.CANCELLED) {
            log.info("Idempotent cancel on order {}: already CANCELLED", orderId);
            return Mono.just(ResponseEntity.ok(orderMapper.toResponse(order)));
          }

          if (currentStatus == OrderStatus.DELIVERED) {
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                "Cannot cancel an order that has already been delivered."));
          }

          if (!isAdmin && currentStatus != OrderStatus.CREATED) {
            log.warn("User '{}' attempted to cancel order {} in non-cancellable state {}",
                currentUsername, orderId, currentStatus);
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                "Order cannot be cancelled once it has been accepted for preparation (current status: " + currentStatus + ")"));
          }

          String changedBy = currentUsername != null ? currentUsername : "user";
          String role = resolvePrimaryRole(auth);
          String reason = (request != null && request.getReason() != null && !request.getReason().trim().isEmpty())
              ? request.getReason().trim()
              : "User requested cancellation";

          order.recordStatusChange(OrderStatus.CANCELLED, changedBy, role, "API_CANCEL", reason);

          Mono<Void> releaseStockMono = inventoryService != null
              ? inventoryService.releaseForOrder(order.getId()).then()
              : Mono.empty();

          return releaseStockMono
              .then(orderRepo.save(order))
              .map(saved -> {
                log.info("Order {} successfully cancelled by '{}' ({})", saved.getId(), changedBy, role);
                if (orderMessages != null && orderEventMapper != null) {
                  orderMessages.sendOrder(orderEventMapper.toOrderCancelledEvent(saved, reason));
                }
                return ResponseEntity.ok(orderMapper.toResponse(saved));
              });
        });
  }

  public boolean hasRole(Authentication auth, String roleName) {
    if (auth == null || auth.getAuthorities() == null) {
      return false;
    }
    String targetAuthority = roleName.startsWith("ROLE_") ? roleName : "ROLE_" + roleName;
    return auth.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .anyMatch(a -> a.equalsIgnoreCase(targetAuthority) || a.equalsIgnoreCase(roleName));
  }

  private boolean hasAnyRole(Authentication auth, Set<String> roles) {
    if (auth == null || auth.getAuthorities() == null || roles == null) {
      return false;
    }
    for (String role : roles) {
      if (hasRole(auth, role)) {
        return true;
      }
    }
    return false;
  }

  private String resolvePrimaryRole(Authentication auth) {
    if (auth == null || auth.getAuthorities() == null) {
      return "ROLE_ANONYMOUS";
    }
    if (hasRole(auth, "ADMIN")) return "ROLE_ADMIN";
    if (hasRole(auth, "KITCHEN")) return "ROLE_KITCHEN";
    if (hasRole(auth, "DELIVERY")) return "ROLE_DELIVERY";
    if (hasRole(auth, "USER")) return "ROLE_USER";

    return auth.getAuthorities().stream()
        .map(GrantedAuthority::getAuthority)
        .findFirst()
        .orElse("ROLE_USER");
  }

}
