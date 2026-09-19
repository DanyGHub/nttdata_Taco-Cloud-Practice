package tacos.data;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.query.Query;
import org.springframework.data.mongodb.core.query.Update;
import org.springframework.stereotype.Service;

import com.mongodb.client.result.UpdateResult;

import reactor.core.publisher.Mono;

@Service
public class PaymentDataMigrationService {

  private static final Logger log = LoggerFactory.getLogger(PaymentDataMigrationService.class);

  private final ReactiveMongoTemplate mongoTemplate;

  public PaymentDataMigrationService(ReactiveMongoTemplate mongoTemplate) {
    this.mongoTemplate = mongoTemplate;
  }

  public Mono<MigrationSummary> purgeLegacySensitiveCardData() {
    log.info("Starting migration: purging ccNumber, ccCVV, ccExpiration from MongoDB...");

    Update unsetUpdate = new Update()
        .unset("ccNumber")
        .unset("ccCVV")
        .unset("ccExpiration");

    Query anyQuery = new Query();

    Mono<Long> ordersUpdated = mongoTemplate.updateMulti(anyQuery, unsetUpdate, "tacoOrder")
        .map(UpdateResult::getModifiedCount)
        .defaultIfEmpty(0L);

    Mono<Long> paymentMethodsUpdated = mongoTemplate.updateMulti(anyQuery, unsetUpdate, "paymentMethod")
        .map(UpdateResult::getModifiedCount)
        .defaultIfEmpty(0L);

    return Mono.zip(ordersUpdated, paymentMethodsUpdated)
        .map(tuple -> {
          long ordersCount = tuple.getT1();
          long pmCount = tuple.getT2();
          log.info("Migration completed: purged {} orders, {} payment methods", ordersCount, pmCount);
          return new MigrationSummary(ordersCount, pmCount);
        });
  }

  public static class MigrationSummary {
    private final long ordersModified;
    private final long paymentMethodsModified;

    public MigrationSummary(long ordersModified, long paymentMethodsModified) {
      this.ordersModified = ordersModified;
      this.paymentMethodsModified = paymentMethodsModified;
    }

    public long getOrdersModified() {
      return ordersModified;
    }

    public long getPaymentMethodsModified() {
      return paymentMethodsModified;
    }
  }
}
