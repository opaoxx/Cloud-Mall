package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.config.ProductMetricBatchContainerFactory;
import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.service.*;
import com.cloudmall.product.service.impl.metrics.ProductMetricBatchListener;
import com.cloudmall.product.service.impl.metrics.ProductMetricConsumer;
import com.cloudmall.product.service.impl.metrics.ProductMetricOutboxDispatcher;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.service.impl.metrics.ProductMetricSingleListener;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.cloudmall.product.util.ProductMetricEvents;
import com.cloudmall.product.util.ProductMetricRawBatchingStrategy;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.amqp.core.AcknowledgeMode;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.amqp.rabbit.connection.ConnectionFactory;
import org.springframework.amqp.rabbit.listener.RabbitListenerEndpointRegistry;
import org.springframework.amqp.rabbit.listener.SimpleMessageListenerContainer;
import org.springframework.amqp.rabbit.listener.adapter.*;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.messaging.handler.invocation.InvocableHandlerMethod;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.*;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Offline original-proxy calls and temporary native files; no broker, SQL, HTTP or JMeter. */
class ProductMetricPipelineLoadSupportTest {
  /** Unique local temporary directory managed by JUnit, never an experiment namespace. */
  @TempDir Path temporary;

  /** Native test codec only, outside the observed call path. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Canonical fixed-length owner used only below the temporary module directory. */
  private static final String OWNER = "0123456789abcdef0123456789abcdef";

  @Test
  void oldConstructorsKeepNewCollectorAbsentAndOriginalStagesUnchanged() throws Exception {
    Target target = new Target();
    ProductMetricService service = proxy(target);
    ProductMetricPipelineLoadSupport observer =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.SINGLE1);
    assertNull(ReflectionTestUtils.getField(observer, "callbackSupport"));
    assertEquals(ProductMetricPipelineLoadSupport.Stage.SETUP, observer.currentStage());
    observer.install(service);
    assertSame(target.singleResult, service.apply(event()));
    assertEquals(1, target.calls);
    assertNull(observer.snapshot().get("failureCode"));
  }

  @Test
  void newHookErrorsPreserveOriginalProxyReturnThrowableAndOldCollector() throws Exception {
    for (boolean originalThrows : new boolean[] {false, true}) {
      Target target = new Target();
      ProductMetricService service = proxy(target);
      ProductMetricApplicationTimingSupport originalTiming =
          new ProductMetricApplicationTimingSupport(
              OWNER + "-batch1", ProductMetricPipelineLoadSupport.Mode.BATCH1, () -> -100L);
      ProductMetricCallbackServiceObservationSupport callback =
          spy(
              new ProductMetricCallbackServiceObservationSupport(
                  () -> -100L, () -> ProductMetricPipelineLoadSupport.Stage.SETUP));
      doThrow(new AssertionError("new enter observer failure"))
          .when(callback)
          .serviceEnter(any(), any(), anyString(), anyInt());
      doThrow(new AssertionError("new exit observer failure"))
          .when(callback)
          .serviceExit(any(), any(), anyBoolean(), any());
      ProductMetricPipelineLoadSupport observer =
          new ProductMetricPipelineLoadSupport(
              ProductMetricPipelineLoadSupport.Mode.BATCH1, originalTiming, callback);
      observer.install(service);
      List<ProductMetricEventDTO> events = List.of(event());
      target.batchResult = result(events);
      AssertionError original = new AssertionError("same original business error");
      if (originalThrows) target.problem = original;
      if (originalThrows)
        assertSame(original, assertThrows(AssertionError.class, () -> service.applyBatch(events)));
      else assertSame(target.batchResult, service.applyBatch(events));
      assertEquals(1, target.calls);
      assertNull(observer.snapshot().get("failureCode"));
      JsonNode timing =
          JSON.valueToTree(
              originalTiming.snapshot(
                  ProductMetricApplicationTimingSupport.SnapshotKind.FINISH,
                  observer.currentStage()));
      assertEquals(0, timing.path("observerState").path("activeServiceCalls").asInt());
      assertEquals(1, timing.path("observerState").path("serviceEntries").asInt());
      verify(callback, atLeastOnce()).recordObserverFailure();
    }
  }

  @Test
  void exactModesAndResourceFreeAdmissionRejectUnknownCombinations() {
    assertEquals(
        ProductMetricPipelineLoadSupport.Mode.SINGLE1,
        ProductMetricPipelineLoadSupport.Mode.parse("single1"));
    assertEquals(
        ProductMetricPipelineLoadSupport.Mode.BATCH1,
        ProductMetricPipelineLoadSupport.Mode.parse("batch1"));
    for (String mode : List.of("load", "10", "batch", "single2", "25", "50"))
      assertThrows(
          IllegalStateException.class, () -> ProductMetricPipelineLoadSupport.Mode.parse(mode));
    for (var mode : ProductMetricPipelineLoadSupport.Mode.values()) {
      boolean batch = mode == ProductMetricPipelineLoadSupport.Mode.BATCH1;
      ProductMetricPipelineLoadSupport.admit(mode, batch, 1, 300, "false", "false");
      assertThrows(
          IllegalStateException.class,
          () -> ProductMetricPipelineLoadSupport.admit(mode, !batch, 1, 300, "false", "false"));
      assertThrows(
          IllegalStateException.class,
          () -> ProductMetricPipelineLoadSupport.admit(mode, batch, 2, 300, "false", "false"));
      assertThrows(
          IllegalStateException.class,
          () -> ProductMetricPipelineLoadSupport.admit(mode, batch, 1, 8, "false", "false"));
      assertThrows(
          IllegalStateException.class,
          () -> ProductMetricPipelineLoadSupport.admit(mode, batch, 1, 300, "true", "false"));
      assertThrows(
          IllegalStateException.class,
          () -> ProductMetricPipelineLoadSupport.admit(mode, batch, 1, 300, "false", "true"));
    }
  }

  @Test
  void completionClaimsOnlyExactFreshOwnedModuleDirectory() throws Exception {
    Path module = module();
    Path completion = expected(module, ProductMetricPipelineLoadSupport.Mode.SINGLE1);
    assertThrows(
        IllegalStateException.class,
        () ->
            ProductMetricPipelineLoadSupport.Completion.claim(
                module,
                OWNER,
                ProductMetricPipelineLoadSupport.Mode.SINGLE1,
                temporary.resolve("completion.json").toString()));
    assertFalse(Files.exists(completion));
    var claim =
        ProductMetricPipelineLoadSupport.Completion.claim(
            module, OWNER, ProductMetricPipelineLoadSupport.Mode.SINGLE1, completion.toString());
    assertEquals(completion.getParent(), claim.directory());
    assertThrows(
        IOException.class,
        () ->
            ProductMetricPipelineLoadSupport.Completion.claim(
                module,
                OWNER,
                ProductMetricPipelineLoadSupport.Mode.SINGLE1,
                completion.toString()));
    assertFalse(Files.exists(completion));
  }

  @Test
  void installRetainsOneOriginalTargetAndTransactionAdvisorOrder() throws Exception {
    Target target = new Target();
    ProductMetricService proxy = proxy(target);
    Advisor[] original = ((Advised) proxy).getAdvisors();
    var observer =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.BATCH1);
    observer.install(proxy);
    assertSame(original[0], ((Advised) proxy).getAdvisors()[1]);
    assertSame(target, ((Advised) proxy).getTargetSource().getTarget());
    assertEquals(2, ((Advised) proxy).getAdvisors().length);
    assertThrows(IllegalStateException.class, () -> observer.install(proxy));
    assertFalse(
        java.util.Arrays.stream(ProductMetricPipelineLoadSupport.class.getDeclaredFields())
            .anyMatch(field -> javax.sql.DataSource.class.isAssignableFrom(field.getType())));
  }

  @Test
  void singleNormalAndOriginalThrowableEachProceedExactlyOnce() throws Exception {
    Target target = new Target();
    ProductMetricService proxy = proxy(target);
    var observer =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.SINGLE1);
    observer.install(proxy);
    String result = new String("APPLIED");
    target.singleResult = result;
    assertSame(result, proxy.apply(event()));
    RuntimeException primary = new IllegalStateException("original failure");
    target.problem = primary;
    assertSame(primary, assertThrows(RuntimeException.class, () -> proxy.apply(event())));
    AssertionError fatal = new AssertionError("original Error");
    target.problem = fatal;
    assertSame(fatal, assertThrows(AssertionError.class, () -> proxy.apply(event())));
    assertEquals(3, target.calls);
    assertEquals(1L, bucket(observer, "SETUP", "apply", 1).path("normalReturns").longValue());
    assertEquals(2L, bucket(observer, "SETUP", "apply", 1).path("originalThrows").longValue());
    assertNull(observer.snapshot().get("failureCode"));
    assertEquals(0L, observer.snapshot().get("activeCalls"));
  }

  @Test
  void actualBatchSizesAndFallbackApplyAreCountedWithoutRetainingInputs() throws Exception {
    Target target = new Target();
    ProductMetricService proxy = proxy(target);
    var observer =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.BATCH1);
    observer.install(proxy);
    for (int size = 1; size <= 4; size++) {
      List<ProductMetricEventDTO> inputs =
          java.util.stream.IntStream.range(0, size).mapToObj(index -> event()).toList();
      target.batchResult = result(inputs);
      assertSame(target.batchResult, proxy.applyBatch(inputs));
      assertEquals(
          1L, bucket(observer, "SETUP", "applyBatch", size).path("normalReturns").longValue());
    }
    RuntimeException batchFailure = new IllegalStateException("original batch failure");
    target.problem = batchFailure;
    assertSame(
        batchFailure,
        assertThrows(RuntimeException.class, () -> proxy.applyBatch(List.of(event()))));
    target.problem = null;
    proxy.apply(event());
    assertEquals(1L, bucket(observer, "SETUP", "apply", 1).path("normalReturns").longValue());
    assertEquals(6, target.calls);
    assertNull(observer.snapshot().get("failureCode"));
  }

  @Test
  void observerInvalidResultRouteAndOverflowRemainStickyWithoutChangingReturns() throws Exception {
    Target target = new Target();
    ProductMetricService proxy = proxy(target);
    var invalid =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.SINGLE1);
    invalid.install(proxy);
    target.singleResult = "UNKNOWN";
    assertSame(target.singleResult, proxy.apply(event()));
    assertNotNull(invalid.snapshot().get("failureCode"));
    Target overflowTarget = new Target();
    ProductMetricService overflowProxy = proxy(overflowTarget);
    var overflow =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.SINGLE1);
    overflow.install(overflowProxy);
    long[][][][] calls = (long[][][][]) ReflectionTestUtils.getField(overflow, "calls");
    calls[0][0][0][0] = Long.MAX_VALUE;
    assertEquals("APPLIED", overflowProxy.apply(event()));
    assertEquals(
        Long.MAX_VALUE, bucket(overflow, "SETUP", "apply", 1).path("normalReturns").longValue());
    assertEquals("COUNTER_OVERFLOW", overflow.snapshot().get("failureCode"));
    Target routeTarget = new Target();
    ProductMetricService routeProxy = proxy(routeTarget);
    var route = new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.SINGLE1);
    route.install(routeProxy);
    List<ProductMetricEventDTO> inputs = List.of(event());
    routeTarget.batchResult = result(inputs);
    assertSame(routeTarget.batchResult, routeProxy.applyBatch(inputs));
    assertEquals("UNEXPECTED_BATCH_ROUTE", route.snapshot().get("failureCode"));
    var unknown =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.BATCH1);
    MethodInvocation invocation = mock(MethodInvocation.class);
    when(invocation.getMethod())
        .thenReturn(ProductMetricService.class.getMethod("applyBatch", List.class));
    when(invocation.getArguments()).thenReturn(new Object[] {List.of()});
    Object originalReturn = new Object();
    try {
      when(invocation.proceed()).thenReturn(originalReturn);
    } catch (Throwable impossible) {
      throw new AssertionError(impossible);
    }
    assertDoesNotThrow(() -> assertSame(originalReturn, unknown.invoke(invocation)));
    assertEquals("INPUT_UNKNOWN", unknown.snapshot().get("failureCode"));
    try {
      verify(invocation, times(1)).proceed();
    } catch (Throwable impossible) {
      throw new AssertionError(impossible);
    }
  }

  @Test
  void cumulativeStageSnapshotsKeepEntryAttributionAndBoundaryCrossings() throws Exception {
    Target target = new Target();
    ProductMetricService proxy = proxy(target);
    var observer =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.SINGLE1);
    observer.install(proxy);
    proxy.apply(event());
    observer.advance(ProductMetricPipelineLoadSupport.Stage.WARMUP);
    target.during = () -> observer.advance(ProductMetricPipelineLoadSupport.Stage.BASELINE_DRAIN);
    proxy.apply(event());
    target.during = () -> {};
    observer.advance(ProductMetricPipelineLoadSupport.Stage.MEASUREMENT);
    proxy.apply(event());
    assertEquals(1L, bucket(observer, "SETUP", "apply", 1).path("normalReturns").longValue());
    assertEquals(1L, bucket(observer, "WARMUP", "apply", 1).path("normalReturns").longValue());
    JsonNode stages = JSON.valueToTree(observer.snapshot()).path("stages");
    assertEquals(1L, stages.get(1).path("crossBoundaryCalls").longValue());
    assertEquals(1L, bucket(observer, "MEASUREMENT", "apply", 1).path("normalReturns").longValue());
    assertThrows(
        IllegalStateException.class,
        () -> observer.advance(ProductMetricPipelineLoadSupport.Stage.POST_COMPARISON_DRAIN));
  }

  @Test
  void initializedBothModeAdaptersRetainActualConsumerServiceAndRegistry() throws Exception {
    for (var mode : ProductMetricPipelineLoadSupport.Mode.values()) {
      RuntimeFixture fixture = new RuntimeFixture(mode);
      Map<String, Object> runtime =
          fixture.observer.runtimeFacts(fixture.context, fixture.container);
      assertEquals(
          mode == ProductMetricPipelineLoadSupport.Mode.BATCH1 ? 4 : 1, runtime.get("prefetch"));
      assertEquals(false, runtime.get("rawChannelWrappedForObservation"));
      fixture.proxy.apply(event());
      if (mode == ProductMetricPipelineLoadSupport.Mode.BATCH1) {
        List<ProductMetricEventDTO> inputs = List.of(event());
        fixture.target.batchResult = result(inputs);
        fixture.proxy.applyBatch(inputs);
      }
      for (var stage : ProductMetricPipelineLoadSupport.Stage.values())
        if (stage != ProductMetricPipelineLoadSupport.Stage.SETUP) fixture.observer.advance(stage);
      fixture.observer.assertHealthy(true);
      assertSame(fixture.listener, fixture.container.getMessageListener());
      assertThrows(
          IllegalStateException.class,
          () -> fixture.observer.runtimeFacts(fixture.context, fixture.container));
    }
    RuntimeFixture wrong = new RuntimeFixture(ProductMetricPipelineLoadSupport.Mode.SINGLE1);
    wrong.container.setPrefetchCount(4);
    assertThrows(
        IllegalStateException.class,
        () -> wrong.observer.runtimeFacts(wrong.context, wrong.container));
  }

  @Test
  void controlsRejectWrongIdentityOrderDuplicateLevelsAndFalseAudit() throws Exception {
    var mode = ProductMetricPipelineLoadSupport.Mode.SINGLE1;
    var controls = new ProductMetricPipelineLoadSupport.Controls(OWNER, mode);
    assertThrows(
        IllegalStateException.class,
        () -> controls.accept("finish-10", control("finish-10", mode)));
    assertThrows(
        IllegalStateException.class,
        () -> controls.accept("baseline-25", control("baseline-25", mode)));
    var wrongOwner = control("baseline-10", mode);
    wrongOwner.put("owner", "other");
    assertThrows(IllegalStateException.class, () -> controls.accept("baseline-10", wrongOwner));
    controls.accept("baseline-10", control("baseline-10", mode));
    assertThrows(
        IllegalStateException.class,
        () -> controls.accept("baseline-10", control("baseline-10", mode)));
    controls.accept("finish-10", control("finish-10", mode));
    var rejected = control("stop", mode);
    rejected.put("allStageAuditsPassed", false);
    assertThrows(IllegalStateException.class, () -> controls.accept("stop", rejected));
    controls.accept("stop", control("stop", mode));
    assertThrows(IllegalStateException.class, () -> controls.accept("stop", control("stop", mode)));
    var abort = new ProductMetricPipelineLoadSupport.Controls(OWNER, mode);
    assertThrows(
        ProductMetricPipelineLoadSupport.Aborted.class,
        () -> abort.accept("abort", control("abort", mode)));
    assertThrows(
        IllegalStateException.class,
        () -> abort.accept("baseline-10", control("baseline-10", mode)));
    Files.writeString(temporary.resolve("baseline-50.control.json"), "{}");
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineLoadSupport.Controls.verifyFiles(temporary));
  }

  @Test
  void completionSeparatesNaturalAbortFailureAndPreservesPublicationErrors() throws Exception {
    Path module = module();
    var mode = ProductMetricPipelineLoadSupport.Mode.SINGLE1;
    var claim =
        ProductMetricPipelineLoadSupport.Completion.claim(
            module, OWNER, mode, expected(module, mode).toString());
    RuntimeException primary = new IllegalStateException("private original failure");
    AssertionError secondary = new AssertionError("private close failure");
    assertSame(primary, ProductMetricPipelineLoadSupport.combine(primary, secondary));
    assertSame(secondary, primary.getSuppressed()[0]);
    claim.publish(null, false, false, true, null, primary);
    JsonNode failed = JSON.readTree(Files.readAllBytes(expected(module, mode)));
    assertEquals("FAILED", failed.path("outcome").asText());
    Object fileKey =
        Files.readAttributes(
                expected(module, mode).getParent(),
                java.nio.file.attribute.BasicFileAttributes.class)
            .fileKey();
    assertEquals(
        fileKey == null ? "UNAVAILABLE" : "OBSERVED_AND_RECHECKED",
        failed.path("directoryFileKeyStatus").asText());
    assertFalse(failed.toString().contains("private original failure"));
    assertThrows(
        IllegalStateException.class, () -> claim.publish(null, true, true, false, null, null));
    var batch = ProductMetricPipelineLoadSupport.Mode.BATCH1;
    var natural =
        ProductMetricPipelineLoadSupport.Completion.claim(
            module, OWNER, batch, expected(module, batch).toString());
    natural.publish("runtime", true, true, false, null, null);
    assertEquals(
        "NATURAL_COMPLETED",
        JSON.readTree(Files.readAllBytes(expected(module, batch))).path("outcome").asText());
    Path other = Files.createDirectories(temporary.resolve("other/cloud-mall-product"));
    Files.writeString(other.resolve("pom.xml"), "unit");
    var publication =
        ProductMetricPipelineLoadSupport.Completion.claim(
            other.toRealPath(), OWNER, mode, expected(other.toRealPath(), mode).toString());
    Files.writeString(expected(other.toRealPath(), mode), "already exists");
    Throwable actual =
        assertThrows(
            IllegalStateException.class,
            () -> publication.publish(null, false, true, false, null, primary));
    assertSame(primary, ProductMetricPipelineLoadSupport.combine(primary, actual));
    assertEquals("already exists", Files.readString(expected(other.toRealPath(), mode)));
    Path third = Files.createDirectories(temporary.resolve("abort/cloud-mall-product"));
    Files.writeString(third.resolve("pom.xml"), "unit");
    var cancelled =
        ProductMetricPipelineLoadSupport.Completion.claim(
            third.toRealPath(), OWNER, mode, expected(third.toRealPath(), mode).toString());
    var controls = new ProductMetricPipelineLoadSupport.Controls(OWNER, mode);
    var abort =
        assertThrows(
            ProductMetricPipelineLoadSupport.Aborted.class,
            () -> controls.accept("abort", control("abort", mode)));
    cancelled.publish("runtime", false, true, false, null, abort);
    assertEquals(
        "ABORTED",
        JSON.readTree(Files.readAllBytes(expected(third.toRealPath(), mode)))
            .path("outcome")
            .asText());
  }

  @Test
  void singleComparisonStopUnknownNeverStartsAndSecondaryCannotMaskPrimary() {
    SimpleMessageListenerContainer container = mock(SimpleMessageListenerContainer.class);
    when(container.isRunning()).thenReturn(true);
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineLoadSupport.requireSingleStopped(container));
    verify(container, never()).start();
    when(container.isRunning()).thenReturn(false);
    when(container.getActiveConsumerCount()).thenReturn(1);
    assertThrows(
        IllegalStateException.class,
        () -> ProductMetricPipelineLoadSupport.requireSingleStopped(container));
    when(container.getActiveConsumerCount()).thenReturn(0);
    AssertionError primary = new AssertionError("original page assertion"),
        startError = new AssertionError("original start Error");
    doThrow(startError).when(container).start();
    ProductMetricPipelineLoadSupport.resumeSingle(container, primary);
    assertSame(startError, primary.getSuppressed()[0]);
    assertSame(
        startError,
        assertThrows(
            AssertionError.class,
            () -> ProductMetricPipelineLoadSupport.resumeSingle(container, null)));
    SimpleMessageListenerContainer healthy = mock(SimpleMessageListenerContainer.class);
    when(healthy.isRunning()).thenReturn(false, true);
    when(healthy.getActiveConsumerCount()).thenReturn(0, 1);
    ProductMetricPipelineLoadSupport.resumeSingle(healthy, null);
    verify(healthy, times(1)).start();
  }

  /** Create the exact standard-module leaf below a temporary real path. */
  private Path module() throws IOException {
    Path module = Files.createDirectory(temporary.resolve("cloud-mall-product"));
    Files.writeString(module.resolve("pom.xml"), "unit");
    return module.toRealPath();
  }

  /** Derive the one contract path rather than choosing a caller-controlled arbitrary base. */
  private static Path expected(Path module, ProductMetricPipelineLoadSupport.Mode mode) {
    return module
        .resolve("target/cm21-single10")
        .resolve(OWNER + "-" + mode.token())
        .resolve("completion.json");
  }

  /** A valid non-running external-process identity exercises no actual process creation. */
  private static com.fasterxml.jackson.databind.node.ObjectNode control(
      String phase, ProductMetricPipelineLoadSupport.Mode mode) {
    var node = JSON.createObjectNode();
    node.put("owner", OWNER);
    node.put("mode", mode.token());
    node.put("runIdentity", OWNER + "-" + mode.token());
    node.put("phase", phase);
    node.put("level", 10);
    node.put("jmeterPid", Long.MAX_VALUE);
    node.put("jmeterStartedAt", "2026-10-27T00:00:00Z");
    node.put("jmeterExitCode", 0);
    node.put("allStageAuditsPassed", true);
    return node;
  }

  /** Build an original transactional service proxy with a local mock transaction manager. */
  private static ProductMetricService proxy(Target target) {
    PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);
    when(transactions.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (ProductMetricService) factory.getProxy();
  }

  /** Canonical event creation is local and does not register any business event. */
  private static ProductMetricEventDTO event() {
    return ProductMetricEvents.product(UUID.randomUUID().toString(), 41L, false, null);
  }

  /** Fixed bounded successful DTO positions, without claiming independent commit evidence. */
  private static ProductMetricBatchApplyResultDTO result(List<ProductMetricEventDTO> inputs) {
    return new ProductMetricBatchApplyResultDTO(
        java.util.stream.IntStream.range(0, inputs.size())
            .mapToObj(
                index ->
                    new ProductMetricBatchEventResultDTO(
                        index, inputs.get(index).eventId, "unit", "APPLIED"))
            .toList(),
        inputs.size());
  }

  /** Locate a primitive evidence bucket without reaching into mutable observer counters. */
  private static JsonNode bucket(
      ProductMetricPipelineLoadSupport observer, String stage, String operation, int size) {
    for (JsonNode selected : JSON.valueToTree(observer.snapshot()).path("stages"))
      if (stage.equals(selected.path("stage").asText()))
        for (JsonNode bucket : selected.path("calls"))
          if (operation.equals(bucket.path("operation").asText())
              && size == bucket.path("size").intValue()) return bucket;
    throw new AssertionError("missing fixed bucket");
  }

  /** Original service target over local state; the observer does not own another target. */
  static class Target extends ProductMetricService {
    /** Exact object returned from single apply. */
    private String singleResult = "APPLIED";

    /** Exact object returned from batch apply. */
    private ProductMetricBatchApplyResultDTO batchResult;

    /** Controlled original RuntimeException or Error. */
    private Throwable problem;

    /** Optional in-call phase transition proves entry attribution. */
    private Runnable during = () -> {};

    /** Number of real target calls, independent of observer counters. */
    private int calls;

    /** Construct only a local service target with no Mapper activity. */
    Target() {
      super(null);
    }

    /** Controlled single target body retains the original transactional method attributes. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public String apply(ProductMetricEventDTO event) {
      enter();
      return singleResult;
    }

    /** Controlled batch target body retains the actual explicit RR method attributes. */
    @Override
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.REPEATABLE_READ,
        timeout = 3)
    public ProductMetricBatchApplyResultDTO applyBatch(List<ProductMetricEventDTO> events) {
      enter();
      return batchResult;
    }

    /** Original target failure remains the same object across the real proxy and observer. */
    private void enter() {
      calls++;
      during.run();
      if (problem instanceof RuntimeException exception) throw exception;
      if (problem instanceof Error fatal) throw fatal;
    }
  }

  /** Real initialized adapters and owners, without endpoint startup or network resources. */
  private static final class RuntimeFixture {
    /** Sole actual target for both installed and runtime-verified service identities. */
    private final Target target = new Target();

    /** Original transactional proxy wrapping that target. */
    private final ProductMetricService proxy = proxy(target);

    /** New SQL-free observer installed once. */
    private final ProductMetricPipelineLoadSupport observer;

    /** Real unstarted framework container exposing initialized settings. */
    private final SimpleMessageListenerContainer container =
        new SimpleMessageListenerContainer(mock(ConnectionFactory.class));

    /** Exact adapter retained by the real unstarted container. */
    private final MessagingMessageListenerAdapter listener;

    /** Local context lookup fixture, not a Spring actor. */
    private final ApplicationContext context = mock(ApplicationContext.class);

    /** Construct genuine adapter ownership and a genuine original registry without connecting. */
    private RuntimeFixture(ProductMetricPipelineLoadSupport.Mode mode) throws Exception {
      observer = new ProductMetricPipelineLoadSupport(mode);
      observer.install(proxy);
      RabbitListenerEndpointRegistry endpoints = mock(RabbitListenerEndpointRegistry.class);
      ProductMetricConsumer consumer =
          new ProductMetricConsumer(proxy, mock(ProductMetricOutboxDispatcher.class), endpoints);
      ProductMetricBatchChannelRegistry channels = new ProductMetricBatchChannelRegistry();
      container.setConcurrentConsumers(1);
      container.setMaxConcurrentConsumers(1);
      container.setPrefetchCount(mode == ProductMetricPipelineLoadSupport.Mode.BATCH1 ? 4 : 1);
      container.setAcknowledgeMode(AcknowledgeMode.MANUAL);
      if (mode == ProductMetricPipelineLoadSupport.Mode.BATCH1) {
        var bean = new ProductMetricBatchListener(consumer, channels);
        var method =
            ProductMetricBatchListener.class.getMethod("onMessages", List.class, Channel.class);
        var adapter =
            new BatchMessagingMessageListenerAdapter(
                bean, method, false, null, new ProductMetricRawBatchingStrategy());
        adapter.setHandlerAdapter(new HandlerAdapter(new InvocableHandlerMethod(bean, method)));
        listener = adapter;
        container.setBatchSize(4);
        container.setReceiveTimeout(25);
        container.setConsumerBatchEnabled(true);
        container.setForceStop(true);
        when(context.getBean(ProductMetricBatchListener.class)).thenReturn(bean);
        when(context.getBeanNamesForType(ProductMetricBatchListener.class))
            .thenReturn(new String[] {"batch"});
        when(context.getBeanNamesForType(ProductMetricSingleListener.class))
            .thenReturn(new String[0]);
        when(context.getBean("productMetricContainerFactory"))
            .thenReturn(new ProductMetricBatchContainerFactory(channels));
      } else {
        var bean = new ProductMetricSingleListener(consumer);
        var method =
            ProductMetricSingleListener.class.getMethod("onMessage", Message.class, Channel.class);
        var adapter = new MessagingMessageListenerAdapter(bean, method, false, null);
        adapter.setHandlerAdapter(new HandlerAdapter(new InvocableHandlerMethod(bean, method)));
        listener = adapter;
        when(context.getBean(ProductMetricSingleListener.class)).thenReturn(bean);
        when(context.getBeanNamesForType(ProductMetricBatchListener.class))
            .thenReturn(new String[0]);
        when(context.getBean("productMetricContainerFactory"))
            .thenReturn(new SimpleRabbitListenerContainerFactory());
      }
      container.setupMessageListener(listener);
      when(context.getBean(ProductMetricService.class)).thenReturn(proxy);
      when(context.getBean(ProductMetricConsumer.class)).thenReturn(consumer);
      when(context.getBean(ProductMetricBatchChannelRegistry.class)).thenReturn(channels);
      when(context.getBean(RabbitListenerEndpointRegistry.class)).thenReturn(endpoints);
      when(endpoints.getListenerContainer("productMetrics")).thenReturn(container);
      when(endpoints.getListenerContainerIds()).thenReturn(Set.of("productMetrics"));
    }
  }
}
