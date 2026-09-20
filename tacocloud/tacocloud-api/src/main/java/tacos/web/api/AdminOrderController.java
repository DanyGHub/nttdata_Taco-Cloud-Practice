package tacos.web.api;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.order.OrderHistoryService;
import tacos.search.TacoPage;
import tacos.web.api.dto.OrderDetailResponse;

@RestController
@RequestMapping(path = "/api/admin/orders", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class AdminOrderController {

  private final OrderHistoryService orderHistoryService;

  @Autowired
  public AdminOrderController(OrderHistoryService orderHistoryService) {
    this.orderHistoryService = orderHistoryService;
  }

  @GetMapping
  public Mono<TacoPage<OrderDetailResponse>> getAdminOrders(
      @RequestParam(name = "userId", required = false) String userId,
      @RequestParam(name = "username", required = false) String username,
      @RequestParam(name = "page", defaultValue = "0") int page,
      @RequestParam(name = "size", defaultValue = "20") int size) {

    return orderHistoryService.getAdminOrders(userId, username, page, size);
  }

  @GetMapping("/{id}")
  public Mono<OrderDetailResponse> getAdminOrderDetail(@PathVariable("id") String orderId) {
    return orderHistoryService.getAdminOrderDetail(orderId);
  }

}
