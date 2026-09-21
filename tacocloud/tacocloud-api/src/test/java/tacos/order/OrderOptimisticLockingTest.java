package tacos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.dao.OptimisticLockingFailureException;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.OrderStatusUpdateRequest;

@ExtendWith(MockitoExtension.class)
public class OrderOptimisticLockingTest {

  @Mock
  private OrderRepository orderRepo;

  @Mock
  private InventoryService inventoryService;

  private OrderMapper orderMapper = new OrderMapper();
  private OrderWorkflowService workflowService;
  private Authentication authKitchen;

  @BeforeEach
  public void setUp() {
    workflowService = new OrderWorkflowService(orderRepo, inventoryService, orderMapper);
    authKitchen = new UsernamePasswordAuthenticationToken("chef", "pass",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_KITCHEN")));
  }

  @Test
  @DisplayName("1. Transición con @Version desactualizada lanza OptimisticLockingFailureException")
  public void testStaleVersionThrowsOptimisticLockConflict() {
    TacoOrder order = new TacoOrder();
    order.setId("ord-lock-1");
    order.setStatus(OrderStatus.CREATED);
    order.setPlacedAt(new Date());
    order.setVersion(3L); // Base de datos está en versión 3

    when(orderRepo.findById("ord-lock-1")).thenReturn(Mono.just(order));

    // Cliente envía una solicitud con versión obsoleta (versión 2)
    OrderStatusUpdateRequest request = OrderStatusUpdateRequest.builder()
        .status(OrderStatus.ACCEPTED)
        .version(2L)
        .reason("Accepting order")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.updateOrderStatus("ord-lock-1", request, authKitchen);

    StepVerifier.create(result)
        .expectErrorMatches(err -> err instanceof OptimisticLockingFailureException &&
            err.getMessage().contains("Stale version"))
        .verify();

    verify(orderRepo, never()).save(any());
  }

  @Test
  @DisplayName("2. Transición con versión coincidente procede exitosamente (200 OK)")
  public void testMatchingVersionSucceeds() {
    TacoOrder order = new TacoOrder();
    order.setId("ord-lock-2");
    order.setStatus(OrderStatus.CREATED);
    order.setPlacedAt(new Date());
    order.setVersion(5L);

    when(orderRepo.findById("ord-lock-2")).thenReturn(Mono.just(order));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> {
      TacoOrder toSave = inv.getArgument(0);
      toSave.setVersion(6L); // Simular incremento de @Version
      return Mono.just(toSave);
    });

    OrderStatusUpdateRequest request = OrderStatusUpdateRequest.builder()
        .status(OrderStatus.ACCEPTED)
        .version(5L)
        .reason("Accepting order with current version")
        .build();

    Mono<ResponseEntity<OrderResponse>> result = workflowService.updateOrderStatus("ord-lock-2", request, authKitchen);

    StepVerifier.create(result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
          assertThat(resp.getBody()).isNotNull();
          assertThat(resp.getBody().getStatus()).isEqualTo(OrderStatus.ACCEPTED);
          assertThat(resp.getBody().getVersion()).isEqualTo(6L);
        })
        .verifyComplete();

    verify(orderRepo).save(any(TacoOrder.class));
  }

}
