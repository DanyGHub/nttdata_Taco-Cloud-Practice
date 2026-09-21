package tacos.order;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.when;

import java.util.Collections;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.User;
import tacos.data.OrderRepository;
import tacos.inventory.InventoryService;
import tacos.web.api.dto.OrderMapper;
import tacos.web.api.dto.OrderResponse;
import tacos.web.api.dto.OrderStatusUpdateRequest;

@ExtendWith(MockitoExtension.class)
public class OrderAuditHistoryTest {

  @Mock
  private OrderRepository orderRepo;

  @Mock
  private InventoryService inventoryService;

  private OrderMapper orderMapper = new OrderMapper();
  private OrderWorkflowService workflowService;

  private User userAlice;
  private Authentication authKitchen;
  private Authentication authDelivery;

  @BeforeEach
  public void setUp() {
    workflowService = new OrderWorkflowService(orderRepo, inventoryService, orderMapper);

    userAlice = new User("alice", "secret123", "Alice", "Street", "City", "State", "12345", "111", "a@test.com", Collections.singletonList("ROLE_USER"));
    userAlice.setId("alice-id");

    authKitchen = new UsernamePasswordAuthenticationToken("chef_mario", "pass",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_KITCHEN")));

    authDelivery = new UsernamePasswordAuthenticationToken("driver_luigi", "pass",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_DELIVERY")));
  }

  @Test
  @DisplayName("1. Historial de estados persiste ordenado y sin datos sensibles de pago ni credenciales")
  public void testAuditTrailRecordedWithoutSensitiveData() {
    TacoOrder order = new TacoOrder();
    order.setId("ord-audit-1");
    order.setStatus(OrderStatus.CREATED);
    order.setUser(userAlice);
    order.setPlacedAt(new Date());
    order.setPaymentToken("tok_secret_pci_token_9999");
    order.setLast4("4242");
    order.setBrand("VISA");

    // Registro inicial de creación
    order.recordStatusChange(OrderStatus.CREATED, "system", "SYSTEM", "API_CREATE", "Order created");

    when(orderRepo.findById("ord-audit-1")).thenReturn(Mono.just(order));
    when(orderRepo.save(any(TacoOrder.class))).thenAnswer(inv -> Mono.just(inv.getArgument(0)));

    // Transición a ACCEPTED por cocina
    OrderStatusUpdateRequest step1 = OrderStatusUpdateRequest.builder()
        .status(OrderStatus.ACCEPTED)
        .reason("Kitchen accepted taco batch")
        .build();

    Mono<ResponseEntity<OrderResponse>> step1Result = workflowService.updateOrderStatus("ord-audit-1", step1, authKitchen);

    StepVerifier.create(step1Result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
          OrderResponse body = resp.getBody();
          assertThat(body).isNotNull();
          assertThat(body.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
          assertThat(body.getStatusHistory()).hasSize(2);
        })
        .verifyComplete();

    // Transición a PREPARING por cocina
    OrderStatusUpdateRequest step2 = OrderStatusUpdateRequest.builder()
        .status(OrderStatus.PREPARING)
        .reason("Tortillas on grill")
        .build();

    Mono<ResponseEntity<OrderResponse>> step2Result = workflowService.updateOrderStatus("ord-audit-1", step2, authKitchen);

    StepVerifier.create(step2Result)
        .assertNext(resp -> {
          assertThat(resp.getStatusCode()).isEqualTo(HttpStatus.OK);
          OrderResponse body = resp.getBody();
          assertThat(body).isNotNull();
          assertThat(body.getStatus()).isEqualTo(OrderStatus.PREPARING);
          assertThat(body.getStatusHistory()).hasSize(3);

          List<OrderStatusHistory> history = body.getStatusHistory();

          // 1. Verificación de orden cronológico
          assertThat(history.get(0).getStatus()).isEqualTo(OrderStatus.CREATED);
          assertThat(history.get(0).getChangedBy()).isEqualTo("system");

          assertThat(history.get(1).getStatus()).isEqualTo(OrderStatus.ACCEPTED);
          assertThat(history.get(1).getChangedBy()).isEqualTo("chef_mario");
          assertThat(history.get(1).getRole()).isEqualTo("ROLE_KITCHEN");
          assertThat(history.get(1).getOrigin()).isEqualTo("API_STATUS_UPDATE");
          assertThat(history.get(1).getReason()).isEqualTo("Kitchen accepted taco batch");
          assertThat(history.get(1).getTimestamp()).isNotNull();

          assertThat(history.get(2).getStatus()).isEqualTo(OrderStatus.PREPARING);
          assertThat(history.get(2).getChangedBy()).isEqualTo("chef_mario");
          assertThat(history.get(2).getRole()).isEqualTo("ROLE_KITCHEN");
          assertThat(history.get(2).getOrigin()).isEqualTo("API_STATUS_UPDATE");
          assertThat(history.get(2).getReason()).isEqualTo("Tortillas on grill");
          assertThat(history.get(2).getTimestamp()).isNotNull();

          // 2. Garantía de NO contener datos sensibles en la auditoría
          for (OrderStatusHistory entry : history) {
            String combined = (entry.getStatus() + " " + entry.getChangedBy() + " " + entry.getRole() + " "
                + entry.getOrigin() + " " + entry.getReason()).toLowerCase();

            assertThat(combined)
                .doesNotContain("secret123")
                .doesNotContain("tok_secret")
                .doesNotContain("password")
                .doesNotContain("cvv");
          }
        })
        .verifyComplete();
  }

}
