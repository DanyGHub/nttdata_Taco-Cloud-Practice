package tacos.kitchen;

import java.util.List;

import javax.validation.Valid;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.CrossOrigin;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import reactor.core.publisher.Mono;
import tacos.kitchen.dto.KitchenClaimRequest;
import tacos.kitchen.dto.KitchenOrderDto;
import tacos.kitchen.dto.KitchenStatusUpdateRequest;

/**
 * Endpoints:
 * - GET /api/kitchen/queue : Consulta de la cola de pedidos pendientes con ETA y orden FIFO.
 * - POST /api/kitchen/orders/claim : Reclamo atómico de la siguiente orden por una estación.
 * - PATCH /api/kitchen/orders/{id}/status : Avance de preparación (PREPARING, READY).
 */
@RestController
@RequestMapping(path = "/api/kitchen", produces = "application/json")
@CrossOrigin(origins = "http://localhost:8080")
public class KitchenApiController {

  private final KitchenQueueService queueService;

  @Autowired
  public KitchenApiController(KitchenQueueService queueService) {
    this.queueService = queueService;
  }

  @GetMapping("/queue")
  public Mono<ResponseEntity<List<KitchenOrderDto>>> getQueue() {
    return queueService.getQueue()
        .map(ResponseEntity::ok);
  }

  @PostMapping(path = "/orders/claim", consumes = "application/json")
  public Mono<ResponseEntity<KitchenOrderDto>> claimOrder(
      @RequestBody(required = false) KitchenClaimRequest request,
      Authentication authentication) {
    return queueService.claimNext(request, authentication)
        .map(ResponseEntity::ok)
        .defaultIfEmpty(ResponseEntity.noContent().build());
  }

  @PatchMapping(path = "/orders/{id}/status", consumes = "application/json")
  public Mono<ResponseEntity<KitchenOrderDto>> updateOrderStatus(
      @PathVariable("id") String orderId,
      @Valid @RequestBody KitchenStatusUpdateRequest request,
      Authentication authentication) {
    return queueService.updateOrderStatus(orderId, request, authentication)
        .map(ResponseEntity::ok);
  }

}
