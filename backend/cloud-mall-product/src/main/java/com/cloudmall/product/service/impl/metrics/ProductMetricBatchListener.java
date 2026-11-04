package com.cloudmall.product.service.impl.metrics;

import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.rabbitmq.client.Channel;
import java.util.List;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/** Explicit batch adapter; no independent lifecycle, retry or settlement owner is introduced. */
public final class ProductMetricBatchListener {
  /** The existing consumer remains the only pause/recover/close owner. */
  private final ProductMetricConsumer consumer;

  /** Exact registry also installed on the controlled container factory. */
  private final ProductMetricBatchChannelRegistry registry;

  /** Bind before endpoint registration, including the zero-delivery close case. */
  public ProductMetricBatchListener(
      ProductMetricConsumer consumer, ProductMetricBatchChannelRegistry registry) {
    this.consumer = consumer;
    this.registry = registry;
    consumer.bindBatchRegistry(registry);
  }

  /** Preserve core AMQP raw messages and delegate the entire callback to the shared owner. */
  @RabbitListener(
      id = "productMetrics",
      queues = ProductMetricConsumer.QUEUE,
      containerFactory = "productMetricContainerFactory")
  public void onMessages(List<Message> messages, Channel channel) {
    consumer.consumeBatch(messages, channel, registry);
  }
}
