package com.cloudmall.product.service;

import com.cloudmall.product.domain.po.ProductMetricOutboxPO;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.*;
import javax.annotation.PreDestroy;
import org.springframework.amqp.core.*;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Persistent mandatory publishing with a shared five-second deadline per claimed batch. */
@Component
@ConditionalOnProperty(name = "cloudmall.product.metrics.enabled", havingValue = "true")
public class ProductMetricOutboxDispatcher {
  /** Shared durable metrics exchange. */
  public static final String EXCHANGE = "cloudmall.product.metrics.exchange";

  /** One versioned routing contract for Product and Stock. */
  public static final String ROUTING_KEY = "product.metric.v1";

  /** Proxy transaction boundary; claim and finish never surround network waits. */
  private final ProductMetricService service;

  /** Real confirm/return-enabled Rabbit client. */
  private final RabbitTemplate rabbit;

  /** Futures owned by this dispatcher, cancelled during its bounded shutdown. */
  private final Set<Future<?>> owned = ConcurrentHashMap.newKeySet();

  /** Shutdown prevents a new claim and records whether our current scheduled round has returned. */
  private volatile boolean closed;

  /** Count of this component's active rounds; no claim about framework thread termination. */
  private int active;

  /** Construct publication on the original configured Rabbit connection. */
  public ProductMetricOutboxDispatcher(ProductMetricService service, RabbitTemplate rabbit) {
    this.service = service;
    if (!(rabbit.getConnectionFactory()
        instanceof org.springframework.amqp.rabbit.connection.CachingConnectionFactory factory))
      throw new IllegalStateException("METRIC_CONFIRM_FACTORY_REQUIRED");
    factory.setPublisherConfirmType(
        org.springframework.amqp.rabbit.connection.CachingConnectionFactory.ConfirmType.CORRELATED);
    factory.setPublisherReturns(true);
    this.rabbit = new RabbitTemplate(factory);
    this.rabbit.setMandatory(true);
  }

  /** Send a claimed batch first, then observe all confirmations against one monotonic deadline. */
  @Scheduled(
      initialDelay = 1000,
      fixedDelayString = "${cloudmall.product.metrics.dispatch-interval-ms:1000}")
  public void dispatch() {
    synchronized (this) {
      if (closed) return;
      active++;
    }
    try {
      // 1. The claim transaction commits before any broker I/O.
      String owner = UUID.randomUUID().toString();
      List<ProductMetricOutboxPO> rows = service.claim(owner);
      Map<ProductMetricOutboxPO, CorrelationData> sent = new LinkedHashMap<>();
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      // 2. Publish every claimed immutable body before waiting for any single confirmation.
      for (ProductMetricOutboxPO row : rows) {
        try {
          sent.put(
              row,
              send(
                  EXCHANGE,
                  ROUTING_KEY,
                  message(row.payloadJson.getBytes(StandardCharsets.UTF_8), row.eventId)));
        } catch (RuntimeException failure) {
          sent.put(row, null);
        }
      }
      // 3. Observe the entire shared-deadline round before one short result transaction.
      Map<String, Boolean> outcomes = new LinkedHashMap<>();
      for (Map.Entry<ProductMetricOutboxPO, CorrelationData> entry : sent.entrySet()) {
        boolean success = confirmed(entry.getValue(), deadline);
        outcomes.put(entry.getKey().eventId, success);
      }
      if (!outcomes.isEmpty()) service.finishBatch(outcomes, owner);
    } finally {
      synchronized (this) {
        active--;
        notifyAll();
      }
    }
  }

  /** Confirm a parking/retry transfer before acknowledging the original consumer delivery. */
  public boolean transfer(String exchange, String routingKey, Message original) {
    if (closed) return false;
    try {
      return confirmed(
          send(
              exchange,
              routingKey,
              org.springframework.amqp.core.MessageBuilder.fromClonedMessage(original)
                  .setDeliveryMode(MessageDeliveryMode.PERSISTENT)
                  .build()),
          System.nanoTime() + TimeUnit.SECONDS.toNanos(5));
    } catch (RuntimeException failure) {
      return false;
    }
  }

  private CorrelationData send(String exchange, String routing, Message message) {
    CorrelationData correlation =
        new CorrelationData(
            message.getMessageProperties().getMessageId() == null
                ? UUID.randomUUID().toString()
                : message.getMessageProperties().getMessageId());
    owned.add(correlation.getFuture());
    try {
      rabbit.send(exchange, routing, message, correlation);
      return correlation;
    } catch (RuntimeException failure) {
      owned.remove(correlation.getFuture());
      correlation.getFuture().cancel(true);
      throw failure;
    }
  }

  private boolean confirmed(CorrelationData correlation, long deadline) {
    if (correlation == null) return false;
    try {
      long remaining = deadline - System.nanoTime();
      if (remaining <= 0) return false;
      CorrelationData.Confirm result = correlation.getFuture().get(remaining, TimeUnit.NANOSECONDS);
      return result.isAck() && correlation.getReturnedMessage() == null;
    } catch (InterruptedException failure) {
      Thread.currentThread().interrupt();
      return false;
    } catch (ExecutionException | TimeoutException | CancellationException failure) {
      return false;
    } finally {
      owned.remove(correlation.getFuture());
    }
  }

  private static Message message(byte[] body, String id) {
    MessageProperties properties = new MessageProperties();
    properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
    properties.setDeliveryMode(MessageDeliveryMode.PERSISTENT);
    properties.setMessageId(id);
    return new Message(body, properties);
  }

  /** Cancel only our futures and await only our active round, bounded by fifteen seconds. */
  @PreDestroy
  public void close() {
    synchronized (this) {
      closed = true;
    }
    owned.forEach(future -> future.cancel(true));
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    synchronized (this) {
      while (active > 0) {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new IllegalStateException("METRIC_DISPATCH_STOP_UNKNOWN");
        try {
          TimeUnit.NANOSECONDS.timedWait(this, left);
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("METRIC_DISPATCH_STOP_INTERRUPTED", failure);
        }
      }
    }
  }
}
