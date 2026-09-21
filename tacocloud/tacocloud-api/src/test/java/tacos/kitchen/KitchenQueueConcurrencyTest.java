package tacos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.FindAndModifyOptions;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.security.authentication.UsernamePasswordAuthenticationToken;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.authority.SimpleGrantedAuthority;

import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.kitchen.dto.KitchenClaimRequest;
import tacos.kitchen.dto.KitchenOrderDto;
import tacos.order.OrderStatus;

@DisplayName("TC-26: Pruebas de concurrencia y claim atómico en cola de cocina")
public class KitchenQueueConcurrencyTest {

  private ReactiveMongoTemplate mongoTemplate;
  private KitchenEtaCalculator etaCalculator;
  private KitchenQueueService queueService;

  private Authentication authChef1;
  private Authentication authChef2;

  @BeforeEach
  public void setUp() {
    mongoTemplate = mock(ReactiveMongoTemplate.class);
    etaCalculator = new KitchenEtaCalculator();
    queueService = new KitchenQueueService(mongoTemplate, etaCalculator, null);

    authChef1 = new UsernamePasswordAuthenticationToken("chef1", "pass",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_KITCHEN")));
    authChef2 = new UsernamePasswordAuthenticationToken("chef2", "pass",
        Collections.singletonList(new SimpleGrantedAuthority("ROLE_KITCHEN")));
  }

  @Test
  @DisplayName("1. Dos estaciones reclamando concurrentemente una sola orden en cola: solo una gana, la otra recibe vacío")
  public void testConcurrentClaimsSingleOrderOnlyOneWins() {
    // Ninguna estación tiene orden activa
    when(mongoTemplate.exists(any(Query.class), eq(TacoOrder.class))).thenReturn(Mono.just(false));

    TacoOrder singleOrder = new TacoOrder();
    singleOrder.setId("order-single-1");
    singleOrder.setStatus(OrderStatus.CREATED);
    singleOrder.setPlacedAt(new Date());

    AtomicInteger calls = new AtomicInteger(0);

    // Simular que el primer findAndModify atómico en Mongo encuentra la orden y la modifica,
    // mientras que el segundo llamado ya no encuentra nada (cola vacía)
    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TacoOrder.class)))
        .thenAnswer(inv -> {
          int callIndex = calls.incrementAndGet();
          if (callIndex == 1) {
            singleOrder.setStatus(OrderStatus.ACCEPTED);
            singleOrder.setStationId("STATION_A");
            singleOrder.setCookId("chef1");
            return Mono.just(singleOrder);
          } else {
            return Mono.empty(); // Ya no hay órdenes CREATED
          }
        });

    KitchenClaimRequest req1 = KitchenClaimRequest.builder().stationId("STATION_A").cookId("chef1").build();
    KitchenClaimRequest req2 = KitchenClaimRequest.builder().stationId("STATION_B").cookId("chef2").build();

    Mono<KitchenOrderDto> claim1 = queueService.claimNext(req1, authChef1);
    Mono<KitchenOrderDto> claim2 = queueService.claimNext(req2, authChef2);

    List<KitchenOrderDto> results = Collections.synchronizedList(new ArrayList<>());

    StepVerifier.create(Flux.merge(claim1, claim2))
        .recordWith(() -> results)
        .expectNextCount(1) // Solo 1 estación logra reclamar la orden
        .verifyComplete();

    assertThat(results).hasSize(1);
    KitchenOrderDto claimed = results.get(0);
    assertThat(claimed.getId()).isEqualTo("order-single-1");
    assertThat(claimed.getStatus()).isEqualTo(OrderStatus.ACCEPTED);
    assertThat(claimed.getStationId()).isEqualTo("STATION_A");
  }

  @Test
  @DisplayName("2. Dos estaciones reclamando concurrentemente cuando hay dos órdenes: cada una obtiene una orden distinta")
  public void testConcurrentClaimsMultipleOrdersGetDistinctOrders() {
    when(mongoTemplate.exists(any(Query.class), eq(TacoOrder.class))).thenReturn(Mono.just(false));

    TacoOrder order1 = new TacoOrder();
    order1.setId("order-fifo-1");
    order1.setStatus(OrderStatus.CREATED);
    order1.setPlacedAt(new Date(1000));

    TacoOrder order2 = new TacoOrder();
    order2.setId("order-fifo-2");
    order2.setStatus(OrderStatus.CREATED);
    order2.setPlacedAt(new Date(2000));

    AtomicInteger calls = new AtomicInteger(0);

    when(mongoTemplate.findAndModify(any(Query.class), any(Update.class), any(FindAndModifyOptions.class), eq(TacoOrder.class)))
        .thenAnswer(inv -> {
          int callIndex = calls.incrementAndGet();
          if (callIndex == 1) {
            order1.setStatus(OrderStatus.ACCEPTED);
            order1.setStationId("STATION_A");
            return Mono.just(order1);
          } else if (callIndex == 2) {
            order2.setStatus(OrderStatus.ACCEPTED);
            order2.setStationId("STATION_B");
            return Mono.just(order2);
          }
          return Mono.empty();
        });

    KitchenClaimRequest req1 = KitchenClaimRequest.builder().stationId("STATION_A").cookId("chef1").build();
    KitchenClaimRequest req2 = KitchenClaimRequest.builder().stationId("STATION_B").cookId("chef2").build();

    List<KitchenOrderDto> results = Collections.synchronizedList(new ArrayList<>());

    StepVerifier.create(Flux.merge(queueService.claimNext(req1, authChef1), queueService.claimNext(req2, authChef2)))
        .recordWith(() -> results)
        .expectNextCount(2)
        .verifyComplete();

    assertThat(results).hasSize(2);
    assertThat(results.get(0).getId()).isNotEqualTo(results.get(1).getId());
    List<String> claimedIds = new ArrayList<>();
    claimedIds.add(results.get(0).getId());
    claimedIds.add(results.get(1).getId());
    assertThat(claimedIds).containsExactlyInAnyOrder("order-fifo-1", "order-fifo-2");
  }

}
