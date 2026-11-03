package com.cloudmall.product.service;

import com.rabbitmq.client.Channel;
import java.io.IOException;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.annotation.RabbitListener;

/** Default single delivery adapter delegates every decision to the original lifecycle owner. */
public final class ProductMetricSingleListener {
  /** Original consumer owns application, retry, ACK and all lifecycle operations. */
  private final ProductMetricConsumer consumer;

  /** Construct the default adapter without binding a batch channel registry. */
  public ProductMetricSingleListener(ProductMetricConsumer consumer) {
    this.consumer = consumer;
  }

  /** Deliver the original raw message without changing its exception or ACK contract. */
  @RabbitListener(
      id = "productMetrics",
      queues = ProductMetricConsumer.QUEUE,
      containerFactory = "productMetricContainerFactory")
  public void onMessage(Message message, Channel channel) throws IOException {
    consumer.consume(message, channel);
  }
}
