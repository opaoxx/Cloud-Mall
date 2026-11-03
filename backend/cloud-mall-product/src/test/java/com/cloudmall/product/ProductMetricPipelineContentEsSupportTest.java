package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.service.ProductMetricConsumer;
import com.cloudmall.product.service.ProductMetricOutboxDispatcher;
import com.cloudmall.product.service.ProductMetricService;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.rabbitmq.client.impl.AMQConnection;
import com.rabbitmq.client.impl.ChannelN;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.listener.BlockingQueueConsumer;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.test.util.ReflectionTestUtils;

/** Original registry/consumer control flow over local mocks; no HTTP, broker, SQL or ES runs. */
class ProductMetricPipelineContentEsSupportTest {
  @Test
  void strictRoleAdmissionKeepsLegacy() {
    ProductMetricPipelineBatchSupport.admit(true, "dual-version", 1, "false");
    ProductMetricPipelineBatchSupport.admit(false, "dual-version", 2, "true");
    for (String role : List.of("content", "unknown", "load"))
      assertThrows(
          IllegalStateException.class,
          () -> ProductMetricPipelineBatchSupport.admit(true, role, 1, "false"));
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineBatchSupport.admit(true, "dual-version", 2, "false"));
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineBatchSupport.admit(true, "dual-version", 1, "true"));
    assertThrows(
        IllegalArgumentException.class,
        () -> Cm21PhaseAnchorBridge.requireDisabledProperty("true"));
    assertEquals(
        64,
        ReflectionTestUtils.getField(ProductMetricPipelineBatchSupport.class, "MAX_OBSERVATIONS"));
    assertEquals(
        256,
        ReflectionTestUtils.getField(ProductMetricPipelineBatchSupport.class, "MAX_POSITIONS"));
  }

  @Test
  void restartAttemptsCountAllOutcomesBeforeActions() {
    List<Map<String, Object>> records = new ArrayList<>();
    var support =
        new ProductMetricPipelineContentEsSupport((name, value) -> records.add(cast(value)));
    for (int ordinal = 1; ordinal <= 16; ordinal++) {
      var token = support.claimRestartSearch();
      if (ordinal == 1) registered(token, List.of(41L, 42L));
      else if (ordinal == 2) token.http503();
      else if (ordinal == 16) token.unknown();
      else token.timeout();
      token.finish();
      assertEquals(ordinal, token.facts().get("attemptOrdinal"));
    }
    assertEquals(16, records.size());
    assertEquals("REGISTERED_200", records.get(0).get("outcome"));
    assertEquals(2, records.get(0).get("actualRegisteredDistinct"));
    for (int index = 1; index < records.size(); index++) {
      assertFalse(records.get(index).containsKey("actualRegisteredDistinct"));
      assertFalse(records.get(index).containsKey("eventIds"));
    }
    assertEquals("UNKNOWN", records.get(15).get("httpStatus"));
    assertEquals("RESTART_SEARCH_UNKNOWN", support.failureCode());
    assertThrows(IllegalStateException.class, support::successfulFacts);
  }

  @Test
  void seventeenthAttemptCannotInvokeHttp() {
    var support = new ProductMetricPipelineContentEsSupport((name, value) -> {});
    AtomicInteger actions = new AtomicInteger();
    for (int ordinal = 0; ordinal < 16; ordinal++) {
      var token = support.claimRestartSearch();
      actions.incrementAndGet();
      token.timeout();
      token.finish();
    }
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> {
              support.claimRestartSearch();
              actions.incrementAndGet();
            });
    assertEquals("CM21_CONTENT_ES_RESTART_SEARCH_QUOTA_EXCEEDED", failure.getMessage());
    assertEquals(16, actions.get());
    assertThrows(IllegalStateException.class, support::successfulFacts);
  }

  @Test
  void failedRegistrationAndWriterNeverBecomeZeroOrSuccess() {
    var invalid = new ProductMetricPipelineContentEsSupport((name, value) -> {});
    var token = invalid.claimRestartSearch();
    assertThrows(
        IllegalStateException.class,
        () -> token.registered200(UUID.randomUUID().toString(), List.of(41L), List.of("wrong")));
    token.finish();
    assertEquals("UNKNOWN", token.facts().get("outcome"));
    assertFalse(token.facts().containsKey("actualRegisteredDistinct"));
    var writer =
        new ProductMetricPipelineContentEsSupport(
            (name, value) -> {
              throw new IOException("private native path");
            });
    writer.claimRestartSearch().http503();
    assertEquals("NATIVE_WRITE_FAILED", writer.failureCode());
    assertThrows(IllegalStateException.class, writer::successfulFacts);
    var unfinished = new ProductMetricPipelineContentEsSupport((name, value) -> {});
    unfinished.claimRestartSearch().finish();
    assertEquals("RESTART_SEARCH_UNKNOWN", unfinished.failureCode());
    Fixture no503 = new Fixture((name, value) -> {});
    for (int comparison = 0; comparison < 3; comparison++) no503.compare();
    no503.support.claimRestartSearch().timeout();
    assertThrows(IllegalStateException.class, no503.support::successfulFacts);
    assertEquals("REAL_503_NOT_OBSERVED", no503.support.failureCode());
    String rendered = writer.failureCode();
    assertFalse(rendered.contains("private native path"));
    for (String outcome : List.of("registered", "503", "timeout", "unknown", "finish")) {
      AssertionError fatal = new AssertionError("native writer Error");
      var fatalWriter =
          new ProductMetricPipelineContentEsSupport(
              (name, value) -> {
                throw fatal;
              });
      var fatalToken = fatalWriter.claimRestartSearch();
      assertSame(
          fatal,
          assertThrows(
              AssertionError.class,
              () -> {
                switch (outcome) {
                  case "registered" -> registered(fatalToken, List.of(41L));
                  case "503" -> fatalToken.http503();
                  case "timeout" -> fatalToken.timeout();
                  case "unknown" -> fatalToken.unknown();
                  case "finish" -> fatalToken.finish();
                  default -> fail(outcome);
                }
              }));
      assertEquals("NATIVE_WRITE_FAILED", fatalWriter.failureCode());
      fatalToken.finish();
    }
    for (boolean completeInFinally : List.of(false, true)) {
      AssertionError secondary = new AssertionError("unknown evidence Error");
      var secondaryWriter =
          new ProductMetricPipelineContentEsSupport(
              (name, value) -> {
                throw secondary;
              });
      var secondaryToken = secondaryWriter.claimRestartSearch();
      IOException primary = new IOException("original HTTP failure");
      if (completeInFinally) secondaryToken.finish(primary);
      else secondaryToken.unknown(primary);
      secondaryToken.finish(primary);
      assertEquals(1, primary.getSuppressed().length);
      assertSame(secondary, primary.getSuppressed()[0]);
      assertEquals("UNKNOWN", secondaryToken.facts().get("outcome"));
      assertFalse(secondaryToken.facts().containsKey("actualRegisteredDistinct"));
    }
  }

  @Test
  void eachPauseGateFailureForbidsResume() throws Exception {
    for (String condition :
        List.of("running", "workers", "batch", "physical", "registry", "safety", "owner")) {
      Fixture fixture = new Fixture((name, value) -> {});
      switch (condition) {
        case "running" -> fixture.running.set(true);
        case "workers" -> fixture.workers.set(1);
        case "batch" -> ReflectionTestUtils.setField(fixture.consumer, "activeBatch", 1);
        case "physical" -> fixture.current.open.set(true);
        case "registry" ->
            ReflectionTestUtils.setField(
                fixture.consumer, "batchRegistry", new ProductMetricBatchChannelRegistry());
        case "safety" ->
            ReflectionTestUtils.setField(
                fixture.consumer, "batchSafetyFailure", new IllegalStateException("unsafe"));
        case "owner" ->
            when(fixture.endpoints.getListenerContainer("productMetrics"))
                .thenReturn(mock(SimpleMessageListenerContainer.class));
        default -> fail(condition);
      }
      assertThrows(RuntimeException.class, fixture::pause);
      verify(fixture.container, never()).start();
      verify(fixture.physical(), never()).close();
      assertEquals(1, fixture.bindingCount());
      assertNotNull(fixture.support.failureCode());
    }
    for (String changed : List.of("container", "consumer", "registry")) {
      Fixture fixture = new Fixture((name, value) -> {});
      fixture.pause();
      assertThrows(
          IllegalStateException.class,
          () ->
              fixture.support.pauseAfterStop(
                  "container".equals(changed)
                      ? mock(SimpleMessageListenerContainer.class)
                      : fixture.container,
                  "consumer".equals(changed)
                      ? new ProductMetricConsumer(
                          mock(ProductMetricService.class),
                          mock(ProductMetricOutboxDispatcher.class),
                          fixture.endpoints)
                      : fixture.consumer,
                  "registry".equals(changed)
                      ? new ProductMetricBatchChannelRegistry()
                      : fixture.registry));
      verify(fixture.container, never()).start();
      verify(fixture.physical(), never()).close();
    }
  }

  @Test
  void sameContainerResumesOnceAndAcceptsConnectionReuse() throws Exception {
    Fixture fixture = new Fixture((name, value) -> {});
    List<ChannelN> channels = new ArrayList<>();
    for (int comparison = 0; comparison < 3; comparison++) {
      channels.add(fixture.physical());
      fixture.compare();
      assertSame(fixture.connection, fixture.physical().getConnection());
      assertEquals(1, fixture.physical().getChannelNumber());
      assertEquals(1, fixture.bindingCount());
    }
    registered(fixture.support.claimRestartSearch(), List.of(41L, 42L));
    var timeout = fixture.support.claimRestartSearch();
    timeout.timeout();
    fixture.support.claimRestartSearch().http503();
    Map<String, Object> facts = fixture.support.successfulFacts();
    assertEquals(3, ((List<?>) facts.get("pauses")).size());
    assertEquals(1L, facts.get("HTTP_503"));
    assertEquals(1L, facts.get("TIMEOUT"));
    assertEquals(2, facts.get("confirmed200RegisteredDistinctSubtotal"));
    assertFalse(facts.containsKey("actualRegisteredDistinct"));
    assertEquals(true, facts.get("unobservedRegistrationPossible"));
    assertFalse(timeout.facts().containsKey("actualRegisteredDistinct"));
    verify(fixture.container, times(3)).start();
    for (ChannelN channel : channels) verify(channel, never()).close();
    Fixture repeated = new Fixture((name, value) -> {});
    var pause = repeated.pause();
    pause.resume(null);
    assertThrows(IllegalStateException.class, () -> pause.resume(null));
    verify(repeated.container, times(1)).start();
  }

  @Test
  void onlyClosedUnleasedBindingCanBePrunedByOriginalRegistration() {
    AMQConnection connection = mock(AMQConnection.class);
    when(connection.isOpen()).thenReturn(true);
    ProductMetricBatchChannelRegistry registry = new ProductMetricBatchChannelRegistry();
    Raw first = new Raw(connection);
    String tag =
        registry.registerBeforeConsume(first.bqc, ProductMetricConsumer.QUEUE, first.channel);
    var lease = registry.acquire(tag, first.channel, List.of(1L));
    first.open.set(false);
    Raw second = new Raw(connection);
    registry.registerBeforeConsume(second.bqc, ProductMetricConsumer.QUEUE, second.channel);
    assertEquals(2, tableSize(registry));
    Raw third = new Raw(connection);
    assertThrows(
        IllegalStateException.class,
        () ->
            registry.registerBeforeConsume(third.bqc, ProductMetricConsumer.QUEUE, third.channel));
    lease.release();
    assertEquals(2, tableSize(registry));
    registry.registerBeforeConsume(third.bqc, ProductMetricConsumer.QUEUE, third.channel);
    assertEquals(2, tableSize(registry));
    assertFalse(first.open.get());
    assertTrue(second.open.get());
  }

  @Test
  void originalFailureSurvivesResumeEvidenceFailure() {
    Fixture fixture =
        new Fixture(
            (name, value) -> {
              if (name.endsWith("-resumed")) throw new IOException("private writer detail");
            });
    var pause = fixture.pause();
    AssertionError primary = new AssertionError("original page assertion");
    AssertionError propagated =
        assertThrows(
            AssertionError.class,
            () -> {
              try {
                throw primary;
              } finally {
                pause.resume(primary);
              }
            });
    assertSame(primary, propagated);
    assertEquals(1, primary.getSuppressed().length);
    assertEquals("NATIVE_WRITE_FAILED", fixture.support.failureCode());
    verify(fixture.container, times(1)).start();
    Fixture failedStart = new Fixture((name, value) -> {});
    var otherPause = failedStart.pause();
    RuntimeException startFailure = new IllegalStateException("original start failure");
    doThrow(startFailure).when(failedStart.container).start();
    assertSame(startFailure, assertThrows(RuntimeException.class, () -> otherPause.resume(null)));
    verify(failedStart.container, times(1)).start();
    for (boolean hasPrimary : List.of(false, true)) {
      AssertionError writerError = new AssertionError("resume evidence Error");
      Fixture fatalWriter =
          new Fixture(
              (name, value) -> {
                if (name.endsWith("-resumed")) throw writerError;
              });
      var fatalPause = fatalWriter.pause();
      if (hasPrimary) {
        RuntimeException original = new IllegalStateException("original page failure");
        fatalPause.resume(original);
        assertEquals(1, original.getSuppressed().length);
        assertSame(writerError, original.getSuppressed()[0]);
      } else
        assertSame(writerError, assertThrows(AssertionError.class, () -> fatalPause.resume(null)));
      assertEquals("NATIVE_WRITE_FAILED", fatalWriter.support.failureCode());
      verify(fatalWriter.container, times(1)).start();
    }
    AssertionError firstWriter = new AssertionError("stopped evidence Error");
    AssertionError unknownWriter = new AssertionError("unknown evidence Error");
    Fixture doubleError =
        new Fixture(
            (name, value) -> {
              if (name.endsWith("-stopped")) throw firstWriter;
              if (name.endsWith("-unknown")) throw unknownWriter;
            });
    assertSame(firstWriter, assertThrows(AssertionError.class, doubleError::pause));
    assertEquals(1, firstWriter.getSuppressed().length);
    assertSame(unknownWriter, firstWriter.getSuppressed()[0]);
    verify(doubleError.container, never()).start();
    Fixture gateError =
        new Fixture(
            (name, value) -> {
              if (name.endsWith("-unknown")) throw unknownWriter;
            });
    gateError.running.set(true);
    IllegalStateException gatePrimary = assertThrows(IllegalStateException.class, gateError::pause);
    assertEquals(1, gatePrimary.getSuppressed().length);
    assertSame(unknownWriter, gatePrimary.getSuppressed()[0]);
    verify(gateError.container, never()).start();
  }

  /** Derive registered identities only with the existing canonical production event factory. */
  private static void registered(
      ProductMetricPipelineContentEsSupport.Attempt token, List<Long> products) {
    String request = UUID.randomUUID().toString();
    token.registered200(
        request,
        products,
        products.stream()
            .map(product -> ProductMetricEvents.product(request, product, true, null).eventId)
            .toList());
  }

  /** Local map cast is limited to helper-native evidence, not an application payload. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> cast(Object value) {
    return (Map<String, Object>) value;
  }

  /** Read original source size without modifying private bindings. */
  private static int tableSize(ProductMetricBatchChannelRegistry registry) {
    synchronized (registry) {
      return ((Map<?, ?>) ReflectionTestUtils.getField(registry, "bindings")).size();
    }
  }

  /** Original source registration over exact raw channel/connection mocks. */
  private static final class Raw {
    /** Exact pinned client physical type, without network I/O. */
    private final ChannelN channel = mock(ChannelN.class);

    /** Original BQC registration owner. */
    private final BlockingQueueConsumer bqc = mock(BlockingQueueConsumer.class);

    /** Explicit liveness under fixture control; helper never closes it. */
    private final AtomicBoolean open = new AtomicBoolean(true);

    /** Construct local transports that satisfy the original registry capture contract. */
    private Raw(AMQConnection connection) {
      when(channel.isOpen()).thenAnswer(invocation -> open.get());
      when(channel.getConnection()).thenReturn(connection);
      when(channel.getChannelNumber()).thenReturn(1);
      when(bqc.getChannel()).thenReturn(channel);
    }
  }

  /** Synthetic container stop/start effects exercise only helper wiring and source registration. */
  private static final class Fixture {
    /** Shared real production source registry, never written reflectively. */
    private final ProductMetricBatchChannelRegistry registry =
        new ProductMetricBatchChannelRegistry();

    /** Same connection deliberately survives all three source registrations. */
    private final AMQConnection connection = mock(AMQConnection.class);

    /** Original endpoint owner identity, no RabbitMQ container actor is started. */
    private final SimpleMessageListenerContainer container =
        mock(SimpleMessageListenerContainer.class);

    /** Original Consumer endpoint registry. */
    private final RabbitListenerEndpointRegistry endpoints =
        mock(RabbitListenerEndpointRegistry.class);

    /** Real Consumer state gate and original registry binding. */
    private final ProductMetricConsumer consumer;

    /** Fixture helper whose only external capability is the explicit test writer. */
    private final ProductMetricPipelineContentEsSupport support;

    /** Controlled container state, not evidence of a real framework stop. */
    private final AtomicBoolean running = new AtomicBoolean(false);

    /** Controlled active-worker count for negative gating. */
    private final AtomicInteger workers = new AtomicInteger();

    /** Current genuinely source-registered raw binding over mocks. */
    private Raw current;

    /** Build local original owners and source register once; no lifecycle thread is created. */
    private Fixture(ProductMetricPipelineBatchSupport.EvidenceSink sink) {
      support = new ProductMetricPipelineContentEsSupport(sink);
      when(connection.isOpen()).thenReturn(true);
      when(endpoints.getListenerContainer("productMetrics")).thenReturn(container);
      consumer =
          new ProductMetricConsumer(
              mock(ProductMetricService.class),
              mock(ProductMetricOutboxDispatcher.class),
              endpoints);
      consumer.bindBatchRegistry(registry);
      when(container.isRunning()).thenAnswer(invocation -> running.get());
      when(container.getActiveConsumerCount()).thenAnswer(invocation -> workers.get());
      ReflectionTestUtils.setField(container, "consumersMonitor", new Object());
      current = new Raw(connection);
      registry.registerBeforeConsume(current.bqc, ProductMetricConsumer.QUEUE, current.channel);
      current.open.set(false);
      doAnswer(
              invocation -> {
                current = new Raw(connection);
                registry.registerBeforeConsume(
                    current.bqc, ProductMetricConsumer.QUEUE, current.channel);
                ReflectionTestUtils.setField(container, "consumers", Set.of(current.bqc));
                running.set(true);
                return null;
              })
          .when(container)
          .start();
    }

    /**
     * Acquire permission from the real consumer state and source registry, without mutating them.
     */
    private ProductMetricPipelineContentEsSupport.Pause pause() {
      return support.pauseAfterStop(container, consumer, registry);
    }

    /** Model only the fixture's existing stopped physical state before each comparison. */
    private void compare() {
      running.set(false);
      current.open.set(false);
      pause().resume(null);
    }

    /** Return only the current raw mock for exact invocation assertions. */
    private ChannelN physical() {
      return current.channel;
    }

    /** Read the source table under its own original monitor. */
    private int bindingCount() {
      return tableSize(registry);
    }
  }
}
