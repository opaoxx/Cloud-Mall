package com.cloudmall.product.util;

import java.util.Collection;
import java.util.Date;
import java.util.function.Consumer;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.batch.BatchingStrategy;
import org.springframework.amqp.rabbit.batch.MessageBatch;

/** Consumer-only strategy preserving each physical delivery, including producer-batch markers. */
public final class ProductMetricRawBatchingStrategy implements BatchingStrategy {
  /** Construct a stateless strategy reserved for the bounded consumer container. */
  public ProductMetricRawBatchingStrategy() {}

  /** Never split a physical message, irrespective of its headers. */
  @Override
  public boolean canDebatch(MessageProperties properties) {
    return false;
  }

  /** Reject accidental expansion instead of losing the original physical tag. */
  @Override
  public void deBatch(Message message, Consumer<Message> consumer) {
    throw new UnsupportedOperationException("METRIC_RAW_DELIVERY_ONLY");
  }

  /** This strategy cannot collect producer messages. */
  @Override
  public MessageBatch addToBatch(String exchange, String routingKey, Message message) {
    throw new UnsupportedOperationException("METRIC_CONSUMER_STRATEGY_ONLY");
  }

  /** This strategy has no producer release schedule. */
  @Override
  public Date nextRelease() {
    throw new UnsupportedOperationException("METRIC_CONSUMER_STRATEGY_ONLY");
  }

  /** This strategy has no producer batches. */
  @Override
  public Collection<MessageBatch> releaseBatches() {
    throw new UnsupportedOperationException("METRIC_CONSUMER_STRATEGY_ONLY");
  }
}
