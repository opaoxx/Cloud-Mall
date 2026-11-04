package com.cloudmall.product;

import com.cloudmall.product.service.impl.metrics.ProductMetricConsumer;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.Recoverable;
import com.rabbitmq.client.impl.AMQConnection;
import com.rabbitmq.client.impl.ChannelN;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import org.springframework.amqp.rabbit.connection.ChannelProxy;
import org.springframework.amqp.rabbit.connection.PublisherCallbackChannelImpl;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

/** Bounded evidence for the existing content/ES fixture; never owns transport lifecycle. */
final class ProductMetricPipelineContentEsSupport {
  /** Sole existing scenario supported by this additional test observer. */
  static final String ROLE = "dual-version";

  /** Every attempted restart search consumes one slot, even when its result is unknown. */
  private static final int MAX_ATTEMPTS = 16;

  /** Fixture-native CREATE_NEW writer. */
  private final ProductMetricPipelineBatchSupport.EvidenceSink sink;

  /** Independent tokens retained in original claim order. */
  private final List<Attempt> attempts = new ArrayList<>();

  /** Three original page comparisons each retain their own pause qualification. */
  private final List<Pause> pauses = new ArrayList<>();

  /** First observation failure permanently denies a successful summary. */
  private String failure;

  /** Original identities cannot be replaced between page comparisons. */
  private SimpleMessageListenerContainer ownerContainer;

  /** Original consumer, never a lifecycle substitute. */
  private ProductMetricConsumer ownerConsumer;

  /** Original production registry, never a synthetic binding table. */
  private ProductMetricBatchChannelRegistry ownerRegistry;

  /** Allocate only fixture-local records, without starting any actor. */
  ProductMetricPipelineContentEsSupport(ProductMetricPipelineBatchSupport.EvidenceSink sink) {
    this.sink = Objects.requireNonNull(sink);
  }

  /** Claim before the HTTP call; a seventeenth claim cannot authorize an action. */
  synchronized Attempt claimRestartSearch() {
    require(failure == null, "STICKY_FAILURE");
    require(attempts.size() < MAX_ATTEMPTS, "RESTART_SEARCH_QUOTA_EXCEEDED");
    Attempt attempt = new Attempt(attempts.size() + 1);
    attempts.add(attempt);
    return attempt;
  }

  /** Qualify the actual stopped owner before the compare body can enter its finally block. */
  synchronized Pause pauseAfterStop(
      SimpleMessageListenerContainer container,
      ProductMetricConsumer consumer,
      ProductMetricBatchChannelRegistry registry) {
    int ordinal = pauses.size() + 1;
    try {
      require(failure == null && pauses.size() < 3, "PAUSE_SCOPE_INVALID");
      require(container != null && consumer != null && registry != null, "PAUSE_OWNER_MISSING");
      if (ownerContainer == null) {
        ownerContainer = container;
        ownerConsumer = consumer;
        ownerRegistry = registry;
      }
      checkStopped(container, consumer, registry);
      Pause pause =
          new Pause(pauses.size() + 1, container, consumer, registry, bindings(registry, true));
      pauses.add(pause);
      write("content-es-pause-" + pause.ordinal + "-stopped", pause.facts());
      require(failure == null, "PAUSE_EVIDENCE_FAILED");
      return pause;
    } catch (RuntimeException | Error originalFailure) {
      fail("PAUSE_GATE_UNKNOWN");
      Map<String, Object> unknown = new LinkedHashMap<>();
      unknown.put("pauseOrdinal", ordinal);
      unknown.put("containerIdentity", identity(container));
      unknown.put("consumerIdentity", identity(consumer));
      unknown.put("registryIdentity", identity(registry));
      for (String name :
          List.of(
              "notRunning",
              "activeConsumerCount",
              "activeBatch",
              "channelsClosed",
              "oldBindings",
              "newBindings")) unknown.put(name, "UNKNOWN");
      unknown.put("resumeClaimed", false);
      unknown.put("resumeReturned", false);
      unknown.put("failureCode", failure);
      write("content-es-pause-" + ordinal + "-unknown", unknown, originalFailure);
      throw originalFailure;
    }
  }

  /** Return only complete bounded records; unknown observations never become zero registrations. */
  synchronized Map<String, Object> successfulFacts() {
    require(failure == null, "STICKY_FAILURE");
    long unavailable = attempts.stream().filter(token -> "HTTP_503".equals(token.outcome)).count();
    require(!attempts.isEmpty() && unavailable > 0, "REAL_503_NOT_OBSERVED");
    require(
        attempts.stream().allMatch(token -> token.completed && !"UNKNOWN".equals(token.outcome)),
        "ATTEMPT_INCOMPLETE");
    require(
        pauses.size() == 3 && pauses.stream().allMatch(pause -> pause.resumeReturned),
        "PAUSE_INCOMPLETE");
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("claimed", attempts.size());
    result.put("completed", attempts.stream().filter(token -> token.completed).count());
    for (String outcome : List.of("REGISTERED_200", "HTTP_503", "TIMEOUT", "UNKNOWN"))
      result.put(outcome, attempts.stream().filter(token -> outcome.equals(token.outcome)).count());
    result.put("attempts", attempts.stream().map(Attempt::facts).toList());
    result.put("pauses", pauses.stream().map(Pause::facts).toList());
    result.put(
        "confirmed200RegisteredDistinctSubtotal",
        attempts.stream()
            .filter(token -> "REGISTERED_200".equals(token.outcome))
            .mapToInt(token -> token.distinctIds.size())
            .sum());
    result.put(
        "unobservedRegistrationPossible",
        attempts.stream().anyMatch(token -> "TIMEOUT".equals(token.outcome)));
    return result;
  }

  /** Expose only a fixed typed failure for direct negative tests and final failure diagnosis. */
  synchronized String failureCode() {
    return failure;
  }

  /** Check state under the original consumer gate, then inspect its original registry read-only. */
  private void checkStopped(
      SimpleMessageListenerContainer container,
      ProductMetricConsumer consumer,
      ProductMetricBatchChannelRegistry registry) {
    require(
        container == ownerContainer && consumer == ownerConsumer && registry == ownerRegistry,
        "PAUSE_OWNER_CHANGED");
    require(
        field(consumer, "registry") instanceof RabbitListenerEndpointRegistry
            && ((RabbitListenerEndpointRegistry) field(consumer, "registry"))
                    .getListenerContainer("productMetrics")
                == container,
        "LISTENER_OWNER_CHANGED");
    require(!container.isRunning(), "CONTAINER_RUNNING");
    require(container.getActiveConsumerCount() == 0, "WORKERS_ACTIVE");
    Object gate = field(consumer, "stateGate");
    require(gate != null, "CONSUMER_GATE_MISSING");
    synchronized (gate) {
      require(field(consumer, "batchRegistry") == registry, "CONSUMER_REGISTRY_CHANGED");
      require(Integer.valueOf(0).equals(field(consumer, "activeBatch")), "BATCH_ACTIVE");
      require(
          field(consumer, "batchSafetyFailure") == null
              && Boolean.FALSE.equals(field(consumer, "closeRequested"))
              && Boolean.FALSE.equals(field(consumer, "closed")),
          "CONSUMER_UNSAFE");
    }
    try {
      registry.assertQuiescentAndChannelsClosed();
    } catch (RuntimeException unsafe) {
      fail("CHANNELS_NOT_QUIESCENT");
      throw unsafe;
    }
  }

  /** Validate the fixed source ABI under its own monitor, without acquiring or changing a lease. */
  private List<Binding> bindings(ProductMetricBatchChannelRegistry registry, boolean stopped) {
    synchronized (registry) {
      Object source = field(registry, "bindings");
      require(source instanceof Map<?, ?>, "BINDINGS_MISSING");
      Map<?, ?> table = (Map<?, ?>) source;
      require(!table.isEmpty() && table.size() <= 2, "BINDING_COUNT_INVALID");
      List<Binding> result = new ArrayList<>();
      for (var entry : table.entrySet()) {
        require(
            entry.getKey() instanceof String
                && ((String) entry.getKey()).matches("cm21-b4-[0-9a-f]{32}"),
            "BINDING_TAG_INVALID");
        Object original = entry.getValue();
        require(field(original, "consumer") instanceof BlockingQueueConsumer, "BQC_MISSING");
        BlockingQueueConsumer bqc = (BlockingQueueConsumer) field(original, "consumer");
        Channel outer = (Channel) field(original, "outer");
        Channel target = (Channel) field(original, "target");
        Channel physical = (Channel) field(original, "physical");
        Connection connection = (Connection) field(original, "connection");
        Object publisher = field(original, "publisher");
        require(ProductMetricConsumer.QUEUE.equals(field(original, "queue")), "QUEUE_CHANGED");
        require(outer != null && target != null, "CAPTURED_CHAIN_MISSING");
        if (outer instanceof ChannelProxy) {
          require(
              Proxy.isProxyClass(outer.getClass())
                  && Proxy.getInvocationHandler(outer)
                      .getClass()
                      .getName()
                      .equals(
                          "org.springframework.amqp.rabbit.connection.CachingConnectionFactory$CachedChannelInvocationHandler"),
              "CAPTURED_SPRING_INVALID");
        } else
          require(
              outer == target && !Proxy.isProxyClass(outer.getClass()), "CAPTURED_OUTER_INVALID");
        if (publisher != null)
          require(
              target == publisher
                  && publisher.getClass() == PublisherCallbackChannelImpl.class
                  && ((PublisherCallbackChannelImpl) publisher).getDelegate() == physical,
              "CAPTURED_PUBLISHER_INVALID");
        else require(target == physical, "CAPTURED_TARGET_INVALID");
        require(
            physical != null
                && physical.getClass() == ChannelN.class
                && !(physical instanceof Recoverable)
                && connection != null
                && connection.getClass() == AMQConnection.class
                && !(connection instanceof Recoverable),
            "RAW_BINDING_INVALID");
        require(
            physical.getConnection() == connection
                && Integer.valueOf(physical.getChannelNumber())
                    .equals(field(original, "channelNumber"))
                && physical.getChannelNumber() > 0,
            "RAW_IDENTITY_CHANGED");
        if (!stopped) {
          require(
              bqc.getChannel() == outer && outer != null && outer.isOpen(), "BQC_CHANNEL_CHANGED");
          if (outer instanceof ChannelProxy spring) {
            require(
                Proxy.isProxyClass(outer.getClass())
                    && Proxy.getInvocationHandler(outer)
                        .getClass()
                        .getName()
                        .equals(
                            "org.springframework.amqp.rabbit.connection.CachingConnectionFactory$CachedChannelInvocationHandler")
                    && !spring.isTransactional()
                    && spring.getTargetChannel() == target,
                "SPRING_TARGET_CHANGED");
          } else
            require(
                outer == target && !Proxy.isProxyClass(outer.getClass()), "OUTER_TARGET_CHANGED");
          if (publisher != null)
            require(
                target == publisher
                    && publisher.getClass() == PublisherCallbackChannelImpl.class
                    && ((PublisherCallbackChannelImpl) publisher).getDelegate() == physical,
                "PUBLISHER_CHANGED");
          else require(target == physical, "PHYSICAL_TARGET_CHANGED");
          require(physical.isOpen() && connection.isOpen(), "NEW_PHYSICAL_CLOSED");
        }
        Object leases = field(original, "leases");
        require(
            leases instanceof Integer
                && (Integer) leases >= 0
                && (Integer) leases <= 1
                && (!stopped || (Integer) leases == 0),
            "LEASE_INVALID");
        require(Boolean.FALSE.equals(field(original, "retired")), "BINDING_RETIRED");
        require(!stopped || !physical.isOpen(), "OLD_PHYSICAL_OPEN");
        result.add(
            new Binding(
                (String) entry.getKey(),
                bqc,
                physical,
                connection,
                physical.getChannelNumber(),
                (Integer) leases,
                physical.isOpen()));
      }
      return List.copyOf(result);
    }
  }

  /** Native publication failures are sticky; they never trigger a replacement business action. */
  private void write(String name, Object value) {
    write(name, value, null);
  }

  /** Preserve a propagating primary while retaining the exact identity of a writer Error. */
  private void write(String name, Object value, Throwable primary) {
    try {
      sink.write(name, value);
    } catch (Exception failure) {
      fail("NATIVE_WRITE_FAILED");
    } catch (Error fatal) {
      fail("NATIVE_WRITE_FAILED");
      if (primary == null) throw fatal;
      if (fatal != primary) primary.addSuppressed(fatal);
    }
  }

  /** Store only the first safe typed diagnostic. */
  private void fail(String code) {
    if (failure == null) failure = code;
  }

  /** Fail without serializing arbitrary source objects or exception messages. */
  private void require(boolean valid, String code) {
    if (!valid) {
      fail(code);
      throw new IllegalStateException("CM21_CONTENT_ES_" + code);
    }
  }

  /** Read a pinned source field; never mutate production state. */
  private static Object field(Object source, String name) {
    return ReflectionTestUtils.getField(source, name);
  }

  /** Numeric identity is evidence only; all authorization uses original object equality. */
  private static int identity(Object source) {
    return System.identityHashCode(source);
  }

  /** Single completion token for one authorized original restart-search HTTP action. */
  final class Attempt {
    /** One-based action claim order, independent of response success. */
    private final int ordinal;

    /** Fixed result classification, with pending retained only until completion. */
    private String outcome = "PENDING";

    /** HTTP status exists only for an observed HTTP response. */
    private Integer status;

    /** Completion prevents a second classification from replacing earlier evidence. */
    private boolean completed;

    /** Actual canonical HTTP request identity only after original registration succeeded. */
    private String requestId;

    /** Actual distinct returned products, absent when registration cannot be observed. */
    private List<Long> distinctIds;

    /** Exact registered exposure identities derived by the existing canonical factory. */
    private List<String> eventIds;

    /** Construct a token only after its quota slot was claimed. */
    private Attempt(int ordinal) {
      this.ordinal = ordinal;
    }

    /** Record only a successful original registerObservation, never an estimated exposure count. */
    void registered200(String requestUUID, List<Long> productIds, List<String> registeredEvents) {
      synchronized (ProductMetricPipelineContentEsSupport.this) {
        require(!completed, "ATTEMPT_ALREADY_COMPLETED");
        require(
            requestUUID != null
                && requestUUID.matches(
                    "[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")
                && UUID.fromString(requestUUID).toString().equals(requestUUID),
            "REQUEST_ID_INVALID");
        require(
            productIds != null
                && productIds.size() <= 2
                && new LinkedHashSet<>(productIds).size() == productIds.size(),
            "RETURNED_IDS_INVALID");
        List<String> expected = new ArrayList<>();
        for (Long productId : productIds) {
          require(productId != null && productId > 0, "RETURNED_ID_INVALID");
          expected.add(ProductMetricEvents.product(requestUUID, productId, true, null).eventId);
        }
        require(expected.equals(registeredEvents), "REGISTERED_EVENTS_CHANGED");
        requestId = requestUUID;
        distinctIds = List.copyOf(productIds);
        eventIds = List.copyOf(registeredEvents);
        complete("REGISTERED_200", 200);
      }
    }

    /** Record an actual observed HTTP503 response; quota exhaustion cannot call this branch. */
    void http503() {
      complete("HTTP_503", 503);
    }

    /** Preserve the original timeout retry behavior without claiming registration was zero. */
    void timeout() {
      complete("TIMEOUT", null);
    }

    /** Unknown action/registration results permanently deny final qualification. */
    void unknown() {
      unknown(null);
    }

    /** Preserve the original HTTP/registration failure during secondary evidence publication. */
    void unknown(Throwable primary) {
      synchronized (ProductMetricPipelineContentEsSupport.this) {
        if (!completed) complete("UNKNOWN", null, primary);
        fail("RESTART_SEARCH_UNKNOWN");
      }
    }

    /** An omitted completion is an unknown action, never silent success. */
    void finish() {
      finish(null);
    }

    /** Finally completion cannot replace an already propagating action failure. */
    void finish(Throwable primary) {
      synchronized (ProductMetricPipelineContentEsSupport.this) {
        if (!completed) unknown(primary);
      }
    }

    /** Set exactly one terminal classification before publishing its bounded safe facts. */
    private void complete(String classification, Integer httpStatus) {
      complete(classification, httpStatus, null);
    }

    /** Complete before I/O so even an Error cannot authorize a replacement action. */
    private void complete(String classification, Integer httpStatus, Throwable primary) {
      synchronized (ProductMetricPipelineContentEsSupport.this) {
        require(!completed, "ATTEMPT_ALREADY_COMPLETED");
        outcome = classification;
        status = httpStatus;
        completed = true;
        write("content-es-search-attempt-" + ordinal, facts(), primary);
      }
    }

    /** Return no synthetic registration count for failures, timeouts or pending tokens. */
    Map<String, Object> facts() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("attemptOrdinal", ordinal);
      result.put("outcome", outcome);
      result.put("httpStatus", status == null ? "UNKNOWN" : status);
      result.put("registrationCompleted", "REGISTERED_200".equals(outcome));
      if (distinctIds != null) {
        result.put("requestId", requestId);
        result.put("returnedDistinctIds", distinctIds);
        result.put("eventIds", eventIds);
        result.put("actualRegisteredDistinct", distinctIds.size());
      }
      return result;
    }
  }

  /** Permission to resume one exact stopped container, not a replacement lifecycle owner. */
  final class Pause {
    /** Original comparison order, bounded to three. */
    private final int ordinal;

    /** Actual stopped listener container. */
    private final SimpleMessageListenerContainer container;

    /** Actual nonterminal original Consumer. */
    private final ProductMetricConsumer consumer;

    /** Original registry shared by the factory and listener. */
    private final ProductMetricBatchChannelRegistry registry;

    /** Proven closed/unleased old source bindings. */
    private final List<Binding> oldBindings;

    /** New source bindings observed only after the original start returned. */
    private List<Binding> newBindings = List.of();

    /** Claim precedes start, including a start that throws. */
    private boolean resumeClaimed;

    /** A returned start qualifies only after actual new source binding verification. */
    private boolean resumeReturned;

    /** Retain the exact qualified owner and read-only source snapshot. */
    private Pause(
        int ordinal,
        SimpleMessageListenerContainer container,
        ProductMetricConsumer consumer,
        ProductMetricBatchChannelRegistry registry,
        List<Binding> oldBindings) {
      this.ordinal = ordinal;
      this.container = container;
      this.consumer = consumer;
      this.registry = registry;
      this.oldBindings = oldBindings;
    }

    /** Preserve an existing compare failure while refusing unsafe or repeated resume attempts. */
    void resume(Throwable primary) {
      try {
        synchronized (ProductMetricPipelineContentEsSupport.this) {
          require(!resumeClaimed && failure == null, "RESUME_NOT_QUALIFIED");
          checkStopped(container, consumer, registry);
          bindings(registry, true);
          resumeClaimed = true;
        }
        // 1. Only the original framework container performs the actual start and registration.
        container.start();
        Object monitor = field(container, "consumersMonitor");
        require(monitor != null, "CONTAINER_CONSUMERS_MISSING");
        List<?> actualConsumers;
        synchronized (monitor) {
          Object active = field(container, "consumers");
          require(active instanceof java.util.Set<?>, "CONTAINER_CONSUMERS_MISSING");
          actualConsumers = List.copyOf((java.util.Set<?>) active);
        }
        // 2. Read the original source table; do not create bindings or extend the start window.
        synchronized (ProductMetricPipelineContentEsSupport.this) {
          newBindings = bindings(registry, false);
          require(
              actualConsumers.size() == 1
                  && newBindings.size() == 1
                  && actualConsumers.get(0) == newBindings.get(0).consumer,
              "NEW_BQC_NOT_OWNED");
          require(
              newBindings.stream()
                  .allMatch(
                      current ->
                          oldBindings.stream()
                              .noneMatch(
                                  old ->
                                      old.consumer == current.consumer
                                          || old.physical == current.physical)),
              "OLD_BINDING_REUSED");
          require(
              field(consumer, "batchRegistry") == registry && container.isRunning(),
              "RESUME_OWNER_CHANGED");
          resumeReturned = true;
          write("content-es-pause-" + ordinal + "-resumed", facts());
          require(failure == null, "RESUME_EVIDENCE_FAILED");
        }
      } catch (RuntimeException | Error secondary) {
        synchronized (ProductMetricPipelineContentEsSupport.this) {
          fail("RESUME_FAILED");
        }
        if (primary == null) throw secondary;
        if (secondary != primary) primary.addSuppressed(secondary);
      }
    }

    /** Safe immutable facts never claim a shared connection was closed or replaced. */
    Map<String, Object> facts() {
      Map<String, Object> result = new LinkedHashMap<>();
      result.put("pauseOrdinal", ordinal);
      result.put("containerIdentity", identity(container));
      result.put("consumerIdentity", identity(consumer));
      result.put("registryIdentity", identity(registry));
      result.put("notRunning", true);
      result.put("activeConsumerCount", 0);
      result.put("activeBatch", 0);
      result.put("channelsClosed", true);
      result.put("oldBindings", oldBindings.stream().map(Binding::facts).toList());
      result.put("resumeClaimed", resumeClaimed);
      result.put("resumeReturned", resumeReturned);
      result.put("newBindings", newBindings.stream().map(Binding::facts).toList());
      return result;
    }
  }

  /**
   * Original source references authorize equality checks; hashes are only native summaries.
   *
   * @param tag canonical original source consumer tag
   * @param consumer original BQC identity
   * @param physical original raw channel identity
   * @param connection original raw connection identity, which may be reused
   * @param channelNumber captured physical number, insufficient as identity alone
   * @param leases captured actual source lease count
   * @param physicalOpen captured actual physical liveness at this observation
   */
  private record Binding(
      String tag,
      BlockingQueueConsumer consumer,
      Channel physical,
      Connection connection,
      int channelNumber,
      int leases,
      boolean physicalOpen) {
    /** Retain immutable observed values without any production state mutation. */
    private Binding {}

    /** Render only verified source identities and bounded state, without raw transport objects. */
    Map<String, Object> facts() {
      return Map.of(
          "consumerTag",
          tag,
          "bqcIdentity",
          identity(consumer),
          "physicalIdentity",
          identity(physical),
          "connectionIdentity",
          identity(connection),
          "channelNumber",
          channelNumber,
          "leases",
          leases,
          "retired",
          false,
          "physicalOpen",
          physicalOpen);
    }
  }
}
