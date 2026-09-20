package tacos.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

import org.bson.types.ObjectId;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.data.StockReservationRepository;

@Service
public class MongoInventoryService implements InventoryService {

  private static final Logger log = LoggerFactory.getLogger(MongoInventoryService.class);

  private final ReactiveMongoTemplate mongoTemplate;
  private final StockReservationRepository reservationRepo;

  @Autowired
  public MongoInventoryService(ReactiveMongoTemplate mongoTemplate, StockReservationRepository reservationRepo) {
    this.mongoTemplate = mongoTemplate;
    this.reservationRepo = reservationRepo;
  }

  @Override
  public Mono<StockReservation> reserve(TacoOrder orderDraft) {
    if (orderDraft == null) {
      return Mono.empty();
    }

    if (orderDraft.getId() == null || orderDraft.getId().trim().isEmpty()) {
      orderDraft.setId(new ObjectId().toHexString());
    }

    String orderId = orderDraft.getId();

    // Idempotencia: Verificar si ya existe una reserva activa para esta orden
    return reservationRepo.findByOrderId(orderId)
        .flatMap(existing -> {
          if (existing.getStatus() == ReservationStatus.RESERVED || existing.getStatus() == ReservationStatus.CONFIRMED) {
            log.info("Idempotent reserve: Active reservation {} already exists for order {}", existing.getId(), orderId);
            return Mono.just(existing);
          }
          // Si estaba en otro estado (ej. FAILED), procedemos a reservar
          return executeReservation(orderDraft);
        })
        .switchIfEmpty(Mono.defer(() -> executeReservation(orderDraft)));
  }

  private Mono<StockReservation> executeReservation(TacoOrder orderDraft) {
    String orderId = orderDraft.getId();
    Map<String, Integer> requiredStock = aggregateRequiredStock(orderDraft);

    if (requiredStock.isEmpty()) {
      StockReservation emptyRes = new StockReservation(null, orderId, ReservationStatus.RESERVED, Collections.emptyList(), Instant.now(), Instant.now());
      return reservationRepo.save(emptyRes);
    }

    // Convertir a lista de ReservedItem manteniendo el orden del TreeMap (alfabético por _id)
    List<ReservedItem> itemsToReserve = requiredStock.entrySet().stream()
        .map(entry -> new ReservedItem(entry.getKey(), entry.getValue()))
        .collect(Collectors.toList());

    log.debug("Starting atomic inventory reservation for order {}: {} distinct ingredients", orderId, itemsToReserve.size());

    // Ejecutar reservas secuenciales atómicas con compensación en caso de fallo
    return reserveSequentially(itemsToReserve, 0, new ArrayList<>())
        .flatMap(reservedItems -> {
          StockReservation reservation = new StockReservation(
              null,
              orderId,
              ReservationStatus.RESERVED,
              reservedItems,
              Instant.now(),
              Instant.now()
          );

          return reservationRepo.save(reservation)
              .onErrorResume(saveErr -> {
                log.error("Failed to persist StockReservation for order {}. Rolling back reserved stock.", orderId, saveErr);
                return compensate(reservedItems).then(Mono.error(saveErr));
              })
              .doOnSuccess(saved -> log.info("Successfully reserved stock for order {}: reservationId={}", orderId, saved.getId()));
        });
  }

  private Mono<List<ReservedItem>> reserveSequentially(List<ReservedItem> items, int index, List<ReservedItem> reservedSoFar) {
    if (index >= items.size()) {
      return Mono.just(reservedSoFar);
    }

    ReservedItem current = items.get(index);
    Query query = Query.query(
        Criteria.where("_id").is(current.getIngredientId())
                .and("stockOnHand").gte(current.getQuantity())
                .and("available").is(true)
    );
    Update update = new Update().inc("stockOnHand", -current.getQuantity());

    return mongoTemplate.updateFirst(query, update, Ingredient.class)
        .flatMap(updateResult -> {
          if (updateResult.getModifiedCount() > 0) {
            log.debug("Reserved {} units of ingredient {}", current.getQuantity(), current.getIngredientId());
            reservedSoFar.add(current);
            return reserveSequentially(items, index + 1, reservedSoFar);
          } else {
            log.warn("Atomic reservation failed for ingredient {} (requested: {}). Initiating compensation.",
                current.getIngredientId(), current.getQuantity());

            return compensate(reservedSoFar)
                .then(mongoTemplate.findById(current.getIngredientId(), Ingredient.class)
                    .defaultIfEmpty(new Ingredient(current.getIngredientId(), current.getIngredientId(), null, BigDecimal.ZERO, false, 0, 0, null))
                    .flatMap(ing -> {
                      int available = ing.getStockOnHand();
                      return Mono.error(new InsufficientStockException(current.getIngredientId(), current.getQuantity(), available));
                    })
                );
          }
        });
  }

  private Mono<Void> compensate(List<ReservedItem> itemsToRollback) {
    if (itemsToRollback == null || itemsToRollback.isEmpty()) {
      return Mono.empty();
    }

    log.info("Executing compensation rollback for {} reserved ingredients", itemsToRollback.size());
    return Flux.fromIterable(itemsToRollback)
        .flatMap(item -> {
          Query query = Query.query(Criteria.where("_id").is(item.getIngredientId()));
          Update update = new Update().inc("stockOnHand", item.getQuantity());
          return mongoTemplate.updateFirst(query, update, Ingredient.class);
        })
        .then();
  }

  @Override
  public Mono<StockReservation> releaseForOrder(String orderId) {
    if (orderId == null || orderId.trim().isEmpty()) {
      return Mono.empty();
    }

    // Transición atómica de RESERVED o CONFIRMED a RELEASED
    Query query = Query.query(
        Criteria.where("orderId").is(orderId)
                .and("status").in(ReservationStatus.RESERVED, ReservationStatus.CONFIRMED)
    );
    Update update = new Update()
        .set("status", ReservationStatus.RELEASED)
        .set("updatedAt", Instant.now());

    return mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), StockReservation.class)
        .flatMap(reservation -> {
          log.info("Releasing stock for order {}: reservationId={}, itemsCount={}",
              orderId, reservation.getId(), reservation.getItems() != null ? reservation.getItems().size() : 0);

          if (reservation.getItems() == null || reservation.getItems().isEmpty()) {
            return Mono.just(reservation);
          }

          return compensate(reservation.getItems())
              .thenReturn(reservation);
        });
  }

  @Override
  public Mono<StockReservation> confirmReservation(String orderId) {
    if (orderId == null || orderId.trim().isEmpty()) {
      return Mono.empty();
    }

    Query query = Query.query(
        Criteria.where("orderId").is(orderId)
                .and("status").is(ReservationStatus.RESERVED)
    );
    Update update = new Update()
        .set("status", ReservationStatus.CONFIRMED)
        .set("updatedAt", Instant.now());

    return mongoTemplate.findAndModify(query, update, FindAndModifyOptions.options().returnNew(true), StockReservation.class);
  }

  @Override
  public Mono<StockReservation> findReservationByOrderId(String orderId) {
    if (orderId == null || orderId.trim().isEmpty()) {
      return Mono.empty();
    }
    return reservationRepo.findByOrderId(orderId);
  }

  /**
   * Agrupa los ingredientes requeridos por una orden y suma sus cantidades totales.
   * Utiliza un {@link TreeMap} para garantizar orden alfabético estricto de los IDs,
   * previniendo bloqueos mutuos bajo concurrencia.
   */
  private Map<String, Integer> aggregateRequiredStock(TacoOrder order) {
    Map<String, Integer> stockMap = new TreeMap<>();

    if (order.getItems() != null && !order.getItems().isEmpty()) {
      for (OrderItem item : order.getItems()) {
        if (item != null) {
          int qty = item.getQuantity() > 0 ? item.getQuantity() : 1;
          Taco taco = item.getTaco();
          if (taco != null && taco.getIngredients() != null) {
            for (Ingredient ing : taco.getIngredients()) {
              if (ing != null && ing.getId() != null && !ing.getId().trim().isEmpty()) {
                stockMap.merge(ing.getId().trim(), qty, Integer::sum);
              }
            }
          }
        }
      }
    } else if (order.getTacos() != null && !order.getTacos().isEmpty()) {
      for (Taco taco : order.getTacos()) {
        if (taco != null && taco.getIngredients() != null) {
          for (Ingredient ing : taco.getIngredients()) {
            if (ing != null && ing.getId() != null && !ing.getId().trim().isEmpty()) {
              stockMap.merge(ing.getId().trim(), 1, Integer::sum);
            }
          }
        }
      }
    }

    return stockMap;
  }
}
