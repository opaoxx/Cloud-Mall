package com.cloudmall.product;

import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.service.ProductMetricConsumer;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.impl.AMQConnection;
import com.rabbitmq.client.impl.ChannelN;
import java.lang.reflect.Proxy;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannelImpl;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Exact test-only delivery/commit association; never owns production settlement or recovery. */
final class ProductMetricPipelineRedeliverySupport {
  /** Sole new scenario admitted by this helper. */
  static final String ROLE = "batch-redelivery";

  /** Bound the native evidence and callback observation footprint independently of success. */
  private static final int MAX_DELIVERIES = 32;

  /** Original fixture-owned native writer. */
  private final ProductMetricPipelineBatchSupport.EvidenceSink sink;

  /** One call stack per original callback thread; nested callbacks cannot borrow an outer frame. */
  private final ThreadLocal<Deque<Frame>> calls = new ThreadLocal<>();

  /** Every original context owns an independent consumer and commit observer. */
  private final Map<Integer, Generation> generations = new LinkedHashMap<>();

  /** First instrumentation failure is sticky and can never qualify a success. */
  private final AtomicReference<String> failure = new AtomicReference<>();

  /** Arming is an explicit test action before the single original product42 request. */
  private final AtomicBoolean armed = new AtomicBoolean();

  /** Claiming the only close attempt precedes I/O, including an unsuccessful close attempt. */
  private final AtomicBoolean closeClaimed = new AtomicBoolean();

  /** Main-thread HTTP identity independently checks the asynchronous callback's exact target. */
  private volatile String expectedEvent;

  /** Immutable first fault binding and bytes survive the old context's actual destruction. */
  private volatile Fault fault;

  /** Only a returned close with an actually closed physical channel satisfies the fault gate. */
  private volatile boolean physicalClosed;

  /** A broker-marked duplicate in the new context, never a test-created replay. */
  private volatile boolean replayObserved;

  /** Construct only fixture-local state; no callback, network or lifecycle is started. */
  ProductMetricPipelineRedeliverySupport(ProductMetricPipelineBatchSupport.EvidenceSink sink) {
    this.sink = Objects.requireNonNull(sink);
  }

  /** Allocate each context generation once without replacing any earlier evidence. */
  synchronized Generation generation(int number) {
    require(number > 0 && generations.size() < 2 && !generations.containsKey(number));
    if (!generations.isEmpty()) {
      Generation previous = generations.values().iterator().next();
      require(previous.expectedClosed && number > previous.number);
    }
    Generation created = new Generation(number);
    generations.put(number, created);
    return created;
  }

  /** Arm only once, before the one original target request is issued. */
  void arm() {
    require(!closeClaimed.get() && armed.compareAndSet(false, true));
  }

  /** Bind the actual HTTP request result without guessing from a shared last-event variable. */
  synchronized void confirmTarget(String eventId) {
    require(eventId != null && !eventId.isBlank());
    require(expectedEvent == null || expectedEvent.equals(eventId));
    expectedEvent = eventId;
    require(fault == null || eventId.equals(fault.event.eventId));
  }

  /**
   * The fixed test wait polls facts only; an instrumentation error never requests another close.
   */
  boolean faultObserved() {
    return physicalClosed;
  }

  /** Poll only real new-generation duplicate evidence. */
  boolean replayObserved() {
    return replayObserved;
  }

  /** Source-preserving reflection is always directed at the one original target. */
  static ProductMetricConsumer ultimateConsumer(Object bean) {
    Object target = bean;
    try {
      for (int depth = 0; target instanceof Advised advised; depth++) {
        require(depth < 4 && advised.getTargetSource().isStatic());
        Object next = advised.getTargetSource().getTarget();
        require(next != null && next != target);
        target = next;
      }
    } catch (Exception invalidTarget) {
      throw invalid();
    }
    require(target instanceof ProductMetricConsumer);
    return (ProductMetricConsumer) target;
  }

  /** Final success does not normalize the expected old close failure into a healthy close. */
  synchronized Map<String, Object> successfulFacts() {
    require(failure.get() == null && expectedEvent != null && fault != null);
    require(closeClaimed.get() && physicalClosed && replayObserved && generations.size() == 2);
    require(expectedEvent.equals(fault.event.eventId));
    require(generations.get(fault.generation).expectedClosed);
    return Map.of(
        "targetEventId",
        expectedEvent,
        "closeAttempts",
        1,
        "physicalClosed",
        true,
        "oldGenerationExpectedSafetyUnknown",
        true,
        "newGenerationBrokerDuplicateObserved",
        true,
        "faultGeneration",
        fault.generation,
        "generations",
        List.copyOf(generations.keySet()),
        "failure",
        "NONE");
  }

  /** A finite observation code is sufficient; arbitrary callback exception text is never saved. */
  String failureCode() {
    return failure.get();
  }

  /** Pure tests can prove stack cleanup without accessing another thread's callback. */
  boolean currentThreadHasFrame() {
    Deque<Frame> stack = calls.get();
    return stack != null && !stack.isEmpty();
  }

  /** Context-local proxy and evidence; no second Consumer or lifecycle owner is created. */
  final class Generation {
    /** Monotonic context identity, not a numeric AMQP delivery-tag generation. */
    final int number;

    /** Original singleton target and its optional test-only proxy. */
    private ProductMetricConsumer target;

    /** Exposed bean delegates every method to the one original target. */
    private ProductMetricConsumer bean;

    /** Exact registry supplied by the original batch listener, bound at its first callback. */
    private ProductMetricBatchChannelRegistry registry;

    /** Bounded callback ordinal also makes native names CREATE_NEW across contexts. */
    private int deliveries;

    /** Every observed replay has a unique native name without requiring exactly one delivery. */
    private int replays;

    /**
     * Success means expected old safety failure and every independent physical gate were proved.
     */
    private boolean expectedClosed;

    /** Retain a unique context ordinal without allocating a registry or consumer. */
    private Generation(int number) {
      this.number = number;
    }

    /** Decorate calls only, preserving the original target, advisor order and lifecycle methods. */
    synchronized ProductMetricConsumer wrapConsumer(Object original) {
      require(bean == null);
      target = ultimateConsumer(original);
      MethodInterceptor capture = this::invoke;
      if (original instanceof Advised advised) {
        Advisor[] before = advised.getAdvisors();
        advised.addAdvice(0, capture);
        Advisor[] after = advised.getAdvisors();
        require(after.length == before.length + 1 && after[0].getAdvice() == capture);
        for (int index = 0; index < before.length; index++)
          require(after[index + 1] == before[index]);
        bean = (ProductMetricConsumer) original;
      } else {
        ProxyFactory factory = new ProxyFactory(target);
        factory.setProxyTargetClass(true);
        factory.addAdvice(capture);
        bean = (ProductMetricConsumer) factory.getProxy();
      }
      require(ultimateConsumer(bean) == target);
      return bean;
    }

    private Object invoke(MethodInvocation invocation) throws Throwable {
      if (!"consumeBatch".equals(invocation.getMethod().getName())) return invocation.proceed();
      Frame frame;
      try {
        frame = capture(invocation);
      } catch (RuntimeException invalidCapture) {
        failure.compareAndSet(null, "DELIVERY_CAPTURE_INVALID");
        frame = new Frame(this);
      }
      Deque<Frame> stack = calls.get();
      if (stack == null) {
        stack = new ArrayDeque<>();
        calls.set(stack);
      }
      stack.push(frame);
      try {
        return invocation.proceed();
      } finally {
        if (stack.poll() != frame) failure.compareAndSet(null, "STACK_CLEANUP_INVALID");
        if (stack.isEmpty()) calls.remove();
      }
    }

    private Frame capture(MethodInvocation invocation) {
      require(invocation.getThis() == target);
      Object[] arguments = invocation.getArguments();
      require(arguments.length == 3 && arguments[0] instanceof List<?>);
      require(
          arguments[1] instanceof Channel
              && arguments[2] instanceof ProductMetricBatchChannelRegistry);
      List<?> originals = (List<?>) arguments[0];
      require(!originals.isEmpty() && originals.size() <= 4);
      Channel callback = (Channel) arguments[1];
      ProductMetricBatchChannelRegistry actualRegistry =
          (ProductMetricBatchChannelRegistry) arguments[2];
      synchronized (this) {
        require(++deliveries <= MAX_DELIVERIES);
        require(registry == null || registry == actualRegistry);
        for (Generation other : generations.values())
          require(other == this || other.registry != actualRegistry);
        registry = actualRegistry;
      }
      require(ReflectionTestUtils.getField(target, "batchRegistry") == actualRegistry);
      List<Delivery> captured = new ArrayList<>();
      long previousTag = 0;
      String consumerTag = null;
      for (Object value : originals) {
        require(value instanceof Message);
        Message message = (Message) value;
        long tag = message.getMessageProperties().getDeliveryTag();
        String actualTag = message.getMessageProperties().getConsumerTag();
        require(tag > previousTag && actualTag != null && !actualTag.isBlank());
        require(
            ProductMetricConsumer.QUEUE.equals(message.getMessageProperties().getConsumerQueue()));
        require(consumerTag == null || consumerTag.equals(actualTag));
        previousTag = tag;
        consumerTag = actualTag;
        byte[] bytes = message.getBody().clone();
        captured.add(
            new Delivery(message, bytes, ProductMetricEvents.parse(bytes), tag, actualTag));
      }
      Frame frame = new Frame(this, originals, callback, actualRegistry, List.copyOf(captured));
      write(
          "batch-redelivery-delivery-g" + number + "-" + deliveries,
          Map.of(
              "generation",
              number,
              "inputSize",
              captured.size(),
              "consumerIdentity",
              identity(target),
              "registryIdentity",
              identity(registry),
              "callbackIdentity",
              identity(callback)));
      return frame;
    }

    /** Called only by the already validated independent post-transaction observer. */
    void afterCommitted(
        List<ProductMetricEventDTO> events,
        ProductMetricBatchApplyResultDTO result,
        Map<String, Object> snapshot) {
      try {
        require(failure.get() == null);
        require(cleanThread());
        Deque<Frame> stack = calls.get();
        require(stack != null && !stack.isEmpty());
        Frame frame = stack.peek();
        require(
            frame.generation == this && frame.valid && frame.deliveries.size() == events.size());
        require(Boolean.TRUE.equals(snapshot.get("independentConnectionClosed")));
        require(Boolean.TRUE.equals(snapshot.get("readOnly")));
        require(
            Integer.valueOf(java.sql.Connection.TRANSACTION_REPEATABLE_READ)
                .equals(snapshot.get("isolation")));
        require(
            "EXPLICIT_NEW_OWNED_DRIVER_MANAGER_CONNECTION"
                .equals(snapshot.get("connectionSource")));
        for (int index = 0; index < events.size(); index++) {
          ProductMetricEventDTO event = events.get(index);
          Delivery delivery = frame.deliveries.get(index);
          require(
              frame.originalList.size() == frame.deliveries.size()
                  && frame.originalList.get(index) == delivery.message);
          require(event.eventId.equals(delivery.event.eventId));
          require(
              ProductMetricEvents.fingerprint(event)
                  .equals(ProductMetricEvents.fingerprint(delivery.event)));
          require(Arrays.equals(delivery.body, delivery.message.getBody()));
          require(delivery.tag == delivery.message.getMessageProperties().getDeliveryTag());
          require(
              delivery.consumerTag.equals(
                  delivery.message.getMessageProperties().getConsumerTag()));
          require(
              ProductMetricConsumer.QUEUE.equals(
                  delivery.message.getMessageProperties().getConsumerQueue()));
          ProductMetricBatchEventResultDTO position = result.events.get(index);
          require(position.index == index && event.eventId.equals(position.eventId));
          require(ProductMetricEvents.fingerprint(event).equals(position.fingerprint));
          if (!Long.valueOf(42L).equals(event.productId)
              || !"PRODUCT_VIEW_CONFIRMED".equals(event.eventType)) continue;
          requireSnapshotEvent(snapshot, event);
          if (fault != null && number > fault.generation) {
            observeReplay(frame, delivery, position);
          } else if (armed.get() && "APPLIED".equals(position.outcome)) {
            triggerFault(frame, delivery);
          }
        }
      } catch (RuntimeException observationFailure) {
        failure.compareAndSet(null, "POST_COMMIT_ASSOCIATION_INVALID");
      }
    }

    private void triggerFault(Frame frame, Delivery delivery) {
      require(expectedEvent == null || expectedEvent.equals(delivery.event.eventId));
      if (closeClaimed.get()) {
        require(
            fault != null
                && fault.generation == number
                && fault.event.eventId.equals(delivery.event.eventId)
                && Arrays.equals(fault.body, delivery.body));
        return;
      }
      Binding binding = binding(frame, delivery);
      if (!closeClaimed.compareAndSet(false, true)) return;
      fault = new Fault(number, delivery.event, delivery.body.clone(), binding);
      try {
        binding.physical.close();
        physicalClosed = !binding.physical.isOpen();
        require(physicalClosed);
        write(
            "batch-redelivery-fault-g" + number,
            Map.of(
                "generation",
                number,
                "eventId",
                delivery.event.eventId,
                "bodySha256",
                ProductMetricEvents.sha256(delivery.body),
                "fingerprint",
                ProductMetricEvents.fingerprint(delivery.event),
                "deliveryTag",
                delivery.tag,
                "physicalIdentity",
                identity(binding.physical),
                "connectionIdentity",
                identity(binding.connection),
                "physicalClosed",
                true,
                "actualTransactionActive",
                false,
                "closeAttempts",
                1));
      } catch (Exception closeFailure) {
        failure.compareAndSet(null, "PHYSICAL_CLOSE_FAILED");
      }
    }

    private void observeReplay(
        Frame frame, Delivery delivery, ProductMetricBatchEventResultDTO position) {
      Fault original = Objects.requireNonNull(fault);
      require(generations.get(original.generation).expectedClosed);
      require(original.event.eventId.equals(delivery.event.eventId));
      require(
          ProductMetricEvents.fingerprint(original.event)
              .equals(ProductMetricEvents.fingerprint(delivery.event)));
      require(Arrays.equals(original.body, delivery.body));
      require(Boolean.TRUE.equals(delivery.message.getMessageProperties().getRedelivered()));
      require("DUPLICATE".equals(position.outcome));
      Binding current = binding(frame, delivery);
      require(
          current.registry != original.binding.registry
              && current.physical != original.binding.physical
              && current.connection != original.binding.connection);
      write(
          "batch-redelivery-replay-g" + number + "-" + (++replays),
          Map.of(
              "generation",
              number,
              "faultGeneration",
              original.generation,
              "eventId",
              delivery.event.eventId,
              "bodySha256",
              ProductMetricEvents.sha256(delivery.body),
              "deliveryTag",
              delivery.tag,
              "redelivered",
              true,
              "physicalIdentity",
              identity(current.physical),
              "connectionIdentity",
              identity(current.connection),
              "duplicateOutcome",
              true));
      replayObserved = true;
    }

    /** Preserve the exact expected exception and independently gate all physical close facts. */
    void expectedSafetyClosed(Throwable aggregate, Map<String, Object> physicalFacts) {
      require(failure.get() == null);
      require(fault != null && fault.generation == number && physicalClosed);
      require(
          aggregate != null
              && aggregate.getClass() == IllegalStateException.class
              && "CM21_PRODUCT_CLOSE_UNKNOWN".equals(aggregate.getMessage()));
      Throwable actual = (Throwable) ReflectionTestUtils.getField(target, "closeFailure");
      require(
          actual != null
              && actual.getClass() == IllegalStateException.class
              && "METRIC_BATCH_SAFETY_UNKNOWN".equals(actual.getMessage()));
      require(aggregate.getSuppressed().length == 1 && aggregate.getSuppressed()[0] == actual);
      Object gate = Objects.requireNonNull(ReflectionTestUtils.getField(target, "stateGate"));
      synchronized (gate) {
        require(Boolean.TRUE.equals(ReflectionTestUtils.getField(target, "closeCompleted")));
        require(Boolean.TRUE.equals(ReflectionTestUtils.getField(target, "closed")));
        require(Boolean.TRUE.equals(ReflectionTestUtils.getField(target, "closeRequested")));
        Object started = ReflectionTestUtils.getField(target, "closeStartedNanos");
        Object elapsed = ReflectionTestUtils.getField(target, "closeElapsedNanos");
        require(started instanceof Long && (Long) started != 0);
        require(
            elapsed instanceof Long
                && (Long) elapsed >= 0
                && (Long) elapsed <= java.util.concurrent.TimeUnit.SECONDS.toNanos(15));
        require(Integer.valueOf(0).equals(ReflectionTestUtils.getField(target, "activeBatch")));
        Object safetyFailure = ReflectionTestUtils.getField(target, "batchSafetyFailure");
        require(safetyFailure != null && actual.getCause() == safetyFailure);
      }
      require(
          Boolean.TRUE.equals(physicalFacts.get("schedulerTerminated"))
              && Boolean.TRUE.equals(physicalFacts.get("contextInactive"))
              && Boolean.TRUE.equals(physicalFacts.get("poolClosed"))
              && Boolean.TRUE.equals(physicalFacts.get("rawConnectionsClosed"))
              && Boolean.TRUE.equals(physicalFacts.get("httpClosed"))
              && Boolean.TRUE.equals(physicalFacts.get("listenerStopped"))
              && Boolean.TRUE.equals(physicalFacts.get("productStopObserved"))
              && Boolean.TRUE.equals(physicalFacts.get("consumerCloseObserved")));
      registry.assertQuiescentAndChannelsClosed();
      require(!fault.binding.physical.isOpen() && !fault.binding.connection.isOpen());
      write(
          "batch-redelivery-expected-close-g" + number,
          Map.of(
              "generation",
              number,
              "expectedFailure",
              "METRIC_BATCH_SAFETY_UNKNOWN",
              "normalClosePassed",
              false,
              "physicalCloseFacts",
              Map.copyOf(physicalFacts)));
      require(failure.get() == null);
      expectedClosed = true;
    }

    ProductMetricConsumer target() {
      return target;
    }
  }

  private static Binding binding(Frame frame, Delivery delivery) {
    ProductMetricBatchChannelRegistry registry = frame.registry;
    synchronized (registry) {
      Object value = ReflectionTestUtils.getField(registry, "bindings");
      require(value instanceof Map<?, ?>);
      Object registered = ((Map<?, ?>) value).get(delivery.consumerTag);
      require(registered != null);
      Object consumerValue = ReflectionTestUtils.getField(registered, "consumer");
      require(consumerValue instanceof BlockingQueueConsumer);
      BlockingQueueConsumer consumer = (BlockingQueueConsumer) consumerValue;
      require(
          ProductMetricConsumer.QUEUE.equals(ReflectionTestUtils.getField(registered, "queue")));
      Channel outer = (Channel) ReflectionTestUtils.getField(registered, "outer");
      Channel target = (Channel) ReflectionTestUtils.getField(registered, "target");
      Object publisher = ReflectionTestUtils.getField(registered, "publisher");
      Channel physical = (Channel) ReflectionTestUtils.getField(registered, "physical");
      Connection connection = (Connection) ReflectionTestUtils.getField(registered, "connection");
      require(outer == frame.channel && consumer.getChannel() == outer && outer.isOpen());
      if (outer instanceof ChannelProxy spring) {
        require(Proxy.isProxyClass(outer.getClass()) && !spring.isTransactional());
        require(
            Proxy.getInvocationHandler(outer)
                .getClass()
                .getName()
                .equals(
                    "org.springframework.amqp.rabbit.connection.CachingConnectionFactory$CachedChannelInvocationHandler"));
        require(spring.getTargetChannel() == target);
      } else require(!Proxy.isProxyClass(outer.getClass()) && outer == target);
      if (publisher == null) require(target == physical);
      else
        require(
            publisher.getClass() == PublisherCallbackChannelImpl.class
                && target == publisher
                && ((PublisherCallbackChannelImpl) publisher).getDelegate() == physical);
      require(
          physical != null
              && physical.getClass() == ChannelN.class
              && !(physical instanceof Recoverable)
              && !(physical instanceof ChannelProxy));
      require(
          connection != null
              && connection.getClass() == AMQConnection.class
              && !(connection instanceof Recoverable));
      require(physical.getConnection() == connection && physical.isOpen() && connection.isOpen());
      Object channelNumber = ReflectionTestUtils.getField(registered, "channelNumber");
      require(
          channelNumber instanceof Integer
              && (Integer) channelNumber > 0
              && (Integer) channelNumber == physical.getChannelNumber());
      require(Integer.valueOf(1).equals(ReflectionTestUtils.getField(registered, "leases")));
      require(Boolean.FALSE.equals(ReflectionTestUtils.getField(registered, "retired")));
      return new Binding(registry, physical, connection);
    }
  }

  private static void requireSnapshotEvent(
      Map<String, Object> snapshot, ProductMetricEventDTO event) {
    Object versions = snapshot.get("observedLedgerVersions");
    require(versions instanceof List<?>);
    long matches = 0;
    for (Object value : (List<?>) versions) {
      require(value instanceof Map<?, ?>);
      Map<?, ?> row = (Map<?, ?>) value;
      if (!event.eventId.equals(row.get("eventId"))) continue;
      require(ProductMetricEvents.fingerprint(event).equals(row.get("fingerprint")));
      require(
          row.get("ledgerMetricVersion") instanceof Number
              && ((Number) row.get("ledgerMetricVersion")).longValue() > 0);
      matches++;
    }
    require(matches == 1);
  }

  private void write(String name, Object value) {
    try {
      sink.write(name, value);
    } catch (Exception failedEvidence) {
      failure.compareAndSet(null, "REDELIVERY_EVIDENCE_FAILED");
    }
  }

  private static int identity(Object value) {
    return System.identityHashCode(value);
  }

  private static boolean cleanThread() {
    return !TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isSynchronizationActive();
  }

  private static void require(boolean condition) {
    if (!condition) throw invalid();
  }

  private static IllegalStateException invalid() {
    return new IllegalStateException("CM21_BATCH_REDELIVERY_OBSERVATION_INVALID");
  }

  /** Original invocation references and frozen bytes; an invalid sentinel cannot authorize I/O. */
  private static final class Frame {
    /** Context-local advice instance whose original callback owns this stack entry. */
    private final Generation generation;

    /** Exact list supplied to the original consumeBatch invocation. */
    private final List<?> originalList;

    /** Exact original callback channel, never an observation proxy. */
    private final Channel channel;

    /** Exact registry argument already bound into the original consumer. */
    private final ProductMetricBatchChannelRegistry registry;

    /** Original message references paired with immutable body bytes and event identities. */
    private final List<Delivery> deliveries;

    /** Only a completely checked frame can authorize the post-commit association. */
    private final boolean valid;

    /** Block an invalid nested callback from borrowing an outer valid frame. */
    private Frame(Generation generation) {
      this.generation = generation;
      originalList = null;
      channel = null;
      registry = null;
      deliveries = List.of();
      valid = false;
    }

    /** Preserve original invocation identities while retaining immutable audit bytes. */
    private Frame(
        Generation generation,
        List<?> originalList,
        Channel channel,
        ProductMetricBatchChannelRegistry registry,
        List<Delivery> deliveries) {
      this.generation = generation;
      this.originalList = originalList;
      this.channel = channel;
      this.registry = registry;
      this.deliveries = deliveries;
      valid = true;
    }
  }

  /**
   * One physical delivery's unchanged object and immutable audit values.
   *
   * @param message original message object
   * @param body immutable copy of the original wire bytes
   * @param event parsed immutable-identity observation, never substituted into the callback
   * @param tag original physical delivery tag
   * @param consumerTag exact pre-consume registration key
   */
  private record Delivery(
      Message message, byte[] body, ProductMetricEventDTO event, long tag, String consumerTag) {}

  /**
   * Read-only validated transport identity; this record never owns a lease or lifecycle.
   *
   * @param registry original registration monitor
   * @param physical exact registered channel allowed to settle deliveries
   * @param connection original non-recovering physical connection
   */
  private record Binding(
      ProductMetricBatchChannelRegistry registry, Channel physical, Connection connection) {}

  /**
   * One claimed fault retained unchanged across the later context.
   *
   * @param generation original context ordinal
   * @param event exact committed target event
   * @param body original wire bytes retained for the broker replay comparison
   * @param binding original validated physical transport
   */
  private record Fault(int generation, ProductMetricEventDTO event, byte[] body, Binding binding) {}
}
