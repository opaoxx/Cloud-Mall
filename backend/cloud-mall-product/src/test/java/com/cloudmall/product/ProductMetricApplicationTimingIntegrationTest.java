package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.ProductIndexDocumentLoader;
import com.cloudmall.product.service.ProductMetricService;
import com.cloudmall.product.util.ProductMetricEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.nio.file.FileAlreadyExistsException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

/**
 * Resource-free shared-hook and cold-export seams; no Pipeline, connection or business main runs.
 */
class ProductMetricApplicationTimingIntegrationTest {
  /** Temporary native files are unrelated to any actual experiment or middleware namespace. */
  @TempDir Path temporary;

  /** Fixed synthetic owner used only in local temporary controls and constructor identities. */
  private static final String OWNER = "0123456789abcdef0123456789abcdef";

  /** Cold test codec, never called by an observed hot method. */
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void optionalAdmissionIsStrictAndDefaultOffDoesNotRequireDiagnosticResources() throws Exception {
    assertFalse(
        ProductOnlineMetricsPipelineIT.applicationTimingRequested(
            "false", false, null, 2, 8, "true", "true"));
    assertTrue(
        ProductOnlineMetricsPipelineIT.applicationTimingRequested(
            "true", true, ProductMetricPipelineLoadSupport.Mode.BATCH1, 1, 300, "false", "false"));
    for (String invalid : Arrays.asList(null, "", "TRUE", "yes", "0"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ProductOnlineMetricsPipelineIT.applicationTimingRequested(
                  invalid,
                  true,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1,
                  1,
                  300,
                  "false",
                  "false"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProductOnlineMetricsPipelineIT.applicationTimingRequested(
                "true",
                true,
                ProductMetricPipelineLoadSupport.Mode.SINGLE1,
                1,
                300,
                "false",
                "false"));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            ProductOnlineMetricsPipelineIT.applicationTimingRequested(
                "true",
                false,
                ProductMetricPipelineLoadSupport.Mode.BATCH1,
                1,
                300,
                "false",
                "false"));
    Object[][] invalidConfigurations = {
      {2, 300, "false", "false"}, {1, 8, "false", "false"},
      {1, 300, "true", "false"}, {1, 300, "false", "true"}
    };
    for (Object[] configuration : invalidConfigurations)
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ProductOnlineMetricsPipelineIT.applicationTimingRequested(
                  "true",
                  true,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1,
                  (Integer) configuration[0],
                  (Integer) configuration[1],
                  (String) configuration[2],
                  (String) configuration[3]));
    ProductMetricPipelineLoadSupport disabled =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.BATCH1);
    assertNull(
        disabled.timingSnapshot(ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE));
    Path absent = temporary.resolve("not-created");
    ProductOnlineMetricsPipelineIT.writeApplicationTiming(absent, null, null);
    assertFalse(Files.exists(absent));
  }

  @Test
  void springConstructorInjectionAndSharedHookPreserveOriginalProxyAndMapperResource()
      throws Exception {
    AtomicLong time = new AtomicLong(-1000);
    try (Fixture fixture = new Fixture(() -> time.getAndAdd(100))) {
      Advisor[] advisors = ((Advised) fixture.service).getAdvisors().clone();
      String returned = fixture.service.apply(event());
      assertSame(fixture.target.singleResult, returned);
      assertEquals(1, fixture.target.calls);
      assertArrayEquals(advisors, ((Advised) fixture.service).getAdvisors());
      fixture.timing.verifyRuntime(fixture.context, fixture.service);
      fixture.timing.assertHealthy(true);
      JsonNode snapshot =
          snapshot(fixture, ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE);
      assertTrue(snapshot.path("runtimeFacts").path("mapperInjectedIdentityMatched").asBoolean());
      assertFalse(snapshot.path("runtimeFacts").path("serviceTimingAdviceAdded").asBoolean());
      assertEquals(
          1,
          cell(snapshot, "SETUP", "apply", 1, "SERVICE_PROXY_TOTAL", "NORMAL")
              .path("calls")
              .asLong());
      assertEquals(
          1, cell(snapshot, "SETUP", "apply", 1, "ENSURE_STAT", "NORMAL").path("calls").asLong());
      // Reproduce only the call pattern with a controlled target, not a real listener fallback.
      ProductMetricEventDTO repeatedEvent = event();
      RuntimeException batchFailure = new IllegalStateException("controlled batch failure");
      StackTraceElement[] originalStack = batchFailure.getStackTrace().clone();
      Throwable[] originalSuppressed = batchFailure.getSuppressed().clone();
      fixture.target.problem = batchFailure;
      assertSame(
          batchFailure,
          assertThrows(
              RuntimeException.class, () -> fixture.service.applyBatch(List.of(repeatedEvent))));
      fixture.target.problem = null;
      assertSame(fixture.target.singleResult, fixture.service.apply(repeatedEvent));
      assertSame(fixture.target.singleResult, fixture.service.apply(repeatedEvent));
      assertEquals(4, fixture.target.calls);
      assertArrayEquals(originalStack, batchFailure.getStackTrace());
      assertArrayEquals(originalSuppressed, batchFailure.getSuppressed());
      assertArrayEquals(advisors, ((Advised) fixture.service).getAdvisors());
      fixture.timing.assertHealthy(true);
      JsonNode repeated =
          snapshot(fixture, ProductMetricApplicationTimingSupport.SnapshotKind.COMPLETION);
      assertEquals(
          3,
          cell(repeated, "SETUP", "apply", 1, "SERVICE_PROXY_TOTAL", "NORMAL")
              .path("calls")
              .asLong());
      assertEquals(
          1,
          cell(repeated, "SETUP", "applyBatch", 1, "SERVICE_PROXY_TOTAL", "THREW")
              .path("calls")
              .asLong());
      assertEquals(
          3, cell(repeated, "SETUP", "apply", 1, "ENSURE_STAT", "NORMAL").path("calls").asLong());
      assertEquals(
          1,
          cell(repeated, "SETUP", "applyBatch", 1, "ENSURE_STAT", "NORMAL").path("calls").asLong());
      assertEquals(4, repeated.path("observerState").path("serviceEntries").asLong());
      assertEquals(3, repeated.path("observerState").path("serviceNormalReturns").asLong());
      assertEquals(1, repeated.path("observerState").path("serviceThrows").asLong());
      assertEquals(0, repeated.path("observerState").path("activeServiceCalls").asLong());
      assertEquals(0, repeated.path("observerState").path("framesOutstanding").asLong());
      verify(fixture.dataSource, never()).getConnection();
      verify(fixture.dataSource, never()).getConnection(anyString(), anyString());
    }
  }

  @Test
  void observerClockFailureCannotReplaceOrMutateTheOriginalBusinessThrowable() throws Exception {
    AtomicInteger reads = new AtomicInteger();
    try (Fixture fixture =
        new Fixture(
            () -> {
              if (reads.incrementAndGet() == 2) throw new AssertionError("private clock failure");
              return 100;
            })) {
      fixture.target.callMapper = false;
      RuntimeException original = new IllegalStateException("original local business failure");
      fixture.target.problem = original;
      StackTraceElement[] stack = original.getStackTrace().clone();
      Throwable[] suppressed = original.getSuppressed().clone();
      assertSame(
          original, assertThrows(RuntimeException.class, () -> fixture.service.apply(event())));
      assertEquals(1, fixture.target.calls);
      assertArrayEquals(stack, original.getStackTrace());
      assertArrayEquals(suppressed, original.getSuppressed());
      assertTrue(fixture.timing.hasFailed());
      JsonNode snapshot =
          snapshot(fixture, ProductMetricApplicationTimingSupport.SnapshotKind.COMPLETION);
      assertEquals(0, snapshot.path("observerState").path("activeServiceCalls").asLong());
      assertEquals(0, snapshot.path("observerState").path("framesOutstanding").asLong());
      JsonNode old = JSON.valueToTree(fixture.observer.snapshot());
      assertEquals(
          1, old.path("stages").get(0).path("calls").get(0).path("originalThrows").asLong());
    }
  }

  @Test
  void transactionFailureAfterTargetReturnIsTheProxyThrowOutcome() throws Exception {
    AtomicLong time = new AtomicLong();
    try (Fixture fixture = new Fixture(() -> time.getAndAdd(100))) {
      RuntimeException commitFailure =
          new IllegalStateException("controlled transaction completion failure");
      doThrow(commitFailure).when(fixture.transactions).commit(any());
      assertSame(
          commitFailure,
          assertThrows(RuntimeException.class, () -> fixture.service.apply(event())));
      assertEquals(1, fixture.target.calls);
      assertFalse(fixture.timing.hasFailed());
      fixture.timing.assertHealthy(true);
      JsonNode snapshot =
          snapshot(fixture, ProductMetricApplicationTimingSupport.SnapshotKind.COMPLETION);
      assertEquals(
          1,
          cell(snapshot, "SETUP", "apply", 1, "SERVICE_PROXY_TOTAL", "THREW")
              .path("calls")
              .asLong());
      assertEquals(
          0,
          cell(snapshot, "SETUP", "apply", 1, "SERVICE_PROXY_TOTAL", "NORMAL")
              .path("calls")
              .asLong());
    }
  }

  @Test
  void sharedEntryStageAndInflightSnapshotDoNotRequireAnIndependentStageClock() throws Exception {
    AtomicLong time = new AtomicLong();
    try (Fixture fixture = new Fixture(() -> time.getAndAdd(100))) {
      fixture.observer.advance(ProductMetricPipelineLoadSupport.Stage.WARMUP);
      fixture.observer.advance(ProductMetricPipelineLoadSupport.Stage.BASELINE_DRAIN);
      fixture.observer.advance(ProductMetricPipelineLoadSupport.Stage.MEASUREMENT);
      AtomicReference<JsonNode> inflight = new AtomicReference<>();
      fixture.target.during =
          () -> {
            fixture.observer.advance(ProductMetricPipelineLoadSupport.Stage.FINISH_DRAIN);
            inflight.set(
                snapshot(
                    fixture, ProductMetricApplicationTimingSupport.SnapshotKind.MEASUREMENT_ENDED));
          };
      fixture.service.apply(event());
      assertEquals("FINISH_DRAIN", inflight.get().path("reportedStage").asText());
      assertEquals(1, inflight.get().path("observerState").path("activeServiceCalls").asLong());
      JsonNode finished =
          snapshot(fixture, ProductMetricApplicationTimingSupport.SnapshotKind.MEASUREMENT_ENDED);
      JsonNode total = cell(finished, "MEASUREMENT", "apply", 1, "SERVICE_PROXY_TOTAL", "NORMAL");
      assertEquals(1, total.path("calls").asLong());
      assertEquals(1, total.path("crossBoundaryCalls").asLong());
      assertEquals(
          1,
          cell(finished, "MEASUREMENT", "apply", 1, "ENSURE_STAT", "NORMAL")
              .path("calls")
              .asLong());
      assertEquals(0, finished.path("observerState").path("framesOutstanding").asLong());
    }
  }

  @Test
  void fourColdFilesAreUtf8BoundedAndCreateOnly() throws Exception {
    for (var kind : ProductMetricApplicationTimingSupport.SnapshotKind.values()) {
      ProductOnlineMetricsPipelineIT.writeApplicationTiming(
          temporary, kind, Map.of("snapshotKind", kind.name(), "text", "本地冷快照"));
    }
    assertTrue(Files.readString(temporary.resolve("baseline-10.timing.json")).contains("本地冷快照"));
    assertTrue(Files.exists(temporary.resolve("measurement-ended.timing.json")));
    assertTrue(Files.exists(temporary.resolve("finish-10.timing.json")));
    assertTrue(Files.exists(temporary.resolve("completion.timing.json")));
    byte[] original = Files.readAllBytes(temporary.resolve("baseline-10.timing.json"));
    assertThrows(
        FileAlreadyExistsException.class,
        () ->
            ProductOnlineMetricsPipelineIT.writeApplicationTiming(
                temporary,
                ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE,
                Map.of("snapshotKind", "BASELINE", "text", "replacement")));
    assertArrayEquals(original, Files.readAllBytes(temporary.resolve("baseline-10.timing.json")));
    Path oversize = Files.createDirectory(temporary.resolve("oversize"));
    assertThrows(
        IllegalStateException.class,
        () ->
            ProductOnlineMetricsPipelineIT.writeApplicationTiming(
                oversize,
                ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE,
                Map.of("snapshotKind", "BASELINE", "text", "a".repeat(1048576))));
    assertFalse(Files.exists(oversize.resolve("baseline-10.timing.json")));
    Path notDirectory = temporary.resolve("not-directory");
    Files.writeString(notDirectory, "regular file");
    assertThrows(
        IllegalStateException.class,
        () ->
            ProductOnlineMetricsPipelineIT.writeApplicationTiming(
                notDirectory,
                ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE,
                Map.of("snapshotKind", "BASELINE")));
  }

  @Test
  void postCloseDiagnosticFailuresPreservePrimaryCloseFlagsAndOriginalCompletionAttempt()
      throws Exception {
    var timing =
        new ProductMetricApplicationTimingSupport(
            OWNER + "-batch1", ProductMetricPipelineLoadSupport.Mode.BATCH1, () -> 0);
    var observer =
        new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.BATCH1, timing);
    var controls =
        new ProductMetricPipelineLoadSupport.Controls(
            OWNER, ProductMetricPipelineLoadSupport.Mode.BATCH1);
    var abort =
        assertThrows(
            ProductMetricPipelineLoadSupport.Aborted.class,
            () ->
                controls.accept(
                    "abort",
                    JSON.valueToTree(
                        Map.of(
                            "owner",
                            OWNER,
                            "mode",
                            "batch1",
                            "runIdentity",
                            OWNER + "-batch1",
                            "phase",
                            "abort",
                            "level",
                            10))));
    IOException closeFailure = new IOException("original close failure");
    Throwable primary = ProductMetricPipelineLoadSupport.combine(abort, closeFailure);
    timing.recordObserverFailure();
    Path module = Files.createDirectory(temporary.resolve("cloud-mall-product"));
    Files.writeString(module.resolve("pom.xml"), "synthetic local module");
    Path completionPath =
        module.resolve("target/cm21-single10").resolve(OWNER + "-batch1/completion.json");
    var completion =
        ProductMetricPipelineLoadSupport.Completion.claim(
            module, OWNER, ProductMetricPipelineLoadSupport.Mode.BATCH1, completionPath.toString());
    Files.writeString(
        completion.directory().resolve("completion.timing.json"), "keep earlier candidate");
    Throwable combined =
        ProductOnlineMetricsPipelineIT.completeApplicationTimingAfterClose(
            completion.directory(), observer, timing, false, true, primary);
    assertSame(abort, combined);
    assertSame(closeFailure, combined.getSuppressed()[0]);
    assertTrue(
        Arrays.stream(combined.getSuppressed())
            .anyMatch(FileAlreadyExistsException.class::isInstance));
    completion.publish(null, false, false, true, observer, combined);
    JsonNode result = JSON.readTree(Files.readAllBytes(completionPath));
    assertEquals("ABORTED", result.path("outcome").asText());
    assertFalse(result.path("workAssertionsComplete").asBoolean());
    assertFalse(result.path("closeReturned").asBoolean());
    assertTrue(result.path("closeFailed").asBoolean());
    assertEquals(
        "keep earlier candidate",
        Files.readString(completion.directory().resolve("completion.timing.json")));
  }

  @Test
  void controlWaitKeepsExplicitAbortBeforeDiagnosticFailure() throws Exception {
    var timing =
        new ProductMetricApplicationTimingSupport(
            OWNER + "-batch1", ProductMetricPipelineLoadSupport.Mode.BATCH1, () -> 0);
    timing.recordObserverFailure();
    Files.write(
        temporary.resolve("abort.control.json"),
        JSON.writeValueAsBytes(
            Map.of(
                "owner",
                OWNER,
                "mode",
                "batch1",
                "runIdentity",
                OWNER + "-batch1",
                "phase",
                "abort",
                "level",
                10)));
    var method =
        ProductOnlineMetricsPipelineIT.class.getDeclaredMethod(
            "single10Control",
            Path.class,
            String.class,
            long.class,
            ProductMetricPipelineLoadSupport.Controls.class,
            ProductMetricApplicationTimingSupport.class);
    method.setAccessible(true);
    InvocationTargetException failure =
        assertThrows(
            InvocationTargetException.class,
            () ->
                method.invoke(
                    null,
                    temporary,
                    "baseline-10",
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                    new ProductMetricPipelineLoadSupport.Controls(
                        OWNER, ProductMetricPipelineLoadSupport.Mode.BATCH1),
                    timing));
    assertInstanceOf(ProductMetricPipelineLoadSupport.Aborted.class, failure.getCause());
    assertTrue(Files.exists(temporary.resolve("abort.accepted.json")));
    Files.delete(temporary.resolve("abort.control.json"));
    InvocationTargetException diagnostic =
        assertThrows(
            InvocationTargetException.class,
            () ->
                method.invoke(
                    null,
                    temporary,
                    "baseline-10",
                    System.nanoTime() + TimeUnit.SECONDS.toNanos(1),
                    new ProductMetricPipelineLoadSupport.Controls(
                        OWNER, ProductMetricPipelineLoadSupport.Mode.BATCH1),
                    timing));
    assertInstanceOf(IllegalStateException.class, diagnostic.getCause());
    assertFalse(diagnostic.getCause() instanceof ProductMetricPipelineLoadSupport.Aborted);
    assertEquals("CM21_TIMING_NOT_QUALIFIED", diagnostic.getCause().getMessage());
  }

  /** Return one canonical local DTO without registering any event or using network state. */
  private static ProductMetricEventDTO event() {
    return ProductMetricEvents.product(UUID.randomUUID().toString(), 41L, false, null);
  }

  /** Convert only an explicitly cold observer map for assertions. */
  private static JsonNode snapshot(
      Fixture fixture, ProductMetricApplicationTimingSupport.SnapshotKind kind) {
    return JSON.valueToTree(fixture.observer.timingSnapshot(kind));
  }

  /** Find one exact fixed cell without depending on mutable primitive-array internals. */
  private static JsonNode cell(
      JsonNode snapshot, String stage, String operation, int size, String span, String outcome) {
    for (JsonNode cell : snapshot.path("cells"))
      if (stage.equals(cell.path("stage").asText())
          && operation.equals(cell.path("operation").asText())
          && size == cell.path("inputSize").asInt()
          && span.equals(cell.path("span").asText())
          && outcome.equals(cell.path("outcome").asText())) return cell;
    throw new AssertionError("missing fixed timing cell");
  }

  /**
   * Small constructor-injected Spring graph whose transaction manager and JDBC method are local.
   */
  private static final class Fixture implements AutoCloseable {
    /** No connection may be acquired by any diagnostic installation or invocation. */
    private final DataSource dataSource = mock(DataSource.class);

    /** Original local JDBC target exposes only the same mocked DataSource. */
    private final JdbcTemplate jdbc = mock(JdbcTemplate.class);

    /** TransactionInterceptor executes, while this manager performs no database operation. */
    private final PlatformTransactionManager transactions = mock(PlatformTransactionManager.class);

    /** One duration collector is shared with the optional old observer hook. */
    private final ProductMetricApplicationTimingSupport timing;

    /** Original old call observer remains the only Service advice added by the fixture. */
    private final ProductMetricPipelineLoadSupport observer;

    /** No Boot auto-configuration, listener, server or scheduler is registered. */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** Original constructor-injected target allocated once by the Service supplier. */
    private Target target;

    /** Original transactional Service proxy retained for every test call. */
    private final ProductMetricService service;

    /** Build only the source-preserving proxy/Mapper graph without fetching a connection. */
    private Fixture(LongSupplier clock) throws Exception {
      timing =
          new ProductMetricApplicationTimingSupport(
              OWNER + "-batch1", ProductMetricPipelineLoadSupport.Mode.BATCH1, clock);
      observer =
          new ProductMetricPipelineLoadSupport(
              ProductMetricPipelineLoadSupport.Mode.BATCH1, timing);
      when(jdbc.getDataSource()).thenReturn(dataSource);
      when(jdbc.update(anyString(), anyLong(), any(), any())).thenReturn(1);
      when(transactions.getTransaction(any()))
          .thenAnswer(invocation -> new SimpleTransactionStatus());
      doThrow(new AssertionError("database connection forbidden")).when(dataSource).getConnection();
      doThrow(new AssertionError("database connection forbidden"))
          .when(dataSource)
          .getConnection(anyString(), anyString());
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "timing-unit", Map.of("cloudmall.product.metrics.enabled", "true")));
      context.registerBean(
          "timingMapperPostProcessor",
          BeanPostProcessor.class,
          () ->
              new BeanPostProcessor() {
                /**
                 * Install the original target before the Service supplier obtains its final
                 * dependency.
                 */
                @Override
                public Object postProcessAfterInitialization(Object bean, String name) {
                  return bean instanceof ProductMetricMapper ? timing.installMapper(bean) : bean;
                }
              });
      context.registerBean(DataSource.class, () -> dataSource);
      context.registerBean(JdbcTemplate.class, () -> jdbc);
      context.registerBean(
          ProductIndexDocumentLoader.class, () -> mock(ProductIndexDocumentLoader.class));
      context.registerBean(
          ProductMetricMapper.class,
          () -> new ProductMetricMapper(context.getBean(JdbcTemplate.class)));
      context.registerBean(
          ProductMetricService.class,
          () -> {
            target = new Target(context.getBean(ProductMetricMapper.class));
            ProxyFactory factory = new ProxyFactory(target);
            factory.setProxyTargetClass(true);
            factory.addAdvice(
                new TransactionInterceptor(
                    transactions, new AnnotationTransactionAttributeSource()));
            ProductMetricService original = (ProductMetricService) factory.getProxy();
            try {
              observer.install(original);
            } catch (Exception failure) {
              throw new IllegalStateException("LOCAL_ORIGINAL_PROXY_INSTALL_FAILED", failure);
            }
            return original;
          });
      context.refresh();
      service = context.getBean(ProductMetricService.class);
      timing.verifyRuntime(context, service);
    }

    /** Close only the small in-process context; it owns no business resource or process. */
    @Override
    public void close() {
      context.close();
    }
  }

  /** Controlled target retaining the production superclass's original final Mapper dependency. */
  static class Target extends ProductMetricService {
    /** Same constructor-injected proxy also held by the superclass. */
    private final ProductMetricMapper selectedMapper;

    /** Exact original normal value returned to the caller. */
    private final String singleResult = new String("APPLIED");

    /** Target invocation count is independent of observation counters. */
    private int calls;

    /** Optional original business failure used by Throwable preservation cases. */
    private RuntimeException problem;

    /** Disable Mapper work only for the clock-failure original-Throwable case. */
    private boolean callMapper = true;

    /** Optional in-call boundary action, never an independent stage supplier. */
    private Runnable during = () -> {};

    /** Retain one original target and the final dependency provided by the Spring graph. */
    Target(ProductMetricMapper mapper) {
      super(mapper);
      selectedMapper = mapper;
    }

    /** Original local body remains inside its ordinary REQUIRES_NEW transaction interceptor. */
    @Override
    @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
    public String apply(ProductMetricEventDTO event) {
      enter();
      return singleResult;
    }

    /** Preserve the production batch transaction contract without creating a database fixture. */
    @Override
    @Transactional(
        propagation = Propagation.REQUIRES_NEW,
        isolation = Isolation.REPEATABLE_READ,
        timeout = 3)
    public ProductMetricBatchApplyResultDTO applyBatch(List<ProductMetricEventDTO> events) {
      enter();
      return new ProductMetricBatchApplyResultDTO(
          java.util.stream.IntStream.range(0, events.size())
              .mapToObj(
                  index ->
                      new ProductMetricBatchEventResultDTO(
                          index, events.get(index).eventId, "unit", "APPLIED"))
              .toList(),
          events.size());
    }

    /** Invoke one actual Mapper semantic method whose JDBC update is a non-connecting mock. */
    private void enter() {
      calls++;
      during.run();
      if (callMapper) selectedMapper.ensureStat(41L, LocalDateTime.of(2026, 10, 28, 0, 0));
      if (problem != null) throw problem;
    }
  }
}
