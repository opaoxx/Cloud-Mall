package com.cloudmall.order.config;

import java.util.Map;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.BindingBuilder;
import org.springframework.amqp.core.DirectExchange;
import org.springframework.amqp.core.Queue;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.support.converter.Jackson2JsonMessageConverter;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

@Configuration
public class OrderMessagingConfiguration {
  /** 未来超时事件先进入的直连交换机名。 */
  public static final String TIMEOUT_EXCHANGE = "cloudmall.order.timeout.exchange";

  /** 按消息TTL等待的持久队列名。 */
  public static final String TIMEOUT_QUEUE = "cloudmall.order.timeout.ttl";

  /** 超时事件到期或直接投递后进入的交换机名。 */
  public static final String DLX = "cloudmall.order.timeout.dlx.exchange";

  /** 执行数据库期限核验和关单的最终队列名。 */
  public static final String DLQ = "cloudmall.order.timeout.dlx";

  /** 超时监听使用的队列兼容别名，与DLQ指向同一队列。 */
  public static final String TIMEOUT_DLQ = DLQ;

  /** 秒杀受理成功后发布落单事件的交换机名。 */
  public static final String SECKILL_EXCHANGE = "cloudmall.seckill.order.exchange";

  /** 执行秒杀落单事务的消费队列名。 */
  public static final String SECKILL_QUEUE = "cloudmall.seckill.order.queue";

  /** 秒杀消费拒绝后接收消息的死信交换机名。 */
  public static final String SECKILL_DLX = "cloudmall.seckill.order.dlx.exchange";

  /** 保存秒杀死信证据并驱动恢复的队列名。 */
  public static final String SECKILL_DLQ = "cloudmall.seckill.order.dlx";

  /** DB 不可用时的有界延迟重入交换机。 */
  public static final String SECKILL_DLQ_RETRY_EXCHANGE =
      "cloudmall.seckill.order.dlx.retry.exchange";

  /** DB 不可用时的有界延迟重入队列。 */
  public static final String SECKILL_DLQ_RETRY_QUEUE = "cloudmall.seckill.order.dlx.retry";

  /** 注册原Rabbit JSON转换器，供默认消息序列化和监听转换使用。 */
  @Bean
  Jackson2JsonMessageConverter rabbitJsonMessageConverter() {
    return new Jackson2JsonMessageConverter();
  }

  /** 声明接收尚未到期超时事件的直连交换机。 */
  @Bean
  DirectExchange timeoutExchange() {
    return new DirectExchange(TIMEOUT_EXCHANGE);
  }

  /** 声明TTL到期及已到期事件直接投递使用的原超时交换机。 */
  @Bean
  DirectExchange timeoutDlx() {
    return new DirectExchange(DLX);
  }

  /** 声明持久等待队列，逐消息TTL到期后转入原超时DLX。 */
  @Bean
  Queue timeoutTtlQueue() {
    return new Queue(TIMEOUT_QUEUE, true, false, false, Map.of("x-dead-letter-exchange", DLX));
  }

  /** 声明最终超时消费队列，当前拓扑未配置二级DLX。 */
  @Bean
  Queue timeoutDeadLetterQueue() {
    return new Queue(DLQ, true);
  }

  /** 以原空路由键将超时交换机绑定到TTL等待队列。 */
  @Bean
  Binding timeoutBinding() {
    return BindingBuilder.bind(timeoutTtlQueue()).to(timeoutExchange()).with("");
  }

  /** 以原空路由键将超时DLX绑定到最终消费队列。 */
  @Bean
  Binding deadLetterBinding() {
    return BindingBuilder.bind(timeoutDeadLetterQueue()).to(timeoutDlx()).with("");
  }

  /** 声明秒杀落单消息的直连交换机。 */
  @Bean
  DirectExchange seckillExchange() {
    return new DirectExchange(SECKILL_EXCHANGE);
  }

  /** 声明秒杀消息重试耗尽后使用的死信交换机。 */
  @Bean
  DirectExchange seckillDlx() {
    return new DirectExchange(SECKILL_DLX);
  }

  /** 声明持久秒杀消费队列，被拒绝的消息转入原秒杀DLX。 */
  @Bean
  Queue seckillQueue() {
    return new Queue(
        SECKILL_QUEUE, true, false, false, Map.of("x-dead-letter-exchange", SECKILL_DLX));
  }

  /** 声明秒杀死信持久化与恢复监听使用的队列。 */
  @Bean
  Queue seckillDeadLetterQueue() {
    return new Queue(SECKILL_DLQ, true);
  }

  /** 以原空路由键将秒杀落单交换机绑定到消费队列。 */
  @Bean
  Binding seckillBinding() {
    return BindingBuilder.bind(seckillQueue()).to(seckillExchange()).with("");
  }

  /** 以原空路由键将秒杀DLX绑定到死信恢复队列。 */
  @Bean
  Binding seckillDeadLetterBinding() {
    return BindingBuilder.bind(seckillDeadLetterQueue()).to(seckillDlx()).with("");
  }

  /** 声明死信暂时无法持久化时延迟重投使用的交换机。 */
  @Bean
  DirectExchange seckillDlqRetryExchange() {
    return new DirectExchange(SECKILL_DLQ_RETRY_EXCHANGE);
  }

  /** 声明10秒TTL恢复缓冲队列，到期后重新进入原秒杀DLX。 */
  @Bean
  Queue seckillDlqRetryQueue() {
    return new Queue(
        SECKILL_DLQ_RETRY_QUEUE,
        true,
        false,
        false,
        Map.of("x-message-ttl", 10000, "x-dead-letter-exchange", SECKILL_DLX));
  }

  /** 以原空路由键将死信恢复交换机绑定到延迟重入队列。 */
  @Bean
  Binding seckillDlqRetryBinding() {
    return BindingBuilder.bind(seckillDlqRetryQueue()).to(seckillDlqRetryExchange()).with("");
  }

  /** 死信落库使用手动确认，数据库失败时由恢复队列延迟重入，避免丢消息或热循环。 */
  @Bean("seckillDlqContainerFactory")
  SimpleRabbitListenerContainerFactory seckillDlqContainerFactory(
      ConnectionFactory connectionFactory) {
    SimpleRabbitListenerContainerFactory factory = new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setDefaultRequeueRejected(false);
    return factory;
  }
}
