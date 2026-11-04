package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.config.ProductMetricBatchContainerFactory;
import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.impl.metrics.ProductMetricBatchListener;
import com.cloudmall.product.service.impl.metrics.ProductMetricConsumer;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.service.impl.metrics.ProductMetricSingleListener;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.cloudmall.product.util.ProductMetricRawBatchingStrategy;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.BatchMessagingMessageListenerAdapter;
import org.springframework.amqp.rabbit.listener.adapter.HandlerAdapter;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.AbstractPlatformTransactionManager;
import org.springframework.transaction.support.DefaultTransactionStatus;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Pure listener/configuration/proxy/JDBC replacements; no middleware or real SQL is used. */
class ProductMetricPipelineBatchSupportTest {
  /** Manual transaction-state negatives never leak into another selected unit method. */
  @AfterEach
  void clearThreadState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void exactFlagAndHealthyRoleAreAdmittedBeforeResourceConstruction() {
    assertFalse(ProductMetricPipelineBatchSupport.requested("false"));
    assertTrue(ProductMetricPipelineBatchSupport.requested("true"));
    for (String flag : List.of("TRUE", "", " unknown", "0"))
      assertThrows(
          IllegalStateException.class, () -> ProductMetricPipelineBatchSupport.requested(flag));
    ProductMetricPipelineBatchSupport.admit(false, "load", 2, "true");
    ProductMetricPipelineBatchSupport.admit(true, "convergence", 1, "false");
    for (String role : List.of("load", "redelivery", "content"))
      assertThrows(
          IllegalStateException.class,
          () -> ProductMetricPipelineBatchSupport.admit(true, role, 1, "false"));
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineBatchSupport.admit(true, "convergence", 2, "false"));
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineBatchSupport.admit(true, "convergence", 1, "true"));
  }

  @Test
  void batchFactoryBranchRetainsItsCustomizerAndSingleBranchIsNotSelected() {
    ProductMetricBatchContainerFactory factory =
        new ProductMetricBatchContainerFactory(new ProductMetricBatchChannelRegistry());
    factory.setContainerCustomizer(container -> container.setForceStop(true));
    Object original = ReflectionTestUtils.getField(factory, "containerCustomizer");
    assertTrue(
        ProductMetricPipelineBatchSupport.preserveFactory(
            true, factory, "productMetricContainerFactory"));
    assertSame(original, ReflectionTestUtils.getField(factory, "containerCustomizer"));
    assertFalse(
        ProductMetricPipelineBatchSupport.preserveFactory(
            false, factory, "productMetricContainerFactory"));
    assertFalse(
        ProductMetricPipelineBatchSupport.preserveFactory(true, factory, "unrelatedFactory"));
  }

  @Test
  void initializedAdapterIdentityAndActualBatchFieldsAreRequiredWithoutWrapping() throws Exception {
    ProductMetricBatchChannelRegistry channels = new ProductMetricBatchChannelRegistry();
    ProductMetricBatchContainerFactory factory = new ProductMetricBatchContainerFactory(channels);
    ProductMetricBatchListener bean =
        new ProductMetricBatchListener(mock(ProductMetricConsumer.class), channels);
    var method =
        ProductMetricBatchListener.class.getMethod("onMessages", List.class, Channel.class);
    BatchMessagingMessageListenerAdapter listener =
        new BatchMessagingMessageListenerAdapter(
            bean, method, false, null, new ProductMetricRawBatchingStrategy());
    listener.setHandlerAdapter(new HandlerAdapter(new InvocableHandlerMethod(bean, method)));
    SimpleMessageListenerContainer container =
        new SimpleMessageListenerContainer(mock(ConnectionFactory.class));
    container.setConcurrentConsumers(1);
    container.setMaxConcurrentConsumers(1);
    container.setPrefetchCount(4);
    container.setBatchSize(4);
    container.setReceiveTimeout(25);
    container.setConsumerBatchEnabled(true);
    container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
    container.setForceStop(true);
    container.setChannelTransacted(false);
    container.setupMessageListener(listener);
    ApplicationContext context = mock(ApplicationContext.class);
    RabbitListenerEndpointRegistry endpoints = mock(RabbitListenerEndpointRegistry.class);
    when(context.getBeanNamesForType(ProductMetricBatchListener.class))
        .thenReturn(new String[] {"batch"});
    when(context.getBeanNamesForType(ProductMetricSingleListener.class)).thenReturn(new String[0]);
    when(context.getBean(RabbitListenerEndpointRegistry.class)).thenReturn(endpoints);
    when(context.getBean(ProductMetricBatchListener.class)).thenReturn(bean);
    when(context.getBean(ProductMetricBatchChannelRegistry.class)).thenReturn(channels);
    when(context.getBean("productMetricContainerFactory")).thenReturn(factory);
    when(endpoints.getListenerContainerIds())
        .thenReturn(Set.of("productMetrics", "otherContentEndpoint"));
    when(endpoints.getListenerContainer("productMetrics")).thenReturn(container);
    assertEquals(
        4,
        ProductMetricPipelineBatchSupport.containerFacts(context, container, listener)
            .get("batchSize"));
    assertSame(listener, container.getMessageListener());
    container.setPrefetchCount(1);
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineBatchSupport.containerFacts(context, container, listener));
    container.setPrefetchCount(4);
    container.setChannelTransacted(true);
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineBatchSupport.containerFacts(context, container, listener));
    container.setChannelTransacted(false);
    assertSame(listener, container.getMessageListener());
  }

  @Test
  void outerAdviceReadsOnlyAfterRealInterceptorCommitAndRetainsOriginalReturn() throws Throwable {
    List<String> phases = new ArrayList<>();
    ProductMetricEventDTO event = event();
    StubMetrics target = new StubMetrics(phases, null);
    ProductMetricService proxy = proxy(target, phases);
    Advisor[] original = ((Advised) proxy).getAdvisors();
    AtomicInteger reads = new AtomicInteger();
    var observer =
        new ProductMetricPipelineBatchSupport.CommitObserver(
            (events, result) -> {
              assertEquals(List.of("begin", "target", "commit"), phases);
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
              reads.incrementAndGet();
              return Map.of("unitSnapshot", true);
            },
            (name, value) -> {});
    observer.install(proxy);
    assertSame(original[0], ((Advised) proxy).getAdvisors()[1]);
    Object returned = proxy.applyBatch(List.of(event));
    assertSame(target.returned, returned);
    assertEquals(1, target.calls);
    assertEquals(1, reads.get());
    assertEquals(
        1,
        observer
            .successfulFacts(Set.of(event.eventId), Set.of(event.eventId))
            .get("successfulBatchObservations"));
    assertThrows(
        IllegalStateException.class,
        () -> observer.successfulFacts(Set.of(event.eventId), Set.of()));
  }

  @Test
  void originalBusinessExceptionIsPreservedAndNeverReadsIndependentSnapshot() throws Exception {
    RuntimeException original = new IllegalStateException("controlled target failure");
    List<String> phases = new ArrayList<>();
    StubMetrics target = new StubMetrics(phases, original);
    ProductMetricService proxy = proxy(target, phases);
    AtomicInteger reads = new AtomicInteger();
    var observer =
        new ProductMetricPipelineBatchSupport.CommitObserver(
            (events, result) -> {
              reads.incrementAndGet();
              return Map.of();
            },
            (name, value) -> {});
    observer.install(proxy);
    assertSame(
        original, assertThrows(RuntimeException.class, () -> proxy.applyBatch(List.of(event()))));
    assertEquals(1, target.calls);
    assertEquals(0, reads.get());
    assertEquals(List.of("begin", "target", "rollback"), phases);
  }

  @Test
  void observationFailureIsStickyWithoutChangingCommittedBusinessResultOrRetrying()
      throws Exception {
    List<String> phases = new ArrayList<>();
    StubMetrics target = new StubMetrics(phases, null);
    ProductMetricService proxy = proxy(target, phases);
    AtomicInteger reads = new AtomicInteger();
    var observer =
        new ProductMetricPipelineBatchSupport.CommitObserver(
            (events, result) -> {
              reads.incrementAndGet();
              throw new IOException("controlled audit failure must not enter evidence");
            },
            (name, value) -> fail("no snapshot to publish"));
    observer.install(proxy);
    ProductMetricEventDTO event = event();
    target.returned = result(List.of(event));
    assertSame(target.returned, proxy.applyBatch(List.of(event)));
    assertEquals(1, target.calls);
    assertEquals(1, reads.get());
    assertEquals("POST_PROXY_OBSERVATION_FAILED", observer.failureCode());
    assertThrows(IllegalStateException.class, () -> observer.successfulFacts(Set.of(), Set.of()));
    assertEquals(List.of("begin", "target", "commit"), phases);
  }

  @Test
  void activeEntryCannotProduceCommitEvidenceButStillInvokesOriginalBusinessOnce()
      throws Exception {
    List<String> phases = new ArrayList<>();
    StubMetrics target = new StubMetrics(phases, null);
    ProductMetricService proxy = proxy(target, phases);
    AtomicInteger reads = new AtomicInteger();
    var observer =
        new ProductMetricPipelineBatchSupport.CommitObserver(
            (events, result) -> {
              reads.incrementAndGet();
              return Map.of();
            },
            (name, value) -> {});
    observer.install(proxy);
    TransactionSynchronizationManager.setActualTransactionActive(true);
    proxy.applyBatch(List.of(event()));
    assertEquals(1, target.calls);
    assertEquals(0, reads.get());
    assertEquals("ENTRY_OBSERVATION_INVALID", observer.failureCode());
  }

  @Test
  void snapshotUsesLedgerVersionNotWireOrLaterStatVersionAndAlwaysClosesReadScope()
      throws Exception {
    JdbcFixture fixture = new JdbcFixture(false);
    Map<String, Object> fact =
        fixture.reader.read(List.of(fixture.event), result(List.of(fixture.event)));
    @SuppressWarnings("unchecked")
    var versions = (List<Map<String, Object>>) fact.get("observedLedgerVersions");
    assertEquals(6L, versions.get(0).get("ledgerMetricVersion"));
    assertEquals(9L, versions.get(0).get("observedStatMetricVersion"));
    verify(fixture.projection).setLong(2, 6L);
    verify(fixture.connection).setReadOnly(true);
    verify(fixture.connection).setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
    verify(fixture.connection).setAutoCommit(false);
    verify(fixture.connection).rollback();
    verify(fixture.connection).close();
    assertEquals(true, fact.get("independentConnectionClosed"));
    assertTrue(fixture.sql.stream().allMatch(value -> value.startsWith("SELECT ")));
  }

  @Test
  void mismatchedCommittedIdentityCannotQualifyAndStillClosesIndependentConnection()
      throws Exception {
    JdbcFixture fixture = new JdbcFixture(true);
    assertThrows(
        IllegalStateException.class,
        () -> fixture.reader.read(List.of(fixture.event), result(List.of(fixture.event))));
    verify(fixture.connection).rollback();
    verify(fixture.connection).close();
  }

  private static ProductMetricEventDTO event() {
    return ProductMetricEvents.product("12345678-1234-1234-1234-123456789abc", 41L, false, null);
  }

  private static ProductMetricBatchApplyResultDTO result(List<ProductMetricEventDTO> events) {
    List<ProductMetricBatchEventResultDTO> results = new ArrayList<>();
    for (int index = 0; index < events.size(); index++)
      results.add(
          new ProductMetricBatchEventResultDTO(
              index,
              events.get(index).eventId,
              ProductMetricEvents.fingerprint(events.get(index)),
              "APPLIED"));
    return new ProductMetricBatchApplyResultDTO(results, events.size());
  }

  private static ProductMetricService proxy(StubMetrics target, List<String> phases) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(
            new StubTransactions(phases), new AnnotationTransactionAttributeSource()));
    return (ProductMetricService) factory.getProxy();
  }

  /** Controlled target is still invoked through the real Spring TransactionInterceptor. */
  static class StubMetrics extends ProductMetricService {
    /** Captured lifecycle order is local unit evidence, never a real database claim. */
    private final List<String> phases;

    /** Exact target exception must pass through unchanged. */
    private final RuntimeException failure;

    /** Returned DTO object identity is asserted after the outer observation. */
    private ProductMetricBatchApplyResultDTO returned;

    /** A recording failure must not call this target a second time. */
    private int calls;

    StubMetrics(List<String> phases, RuntimeException failure) {
      super(mock(ProductMetricMapper.class));
      this.phases = phases;
      this.failure = failure;
    }

    /** Return controlled results while the real unit transaction interceptor is active. */
    @Override
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.REPEATABLE_READ,
        timeout = 3)
    public ProductMetricBatchApplyResultDTO applyBatch(List<ProductMetricEventDTO> events) {
      calls++;
      phases.add("target");
      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
      if (failure != null) throw failure;
      if (returned == null) returned = result(events);
      return returned;
    }
  }

  /** Real transaction infrastructure drives these local lifecycle callbacks, without JDBC. */
  private static final class StubTransactions extends AbstractPlatformTransactionManager {
    /** Local ordered callbacks distinguish method return from transaction commit. */
    private final List<String> phases;

    private StubTransactions(List<String> phases) {
      this.phases = phases;
    }

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {
      phases.add("begin");
    }

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      phases.add("commit");
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {
      phases.add("rollback");
    }
  }

  /** PreparedStatement/ResultSet replacements exercise actual read logic and resource scopes. */
  private static final class JdbcFixture {
    /** Immutable valid input uses wire revision1, independently of the assigned ledger version6. */
    private final ProductMetricEventDTO event = event();

    /** No network or real DataSource constructor is invoked. */
    private final ProductOnlineMetricsMySqlIT.OwnedDataSource source =
        mock(ProductOnlineMetricsMySqlIT.OwnedDataSource.class);

    /** One explicit replacement connection proves rollback/close on both success and failure. */
    private final Connection connection = mock(Connection.class);

    /** Projection lookup must bind the actual ledger version, not the later aggregate version9. */
    private PreparedStatement projection;

    /** Every observed statement must remain read-only. */
    private final List<String> sql = new ArrayList<>();

    /** Production reader code is exercised with only JDBC interfaces replaced. */
    private final ProductMetricPipelineBatchSupport.JdbcSnapshot reader;

    private JdbcFixture(boolean wrongFingerprint) throws Exception {
      ReflectionTestUtils.setField(source, "catalog", "fixture_catalog");
      when(source.getConnection()).thenReturn(connection);
      when(connection.getCatalog()).thenReturn("fixture_catalog");
      when(connection.isReadOnly()).thenReturn(true);
      when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_REPEATABLE_READ);
      when(connection.prepareStatement(anyString()))
          .thenAnswer(
              invocation -> {
                String query = invocation.getArgument(0);
                sql.add(query);
                PreparedStatement statement = mock(PreparedStatement.class);
                Map<Object, Object> row = new LinkedHashMap<>();
                if (query.startsWith("SELECT series_id")) {
                  row.put("series_id", ProductMetricEvents.SERIES);
                  row.put("revision", 100L);
                } else if (query.startsWith("SELECT fingerprint")) {
                  row.put(
                      "fingerprint",
                      wrongFingerprint ? "wrong" : ProductMetricEvents.fingerprint(event));
                  row.put("event_type", event.eventType);
                  row.put("product_id", event.productId);
                  row.put("sku_id", event.skuId);
                  row.put("quantity", event.quantity);
                  row.put("order_no", event.orderNo);
                  row.put("source_ref", event.sourceRef);
                  row.put("source_revision", event.sourceRevision);
                  row.put("occurred_at", OffsetDateTime.parse(event.occurredAt).toLocalDateTime());
                  row.put("metric_version", 6L);
                } else if (query.startsWith("SELECT metric_version")) row.put(1, 9L);
                else if (query.startsWith("SELECT status")) {
                  projection = statement;
                  row.put(1, "DONE");
                } else if (query.startsWith("SELECT COALESCE")) row.put(1, 12L);
                else if (query.startsWith("SELECT CONNECTION_ID")) row.put(1, 77L);
                else throw new IllegalStateException("unexpected pure query");
                ResultSet resultRows = rows(row);
                when(statement.executeQuery()).thenReturn(resultRows);
                return statement;
              });
      reader = new ProductMetricPipelineBatchSupport.JdbcSnapshot(source, "fixture_catalog");
    }
  }

  private static ResultSet rows(Map<Object, Object> values) throws Exception {
    ResultSet rows = mock(ResultSet.class);
    AtomicReference<Object> last = new AtomicReference<>();
    when(rows.next()).thenReturn(true, false);
    when(rows.wasNull()).thenAnswer(invocation -> last.get() == null);
    when(rows.getLong(anyString()))
        .thenAnswer(
            invocation -> {
              Object value = values.get(invocation.getArgument(0));
              last.set(value);
              return value == null ? 0L : ((Number) value).longValue();
            });
    when(rows.getLong(anyInt()))
        .thenAnswer(
            invocation -> {
              Object value = values.get(invocation.getArgument(0));
              last.set(value);
              return value == null ? 0L : ((Number) value).longValue();
            });
    when(rows.getString(anyString()))
        .thenAnswer(
            invocation -> {
              Object value = values.get(invocation.getArgument(0));
              last.set(value);
              return value == null ? null : value.toString();
            });
    when(rows.getString(anyInt()))
        .thenAnswer(
            invocation -> {
              Object value = values.get(invocation.getArgument(0));
              last.set(value);
              return value == null ? null : value.toString();
            });
    when(rows.getObject(anyString()))
        .thenAnswer(invocation -> values.get(invocation.getArgument(0)));
    return rows;
  }
}
