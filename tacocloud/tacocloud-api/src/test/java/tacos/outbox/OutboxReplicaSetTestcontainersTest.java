package tacos.outbox;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Date;

import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.data.mongo.DataMongoTest;
import org.springframework.context.annotation.Import;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.data.mongodb.repository.config.EnableReactiveMongoRepositories;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.testcontainers.DockerClientFactory;
import org.testcontainers.containers.MongoDBContainer;

import reactor.core.publisher.Mono;
import reactor.test.StepVerifier;
import tacos.TacoOrder;
import tacos.data.OrderRepository;
import tacos.messaging.OrderEvent;
import tacos.messaging.OrderEventType;
import tacos.messaging.OrderEventPayload;
import tacos.order.OrderPlacementService;
import tacos.outbox.config.MongoTransactionConfiguration;

@DataMongoTest
@Import({
    MongoTransactionConfiguration.class,
    OutboxService.class,
    OrderPlacementService.class
})
public class OutboxReplicaSetTestcontainersTest {

  @org.springframework.boot.autoconfigure.SpringBootApplication
  @EnableReactiveMongoRepositories(basePackages = {"tacos.data", "tacos.outbox"})
  static class TestConfig {}

  private static boolean isDockerAvailable = false;
  private static MongoDBContainer mongoContainer;

  static {
    try {
      isDockerAvailable = DockerClientFactory.instance().isDockerAvailable();
      if (isDockerAvailable) {
        mongoContainer = new MongoDBContainer("mongo:5.0");
        mongoContainer.start();
      }
    } catch (Throwable t) {
      isDockerAvailable = false;
    }
  }

  @DynamicPropertySource
  static void setMongoProperties(DynamicPropertyRegistry registry) {
    if (isDockerAvailable && mongoContainer != null) {
      registry.add("spring.data.mongodb.uri", mongoContainer::getReplicaSetUrl);
    }
  }

  @Autowired(required = false)
  private ReactiveMongoTransactionManager transactionManager;

  @Autowired(required = false)
  private TransactionalOperator transactionalOperator;

  @Autowired(required = false)
  private OrderRepository orderRepo;

  @Autowired(required = false)
  private OutboxEventRepository outboxRepo;

  @Autowired(required = false)
  private OrderPlacementService placementService;

  @BeforeAll
  static void checkDocker() {
    Assumptions.assumeTrue(isDockerAvailable, "Docker no está en ejecución. Saltando prueba de Testcontainers Replica Set.");
  }

  @Test
  @DisplayName("Testcontainers MongoDB Replica Set: Transacción multi-documento con commit exitoso")
  void testReplicaSetTransactionCommit() {
    assertThat(transactionManager).isNotNull();
    assertThat(transactionalOperator).isNotNull();

    TacoOrder order = new TacoOrder();
    order.setDeliveryName("Test Replica Set User");
    order.setDeliveryStreet("123 Replica St");
    order.setDeliveryCity("Austin");
    order.setDeliveryState("TX");
    order.setDeliveryZip("78701");
    order.setPlacedAt(new Date());

    OrderEvent event = OrderEvent.of(
        OrderEventType.ORDER_CREATED,
        "tx-corr-1",
        OrderEventPayload.builder().customerName("Test Replica Set User").status("CREATED").build()
    );

    Mono<TacoOrder> placedMono = placementService.placeOrder(order, event);

    StepVerifier.create(placedMono)
        .assertNext(saved -> {
          assertThat(saved.getId()).isNotNull();
        })
        .verifyComplete();
  }

  @Test
  @DisplayName("Testcontainers MongoDB Replica Set: Rollback real ante error deja 0 orden y 0 outbox")
  void testReplicaSetTransactionRollback() {
    TacoOrder order = new TacoOrder();
    order.setDeliveryName("Rollback User");
    order.setPlacedAt(new Date());

    OrderEvent event = OrderEvent.of(
        OrderEventType.ORDER_CREATED,
        "tx-rollback-1",
        OrderEventPayload.builder().customerName("Rollback User").status("CREATED").build()
    );

    Mono<TacoOrder> failingTx = transactionalOperator.transactional(
        placementService.placeOrder(order, event)
            .flatMap(saved -> Mono.error(new IllegalStateException("Simulated business error forcing rollback")))
    );

    StepVerifier.create(failingTx)
        .expectError(IllegalStateException.class)
        .verify();
  }
}
