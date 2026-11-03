package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.config.ProductMetricMessagingConfiguration;
import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.ProductIndexDocumentLoader;
import com.cloudmall.product.service.ProductMetricBatchListener;
import com.cloudmall.product.service.ProductMetricConsumer;
import com.cloudmall.product.service.ProductMetricOutboxDispatcher;
import com.cloudmall.product.service.ProductMetricService;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.impl.AMQConnection;
import com.rabbitmq.client.impl.ChannelN;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.annotation.EnableRabbit;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.BatchMessagingMessageListenerAdapter;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Genuine unstarted Spring batch route and original consumer/registry, with transport leaves
 * mocked.
 */
class ProductMetricCallbackServiceObservationIntegrationTest {
  /** Cold local JSON codec used only after callbacks have returned. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Fixed test identity is unrelated to any middleware namespace. */
  private static final String OWNER = "0123456789abcdef0123456789abcdef";

  /** JUnit owns these local files; no actual completion or workload directory is created. */
  @TempDir Path temporary;

  @Test
  void defaultOffFlagAndInvalidEnabledProfilesDoNotAcquireDiagnosticResources() throws Exception {
    assertFalse(
        ProductOnlineMetricsPipelineIT.callbackServiceObservationRequested(
            "false", false, null, 2, 8, false, "true", "true"));
    assertTrue(
        ProductOnlineMetricsPipelineIT.callbackServiceObservationRequested(
            "true",
            true,
            ProductMetricPipelineLoadSupport.Mode.BATCH1,
            1,
            300,
            true,
            "false",
            "false"));
    for (String value : Arrays.asList(null, "", "TRUE", "yes", " false"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ProductOnlineMetricsPipelineIT.callbackServiceObservationRequested(
                  value,
                  true,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1,
                  1,
                  300,
                  true,
                  "false",
                  "false"));
    for (Object[] invalid :
        List.of(
            new Object[] {
              false, ProductMetricPipelineLoadSupport.Mode.BATCH1, 1, 300, true, "false", "false"
            },
            new Object[] {
              true, ProductMetricPipelineLoadSupport.Mode.SINGLE1, 1, 300, true, "false", "false"
            },
            new Object[] {
              true, ProductMetricPipelineLoadSupport.Mode.BATCH1, 2, 300, true, "false", "false"
            },
            new Object[] {
              true, ProductMetricPipelineLoadSupport.Mode.BATCH1, 1, 8, true, "false", "false"
            },
            new Object[] {
              true, ProductMetricPipelineLoadSupport.Mode.BATCH1, 1, 300, false, "false", "false"
            },
            new Object[] {
              true, ProductMetricPipelineLoadSupport.Mode.BATCH1, 1, 300, true, "true", "false"
            },
            new Object[] {
              true, ProductMetricPipelineLoadSupport.Mode.BATCH1, 1, 300, true, "false", "true"
            })) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ProductOnlineMetricsPipelineIT.callbackServiceObservationRequested(
                  "true",
                  (boolean) invalid[0],
                  (ProductMetricPipelineLoadSupport.Mode) invalid[1],
                  (int) invalid[2],
                  (int) invalid[3],
                  (boolean) invalid[4],
                  (String) invalid[5],
                  (String) invalid[6]));
    }
    AssertionError primary = new AssertionError("unchanged original");
    assertSame(
        primary,
        ProductOnlineMetricsPipelineIT.completeCallbackServiceObservationAfterClose(
            temporary.resolve("absent"),
            null,
            OWNER,
            ProductMetricPipelineLoadSupport.Mode.BATCH1,
            primary));
    assertFalse(Files.exists(temporary.resolve("absent")));
  }

  @Test
  void actualFactoryDelegateAndOriginalConsumerFinallyPreserveIdentitiesAndNativeAcknowledgement()
      throws Throwable {
    try (Harness harness = new Harness(true)) {
      Object adapter = harness.container.getMessageListener();
      Object bean = harness.context.getBean(ProductMetricBatchListener.class);
      Advisor[] originalAdvisors = ((Advised) harness.service).getAdvisors().clone();
      Map<String, Object> runtime =
          harness.observer.runtimeFacts(harness.context, harness.container);
      assertEquals(false, runtime.get("rawChannelWrappedForObservation"));
      assertInstanceOf(BatchMessagingMessageListenerAdapter.class, adapter);
      List<Message> messages = harness.messages();
      harness.invoke(messages);
      verify(harness.consumer)
          .consumeBatch(same(messages), same(harness.channel), same(harness.registry));
      verify(harness.channel).basicAck(1L, false);
      assertEquals(1, harness.target.calls);
      assertEquals(0, ReflectionTestUtils.getField(harness.consumer, "activeBatch"));
      harness.assertLeaseReleased();
      assertSame(adapter, harness.container.getMessageListener());
      assertSame(bean, harness.context.getBean(ProductMetricBatchListener.class));
      assertSame(harness.service, ReflectionTestUtils.getField(harness.consumer, "service"));
      assertArrayEquals(originalAdvisors, ((Advised) harness.service).getAdvisors());
      assertEquals(15000L, ReflectionTestUtils.getField(harness.container, "shutdownTimeout"));
      assertEquals(true, ReflectionTestUtils.getField(harness.container, "forceStop"));
      assertEquals(4, ReflectionTestUtils.getField(harness.container, "prefetchCount"));
      assertEquals(1, harness.factory.getAdviceChain().length);
      assertSame(harness.callback.callbackAdvice(), harness.factory.getAdviceChain()[0]);
      assertFalse(harness.container.isRunning());
      assertEquals(0, harness.container.getActiveConsumerCount());
      verify(harness.connection, never()).createConnection();
      harness.callback.assertHealthyAfterClose();
      JsonNode snapshot = JSON.valueToTree(harness.callback.snapshot());
      assertEquals(1, snapshot.path("collectorState").path("callbacksCompleted").asInt());
      assertEquals(1, snapshot.path("collectorState").path("serviceAttemptsCompleted").asInt());
      assertEquals("COMPLETE", snapshot.path("qualification").path("partitionCoverage").asText());
    }
  }

  @Test
  void actualUnobservedFactoryHasNoAdviceOrNewClockAndRetainsOriginalCallback() throws Throwable {
    try (Harness harness = new Harness(false)) {
      assertNull(harness.callback);
      assertTrue(
          harness.factory.getAdviceChain() == null || harness.factory.getAdviceChain().length == 0);
      harness.invoke(harness.messages());
      assertEquals(1, harness.target.calls);
      assertEquals(0, harness.clockReads.get());
      harness.assertLeaseReleased();
      verify(harness.channel).basicAck(1L, false);
      verify(harness.connection, never()).createConnection();
    }
  }

  @Test
  void originalErrorTraversesRealAdapterAndConsumerFinallyWithoutDiagnosticReplacement()
      throws Throwable {
    try (Harness harness = new Harness(true)) {
      AssertionError original = new AssertionError("original application error");
      harness.target.problem = original;
      Throwable observed = assertThrows(Throwable.class, () -> harness.invoke(harness.messages()));
      assertSame(original, rootCause(observed));
      assertEquals(1, harness.target.calls);
      assertEquals(0, ReflectionTestUtils.getField(harness.consumer, "activeBatch"));
      harness.assertLeaseReleased();
      verify(harness.channel, never()).basicAck(anyLong(), anyBoolean());
      JsonNode snapshot = JSON.valueToTree(harness.callback.snapshot());
      assertEquals(0, snapshot.path("collectorState").path("activeCallbacks").asInt());
      assertEquals(0, snapshot.path("collectorState").path("activeServiceAttempts").asInt());
      assertEquals(1, snapshot.path("collectorState").path("callbacksCompleted").asInt());
      harness.callback.assertHealthyAfterClose();
      verify(harness.connection, never()).createConnection();
    }
  }

  @Test
  void originalClosedConsumerEarlyReturnProducesNoServiceAttemptOrAcknowledgement()
      throws Throwable {
    try (Harness harness = new Harness(true)) {
      when(harness.channel.isOpen()).thenReturn(false);
      when(harness.physicalConnection.isOpen()).thenReturn(false);
      harness.consumer.close();
      harness.invoke(harness.messages());
      assertEquals(0, harness.target.calls);
      assertEquals(0, ReflectionTestUtils.getField(harness.consumer, "activeBatch"));
      harness.assertLeaseReleased();
      verify(harness.channel, never()).basicAck(anyLong(), anyBoolean());
      verify(harness.channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
      harness.callback.assertHealthyAfterClose();
      JsonNode snapshot = JSON.valueToTree(harness.callback.snapshot());
      assertEquals(1, snapshot.path("collectorState").path("callbacksCompleted").asInt());
      assertEquals(0, snapshot.path("collectorState").path("serviceAttemptsEntered").asInt());
      assertEquals(
          "UNAVAILABLE", snapshot.path("qualification").path("partitionCoverage").asText());
    }
  }

  @Test
  void originalConsumerSwallowsControlledRuntimeAndStillReleasesActualLease() throws Throwable {
    try (Harness harness = new Harness(true)) {
      IllegalStateException original = new IllegalStateException("controlled nontransient failure");
      harness.target.problem = original;
      harness.invoke(harness.messages());
      assertSame(original, ReflectionTestUtils.getField(harness.consumer, "batchSafetyFailure"));
      assertEquals(1, harness.target.calls);
      assertEquals(0, ReflectionTestUtils.getField(harness.consumer, "activeBatch"));
      harness.assertLeaseReleased();
      verify(harness.channel, never()).basicAck(anyLong(), anyBoolean());
      verify(harness.channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
      harness.callback.assertHealthyAfterClose();
      JsonNode snapshot = JSON.valueToTree(harness.callback.snapshot());
      assertEquals(1, observations(snapshot.path("callbackCells"), "NORMAL"));
      assertEquals(1, observations(snapshot.path("attemptCells"), "THREW"));
      assertEquals(0, snapshot.path("collectorState").path("activeCallbacks").asInt());
      assertEquals(0, snapshot.path("collectorState").path("activeServiceAttempts").asInt());
    }
  }

  /** Sum fixed cells only; a normal callback outcome is not a claim of application success. */
  private static long observations(JsonNode cells, String outcome) {
    long total = 0;
    for (JsonNode cell : cells)
      if (outcome.equals(cell.path("outcome").asText()))
        total += cell.path("stats").path("observations").asLong();
    return total;
  }

  @Test
  void instrumentedReleaseFaultRunsRealAcquireAndReleaseBeforeOriginalConsumerFinallyPropagates()
      throws Throwable {
    AssertionError originalRelease =
        new AssertionError("controlled failure after actual lease release");
    try (Harness harness = new Harness(true, originalRelease)) {
      Throwable observed = assertThrows(Throwable.class, () -> harness.invoke(harness.messages()));
      assertSame(originalRelease, rootCause(observed));
      assertEquals(1, harness.target.calls);
      assertEquals(1, harness.realReleaseCalls);
      verify(harness.releaseFaultLease, times(1)).release();
      verify(harness.registry, times(1)).acquire(anyString(), same(harness.channel), anyList());
      verify(harness.channel, times(1)).basicAck(1L, false);
      verify(harness.channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
      assertEquals(0, ReflectionTestUtils.getField(harness.consumer, "activeBatch"));
      harness.assertLeaseReleased();
      assertSame(harness.registry, ReflectionTestUtils.getField(harness.factory, "registry"));
      assertSame(harness.registry, ReflectionTestUtils.getField(harness.consumer, "batchRegistry"));
      assertSame(
          harness.registry,
          ReflectionTestUtils.getField(
              harness.context.getBean(ProductMetricBatchListener.class), "registry"));
      harness.callback.assertHealthyAfterClose();
      JsonNode snapshot = JSON.valueToTree(harness.callback.snapshot());
      assertEquals(1, observations(snapshot.path("callbackCells"), "THREW"));
      assertEquals(1, observations(snapshot.path("attemptCells"), "NORMAL"));
      verify(harness.connection, never()).createConnection();
    }
  }

  @Test
  void coldSidecarIsIndependentCreateOnlyAndColdErrorsNeverReplacePrimary() throws Exception {
    ProductMetricCallbackServiceObservationSupport collector =
        spy(
            new ProductMetricCallbackServiceObservationSupport(
                () -> -10L, () -> ProductMetricPipelineLoadSupport.Stage.SETUP));
    ProductOnlineMetricsPipelineIT.writeCallbackServiceObservation(
        temporary.toRealPath(),
        ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE,
        OWNER,
        ProductMetricPipelineLoadSupport.Mode.BATCH1,
        collector.snapshot());
    JsonNode envelope =
        JSON.readTree(Files.readAllBytes(temporary.resolve("baseline-10.callback-service.json")));
    assertEquals(8, envelope.size());
    assertEquals("CALLBACK_SERVICE_TEST_SIDECAR_V1", envelope.path("scope").asText());
    assertFalse(
        envelope
            .path("observation")
            .path("qualification")
            .path("runtimeQualification")
            .asBoolean());
    assertThrows(
        java.nio.file.FileAlreadyExistsException.class,
        () ->
            ProductOnlineMetricsPipelineIT.writeCallbackServiceObservation(
                temporary.toRealPath(),
                ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE,
                OWNER,
                ProductMetricPipelineLoadSupport.Mode.BATCH1,
                collector.snapshot()));
    AssertionError primary = new AssertionError("business primary");
    StackTraceElement[] stack = primary.getStackTrace().clone();
    doThrow(new AssertionError("snapshot failure")).when(collector).snapshot();
    assertSame(
        primary,
        ProductOnlineMetricsPipelineIT.completeCallbackServiceObservationAfterClose(
            temporary, collector, OWNER, ProductMetricPipelineLoadSupport.Mode.BATCH1, primary));
    assertArrayEquals(stack, primary.getStackTrace());
    assertEquals(0, primary.getSuppressed().length);
    JsonNode unavailable =
        JSON.readTree(Files.readAllBytes(temporary.resolve("completion.callback-service.json")));
    assertEquals("UNAVAILABLE", unavailable.path("status").asText());
    assertTrue(unavailable.path("observation").isNull());
    assertEquals(8, unavailable.size());
  }

  /** Preserve a framework-created wrapper but identify the original terminal business cause. */
  private static Throwable rootCause(Throwable value) {
    while (value.getCause() != null && value.getCause() != value) value = value.getCause();
    return value;
  }

  /** Actual annotation processing and container creation remain local and unstarted. */
  @Configuration(proxyBeanMethods = false)
  @EnableRabbit
  static class RabbitInfrastructure {}

  /** Local actual service target, with only database work replaced by deterministic results. */
  static class Target extends ProductMetricService {
    /** Original business invocation count, independent of observation. */
    private int calls;

    /** Fixed original RuntimeException or Error from the controlled external Service leaf. */
    private Throwable problem;

    /** Bind the original Mapper identity without executing any database operation. */
    Target(ProductMetricMapper mapper) {
      super(mapper);
    }

    /** Return canonical per-position evidence through the original transaction proxy. */
    @Override
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.REPEATABLE_READ,
        timeout = 3)
    public ProductMetricBatchApplyResultDTO applyBatch(List<ProductMetricEventDTO> events) {
      calls++;
      if (problem instanceof RuntimeException exception) throw exception;
      if (problem instanceof Error fatal) throw fatal;
      return new ProductMetricBatchApplyResultDTO(
          java.util.stream.IntStream.range(0, events.size())
              .mapToObj(
                  index ->
                      new ProductMetricBatchEventResultDTO(
                          index,
                          events.get(index).eventId,
                          ProductMetricEvents.fingerprint(events.get(index)),
                          "APPLIED"))
              .toList(),
          events.size());
    }
  }

  /** Genuine wiring and registry lease lifecycle; no physical connection can be created. */
  private static final class Harness implements AutoCloseable {
    /** Real Spring context, explicitly disabled before any listener can start. */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** Real configured CCF spy proves transport startup is never invoked. */
    private final CachingConnectionFactory connection;

    /** Original production factory retaining its customizer. */
    private final SimpleRabbitListenerContainerFactory factory;

    /** Original production BoundContainer initialized by the actual factory. */
    private final SimpleMessageListenerContainer container;

    /** The original listener and consumer share this actual registry. */
    private final ProductMetricBatchChannelRegistry registry;

    /** Actual Consumer executes its original consumeBatch and all finally blocks. */
    private final ProductMetricConsumer consumer;

    /** Original service proxy, not replaced when the observation is enabled. */
    private final ProductMetricService service;

    /** Local original target avoids database effects while retaining real proxy behavior. */
    private final Target target;

    /** Old call observer is the sole added Service advisor. */
    private final ProductMetricPipelineLoadSupport observer;

    /** Null is the complete new-observation off path. */
    private final ProductMetricCallbackServiceObservationSupport callback;

    /** New clock reads are separately counted only in enabled mode. */
    private final AtomicLong clockReads = new AtomicLong();

    /** Exact physical class is required by the original audited registry. */
    private final ChannelN channel = mock(ChannelN.class);

    /** Real registry also verifies this exact fixed connection class and identity. */
    private final AMQConnection physicalConnection = mock(AMQConnection.class);

    /** Unique local registration is not sent to a broker. */
    private final String tag;

    /** Only the explicitly instrumented fixture retains its returned lease spy until teardown. */
    private ProductMetricBatchChannelRegistry.Lease releaseFaultLease;

    /** Number of actual release bodies executed before the one injected failure. */
    private int realReleaseCalls;

    /** Keep ordinary identity baseline fixtures completely free of registry/lease spies. */
    private Harness(boolean enabled) throws Exception {
      this(enabled, null);
    }

    /**
     * Assemble production wiring; an optional isolated fixture instruments only the release point.
     * The spy registry is published consistently before any original factory/listener binding.
     */
    private Harness(boolean enabled, Error releaseFailure) throws Exception {
      ProductMetricMapper mapper = mock(ProductMetricMapper.class);
      DataSource dataSource = mock(DataSource.class);
      when(mapper.dataSource()).thenReturn(dataSource);
      DataSourceTransactionManager transactions = mock(DataSourceTransactionManager.class);
      when(transactions.getDataSource()).thenReturn(dataSource);
      when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
      target = new Target(mapper);
      ProxyFactory serviceFactory = new ProxyFactory(target);
      serviceFactory.setProxyTargetClass(true);
      serviceFactory.addAdvice(
          new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
      service = (ProductMetricService) serviceFactory.getProxy();
      LongSupplier sharedClock = () -> -1000L + clockReads.getAndIncrement() * 100L;
      ProductMetricApplicationTimingSupport oldTiming =
          new ProductMetricApplicationTimingSupport(
              OWNER + "-batch1",
              ProductMetricPipelineLoadSupport.Mode.BATCH1,
              enabled ? sharedClock : () -> -100L);
      ProductMetricPipelineLoadSupport[] holder = new ProductMetricPipelineLoadSupport[1];
      callback =
          enabled
              ? new ProductMetricCallbackServiceObservationSupport(
                  sharedClock, () -> holder[0].currentStage())
              : null;
      observer =
          new ProductMetricPipelineLoadSupport(
              ProductMetricPipelineLoadSupport.Mode.BATCH1, oldTiming, callback);
      holder[0] = observer;
      observer.install(service);
      com.rabbitmq.client.ConnectionFactory client = new com.rabbitmq.client.ConnectionFactory();
      client.setAutomaticRecoveryEnabled(false);
      connection = spy(new CachingConnectionFactory(client));
      connection.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
      connection.setPublisherReturns(true);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new MapPropertySource(
                  "fixture",
                  Map.of(
                      "cloudmall.product.metrics.enabled",
                      "true",
                      "cloudmall.product.metrics.batch.enabled",
                      "true")));
      ProductMetricBatchChannelRegistry instrumentedRegistry =
          releaseFailure == null ? null : spy(new ProductMetricBatchChannelRegistry());
      context
          .getBeanFactory()
          .addBeanPostProcessor(
              new BeanPostProcessor() {
                /**
                 * Suppress only external startup, retaining all original factory initialization.
                 */
                @Override
                public Object postProcessBeforeInitialization(Object bean, String name) {
                  if (instrumentedRegistry != null
                      && bean instanceof ProductMetricMessagingConfiguration)
                    ReflectionTestUtils.setField(bean, "batchChannels", instrumentedRegistry);
                  if (bean instanceof SimpleRabbitListenerContainerFactory currentFactory)
                    currentFactory.setAutoStartup(false);
                  return bean;
                }

                /**
                 * Install before factory endpoint/container creation, exactly like the test entry
                 * seam.
                 */
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                  if (callback != null
                      && bean instanceof SimpleRabbitListenerContainerFactory currentFactory
                      && "productMetricContainerFactory".equals(name))
                    callback.installFactory(currentFactory);
                  return bean;
                }
              });
      context.registerBean("connectionFactory", CachingConnectionFactory.class, () -> connection);
      context.registerBean(ProductMetricMapper.class, () -> mapper);
      context.registerBean(DataSourceTransactionManager.class, () -> transactions);
      context.registerBean(ProductMetricService.class, () -> service);
      context.registerBean(
          ProductIndexDocumentLoader.class, () -> mock(ProductIndexDocumentLoader.class));
      context.registerBean(
          ProductMetricOutboxDispatcher.class, () -> mock(ProductMetricOutboxDispatcher.class));
      context.registerBean(
          ProductMetricConsumer.class,
          () ->
              spy(
                  new ProductMetricConsumer(
                      service,
                      context.getBean(ProductMetricOutboxDispatcher.class),
                      context.getBean(RabbitListenerEndpointRegistry.class))),
          definition -> definition.setDestroyMethodName(""));
      context.register(RabbitInfrastructure.class, ProductMetricMessagingConfiguration.class);
      context.refresh();
      factory =
          context.getBean(
              "productMetricContainerFactory", SimpleRabbitListenerContainerFactory.class);
      container =
          (SimpleMessageListenerContainer)
              context
                  .getBean(RabbitListenerEndpointRegistry.class)
                  .getListenerContainer("productMetrics");
      container.initialize();
      consumer = context.getBean(ProductMetricConsumer.class);
      registry = context.getBean(ProductMetricBatchChannelRegistry.class);
      when(channel.isOpen()).thenReturn(true);
      when(channel.getConnection()).thenReturn(physicalConnection);
      when(channel.getChannelNumber()).thenReturn(7);
      when(physicalConnection.isOpen()).thenReturn(true);
      BlockingQueueConsumer source = mock(BlockingQueueConsumer.class);
      when(source.getChannel()).thenReturn(channel);
      tag = registry.registerBeforeConsume(source, ProductMetricConsumer.QUEUE, channel);
      if (releaseFailure != null) {
        assertSame(instrumentedRegistry, registry);
        doAnswer(
                acquisition -> {
                  ProductMetricBatchChannelRegistry.Lease actualLease =
                      (ProductMetricBatchChannelRegistry.Lease) acquisition.callRealMethod();
                  releaseFaultLease = spy(actualLease);
                  doAnswer(
                          release -> {
                            release.callRealMethod();
                            realReleaseCalls++;
                            throw releaseFailure;
                          })
                      .when(releaseFaultLease)
                      .release();
                  return releaseFaultLease;
                })
            .when(registry)
            .acquire(anyString(), same(channel), anyList());
      }
    }

    /** Create one local canonical raw delivery; the original registry owns its tag. */
    private List<Message> messages() {
      MessageProperties properties = new MessageProperties();
      properties.setConsumerQueue(ProductMetricConsumer.QUEUE);
      properties.setConsumerTag(tag);
      properties.setDeliveryTag(1L);
      ProductMetricEventDTO event =
          ProductMetricEvents.product(UUID.randomUUID().toString(), 41L, false, null);
      return List.of(new Message(ProductMetricEvents.bytes(event), properties));
    }

    /** Invoke the actual initialized framework delegate; no listener start or network operation. */
    private void invoke(List<Message> messages) throws Throwable {
      Method method =
          AbstractMessageListenerContainer.class.getDeclaredMethod(
              "invokeListener", Channel.class, Object.class);
      method.setAccessible(true);
      try {
        method.invoke(container, channel, messages);
      } catch (InvocationTargetException wrapper) {
        throw wrapper.getCause();
      }
    }

    /**
     * Actual registry state must show release; a manually declared success flag is insufficient.
     */
    private void assertLeaseReleased() {
      Map<?, ?> bindings = (Map<?, ?>) ReflectionTestUtils.getField(registry, "bindings");
      assertEquals(1, bindings.size());
      assertEquals(0, ReflectionTestUtils.getField(bindings.values().iterator().next(), "leases"));
    }

    /**
     * Mark controlled physical leaves closed before local context teardown; never open a broker.
     */
    @Override
    public void close() {
      when(channel.isOpen()).thenReturn(false);
      when(physicalConnection.isOpen()).thenReturn(false);
      assertLeaseReleased();
      registry.assertQuiescentAndChannelsClosed();
      context.close();
      verify(connection, never()).createConnection();
    }
  }
}
