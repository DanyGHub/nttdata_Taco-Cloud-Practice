package tacos.outbox.config;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.mongodb.ReactiveMongoDatabaseFactory;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.transaction.reactive.TransactionalOperator;

@Configuration
public class MongoTransactionConfiguration {

  private static final Logger log = LoggerFactory.getLogger(MongoTransactionConfiguration.class);

  @Bean
  public ReactiveMongoTransactionManager reactiveMongoTransactionManager(ReactiveMongoDatabaseFactory dbFactory) {
    log.info("Registering ReactiveMongoTransactionManager for transactional operations (Replica Set required)");
    return new ReactiveMongoTransactionManager(dbFactory);
  }

  @Bean
  public TransactionalOperator transactionalOperator(ReactiveMongoTransactionManager txManager) {
    return TransactionalOperator.create(txManager);
  }

}
