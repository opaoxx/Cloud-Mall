package com.cloudmall.product.config;

import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannelImpl;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.util.ReflectionUtils;

/**
 * Batch-only factory installing ownership registration before the original BQC basicConsume call.
 */
public final class ProductMetricBatchContainerFactory extends SimpleRabbitListenerContainerFactory {
  /** Same singleton registry bound into the Consumer before its endpoint starts. */
  private final ProductMetricBatchChannelRegistry registry;

  /**
   * Construct the controlled factory without opening a connection or creating a second registry.
   */
  public ProductMetricBatchContainerFactory(ProductMetricBatchChannelRegistry registry) {
    this.registry = Objects.requireNonNull(registry, "registry");
  }

  /** Keep all original SMLC receive and acknowledgement algorithms. */
  @Override
  protected SimpleMessageListenerContainer createContainerInstance() {
    return new BoundContainer(registry);
  }

  /** Minimal SMLC hook for the audited Spring Rabbit 2.4.17 implementation. */
  private static final class BoundContainer extends SimpleMessageListenerContainer {
    /** Immutable process-local registry shared with the listener adapter. */
    private final ProductMetricBatchChannelRegistry registry;

    private BoundContainer(ProductMetricBatchChannelRegistry registry) {
      this.registry = registry;
    }

    /** Reject unknown/transactional/recovering transports before any listener starts. */
    @Override
    public void afterPropertiesSet() {
      // 1. Check the actual configured connection factory without connecting to RabbitMQ.
      if (!(getConnectionFactory() instanceof CachingConnectionFactory caching)
          || caching.getRabbitConnectionFactory().isAutomaticRecoveryEnabled()
          || !caching.isPublisherConfirms()
          || !caching.isPublisherReturns()
          || isChannelTransacted()
          || !hasDefaultPublisherFactory(caching)) {
        throw new IllegalStateException("METRIC_BATCH_TRANSPORT_UNSUPPORTED");
      }
      // 2. Let the actual framework perform its initialization, including debatching enablement.
      super.afterPropertiesSet();
    }

    /**
     * The version-pinned CCF has no public getter for its publisher factory. Fail closed on drift.
     */
    private boolean hasDefaultPublisherFactory(CachingConnectionFactory caching) {
      java.lang.reflect.Field field =
          ReflectionUtils.findField(CachingConnectionFactory.class, "publisherChannelFactory");
      if (field == null) return false;
      ReflectionUtils.makeAccessible(field);
      Object factory = ReflectionUtils.getField(field, caching);
      return factory != null
          && factory.getClass() == PublisherCallbackChannelImpl.factory().getClass();
    }

    /** Install registration on each new original BQC before it can call basicConsume. */
    @Override
    protected BlockingQueueConsumer createBlockingQueueConsumer() {
      if (isChannelTransacted()) {
        throw new IllegalStateException("METRIC_BATCH_TRANSPORT_UNSUPPORTED");
      }
      BlockingQueueConsumer consumer = super.createBlockingQueueConsumer();
      AtomicBoolean registered = new AtomicBoolean();
      consumer.setTagStrategy(
          queue -> {
            if (!registered.compareAndSet(false, true)) {
              throw new IllegalStateException("METRIC_BATCH_CONSUMER_REENTRY");
            }
            return registry.registerBeforeConsume(consumer, queue, consumer.getChannel());
          });
      return consumer;
    }
  }
}
