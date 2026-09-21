package tacos.kitchen;

import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.http.HttpStatus;
import org.springframework.security.core.Authentication;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import tacos.Ingredient;
import tacos.OrderItem;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.kitchen.dto.KitchenClaimRequest;
import tacos.kitchen.dto.KitchenItemDto;
import tacos.kitchen.dto.KitchenOrderDto;
import tacos.kitchen.dto.KitchenStatusUpdateRequest;
import tacos.order.OrderStatus;
import tacos.order.OrderStatusHistory;
import tacos.order.OrderWorkflowService;
import tacos.web.api.dto.OrderStatusUpdateRequest;

@Service
public class KitchenQueueService {

  private static final Logger log = LoggerFactory.getLogger(KitchenQueueService.class);

  private final ReactiveMongoTemplate mongoTemplate;
  private final KitchenEtaCalculator etaCalculator;
  private final OrderWorkflowService workflowService;

  @Autowired
  public KitchenQueueService(
      ReactiveMongoTemplate mongoTemplate,
      KitchenEtaCalculator etaCalculator,
      OrderWorkflowService workflowService) {
    this.mongoTemplate = mongoTemplate;
    this.etaCalculator = etaCalculator != null ? etaCalculator : new KitchenEtaCalculator();
    this.workflowService = workflowService;
  }

  /**
   * Obtiene la cola actual de órdenes pendientes (CREATED) ordenadas por placedAt e ID (FIFO).
   * Calcula deterministamente el tiempo estimado (ETA) y la posición para cada una.
   */
  public Mono<List<KitchenOrderDto>> getQueue() {
    Query query = new Query(Criteria.where("status").is(OrderStatus.CREATED))
        .with(Sort.by(Sort.Direction.ASC, "placedAt", "_id"));

    return mongoTemplate.find(query, TacoOrder.class)
        .collectList()
        .map(orders -> {
          List<KitchenOrderDto> dtos = new ArrayList<>();
          List<TacoOrder> preceding = new ArrayList<>();

          for (int i = 0; i < orders.size(); i++) {
            TacoOrder order = orders.get(i);
            int queuePosition = i + 1;
            int eta = etaCalculator.calculateCumulativeEta(order, preceding);
            dtos.add(toDto(order, queuePosition, eta));
            preceding.add(order);
          }

          log.debug("Retrieved kitchen queue: {} orders waiting", dtos.size());
          return dtos;
        });
  }

  /**
   * Reclama atómicamente la siguiente orden en cola para una estación de cocina.
   * Utiliza findAndModify con returnNew=true para evitar carreras concurrentes entre estaciones.
   * Impide que una misma estación reclame dos órdenes a la vez si ya tiene una activa.
   */
  public Mono<KitchenOrderDto> claimNext(KitchenClaimRequest request, Authentication auth) {
    final String stationId = (request != null && request.getStationId() != null && !request.getStationId().trim().isEmpty())
        ? request.getStationId().trim()
        : "STATION_1";

    final String cookId = (request != null && request.getCookId() != null && !request.getCookId().trim().isEmpty())
        ? request.getCookId().trim()
        : (auth != null && auth.getName() != null ? auth.getName() : "chef");

    // 1. Validar que la estación no tenga ya una orden activa en ACCEPTED o PREPARING
    Query activeQuery = new Query(Criteria.where("stationId").is(stationId)
        .and("status").in(OrderStatus.ACCEPTED, OrderStatus.PREPARING));

    return mongoTemplate.exists(activeQuery, TacoOrder.class)
        .flatMap(hasActive -> {
          if (hasActive) {
            log.warn("Station '{}' already has an active order in progress", stationId);
            return Mono.error(new ResponseStatusException(HttpStatus.CONFLICT,
                "Station '" + stationId + "' already has an active order in progress. Complete current order before claiming a new one."));
          }

          // 2. Operación atómica findAndModify: toma la orden más antigua en estado CREATED
          Query claimQuery = new Query(Criteria.where("status").is(OrderStatus.CREATED))
              .with(Sort.by(Sort.Direction.ASC, "placedAt", "_id"));

          Date now = new Date();
          OrderStatusHistory audit = OrderStatusHistory.builder()
              .status(OrderStatus.ACCEPTED)
              .timestamp(now)
              .changedBy(cookId)
              .role("ROLE_KITCHEN")
              .origin("API_KITCHEN_CLAIM")
              .reason("Order claimed by station " + stationId)
              .build();

          Update claimUpdate = new Update()
              .set("status", OrderStatus.ACCEPTED)
              .set("stationId", stationId)
              .set("cookId", cookId)
              .set("acceptedAt", now)
              .push("statusHistory", audit);

          FindAndModifyOptions options = FindAndModifyOptions.options().returnNew(true);

          return mongoTemplate.findAndModify(claimQuery, claimUpdate, options, TacoOrder.class)
              .map(claimedOrder -> {
                int prepTime = etaCalculator.calculateOrderPrepMinutes(claimedOrder);
                claimedOrder.setEstimatedPrepMinutes(prepTime);
                log.info("Order '{}' atomically claimed by station '{}' (cook: '{}')",
                    claimedOrder.getId(), stationId, cookId);
                return toDto(claimedOrder, 1, prepTime);
              });
        });
  }

  /**
   * Actualiza el estado de la orden en cocina (ej. ACCEPTED -> PREPARING -> READY).
   * Delega a OrderWorkflowService para mantener centralizada la máquina de estados.
   */
  public Mono<KitchenOrderDto> updateOrderStatus(String orderId, KitchenStatusUpdateRequest request, Authentication auth) {
    if (request == null || request.getStatus() == null) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Target status is required"));
    }

    if (request.getStatus() != OrderStatus.PREPARING && request.getStatus() != OrderStatus.READY) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
          "Kitchen can only advance orders to PREPARING or READY"));
    }

    OrderStatusUpdateRequest updateReq = OrderStatusUpdateRequest.builder()
        .status(request.getStatus())
        .reason(request.getReason() != null ? request.getReason() : "Kitchen status update")
        .build();

    return workflowService.updateOrderStatus(orderId, updateReq, auth)
        .flatMap(resp -> {
          return mongoTemplate.findById(orderId, TacoOrder.class)
              .map(order -> {
                int prepTime = etaCalculator.calculateOrderPrepMinutes(order);
                return toDto(order, 0, prepTime);
              });
        });
  }

  /**
   * Mapea un TacoOrder a KitchenOrderDto, eliminando estrictamente datos sensibles.
   */
  public KitchenOrderDto toDto(TacoOrder order, int queuePosition, int eta) {
    if (order == null) {
      return null;
    }

    List<KitchenItemDto> items = new ArrayList<>();
    if (order.getItems() != null && !order.getItems().isEmpty()) {
      for (OrderItem item : order.getItems()) {
        if (item != null && item.getTaco() != null) {
          Taco taco = item.getTaco();
          List<String> ingredients = taco.getIngredients() != null
              ? taco.getIngredients().stream().map(Ingredient::getName).collect(Collectors.toList())
              : new ArrayList<>();

          items.add(KitchenItemDto.builder()
              .tacoName(taco.getName())
              .quantity(item.getQuantity() > 0 ? item.getQuantity() : 1)
              .ingredients(ingredients)
              .build());
        }
      }
    } else if (order.getTacos() != null) {
      for (Taco taco : order.getTacos()) {
        if (taco != null) {
          List<String> ingredients = taco.getIngredients() != null
              ? taco.getIngredients().stream().map(Ingredient::getName).collect(Collectors.toList())
              : new ArrayList<>();

          items.add(KitchenItemDto.builder()
              .tacoName(taco.getName())
              .quantity(1)
              .ingredients(ingredients)
              .build());
        }
      }
    }

    return KitchenOrderDto.builder()
        .id(order.getId())
        .placedAt(order.getPlacedAt())
        .status(order.getStatus())
        .stationId(order.getStationId())
        .cookId(order.getCookId())
        .queuePosition(queuePosition)
        .estimatedPrepMinutes(eta)
        .customerName(order.getDeliveryName())
        .items(items)
        .version(order.getVersion())
        .build();
  }

}
