package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.domain.dto.ProductMetricInitialApplySnapshotDTO;
import com.cloudmall.product.domain.po.ProductMetricControlPO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.ProductIndexDocumentLoader;
import com.cloudmall.product.service.ProductMetricService;
import com.cloudmall.product.util.ProductMetricEvents;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.support.JdbcTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionAspectSupport;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Real local Spring transaction lifecycle with original Service code and external leaves doubled.
 */
class ProductMetricServiceTransactionObservationIntegrationTest {
  /** Synthetic identity never used to construct a middleware namespace. */
  private static final String OWNER = "0123456789abcdef0123456789abcdef";

  /** Local snapshot codec; no runtime binding or actual result is produced. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** JUnit owns only these local create-only sidecars. */
  @TempDir Path temporary;

  @Test
  void strictFlagAdmissionAndDisabledCompletionHaveNoNewResources() throws Exception {
    assertFalse(
        ProductOnlineMetricsPipelineIT.serviceTransactionObservationRequested(
            "false", false, null, 2, 8, false, false, "true", "true"));
    assertTrue(
        ProductOnlineMetricsPipelineIT.serviceTransactionObservationRequested(
            "true",
            true,
            ProductMetricPipelineLoadSupport.Mode.BATCH1,
            1,
            300,
            true,
            true,
            "false",
            "false"));
    for (String invalid : Arrays.asList(null, "", "TRUE", " true", "0"))
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ProductOnlineMetricsPipelineIT.serviceTransactionObservationRequested(
                  invalid,
                  true,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1,
                  1,
                  300,
                  true,
                  true,
                  "false",
                  "false"));
    for (int dimension = 0; dimension < 8; dimension++) {
      final int selected = dimension;
      assertThrows(
          IllegalArgumentException.class,
          () ->
              ProductOnlineMetricsPipelineIT.serviceTransactionObservationRequested(
                  "true",
                  selected != 0,
                  selected == 1
                      ? ProductMetricPipelineLoadSupport.Mode.SINGLE1
                      : ProductMetricPipelineLoadSupport.Mode.BATCH1,
                  selected == 2 ? 2 : 1,
                  selected == 3 ? 8 : 300,
                  selected != 4,
                  selected != 5,
                  selected == 6 ? "true" : "false",
                  selected == 7 ? "true" : "false"));
    }
    AssertionError primary = new AssertionError("original primary");
    assertSame(
        primary,
        ProductOnlineMetricsPipelineIT.completeServiceTransactionObservationAfterClose(
            temporary.resolve("absent"),
            null,
            OWNER,
            ProductMetricPipelineLoadSupport.Mode.BATCH1,
            primary));
    assertFalse(Files.exists(temporary.resolve("absent")));
  }

  @Test
  void actualSingle10MainRejectsNewFlagBeforeClaimAndKeepsFalseOnOriginalEarlyGuard()
      throws Exception {
    String freshOwner = java.util.UUID.randomUUID().toString().replace("-", "");
    Path claimDirectory =
        Path.of("")
            .toAbsolutePath()
            .normalize()
            .resolve("target/cm21-single10")
            .resolve(freshOwner + "-batch1");
    Map<String, String> selected = new java.util.LinkedHashMap<>();
    selected.put("cloudmall.cm21.pipeline-batch", "true");
    selected.put("cloudmall.cm21.metric-consumers", "1");
    selected.put("cloudmall.cm21.phase-timing", "false");
    selected.put("cloudmall.cm21.phase-anchor", "false");
    selected.put("cloudmall.cm21.single10.application-timing", "true");
    selected.put("cloudmall.cm21.single10.callback-service-observation", "true");
    selected.put("cloudmall.cm21.single10.service-transaction-observation", "TRUE");
    selected.put("cloudmall.cm21.owner", freshOwner);
    selected.put(
        "cloudmall.cm21.single10.completion-file",
        claimDirectory.resolve("completion.json").toString());
    Map<String, String> previous = new java.util.LinkedHashMap<>();
    selected.forEach(
        (property, value) -> {
          previous.put(property, System.getProperty(property));
          System.setProperty(property, value);
        });
    try {
      assertFalse(Files.exists(claimDirectory));
      IllegalArgumentException invalidLiteral =
          assertThrows(
              IllegalArgumentException.class,
              () -> ProductOnlineMetricsPipelineIT.Single10.main(new String[] {"batch1"}));
      assertEquals("CM21_SERVICE_TRANSACTION_BOOLEAN_REQUIRED", invalidLiteral.getMessage());
      assertFalse(Files.exists(claimDirectory));
      System.setProperty("cloudmall.cm21.single10.service-transaction-observation", "true");
      System.setProperty("cloudmall.cm21.single10.callback-service-observation", "false");
      IllegalArgumentException missingCallback =
          assertThrows(
              IllegalArgumentException.class,
              () -> ProductOnlineMetricsPipelineIT.Single10.main(new String[] {"batch1"}));
      assertEquals(
          "CM21_SERVICE_TRANSACTION_SINGLE10_BATCH1_REQUIRED", missingCallback.getMessage());
      assertFalse(Files.exists(claimDirectory));
      System.setProperty("cloudmall.cm21.single10.service-transaction-observation", "false");
      System.setProperty("cloudmall.cm21.owner", "invalid-owner-for-original-claim-guard");
      IllegalStateException originalGuard =
          assertThrows(
              IllegalStateException.class,
              () -> ProductOnlineMetricsPipelineIT.Single10.main(new String[] {"batch1"}));
      assertEquals("CM21_SINGLE10_COMPLETION_IDENTITY_INVALID", originalGuard.getMessage());
      assertFalse(Files.exists(claimDirectory));
    } finally {
      previous.forEach(
          (property, value) -> {
            if (value == null) System.clearProperty(property);
            else System.setProperty(property, value);
          });
    }
  }

  @Test
  void realServiceAndMapperProxyCommitOnceWithOneNewSynchronizationAndOriginalIdentities()
      throws Exception {
    try (Harness harness = new Harness(true, false)) {
      ProductMetricBatchApplyResultDTO result = harness.service.applyBatch(harness.events);
      assertEquals(0, result.appliedCount);
      assertEquals(2, result.events.size());
      assertEquals("DUPLICATE", result.events.get(0).outcome);
      assertEquals(2, harness.mapperCalls.get());
      assertEquals(1, harness.observedSynchronizationCount);
      verify(harness.connection).commit();
      verify(harness.connection, never()).rollback();
      harness.assertIdentities();
      harness.assertQuiescent();
      JsonNode cell = harness.cell("applyBatch", 2, "NORMAL");
      assertEquals(1, cell.path("fullLogicalCalls").asLong());
      assertEquals(1, cell.path("completionStatusCounts").path("COMMITTED").asLong());
      assertEquals(1, harness.counters().path("registrationAttempts").asLong());
      assertEquals(1, harness.counters().path("registrationsConfirmed").asLong());
      long sum = 0;
      for (JsonNode segment : cell.path("segments")) {
        assertEquals(1, segment.path("observations").asLong());
        sum = Math.addExact(sum, segment.path("sumNs").asLong());
      }
      assertEquals(cell.path("fullWholeStats").path("sumNs").asLong(), sum);
      harness.observation.assertHealthyAfterClose();
    }
  }

  @Test
  void originalApplyAnnotationAndRepeatedAttemptsKeepFreshTransactions() throws Exception {
    try (Harness harness = new Harness(true, false)) {
      assertEquals("DUPLICATE", harness.service.apply(harness.events.get(0)));
      assertEquals("DUPLICATE", harness.service.apply(harness.events.get(1)));
      verify(harness.connection, times(2)).commit();
      verify(harness.dataSource, times(2)).getConnection();
      assertEquals(2, harness.cell("apply", 1, "NORMAL").path("fullLogicalCalls").asLong());
      assertEquals(2, harness.counters().path("registrationAttempts").asLong());
      harness.assertIdentities();
      harness.assertQuiescent();
    }
  }

  @Test
  void disabledRealTransactionPathAddsNoSynchronizationOrNewClock() throws Exception {
    try (Harness harness = new Harness(false, false)) {
      assertNull(harness.observation);
      harness.service.applyBatch(harness.events);
      assertEquals(0, harness.clockReads.get());
      assertEquals(0, harness.observedSynchronizationCount);
      verify(harness.connection).commit();
      harness.assertIdentities();
      harness.assertQuiescent();
    }
  }

  @Test
  void actualTargetRuntimeExceptionAndErrorRollbackWithoutReplacingOriginalThrowable()
      throws Exception {
    for (Throwable original :
        List.of(new IllegalStateException("target runtime"), new AssertionError("target error"))) {
      try (Harness harness = new Harness(true, false)) {
        harness.mapperFailure = original;
        StackTraceElement[] stack = original.getStackTrace().clone();
        Throwable visible =
            assertThrows(Throwable.class, () -> harness.service.applyBatch(harness.events));
        assertSame(original, visible);
        assertArrayEquals(stack, visible.getStackTrace());
        assertEquals(0, visible.getSuppressed().length);
        verify(harness.connection).rollback();
        verify(harness.connection, never()).commit();
        assertEquals(1, harness.mapperCalls.get());
        JsonNode cell = harness.cell("applyBatch", 2, "THREW");
        assertEquals(1, cell.path("completionStatusCounts").path("ROLLED_BACK").asLong());
        assertEquals(0, cell.path("markerSeenCounts").path("B").asLong());
        assertEquals(0, cell.path("fullLogicalCalls").asLong());
        harness.assertQuiescent();
        assertFalse(harness.applicationTiming.hasFailed());
      }
    }
  }

  @Test
  void originalEarlyValidationAndBeginFailureHaveNoFabricatedMapperOrCompletion() throws Exception {
    try (Harness harness = new Harness(true, false)) {
      assertThrows(IllegalArgumentException.class, () -> harness.service.applyBatch(null));
      assertEquals(0, harness.mapperCalls.get());
      assertEquals(0, harness.counters().path("registrationAttempts").asLong());
      harness.assertQuiescent();
    }
    try (Harness harness = new Harness(true, false)) {
      SQLException original = new SQLException("controlled begin leaf");
      when(harness.dataSource.getConnection()).thenThrow(original);
      Throwable visible =
          assertThrows(Throwable.class, () -> harness.service.applyBatch(harness.events));
      assertSame(original, visible.getCause());
      assertEquals(0, harness.mapperCalls.get());
      assertEquals(0, harness.counters().path("registrationAttempts").asLong());
      assertEquals(
          1,
          harness
              .cell("applyBatch", 2, "THREW")
              .path("completionStatusCounts")
              .path("NOT_OBSERVED")
              .asLong());
      harness.assertQuiescent();
    }
  }

  @Test
  void realCommitFailurePreservesSpringTranslationAndConfiguredRollbackBranch() throws Exception {
    for (boolean rollbackOnCommitFailure : List.of(false, true)) {
      try (Harness harness = new Harness(true, false, false, rollbackOnCommitFailure)) {
        SQLException original = new SQLException("controlled commit leaf");
        doThrow(original).when(harness.connection).commit();
        TransactionSystemException visible =
            assertThrows(
                TransactionSystemException.class, () -> harness.service.applyBatch(harness.events));
        assertSame(original, visible.getCause());
        verify(harness.connection).commit();
        verify(harness.connection, times(rollbackOnCommitFailure ? 1 : 0)).rollback();
        JsonNode cell = harness.cell("applyBatch", 2, "THREW");
        assertEquals(0, cell.path("markerSeenCounts").path("A").asLong());
        assertEquals(
            1,
            cell.path("completionStatusCounts")
                .path(rollbackOnCommitFailure ? "ROLLED_BACK" : "UNKNOWN")
                .asLong());
        harness.assertQuiescent();
      }
    }
  }

  @Test
  void rollbackFailureKeepsOriginalSpringFinalFailureAndApplicationException() throws Exception {
    try (Harness harness = new Harness(true, false)) {
      AssertionError applicationFailure = new AssertionError("application before rollback failure");
      SQLException rollbackFailure = new SQLException("controlled rollback leaf");
      harness.mapperFailure = applicationFailure;
      doThrow(rollbackFailure).when(harness.connection).rollback();
      TransactionSystemException visible =
          assertThrows(
              TransactionSystemException.class, () -> harness.service.applyBatch(harness.events));
      assertSame(rollbackFailure, visible.getCause());
      assertSame(applicationFailure, visible.getApplicationException());
      verify(harness.connection).rollback();
      verify(harness.connection, never()).commit();
      assertEquals(
          1,
          harness
              .cell("applyBatch", 2, "THREW")
              .path("completionStatusCounts")
              .path("UNKNOWN")
              .asLong());
      harness.assertQuiescent();
    }
  }

  @Test
  void originalJdbcTransactionManagerSubclassRetainsItsConfiguredExceptionTranslation()
      throws Exception {
    try (Harness harness = new Harness(true, true)) {
      SQLException original = new SQLException("configured JdbcTransactionManager commit leaf");
      DataAccessResourceFailureException translated = harness.jdbcTranslation;
      doThrow(original).when(harness.connection).commit();
      assertSame(
          translated,
          assertThrows(
              DataAccessResourceFailureException.class,
              () -> harness.service.applyBatch(harness.events)));
      assertEquals(JdbcTransactionManager.class, harness.manager.getClass());
      harness.assertIdentities();
      harness.assertQuiescent();
    }
  }

  @Test
  void priorSynchronizationFailuresKeepMissingMarkersStatusAndBusinessOutcomeSeparate()
      throws Exception {
    for (String boundary : List.of("BEFORE_COMMIT", "AFTER_COMMIT", "AFTER_COMPLETION")) {
      try (Harness harness = new Harness(true, false)) {
        IllegalStateException original = new IllegalStateException("original prior " + boundary);
        harness.extraSynchronization = synchronization(boundary, original, Integer.MIN_VALUE);
        if ("AFTER_COMPLETION".equals(boundary)) {
          harness.service.applyBatch(harness.events);
        } else {
          assertSame(
              original,
              assertThrows(
                  IllegalStateException.class, () -> harness.service.applyBatch(harness.events)));
        }
        JsonNode cell =
            harness.cell("applyBatch", 2, "AFTER_COMPLETION".equals(boundary) ? "NORMAL" : "THREW");
        assertEquals(
            1,
            cell.path("completionStatusCounts")
                .path("BEFORE_COMMIT".equals(boundary) ? "ROLLED_BACK" : "COMMITTED")
                .asLong());
        assertEquals(
            "AFTER_COMPLETION".equals(boundary) ? 1 : 0,
            cell.path("markerSeenCounts").path("A").asInt());
        assertEquals(
            "AFTER_COMPLETION".equals(boundary) ? 1 : 0, cell.path("fullLogicalCalls").asInt());
        harness.assertQuiescent();
        assertEquals(
            "NONE", harness.snapshot().path("collectorState").path("failureCode").asText());
      }
    }
  }

  @Test
  void equalOrderLaterAfterCommitFailureCanHaveFullLogicalCoverageWithCommittedAndThrew()
      throws Exception {
    try (Harness harness = new Harness(true, false)) {
      IllegalStateException original = new IllegalStateException("later equal order afterCommit");
      harness.extraSynchronization = synchronization("AFTER_COMMIT", original, Integer.MAX_VALUE);
      assertSame(
          original,
          assertThrows(
              IllegalStateException.class, () -> harness.service.applyBatch(harness.events)));
      JsonNode cell = harness.cell("applyBatch", 2, "THREW");
      assertEquals(1, cell.path("fullLogicalCalls").asLong());
      assertEquals(1, cell.path("completionStatusCounts").path("COMMITTED").asLong());
      assertEquals(1, cell.path("markerSeenCounts").path("A").asLong());
      verify(harness.connection).commit();
      harness.assertQuiescent();
    }
  }

  @Test
  void realLocalRollbackOnlyIsNotInferredFromNormalServiceReturn() throws Exception {
    try (Harness harness = new Harness(true, false)) {
      harness.markRollbackOnly = true;
      assertEquals(0, harness.service.applyBatch(harness.events).appliedCount);
      JsonNode cell = harness.cell("applyBatch", 2, "NORMAL");
      assertEquals(1, cell.path("completionStatusCounts").path("ROLLED_BACK").asLong());
      assertEquals(0, cell.path("fullLogicalCalls").asLong());
      verify(harness.connection).rollback();
      verify(harness.connection, never()).commit();
      harness.assertQuiescent();
    }
  }

  @Test
  void originalParentTransactionSuspendsAndResumesWhileNewDiagnosticsSkipUnknownOuterState()
      throws Exception {
    try (Harness harness = new Harness(true, false)) {
      Connection parentConnection = connectionLeaf();
      when(harness.dataSource.getConnection()).thenReturn(parentConnection, harness.connection);
      new TransactionTemplate(harness.manager)
          .execute(
              status -> {
                Object originalHolder =
                    TransactionSynchronizationManager.getResource(harness.dataSource);
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                harness.service.applyBatch(harness.events);
                assertSame(
                    originalHolder,
                    TransactionSynchronizationManager.getResource(harness.dataSource));
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return null;
              });
      verify(parentConnection).commit();
      verify(harness.connection).commit();
      assertEquals(1, harness.counters().path("skippedOuterTransaction").asLong());
      assertEquals(0, harness.counters().path("registrationAttempts").asLong());
      harness.assertQuiescent();
    }
  }

  @Test
  void eachNewWiringHookFaultPreservesRealTargetErrorAndLegacyTimingHealth() throws Exception {
    for (String boundary : List.of("ENTER", "MAPPER", "EXIT")) {
      try (Harness harness = new Harness(true, false, true)) {
        AssertionError diagnostic = new AssertionError("controlled new " + boundary);
        if ("ENTER".equals(boundary))
          doThrow(diagnostic)
              .when(harness.observation)
              .serviceEnter(any(), any(), any(), anyString(), anyInt());
        else if ("MAPPER".equals(boundary))
          doThrow(diagnostic).when(harness.observation).mapperEnter(any(), anyString());
        else
          doThrow(diagnostic)
              .when(harness.observation)
              .serviceExit(any(), any(), anyBoolean(), any());
        AssertionError original = new AssertionError("real target error");
        harness.mapperFailure = original;
        assertSame(
            original,
            assertThrows(AssertionError.class, () -> harness.service.applyBatch(harness.events)));
        assertEquals(1, harness.mapperCalls.get());
        assertFalse(harness.applicationTiming.hasFailed());
        verify(harness.connection).rollback();
      }
    }
  }

  @Test
  void syntheticDirectAdviceSeamPreservesResultAndThrowableReferenceWithFaultingDiagnostics()
      throws Throwable {
    LongSupplier clock = () -> -100L;
    ProductMetricServiceTransactionObservationSupport observation =
        mock(ProductMetricServiceTransactionObservationSupport.class);
    doThrow(new AssertionError("observer entry"))
        .when(observation)
        .serviceEnter(any(), any(), any(), anyString(), anyInt());
    doThrow(new AssertionError("observer failure recorder"))
        .when(observation)
        .recordObserverFailure();
    ProductMetricApplicationTimingSupport timing =
        new ProductMetricApplicationTimingSupport(
            OWNER + "-batch1", ProductMetricPipelineLoadSupport.Mode.BATCH1, clock, observation);
    ProductMetricPipelineLoadSupport load =
        new ProductMetricPipelineLoadSupport(
            ProductMetricPipelineLoadSupport.Mode.BATCH1, timing, null, observation);
    ProductMetricEventDTO event =
        ProductMetricEvents.product("11111111-1111-4111-8111-111111111111", 41L, false, null);
    ProductMetricBatchApplyResultDTO originalResult =
        new ProductMetricBatchApplyResultDTO(
            List.of(
                new com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO(
                    0, event.eventId, ProductMetricEvents.fingerprint(event), "DUPLICATE")),
            0);
    AtomicInteger proceeded = new AtomicInteger();
    assertSame(
        originalResult, load.invoke(invocation(List.of(event), originalResult, null, proceeded)));
    AssertionError original = new AssertionError("direct original error");
    StackTraceElement[] stack = original.getStackTrace().clone();
    assertSame(
        original,
        assertThrows(
            AssertionError.class,
            () -> load.invoke(invocation(List.of(event), null, original, proceeded))));
    assertEquals(2, proceeded.get());
    assertArrayEquals(stack, original.getStackTrace());
    assertEquals(0, original.getSuppressed().length);
    assertFalse(timing.hasFailed());
  }

  @Test
  void independentFourColdSidecarsAreCreateOnlyAndSnapshotFailureCannotChangePrimary()
      throws Exception {
    try (Harness harness = new Harness(true, false)) {
      harness.service.applyBatch(harness.events);
      Path directory = temporary.toRealPath();
      for (ProductMetricApplicationTimingSupport.SnapshotKind kind :
          ProductMetricApplicationTimingSupport.SnapshotKind.values())
        ProductOnlineMetricsPipelineIT.writeServiceTransactionObservation(
            directory,
            kind,
            OWNER,
            ProductMetricPipelineLoadSupport.Mode.BATCH1,
            harness.observation.snapshot());
      for (String file : List.of("baseline-10", "measurement-ended", "finish-10", "completion")) {
        JsonNode envelope =
            JSON.readTree(
                Files.readAllBytes(directory.resolve(file + ".service-transaction.json")));
        assertEquals(8, envelope.size());
        assertEquals("AVAILABLE", envelope.path("status").asText());
      }
      assertThrows(
          java.nio.file.FileAlreadyExistsException.class,
          () ->
              ProductOnlineMetricsPipelineIT.writeServiceTransactionObservation(
                  directory,
                  ProductMetricApplicationTimingSupport.SnapshotKind.BASELINE,
                  OWNER,
                  ProductMetricPipelineLoadSupport.Mode.BATCH1,
                  harness.observation.snapshot()));
    }
    ProductMetricServiceTransactionObservationSupport faulty =
        mock(ProductMetricServiceTransactionObservationSupport.class);
    doThrow(new AssertionError("snapshot leaf fault")).when(faulty).snapshot();
    AssertionError primary = new AssertionError("preserved primary");
    StackTraceElement[] stack = primary.getStackTrace().clone();
    Path unavailable = Files.createDirectory(temporary.resolve("unavailable")).toRealPath();
    assertSame(
        primary,
        ProductOnlineMetricsPipelineIT.completeServiceTransactionObservationAfterClose(
            unavailable, faulty, OWNER, ProductMetricPipelineLoadSupport.Mode.BATCH1, primary));
    assertArrayEquals(stack, primary.getStackTrace());
    assertEquals(0, primary.getSuppressed().length);
    JsonNode envelope =
        JSON.readTree(
            Files.readAllBytes(unavailable.resolve("completion.service-transaction.json")));
    assertEquals("UNAVAILABLE", envelope.path("status").asText());
    assertTrue(envelope.path("observation").isNull());
    verify(faulty).snapshot();
  }

  @Test
  void coldContextLookupErrorIsIsolatedAndDisabledProofHasZeroLookups() {
    org.springframework.context.ApplicationContext unavailable =
        mock(org.springframework.context.ApplicationContext.class);
    ProductMetricServiceTransactionObservationSupport observation =
        mock(ProductMetricServiceTransactionObservationSupport.class);
    when(unavailable.getBean(PlatformTransactionManager.class))
        .thenThrow(new AssertionError("controlled original context lookup"));
    assertDoesNotThrow(
        () ->
            ProductOnlineMetricsPipelineIT.verifyServiceTransactionObservationRuntime(
                unavailable, observation));
    verify(observation).recordObserverFailure();
    verify(observation, never()).verifyRuntime(any(), any());
    verify(unavailable, never()).getBean(DataSource.class);
    org.springframework.context.ApplicationContext disabled =
        mock(org.springframework.context.ApplicationContext.class);
    ProductOnlineMetricsPipelineIT.verifyServiceTransactionObservationRuntime(disabled, null);
    verifyNoInteractions(disabled);
  }

  @Test
  void coldUnprovenRuntimeIsIncompleteAndExportsPartialWithoutPoisoningHealth() throws Exception {
    ProductMetricServiceTransactionObservationSupport unproven =
        new ProductMetricServiceTransactionObservationSupport(
            () -> 0L, () -> ProductMetricPipelineLoadSupport.Stage.SETUP);
    assertThrows(
        ProductMetricServiceTransactionObservationSupport.ObservationIncompleteException.class,
        unproven::assertHealthyAfterClose);
    AssertionError primary = new AssertionError("unrelated existing primary");
    assertSame(
        primary,
        ProductOnlineMetricsPipelineIT.completeServiceTransactionObservationAfterClose(
            temporary.toRealPath(),
            unproven,
            OWNER,
            ProductMetricPipelineLoadSupport.Mode.BATCH1,
            primary));
    JsonNode envelope =
        JSON.readTree(Files.readAllBytes(temporary.resolve("completion.service-transaction.json")));
    assertEquals("PARTIAL", envelope.path("status").asText());
    assertEquals(
        "NONE", envelope.path("observation").path("collectorState").path("failureCode").asText());
  }

  /**
   * Original independent synchronization; order relative to the observer is deliberate evidence.
   */
  private static TransactionSynchronization synchronization(
      String boundary, RuntimeException failure, int order) {
    return new TransactionSynchronization() {
      /** Retain the controlled original participant's explicit order. */
      @Override
      public int getOrder() {
        return order;
      }

      /** Throw only the original participant's controlled pre-commit failure. */
      @Override
      public void beforeCommit(boolean readOnly) {
        if ("BEFORE_COMMIT".equals(boundary)) throw failure;
      }

      /** Throw only the original participant's controlled post-commit failure. */
      @Override
      public void afterCommit() {
        if ("AFTER_COMMIT".equals(boundary)) throw failure;
      }

      /** Spring's existing completion semantics decide whether this failure is logged/swallowed. */
      @Override
      public void afterCompletion(int status) {
        if ("AFTER_COMPLETION".equals(boundary)) throw failure;
      }
    };
  }

  /** Synthetic direct invocation is explicitly separate from the real Spring lifecycle fixtures. */
  private static MethodInvocation invocation(
      List<ProductMetricEventDTO> events, Object result, Throwable failure, AtomicInteger proceeded)
      throws Exception {
    Method method = ProductMetricService.class.getMethod("applyBatch", List.class);
    Object[] originalArguments = {events};
    return new MethodInvocation() {
      /** Expose only the original method metadata. */
      @Override
      public Method getMethod() {
        return method;
      }

      /** Return the same original argument array. */
      @Override
      public Object[] getArguments() {
        return originalArguments;
      }

      /** Count one original execution and return/throw its exact object. */
      @Override
      public Object proceed() throws Throwable {
        proceeded.incrementAndGet();
        if (failure != null) throw failure;
        return result;
      }

      /** No target substitution is needed for this explicitly synthetic seam. */
      @Override
      public Object getThis() {
        return null;
      }

      /** Return the same method as the static part. */
      @Override
      public AccessibleObject getStaticPart() {
        return method;
      }
    };
  }

  /** External JDBC leaf never opens a socket or runs a statement. */
  private static Connection connectionLeaf() throws SQLException {
    Connection connection = mock(Connection.class);
    when(connection.getAutoCommit()).thenReturn(true);
    when(connection.getTransactionIsolation()).thenReturn(Connection.TRANSACTION_READ_COMMITTED);
    return connection;
  }

  /**
   * Real original transaction interception and Service body; only transport/data leaves are
   * doubles.
   */
  private static final class Harness implements AutoCloseable {
    /** Local Bean graph with no auto configuration, network or database startup. */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** Original controlled DataSource bean, shared by Mapper and manager. */
    private final DataSource dataSource = mock(DataSource.class);

    /** External Connection leaf used by the original real transaction manager. */
    private final Connection connection = connectionLeaf();

    /** Original actual DSTM or JdbcTM bean, never wrapped or mocked. */
    private final DataSourceTransactionManager manager;

    /** Original Mapper data leaf; its existing timing proxy is retained separately. */
    private final ProductMetricMapper mapperTarget = mock(ProductMetricMapper.class);

    /** Original configured JdbcTM translation result, independent of collector instrumentation. */
    private final DataAccessResourceFailureException jdbcTranslation =
        new DataAccessResourceFailureException("original configured translation");

    /** Existing Mapper timing proxy, used directly by the original Service constructor. */
    private final ProductMetricMapper mapper;

    /** Original production Service target, without overriding apply or applyBatch. */
    private final ProductMetricService target;

    /** Original Service transaction proxy retained through enabled observation. */
    private final ProductMetricService service;

    /** Legacy timing instance shares the enabled clock but keeps its original health. */
    private final ProductMetricApplicationTimingSupport applicationTiming;

    /** Existing sole load advice supplies S/E; no new advisor is added. */
    private final ProductMetricPipelineLoadSupport load;

    /** Null is the complete new-observation disabled path. */
    private final ProductMetricServiceTransactionObservationSupport observation;

    /** Clock reads distinguish enabled new instrumentation from disabled fixture calls. */
    private final AtomicLong clockReads = new AtomicLong();

    /** Actual original Mapper body entries, independently counted at the data leaf. */
    private final AtomicInteger mapperCalls = new AtomicInteger();

    /** Canonical duplicate events exercise original target validation without business SQL. */
    private final List<ProductMetricEventDTO> events =
        List.of(
            ProductMetricEvents.product("22222222-2222-4222-8222-222222222222", 41L, false, null),
            ProductMetricEvents.product("33333333-3333-4333-8333-333333333333", 42L, false, null));

    /** Existing Service and Mapper advisor references captured before any invocation. */
    private final Advisor[] serviceAdvisors, mapperAdvisors;

    /** Optional original participant inserted by the external leaf after observer registration. */
    private TransactionSynchronization extraSynchronization;

    /** Controlled original external leaf failure; observation must never retain it. */
    private Throwable mapperFailure;

    /** Mark the real transaction rollback-only at the data leaf for a dedicated test. */
    private boolean markRollbackOnly;

    /** Number of registered observer synchronizations seen at the first data leaf entry. */
    private int observedSynchronizationCount;

    /** Keep ordinary identity fixtures free of Mockito instrumentation on the collector. */
    private Harness(boolean enabled, boolean jdbcManager) throws Exception {
      this(enabled, jdbcManager, false);
    }

    /** Optional collector spy is limited to explicitly fault-instrumented fixtures. */
    private Harness(boolean enabled, boolean jdbcManager, boolean faultInstrumentation)
        throws Exception {
      this(enabled, jdbcManager, faultInstrumentation, false);
    }

    /**
     * Freeze original manager configuration before publishing and proving its resource identity.
     */
    private Harness(
        boolean enabled,
        boolean jdbcManager,
        boolean faultInstrumentation,
        boolean rollbackOnCommitFailure)
        throws Exception {
      // 1. Assemble the original local manager/resource and the existing timing/load proxy chain.
      when(dataSource.getConnection()).thenReturn(connection);
      when(mapperTarget.dataSource()).thenReturn(dataSource);
      manager =
          jdbcManager
              ? new JdbcTransactionManager(dataSource)
              : new DataSourceTransactionManager(dataSource);
      manager.setRollbackOnCommitFailure(rollbackOnCommitFailure);
      if (manager instanceof JdbcTransactionManager jdbcManagerBean)
        jdbcManagerBean.setExceptionTranslator(
            (task, sql, failure) -> {
              jdbcTranslation.initCause(failure);
              return jdbcTranslation;
            });
      ProductMetricPipelineLoadSupport[] loadHolder = new ProductMetricPipelineLoadSupport[1];
      LongSupplier clock = () -> -1000L + clockReads.getAndIncrement() * 100L;
      ProductMetricServiceTransactionObservationSupport created =
          enabled
              ? new ProductMetricServiceTransactionObservationSupport(
                  clock, () -> loadHolder[0].currentStage())
              : null;
      observation = faultInstrumentation ? spy(created) : created;
      applicationTiming =
          new ProductMetricApplicationTimingSupport(
              OWNER + "-batch1",
              ProductMetricPipelineLoadSupport.Mode.BATCH1,
              enabled ? clock : () -> 0L,
              observation);
      mapper = (ProductMetricMapper) applicationTiming.installMapper(mapperTarget);
      target = new ProductMetricService(mapper);
      ProxyFactory factory = new ProxyFactory(target);
      factory.setProxyTargetClass(true);
      factory.addAdvice(
          new TransactionInterceptor(manager, new AnnotationTransactionAttributeSource()));
      service = (ProductMetricService) factory.getProxy();
      load =
          new ProductMetricPipelineLoadSupport(
              ProductMetricPipelineLoadSupport.Mode.BATCH1, applicationTiming, null, observation);
      loadHolder[0] = load;
      load.install(service);
      serviceAdvisors = ((Advised) service).getAdvisors().clone();
      mapperAdvisors = ((Advised) mapper).getAdvisors().clone();

      // 2. Only external Mapper data is controlled; original apply/applyBatch bodies still execute.
      ProductMetricControlPO control = new ProductMetricControlPO();
      control.seriesId = ProductMetricEvents.SERIES;
      control.revision = 0L;
      when(mapperTarget.control(true)).thenReturn(control);
      when(mapperTarget.initialApplySnapshot(anyString(), anyLong(), nullable(Long.class)))
          .thenAnswer(
              invocation -> {
                int calls = mapperCalls.incrementAndGet();
                if (calls == 1) {
                  observedSynchronizationCount =
                      TransactionSynchronizationManager.getSynchronizations().size();
                  if (extraSynchronization != null)
                    TransactionSynchronizationManager.registerSynchronization(extraSynchronization);
                }
                if (markRollbackOnly)
                  TransactionAspectSupport.currentTransactionStatus().setRollbackOnly();
                if (mapperFailure != null) throw mapperFailure;
                String eventId = invocation.getArgument(0);
                ProductMetricEventDTO event =
                    events.stream()
                        .filter(candidate -> candidate.eventId.equals(eventId))
                        .findFirst()
                        .orElseThrow();
                return new ProductMetricInitialApplySnapshotDTO(
                    control, eventId, ProductMetricEvents.fingerprint(event), true);
              });

      // 3. Publish the exact original beans and prove identities without acquiring a connection.
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "local-transaction-fixture",
                  Map.of("cloudmall.product.metrics.enabled", "true")));
      context.registerBean(DataSource.class, () -> dataSource);
      context.registerBean(PlatformTransactionManager.class, () -> manager);
      context.registerBean(ProductMetricMapper.class, () -> mapper);
      context.registerBean(ProductMetricService.class, () -> service);
      context.registerBean(
          ProductIndexDocumentLoader.class, () -> mock(ProductIndexDocumentLoader.class));
      context.refresh();
      applicationTiming.verifyRuntime(context, service);
      if (observation != null) assertTrue(observation.verifyRuntime(manager, dataSource));
      verify(dataSource, never()).getConnection();
      assertIdentities();
    }

    /** Cold JSON only; the collector never retains results, exceptions or event identifiers. */
    private JsonNode snapshot() {
      return JSON.valueToTree(observation.snapshot());
    }

    /** Read fixed global counters without assuming cells' array order. */
    private JsonNode counters() {
      return snapshot().path("collectorState").path("counters");
    }

    /** Select one precise fixed cohort; no cumulative aggregate subtraction. */
    private JsonNode cell(String operation, int size, String outcome) {
      for (JsonNode cell : snapshot().path("cells"))
        if ("SETUP".equals(cell.path("entryStage").asText())
            && operation.equals(cell.path("operation").asText())
            && size == cell.path("inputSize").asInt()
            && outcome.equals(cell.path("outcome").asText())) return cell;
      throw new AssertionError("Missing fixed cohort");
    }

    /** Preserve all original manager/resource/proxy/target/advisor references and order. */
    private void assertIdentities() throws Exception {
      assertSame(manager, context.getBean(PlatformTransactionManager.class));
      assertSame(dataSource, context.getBean(DataSource.class));
      assertSame(dataSource, manager.getDataSource());
      assertSame(dataSource, mapper.dataSource());
      assertSame(service, context.getBean(ProductMetricService.class));
      assertSame(mapper, context.getBean(ProductMetricMapper.class));
      assertSame(target, ((Advised) service).getTargetSource().getTarget());
      assertSame(mapperTarget, ((Advised) mapper).getTargetSource().getTarget());
      assertArrayEquals(serviceAdvisors, ((Advised) service).getAdvisors());
      assertArrayEquals(mapperAdvisors, ((Advised) mapper).getAdvisors());
    }

    /** Natural Spring completion must leave no original thread resources or new capabilities. */
    private void assertQuiescent() {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
      assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
      if (observation != null) {
        JsonNode state = snapshot().path("collectorState");
        assertEquals(0, state.path("activeServices").asLong());
        assertEquals(0, state.path("framesOutstanding").asLong());
        assertEquals(0, state.path("pendingRegistrations").asLong());
      }
    }

    /**
     * Close only the local graph; no broker, database, executor or external resource was started.
     */
    @Override
    public void close() {
      context.close();
    }
  }
}
