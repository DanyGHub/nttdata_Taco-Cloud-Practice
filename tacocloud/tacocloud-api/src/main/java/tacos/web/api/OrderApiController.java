package tacos.web.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.dao.EmptyResultDataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.context.ReactiveSecurityContextHolder;
import javax.validation.Valid;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderMessagingService;
import tacos.web.api.dto.OrderCreateRequest;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderPatchDTO;
import tacos.web.api.dto.OrderPutDTO;
import tacos.web.api.dto.OrderResponse;
import java.security.Principal;

import org.springframework.data.domain.PageRequest;
import org.springframework.security.core.Authentication;
import tacos.data.UserRepository;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import tacos.OrderItem;
import tacos.Taco;
import tacos.data.PaymentMethodRepository;
import tacos.payment.PaymentGateway;
import tacos.pricing.PricingService;
import tacos.web.api.dto.OrderItemRequest;
import tacos.web.api.dto.OrderItemResponse;
import tacos.web.api.dto.OrderQuoteRequest;
import tacos.web.api.dto.OrderQuoteResponse;
import tacos.web.api.dto.TacoRequest;

import tacos.inventory.InventoryService;
import tacos.physics.TacoDesignValidator;
import org.bson.types.ObjectId;

@RestController
@RequestMapping(path="/api/orders",
                produces="application/json")
@CrossOrigin(origins="http://localhost:8080")
public class OrderApiController {

  private static final Logger log = LoggerFactory.getLogger(OrderApiController.class);

  private OrderRepository repo;
  private OrderMessagingService orderMessages;
  private EmailOrderService emailOrderService;
  private OrderMapper orderMapper;
  private UserRepository userRepo;
  private PaymentMethodRepository paymentMethodRepo;
  private PaymentGateway paymentGateway;
  private PricingService pricingService;
  private InventoryService inventoryService;
  private TacoDesignValidator designValidator;

  @Autowired
  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            OrderMapper orderMapper,
                            UserRepository userRepo,
                            PaymentMethodRepository paymentMethodRepo,
                            PaymentGateway paymentGateway,
                            PricingService pricingService,
                            InventoryService inventoryService,
                            TacoDesignValidator designValidator) {
    this.repo = repo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
    this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
    this.userRepo = userRepo;
    this.paymentMethodRepo = paymentMethodRepo;
    this.paymentGateway = paymentGateway;
    this.pricingService = pricingService;
    this.inventoryService = inventoryService;
    this.designValidator = designValidator;
  }

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            OrderMapper orderMapper,
                            UserRepository userRepo,
                            PaymentMethodRepository paymentMethodRepo,
                            PaymentGateway paymentGateway,
                            PricingService pricingService,
                            InventoryService inventoryService) {
    this(repo, orderMessages, emailOrderService, orderMapper, userRepo, paymentMethodRepo, paymentGateway, pricingService, inventoryService, null);
  }

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            OrderMapper orderMapper,
                            UserRepository userRepo,
                            PaymentMethodRepository paymentMethodRepo,
                            PaymentGateway paymentGateway,
                            PricingService pricingService) {
    this(repo, orderMessages, emailOrderService, orderMapper, userRepo, paymentMethodRepo, paymentGateway, pricingService, null);
  }

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            OrderMapper orderMapper,
                            UserRepository userRepo,
                            PaymentMethodRepository paymentMethodRepo,
                            PaymentGateway paymentGateway) {
    this(repo, orderMessages, emailOrderService, orderMapper, userRepo, paymentMethodRepo, paymentGateway, null);
  }

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            OrderMapper orderMapper,
                            UserRepository userRepo) {
    this(repo, orderMessages, emailOrderService, orderMapper, userRepo, null, null, null);
  }

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService,
                            OrderMapper orderMapper) {
    this(repo, orderMessages, emailOrderService, orderMapper, null, null, null, null);
  }

  public OrderApiController(OrderRepository repo,
                            OrderMessagingService orderMessages,
                            EmailOrderService emailOrderService) {
    this(repo, orderMessages, emailOrderService, new OrderMapper(), null, null, null, null);
  }

  @GetMapping(produces="application/json")
  public Flux<OrderResponse> allOrders(Authentication authentication) {
    boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
        .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
    if (isAdmin) {
      return repo.findAll().map(orderMapper::toResponse);
    }

    String username = authentication != null ? authentication.getName() : null;
    if (username == null || userRepo == null) {
      return repo.findAll().map(orderMapper::toResponse);
    }

    return userRepo.findByUsername(username)
        .flatMapMany(user -> repo.findByUserOrderByPlacedAtDesc(user, PageRequest.of(0, 50)))
        .map(orderMapper::toResponse);
  }

  @GetMapping(path="/{orderId}", produces="application/json")
  public Mono<ResponseEntity<OrderResponse>> getOrderById(@PathVariable("orderId") String orderId, Authentication authentication) {
    return repo.findById(orderId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")))
        .flatMap(order -> {
          boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
              .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
          String currentUsername = authentication != null ? authentication.getName() : null;
          boolean isOwner = order.getUser() != null && order.getUser().getUsername() != null &&
              order.getUser().getUsername().equals(currentUsername);

          if (!isAdmin && !isOwner) {
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not authorized to access this order"));
          }
          return Mono.just(ResponseEntity.ok(orderMapper.toResponse(order)));
        });
  }

  private Mono<Void> validateOrderTacos(TacoOrder order) {
    if (designValidator == null || order == null) {
      return Mono.empty();
    }
    List<Mono<Void>> validations = new ArrayList<>();
    if (order.getItems() != null) {
      for (OrderItem item : order.getItems()) {
        if (item != null && item.getTaco() != null) {
          validations.add(designValidator.validateTacoAndThrow(item.getTaco()));
        }
      }
    }
    if (order.getTacos() != null) {
      for (Taco taco : order.getTacos()) {
        if (taco != null) {
          validations.add(designValidator.validateTacoAndThrow(taco));
        }
      }
    }
    return validations.isEmpty() ? Mono.empty() : Mono.when(validations);
  }

  @PostMapping(path="/quote", consumes="application/json")
  public Mono<OrderQuoteResponse> quoteOrder(@RequestBody OrderQuoteRequest request) {
    if (request == null) {
      return Mono.just(OrderQuoteResponse.builder()
          .subtotal(BigDecimal.ZERO.setScale(2))
          .discountAmount(BigDecimal.ZERO.setScale(2))
          .total(BigDecimal.ZERO.setScale(2))
          .currency("USD")
          .build());
    }

    TacoOrder tempOrder = new TacoOrder();
    tempOrder.setCouponCode(request.getCouponCode());

    if (request.getItems() != null && !request.getItems().isEmpty()) {
      for (OrderItemRequest itemReq : request.getItems()) {
        if (itemReq != null && itemReq.getTaco() != null) {
          Taco taco = orderMapper.toTacoDomain(itemReq.getTaco());
          int qty = itemReq.getQuantity() > 0 ? itemReq.getQuantity() : 1;
          tempOrder.addOrderItem(new OrderItem(taco, qty));
        }
      }
    } else if (request.getTacos() != null && !request.getTacos().isEmpty()) {
      for (TacoRequest tacoReq : request.getTacos()) {
        if (tacoReq != null) {
          Taco taco = orderMapper.toTacoDomain(tacoReq);
          tempOrder.addOrderItem(new OrderItem(taco, 1));
        }
      }
    }

    return validateOrderTacos(tempOrder).then(Mono.defer(() -> {
      Mono<TacoOrder> pricedOrderMono = pricingService != null
          ? pricingService.calculateAndApplyPricing(tempOrder)
          : Mono.just(tempOrder);

      return pricedOrderMono.map(priced -> {
        List<OrderItemResponse> itemResponses = new ArrayList<>();
        if (priced.getItems() != null) {
          for (OrderItem item : priced.getItems()) {
            itemResponses.add(new OrderItemResponse(
                orderMapper.toTacoResponse(item.getTaco()),
                item.getQuantity(),
                item.getUnitPriceAtPurchase(),
                item.getSubtotal()
            ));
          }
        }

        boolean couponApplied = priced.getDiscountAmount() != null &&
            priced.getDiscountAmount().compareTo(BigDecimal.ZERO) > 0;

        return OrderQuoteResponse.builder()
            .subtotal(priced.getSubtotal())
            .discountAmount(priced.getDiscountAmount() != null ? priced.getDiscountAmount() : BigDecimal.ZERO.setScale(2))
            .total(priced.getTotal())
            .currency(priced.getCurrency() != null ? priced.getCurrency() : "USD")
            .couponCode(priced.getCouponCode())
            .couponApplied(couponApplied)
            .couponMessage(couponApplied ? "Coupon applied successfully" : (request.getCouponCode() != null ? "Coupon not applied" : null))
            .items(itemResponses)
            .build();
      });
    }));
  }

  @PostMapping(consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrder(@Valid @RequestBody OrderCreateRequest request, Authentication authentication) {
    TacoOrder order = orderMapper.toDomain(request);

    return validateOrderTacos(order).then(Mono.defer(() -> {
      Mono<TacoOrder> orderWithPayment;
      if (request.getPaymentMethodId() != null && paymentMethodRepo != null) {
        orderWithPayment = paymentMethodRepo.findById(request.getPaymentMethodId())
            .map(pm -> {
              order.setPaymentMethodId(pm.getId());
              order.setPaymentToken(pm.getPaymentToken());
              order.setBrand(pm.getBrand());
              order.setLast4(pm.getLast4());
              return order;
            })
            .defaultIfEmpty(order);
      } else if (request.getCcNumber() != null && paymentGateway != null) {
        orderWithPayment = paymentGateway.tokenize(request.getCcNumber(), request.getCcExpiration(), request.getCcCVV())
            .map(tok -> {
              order.setPaymentToken(tok.getPaymentToken());
              order.setBrand(tok.getBrand());
              order.setLast4(tok.getLast4());
              return order;
            })
            .defaultIfEmpty(order);
      } else {
        orderWithPayment = Mono.just(order);
      }

      return orderWithPayment.flatMap(ord -> {
        Mono<TacoOrder> orderWithPricing = pricingService != null
            ? pricingService.calculateAndApplyPricing(ord)
            : Mono.just(ord);

        return orderWithPricing.flatMap(pricedOrder -> {
          if (pricedOrder.getId() == null || pricedOrder.getId().trim().isEmpty()) {
            pricedOrder.setId(new ObjectId().toHexString());
          }

          Mono<TacoOrder> reservedOrderMono = inventoryService != null
              ? inventoryService.reserve(pricedOrder).thenReturn(pricedOrder)
              : Mono.just(pricedOrder);

          return reservedOrderMono.flatMap(ordToSave -> {
            Mono<TacoOrder> orderWithUser;
            if (authentication != null && authentication.getName() != null && userRepo != null) {
              orderWithUser = userRepo.findByUsername(authentication.getName())
                  .map(u -> {
                    ordToSave.setUser(u);
                    return ordToSave;
                  })
                  .defaultIfEmpty(ordToSave);
            } else {
              orderWithUser = Mono.just(ordToSave);
            }

            return orderWithUser
                .flatMap(repo::save)
                .doOnNext(saved -> {
                  log.info("Order saved: id={}, brand={}, last4={}, subtotal={}, total={}",
                      saved.getId(), saved.getBrand(), saved.getLast4(), saved.getSubtotal(), saved.getTotal());
                  orderMessages.sendOrder(saved);
                })
                .onErrorResume(error -> {
                  log.error("Failed to save or publish order {}. Releasing reserved stock.", ordToSave.getId(), error);
                  if (inventoryService != null) {
                    return inventoryService.releaseForOrder(ordToSave.getId())
                        .then(Mono.error(error));
                  }
                  return Mono.error(error);
                })
                .map(orderMapper::toResponse);
          });
        });
      });
    }));
  }

  // TC-07: Una sola suscripción para guardar y publicar (save-then-send)
  @PostMapping(path="fromEmail", consumes="application/json")
  @ResponseStatus(HttpStatus.CREATED)
  public Mono<OrderResponse> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
    return emailOrderService.convertEmailOrderToDomainOrder(emailOrder)
        .flatMap(repo::save)                  // Garantiza consistencia de la base de datos antes de enviar el mensaje
        .doOnNext(orderMessages::sendOrder)  // Implementar Outbox para TC-29
        .map(orderMapper::toResponse);
  }

  // @PostMapping(path="fromEmail", consumes="application/json")
  // @ResponseStatus(HttpStatus.CREATED)
  // public Mono<TacoOrder> postOrderFromEmail(@RequestBody Mono<EmailOrder> emailOrder) {
  //   Mono<TacoOrder> order = emailOrderService.convertEmailOrderToDomainOrder(emailOrder);
  //   order.subscribe(orderMessages::sendOrder); // TODO: not ideal...work into reactive flow below
  //   return order
  //       .flatMap(repo::save);
  // }

  // TC-04 — PATCH de órdenes con lista blanca y sin ZIP mutante
  @PatchMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<OrderResponse>> patchOrder(
          @PathVariable("orderId") String orderId, 
          @Valid @RequestBody OrderPatchDTO patch, 
          Principal principal) {
    
    if(patch.getId() != null && !orderId.equals(patch.getId()))
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order ID in path and request body do not match")); // Status: 400

    // User resolution logic, get the current user from Principal or ReactiveSecurityContextHolder
    Mono<String> userM = (principal != null && principal.getName() != null)
        ? Mono.just(principal.getName())
        : ReactiveSecurityContextHolder.getContext()
            .filter(ctx -> ctx.getAuthentication() != null && ctx.getAuthentication().getName() != null)
            .map(ctx -> ctx.getAuthentication().getName())
            .defaultIfEmpty("anonymousUser");

    return userM.flatMap(user -> repo.findById(orderId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"))) // Status: 404
        .flatMap(order -> {
          boolean isOwner = order.getUser() != null && user.equals(order.getUser().getUsername());
          boolean isAdmin = "admin".equals(user);
          if (!isOwner && !isAdmin) 
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not authorized to modify this order")); // Status: 403
          
          if (patch.getDeliveryName() != null) order.setDeliveryName(patch.getDeliveryName());
          if (patch.getDeliveryStreet() != null) order.setDeliveryStreet(patch.getDeliveryStreet());
          if (patch.getDeliveryCity() != null) order.setDeliveryCity(patch.getDeliveryCity());
          if (patch.getDeliveryState() != null) order.setDeliveryState(patch.getDeliveryState());
          if (patch.getDeliveryZip() != null) order.setDeliveryZip(patch.getDeliveryZip());

          return repo.save(order);
        })
        .map(orderMapper::toResponse)
        .map(ResponseEntity::ok) // Status: 200
    );
  }

  // @PatchMapping(path="/{orderId}", consumes="application/json")
  // public Mono<TacoOrder> patchOrder(@PathVariable("orderId") String orderId,
  //                         @RequestBody TacoOrder patch) {

  //   return repo.findById(orderId)
  //       .map(order -> {
  //         if (patch.getDeliveryName() != null) {
  //           order.setDeliveryName(patch.getDeliveryName());
  //         }
  //         if (patch.getDeliveryStreet() != null) {
  //           order.setDeliveryStreet(patch.getDeliveryStreet());
  //         }
  //         if (patch.getDeliveryCity() != null) {
  //           order.setDeliveryCity(patch.getDeliveryCity());
  //         }
  //         if (patch.getDeliveryState() != null) {
  //           order.setDeliveryState(patch.getDeliveryState());
  //         }
  //         if (patch.getDeliveryZip() != null) {
  //           order.setDeliveryZip(patch.getDeliveryState());
  //         }
  //         if (patch.getCcNumber() != null) {
  //           order.setCcNumber(patch.getCcNumber());
  //         }
  //         if (patch.getCcExpiration() != null) {
  //           order.setCcExpiration(patch.getCcExpiration());
  //         }
  //         if (patch.getCcCVV() != null) {
  //           order.setCcCVV(patch.getCcCVV());
  //         }
  //         return order;
  //       })
  //       .flatMap(repo::save);
  // }

  // TC-05 — PUT y DELETE de órdenes con identidad consistente
  @PutMapping(path="/{orderId}", consumes="application/json")
  public Mono<ResponseEntity<OrderResponse>> putOrder(
          @PathVariable("orderId") String orderId, 
          @Valid @RequestBody OrderPutDTO update, 
          Principal principal) {
    
    if (update.getId() != null && !orderId.equals(update.getId()))
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order ID in path and request body do not match")); // Status: 400

    // User resolution logic, get the current user from Principal or ReactiveSecurityContextHolder
    Mono<String> userM = (principal != null && principal.getName() != null)
        ? Mono.just(principal.getName())
        : ReactiveSecurityContextHolder.getContext()
            .filter(ctx -> ctx.getAuthentication() != null && ctx.getAuthentication().getName() != null)
            .map(ctx -> ctx.getAuthentication().getName())
            .defaultIfEmpty("anonymousUser");

    return userM.flatMap(user -> repo.findById(orderId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"))) // Status: 404
        .flatMap(order -> {
          boolean isOwner = order.getUser() != null && user.equals(order.getUser().getUsername());
          boolean isAdmin = "admin".equals(user);
          
          if (!isOwner && !isAdmin)
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not authorized to modify this order")); // Status: 403

          order.setDeliveryName(update.getDeliveryName());
          order.setDeliveryStreet(update.getDeliveryStreet());
          order.setDeliveryCity(update.getDeliveryCity());
          order.setDeliveryState(update.getDeliveryState());
          order.setDeliveryZip(update.getDeliveryZip());
          if (update.getTacos() != null)
            order.setTacos(update.getTacos());

          return repo.save(order);
        })
        .map(orderMapper::toResponse)
        .map(ResponseEntity::ok) // Status: 200
    );
  }

  // @PutMapping(path="/{orderId}", consumes="application/json")
  // public Mono<TacoOrder> putOrder(@RequestBody Mono<TacoOrder> order) {
  //   return order.flatMap(repo::save);
  // }

  @DeleteMapping(path="/{orderId}")
  public Mono<ResponseEntity<Void>> deleteOrder(
          @PathVariable("orderId") String orderId, 
          Principal principal) {
    
    // User resolution logic, get the current user from Principal or ReactiveSecurityContextHolder
    Mono<String> userM = (principal != null && principal.getName() != null)
        ? Mono.just(principal.getName())
        : ReactiveSecurityContextHolder.getContext()
            .filter(ctx -> ctx.getAuthentication() != null && ctx.getAuthentication().getName() != null)
            .map(ctx -> ctx.getAuthentication().getName())
            .defaultIfEmpty("anonymousUser");

    return userM.flatMap(user -> repo.findById(orderId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"))) // Status: 404
        .flatMap(order -> {
          boolean isOwner = order.getUser() != null && user.equals(order.getUser().getUsername());
          boolean isAdmin = "admin".equals(user);
          
          if (!isOwner && !isAdmin)
            return Mono.error(new ResponseStatusException(HttpStatus.FORBIDDEN, "User is not authorized to delete this order")); // Status: 403

          Mono<Void> releaseStockMono = inventoryService != null
              ? inventoryService.releaseForOrder(orderId).then()
              : Mono.empty();

          return releaseStockMono
              .then(repo.deleteById(orderId));
        })
        .thenReturn(ResponseEntity.noContent().<Void>build()) // Status: 204
    );
  }

  // @DeleteMapping("/{orderId}")
  // @ResponseStatus(HttpStatus.NO_CONTENT)
  // public void deleteOrder(@PathVariable("orderId") String orderId) {
  //   try {
  //     repo.deleteById(orderId);
  //   } catch (EmptyResultDataAccessException e) {}
  // }

}
