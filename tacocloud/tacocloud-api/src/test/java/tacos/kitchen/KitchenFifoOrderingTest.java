package tacos.kitchen;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Arrays;
import java.util.Date;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;

import reactor.core.publisher.Flux;
import reactor.test.StepVerifier;
import tacos.Taco;
import tacos.TacoOrder;
import tacos.kitchen.dto.KitchenOrderDto;
import tacos.order.OrderStatus;

@DisplayName("TC-26: Ordenamiento FIFO estable en cola de cocina")
public class KitchenFifoOrderingTest {

  private ReactiveMongoTemplate mongoTemplate;
  private KitchenEtaCalculator etaCalculator;
  private KitchenQueueService queueService;

  @BeforeEach
  public void setUp() {
    mongoTemplate = mock(ReactiveMongoTemplate.class);
    etaCalculator = new KitchenEtaCalculator();
    queueService = new KitchenQueueService(mongoTemplate, etaCalculator, null);
  }

  @Test
  @DisplayName("1. GET /api/kitchen/queue devuelve órdenes en estricto orden FIFO con posición y ETA incremental")
  public void testQueueFifoOrderAndIncrementalEta() {
    TacoOrder order1 = new TacoOrder();
    order1.setId("order-1");
    order1.setStatus(OrderStatus.CREATED);
    order1.setPlacedAt(new Date(10000));
    order1.setDeliveryName("Customer 1");
    Taco taco1 = new Taco();
    taco1.setName("Taco A");
    order1.addTaco(taco1);

    TacoOrder order2 = new TacoOrder();
    order2.setId("order-2");
    order2.setStatus(OrderStatus.CREATED);
    order2.setPlacedAt(new Date(20000));
    order2.setDeliveryName("Customer 2");
    Taco taco2 = new Taco();
    taco2.setName("Taco B");
    order2.addTaco(taco2);

    TacoOrder order3 = new TacoOrder();
    order3.setId("order-3");
    order3.setStatus(OrderStatus.CREATED);
    order3.setPlacedAt(new Date(30000));
    order3.setDeliveryName("Customer 3");
    Taco taco3 = new Taco();
    taco3.setName("Taco C");
    order3.addTaco(taco3);

    when(mongoTemplate.find(any(Query.class), eq(TacoOrder.class)))
        .thenReturn(Flux.just(order1, order2, order3));

    StepVerifier.create(queueService.getQueue())
        .assertNext(queue -> {
          assertThat(queue).hasSize(3);

          KitchenOrderDto first = queue.get(0);
          assertThat(first.getId()).isEqualTo("order-1");
          assertThat(first.getQueuePosition()).isEqualTo(1);
          assertThat(first.getEstimatedPrepMinutes()).isEqualTo(5); // base 3 + 2 = 5 min

          KitchenOrderDto second = queue.get(1);
          assertThat(second.getId()).isEqualTo("order-2");
          assertThat(second.getQueuePosition()).isEqualTo(2);
          assertThat(second.getEstimatedPrepMinutes()).isEqualTo(10); // 5 + 5 = 10 min

          KitchenOrderDto third = queue.get(2);
          assertThat(third.getId()).isEqualTo("order-3");
          assertThat(third.getQueuePosition()).isEqualTo(3);
          assertThat(third.getEstimatedPrepMinutes()).isEqualTo(15); // 10 + 5 = 15 min

          // Garantizar que ETA aumenta estrictamente con la posición en la cola
          assertThat(second.getEstimatedPrepMinutes()).isGreaterThan(first.getEstimatedPrepMinutes());
          assertThat(third.getEstimatedPrepMinutes()).isGreaterThan(second.getEstimatedPrepMinutes());
        })
        .verifyComplete();
  }

}
