package com.cloudmall.product.config;

import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class ProductMessagingConfiguration {
  /** 保存 PRODUCT_EXCHANGE 的业务状态或配置。 */
  public static final String PRODUCT_EXCHANGE = "cloudmall.product.exchange";

  /** 保存 PRODUCT_ROUTING_KEY 的业务状态或配置。 */
  public static final String PRODUCT_ROUTING_KEY = "product.changed";

  /** 保存 PRODUCT_INDEX_QUEUE 的业务状态或配置。 */
  public static final String PRODUCT_INDEX_QUEUE = "cloudmall.product.index.queue";

  /** 保存 PRODUCT_INDEX_CONTAINER_FACTORY 的业务状态或配置。 */
  public static final String PRODUCT_INDEX_CONTAINER_FACTORY = "productIndexContainerFactory";

  /** 保存 PRODUCT_INDEX_DLX 的业务状态或配置。 */
  private static final String PRODUCT_INDEX_DLX = "cloudmall.product.index.dlx";

  /** 保存 PRODUCT_INDEX_DLQ 的业务状态或配置。 */
  private static final String PRODUCT_INDEX_DLQ = "cloudmall.product.index.dlq";

  @Bean
  Jackson2JsonMessageConverter rabbitJsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  @Bean
  DirectExchange productExchange() {
    return new DirectExchange(PRODUCT_EXCHANGE, true, false);
  }

  @Bean
  DirectExchange productIndexDeadLetterExchange() {
    return new DirectExchange(PRODUCT_INDEX_DLX, true, false);
  }

  @Bean
  Queue productIndexQueue() {
    return new Queue(
        PRODUCT_INDEX_QUEUE,
        true,
        false,
        false,
        Map.of(
            "x-dead-letter-exchange",
            PRODUCT_INDEX_DLX,
            "x-dead-letter-routing-key",
            PRODUCT_INDEX_DLQ));
  }

  @Bean
  Queue productIndexDeadLetterQueue() {
    return new Queue(PRODUCT_INDEX_DLQ, true);
  }

  @Bean
  Binding productIndexBinding(
      @Qualifier("productIndexQueue") Queue productIndexQueue,
      @Qualifier("productExchange") DirectExchange productExchange) {
    return BindingBuilder.bind(productIndexQueue).to(productExchange).with(PRODUCT_ROUTING_KEY);
  }

  @Bean
  Binding productIndexDeadLetterBinding(
      @Qualifier("productIndexDeadLetterQueue") Queue productIndexDeadLetterQueue,
      @Qualifier("productIndexDeadLetterExchange") DirectExchange productIndexDeadLetterExchange) {
    return BindingBuilder.bind(productIndexDeadLetterQueue)
        .to(productIndexDeadLetterExchange)
        .with(PRODUCT_INDEX_DLQ);
  }

  @Bean
  SimpleRabbitListenerContainerFactory productIndexContainerFactory(
      ConnectionFactory connectionFactory, RabbitTemplate rabbitTemplate) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(new Jackson2JsonMessageConverter());
    RepublishMessageRecoverer recoverer =
        new RepublishMessageRecoverer(rabbitTemplate, PRODUCT_INDEX_DLX, PRODUCT_INDEX_DLQ);
    factory.setAdviceChain(
        RetryInterceptorBuilder.stateless().maxAttempts(3).recoverer(recoverer).build());
    return factory;
  }
}
