package tacos.kitchen.messaging.rabbit;

import java.util.HashMap;
import java.util.Map;

import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.core.QueueBuilder;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.retry.backoff.ExponentialBackOffPolicy;
import org.springframework.retry.interceptor.RetryOperationsInterceptor;
import org.springframework.retry.policy.SimpleRetryPolicy;
import org.springframework.retry.support.RetryTemplate;

import tacos.kitchen.exception.PermanentProcessingException;

@Profile({"rabbitmq-template", "rabbitmq-listener"})
@Configuration
public class KitchenRabbitMessagingConfig {

  @Value("${tacocloud.messaging.rabbit.destination:${tacocloud.messaging.rabbit.queue:tacocloud.order.queue}}")
  private String orderQueueName;

  @Value("${tacocloud.messaging.rabbit.exchange:tacocloud.order.exchange}")
  private String orderExchangeName;

  @Value("${tacocloud.messaging.rabbit.dlx:tacocloud.order.dlx}")
  private String dlxExchangeName;

  @Value("${tacocloud.messaging.rabbit.dlq:tacocloud.order.queue.dlq}")
  private String dlqQueueName;

  @Value("${tacocloud.messaging.consumer.retry.max-attempts:3}")
  private int maxAttempts;

  @Value("${tacocloud.messaging.consumer.retry.initial-interval:1000}")
  private long initialInterval;

  @Value("${tacocloud.messaging.consumer.retry.multiplier:2.0}")
  private double multiplier;

  @Value("${tacocloud.messaging.consumer.retry.max-interval:10000}")
  private long maxInterval;

  @Bean
  public Jackson2JsonMessageConverter messageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  // 1. Dead Letter Exchange
  @Bean
  public DirectExchange deadLetterExchange() {
    return new DirectExchange(dlxExchangeName, true, false);
  }

  // 2. Dead Letter Queue
  @Bean
  public Queue deadLetterQueue() {
    return QueueBuilder.durable(dlqQueueName).build();
  }

  // 3. Bind Dead Letter Queue to Dead Letter Exchange
  @Bean
  public Binding deadLetterBinding(Queue deadLetterQueue, DirectExchange deadLetterExchange) {
    return BindingBuilder.bind(deadLetterQueue).to(deadLetterExchange).with(dlqQueueName);
  }

  // 4. Main Order Queue with DLX configuration
  @Bean
  public Queue orderQueue() {
    return QueueBuilder.durable(orderQueueName)
        .withArgument("x-dead-letter-exchange", dlxExchangeName)
        .withArgument("x-dead-letter-routing-key", dlqQueueName)
        .build();
  }

  // 5. Main Direct Exchange
  @Bean
  public DirectExchange orderExchange() {
    return new DirectExchange(orderExchangeName, true, false);
  }

  // 6. Bind Order Queue to Main Exchange
  @Bean
  public Binding orderBinding(Queue orderQueue, DirectExchange orderExchange) {
    return BindingBuilder.bind(orderQueue).to(orderExchange).with(orderQueueName);
  }

  // 7. RetryTemplate with PermanentProcessingException excluded
  @Bean
  public RetryTemplate consumerRetryTemplate() {
    Map<Class<? extends Throwable>, Boolean> retryableExceptions = new HashMap<>();
    retryableExceptions.put(PermanentProcessingException.class, false);
    retryableExceptions.put(Exception.class, true);

    SimpleRetryPolicy retryPolicy = new SimpleRetryPolicy(maxAttempts, retryableExceptions, true);

    ExponentialBackOffPolicy backOffPolicy = new ExponentialBackOffPolicy();
    backOffPolicy.setInitialInterval(initialInterval);
    backOffPolicy.setMultiplier(multiplier);
    backOffPolicy.setMaxInterval(maxInterval);

    RetryTemplate retryTemplate = new RetryTemplate();
    retryTemplate.setRetryPolicy(retryPolicy);
    retryTemplate.setBackOffPolicy(backOffPolicy);
    return retryTemplate;
  }

  // 8. RepublishMessageRecoverer sending exhausted messages to DLQ
  @Bean
  public RepublishMessageRecoverer dlqMessageRecoverer(RabbitTemplate rabbitTemplate) {
    RepublishMessageRecoverer recoverer = new RepublishMessageRecoverer(
        rabbitTemplate, dlxExchangeName, dlqQueueName);
    return recoverer;
  }

  // 9. Retry Operations Interceptor
  @Bean
  public RetryOperationsInterceptor retryOperationsInterceptor(
      RetryTemplate consumerRetryTemplate,
      RepublishMessageRecoverer dlqMessageRecoverer) {
    return RetryInterceptorBuilder.stateless()
        .retryOperations(consumerRetryTemplate)
        .recoverer(dlqMessageRecoverer)
        .build();
  }

  // 10. Container factory applying JSON converter, auto-ack after durable effect, and retry advice
  @Bean
  public SimpleRabbitListenerContainerFactory rabbitListenerContainerFactory(
      ConnectionFactory connectionFactory,
      Jackson2JsonMessageConverter messageConverter,
      RetryOperationsInterceptor retryOperationsInterceptor) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(messageConverter);
    factory.setAcknowledgeMode(AcknowledgeMode.AUTO);
    factory.setAdviceChain(retryOperationsInterceptor);
    return factory;
  }

}
