package tacos.order;

import java.util.ArrayList;
import java.util.List;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.data.domain.Sort;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Criteria;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Service;
import org.springframework.web.server.ResponseStatusException;

import reactor.core.publisher.Mono;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.search.TacoPage;
import tacos.web.api.dto.OrderDetailResponse;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderSummaryResponse;

@Service
public class OrderHistoryService {

  public static final int MAX_PAGE_SIZE = 50;

  private final OrderRepository orderRepo;
  private final ReactiveMongoTemplate mongoTemplate;
  private final OrderMapper orderMapper;

  @Autowired
  public OrderHistoryService(OrderRepository orderRepo,
                             ReactiveMongoTemplate mongoTemplate,
                             OrderMapper orderMapper) {
    this.orderRepo = orderRepo;
    this.mongoTemplate = mongoTemplate;
    this.orderMapper = orderMapper != null ? orderMapper : new OrderMapper();
  }

  public Mono<TacoPage<OrderSummaryResponse>> getUserOrders(String userId, String username, int page, int size) {
    return validatePagination(page, size).then(Mono.defer(() -> {
      Criteria userCriteria = buildUserCriteria(userId, username);
      Query query = new Query(userCriteria);
      query.with(Sort.by(Sort.Order.desc("placedAt"), Sort.Order.desc("id")));
      query.skip((long) page * size).limit(size);

      Query countQuery = new Query(userCriteria);

      return Mono.zip(
          mongoTemplate.find(query, TacoOrder.class).map(orderMapper::toSummaryResponse).collectList(),
          mongoTemplate.count(countQuery, TacoOrder.class)
      ).map(tuple -> TacoPage.of(tuple.getT1(), page, size, tuple.getT2()));
    }));
  }

  public Mono<OrderDetailResponse> getUserOrderDetail(String userId, String username, String orderId) {
    if (orderId == null || orderId.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order ID is required"));
    }

    return orderRepo.findById(orderId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found with id: " + orderId)))
        .flatMap(order -> {
          boolean isOwner = false;
          if (order.getUser() != null) {
            if (userId != null && userId.equals(order.getUser().getId())) {
              isOwner = true;
            } else if (username != null && username.equals(order.getUser().getUsername())) {
              isOwner = true;
            }
          }

          if (!isOwner) {
            // Política de no revelación: responde 404 para evitar enumeración de órdenes ajenas
            return Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found with id: " + orderId));
          }

          return Mono.just(orderMapper.toDetailResponse(order));
        });
  }

  public Mono<TacoPage<OrderDetailResponse>> getAdminOrders(String filterUserId, String filterUsername, int page, int size) {
    return validatePagination(page, size).then(Mono.defer(() -> {
      Query query = new Query();
      if ((filterUserId != null && !filterUserId.trim().isEmpty()) ||
          (filterUsername != null && !filterUsername.trim().isEmpty())) {
        query.addCriteria(buildUserCriteria(filterUserId, filterUsername));
      }

      query.with(Sort.by(Sort.Order.desc("placedAt"), Sort.Order.desc("id")));
      query.skip((long) page * size).limit(size);

      Query countQuery = new Query();
      if (query.getQueryObject().containsKey("user.id") || query.getQueryObject().containsKey("$or")) {
        countQuery.addCriteria(buildUserCriteria(filterUserId, filterUsername));
      }

      return Mono.zip(
          mongoTemplate.find(query, TacoOrder.class).map(orderMapper::toDetailResponse).collectList(),
          mongoTemplate.count(countQuery, TacoOrder.class)
      ).map(tuple -> TacoPage.of(tuple.getT1(), page, size, tuple.getT2()));
    }));
  }

  public Mono<OrderDetailResponse> getAdminOrderDetail(String orderId) {
    if (orderId == null || orderId.trim().isEmpty()) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Order ID is required"));
    }

    return orderRepo.findById(orderId)
        .switchIfEmpty(Mono.error(new ResponseStatusException(HttpStatus.NOT_FOUND, "Order not found with id: " + orderId)))
        .map(orderMapper::toDetailResponse);
  }

  private Criteria buildUserCriteria(String userId, String username) {
    List<Criteria> orList = new ArrayList<>();
    if (userId != null && !userId.trim().isEmpty()) {
      orList.add(Criteria.where("user.id").is(userId));
      orList.add(Criteria.where("user._id").is(userId));
    }
    if (username != null && !username.trim().isEmpty()) {
      orList.add(Criteria.where("user.username").is(username));
    }

    if (orList.isEmpty()) {
      return Criteria.where("user.id").is("__non_existent_user__");
    }
    if (orList.size() == 1) {
      return orList.get(0);
    }
    return new Criteria().orOperator(orList.toArray(new Criteria[0]));
  }

  private Mono<Void> validatePagination(int page, int size) {
    if (page < 0) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page index must not be less than zero"));
    }
    if (size < 1) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST, "Page size must be greater than zero"));
    }
    if (size > MAX_PAGE_SIZE) {
      return Mono.error(new ResponseStatusException(HttpStatus.BAD_REQUEST,
          String.format("Page size (%d) exceeds maximum allowed size (%d)", size, MAX_PAGE_SIZE)));
    }
    return Mono.empty();
  }

}
