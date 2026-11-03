package com.cloudmall.product.service;

import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry.Lease;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry.SettlementStatus;
import com.cloudmall.product.util.ProductMetricEvents;
import com.cloudmall.product.util.ProductMetricEvents.InvalidEvent;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.annotation.PreDestroy;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.listener.MessageListenerContainer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Manual ACK follows a completed application or quarantine transaction and confirmed transfer. */
@Component
@ConditionalOnProperty(name = "cloudmall.product.metrics.enabled", havingValue = "true")
public class ProductMetricConsumer {
  /** Fixed source queue used in durable quarantine identity. */
  public static final String QUEUE = "cloudmall.product.metrics.queue";

  /** Real proxy; returning from apply means its database transaction has completed. */
  private final ProductMetricService service;

  /** Mandatory confirmed retry/parking transfer on the real broker. */
  private final ProductMetricOutboxDispatcher publisher;

  /** Only this component's listener is stopped/restarted after a failed retry transfer. */
  private final RabbitListenerEndpointRegistry registry;

  /** Earliest monotonic restart, at least ten seconds after a transfer failure. */
  private volatile long restartAfter;

  /** Short state transitions never hold this gate while calling listener lifecycle methods. */
  private final Object stateGate = new Object();

  /** Serializes actual start/stop operations without serializing message application. */
  private final ReentrantLock lifecycleOperation = new ReentrantLock();

  /** A close request rejects recovery before close obtains the lifecycle operation. */
  private boolean closeRequested;

  /** Terminal state is linearized under the operation lock after any earlier start returns. */
  private volatile boolean closed;

  /** Each failed delivery advances this generation while installing its latest ten-second pause. */
  private long pauseGeneration;

  /** Identity of the first outstanding stop; duplicate or older callbacks cannot replace it. */
  private long stopToken;

  /** Completion belongs only to stopToken and is never inferred from isRunning alone. */
  private CountDownLatch stopCompletion;

  /** A registered stop is drained by the operation owner or the next original recovery tick. */
  private boolean stopRequested;

  /** Records a lifecycle start qualification while the real start runs outside stateGate. */
  private boolean starting;

  /** A failed stop remains unknown and cannot qualify a later recovery as completed. */
  private Throwable stopFailure;

  /** First real close failure stays visible even when Spring logs a destroy exception. */
  private Throwable closeFailure;

  /** Start of the latest actual close invocation; protected by stateGate. */
  private long closeStartedNanos;

  /** Elapsed monotonic time of the latest completed close invocation. */
  private long closeElapsedNanos;

  /** Finally completion is evidence of return/throw, not proof that close succeeded. */
  private boolean closeCompleted;

  /** Registry is fixed by the batch adapter before its endpoint can start. */
  private ProductMetricBatchChannelRegistry batchRegistry;

  /** Entire callbacks remain active through fallback and final tag settlement. */
  private int activeBatch;

  /** Unknown batch application or settlement permanently denies automatic recovery. */
  private Throwable batchSafetyFailure;

  /** Local single-delivery outcome, never a broker confirmation. */
  private enum Disposition {
    ACK_CALL_RETURNED,
    PAUSED,
    UNKNOWN
  }

  /** Known lifecycle denial is a local control flow, not unknown channel ownership. */
  private static final class BatchStopped extends RuntimeException {
    /** Serialization identity for this private, stackless control signal. */
    private static final long serialVersionUID = 1L;

    private BatchStopped() {
      super("METRIC_BATCH_KNOWN_STOP", null, false, false);
    }
  }

  /** Construct the consumer with genuine transactional and broker boundaries. */
  public ProductMetricConsumer(
      ProductMetricService service,
      ProductMetricOutboxDispatcher publisher,
      RabbitListenerEndpointRegistry registry) {
    this.service = service;
    this.publisher = publisher;
    this.registry = registry;
  }

  /**
   * Handle one immutable delivery and acknowledge only its own tag after transaction completion.
   */
  public void consume(Message message, Channel channel) throws IOException {
    consumeSingle(message, channel, null);
  }

  /** Bind exactly the pre-consume registry before the batch endpoint is registered. */
  public void bindBatchRegistry(ProductMetricBatchChannelRegistry candidate) {
    synchronized (stateGate) {
      if (candidate == null
          || closeRequested
          || closed
          || (batchRegistry != null && batchRegistry != candidate))
        throw new IllegalStateException("METRIC_BATCH_REGISTRY_BINDING");
      batchRegistry = candidate;
    }
  }

  private Disposition consumeSingle(Message message, Channel channel, Lease lease)
      throws IOException {
    // 1. Bound and validate the complete envelope before touching online counters.
    byte[] body = message.getBody();
    ProductMetricEventDTO event = null;
    try {
      if (lease != null
          && message.getMessageProperties().getHeaders().containsKey("springBatchFormat"))
        throw new InvalidEvent("PRODUCER_BATCH_UNSUPPORTED");
      event = ProductMetricEvents.parse(body);
      // 2. Transient database/commit failures receive three local application attempts.
      for (int attempt = 0; ; attempt++) {
        try {
          admitAttempt(lease);
          service.apply(event);
          break;
        } catch (org.springframework.dao.DataAccessException
            | org.springframework.transaction.TransactionException failure) {
          if (attempt == 2) throw failure;
        }
      }
      // 3. ACK is outside both the actual transaction and its synchronization scope.
      assertCompleted();
      acknowledge(message, channel, lease);
      return Disposition.ACK_CALL_RETURNED;
    } catch (InvalidEvent permanent) {
      if (settlementUnknown(message, lease)) throw permanent;
      try {
        admitAttempt(lease);
        service.quarantine(QUEUE, body, event == null ? null : event.eventId, permanent.reason());
      } catch (org.springframework.dao.DataAccessException
          | org.springframework.transaction.TransactionException failure) {
        pause(message, channel, lease);
        return Disposition.PAUSED;
      }
      assertCompleted();
      assertLease(lease);
      if (publisher.transfer(
          "cloudmall.product.metrics.dlx", "cloudmall.product.metrics.dlq", message)) {
        acknowledge(message, channel, lease);
        return Disposition.ACK_CALL_RETURNED;
      } else return retry(message, channel, lease);
    } catch (org.springframework.dao.DataAccessException
        | org.springframework.transaction.TransactionException transientFailure) {
      if (settlementUnknown(message, lease)) throw transientFailure;
      return retry(message, channel, lease);
    }
  }

  private Disposition retry(Message message, Channel channel, Lease lease) throws IOException {
    assertCompleted();
    assertLease(lease);
    if (publisher.transfer(
        "cloudmall.product.metrics.dlx", "cloudmall.product.metrics.retry", message)) {
      acknowledge(message, channel, lease);
      return Disposition.ACK_CALL_RETURNED;
    } else {
      pause(message, channel, lease);
      return Disposition.PAUSED;
    }
  }

  private void pause(Message message, Channel channel, Lease lease) throws IOException {
    assertCompleted();
    synchronized (stateGate) {
      restartAfter = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
      pauseGeneration++;
      requestStopLocked();
    }
    Throwable originalFailure = null;
    try {
      if (lease == null)
        channel.basicNack(message.getMessageProperties().getDeliveryTag(), false, true);
      else lease.nack(message.getMessageProperties().getDeliveryTag());
    } catch (IOException | RuntimeException | Error failure) {
      originalFailure = failure;
      throw failure;
    } finally {
      if (lease == null && lifecycleOperation.tryLock()) releaseLifecycleOperation(originalFailure);
    }
  }

  /**
   * Check each new application admission atomically against close and the original physical lease.
   */
  private void admitAttempt(Lease lease) {
    if (lease == null) return;
    synchronized (stateGate) {
      if (closeRequested || closed || restartAfter != 0) {
        requestStopLocked();
        throw new BatchStopped();
      }
      if (batchSafetyFailure != null)
        throw new IllegalStateException("METRIC_BATCH_ATTEMPT_NOT_ADMITTED");
      lease.assertCurrent();
    }
  }

  private static void assertLease(Lease lease) {
    if (lease != null) lease.assertCurrent();
  }

  /** Transport failure types must not accidentally re-enter the business exception classifiers. */
  private static boolean settlementUnknown(Message message, Lease lease) {
    return lease != null
        && lease.status(message.getMessageProperties().getDeliveryTag())
            == SettlementStatus.SETTLEMENT_UNKNOWN;
  }

  private static void acknowledge(Message message, Channel channel, Lease lease)
      throws IOException {
    if (lease == null) channel.basicAck(message.getMessageProperties().getDeliveryTag(), false);
    else lease.ack(message.getMessageProperties().getDeliveryTag());
  }

  /**
   * Consume one bounded raw callback; controlled failures never escape into whole-batch rejection.
   */
  public void consumeBatch(
      List<Message> input, Channel channel, ProductMetricBatchChannelRegistry candidate) {
    Lease lease = null;
    boolean admitted = false;
    try {
      // 1. Own the entire callback before parsing; callback entry cannot replace an existing
      // binding.
      synchronized (stateGate) {
        if (closeRequested || closed || restartAfter != 0) {
          requestStopLocked();
          return;
        }
        if (candidate == null
            || candidate != batchRegistry
            || batchSafetyFailure != null
            || activeBatch != 0)
          throw new IllegalStateException("METRIC_BATCH_CALLBACK_NOT_ADMITTED");
        activeBatch++;
        admitted = true;
      }
      if (input == null || input.isEmpty() || input.size() > 4)
        throw new IllegalStateException("METRIC_BATCH_SIZE");
      List<Message> messages = List.copyOf(input);
      List<Long> tags = new ArrayList<>();
      String consumerTag = null;
      long previousTag = 0;
      for (Message message : messages) {
        String current = message.getMessageProperties().getConsumerTag();
        long delivery = message.getMessageProperties().getDeliveryTag();
        if (current == null
            || !QUEUE.equals(message.getMessageProperties().getConsumerQueue())
            || delivery <= previousTag
            || (consumerTag != null && !consumerTag.equals(current)))
          throw new IllegalStateException("METRIC_BATCH_DELIVERY_IDENTITY");
        consumerTag = current;
        previousTag = delivery;
        tags.add(delivery);
      }
      lease = candidate.acquire(consumerTag, channel, tags);
      List<ProductMetricEventDTO> events = new ArrayList<>();
      Map<String, String> fingerprints = new HashMap<>();
      boolean fallback = false;
      for (Message message : messages) {
        try {
          if (message.getMessageProperties().getHeaders().containsKey("springBatchFormat"))
            throw new InvalidEvent("PRODUCER_BATCH_UNSUPPORTED");
          ProductMetricEventDTO event = ProductMetricEvents.parse(message.getBody());
          String fingerprint = ProductMetricEvents.fingerprint(event);
          String earlier = fingerprints.putIfAbsent(event.eventId, fingerprint);
          if (earlier != null && !earlier.equals(fingerprint)) fallback = true;
          events.add(event);
        } catch (InvalidEvent invalid) {
          fallback = true;
        }
      }
      // 2. Each retry enters a new real service proxy transaction; permanent failures immediately
      // fall back.
      if (!fallback) {
        ProductMetricBatchApplyResultDTO committed = null;
        for (int attempt = 0; attempt < 3; attempt++) {
          try {
            admitAttempt(lease);
            committed = service.applyBatch(events);
            break;
          } catch (InvalidEvent permanent) {
            fallback = true;
            break;
          } catch (org.springframework.dao.DataAccessException
              | org.springframework.transaction.TransactionException transientFailure) {
            if (attempt == 2) fallback = true;
          } catch (IllegalStateException failure) {
            if (!"METRIC_CONTROL_EXHAUSTED".equals(failure.getMessage())) throw failure;
            fallback = true;
            break;
          }
        }
        if (!fallback) {
          validateBatchResult(events, committed);
          assertCompleted();
          for (Message message : messages) acknowledge(message, channel, lease);
          return;
        }
      }
      // 3. Fallback preserves each delivery and stops immediately after a known pause or close.
      if (fallback) {
        for (Message message : messages) {
          boolean stopping;
          synchronized (stateGate) {
            stopping = closeRequested || closed || restartAfter != 0;
          }
          if (stopping) {
            throw new BatchStopped();
          }
          Disposition disposition = consumeBatchSingle(message, channel, lease);
          if (disposition == Disposition.UNKNOWN) return;
          if (disposition == Disposition.PAUSED) {
            nackUntouched(messages, lease);
            return;
          }
        }
      }
    } catch (BatchStopped stopped) {
      // The lifecycle owner already owns requeue by channel closure; never guess remaining tags.
      synchronized (stateGate) {
        requestStopLocked();
      }
    } catch (IOException | RuntimeException failure) {
      recordBatchUnknown(failure);
    } catch (Error fatal) {
      recordBatchUnknown(fatal);
      throw fatal;
    } finally {
      try {
        if (lease != null) lease.release();
      } finally {
        if (admitted)
          synchronized (stateGate) {
            activeBatch--;
            stateGate.notifyAll();
          }
      }
    }
  }

  /** Batch-only boundary converts controlled failures to an inspectable stop outcome. */
  private Disposition consumeBatchSingle(Message message, Channel channel, Lease lease) {
    try {
      return consumeSingle(message, channel, lease);
    } catch (BatchStopped stopped) {
      throw stopped;
    } catch (IOException | RuntimeException failure) {
      recordBatchUnknown(failure);
      return Disposition.UNKNOWN;
    }
  }

  private static void validateBatchResult(
      List<ProductMetricEventDTO> events, ProductMetricBatchApplyResultDTO result) {
    if (result == null
        || result.events == null
        || result.events.size() != events.size()
        || result.appliedCount < 0
        || result.appliedCount > events.size())
      throw new IllegalStateException("METRIC_BATCH_RESULT_INVALID");
    int applied = 0;
    java.util.Set<String> seenIdentities = new java.util.HashSet<>();
    for (int index = 0; index < events.size(); index++) {
      ProductMetricEventDTO event = events.get(index);
      ProductMetricBatchEventResultDTO item = result.events.get(index);
      if (item == null
          || item.index != index
          || !event.eventId.equals(item.eventId)
          || !ProductMetricEvents.fingerprint(event).equals(item.fingerprint)
          || !("APPLIED".equals(item.outcome) || "DUPLICATE".equals(item.outcome)))
        throw new IllegalStateException("METRIC_BATCH_RESULT_INVALID");
      if ("APPLIED".equals(item.outcome)) {
        if (seenIdentities.contains(item.eventId))
          throw new IllegalStateException("METRIC_BATCH_RESULT_INVALID");
        applied++;
      }
      seenIdentities.add(item.eventId);
    }
    if (applied != result.appliedCount)
      throw new IllegalStateException("METRIC_BATCH_RESULT_INVALID");
  }

  /** Never reissue a tag after a returned settlement or an unknown command. */
  private static void nackUntouched(List<Message> messages, Lease lease) throws IOException {
    for (Message message : messages) {
      long delivery = message.getMessageProperties().getDeliveryTag();
      if (lease.status(delivery) == SettlementStatus.SETTLEMENT_UNKNOWN)
        throw new IllegalStateException("METRIC_BATCH_SETTLEMENT_UNKNOWN");
      if (lease.status(delivery) == SettlementStatus.UNTOUCHED) lease.nack(delivery);
    }
  }

  private void recordBatchUnknown(Throwable failure) {
    synchronized (stateGate) {
      if (batchSafetyFailure == null) batchSafetyFailure = failure;
      pauseGeneration++;
      requestStopLocked();
    }
  }

  /** Merge pauses into the first outstanding stop; close reuses its terminal stop identity. */
  private void requestStopLocked() {
    if (closed && stopCompletion != null) return;
    if (stopCompletion == null || stopCompletion.getCount() == 0) {
      stopToken++;
      stopCompletion = new CountDownLatch(1);
      stopRequested = true;
      stopFailure = null;
    }
  }

  /** Called only by the operation owner; the callback updates state but never waits or starts. */
  private void issuePendingStop() {
    long token;
    CountDownLatch completion;
    synchronized (stateGate) {
      if (!stopRequested) return;
      stopRequested = false;
      token = stopToken;
      completion = stopCompletion;
    }
    try {
      MessageListenerContainer container = registry.getListenerContainer("productMetrics");
      if (container == null) return; // Missing registration cannot manufacture stop completion.
      container.stop(
          () -> {
            synchronized (stateGate) {
              if (token == stopToken && completion == stopCompletion) completion.countDown();
            }
          });
    } catch (RuntimeException | Error failure) {
      synchronized (stateGate) {
        if (token == stopToken && completion == stopCompletion) stopFailure = failure;
      }
      throw failure;
    }
  }

  /** Final check and unlock share stateGate so a nonblocking pause cannot lose its handoff. */
  private void releaseLifecycleOperation(Throwable originalFailure) {
    try {
      for (; ; ) {
        issuePendingStop();
        synchronized (stateGate) {
          if (!stopRequested) {
            lifecycleOperation.unlock();
            return;
          }
        }
      }
    } catch (RuntimeException | Error failure) {
      synchronized (stateGate) {
        lifecycleOperation.unlock();
      }
      if (originalFailure != null) {
        if (originalFailure != failure) originalFailure.addSuppressed(failure);
      } else throw failure;
    }
  }

  /** Resume only after the latest pause and the first stop's finite framework evidence. */
  @Scheduled(initialDelay = 1000, fixedDelay = 1000)
  public void recoverListener() {
    // 1. Never make a message-delivery thread wait for this operation owner.
    if (!lifecycleOperation.tryLock()) return;
    Throwable originalFailure = null;
    try {
      issuePendingStop();
      MessageListenerContainer candidate = registry.getListenerContainer("productMetrics");
      if (!(candidate instanceof SimpleMessageListenerContainer container)
          || container.isRunning()
          || container.getActiveConsumerCount() != 0) return;
      long generation;
      synchronized (stateGate) {
        if (stopFailure != null)
          throw new IllegalStateException("METRIC_LISTENER_STOP_FAILED", stopFailure);
        if (closeRequested
            || closed
            || batchSafetyFailure != null
            || activeBatch != 0
            || restartAfter == 0
            || System.nanoTime() < restartAfter
            || stopRequested
            || stopCompletion == null
            || stopCompletion.getCount() != 0) return;
        if (batchRegistry != null) {
          try {
            batchRegistry.assertQuiescentAndChannelsClosed();
          } catch (IllegalStateException notClosed) {
            return;
          }
        }
        generation = pauseGeneration;
        starting = true;
        // Batch delivery can start synchronously inside start(); publish eligibility first.
        if (batchRegistry != null) restartAfter = 0;
      }
      // 2. Qualification is linearized; new pauses remain nonblocking while real start runs.
      try {
        container.start();
      } catch (RuntimeException | Error failure) {
        if (batchRegistry != null) recordBatchUnknown(failure);
        throw failure;
      } finally {
        synchronized (stateGate) {
          starting = false;
        }
      }
      // 3. A newer pause/close keeps its deadline and is stopped by the final handoff below.
      synchronized (stateGate) {
        if (batchRegistry == null
            && generation == pauseGeneration
            && !closeRequested
            && !closed
            && !stopRequested) restartAfter = 0;
      }
    } catch (RuntimeException | Error failure) {
      originalFailure = failure;
      throw failure;
    } finally {
      releaseLifecycleOperation(originalFailure);
    }
  }

  /** Close is terminal; normal stop completion must fit the original fifteen-second budget. */
  @PreDestroy
  public void close() {
    long closeStarted = System.nanoTime();
    synchronized (stateGate) {
      closeStartedNanos = closeStarted;
      closeCompleted = false;
    }
    try {
      // 1. Reject future start qualifications before waiting for an already qualified real start.
      long deadline = closeStarted + TimeUnit.SECONDS.toNanos(15);
      synchronized (stateGate) {
        closeRequested = true;
        requestStopLocked();
      }
      try {
        long remaining = deadline - System.nanoTime();
        if (remaining <= 0 || !lifecycleOperation.tryLock(remaining, TimeUnit.NANOSECONDS))
          throw new IllegalStateException("METRIC_LISTENER_CLOSE_OPERATION_TIMEOUT");
        CountDownLatch completion;
        try {
          synchronized (stateGate) {
            closed = true;
            completion = stopCompletion;
          }
        } finally {
          releaseLifecycleOperation(null);
        }
        // 2. Callbacks and in-flight pauses need neither held gate while close waits.
        remaining = deadline - System.nanoTime();
        if (completion == null
            || remaining <= 0
            || !completion.await(remaining, TimeUnit.NANOSECONDS)
            || System.nanoTime() > deadline)
          throw new IllegalStateException("METRIC_LISTENER_CLOSE_STOP_UNKNOWN");
        synchronized (stateGate) {
          if (stopFailure != null)
            throw new IllegalStateException("METRIC_LISTENER_STOP_FAILED", stopFailure);
        }
        // 3. Framework completion/counts are bounded evidence, never a claim of all thread joins.
        MessageListenerContainer candidate = registry.getListenerContainer("productMetrics");
        if (!(candidate instanceof SimpleMessageListenerContainer container)
            || container.isRunning()
            || container.getActiveConsumerCount() != 0)
          throw new IllegalStateException("METRIC_LISTENER_CLOSE_WORKERS_UNKNOWN");
        synchronized (stateGate) {
          while (activeBatch != 0) {
            remaining = deadline - System.nanoTime();
            if (remaining <= 0)
              throw new IllegalStateException("METRIC_LISTENER_CLOSE_BATCH_UNKNOWN");
            TimeUnit.NANOSECONDS.timedWait(stateGate, remaining);
          }
          if (batchRegistry != null) {
            for (; ; ) {
              try {
                batchRegistry.assertQuiescentAndChannelsClosed();
                break;
              } catch (IllegalStateException notClosed) {
                remaining = deadline - System.nanoTime();
                if (remaining <= 0)
                  throw new IllegalStateException(
                      "METRIC_LISTENER_CLOSE_CHANNELS_UNKNOWN", notClosed);
                TimeUnit.NANOSECONDS.timedWait(
                    stateGate, Math.min(remaining, TimeUnit.MILLISECONDS.toNanos(10)));
              }
            }
            if (batchSafetyFailure != null)
              throw new IllegalStateException("METRIC_BATCH_SAFETY_UNKNOWN", batchSafetyFailure);
          }
          if (System.nanoTime() > deadline)
            throw new IllegalStateException("METRIC_LISTENER_CLOSE_BATCH_UNKNOWN");
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("METRIC_LISTENER_CLOSE_INTERRUPTED", failure);
      }
    } catch (RuntimeException | Error failure) {
      synchronized (stateGate) {
        if (closeFailure == null) closeFailure = failure;
      }
      throw failure;
    } finally {
      synchronized (stateGate) {
        closeElapsedNanos = System.nanoTime() - closeStarted;
        closeCompleted = true;
      }
    }
  }

  private static void assertCompleted() {
    if (TransactionSynchronizationManager.isActualTransactionActive()
        || TransactionSynchronizationManager.isSynchronizationActive())
      throw new IllegalStateException("METRIC_ACK_TRANSACTION_ACTIVE");
  }
}
