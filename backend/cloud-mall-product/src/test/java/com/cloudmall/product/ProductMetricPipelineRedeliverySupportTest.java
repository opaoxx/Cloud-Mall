package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.impl.metrics.ProductMetricConsumer;
import com.cloudmall.product.service.impl.metrics.ProductMetricOutboxDispatcher;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.rabbitmq.client.impl.AMQConnection;
import com.rabbitmq.client.impl.ChannelN;
import java.io.IOException;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.CommonAnnotationBeanPostProcessor;
import org.springframework.context.support.GenericApplicationContext;
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

/** Mock transport and local transaction boundaries only; no broker, HTTP or real SQL is used. */
class ProductMetricPipelineRedeliverySupportTest {
  /** Local negatives cannot leak a transaction flag into another selected method. */
  @AfterEach
  void clearThreadState() {
    TransactionSynchronizationManager.clear();
  }

  @Test
  void strictNewRoleAndSingleTargetProxyKeepOriginalArgumentsAndStackCleanup() throws Exception {
    ProductMetricPipelineBatchSupport.admit(true, "batch-redelivery", 1, "false");
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineBatchSupport.admit(false, "batch-redelivery", 1, "false"));
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineBatchSupport.admit(true, "batch-redelivery", 2, "false"));
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineBatchSupport.admit(true, "redelivery", 1, "false"));
    Fixture fixture = new Fixture(null, 1, event(41));
    assertSame(
        fixture.target, ProductMetricPipelineRedeliverySupport.ultimateConsumer(fixture.proxy));
    fixture.deliver();
    verify(fixture.target)
        .consumeBatch(same(fixture.messages), same(fixture.physical), same(fixture.registry));
    verify(fixture.service).applyBatch(anyList());
    assertFalse(fixture.support.currentThreadHasFrame());
    assertNull(fixture.support.failureCode());
    verify(fixture.physical, never()).close();
    RabbitListenerEndpointRegistry endpoints =
        (RabbitListenerEndpointRegistry) ReflectionTestUtils.getField(fixture.target, "registry");
    SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
    when(endpoints.getListenerContainer("productMetrics")).thenReturn(container);
    when(container.isRunning()).thenReturn(false);
    when(container.getActiveConsumerCount()).thenReturn(0);
    doAnswer(
            invocation -> {
              fixture.open.set(false);
              invocation.getArgument(0, Runnable.class).run();
              return null;
            })
        .when(container)
        .stop(any(Runnable.class));
    try (GenericApplicationContext context = new GenericApplicationContext()) {
      context.registerBean(CommonAnnotationBeanPostProcessor.class);
      context.registerBean("observedConsumer", ProductMetricConsumer.class, () -> fixture.proxy);
      context.refresh();
      assertSame(fixture.proxy, context.getBean(ProductMetricConsumer.class));
      assertSame(
          fixture.target,
          ProductMetricPipelineRedeliverySupport.ultimateConsumer(
              context.getBean(ProductMetricConsumer.class)));
    }
    verify(fixture.target, times(1)).close();
    assertEquals(true, ReflectionTestUtils.getField(fixture.target, "closeCompleted"));
    assertNull(ReflectionTestUtils.getField(fixture.target, "closeFailure"));
    assertSame(fixture.registry, ReflectionTestUtils.getField(fixture.target, "batchRegistry"));
  }

  @Test
  void successfulCommittedTargetClosesOnlyOriginalPhysicalOnceWithoutIssuingAck() throws Exception {
    Fixture fixture = new Fixture(null, 1, event(42));
    fixture.repeatedHook = true;
    fixture.support.arm();
    fixture.support.confirmTarget(fixture.event.eventId);
    fixture.deliver();
    assertTrue(fixture.support.faultObserved());
    assertNull(fixture.support.failureCode());
    verify(fixture.physical, times(1)).close();
    verify(fixture.physical, never()).basicAck(anyLong(), anyBoolean());
    verify(fixture.service, times(1)).applyBatch(anyList());
    assertFalse(fixture.support.currentThreadHasFrame());
    assertNotNull(ReflectionTestUtils.getField(fixture.target, "batchSafetyFailure"));
  }

  @Test
  void armedOtherProductCannotTriggerTargetFault() throws Exception {
    Fixture fixture = new Fixture(null, 1, event(41));
    fixture.support.arm();
    fixture.deliver();
    assertFalse(fixture.support.faultObserved());
    verify(fixture.physical, never()).close();
    verify(fixture.physical).basicAck(1, false);
    assertNull(fixture.support.failureCode());
  }

  @Test
  void absentFrameOrUncommittedObservationCannotClose() throws Exception {
    Fixture missing = new Fixture(null, 1, event(42));
    missing.support.arm();
    missing.generation.afterCommitted(
        List.of(missing.event), result(missing.event, "APPLIED"), snapshot(missing.event));
    assertNotNull(missing.support.failureCode());
    assertFalse(missing.support.currentThreadHasFrame());
    verify(missing.physical, never()).close();
    Fixture transactional = new Fixture(null, 1, event(42));
    transactional.support.arm();
    transactional.beforeHook =
        () -> TransactionSynchronizationManager.setActualTransactionActive(true);
    transactional.deliver();
    assertNotNull(transactional.support.failureCode());
    verify(transactional.physical, never()).close();
  }

  @Test
  void changedBindingAndUnleasedCallbackNeverClosePhysical() throws Exception {
    Fixture changed = new Fixture(null, 1, event(42));
    changed.support.arm();
    ChannelN replacement = mock(ChannelN.class);
    changed.beforeHook = () -> when(changed.bqc.getChannel()).thenReturn(replacement);
    changed.deliver();
    assertNotNull(changed.support.failureCode());
    verify(changed.physical, never()).close();
    Fixture unleased = new Fixture(null, 1, event(42));
    unleased.support.arm();
    doAnswer(
            invocation -> {
              unleased.generation.afterCommitted(
                  List.of(unleased.event),
                  result(unleased.event, "APPLIED"),
                  snapshot(unleased.event));
              return null;
            })
        .when(unleased.target)
        .consumeBatch(anyList(), any(), any());
    unleased.deliver();
    assertNotNull(unleased.support.failureCode());
    verify(unleased.physical, never()).close();

    Fixture changedTag = new Fixture(null, 1, event(42));
    changedTag.support.arm();
    changedTag.beforeHook =
        () -> changedTag.messages.get(0).getMessageProperties().setDeliveryTag(2L);
    changedTag.deliver();
    assertEquals("POST_COMMIT_ASSOCIATION_INVALID", changedTag.support.failureCode());
    verify(changedTag.physical, never()).close();
    verify(changedTag.service, times(1)).applyBatch(anyList());

    Fixture retired = new Fixture(null, 1, event(42));
    retired.support.arm();
    IOException ackFailure = new IOException("controlled original settlement failure");
    doThrow(ackFailure).when(retired.physical).basicAck(1L, false);
    doAnswer(
            invocation -> {
              // This controlled owner replaces the Consumer body, not the adapter: acquire once,
              // retire through the original settlement API, and retain that same active lease.
              String consumerTag = retired.messages.get(0).getMessageProperties().getConsumerTag();
              var lease = retired.registry.acquire(consumerTag, retired.physical, List.of(1L));
              try {
                assertSame(ackFailure, assertThrows(IOException.class, () -> lease.ack(1L)));
                synchronized (retired.registry) {
                  Map<?, ?> bindings =
                      (Map<?, ?>) ReflectionTestUtils.getField(retired.registry, "bindings");
                  Object binding = bindings.get(consumerTag);
                  assertEquals(true, ReflectionTestUtils.getField(binding, "retired"));
                  assertEquals(1, ReflectionTestUtils.getField(binding, "leases"));
                }
                retired.generation.afterCommitted(
                    List.of(retired.event),
                    result(retired.event, "APPLIED"),
                    snapshot(retired.event));
              } finally {
                lease.release();
              }
              return null;
            })
        .when(retired.target)
        .consumeBatch(anyList(), any(), any());
    retired.deliver();
    assertEquals("POST_COMMIT_ASSOCIATION_INVALID", retired.support.failureCode());
    verify(retired.physical, never()).close();
    verify(retired.physical, times(1)).basicAck(1L, false);
    verifyNoInteractions(retired.service);
  }

  @Test
  void originalThrowableIdentityAndFinallyStackCleanupArePreserved() {
    Fixture fixture = new Fixture(null, 1, event(42));
    RuntimeException original = new IllegalArgumentException("controlled-original");
    doAnswer(
            invocation -> {
              // Corrupt only this private test observer stack, never the production registry or
              // consumer.
              ThreadLocal<?> frames =
                  (ThreadLocal<?>) ReflectionTestUtils.getField(fixture.support, "calls");
              ((Deque<?>) frames.get()).clear();
              throw original;
            })
        .when(fixture.target)
        .consumeBatch(anyList(), any(), any());
    assertSame(original, assertThrows(RuntimeException.class, fixture::deliver));
    assertFalse(fixture.support.currentThreadHasFrame());
    assertEquals("STACK_CLEANUP_INVALID", fixture.support.failureCode());
    verifyNoInteractions(fixture.service);
  }

  @Test
  void expectedSafetyCannotNormalizePhysicalFailureOrExtraException() throws Exception {
    Fixture fixture = new Fixture(null, 1, event(42));
    fixture.support.arm();
    fixture.deliver();
    Throwable aggregate = fixture.syntheticExpectedClose();
    ReflectionTestUtils.setField(fixture.target, "closeStartedNanos", 0L);
    assertThrows(
        IllegalStateException.class,
        () -> fixture.generation.expectedSafetyClosed(aggregate, closedFacts()));
    // nanoTime has an arbitrary origin; only elapsed duration must be nonnegative.
    ReflectionTestUtils.setField(fixture.target, "closeStartedNanos", -5L);
    Map<String, Object> incomplete = new LinkedHashMap<>(closedFacts());
    incomplete.put("httpClosed", false);
    assertThrows(
        IllegalStateException.class,
        () -> fixture.generation.expectedSafetyClosed(aggregate, incomplete));
    assertThrows(IllegalStateException.class, () -> fixture.support.generation(2));
    IllegalStateException extra = new IllegalStateException("CM21_PRODUCT_CLOSE_UNKNOWN");
    extra.addSuppressed(aggregate.getSuppressed()[0]);
    extra.addSuppressed(new IllegalArgumentException("unexpected"));
    assertThrows(
        IllegalStateException.class,
        () -> fixture.generation.expectedSafetyClosed(extra, closedFacts()));
    fixture.generation.expectedSafetyClosed(aggregate, closedFacts());
    assertNotNull(fixture.support.generation(2));
    assertSame(
        aggregate.getSuppressed()[0], ReflectionTestUtils.getField(fixture.target, "closeFailure"));
  }

  @Test
  void newObjectsCanReplaySameNumericTagOnlyAsBrokerMarkedDuplicate() throws Exception {
    Fixture original = new Fixture(null, 1, event(42));
    original.support.arm();
    original.support.confirmTarget(original.event.eventId);
    original.deliver();
    original.generation.expectedSafetyClosed(original.syntheticExpectedClose(), closedFacts());
    Fixture replay = new Fixture(original.support, 2, original.event);
    replay.outcome = "DUPLICATE";
    replay.messages.get(0).getMessageProperties().setRedelivered(true);
    replay.deliver();
    assertTrue(original.support.replayObserved());
    assertEquals(1, original.support.successfulFacts().get("closeAttempts"));
    assertNotSame(original.physical, replay.physical);
    assertEquals(original.physical.getChannelNumber(), replay.physical.getChannelNumber());
    assertEquals(
        original.messages.get(0).getMessageProperties().getDeliveryTag(),
        replay.messages.get(0).getMessageProperties().getDeliveryTag());
    verify(replay.physical, never()).close();
  }

  @Test
  void generationReuseAndUnclosedPriorGenerationCannotReplaceEvidence() {
    ProductMetricPipelineRedeliverySupport support =
        new ProductMetricPipelineRedeliverySupport((name, value) -> {});
    support.generation(1);
    assertThrows(IllegalStateException.class, () -> support.generation(1));
    assertThrows(IllegalStateException.class, () -> support.generation(2));
    assertThrows(IllegalStateException.class, support::successfulFacts);
  }

  @Test
  void hookRunsAfterRealInterceptorAndSnapshotAndFailureDoesNotAlterCommittedReturn()
      throws Exception {
    AtomicInteger commits = new AtomicInteger();
    CommitTarget target = new CommitTarget();
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(
            new LocalTransactions(commits), new AnnotationTransactionAttributeSource()));
    ProductMetricService proxy = (ProductMetricService) factory.getProxy();
    AtomicBoolean read = new AtomicBoolean();
    var observer =
        new ProductMetricPipelineBatchSupport.CommitObserver(
            (events, result) -> {
              assertEquals(1, commits.get());
              read.set(true);
              return Map.of("unitSnapshot", true);
            },
            (name, value) -> {},
            (events, result, facts) -> {
              assertTrue(read.get());
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
              throw new IllegalStateException("controlled hook failure");
            });
    observer.install(proxy);
    ProductMetricBatchApplyResultDTO returned = proxy.applyBatch(List.of(event(42)));
    assertSame(target.returned, returned);
    assertEquals(1, target.calls);
    assertEquals("POST_PROXY_OBSERVATION_FAILED", observer.failureCode());
    assertThrows(IllegalStateException.class, () -> observer.install(proxy));
  }

  private static ProductMetricEventDTO event(long product) {
    return ProductMetricEvents.product(
        "12345678-1234-1234-1234-123456789abc", product, false, null);
  }

  private static ProductMetricBatchApplyResultDTO result(
      ProductMetricEventDTO event, String outcome) {
    return new ProductMetricBatchApplyResultDTO(
        List.of(
            new ProductMetricBatchEventResultDTO(
                0, event.eventId, ProductMetricEvents.fingerprint(event), outcome)),
        "APPLIED".equals(outcome) ? 1 : 0);
  }

  private static Map<String, Object> snapshot(ProductMetricEventDTO event) {
    return Map.of(
        "independentConnectionClosed",
        true,
        "readOnly",
        true,
        "isolation",
        java.sql.Connection.TRANSACTION_REPEATABLE_READ,
        "connectionSource",
        "EXPLICIT_NEW_OWNED_DRIVER_MANAGER_CONNECTION",
        "observedLedgerVersions",
        List.of(
            Map.of(
                "eventId",
                event.eventId,
                "fingerprint",
                ProductMetricEvents.fingerprint(event),
                "ledgerMetricVersion",
                1L)));
  }

  private static Map<String, Object> closedFacts() {
    return Map.of(
        "schedulerTerminated",
        true,
        "contextInactive",
        true,
        "poolClosed",
        true,
        "rawConnectionsClosed",
        true,
        "httpClosed",
        true,
        "listenerStopped",
        true,
        "productStopObserved",
        true,
        "consumerCloseObserved",
        true);
  }

  /** Real registry/Consumer control flow over mocks; no mocked private Binding writes. */
  private static final class Fixture {
    /** Exact canonical event represented by this controlled delivery. */
    private final ProductMetricEventDTO event;

    /** Fixture-level coordinator shared only when explicitly testing the second context. */
    private final ProductMetricPipelineRedeliverySupport support;

    /** Unique context-local advice and evidence owner. */
    private final ProductMetricPipelineRedeliverySupport.Generation generation;

    /** Business service replacement; no Mapper or database operation is executed. */
    private final ProductMetricService service = mock(ProductMetricService.class);

    /** Real ownership registry whose private fields are never written by the unit fixture. */
    private final ProductMetricBatchChannelRegistry registry =
        new ProductMetricBatchChannelRegistry();

    /** Original physical channel type required by the production ownership contract. */
    private final ChannelN physical = mock(ChannelN.class);

    /** Original non-recovering connection identity used by the real registry. */
    private final AMQConnection connection = mock(AMQConnection.class);

    /** Original registration owner with a controlled getChannel return. */
    private final BlockingQueueConsumer bqc = mock(BlockingQueueConsumer.class);

    /** Mocked transport liveness changes only on the unit's deliberate close or teardown. */
    private final AtomicBoolean open = new AtomicBoolean(true),
        connectionOpen = new AtomicBoolean(true);

    /** One constructed real Consumer target, spied only for exact invocation assertions. */
    private final ProductMetricConsumer target;

    /** Test proxy delegating to the target; it does not own separate consumer state. */
    private final ProductMetricConsumer proxy;

    /** Original immutable list and message objects supplied to the callback. */
    private final List<Message> messages;

    /** Controlled business result distinguishes first application from duplicate replay. */
    private String outcome = "APPLIED";

    /** Optional same-frame duplicate hook proves physical close remains one-shot. */
    private boolean repeatedHook;

    /** Fixed in-memory negative arrangement applied after real lease acquisition. */
    private Runnable beforeHook = () -> {};

    /** Build only mocks and original registry/consumer objects, without background work. */
    private Fixture(
        ProductMetricPipelineRedeliverySupport shared, int number, ProductMetricEventDTO event) {
      this.event = event;
      support =
          shared == null ? new ProductMetricPipelineRedeliverySupport((name, value) -> {}) : shared;
      generation = support.generation(number);
      target =
          spy(
              new ProductMetricConsumer(
                  service,
                  mock(ProductMetricOutboxDispatcher.class),
                  mock(RabbitListenerEndpointRegistry.class)));
      target.bindBatchRegistry(registry);
      when(physical.isOpen()).thenAnswer(invocation -> open.get());
      when(physical.getConnection()).thenReturn(connection);
      when(physical.getChannelNumber()).thenReturn(1);
      when(connection.isOpen()).thenAnswer(invocation -> connectionOpen.get());
      when(bqc.getChannel()).thenReturn(physical);
      String consumerTag =
          registry.registerBeforeConsume(bqc, ProductMetricConsumer.QUEUE, physical);
      MessageProperties properties = new MessageProperties();
      properties.setConsumerTag(consumerTag);
      properties.setConsumerQueue(ProductMetricConsumer.QUEUE);
      properties.setDeliveryTag(1);
      messages = List.of(new Message(ProductMetricEvents.bytes(event), properties));
      try {
        doAnswer(
                invocation -> {
                  open.set(false);
                  return null;
                })
            .when(physical)
            .close();
      } catch (Exception impossibleMockSetup) {
        throw new AssertionError(impossibleMockSetup);
      }
      when(service.applyBatch(anyList()))
          .thenAnswer(
              invocation -> {
                List<ProductMetricEventDTO> events = invocation.getArgument(0);
                ProductMetricBatchApplyResultDTO returned = result(event, outcome);
                beforeHook.run();
                generation.afterCommitted(events, returned, snapshot(event));
                if (repeatedHook) generation.afterCommitted(events, returned, snapshot(event));
                return returned;
              });
      proxy = generation.wrapConsumer(target);
    }

    private void deliver() {
      proxy.consumeBatch(messages, physical, registry);
    }

    /** Synthetic lifecycle fields test the gate only; this is not a real close assertion. */
    private Throwable syntheticExpectedClose() {
      Throwable sticky = (Throwable) ReflectionTestUtils.getField(target, "batchSafetyFailure");
      assertNotNull(sticky);
      IllegalStateException close =
          new IllegalStateException("METRIC_BATCH_SAFETY_UNKNOWN", sticky);
      ReflectionTestUtils.setField(target, "closeFailure", close);
      ReflectionTestUtils.setField(target, "closeCompleted", true);
      ReflectionTestUtils.setField(target, "closed", true);
      ReflectionTestUtils.setField(target, "closeRequested", true);
      ReflectionTestUtils.setField(target, "closeStartedNanos", 1L);
      ReflectionTestUtils.setField(target, "closeElapsedNanos", 1L);
      connectionOpen.set(false);
      IllegalStateException aggregate = new IllegalStateException("CM21_PRODUCT_CLOSE_UNKNOWN");
      aggregate.addSuppressed(close);
      return aggregate;
    }
  }

  /** A controlled result still crosses the real Spring transaction interceptor. */
  static class CommitTarget extends ProductMetricService {
    /** Exact number of original service invocations through the transaction interceptor. */
    private int calls;

    /** Controlled DTO whose object identity must survive a failing test hook. */
    private ProductMetricBatchApplyResultDTO returned;

    /** Supply an unused Mapper replacement without opening a JDBC connection. */
    CommitTarget() {
      super(mock(ProductMetricMapper.class));
    }

    /** Return one controlled batch while the original transaction infrastructure is active. */
    @Override
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.REPEATABLE_READ,
        timeout = 3)
    public ProductMetricBatchApplyResultDTO applyBatch(List<ProductMetricEventDTO> events) {
      calls++;
      returned = result(events.get(0), "APPLIED");
      return returned;
    }
  }

  /** Local callbacks give timing evidence without creating JDBC connections or threads. */
  private static final class LocalTransactions extends AbstractPlatformTransactionManager {
    /** Commit callback count proves the observer executes outside the transaction return. */
    private final AtomicInteger commits;

    /** Bind the local commit counter without creating transaction resources. */
    private LocalTransactions(AtomicInteger commits) {
      this.commits = commits;
    }

    @Override
    protected Object doGetTransaction() {
      return new Object();
    }

    @Override
    protected void doBegin(Object transaction, TransactionDefinition definition) {}

    @Override
    protected void doCommit(DefaultTransactionStatus status) {
      commits.incrementAndGet();
    }

    @Override
    protected void doRollback(DefaultTransactionStatus status) {}
  }
}
