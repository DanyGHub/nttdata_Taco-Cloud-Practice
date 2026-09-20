package tacos.order;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Objects;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderItem;
import tacos.PaymentMethod;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.IngredientRepository;
import tacos.data.OrderRepository;
import tacos.data.PaymentMethodRepository;
import tacos.data.UserRepository;
import tacos.inventory.InventoryService;
import tacos.messaging.OrderMessagingService;
import tacos.physics.TacoDesignValidator;
import tacos.pricing.PricingService;
import tacos.web.api.EmailOrderService;
import tacos.web.api.dto.ItemDifference;
import tacos.web.api.dto.OrderItemResponse;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderQuoteResponse;
import tacos.web.api.dto.ReorderRequest;
import tacos.web.api.dto.ReorderResponse;

@Service
public class OrderApplicationService {

  private static final Logger log = LoggerFactory.getLogger(OrderApplicationService.class);

  private final OrderRepository orderRepo;
  private final OrderMessagingService orderMessages;
  private final EmailOrderService emailOrderService;
  private final OrderMapper orderMapper;
  private final UserRepository userRepo;
  private final PaymentMethodRepository paymentMethodRepo;
  private final PricingService pricingService;
  private final InventoryService inventoryService;
  private final TacoDesignValidator designValidator;
  private final IngredientRepository ingredientRepo;

  @Autowired
  public OrderApplicationService(
      OrderRepository orderRepo,
      OrderMessagingService orderMessages,
      EmailOrderService emailOrderService,
      OrderMapper orderMapper,
      UserRepository userRepo,
      PaymentMethodRepository paymentMethodRepo,
      PricingService pricingService,
      InventoryService inventoryService,
      TacoDesignValidator designValidator,
      IngredientRepository ingredientRepo) {
    this.orderRepo = orderRepo;
    this.orderMessages = orderMessages;
    this.emailOrderService = emailOrderService;
    this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
    this.userRepo = userRepo;
    this.paymentMethodRepo = paymentMethodRepo;
    this.pricingService = pricingService;
    this.inventoryService = inventoryService;
    this.designValidator = designValidator;
    this.ingredientRepo = ingredientRepo;
  }

  public OrderApplicationService(
      OrderRepository orderRepo,
      OrderMessagingService orderMessages,
      OrderMapper orderMapper,
      UserRepository userRepo,
      PaymentMethodRepository paymentMethodRepo,
      PricingService pricingService,
      InventoryService inventoryService,
      TacoDesignValidator designValidator,
      IngredientRepository ingredientRepo) {
    this(orderRepo, orderMessages, null, orderMapper, userRepo, paymentMethodRepo,
        pricingService, inventoryService, designValidator, ingredientRepo);
  }

  /**
   * Reordena una compra anterior aplicando reglas vigentes, disponibilidad,
   * recálculo de precios, política de confirmación e idempotencia.
   */
  public Mono<ResponseEntity<ReorderResponse>> reorder(
      String originalOrderId,
      ReorderRequest request,
      Authentication authentication,
      String idempotencyKeyHeader) {

    String effectiveIdempotencyKey = (idempotencyKeyHeader != null && !idempotencyKeyHeader.trim().isEmpty())
        ? idempotencyKeyHeader.trim()
        : (request != null && request.getIdempotencyKey() != null && !request.getIdempotencyKey().trim().isEmpty()
            ? request.getIdempotencyKey().trim()
            : null);

    // 1. Verificación de Idempotencia previa
    if (effectiveIdempotencyKey != null) {
      return orderRepo.findByIdempotencyKey(effectiveIdempotencyKey)
          .flatMap(existingOrder -> {
            log.info("Idempotent reorder detected for key '{}'. Returning existing order '{}'",
                effectiveIdempotencyKey, existingOrder.getId());
            ReorderResponse resp = ReorderResponse.builder()
                .status("CREATED")
                .message("Order previously reordered (idempotent)")
                .oldTotal(existingOrder.getTotal())
                .newTotal(existingOrder.getTotal())
                .priceDifference(BigDecimal.ZERO.setScale(2))
                .priceChanged(false)
                .itemDifferences(Collections.emptyList())
                .order(orderMapper.toResponse(existingOrder))
                .build();
            return Mono.just(ResponseEntity.ok(resp));
          })
          .switchIfEmpty(Mono.defer(() -> executeReorder(originalOrderId, request, authentication, effectiveIdempotencyKey)));
    }

    return executeReorder(originalOrderId, request, authentication, null);
  }

  private Mono<ResponseEntity<ReorderResponse>> executeReorder(
      String originalOrderId,
      ReorderRequest request,
      Authentication authentication,
      String idempotencyKey) {

    // 2. Buscar orden original
    return orderRepo.findById(originalOrderId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found")))
        .flatMap(originalOrder -> {
          // 3. Validar propiedad (Ownership) con política de no-revelación (404)
          boolean isAdmin = authentication != null && authentication.getAuthorities().stream()
              .anyMatch(a -> a.getAuthority().equals("ROLE_ADMIN"));
          String currentUsername = authentication != null ? authentication.getName() : null;
          boolean isOwner = originalOrder.getUser() != null &&
              ((originalOrder.getUser().getUsername() != null && originalOrder.getUser().getUsername().equals(currentUsername)) ||
               (originalOrder.getUser().getId() != null && originalOrder.getUser().getId().equals(currentUsername)));

          if (!isAdmin && !isOwner) {
            log.warn("Ownership check failed: user '{}' attempted to reorder order '{}' belonging to '{}'",
                currentUsername, originalOrderId, originalOrder.getUser() != null ? originalOrder.getUser().getUsername() : "null");
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found"));
          }

          // 4. Resolver usuario actual
          Mono<User> currentUserMono;
          if (authentication != null && authentication.getName() != null && userRepo != null) {
            currentUserMono = userRepo.findByUsername(authentication.getName())
                .defaultIfEmpty(originalOrder.getUser());
          } else {
            currentUserMono = Mono.justOrEmpty(originalOrder.getUser());
          }

          return currentUserMono.flatMap(currentUser -> {
            // 5. Reconstruir tacos con referencias limpias (Inmutabilidad de la orden original)
            TacoOrder newOrderDraft = new TacoOrder();
            newOrderDraft.setUser(currentUser);
            newOrderDraft.setDeliveryName(request != null && request.getDeliveryName() != null ? request.getDeliveryName() : originalOrder.getDeliveryName());
            newOrderDraft.setDeliveryStreet(request != null && request.getDeliveryStreet() != null ? request.getDeliveryStreet() : originalOrder.getDeliveryStreet());
            newOrderDraft.setDeliveryCity(request != null && request.getDeliveryCity() != null ? request.getDeliveryCity() : originalOrder.getDeliveryCity());
            newOrderDraft.setDeliveryState(request != null && request.getDeliveryState() != null ? request.getDeliveryState() : originalOrder.getDeliveryState());
            newOrderDraft.setDeliveryZip(request != null && request.getDeliveryZip() != null ? request.getDeliveryZip() : originalOrder.getDeliveryZip());
            newOrderDraft.setCouponCode(originalOrder.getCouponCode());
            newOrderDraft.setIdempotencyKey(idempotencyKey);

            List<OrderItem> sourceItems = originalOrder.getItems();
            if (sourceItems != null && !sourceItems.isEmpty()) {
              for (OrderItem item : sourceItems) {
                if (item != null && item.getTaco() != null) {
                  Taco newTaco = cloneTaco(item.getTaco());
                  int qty = item.getQuantity() > 0 ? item.getQuantity() : 1;
                  newOrderDraft.addOrderItem(new OrderItem(newTaco, qty));
                }
              }
            } else if (originalOrder.getTacos() != null && !originalOrder.getTacos().isEmpty()) {
              for (Taco taco : originalOrder.getTacos()) {
                if (taco != null) {
                  Taco newTaco = cloneTaco(taco);
                  newOrderDraft.addOrderItem(new OrderItem(newTaco, 1));
                }
              }
            }

            // 6. Revalidar diseño de tacos con reglas vigentes
            return validateOrderTacos(newOrderDraft).then(Mono.defer(() -> {
              // 7. Recalcular precios actuales y cupones
              Mono<TacoOrder> pricedMono = pricingService != null
                  ? pricingService.calculateAndApplyPricing(newOrderDraft)
                  : Mono.just(newOrderDraft);

              return pricedMono.flatMap(pricedOrder -> {
                BigDecimal oldTotal = originalOrder.getTotal() != null ? originalOrder.getTotal() : BigDecimal.ZERO.setScale(2);
                BigDecimal newTotal = pricedOrder.getTotal() != null ? pricedOrder.getTotal() : BigDecimal.ZERO.setScale(2);
                BigDecimal diff = newTotal.subtract(oldTotal);
                boolean priceChanged = diff.compareTo(BigDecimal.ZERO) != 0;

                List<ItemDifference> itemDifferences = calculateDifferences(originalOrder, pricedOrder);
                boolean confirmed = request != null && Boolean.TRUE.equals(request.getConfirmPriceChange());

                // 8. Si hubo cambio de precio y no se ha confirmado -> 409 CONFLICT con quote
                if (priceChanged && !confirmed) {
                  log.info("Price changed on reorder from {} to {}. Awaiting confirmation.", oldTotal, newTotal);
                  OrderQuoteResponse quote = buildQuote(pricedOrder);
                  ReorderResponse conflictResponse = ReorderResponse.builder()
                      .status("PRICE_CHANGE_REQUIRED")
                      .message(String.format("Order price changed from $%s to $%s. Review differences and set confirmPriceChange=true to confirm.", oldTotal, newTotal))
                      .oldTotal(oldTotal)
                      .newTotal(newTotal)
                      .priceDifference(diff)
                      .priceChanged(true)
                      .itemDifferences(itemDifferences)
                      .newQuote(quote)
                      .build();
                  return Mono.just(ResponseEntity.status(HttpStatus.CONFLICT).body(conflictResponse));
                }

                // 9. Confirmación otorgada o precio idéntico: requerir método de pago válido
                String pmId = request != null ? request.getPaymentMethodId() : null;
                if (pmId == null || pmId.trim().isEmpty()) {
                  return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
                      "A valid paymentMethodId is required to complete reorder"));
                }

                Mono<PaymentMethod> pmMono;
                if (paymentMethodRepo != null) {
                  pmMono = paymentMethodRepo.findById(pmId)
                      .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment method not found")))
                      .flatMap(pm -> {
                        if (currentUser != null && pm.getUser() != null && !matchesUser(pm.getUser(), currentUser)) {
                          return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Payment method does not belong to current user"));
                        }
                        return Mono.just(pm);
                      });
                } else {
                  return Mono.error(new ResponseStatusException(HttpStatus.INTERNAL_SERVER_ERROR, "Payment service unavailable"));
                }

                return pmMono.flatMap(pm -> {
                  // Asignar pago fresco sin copiar datos obsoletos de la orden original
                  pricedOrder.setPaymentMethodId(pm.getId());
                  pricedOrder.setPaymentToken(pm.getPaymentToken());
                  pricedOrder.setBrand(pm.getBrand());
                  pricedOrder.setLast4(pm.getLast4());

                  // Nueva identidad y fecha
                  pricedOrder.setId(new ObjectId().toHexString());
                  pricedOrder.setPlacedAt(new Date());

                  // 10. Reservar inventario atómicamente
                  Mono<TacoOrder> reservedMono = inventoryService != null
                      ? inventoryService.reserve(pricedOrder).thenReturn(pricedOrder)
                      : Mono.just(pricedOrder);

                  return reservedMono.flatMap(ordToSave -> orderRepo.save(ordToSave)
                      .doOnNext(saved -> {
                        log.info("Reordered successfully: newOrderId={}, oldOrderId={}, total={}",
                            saved.getId(), originalOrderId, saved.getTotal());
                        if (orderMessages != null) {
                          orderMessages.sendOrder(saved);
                        }
                      })
                      .onErrorResume(error -> {
                        log.error("Failed to save reorder. Releasing reserved stock for order {}", ordToSave.getId(), error);
                        if (inventoryService != null) {
                          return inventoryService.releaseForOrder(ordToSave.getId()).then(Mono.error(error));
                        }
                        return Mono.error(error);
                      })
                      .map(saved -> {
                        ReorderResponse successResp = ReorderResponse.builder()
                            .status("CREATED")
                            .message("Order successfully reordered.")
                            .oldTotal(oldTotal)
                            .newTotal(newTotal)
                            .priceDifference(diff)
                            .priceChanged(priceChanged)
                            .itemDifferences(itemDifferences)
                            .order(orderMapper.toResponse(saved))
                            .build();
                        return ResponseEntity.status(HttpStatus.CREATED).body(successResp);
                      }));
                });
              });
            }));
          });
        });
  }

  private Taco cloneTaco(Taco source) {
    Taco taco = new Taco();
    taco.setName(source.getName());
    if (source.getIngredients() != null) {
      List<Ingredient> ingredients = new ArrayList<>();
      for (Ingredient ing : source.getIngredients()) {
        if (ing != null) {
          ingredients.add(new Ingredient(
              ing.getId(),
              ing.getName(),
              ing.getType(),
              ing.getUnitPrice(),
              ing.isAvailable(),
              ing.getStockOnHand(),
              ing.getReorderLevel(),
              ing.getVersion(),
              ing.getDietaryTags(),
              ing.getAllergens(),
              ing.getSpiceLevel()
          ));
        }
      }
      taco.setIngredients(ingredients);
    }
    return taco;
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

  private List<ItemDifference> calculateDifferences(TacoOrder oldOrder, TacoOrder newOrder) {
    List<ItemDifference> diffs = new ArrayList<>();
    List<OrderItem> oldItems = oldOrder.getItems() != null ? oldOrder.getItems() : Collections.emptyList();
    List<OrderItem> newItems = newOrder.getItems() != null ? newOrder.getItems() : Collections.emptyList();

    int count = Math.max(oldItems.size(), newItems.size());
    for (int i = 0; i < count; i++) {
      OrderItem oldItem = i < oldItems.size() ? oldItems.get(i) : null;
      OrderItem newItem = i < newItems.size() ? newItems.get(i) : null;

      String tacoName = newItem != null && newItem.getTaco() != null ? newItem.getTaco().getName()
          : (oldItem != null && oldItem.getTaco() != null ? oldItem.getTaco().getName() : "Taco " + (i + 1));
      int qty = newItem != null ? newItem.getQuantity() : (oldItem != null ? oldItem.getQuantity() : 1);

      BigDecimal oldUnit = oldItem != null && oldItem.getUnitPriceAtPurchase() != null
          ? oldItem.getUnitPriceAtPurchase() : BigDecimal.ZERO.setScale(2);
      BigDecimal newUnit = newItem != null && newItem.getUnitPriceAtPurchase() != null
          ? newItem.getUnitPriceAtPurchase() : BigDecimal.ZERO.setScale(2);

      BigDecimal oldLine = oldItem != null && oldItem.getSubtotal() != null
          ? oldItem.getSubtotal() : oldUnit.multiply(BigDecimal.valueOf(qty)).setScale(2);
      BigDecimal newLine = newItem != null && newItem.getSubtotal() != null
          ? newItem.getSubtotal() : newUnit.multiply(BigDecimal.valueOf(qty)).setScale(2);

      BigDecimal diff = newLine.subtract(oldLine);
      List<String> notes = new ArrayList<>();
      if (diff.compareTo(BigDecimal.ZERO) != 0) {
        if (diff.compareTo(BigDecimal.ZERO) > 0) {
          notes.add("Price increased by $" + diff);
        } else {
          notes.add("Price decreased by $" + diff.abs());
        }
      }

      diffs.add(ItemDifference.builder()
          .tacoName(tacoName)
          .quantity(qty)
          .oldUnitPrice(oldUnit)
          .newUnitPrice(newUnit)
          .oldLineTotal(oldLine)
          .newLineTotal(newLine)
          .difference(diff)
          .notes(notes)
          .build());
    }
    return diffs;
  }

  private OrderQuoteResponse buildQuote(TacoOrder priced) {
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
        .couponMessage(couponApplied ? "Coupon applied successfully" : (priced.getCouponCode() != null ? "Coupon not applied" : null))
        .items(itemResponses)
        .build();
  }

  private boolean matchesUser(User userA, User userB) {
    if (userA == null || userB == null) return false;
    if (userA.getId() != null && userB.getId() != null && userA.getId().equals(userB.getId())) {
      return true;
    }
    if (userA.getUsername() != null && userB.getUsername() != null && userA.getUsername().equals(userB.getUsername())) {
      return true;
    }
    return false;
  }

}
