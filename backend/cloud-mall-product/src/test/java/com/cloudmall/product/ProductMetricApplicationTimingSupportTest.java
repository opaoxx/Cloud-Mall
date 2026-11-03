package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.ProductMetricService;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Method;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.SimpleTransactionStatus;

/** Controlled Java clocks, original local proxy objects and fake invocations; no SQL or network. */
class ProductMetricApplicationTimingSupportTest {
  /** Synthetic identity used only by this in-memory collector. */
  private static final String RUN_IDENTITY = "0123456789abcdef0123456789abcdef-batch1";

  /** Fixed entry stage used by most local duration fixtures. */
  private static final ProductMetricPipelineLoadSupport.Stage MEASUREMENT =
      ProductMetricPipelineLoadSupport.Stage.MEASUREMENT;

  /** Fixed later stage used to expose entry attribution. */
  private static final ProductMetricPipelineLoadSupport.Stage FINISH =
      ProductMetricPipelineLoadSupport.Stage.FINISH_DRAIN;

  @Test
  void constructorRejectsUnknownIdentityAndModeWithoutReadingClock() {
    AtomicInteger reads = new AtomicInteger();
    LongSupplier clock = () -> reads.incrementAndGet();
    assertThrows(
        IllegalStateException.class,
        () ->
            new ProductMetricApplicationTimingSupport(
                "runtime-uuid", ProductMetricPipelineLoadSupport.Mode.BATCH1, clock));
    assertThrows(
        IllegalStateException.class,
        () ->
            new ProductMetricApplicationTimingSupport(
                RUN_IDENTITY, ProductMetricPipelineLoadSupport.Mode.SINGLE1, clock));
    collector(clock);
    assertEquals(0, reads.get());
  }

  @Test
  void pureCollectorSnapshotHasFixedShapeButDoesNotPretendRuntimeProof() {
    var timing =
        collector(
            () -> {
              throw new AssertionError("snapshot must not read a clock");
            });
    Map<String, Object> snapshot = snapshot(timing);
    assertEquals(13, snapshot.size());
    assertEquals(15, map(snapshot.get("runtimeFacts")).size());
    assertEquals(10, map(snapshot.get("observerState")).size());
    assertEquals(1008, list(snapshot.get("cells")).size());
    assertEquals(false, map(snapshot.get("runtimeFacts")).get("serviceProxySame"));
    assertEquals(false, map(snapshot.get("runtimeFacts")).get("physicalAckMeasured"));
    assertThrows(IllegalStateException.class, () -> timing.assertHealthy(true));
    assertFalse(timing.hasFailed());
    assertCellsConsistent(snapshot);
    Set<List<Object>> dimensions = new HashSet<>();
    for (Object item : list(snapshot.get("cells"))) {
      Map<String, Object> cell = map(item);
      assertEquals(10, cell.size());
      assertTrue(
          dimensions.add(
              List.of(
                  cell.get("stage"),
                  cell.get("operation"),
                  cell.get("inputSize"),
                  cell.get("span"),
                  cell.get("outcome"))));
    }
    assertEquals(1008, dimensions.size());
    long[][] cells = (long[][]) ReflectionTestUtils.getField(timing, "cells");
    assertEquals(
        1008 * 18 * Long.BYTES,
        Arrays.stream(cells).mapToInt(row -> row.length * Long.BYTES).sum());
  }

  @Test
  void normalServiceElapsedUsesEntryStageAndExplicitExitCrossing() {
    var timing = collector(sequence(-100, 0));
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    timing.serviceExit(token, false, FINISH);
    Map<String, Object> cell = cell(timing, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4);
    assertEquals(1L, cell.get("calls"));
    assertEquals(100L, cell.get("sumNs"));
    assertEquals(100L, cell.get("maxNs"));
    assertEquals(1L, cell.get("crossBoundaryCalls"));
    assertEquals(1L, state(timing).get("serviceNormalReturns"));
    assertEquals(0L, state(timing).get("activeServiceCalls"));
    assertFalse(timing.hasFailed());
  }

  @Test
  void originalServiceThrowIsALocalOutcomeNotAnObserverFailure() {
    var timing = collector(sequence(10, 20));
    var token = timing.serviceEnter(MEASUREMENT, "apply", 1);
    timing.serviceExit(token, true, MEASUREMENT);
    assertEquals(1L, cell(timing, "SERVICE_PROXY_TOTAL", "THREW", "apply", 1).get("calls"));
    assertEquals(1L, state(timing).get("serviceThrows"));
    assertEquals("NONE", state(timing).get("failureCode"));
    assertFalse(timing.hasFailed());
  }

  @Test
  void allHistogramBoundsAreInclusiveAndOverflowDoesNotChangeTimeout() {
    long[] bounds = (long[]) snapshot(collector(() -> 0)).get("histogramUpperBoundsNs");
    for (int boundary = 0; boundary < bounds.length; boundary++) {
      for (long duration : new long[] {bounds[boundary], bounds[boundary] + 1}) {
        var timing = collector(sequence(0, duration));
        var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
        timing.serviceExit(token, false, MEASUREMENT);
        long[] buckets =
            (long[]) cell(timing, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4).get("buckets");
        int expected = 0;
        while (expected < bounds.length && duration > bounds[expected]) expected++;
        assertEquals(14, buckets.length);
        assertEquals(1, buckets[expected]);
        assertEquals(1, Arrays.stream(buckets).sum());
        assertFalse(timing.hasFailed());
      }
    }
    var zero = collector(sequence(7, 7));
    var token = zero.serviceEnter(MEASUREMENT, "applyBatch", 4);
    zero.serviceExit(token, false, MEASUREMENT);
    assertEquals(
        1L,
        ((long[]) cell(zero, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4).get("buckets"))[0]);
  }

  @Test
  void negativeOriginAndShortSignedWrapAreValidButBackwardDurationIsNot() {
    for (long[] values :
        new long[][] {{-200, -100, 100}, {Long.MAX_VALUE - 5, Long.MIN_VALUE + 4, 10}}) {
      var timing = collector(sequence(values[0], values[1]));
      var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
      timing.serviceExit(token, false, MEASUREMENT);
      assertEquals(
          values[2], cell(timing, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4).get("sumNs"));
      assertFalse(timing.hasFailed());
    }
    var invalid = collector(sequence(10, 9));
    var token = invalid.serviceEnter(MEASUREMENT, "applyBatch", 4);
    invalid.serviceExit(token, false, MEASUREMENT);
    assertEquals("CLOCK_INVALID", state(invalid).get("failureCode"));
    assertEquals(0L, cell(invalid, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4).get("calls"));
    assertEquals(0L, state(invalid).get("framesOutstanding"));
  }

  @Test
  void entryClockFailureKeepsBusinessCompletionAndReleasesItsOwnFrame() {
    AtomicInteger reads = new AtomicInteger();
    var timing =
        collector(
            () -> {
              reads.incrementAndGet();
              throw new AssertionError("private clock");
            });
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    assertEquals(1L, state(timing).get("activeServiceCalls"));
    timing.serviceExit(token, false, MEASUREMENT);
    assertEquals(1, reads.get());
    assertEquals("CLOCK_INVALID", state(timing).get("failureCode"));
    assertEquals(1L, state(timing).get("serviceNormalReturns"));
    assertEquals(0L, state(timing).get("framesOutstanding"));
    assertNull(frame(timing));
  }

  @Test
  void exitClockFailureIsStickyAndReleasesItsAdmittedFrame() {
    AtomicInteger reads = new AtomicInteger();
    var timing =
        collector(
            () -> {
              if (reads.getAndIncrement() == 0) return 10;
              throw new AssertionError("private clock");
            });
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    assertDoesNotThrow(() -> timing.serviceExit(token, false, MEASUREMENT));
    assertEquals("CLOCK_INVALID", state(timing).get("failureCode"));
    assertEquals(0L, state(timing).get("activeServiceCalls"));
    assertNull(frame(timing));
  }

  @Test
  void wholeCellOverflowLeavesThePreviousCellUnchangedAndCoverageInvalid() {
    var timing = collector(sequence(0, Long.MAX_VALUE, 0, 1));
    var first = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    timing.serviceExit(first, false, MEASUREMENT);
    Map<String, Object> before = cell(timing, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4);
    var second = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    timing.serviceExit(second, false, MEASUREMENT);
    Map<String, Object> after = cell(timing, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4);
    assertEquals(before.get("calls"), after.get("calls"));
    assertEquals(before.get("sumNs"), after.get("sumNs"));
    assertEquals(before.get("maxNs"), after.get("maxNs"));
    assertArrayEquals((long[]) before.get("buckets"), (long[]) after.get("buckets"));
    assertEquals("COUNT_OVERFLOW", state(timing).get("failureCode"));
    assertEquals(false, state(timing).get("coverageComplete"));
    assertEquals(0L, state(timing).get("activeServiceCalls"));
    assertCellsConsistent(snapshot(timing));

    // Seed a representable aggregate of zero-duration calls: the next calls increment overflows.
    var callOverflow = collector(sequence(0, 1));
    long[] callsCell = rawNormalServiceCell(callOverflow);
    callsCell[0] = Long.MAX_VALUE;
    callsCell[3] = Long.MAX_VALUE;
    long[] callsBefore = callsCell.clone();
    var callToken = callOverflow.serviceEnter(MEASUREMENT, "applyBatch", 4);
    assertDoesNotThrow(() -> callOverflow.serviceExit(callToken, false, MEASUREMENT));
    assertArrayEquals(callsBefore, callsCell);
    assertEquals("COUNT_OVERFLOW", state(callOverflow).get("failureCode"));
    assertEquals(false, state(callOverflow).get("coverageComplete"));
    assertEquals(0L, state(callOverflow).get("activeServiceCalls"));
    assertNull(frame(callOverflow));
    assertCellsConsistent(snapshot(callOverflow));

    // In valid state bucket/cross <= calls: their MAX increment is preceded by calls overflow.
    // Isolated synthetic corrupt-state injections exercise the later checked-candidate guards,
    // without claiming these two states can be produced by a healthy collector.
    for (int overflowingSlot : new int[] {3, 17}) {
      var candidateOverflow = collector(sequence(0, 1));
      long[] candidateCell = rawNormalServiceCell(candidateOverflow);
      candidateCell[overflowingSlot] = Long.MAX_VALUE;
      long[] candidateBefore = candidateCell.clone();
      var candidateToken = candidateOverflow.serviceEnter(MEASUREMENT, "applyBatch", 4);
      assertDoesNotThrow(() -> candidateOverflow.serviceExit(candidateToken, false, FINISH));
      assertArrayEquals(candidateBefore, candidateCell);
      assertEquals("COUNT_OVERFLOW", state(candidateOverflow).get("failureCode"));
      assertEquals(false, state(candidateOverflow).get("coverageComplete"));
      assertEquals(0L, state(candidateOverflow).get("activeServiceCalls"));
      assertNull(frame(candidateOverflow));
    }
  }

  @Test
  void entryStateOverflowDoesNotLeakAThreadLocalOrActiveCount() throws Throwable {
    var timing =
        collector(
            () -> {
              throw new AssertionError("overflow must precede clock");
            });
    ReflectionTestUtils.setField(timing, "serviceEntries", Long.MAX_VALUE);
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    timing.serviceExit(token, false, MEASUREMENT);
    assertEquals("COUNT_OVERFLOW", state(timing).get("failureCode"));
    assertEquals(0L, state(timing).get("activeServiceCalls"));
    assertEquals(Long.MAX_VALUE, state(timing).get("serviceEntries"));
    assertNull(frame(timing));

    // active <= entries in healthy state, so this isolated active overflow is a corrupt-state
    // fault injection. Failure must not partially commit the candidate entries increment.
    var activeOverflow =
        collector(
            () -> {
              throw new AssertionError("overflow precedes clock");
            });
    ReflectionTestUtils.setField(activeOverflow, "activeServiceCalls", Long.MAX_VALUE);
    var activeToken = activeOverflow.serviceEnter(MEASUREMENT, "applyBatch", 4);
    assertDoesNotThrow(() -> activeOverflow.serviceExit(activeToken, false, MEASUREMENT));
    assertEquals(0L, state(activeOverflow).get("serviceEntries"));
    assertEquals(Long.MAX_VALUE, state(activeOverflow).get("activeServiceCalls"));
    assertEquals("COUNT_OVERFLOW", state(activeOverflow).get("failureCode"));
    assertEquals(false, state(activeOverflow).get("coverageComplete"));
    assertNull(frame(activeOverflow));

    // Completion cannot naturally exceed registered entries; inject each counter independently
    // after admitting one frame to verify overflow still releases exactly that frame and active.
    for (boolean originalThrew : new boolean[] {false, true}) {
      var completedOverflow = collector(sequence(0, 1));
      var completedToken = completedOverflow.serviceEnter(MEASUREMENT, "applyBatch", 4);
      String overflowingCounter = originalThrew ? "serviceThrows" : "serviceNormalReturns";
      String otherCounter = originalThrew ? "serviceNormalReturns" : "serviceThrows";
      ReflectionTestUtils.setField(completedOverflow, overflowingCounter, Long.MAX_VALUE);
      assertDoesNotThrow(
          () -> completedOverflow.serviceExit(completedToken, originalThrew, MEASUREMENT));
      assertEquals(Long.MAX_VALUE, state(completedOverflow).get(overflowingCounter));
      assertEquals(0L, state(completedOverflow).get(otherCounter));
      assertEquals(1L, state(completedOverflow).get("serviceEntries"));
      assertEquals(0L, state(completedOverflow).get("activeServiceCalls"));
      assertEquals(0L, state(completedOverflow).get("framesOutstanding"));
      assertEquals("COUNT_OVERFLOW", state(completedOverflow).get("failureCode"));
      assertEquals(false, state(completedOverflow).get("coverageComplete"));
      assertNull(frame(completedOverflow));
    }

    // A synthetic saturated Mapper active counter must preserve the original body exactly once.
    // It is not decremented because the failed increment never admitted this Mapper observation.
    var mapperOverflow = collector(sequence(0, 1));
    var mapperToken = mapperOverflow.serviceEnter(MEASUREMENT, "applyBatch", 4);
    ReflectionTestUtils.setField(mapperOverflow, "activeMapperCalls", Long.MAX_VALUE);
    Object originalReturn = new Object();
    Invocation invocation =
        invocation(
            "ensureStat",
            new Class<?>[] {long.class, java.time.LocalDateTime.class},
            new Object[] {41L, null},
            () -> originalReturn);
    assertSame(originalReturn, mapperOverflow.mapperAdvice().invoke(invocation));
    mapperOverflow.serviceExit(mapperToken, false, MEASUREMENT);
    assertEquals(1, invocation.proceeded);
    assertEquals(Long.MAX_VALUE, state(mapperOverflow).get("activeMapperCalls"));
    assertEquals(0L, state(mapperOverflow).get("activeServiceCalls"));
    assertEquals("COUNT_OVERFLOW", state(mapperOverflow).get("failureCode"));
    assertEquals(false, state(mapperOverflow).get("coverageComplete"));
    assertNull(frame(mapperOverflow));
  }

  @Test
  void noApplyFrameBypassesAllObservationEvenWhenAccessorWouldFail() throws Throwable {
    var timing =
        collector(
            () -> {
              throw new AssertionError("no-frame clock");
            });
    Object result = new Object();
    Invocation invocation = invocation("advance", new Class<?>[0], new Object[0], () -> result);
    invocation.accessorFailure = true;
    assertSame(result, timing.mapperAdvice().invoke(invocation));
    assertEquals(1, invocation.proceeded);
    assertFalse(timing.hasFailed());
    assertEquals(0L, state(timing).get("unclassifiedMapperCalls"));
  }

  @Test
  void objectAndDatasourceMetadataInsideFrameDoNotReadMapperClocks() throws Throwable {
    var timing = collector(sequence(0, 100));
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    Invocation getter = invocation("dataSource", new Class<?>[0], new Object[0], () -> null);
    timing.mapperAdvice().invoke(getter);
    Invocation object =
        new Invocation(Object.class.getMethod("toString"), new Object[0], () -> "synthetic");
    assertEquals("synthetic", timing.mapperAdvice().invoke(object));
    timing.serviceExit(token, false, MEASUREMENT);
    assertFalse(timing.hasFailed());
    assertEquals(0L, state(timing).get("unclassifiedMapperCalls"));
    assertEquals(1, getter.proceeded);
    assertEquals(1, object.proceeded);
  }

  @Test
  void unclassifiedBusinessMethodIsNotSilentlyAssignedToAWhitelistedSpan() throws Throwable {
    var timing = collector(sequence(0, 100));
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    Invocation invocation =
        invocation(
            "findAppliedIdentity",
            new Class<?>[] {String.class, boolean.class},
            new Object[] {"synthetic", true},
            () -> null);
    timing.mapperAdvice().invoke(invocation);
    timing.serviceExit(token, false, MEASUREMENT);
    assertEquals(1, invocation.proceeded);
    assertEquals(1L, state(timing).get("unclassifiedMapperCalls"));
    assertEquals(false, state(timing).get("coverageComplete"));
    assertTrue(timing.hasFailed());
  }

  @Test
  void statWithoutLockInsideApplyFrameIsUnknownButOriginalCallStillRuns() throws Throwable {
    var timing = collector(sequence(0, 100));
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    Invocation invocation =
        invocation(
            "stat",
            new Class<?>[] {long.class, boolean.class},
            new Object[] {41L, false},
            () -> null);
    timing.mapperAdvice().invoke(invocation);
    timing.serviceExit(token, false, MEASUREMENT);
    assertEquals(1, invocation.proceeded);
    assertEquals("INPUT_UNKNOWN", state(timing).get("failureCode"));
    assertEquals(0L, cell(timing, "LOCK_STAT", "NORMAL", "applyBatch", 4).get("calls"));
  }

  @Test
  void mapperNormalAndOriginalThrowablePreserveExactResultsAndProceedOnce() throws Throwable {
    for (boolean throwsOriginal : new boolean[] {false, true}) {
      var timing = collector(sequence(0, 5, 15, 30));
      var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
      Object returned = new Object();
      AssertionError primary = new AssertionError("original business Error");
      primary.addSuppressed(new IllegalStateException("original secondary"));
      StackTraceElement[] originalStack = primary.getStackTrace().clone();
      Invocation invocation =
          invocation(
              "ensureStat",
              new Class<?>[] {long.class, java.time.LocalDateTime.class},
              new Object[] {41L, null},
              () -> {
                if (throwsOriginal) throw primary;
                return returned;
              });
      if (throwsOriginal)
        assertSame(
            primary,
            assertThrows(AssertionError.class, () -> timing.mapperAdvice().invoke(invocation)));
      else assertSame(returned, timing.mapperAdvice().invoke(invocation));
      timing.serviceExit(token, throwsOriginal, MEASUREMENT);
      assertEquals(1, invocation.proceeded);
      assertArrayEquals(originalStack, primary.getStackTrace());
      assertEquals(1, primary.getSuppressed().length);
      assertEquals(
          1L,
          cell(timing, "ENSURE_STAT", throwsOriginal ? "THREW" : "NORMAL", "applyBatch", 4)
              .get("calls"));
      assertFalse(timing.hasFailed());
    }
  }

  @Test
  void mapperAccessorFaultCannotPreventOriginalProceedOrReplaceItsReturnOrThrowable()
      throws Throwable {
    for (boolean methodAccessor : new boolean[] {false, true}) {
      for (boolean originalThrows : new boolean[] {false, true}) {
        var timing = collector(sequence(0, 100));
        var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
        Object returned = new Object();
        AssertionError primary = new AssertionError("original body failure");
        Invocation invocation =
            invocation(
                "stat",
                new Class<?>[] {long.class, boolean.class},
                new Object[] {41L, true},
                () -> {
                  if (originalThrows) throw primary;
                  return returned;
                });
        invocation.accessorFailure = methodAccessor;
        invocation.argumentFailure = !methodAccessor;
        if (originalThrows)
          assertSame(
              primary,
              assertThrows(AssertionError.class, () -> timing.mapperAdvice().invoke(invocation)));
        else assertSame(returned, timing.mapperAdvice().invoke(invocation));
        timing.serviceExit(token, originalThrows, MEASUREMENT);
        assertEquals(1, invocation.proceeded);
        assertEquals("OBSERVER_FAILED", state(timing).get("failureCode"));
      }
    }
  }

  @Test
  void allEightExactSignaturesUseOnlyTheirFixedSemanticSpans() throws Throwable {
    AtomicLong clock = new AtomicLong();
    var timing = collector(clock::getAndIncrement);
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    String[] names = {
      "initialApplySnapshot",
      "ensureStat",
      "stat",
      "insertApplied",
      "updateStat",
      "control",
      "advanceBy",
      "advance"
    };
    Class<?>[][] parameters = {
      {String.class, long.class, Long.class},
      {long.class, java.time.LocalDateTime.class},
      {long.class, boolean.class},
      {ProductMetricEventDTO.class, String.class, long.class, java.time.LocalDateTime.class},
      {
        com.cloudmall.product.domain.po.ProductOnlineStatPO.class,
        String.class,
        java.time.LocalDateTime.class
      },
      {boolean.class},
      {int.class},
      {}
    };
    Object[][] arguments = {
      {"unit", 41L, null},
      {41L, null},
      {41L, true},
      {null, "unit", 1L, null},
      {null, "unit", null},
      {true},
      {1},
      {}
    };
    String[] spans = {
      "INITIAL_APPLY_SNAPSHOT",
      "ENSURE_STAT",
      "LOCK_STAT",
      "INSERT_APPLIED",
      "UPDATE_STAT_AND_PROJECTION",
      "CONTROL",
      "ADVANCE_BY",
      "ADVANCE"
    };
    for (int index = 0; index < names.length; index++) {
      Invocation invocation =
          invocation(names[index], parameters[index], arguments[index], () -> null);
      timing.mapperAdvice().invoke(invocation);
      assertEquals(1, invocation.proceeded);
      assertEquals(1L, cell(timing, spans[index], "NORMAL", "applyBatch", 4).get("calls"));
    }
    timing.serviceExit(token, false, MEASUREMENT);
    assertFalse(timing.hasFailed());
    assertEquals(0L, state(timing).get("activeMapperCalls"));
  }

  @Test
  void enhancedActualMapperInvocationUsesFixedAliasAndOriginalBodyOnce() {
    var timing = collector(sequence(0, 5, 15, 30));
    var original = new SemanticMapper();
    ProductMetricMapper enhanced = (ProductMetricMapper) timing.installMapper(original);
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    assertEquals(2, enhanced.advanceBy(2));
    timing.serviceExit(token, false, MEASUREMENT);
    assertEquals(1, original.advanceCalls);
    assertEquals(1L, cell(timing, "ADVANCE_BY", "NORMAL", "applyBatch", 4).get("calls"));
    assertEquals(10L, cell(timing, "ADVANCE_BY", "NORMAL", "applyBatch", 4).get("sumNs"));
    assertFalse(timing.hasFailed());
  }

  @Test
  void nestedServiceNoopExitKeepsTheOuterFrameUntilItsOwnExit() {
    var timing = collector(sequence(0, 100));
    var outer = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    Object original = frame(timing);
    var nested = timing.serviceEnter(FINISH, "apply", 1);
    timing.serviceExit(nested, false, FINISH);
    assertSame(original, frame(timing));
    assertEquals(1L, state(timing).get("activeServiceCalls"));
    timing.serviceExit(outer, false, FINISH);
    assertNull(frame(timing));
    assertEquals(0L, state(timing).get("activeServiceCalls"));
    assertEquals(1L, state(timing).get("nestedServiceCalls"));
    assertEquals("UNEXPECTED_REENTRY", state(timing).get("failureCode"));
  }

  @Test
  void foreignDuplicateAndWrongThreadTokensNeverReleaseAnotherActiveFrame() throws Exception {
    var owner = collector(sequence(0, 100));
    var other = collector(() -> 0);
    var token = owner.serviceEnter(MEASUREMENT, "applyBatch", 4);
    var otherToken = other.serviceEnter(MEASUREMENT, "apply", 1);
    Object otherFrame = frame(other);
    other.serviceExit(token, false, MEASUREMENT);
    assertSame(otherFrame, frame(other));
    assertEquals(1L, state(other).get("activeServiceCalls"));
    other.serviceExit(otherToken, false, MEASUREMENT);
    assertEquals(1L, state(owner).get("activeServiceCalls"));
    var executor = Executors.newSingleThreadExecutor();
    try {
      executor.submit(() -> owner.serviceExit(token, false, MEASUREMENT)).get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertEquals(1L, state(owner).get("activeServiceCalls"));
    owner.serviceExit(token, false, MEASUREMENT);
    owner.serviceExit(token, false, MEASUREMENT);
    assertEquals(0L, state(owner).get("activeServiceCalls"));
    assertEquals(1L, state(owner).get("serviceNormalReturns"));
    assertTrue(owner.hasFailed());
    assertTrue(other.hasFailed());
  }

  @Test
  void nestedMapperIsNotDoubleTimedAndOriginalInnerCallStillProceeds() throws Throwable {
    var timing = collector(sequence(0, 5, 15, 30));
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    Invocation inner =
        invocation(
            "stat",
            new Class<?>[] {long.class, boolean.class},
            new Object[] {41L, true},
            () -> null);
    Invocation outer =
        invocation(
            "ensureStat",
            new Class<?>[] {long.class, java.time.LocalDateTime.class},
            new Object[] {41L, null},
            () -> timing.mapperAdvice().invoke(inner));
    timing.mapperAdvice().invoke(outer);
    timing.serviceExit(token, false, MEASUREMENT);
    assertEquals(1, inner.proceeded);
    assertEquals(1, outer.proceeded);
    assertEquals(1L, cell(timing, "ENSURE_STAT", "NORMAL", "applyBatch", 4).get("calls"));
    assertEquals(0L, cell(timing, "LOCK_STAT", "NORMAL", "applyBatch", 4).get("calls"));
    assertEquals(0L, state(timing).get("activeMapperCalls"));
    assertEquals("UNEXPECTED_REENTRY", state(timing).get("failureCode"));
  }

  @Test
  void concurrentCallsUseFixedCellsAndClearTheirOwnThreadLocals() throws Exception {
    AtomicLong clock = new AtomicLong();
    var timing = collector(clock::getAndIncrement);
    var executor = Executors.newFixedThreadPool(4);
    CountDownLatch start = new CountDownLatch(1);
    try {
      var tasks =
          java.util.stream.IntStream.range(0, 4)
              .mapToObj(
                  worker ->
                      executor.submit(
                          () -> {
                            try {
                              assertTrue(start.await(5, TimeUnit.SECONDS));
                            } catch (InterruptedException interrupted) {
                              throw new AssertionError(interrupted);
                            }
                            for (int count = 0; count < 1000; count++) {
                              var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
                              timing.serviceExit(token, false, MEASUREMENT);
                              assertNull(frame(timing));
                            }
                          }))
              .toList();
      start.countDown();
      for (int sample = 0; sample < 100; sample++) {
        assertHealthyCounterRelations(snapshot(timing));
        if (tasks.stream().allMatch(java.util.concurrent.Future::isDone)) break;
      }
      for (var task : tasks) task.get(5, TimeUnit.SECONDS);
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
    assertEquals(4000L, state(timing).get("serviceEntries"));
    assertEquals(4000L, state(timing).get("serviceNormalReturns"));
    assertEquals(0L, state(timing).get("framesOutstanding"));
    assertEquals(
        4000L, cell(timing, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4).get("calls"));
    assertFalse(timing.hasFailed());
    assertCellsConsistent(snapshot(timing));
  }

  @Test
  void plainMapperProxyPreservesInjectedTargetDatasourceAndOriginalServiceAdvisors()
      throws Exception {
    var fixture = new RuntimeFixture(false);
    assertNotSame(fixture.originalMapper, fixture.mapper);
    assertSame(fixture.originalMapper, ((Advised) fixture.mapper).getTargetSource().getTarget());
    Advisor[] original = ((Advised) fixture.service).getAdvisors().clone();
    fixture.timing.verifyRuntime(fixture.context, fixture.service);
    fixture.timing.verifyRuntime(fixture.context, fixture.service);
    assertArrayEquals(original, ((Advised) fixture.service).getAdvisors());
    fixture.timing.assertHealthy(true);
    assertEquals(
        true,
        map(snapshot(fixture.timing).get("runtimeFacts")).get("mapperInjectedIdentityMatched"));
    assertEquals(
        false, map(snapshot(fixture.timing).get("runtimeFacts")).get("serviceTimingAdviceAdded"));
  }

  @Test
  void alreadyAdvisedMapperKeepsSameProxyAndOriginalAdvisorObjectOrder() throws Exception {
    var fixture = new RuntimeFixture(true);
    assertSame(fixture.mapperBeforeInstall, fixture.mapper);
    assertSame(fixture.originalMapperAdvisor, ((Advised) fixture.mapper).getAdvisors()[1]);
    fixture.timing.verifyRuntime(fixture.context, fixture.service);
    fixture.timing.assertHealthy(true);
    assertEquals(
        true, map(snapshot(fixture.timing).get("runtimeFacts")).get("mapperAdvisorsPreserved"));
  }

  @Test
  void frozenDuplicateOrWrongMapperInstallationFailsClosed() {
    var timing = collector(() -> 0);
    var original = new ProductMetricMapper(new JdbcTemplate(new NoSqlDataSource()));
    ProxyFactory factory = new ProxyFactory(original);
    factory.setProxyTargetClass(true);
    factory.setFrozen(true);
    Object frozen = factory.getProxy();
    assertThrows(IllegalStateException.class, () -> timing.installMapper(frozen));
    assertEquals("INSTALL_INVALID", state(timing).get("failureCode"));
    var duplicate = collector(() -> 0);
    duplicate.installMapper(original);
    assertThrows(IllegalStateException.class, () -> duplicate.installMapper(original));
    assertThrows(IllegalStateException.class, () -> collector(() -> 0).installMapper(new Object()));
  }

  @Test
  void runtimeProofRejectsDifferentInjectedMapperAndLaterAdvisorDrift() throws Exception {
    var fixture = new RuntimeFixture(false);
    fixture.timing.verifyRuntime(fixture.context, fixture.service);
    ((Advised) fixture.service)
        .addAdvice(
            (org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
    assertThrows(
        IllegalStateException.class,
        () -> fixture.timing.verifyRuntime(fixture.context, fixture.service));
    assertEquals(
        false, map(snapshot(fixture.timing).get("runtimeFacts")).get("serviceAdvisorsPreserved"));
    var mismatch = new RuntimeFixture(false);
    when(mismatch.context.getBean(ProductMetricMapper.class)).thenReturn(mismatch.originalMapper);
    assertThrows(
        IllegalStateException.class,
        () -> mismatch.timing.verifyRuntime(mismatch.context, mismatch.service));
    assertTrue(mismatch.timing.hasFailed());
  }

  @Test
  void snapshotCopiesDoNotMutateCountersOrSharedHistogramBounds() {
    var timing = collector(sequence(0, 100));
    var token = timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    timing.serviceExit(token, false, MEASUREMENT);
    Map<String, Object> exported = snapshot(timing);
    ((long[]) exported.get("histogramUpperBoundsNs"))[0] = -1;
    Map<String, Object> selected =
        cellFrom(exported, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4);
    ((long[]) selected.get("buckets"))[0] = -1;
    selected.put("calls", -1);
    assertEquals(100_000L, ((long[]) snapshot(timing).get("histogramUpperBoundsNs"))[0]);
    assertEquals(1L, cell(timing, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4).get("calls"));
    assertEquals(
        1L,
        ((long[]) cell(timing, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4).get("buckets"))
            [0]);
  }

  @Test
  void inflightSnapshotAndIdleHealthGateRemainDistinct() throws Exception {
    var fixture = new RuntimeFixture(false);
    fixture.timing.verifyRuntime(fixture.context, fixture.service);
    var token = fixture.timing.serviceEnter(MEASUREMENT, "applyBatch", 4);
    fixture.timing.assertHealthy(false);
    assertThrows(IllegalStateException.class, () -> fixture.timing.assertHealthy(true));
    assertEquals(1L, state(fixture.timing).get("activeServiceCalls"));
    assertEquals(0L, state(fixture.timing).get("serviceNormalReturns"));
    fixture.timing.serviceExit(token, false, MEASUREMENT);
    fixture.timing.assertHealthy(true);
  }

  @Test
  void invalidInputAndExplicitObserverFailureStayStickyWithoutClockOrReset() {
    AtomicInteger reads = new AtomicInteger();
    var timing = collector(() -> reads.incrementAndGet());
    timing.serviceEnter(MEASUREMENT, "unsupported", 4);
    timing.recordObserverFailure();
    var ignored = timing.serviceEnter(FINISH, "applyBatch", 4);
    timing.serviceExit(ignored, false, FINISH);
    assertEquals(0, reads.get());
    assertEquals("INPUT_UNKNOWN", state(timing).get("failureCode"));
    assertEquals(false, state(timing).get("coverageComplete"));
    assertEquals(0L, state(timing).get("framesOutstanding"));
  }

  /** Construct only a pure collector; runtime proof remains explicitly unverified. */
  private static ProductMetricApplicationTimingSupport collector(LongSupplier clock) {
    return new ProductMetricApplicationTimingSupport(
        RUN_IDENTITY, ProductMetricPipelineLoadSupport.Mode.BATCH1, clock);
  }

  /** Consume a fixed sequence; accidental extra timing reads fail the observation. */
  private static LongSupplier sequence(long... values) {
    AtomicInteger cursor = new AtomicInteger();
    return () -> values[cursor.getAndIncrement()];
  }

  /** Export a cold snapshot without requiring actual application runtime qualification. */
  private static Map<String, Object> snapshot(ProductMetricApplicationTimingSupport timing) {
    return timing.snapshot(
        ProductMetricApplicationTimingSupport.SnapshotKind.MEASUREMENT_ENDED, FINISH);
  }

  /** Read fixed observer state from its immutable cold copy. */
  private static Map<String, Object> state(ProductMetricApplicationTimingSupport timing) {
    return map(snapshot(timing).get("observerState"));
  }

  /** Locate one exact constant dimension cell. */
  private static Map<String, Object> cell(
      ProductMetricApplicationTimingSupport timing,
      String span,
      String outcome,
      String operation,
      int inputSize) {
    return cellFrom(snapshot(timing), span, outcome, operation, inputSize);
  }

  /** Locate one exact constant dimension cell in an already copied snapshot. */
  private static Map<String, Object> cellFrom(
      Map<String, Object> snapshot, String span, String outcome, String operation, int inputSize) {
    for (Object item : list(snapshot.get("cells"))) {
      Map<String, Object> cell = map(item);
      if (MEASUREMENT.name().equals(cell.get("stage"))
          && span.equals(cell.get("span"))
          && outcome.equals(cell.get("outcome"))
          && operation.equals(cell.get("operation"))
          && Integer.valueOf(inputSize).equals(cell.get("inputSize"))) return cell;
    }
    throw new AssertionError("fixed cell absent");
  }

  /** Locate the raw fixed cell only for explicitly labeled synthetic arithmetic fault injection. */
  private static long[] rawNormalServiceCell(ProductMetricApplicationTimingSupport timing) {
    Map<String, Object> copied = snapshot(timing);
    Map<String, Object> selected =
        cellFrom(copied, "SERVICE_PROXY_TOTAL", "NORMAL", "applyBatch", 4);
    int selectedIndex = list(copied.get("cells")).indexOf(selected);
    return ((long[][]) ReflectionTestUtils.getField(timing, "cells"))[selectedIndex];
  }

  /** Inspect only this test's private active-frame slot, never a business argument. */
  private static Object frame(ProductMetricApplicationTimingSupport timing) {
    return ((ThreadLocal<?>) ReflectionTestUtils.getField(timing, "currentFrame")).get();
  }

  /** Verify per-cell invariants even for sticky-invalid partial evidence. */
  private static void assertCellsConsistent(Map<String, Object> snapshot) {
    for (Object item : list(snapshot.get("cells"))) {
      Map<String, Object> cell = map(item);
      long count = ((Number) cell.get("calls")).longValue();
      long sum = ((Number) cell.get("sumNs")).longValue();
      long maximum = ((Number) cell.get("maxNs")).longValue();
      long crossed = ((Number) cell.get("crossBoundaryCalls")).longValue();
      long[] buckets = (long[]) cell.get("buckets");
      assertEquals(14, buckets.length);
      assertEquals(count, Arrays.stream(buckets).sum());
      assertTrue(crossed <= count && sum >= maximum);
      if (count == 0) assertEquals(0, sum + maximum + crossed);
    }
  }

  /** Healthy inflight snapshots close completed cells and state at the same observation point. */
  private static void assertHealthyCounterRelations(Map<String, Object> snapshot) {
    Map<String, Object> state = map(snapshot.get("observerState"));
    assertEquals("NONE", state.get("failureCode"));
    long normal = 0, threw = 0;
    for (Object item : list(snapshot.get("cells"))) {
      Map<String, Object> cell = map(item);
      if (!"SERVICE_PROXY_TOTAL".equals(cell.get("span"))) continue;
      long count = ((Number) cell.get("calls")).longValue();
      if ("NORMAL".equals(cell.get("outcome"))) normal += count;
      else threw += count;
    }
    assertEquals(normal, state.get("serviceNormalReturns"));
    assertEquals(threw, state.get("serviceThrows"));
    long active = ((Number) state.get("activeServiceCalls")).longValue();
    assertEquals(((Number) state.get("serviceEntries")).longValue() - normal - threw, active);
    assertEquals(active, state.get("framesOutstanding"));
  }

  /** Cast only the helper's own cold closed-schema map. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  /** Cast only the helper's own fixed cell list. */
  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  /** Build a controlled semantic Mapper invocation without calling JDBC. */
  private static Invocation invocation(
      String name, Class<?>[] types, Object[] arguments, Action action)
      throws NoSuchMethodException {
    return new Invocation(ProductMetricMapper.class.getMethod(name, types), arguments, action);
  }

  /** Business execution may return or throw; observation never owns its result. */
  private interface Action {
    /** Invoke the original controlled body once. */
    Object run() throws Throwable;
  }

  /** Fake AOP invocation preserves one original body and independently counted proceeds. */
  private static final class Invocation implements MethodInvocation {
    /** Original fixed semantic method metadata. */
    private final Method method;

    /** Original arguments remain local to this test invocation. */
    private final Object[] arguments;

    /** Original body, never replaced by the collector. */
    private final Action action;

    /** Actual number of body invocations. */
    private int proceeded;

    /** Controlled metadata accessor failure probes the observer-only error boundary. */
    private boolean accessorFailure;

    /** Controlled argument accessor fault, distinct from a business Throwable. */
    private boolean argumentFailure;

    /** Bind original metadata, arguments and body. */
    private Invocation(Method method, Object[] arguments, Action action) {
      this.method = method;
      this.arguments = arguments;
      this.action = action;
    }

    /** Return or deliberately fail original metadata without invoking the business body. */
    @Override
    public Method getMethod() {
      if (accessorFailure) throw new AssertionError("private accessor failure");
      return method;
    }

    /** Return original argument references only to the current AOP invocation. */
    @Override
    public Object[] getArguments() {
      if (argumentFailure) throw new AssertionError("private argument accessor failure");
      return arguments;
    }

    /** Invoke the exact controlled business body once per original proceed. */
    @Override
    public Object proceed() throws Throwable {
      proceeded++;
      return action.run();
    }

    /** No actual target is needed for these direct advice unit invocations. */
    @Override
    public Object getThis() {
      return this;
    }

    /** Return the fixed reflective joinpoint metadata. */
    @Override
    public AccessibleObject getStaticPart() {
      return method;
    }
  }

  /** A datasource which makes any accidental SQL/connection attempt fail the test. */
  private static final class NoSqlDataSource extends AbstractDataSource {
    /** No network resource may be acquired by the runtime metadata proof. */
    @Override
    public Connection getConnection() throws SQLException {
      throw new AssertionError("SQL forbidden");
    }

    /** No credentialed network resource may be acquired by the runtime metadata proof. */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      throw new AssertionError("SQL forbidden");
    }
  }

  /** Override one semantic body to exercise a genuine class proxy without issuing SQL. */
  static class SemanticMapper extends ProductMetricMapper {
    /** Number of original target calls, independent of timing cells. */
    private int advanceCalls;

    /** Bind one original nonconnecting datasource. */
    SemanticMapper() {
      super(new JdbcTemplate(new NoSqlDataSource()));
    }

    /** Preserve the input and invoke the original controlled body once. */
    @Override
    public int advanceBy(int count) {
      advanceCalls++;
      return count;
    }
  }

  /** Real local framework proxy identities with mocked transactions and no business execution. */
  private static final class RuntimeFixture {
    /** One nonconnecting datasource. */
    private final DataSource dataSource = new NoSqlDataSource();

    /** One original mapper constructed over the original datasource. */
    private final ProductMetricMapper originalMapper =
        new ProductMetricMapper(new JdbcTemplate(dataSource));

    /** Original supplied mapper, possibly already advised. */
    private final Object mapperBeforeInstall;

    /** Original preexisting Mapper advisor, or null for a plain mapper. */
    private final Advisor originalMapperAdvisor;

    /** The sole timing collector. */
    private final ProductMetricApplicationTimingSupport timing =
        collector(new AtomicLong()::getAndIncrement);

    /** Enhanced original mapper actually injected into Service. */
    private final ProductMetricMapper mapper;

    /** Original transactional Service proxy. */
    private final ProductMetricService service;

    /** Cold context lookups do not initialize infrastructure or endpoints. */
    private final ApplicationContext context = mock(ApplicationContext.class);

    /** Build the same constructor dependency chain the real BPP must preserve. */
    private RuntimeFixture(boolean alreadyAdvised) throws Exception {
      if (alreadyAdvised) {
        ProxyFactory mapperFactory = new ProxyFactory(originalMapper);
        mapperFactory.setProxyTargetClass(true);
        mapperFactory.addAdvice(
            (org.aopalliance.intercept.MethodInterceptor) invocation -> invocation.proceed());
        mapperBeforeInstall = mapperFactory.getProxy();
        originalMapperAdvisor = ((Advised) mapperBeforeInstall).getAdvisors()[0];
      } else {
        mapperBeforeInstall = originalMapper;
        originalMapperAdvisor = null;
      }
      mapper = (ProductMetricMapper) timing.installMapper(mapperBeforeInstall);
      ProductMetricService target = new ProductMetricService(mapper);
      PlatformTransactionManager transactionManager = mock(PlatformTransactionManager.class);
      when(transactionManager.getTransaction(any())).thenReturn(new SimpleTransactionStatus());
      ProxyFactory serviceFactory = new ProxyFactory(target);
      serviceFactory.setProxyTargetClass(true);
      serviceFactory.addAdvice(
          new TransactionInterceptor(
              transactionManager, new AnnotationTransactionAttributeSource()));
      service = (ProductMetricService) serviceFactory.getProxy();
      new ProductMetricPipelineLoadSupport(ProductMetricPipelineLoadSupport.Mode.BATCH1)
          .install(service);
      when(context.getBean(ProductMetricService.class)).thenReturn(service);
      when(context.getBean(ProductMetricMapper.class)).thenReturn(mapper);
      when(context.getBean(DataSource.class)).thenReturn(dataSource);
    }
  }
}
