package com.cloudmall.product.config;

import java.util.Map;
import org.springframework.amqp.core.*;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.RetryInterceptorBuilder;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.amqp.rabbit.retry.RepublishMessageRecoverer;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.amqp.support.converter.SimpleMessageConverter;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;

@Configuration
@EnableScheduling
public class ProductMessagingConfiguration {
  /** 商品变更事件使用的持久化直连交换机名称。 */
  public static final String PRODUCT_EXCHANGE = "cloudmall.product.exchange";

  /** 商品变更事件路由到内容索引队列的固定路由键。 */
  public static final String PRODUCT_ROUTING_KEY = "product.changed";

  /** 承接商品内容索引事件的持久化队列名称。 */
  public static final String PRODUCT_INDEX_QUEUE = "cloudmall.product.index.queue";

  /** 索引消费者使用的原字节转换及三次本地重试容器 Bean 名。 */
  public static final String PRODUCT_INDEX_CONTAINER_FACTORY = "productIndexContainerFactory";

  /** 商品索引 DLQ 手动确认容器。 */
  public static final String PRODUCT_INDEX_DLQ_CONTAINER_FACTORY =
      "productIndexDlqContainerFactory";

  /** 索引死信和延迟重投共用的持久化直连交换机名称。 */
  public static final String PRODUCT_INDEX_DLX = "cloudmall.product.index.dlx";

  /** 保存索引失败消息的队列名称，同时用作其死信路由键。 */
  public static final String PRODUCT_INDEX_DLQ = "cloudmall.product.index.dlq";

  /** 证据落库失败时保留原字节、延迟十秒再回到 DLQ。 */
  public static final String PRODUCT_INDEX_DLQ_RETRY = "cloudmall.product.index.dlq.retry";

  /** 为通用 Rabbit 消息提供 JSON 转换器；索引监听容器另行使用原字节转换。 */
  @Bean
  Jackson2JsonMessageConverter rabbitJsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  /** 声明持久化、非自动删除的商品变更交换机。 */
  @Bean
  DirectExchange productExchange() {
    return new DirectExchange(PRODUCT_EXCHANGE, true, false);
  }

  /** 声明持久化、非自动删除的索引死信交换机。 */
  @Bean
  DirectExchange productIndexDeadLetterExchange() {
    return new DirectExchange(PRODUCT_INDEX_DLX, true, false);
  }

  /** 声明索引工作队列，并将被死信处理的消息路由到索引死信队列。 */
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

  /** 声明索引死信队列，供原字节隔离与受控恢复消费。 */
  @Bean
  Queue productIndexDeadLetterQueue() {
    return new Queue(PRODUCT_INDEX_DLQ, true);
  }

  /** 声明十秒 TTL 重投队列，过期后经原死信交换机返回索引死信队列。 */
  @Bean
  Queue productIndexDeadLetterRetryQueue() {
    return new Queue(
        PRODUCT_INDEX_DLQ_RETRY,
        true,
        false,
        false,
        Map.of(
            "x-message-ttl",
            10000,
            "x-dead-letter-exchange",
            PRODUCT_INDEX_DLX,
            "x-dead-letter-routing-key",
            PRODUCT_INDEX_DLQ));
  }

  /** 将死信证据保存失败后的重投路由绑定到十秒延迟队列。 */
  @Bean
  Binding productIndexDeadLetterRetryBinding(
      @Qualifier("productIndexDeadLetterRetryQueue") Queue retryQueue,
      @Qualifier("productIndexDeadLetterExchange") DirectExchange exchange) {
    return BindingBuilder.bind(retryQueue).to(exchange).with(PRODUCT_INDEX_DLQ_RETRY);
  }

  /** 按固定商品变更路由键将商品交换机绑定到索引工作队列。 */
  @Bean
  Binding productIndexBinding(
      @Qualifier("productIndexQueue") Queue productIndexQueue,
      @Qualifier("productExchange") DirectExchange productExchange) {
    return BindingBuilder.bind(productIndexQueue).to(productExchange).with(PRODUCT_ROUTING_KEY);
  }

  /** 将索引死信路由键绑定到持久化死信队列。 */
  @Bean
  Binding productIndexDeadLetterBinding(
      @Qualifier("productIndexDeadLetterQueue") Queue productIndexDeadLetterQueue,
      @Qualifier("productIndexDeadLetterExchange") DirectExchange productIndexDeadLetterExchange) {
    return BindingBuilder.bind(productIndexDeadLetterQueue)
        .to(productIndexDeadLetterExchange)
        .with(PRODUCT_INDEX_DLQ);
  }

  /** 索引消费保留原字节，失败最多本地尝试三次，再由恢复器发布到索引死信队列。 */
  @Bean
  SimpleRabbitListenerContainerFactory productIndexContainerFactory(
      ConnectionFactory connectionFactory, RabbitTemplate rabbitTemplate) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(rawBodyConverter());
    RepublishMessageRecoverer recoverer =
        new RepublishMessageRecoverer(rabbitTemplate, PRODUCT_INDEX_DLX, PRODUCT_INDEX_DLQ);
    factory.setAdviceChain(
        RetryInterceptorBuilder.stateless().maxAttempts(3).recoverer(recoverer).build());
    return factory;
  }

  /** 死信消费采用手动 ACK，由消费者在证据保存或确认转投后处理原 delivery tag。 */
  @Bean
  SimpleRabbitListenerContainerFactory productIndexDlqContainerFactory(
      ConnectionFactory connectionFactory, RabbitTemplate rabbitTemplate) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setMessageConverter(rawBodyConverter());
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    return factory;
  }

  /** 消费方法自行验证 envelope；禁止按 content type 反序列化旧 Java 字节。 */
  static SimpleMessageConverter rawBodyConverter() {
    return new SimpleMessageConverter() {
      /** 直接交付消息原字节，不根据 content type 触发 Java 对象反序列化。 */
      @Override
      public Object fromMessage(Message message) {
        return message.getBody();
      }
    };
  }
}
