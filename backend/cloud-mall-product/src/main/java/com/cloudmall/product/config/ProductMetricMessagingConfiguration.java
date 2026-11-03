package com.cloudmall.product.config;

import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.ProductMetricBatchListener;
import com.cloudmall.product.service.ProductMetricConsumer;
import com.cloudmall.product.service.ProductMetricSingleListener;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricRawBatchingStrategy;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.*;
import org.springframework.core.env.Environment;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;

/** Enabled-only manual metrics topology; the existing content listener keeps its AUTO protocol. */
@Configuration(proxyBeanMethods = false)
@ConditionalOnProperty(name = "cloudmall.product.metrics.enabled", havingValue = "true")
public class ProductMetricMessagingConfiguration {
  /** Original Product metrics mapper used for migration and resource admission. */
  private final ProductMetricMapper mapper;

  /** Existing Product transaction manager, never a new metrics manager. */
  private final PlatformTransactionManager manager;

  /** Final environment supplies bounded dispatcher intervals. */
  private final Environment environment;

  /** One instance is shared explicitly even though configuration proxying is disabled. */
  private final ProductMetricBatchChannelRegistry batchChannels =
      new ProductMetricBatchChannelRegistry();

  /** Construct enabled-mode admission dependencies. */
  public ProductMetricMessagingConfiguration(
      ProductMetricMapper mapper, PlatformTransactionManager manager, Environment environment) {
    this.mapper = mapper;
    this.manager = manager;
    this.environment = environment;
  }

  /** Fail enabled startup if the migration, transaction resource or scheduling bounds differ. */
  @PostConstruct
  public void validate() {
    batchEnabled();
    if (!(manager instanceof DataSourceTransactionManager local)
        || local.getDataSource() != mapper.dataSource())
      throw new IllegalStateException("METRIC_TRANSACTION_RESOURCE_MISMATCH");
    for (String name : new String[] {"dispatch", "projection"}) {
      long delay =
          environment.getProperty(
              "cloudmall.product.metrics." + name + "-interval-ms", Long.class, 1000L);
      if (delay < 100 || delay > 60000) throw new IllegalStateException("METRIC_INTERVAL_INVALID");
    }
    mapper.verifySchema();
  }

  /** Declare only the reviewed durable queues and exact TTL/DLX arguments. */
  @Bean
  public Declarables metricTopology() {
    DirectExchange exchange = new DirectExchange("cloudmall.product.metrics.exchange", true, false);
    DirectExchange dlx = new DirectExchange("cloudmall.product.metrics.dlx", true, false);
    Queue main =
        new Queue(
            "cloudmall.product.metrics.queue",
            true,
            false,
            false,
            Map.of(
                "x-dead-letter-exchange",
                dlx.getName(),
                "x-dead-letter-routing-key",
                "cloudmall.product.metrics.dlq"));
    Queue parking = new Queue("cloudmall.product.metrics.dlq", true);
    Queue retry =
        new Queue(
            "cloudmall.product.metrics.retry",
            true,
            false,
            false,
            Map.of(
                "x-message-ttl",
                10000,
                "x-dead-letter-exchange",
                exchange.getName(),
                "x-dead-letter-routing-key",
                "product.metric.v1"));
    return new Declarables(
        exchange,
        dlx,
        main,
        parking,
        retry,
        BindingBuilder.bind(main).to(exchange).with("product.metric.v1"),
        BindingBuilder.bind(parking).to(dlx).with(parking.getName()),
        BindingBuilder.bind(retry).to(dlx).with(retry.getName()));
  }

  /**
   * Select the frozen startup protocol while retaining original topology and manual ACK ownership.
   */
  @Bean("productMetricContainerFactory")
  public SimpleRabbitListenerContainerFactory factory(ConnectionFactory connectionFactory) {
    boolean batch = batchEnabled();
    SimpleRabbitListenerContainerFactory factory =
        batch
            ? new ProductMetricBatchContainerFactory(batchChannels)
            : new SimpleRabbitListenerContainerFactory();
    factory.setConnectionFactory(connectionFactory);
    factory.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    factory.setConcurrentConsumers(1);
    factory.setMaxConcurrentConsumers(1);
    factory.setPrefetchCount(batch ? 4 : 1);
    factory.setRecoveryInterval(10000L);
    factory.setChannelTransacted(false);
    if (batch) {
      factory.setBatchListener(true);
      factory.setConsumerBatchEnabled(true);
      factory.setBatchSize(4);
      factory.setReceiveTimeout(25L);
      factory.setBatchingStrategy(new ProductMetricRawBatchingStrategy());
    }
    factory.setContainerCustomizer(
        container -> {
          container.setShutdownTimeout(15000L);
          if (batch) container.setForceStop(true);
        });
    return factory;
  }

  /** Expose the exact registry used by the controlled factory and batch adapter. */
  @Bean
  public ProductMetricBatchChannelRegistry productMetricBatchChannelRegistry() {
    return batchChannels;
  }

  /** Default or exact false selects only the original single-message protocol. */
  @Bean
  @ConditionalOnProperty(
      name = "cloudmall.product.metrics.batch.enabled",
      havingValue = "false",
      matchIfMissing = true)
  public ProductMetricSingleListener productMetricSingleListener(ProductMetricConsumer consumer) {
    return new ProductMetricSingleListener(consumer);
  }

  /** Exact true binds the registry before the batch endpoint can be started. */
  @Bean
  @ConditionalOnProperty(name = "cloudmall.product.metrics.batch.enabled", havingValue = "true")
  public ProductMetricBatchListener productMetricBatchListener(
      ProductMetricConsumer consumer, ProductMetricBatchChannelRegistry registry) {
    return new ProductMetricBatchListener(consumer, registry);
  }

  private boolean batchEnabled() {
    String value = environment.getProperty("cloudmall.product.metrics.batch.enabled", "false");
    if (!"true".equals(value) && !"false".equals(value))
      throw new IllegalStateException("METRIC_BATCH_FLAG_INVALID");
    return "true".equals(value);
  }
}
