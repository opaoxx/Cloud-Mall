package com.cloudmall.product.util;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.impl.AMQConnection;
import com.rabbitmq.client.impl.ChannelN;
import java.io.IOException;
import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannelImpl;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;

/**
 * Two-generation, process-local ownership table; never migrates a queued delivery to a new target.
 */
public final class ProductMetricBatchChannelRegistry {
  /** Sole queue admitted by this transport contract. */
  private static final String QUEUE = "cloudmall.product.metrics.queue";

  /** At most one active generation and one draining generation, including retired bindings. */
  private final Map<String, Binding> bindings = new LinkedHashMap<>();

  /**
   * Construct an empty process-local registry without opening connections or background workers.
   */
  public ProductMetricBatchChannelRegistry() {}

  /** Client-call result only; a successful call is not a broker acknowledgement confirmation. */
  public enum SettlementStatus {
    /** No client disposition has been attempted for this delivery. */
    UNTOUCHED,
    /** basicAck(tag,false) returned, without a broker confirmation guarantee. */
    ACK_CALL_RETURNED,
    /** basicNack(tag,false,true) returned, without a broker confirmation guarantee. */
    NACK_CALL_RETURNED,
    /** A disposition is in flight or its result/ownership is unknown; never resend this tag. */
    SETTLEMENT_UNKNOWN
  }

  /** Register the immutable chain before BQC invokes basicConsume; no callback may create it. */
  public synchronized String registerBeforeConsume(
      BlockingQueueConsumer consumer, String queue, Channel channel) {
    // 1. Reap only proven closed, unleased physical generations.
    bindings.values().removeIf(binding -> binding.leases == 0 && !binding.physical.isOpen());
    if (consumer == null
        || !QUEUE.equals(queue)
        || channel == null
        || consumer.getChannel() != channel
        || bindings.size() >= 2) {
      throw invalid();
    }
    // 2. Capture only the reviewed fixed wrapper chain and fixed connection.
    Binding binding = capture(consumer, queue, channel);
    for (Binding previous : bindings.values()) {
      if (previous.consumer == consumer || previous.physical == binding.physical) throw invalid();
    }
    // 3. Publish a unique, non-reusable broker tag before returning to basicConsume.
    String tag = "cm21-b4-" + UUID.randomUUID().toString().replace("-", "");
    bindings.put(tag, binding);
    return tag;
  }

  /** Acquire one bounded callback lease over a previously registered consumer generation. */
  public synchronized Lease acquire(String tag, Channel callbackChannel, List<Long> rawTags) {
    Binding binding = bindings.get(tag);
    if (binding == null
        || binding.leases != 0
        || rawTags == null
        || rawTags.isEmpty()
        || rawTags.size() > 4) throw invalid();
    long previous = 0;
    for (Long rawTag : rawTags) {
      if (rawTag == null || rawTag <= previous) throw invalid();
      previous = rawTag;
    }
    assertBinding(binding, callbackChannel);
    binding.leases++;
    return new Lease(binding, callbackChannel, rawTags);
  }

  /**
   * Check closure without closing shared connections, evicting live bindings or extending a
   * deadline.
   */
  public synchronized void assertQuiescentAndChannelsClosed() {
    for (Binding binding : bindings.values()) {
      if (binding.leases != 0 || binding.physical.isOpen()) throw invalid();
    }
  }

  /**
   * Resolve only the exact, audited Spring/publisher layers; never recursively unwrap unknown
   * proxies.
   */
  private Binding capture(BlockingQueueConsumer consumer, String queue, Channel outer) {
    Channel target = outer;
    if (outer instanceof ChannelProxy spring) {
      if (!Proxy.isProxyClass(outer.getClass())
          || !Proxy.getInvocationHandler(outer)
              .getClass()
              .getName()
              .equals(
                  "org.springframework.amqp.rabbit.connection.CachingConnectionFactory$CachedChannelInvocationHandler")
          || spring.isTransactional()) throw invalid();
      target = spring.getTargetChannel();
    } else if (Proxy.isProxyClass(outer.getClass())) throw invalid();
    Channel physical = target;
    PublisherCallbackChannelImpl publisher = null;
    if (target != null && target.getClass() == PublisherCallbackChannelImpl.class) {
      publisher = (PublisherCallbackChannelImpl) target;
      physical = publisher.getDelegate();
    }
    if (physical == null
        || physical.getClass() != ChannelN.class
        || physical instanceof Recoverable
        || physical instanceof ChannelProxy
        || physical == publisher) throw invalid();
    Connection connection = physical.getConnection();
    if (connection == null
        || connection.getClass() != AMQConnection.class
        || connection instanceof Recoverable
        || !physical.isOpen()
        || !connection.isOpen()
        || physical.getChannelNumber() <= 0) {
      throw invalid();
    }
    return new Binding(consumer, queue, outer, target, publisher, physical, connection);
  }

  /**
   * A mismatch permanently retires the generation; it must not be refreshed after a target swap.
   */
  private void assertBinding(Binding binding, Channel callbackChannel) {
    try {
      if (binding.retired
          || callbackChannel != binding.outer
          || binding.consumer.getChannel() != binding.outer
          || !QUEUE.equals(binding.queue)
          || !binding.outer.isOpen()
          || (binding.outer instanceof ChannelProxy spring
              && (spring.isTransactional() || spring.getTargetChannel() != binding.target))
          || (binding.publisher != null && binding.publisher.getDelegate() != binding.physical)
          || binding.physical.getConnection() != binding.connection
          || binding.physical.getChannelNumber() != binding.channelNumber
          || !binding.physical.isOpen()
          || !binding.connection.isOpen()) throw invalid();
    } catch (RuntimeException failure) {
      binding.retired = true;
      throw failure;
    }
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("METRIC_BATCH_CHANNEL_OWNERSHIP_UNKNOWN");
  }

  /** Fixed generation identity; only lease count and retirement status may change. */
  private static final class Binding {
    /** Original BQC owning the consume registration. */
    private final BlockingQueueConsumer consumer;

    /** Exact declared queue. */
    private final String queue;

    /** Original callback-visible channel. */
    private final Channel outer;

    /** Original Spring target, possibly the publisher wrapper. */
    private final Channel target;

    /** Optional fixed correlated-confirm wrapper. */
    private final PublisherCallbackChannelImpl publisher;

    /** Sole channel allowed to send dispositions. */
    private final Channel physical;

    /** Original non-recovering connection identity. */
    private final Connection connection;

    /** Original physical channel number, never sufficient identity on its own. */
    private final int channelNumber;

    /** Active callback count; currently limited to one. */
    private int leases;

    /** Sticky unsafe generation marker. */
    private boolean retired;

    private Binding(
        BlockingQueueConsumer consumer,
        String queue,
        Channel outer,
        Channel target,
        PublisherCallbackChannelImpl publisher,
        Channel physical,
        Connection connection) {
      this.consumer = consumer;
      this.queue = queue;
      this.outer = outer;
      this.target = target;
      this.publisher = publisher;
      this.physical = physical;
      this.connection = connection;
      this.channelNumber = physical.getChannelNumber();
    }
  }

  /**
   * Callback-scoped lease retained through parsing, all attempts, fallback and final settlement.
   */
  public final class Lease {
    /** Immutable registered generation. */
    private final Binding binding;

    /** Callback's original channel identity. */
    private final Channel callbackChannel;

    /** Local state for at most four physical tags. */
    private final Map<Long, SettlementStatus> statuses = new LinkedHashMap<>();

    /** Original disposition failure for each unknown tag. */
    private final Map<Long, Throwable> failures = new LinkedHashMap<>();

    /** Idempotent finally-release marker. */
    private boolean released;

    /** One outstanding client disposition, without holding the registry monitor during I/O. */
    private boolean settling;

    private Lease(Binding binding, Channel callbackChannel, List<Long> tags) {
      this.binding = binding;
      this.callbackChannel = callbackChannel;
      for (Long tag : tags) statuses.put(tag, SettlementStatus.UNTOUCHED);
    }

    /** Recheck ownership immediately before each application admission or channel operation. */
    public void assertCurrent() {
      synchronized (ProductMetricBatchChannelRegistry.this) {
        if (released) throw invalid();
        assertBinding(binding, callbackChannel);
      }
    }

    /** ACK one original physical delivery, never a range or a replacement channel. */
    public void ack(long tag) throws IOException {
      settle(tag, false);
    }

    /** NACK one original physical delivery with requeue=true, never a range. */
    public void nack(long tag) throws IOException {
      settle(tag, true);
    }

    /** Read local client-call disposition; this does not query or infer broker state. */
    public SettlementStatus status(long tag) {
      synchronized (ProductMetricBatchChannelRegistry.this) {
        SettlementStatus status = statuses.get(tag);
        if (status == null) throw invalid();
        return status;
      }
    }

    /** Return the original failed call/check, without replacing its cause. */
    public Throwable failure(long tag) {
      synchronized (ProductMetricBatchChannelRegistry.this) {
        if (!statuses.containsKey(tag)) throw invalid();
        return failures.get(tag);
      }
    }

    /** Release the callback only; never closes a shared channel or connection. */
    public void release() {
      synchronized (ProductMetricBatchChannelRegistry.this) {
        if (settling) throw invalid();
        if (!released) {
          released = true;
          binding.leases--;
        }
      }
    }

    /** Mark the call unknown before entering I/O so no exception can permit a duplicate command. */
    private void settle(long tag, boolean nack) throws IOException {
      synchronized (ProductMetricBatchChannelRegistry.this) {
        if (released || settling || statuses.get(tag) != SettlementStatus.UNTOUCHED)
          throw invalid();
        statuses.put(tag, SettlementStatus.SETTLEMENT_UNKNOWN);
        try {
          assertBinding(binding, callbackChannel);
          settling = true;
        } catch (RuntimeException | Error failure) {
          failures.put(tag, failure);
          binding.retired = true;
          throw failure;
        }
      }
      try {
        // Closing may race this fixed-channel call, but cannot route it through a new proxy target.
        if (nack) binding.physical.basicNack(tag, false, true);
        else binding.physical.basicAck(tag, false);
        synchronized (ProductMetricBatchChannelRegistry.this) {
          statuses.put(
              tag, nack ? SettlementStatus.NACK_CALL_RETURNED : SettlementStatus.ACK_CALL_RETURNED);
        }
      } catch (IOException | RuntimeException | Error failure) {
        synchronized (ProductMetricBatchChannelRegistry.this) {
          failures.put(tag, failure);
          binding.retired = true;
        }
        throw failure;
      } finally {
        synchronized (ProductMetricBatchChannelRegistry.this) {
          settling = false;
        }
      }
    }
  }
}
