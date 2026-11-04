package com.cloudmall.product;

import com.cloudmall.product.config.ProductMetricBatchContainerFactory;
import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.service.impl.metrics.ProductMetricBatchListener;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.service.impl.metrics.ProductMetricSingleListener;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.rabbitmq.client.Channel;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.BatchMessagingMessageListenerAdapter;
import org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionAttribute;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only batch admission and bounded post-proxy observations; never changes settlement. */
final class ProductMetricPipelineBatchSupport {
  /** Maximum successful observations retained for the unchanged seven-event healthy fixture. */
  private static final int MAX_OBSERVATIONS = 64;

  /** Input positions are bounded separately because repeated event identities remain positions. */
  private static final int MAX_POSITIONS = 256;

  private ProductMetricPipelineBatchSupport() {}

  /** Read only the exact reviewed test flag; no unknown value silently selects a protocol. */
  static boolean requested(String value) {
    require("true".equals(value) || "false".equals(value));
    return "true".equals(value);
  }

  /** Reject unsupported test roles before middleware inspection or owned database construction. */
  static void admit(boolean batch, String role, int consumers, String phaseTiming) {
    if ("batch-redelivery".equals(role)) require(batch);
    if (batch)
      require(
          ("convergence".equals(role)
                  || "batch-redelivery".equals(role)
                  || "dual-version".equals(role))
              && consumers == 1
              && "false".equals(phaseTiming));
  }

  /** The batch branch must leave the actual production factory and its customizer untouched. */
  static boolean preserveFactory(boolean batch, Object bean, String name) {
    if (!batch
        || !(bean instanceof SimpleRabbitListenerContainerFactory)
        || !"productMetricContainerFactory".equals(name)) return false;
    require(bean instanceof ProductMetricBatchContainerFactory);
    return true;
  }

  /** Read initialized objects; never install a replacement listener, channel or customizer. */
  static Map<String, Object> containerFacts(
      ApplicationContext context,
      SimpleMessageListenerContainer container,
      Object originalListener) {
    require(container.getMessageListener() == originalListener);
    require(originalListener instanceof BatchMessagingMessageListenerAdapter);
    require(context.getBeanNamesForType(ProductMetricBatchListener.class).length == 1);
    require(context.getBeanNamesForType(ProductMetricSingleListener.class).length == 0);
    RabbitListenerEndpointRegistry registry = context.getBean(RabbitListenerEndpointRegistry.class);
    require(registry.getListenerContainerIds().contains("productMetrics"));
    require(registry.getListenerContainer("productMetrics") == container);
    HandlerAdapter handler =
        (HandlerAdapter) ReflectionTestUtils.getField(originalListener, "handlerAdapter");
    require(handler != null);
    InvocableHandlerMethod invocation =
        (InvocableHandlerMethod) ReflectionTestUtils.getField(handler, "invokerHandlerMethod");
    require(invocation != null);
    require(invocation.getBean() == context.getBean(ProductMetricBatchListener.class));
    require("onMessages".equals(invocation.getMethod().getName()));
    require(
        Arrays.equals(
            invocation.getMethod().getParameterTypes(),
            new Class<?>[] {List.class, Channel.class}));
    Object registryBean = context.getBean(ProductMetricBatchChannelRegistry.class);
    Object factory = context.getBean("productMetricContainerFactory");
    require(factory instanceof ProductMetricBatchContainerFactory);
    require(ReflectionTestUtils.getField(factory, "registry") == registryBean);
    require(ReflectionTestUtils.getField(invocation.getBean(), "registry") == registryBean);
    Map<String, Object> facts = new LinkedHashMap<>();
    for (String field :
        List.of(
            "concurrentConsumers",
            "maxConcurrentConsumers",
            "prefetchCount",
            "batchSize",
            "receiveTimeout",
            "forceStop")) facts.put(field, ReflectionTestUtils.getField(container, field));
    facts.put("channelTransacted", container.isChannelTransacted());
    require(Integer.valueOf(1).equals(facts.get("concurrentConsumers")));
    require(Integer.valueOf(1).equals(facts.get("maxConcurrentConsumers")));
    require(Integer.valueOf(4).equals(facts.get("prefetchCount")));
    require(Integer.valueOf(4).equals(facts.get("batchSize")));
    require(Long.valueOf(25).equals(facts.get("receiveTimeout")));
    require(Boolean.TRUE.equals(facts.get("forceStop")));
    require(Boolean.FALSE.equals(facts.get("channelTransacted")));
    require(container.isConsumerBatchEnabled());
    require(container.getAcknowledgeMode() == AcknowledgeMode.MANUAL);
    facts.put("acknowledgeMode", "MANUAL");
    facts.put("consumerBatchEnabled", true);
    facts.put("batchListener", true);
    facts.put("endpointId", "productMetrics");
    facts.put("adapterType", invocation.getBean().getClass().getName());
    facts.put("listenerType", originalListener.getClass().getName());
    facts.put("listenerIdentity", System.identityHashCode(originalListener));
    facts.put("originalListenerRetained", true);
    facts.put("channelWrappedForObservation", false);
    return facts;
  }

  /** A fixed fixture sink writes native evidence, never credentials or arbitrary error text. */
  @FunctionalInterface
  interface EvidenceSink {
    /** Publish one bounded safe fact beneath the existing exact fixture evidence root. */
    void write(String name, Object value) throws Exception;
  }

  /**
   * A controlled unit can replace only the read observer, never the original service invocation.
   */
  @FunctionalInterface
  interface SnapshotReader {
    /** Observe only the supplied canonical batch after its original transaction proxy returned. */
    Map<String, Object> read(
        List<ProductMetricEventDTO> events, ProductMetricBatchApplyResultDTO result)
        throws Exception;
  }

  /** Fixed test-role hook invoked only after the independent committed snapshot has succeeded. */
  @FunctionalInterface
  interface PostCommitHook {
    /** Observe the exact frozen input and completed result without changing the original return. */
    void afterCommitted(
        List<ProductMetricEventDTO> events,
        ProductMetricBatchApplyResultDTO result,
        Map<String, Object> snapshot)
        throws Exception;
  }

  /** Bounded outer advice; observational failures cannot turn a committed return into a retry. */
  static final class CommitObserver implements MethodInterceptor {
    /** Actual independent SQL reader in the real fixture, or an explicit pure unit replacement. */
    private final SnapshotReader reader;

    /** Native publication remains separate from the business transaction and raw broker channel. */
    private final EvidenceSink sink;

    /** Optional fixed redelivery hook; the original healthy constructor selects a no-op. */
    private final PostCommitHook postCommit;

    /**
     * Sticky finite failure code makes fixture success impossible without changing business flow.
     */
    private final AtomicReference<String> failure = new AtomicReference<>();

    /**
     * Only immutable safe snapshots are retained; insertion order is the observed callback order.
     */
    private final List<Map<String, Object>> observations = new ArrayList<>();

    /** Distinct event identities provide a precise subset of the final healthy ledger. */
    private final Set<String> observedEvents = new LinkedHashSet<>();

    /** Bounded total input positions and installed proxy metadata are fixture-local. */
    private int positions;

    /** Duplicate installation would change the original advice order and is rejected. */
    private boolean installed;

    /** Retain the original healthy path without an injected fault hook. */
    CommitObserver(SnapshotReader reader, EvidenceSink sink) {
      this(reader, sink, (events, result, snapshot) -> {});
    }

    /** Bind the one reviewed role-specific hook after independent snapshot validation. */
    CommitObserver(SnapshotReader reader, EvidenceSink sink, PostCommitHook postCommit) {
      this.reader = Objects.requireNonNull(reader);
      this.sink = Objects.requireNonNull(sink);
      this.postCommit = Objects.requireNonNull(postCommit);
    }

    /** Add exactly one outer advice while retaining the existing transaction chain's order. */
    synchronized void install(Object service) throws Exception {
      require(!installed && service instanceof Advised);
      Advised advised = (Advised) service;
      Advisor[] original = advised.getAdvisors();
      Method method = ProductMetricService.class.getMethod("applyBatch", List.class);
      int transactions = 0;
      for (Advisor advisor : original) {
        if (advisor.getAdvice() instanceof TransactionInterceptor transaction) {
          TransactionAttribute attribute =
              Objects.requireNonNull(transaction.getTransactionAttributeSource())
                  .getTransactionAttribute(method, advised.getTargetClass());
          require(attribute != null);
          require(
              attribute.getPropagationBehavior() == TransactionDefinition.PROPAGATION_REQUIRES_NEW);
          require(attribute.getIsolationLevel() == TransactionDefinition.ISOLATION_REPEATABLE_READ);
          require(attribute.getTimeout() == 3);
          transactions++;
        }
      }
      require(transactions == 1);
      advised.addAdvice(0, this);
      Advisor[] after = advised.getAdvisors();
      require(after.length == original.length + 1 && after[0].getAdvice() == this);
      for (int index = 0; index < original.length; index++)
        require(after[index + 1] == original[index]);
      installed = true;
    }

    /** Call the original proxy once; only a clean normal return admits independent SQL evidence. */
    @Override
    public Object invoke(MethodInvocation invocation) throws Throwable {
      if (!"applyBatch".equals(invocation.getMethod().getName())) return invocation.proceed();
      List<ProductMetricEventDTO> frozen = null;
      boolean admitted = cleanThread();
      try {
        require(admitted);
        require(
            invocation.getArguments().length == 1
                && invocation.getArguments()[0] instanceof List<?>);
        List<?> input = (List<?>) invocation.getArguments()[0];
        require(!input.isEmpty() && input.size() <= 4);
        List<ProductMetricEventDTO> copy = new ArrayList<>();
        for (Object value : input) {
          require(value instanceof ProductMetricEventDTO);
          copy.add(
              ProductMetricEvents.parse(ProductMetricEvents.bytes((ProductMetricEventDTO) value)));
        }
        frozen = List.copyOf(copy);
      } catch (RuntimeException invalidObservation) {
        failure.compareAndSet(null, "ENTRY_OBSERVATION_INVALID");
        admitted = false;
      }
      Object returned = invocation.proceed();
      if (!admitted) return returned;
      try {
        require(cleanThread());
        require(returned instanceof ProductMetricBatchApplyResultDTO);
        ProductMetricBatchApplyResultDTO result = (ProductMetricBatchApplyResultDTO) returned;
        require(result.events.size() == frozen.size());
        Set<String> newlyApplied = new LinkedHashSet<>();
        for (int index = 0; index < frozen.size(); index++) {
          ProductMetricEventDTO event = frozen.get(index);
          ProductMetricBatchEventResultDTO position = result.events.get(index);
          require(position.index == index && Objects.equals(event.eventId, position.eventId));
          require(ProductMetricEvents.fingerprint(event).equals(position.fingerprint));
          require("APPLIED".equals(position.outcome) || "DUPLICATE".equals(position.outcome));
          if ("APPLIED".equals(position.outcome)) require(newlyApplied.add(event.eventId));
        }
        require(result.appliedCount == newlyApplied.size());
        synchronized (this) {
          require(
              observations.size() < MAX_OBSERVATIONS && positions + frozen.size() <= MAX_POSITIONS);
          Map<String, Object> snapshot = new LinkedHashMap<>(reader.read(frozen, result));
          require(cleanThread());
          snapshot.put("observedInputSize", frozen.size());
          snapshot.put("appliedCount", result.appliedCount);
          snapshot.put("transactionProxyReturned", true);
          snapshot.put("actualTransactionActive", false);
          snapshot.put("synchronizationActive", false);
          snapshot.put("observationOrdinal", observations.size() + 1);
          sink.write("batch-commit-" + (observations.size() + 1), snapshot);
          observations.add(Map.copyOf(snapshot));
          positions += frozen.size();
          for (ProductMetricEventDTO event : frozen) observedEvents.add(event.eventId);
          postCommit.afterCommitted(frozen, result, Map.copyOf(snapshot));
        }
      } catch (Exception observationFailure) {
        failure.compareAndSet(null, "POST_PROXY_OBSERVATION_FAILED");
      }
      return returned;
    }

    /**
     * Final gate preserves subset/partial-batch truth and never substitutes the complete ledger.
     */
    synchronized Map<String, Object> successfulFacts(
        Set<String> finalLedgerEvents, Set<String> finalOutboxEvents) {
      require(installed && failure.get() == null && !observations.isEmpty());
      require(
          finalLedgerEvents.containsAll(observedEvents)
              && finalOutboxEvents.containsAll(observedEvents));
      Map<String, Object> facts = new LinkedHashMap<>();
      facts.put("successfulBatchObservations", observations.size());
      facts.put("observedInputPositions", positions);
      facts.put("observedEventIds", List.copyOf(observedEvents));
      facts.put("finalLedgerEventCount", finalLedgerEvents.size());
      facts.put(
          "allFinalEventsObservedInSuccessfulBatches", finalLedgerEvents.equals(observedEvents));
      facts.put("observations", List.copyOf(observations));
      facts.put("failure", "NONE");
      return facts;
    }

    /** Pure verification reads only the finite code and never any underlying exception text. */
    String failureCode() {
      return failure.get();
    }
  }

  /** Explicit new physical connection, independent of Spring's business resource binding. */
  static final class JdbcSnapshot implements SnapshotReader {
    /** The real fixture supplies its raw DriverManager-owned source, never the business pool. */
    private final DataSource source;

    /** This exact new catalog is the sole allowed SQL resource. */
    private final String catalog;

    JdbcSnapshot(DataSource source, String catalog) {
      this.source = Objects.requireNonNull(source);
      this.catalog = Objects.requireNonNull(catalog);
      require(source instanceof ProductOnlineMetricsMySqlIT.OwnedDataSource);
      require(catalog.equals(((ProductOnlineMetricsMySqlIT.OwnedDataSource) source).catalog));
    }

    /**
     * Read one RR snapshot after the business proxy returned, then rollback the read-only scope.
     */
    @Override
    public Map<String, Object> read(
        List<ProductMetricEventDTO> events, ProductMetricBatchApplyResultDTO result)
        throws Exception {
      require(cleanThread());
      Map<String, Object> facts = new LinkedHashMap<>();
      try (Connection connection = source.getConnection()) {
        require(catalog.equals(connection.getCatalog()));
        connection.setReadOnly(true);
        connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
        connection.setAutoCommit(false);
        try {
          long revision;
          try (PreparedStatement statement =
                  query(
                      connection,
                      "SELECT series_id,revision FROM product_metric_control WHERE singleton_id=1");
              ResultSet rows = statement.executeQuery()) {
            require(rows.next() && ProductMetricEvents.SERIES.equals(rows.getString("series_id")));
            revision = rows.getLong("revision");
            require(!rows.wasNull() && revision >= 0 && !rows.next());
          }
          List<Map<String, Object>> versions = new ArrayList<>();
          for (ProductMetricEventDTO event : events) {
            long version;
            try (PreparedStatement statement =
                query(
                    connection,
                    "SELECT"
                        + " fingerprint,event_type,product_id,sku_id,quantity,order_no,source_ref,source_revision,occurred_at,metric_version"
                        + " FROM product_metric_applied_event WHERE event_id=?")) {
              statement.setString(1, event.eventId);
              try (ResultSet rows = statement.executeQuery()) {
                require(rows.next());
                require(
                    ProductMetricEvents.fingerprint(event).equals(rows.getString("fingerprint")));
                require(event.eventType.equals(rows.getString("event_type")));
                require(Objects.equals(event.productId, nullableLong(rows, "product_id")));
                require(Objects.equals(event.skuId, nullableLong(rows, "sku_id")));
                require(event.quantity.longValue() == rows.getLong("quantity"));
                require(Objects.equals(event.orderNo, rows.getString("order_no")));
                require(Objects.equals(event.sourceRef, rows.getString("source_ref")));
                require(
                    Objects.equals(event.sourceRevision, nullableLong(rows, "source_revision")));
                Object occurred = rows.getObject("occurred_at");
                LocalDateTime local =
                    occurred instanceof LocalDateTime
                        ? (LocalDateTime) occurred
                        : occurred instanceof Timestamp
                            ? ((Timestamp) occurred).toLocalDateTime()
                            : null;
                require(OffsetDateTime.parse(event.occurredAt).toLocalDateTime().equals(local));
                version = rows.getLong("metric_version");
                require(!rows.wasNull() && version > 0 && !rows.next());
              }
            }
            long currentVersion;
            try (PreparedStatement statement =
                query(
                    connection,
                    "SELECT metric_version FROM product_online_stat WHERE product_id=?")) {
              statement.setLong(1, event.productId);
              try (ResultSet rows = statement.executeQuery()) {
                require(rows.next());
                currentVersion = rows.getLong(1);
                require(!rows.wasNull() && currentVersion >= version && !rows.next());
              }
            }
            String projectionStatus;
            try (PreparedStatement statement =
                query(
                    connection,
                    "SELECT status FROM product_metric_projection_outbox WHERE product_id=? AND"
                        + " metric_version=?")) {
              statement.setLong(1, event.productId);
              statement.setLong(2, version);
              try (ResultSet rows = statement.executeQuery()) {
                require(rows.next());
                projectionStatus = rows.getString(1);
                require(
                    Set.of("PENDING", "UNKNOWN", "DONE").contains(projectionStatus)
                        && !rows.next());
              }
            }
            versions.add(
                Map.of(
                    "eventId",
                    event.eventId,
                    "fingerprint",
                    ProductMetricEvents.fingerprint(event),
                    "productId",
                    event.productId,
                    "ledgerMetricVersion",
                    version,
                    "observedStatMetricVersion",
                    currentVersion,
                    "projectionStatus",
                    projectionStatus));
          }
          try (PreparedStatement statement =
                  query(
                      connection,
                      "SELECT COALESCE(SUM(metric_version),0) FROM product_online_stat");
              ResultSet rows = statement.executeQuery()) {
            require(rows.next());
            long total = rows.getLong(1);
            require(!rows.wasNull() && total >= 0 && revision >= total && !rows.next());
          }
          try (PreparedStatement statement = query(connection, "SELECT CONNECTION_ID()");
              ResultSet rows = statement.executeQuery()) {
            require(rows.next());
            facts.put("independentServerConnectionId", rows.getLong(1));
            require(!rows.wasNull() && !rows.next());
          }
          facts.put("catalog", catalog);
          facts.put("controlRevisionObserved", revision);
          facts.put("observedLedgerVersions", List.copyOf(versions));
          facts.put("businessConnectionIdCompared", false);
          facts.put("connectionSource", "EXPLICIT_NEW_OWNED_DRIVER_MANAGER_CONNECTION");
          facts.put("readOnly", connection.isReadOnly());
          facts.put("isolation", connection.getTransactionIsolation());
          require(
              connection.isReadOnly()
                  && connection.getTransactionIsolation()
                      == Connection.TRANSACTION_REPEATABLE_READ);
        } finally {
          connection.rollback();
        }
      }
      facts.put("independentConnectionClosed", true);
      return facts;
    }
  }

  private static PreparedStatement query(Connection connection, String sql) throws Exception {
    PreparedStatement statement = connection.prepareStatement(sql);
    try {
      statement.setQueryTimeout(2);
    } catch (Exception failure) {
      statement.close();
      throw failure;
    }
    return statement;
  }

  private static Long nullableLong(ResultSet rows, String column) throws Exception {
    long value = rows.getLong(column);
    return rows.wasNull() ? null : value;
  }

  private static boolean cleanThread() {
    return !TransactionSynchronizationManager.isActualTransactionActive()
        && !TransactionSynchronizationManager.isSynchronizationActive();
  }

  private static void require(boolean condition) {
    if (!condition) throw new IllegalStateException("CM21_BATCH_TEST_OBSERVATION_INVALID");
  }
}
