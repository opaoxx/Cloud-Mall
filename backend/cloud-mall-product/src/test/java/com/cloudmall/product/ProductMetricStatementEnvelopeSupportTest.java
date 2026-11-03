package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.fasterxml.jackson.databind.ObjectMapper;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.PreparedStatement;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.jdbc.JdbcProperties;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** Real bounded collector, controlled Java clocks and original JDBC with resource-free leaves. */
class ProductMetricStatementEnvelopeSupportTest {
  /** Synthetic original owner-mode identity. */
  private static final String RUN = "0123456789abcdef0123456789abcdef-batch1";

  /** Default original Service entry attribution. */
  private static final ProductMetricPipelineLoadSupport.Stage MEASUREMENT =
      ProductMetricPipelineLoadSupport.Stage.MEASUREMENT;

  /** Original selected mode. */
  private static final ProductMetricPipelineLoadSupport.Mode MODE =
      ProductMetricPipelineLoadSupport.Mode.BATCH1;

  /** Exact admission property names, restored after each local capture matrix. */
  private static final List<String> PROPERTIES =
      List.of(
          ProductMetricStatementEnvelopeSupport.FLAG,
          "cloudmall.cm21.pipeline-batch",
          "cloudmall.cm21.metric-consumers",
          "cloudmall.cm21.phase-timing",
          "cloudmall.cm21.phase-anchor",
          "cloudmall.cm21.single10.application-timing",
          "cloudmall.cm21.single10.callback-service-observation",
          "cloudmall.cm21.single10.service-transaction-observation");

  @Test
  void missingAndFalseLeaveOriginalFactoryAndObservationDormant() {
    try (PropertiesScope properties = new PropertiesScope()) {
      for (String request : new String[] {null, "false"}) {
        properties.set(ProductMetricStatementEnvelopeSupport.FLAG, request);
        properties.set("cloudmall.cm21.pipeline-batch", "not-a-boolean");
        properties.set("cloudmall.cm21.metric-consumers", "not-an-integer");
        properties.set("cloudmall.cm21.single10.application-timing", "bad");
        var admission = ProductMetricStatementEnvelopeSupport.captureAdmission("legacy", 0, null);
        assertFalse(admission.enabled());
        assertEquals(request, admission.request());
        ProductMetricStatementEnvelopeSupport.requireContext(
            admission, "legacy", 0, null, false, 1, false, false);
      }
    }
    var reads = new AtomicInteger();
    var observation =
        collector(
            () -> {
              reads.incrementAndGet();
              throw new AssertionError("cold clock");
            },
            () -> MEASUREMENT);
    assertEquals(0, reads.get());
    observation.snapshot(ProductMetricStatementEnvelopeSupport.SnapshotKind.BASELINE);
    assertEquals(0, reads.get());
    assertEquals(
        1,
        ProductMetricStatementEnvelopeSupport.ObservedJdbcTemplate.class.getDeclaredMethods()
            .length);
    assertEquals(
        "update",
        ProductMetricStatementEnvelopeSupport.ObservedJdbcTemplate.class.getDeclaredMethods()[0]
            .getName());
  }

  @Test
  void strictFlagAndUnsupportedShapesFailBeforeClaimAndActors() {
    try (PropertiesScope properties = new PropertiesScope()) {
      properties.valid();
      for (String flag : List.of("", "TRUE", "False", " true", "false ", "1", "yes")) {
        properties.set(ProductMetricStatementEnvelopeSupport.FLAG, flag);
        assertThrows(
            IllegalStateException.class,
            () ->
                ProductMetricStatementEnvelopeSupport.captureAdmission("single10-load", 300, MODE));
      }
      properties.valid();
      assertThrows(
          IllegalStateException.class,
          () -> ProductMetricStatementEnvelopeSupport.captureAdmission("pipeline", 300, MODE));
      assertThrows(
          IllegalStateException.class,
          () -> ProductMetricStatementEnvelopeSupport.captureAdmission("single10-load", 299, MODE));
      assertThrows(
          IllegalStateException.class,
          () ->
              ProductMetricStatementEnvelopeSupport.captureAdmission(
                  "single10-load", 300, ProductMetricPipelineLoadSupport.Mode.SINGLE1));
      for (String property : PROPERTIES.subList(1, PROPERTIES.size())) {
        properties.valid();
        properties.set(
            property,
            property.endsWith("metric-consumers")
                ? "2"
                : property.endsWith("phase-timing") || property.endsWith("phase-anchor")
                    ? "true"
                    : "false");
        assertThrows(
            IllegalStateException.class,
            () ->
                ProductMetricStatementEnvelopeSupport.captureAdmission("single10-load", 300, MODE),
            property);
      }
      properties.valid();
      var admitted =
          ProductMetricStatementEnvelopeSupport.captureAdmission("single10-load", 300, MODE);
      assertThrows(
          IllegalStateException.class,
          () ->
              ProductMetricStatementEnvelopeSupport.requireContext(
                  admitted, "single10-load", 299, MODE, true, 1, false, false));
      assertThrows(
          IllegalStateException.class,
          () ->
              ProductMetricStatementEnvelopeSupport.requireContext(
                  admitted, "pipeline", 300, MODE, true, 1, false, false));
      assertThrows(
          IllegalStateException.class,
          () ->
              new ProductMetricStatementEnvelopeSupport.Admission(
                  false,
                  "true",
                  "single10-load",
                  300,
                  MODE,
                  true,
                  1,
                  false,
                  false,
                  true,
                  true,
                  true));
      assertThrows(
          IllegalStateException.class,
          () ->
              new ProductMetricStatementEnvelopeSupport.Admission(
                  true,
                  "true",
                  "single10-load",
                  300,
                  MODE,
                  true,
                  1,
                  false,
                  false,
                  true,
                  false,
                  true));
    }
  }

  @Test
  void capturedAdmissionSurvivesSystemPropertyMutation() {
    try (PropertiesScope properties = new PropertiesScope()) {
      properties.valid();
      var shape =
          ProductMetricQueryInfoCacheSupport.admit(
              "false", true, "single10-load", MODE, true, 1, 300, "false", "false");
      var admission = ProductMetricStatementEnvelopeSupport.captureAdmission(shape);
      for (String property : PROPERTIES) properties.set(property, "invalid-after-capture");
      assertTrue(admission.enabled());
      assertTrue(admission.applicationTiming());
      assertTrue(admission.callbackObservation());
      assertTrue(admission.transactionObservation());
      ProductMetricStatementEnvelopeSupport.requireContext(
          admission, "single10-load", 300, MODE, true, 1, false, false);
      assertEquals("false", shape.request());
      assertThrows(
          IllegalStateException.class,
          () ->
              ProductMetricStatementEnvelopeSupport.requireContext(
                  admission, "single10-load", 300, MODE, true, 2, false, false));
    }
  }

  @Test
  void normalPairSharesOneTokenAndExactFiveSegmentSum() throws Exception {
    Fixture fixture = new Fixture();
    var scope = fixture.begin();
    var first =
        fixture.observation.beforeTemplateCall(
            fixture.jdbc, ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL);
    fixture.observation.afterTemplateCall(first, false, 1);
    var second =
        fixture.observation.beforeTemplateCall(
            fixture.jdbc, ProductMetricStatementEnvelopeSupport.PROJECTION_INSERT_SQL);
    assertSame(
        ReflectionTestUtils.getField(scope, "frame"), ReflectionTestUtils.getField(first, "frame"));
    assertSame(
        ReflectionTestUtils.getField(first, "frame"),
        ReflectionTestUtils.getField(second, "frame"));
    fixture.observation.afterTemplateCall(second, false, 1);
    fixture.observation.afterMapperProceed(scope, false);
    assertEquals(1L, state(fixture).get("activeFrames"));
    assertEquals(0L, state(fixture).get("completedFrames"));
    fixture.observation.closeMapperScope(scope);
    Map<String, Object> cell = cell(fixture, false);
    assertEquals(1L, cell.get("normalFullPairs"));
    assertEquals(50L, map(cell.get("normalFullWholeStats")).get("sumNs"));
    long sum = 0;
    for (Object value : map(cell.get("segments")).values()) {
      Map<String, Object> stats = map(value);
      assertEquals(1L, stats.get("observations"));
      assertEquals(1L, stats.get("timedCalls"));
      assertEquals(10L, stats.get("sumNs"));
      sum += (long) stats.get("sumNs");
    }
    assertEquals(map(cell.get("normalFullWholeStats")).get("sumNs"), sum);
    assertEquals("COMPLETE", qualification(fixture).get("pairedFullCoverage"));
    assertConservation(fixture);
    // Actual stage changes between the two statements keep original entry attribution and no full
    // pair.
    for (int scenario = 0; scenario < 3; scenario++) {
      Fixture changed = new Fixture();
      var token = changed.begin();
      changed.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
      if (scenario == 0) changed.stage.set(ProductMetricPipelineLoadSupport.Stage.FINISH_DRAIN);
      else if (scenario == 1) changed.stage.set(null);
      else changed.stageFault.set(new AssertionError("stage leaf"));
      changed.jdbc.update(ProductMetricStatementEnvelopeSupport.PROJECTION_INSERT_SQL, 1);
      changed.end(token, false);
      assertEquals(2, changed.executions.get());
      assertEquals(0L, state(changed).get("normalFullPairs"));
      assertEquals(1L, state(changed).get("partialFrames"));
      assertEquals(scenario == 0 ? 1L : 0L, state(changed).get("crossStageFrames"));
      assertEquals("MEASUREMENT", cell(changed, false).get("entryStage"));
    }
  }

  @Test
  void projectionZeroAndOtherCountsRemainNormalFullPairs() throws Exception {
    for (int rows : new int[] {0, 1, 2, -7}) {
      Fixture fixture = new Fixture();
      fixture.projectionRows = rows;
      var scope = fixture.begin();
      fixture.pair();
      fixture.end(scope, false);
      assertEquals(1L, state(fixture).get("normalFullPairs"));
      Map<String, Object> returned =
          map(role(cell(fixture, false), "PROJECTION_INSERT").get("returnedRows"));
      assertEquals(1L, returned.get(rows == 0 ? "ZERO" : rows == 1 ? "ONE" : "OTHER"));
      assertEquals(2, fixture.executions.get());
      assertConservation(fixture);
    }
  }

  @Test
  void statNonOneEndsPartialWithoutProjectionSample() throws Exception {
    for (int rows : new int[] {0, 2, -1}) {
      Fixture fixture = new Fixture();
      fixture.statRows = rows;
      var scope = fixture.begin();
      assertEquals(
          rows, fixture.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1));
      fixture.end(scope, true);
      assertEquals(1L, state(fixture).get("partialFrames"));
      Map<String, Object> cell = cell(fixture, true);
      assertEquals(0L, role(cell, "PROJECTION_INSERT").get("entered"));
      assertEquals(
          0L, map(role(cell, "PROJECTION_INSERT").get("durationStats")).get("observations"));
      assertEquals(1L, role(cell, "STAT_UPDATE").get("returned"));
      assertEquals(1, fixture.executions.get());
      assertConservation(fixture);
    }
  }

  @Test
  void statThrowKeepsKnownEarlyStopAndMissingProjectionUnknown() throws Exception {
    Fixture fixture = new Fixture();
    RuntimeException primary =
        new IllegalArgumentException("original stat", new Exception("cause"));
    fixture.statFailure = primary;
    var scope = fixture.begin();
    assertSame(
        primary,
        assertThrows(
            RuntimeException.class,
            () -> fixture.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1)));
    fixture.end(scope, true);
    Map<String, Object> cell = cell(fixture, true);
    assertEquals(1L, cell.get("partialFrames"));
    assertEquals(1L, role(cell, "STAT_UPDATE").get("threw"));
    assertEquals(0L, role(cell, "PROJECTION_INSERT").get("entered"));
    assertEquals(0, primary.getSuppressed().length);
    assertEquals(1, fixture.executions.get());
    assertConservation(fixture);
  }

  @Test
  void projectionThrowIsCompleteAttemptNotNormalFullPair() throws Exception {
    Fixture fixture = new Fixture();
    RuntimeException primary = new IllegalStateException("original projection");
    fixture.projectionFailure = primary;
    var scope = fixture.begin();
    assertSame(primary, assertThrows(RuntimeException.class, fixture::pair));
    fixture.end(scope, true);
    Map<String, Object> cell = cell(fixture, true);
    assertEquals(1L, cell.get("completeThrownAttempts"));
    assertEquals(0L, cell.get("normalFullPairs"));
    assertEquals(0L, map(cell.get("normalFullWholeStats")).get("observations"));
    for (Object segment : map(cell.get("segments")).values())
      assertEquals(0L, map(segment).get("observations"));
    assertEquals(1L, role(cell, "PROJECTION_INSERT").get("threw"));
    assertEquals("PARTIAL", qualification(fixture).get("pairedFullCoverage"));
    assertConservation(fixture);
  }

  @Test
  void extraDuplicateAndReorderedTemplatesInvalidateButDelegateOnce() throws Exception {
    Fixture outside = new Fixture();
    outside.jdbc.update("UPDATE ordinary SET value=?", 1);
    assertEquals(0L, state(outside).get("skippedNoFrame"));
    outside.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
    assertEquals(1L, state(outside).get("skippedNoFrame"));
    assertEquals(2, outside.executions.get());
    for (int scenario = 0; scenario < 4; scenario++) {
      Fixture fixture = new Fixture();
      var scope = fixture.begin();
      if (scenario == 0) {
        fixture.jdbc.update(ProductMetricStatementEnvelopeSupport.PROJECTION_INSERT_SQL, 1);
        fixture.pair();
      } else if (scenario == 1) {
        fixture.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
        fixture.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
        fixture.jdbc.update(ProductMetricStatementEnvelopeSupport.PROJECTION_INSERT_SQL, 1);
      } else if (scenario == 2) {
        fixture.jdbc.update(" " + ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
        fixture.pair();
      } else {
        JdbcTemplate other = new JdbcTemplate(fixture.dataSource);
        assertNull(
            fixture.observation.beforeTemplateCall(
                other, ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL));
        other.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
        fixture.pair();
      }
      fixture.end(scope, false);
      assertEquals(3, fixture.executions.get());
      assertEquals(0L, state(fixture).get("normalFullPairs"));
      assertEquals(1L, state(fixture).get("partialFrames"));
      assertEquals(0L, state(fixture).get("activeFrames"));
    }
    for (boolean duplicateStart : new boolean[] {true, false}) {
      Fixture duplicate = new Fixture();
      var scope = duplicate.begin();
      if (duplicateStart) duplicate.observation.beforeMapperProceed(scope);
      duplicate.pair();
      duplicate.observation.afterMapperProceed(scope, false);
      if (!duplicateStart) duplicate.observation.afterMapperProceed(scope, true);
      duplicate.observation.closeMapperScope(scope);
      assertEquals(2, duplicate.executions.get());
      assertEquals(1L, state(duplicate).get("partialFrames"));
      assertEquals(0L, state(duplicate).get("normalFullPairs"));
      assertEquals("REENTRY_OR_SEQUENCE", state(duplicate).get("failureCode"));
      assertEquals(1L, cell(duplicate, false).get("completedFrames"));
      assertEquals(0L, cell(duplicate, true).get("completedFrames"));
    }
    // An unbound collector cannot associate two null references as its unique JDBC template.
    var unbound = collector(stepClock(), () -> MEASUREMENT);
    var unboundScope = unbound.openMapperScope(true, MEASUREMENT, 1, 4);
    unbound.beforeMapperProceed(unboundScope);
    assertNull(
        unbound.beforeTemplateCall(null, ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL));
    unbound.afterMapperProceed(unboundScope, false);
    unbound.closeMapperScope(unboundScope);
    Map<String, Object> unboundState =
        map(
            unbound
                .snapshot(ProductMetricStatementEnvelopeSupport.SnapshotKind.COMPLETION)
                .get("collectorState"));
    assertEquals("UNEXPECTED_TEMPLATE_OR_SQL", unboundState.get("failureCode"));
    assertEquals(1L, unboundState.get("unexpectedSqlOrTemplate"));
    assertEquals(1L, unboundState.get("partialFrames"));
    assertEquals(0L, unboundState.get("normalFullPairs"));
    assertEquals(0L, unboundState.get("activeFrames"));
    Fixture bound = new Fixture();
    var boundScope = bound.begin();
    assertNull(
        bound.observation.beforeTemplateCall(
            null, ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL));
    bound.pair();
    bound.end(boundScope, false);
    assertEquals(2, bound.executions.get());
    assertEquals(1L, state(bound).get("partialFrames"));
    assertEquals(0L, state(bound).get("normalFullPairs"));
  }

  @Test
  void nestedMapperIncludingUntimedBranchCannotPolluteOuterFrame() throws Exception {
    for (boolean timed : new boolean[] {true, false}) {
      Fixture fixture = new Fixture();
      var outer = fixture.begin();
      var nested =
          fixture.observation.openMapperScope(
              timed, timed ? MEASUREMENT : null, timed ? 1 : -1, timed ? 4 : 0);
      fixture.observation.beforeMapperProceed(nested);
      fixture.pair();
      fixture.observation.afterMapperProceed(nested, false);
      fixture.observation.closeMapperScope(nested);
      assertEquals(1L, state(fixture).get("activeFrames"));
      fixture.pair();
      fixture.end(outer, false);
      assertEquals(4, fixture.executions.get());
      assertEquals(1L, state(fixture).get("skippedNested"));
      assertEquals(1L, state(fixture).get("partialFrames"));
      assertEquals(0L, state(fixture).get("normalFullPairs"));
      assertEquals(1L, state(fixture).get("completedFrames"));
      assertEquals(0L, state(fixture).get("activeFrames"));
    }
  }

  @Test
  void nestedIdenticalSqlDuringRunningOrWaitPhaseIsPartial() throws Exception {
    for (int phase = 0; phase < 3; phase++) {
      Fixture fixture = new Fixture();
      var scope = fixture.begin();
      var stat =
          fixture.observation.beforeTemplateCall(
              fixture.jdbc, ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL);
      if (phase == 0) {
        fixture.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
        fixture.observation.afterTemplateCall(stat, false, 1);
      } else {
        fixture.observation.afterTemplateCall(stat, false, 1);
        if (phase == 1)
          fixture.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
      }
      var projection =
          fixture.observation.beforeTemplateCall(
              fixture.jdbc, ProductMetricStatementEnvelopeSupport.PROJECTION_INSERT_SQL);
      if (phase == 2)
        fixture.jdbc.update(ProductMetricStatementEnvelopeSupport.PROJECTION_INSERT_SQL, 1);
      fixture.observation.afterTemplateCall(projection, false, 1);
      fixture.end(scope, false);
      assertEquals(1, fixture.executions.get());
      assertEquals(0L, state(fixture).get("normalFullPairs"));
      assertEquals(1L, state(fixture).get("partialFrames"));
    }
    // Unknown actual marker stage is partial even with all six valid clocks and no reentry.
    Fixture unknown = new Fixture();
    var scope = unknown.begin();
    unknown.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
    unknown.stage.set(null);
    unknown.jdbc.update(ProductMetricStatementEnvelopeSupport.PROJECTION_INSERT_SQL, 1);
    unknown.end(scope, false);
    assertEquals(1L, state(unknown).get("partialFrames"));
    assertEquals(0L, state(unknown).get("crossStageFrames"));
  }

  @Test
  void wrongThreadNoFrameAndExplicitForeignEndHaveDistinctScopes() throws Exception {
    Fixture noFrame = new Fixture();
    Thread ordinary =
        new Thread(
            () -> {
              noFrame.jdbc.update("UPDATE ordinary SET value=?", 1);
              noFrame.jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
            });
    ordinary.start();
    ordinary.join(5000);
    assertFalse(ordinary.isAlive());
    assertEquals(1L, state(noFrame).get("skippedNoFrame"));
    assertEquals(0L, state(noFrame).get("invalidTokenOrThread"));
    Fixture foreign = new Fixture();
    var scope = foreign.begin();
    Thread wrong =
        new Thread(
            () -> {
              foreign.observation.afterMapperProceed(scope, false);
              foreign.observation.closeMapperScope(scope);
            });
    wrong.start();
    wrong.join(5000);
    assertFalse(wrong.isAlive());
    assertEquals(1L, state(foreign).get("activeFrames"));
    assertNotNull(
        ((ThreadLocal<?>) ReflectionTestUtils.getField(foreign.observation, "currentFrame")).get());
    foreign.pair();
    foreign.end(scope, false);
    assertEquals(0L, state(foreign).get("activeFrames"));
    assertEquals(0L, state(foreign).get("normalFullPairs"));
    assertEquals(2L, state(foreign).get("invalidTokenOrThread"));
  }

  @Test
  void globalSingleAdmissionCapSkipsExtraFrameWithoutRegistry() throws Exception {
    Fixture fixture = new Fixture();
    var scope = fixture.begin();
    AtomicReference<Throwable> failure = new AtomicReference<>();
    Thread extra =
        new Thread(
            () -> {
              try {
                var rejected = fixture.observation.openMapperScope(true, MEASUREMENT, 1, 4);
                assertNull(rejected);
                fixture.pair();
                fixture.observation.closeMapperScope(rejected);
              } catch (Throwable fault) {
                failure.set(fault);
              }
            });
    extra.start();
    extra.join(5000);
    assertFalse(extra.isAlive());
    assertNull(failure.get());
    assertEquals(1L, state(fixture).get("skippedCapacity"));
    assertEquals(2L, state(fixture).get("skippedNoFrame"));
    assertEquals(1L, state(fixture).get("activeFrames"));
    fixture.pair();
    fixture.end(scope, false);
    assertEquals(4, fixture.executions.get());
    assertEquals(1L, state(fixture).get("admittedFrames"));
    assertEquals("PARTIAL", qualification(fixture).get("pairedFullCoverage"));
    assertConservation(fixture);
  }

  @Test
  void clockInvalidWrapAndObserverFaultsKeepPrimaryUnmodified() throws Exception {
    AtomicLong wrapping = new AtomicLong(Long.MAX_VALUE - 20);
    Fixture wrap = new Fixture(() -> wrapping.getAndAdd(10));
    var wrapScope = wrap.begin();
    wrap.pair();
    wrap.end(wrapScope, false);
    assertEquals(1L, state(wrap).get("normalFullPairs"));
    assertEquals(50L, map(cell(wrap, false).get("normalFullWholeStats")).get("sumNs"));
    for (LongSupplier clock :
        List.<LongSupplier>of(
            new LongSupplier() {
              /** Number of controlled marker reads already made. */
              private int index;

              /** Return the next synthetic marker for this clock-boundary case. */
              @Override
              public long getAsLong() {
                return index++ == 3 ? -1 : index * 10L;
              }
            },
            () -> {
              throw new AssertionError("clock leaf");
            },
            new LongSupplier() {
              /** Number of controlled marker reads already made. */
              private int index;

              /** Return the next synthetic marker for this clock-boundary case. */
              @Override
              public long getAsLong() {
                return (long) (index++) * (Long.MAX_VALUE / 3);
              }
            })) {
      Fixture fixture = new Fixture(clock);
      var scope = fixture.begin();
      fixture.pair();
      fixture.end(scope, false);
      assertEquals(0L, state(fixture).get("normalFullPairs"));
      assertEquals("CLOCK_INVALID", state(fixture).get("failureCode"));
      assertEquals(0L, state(fixture).get("activeFrames"));
      assertEquals(2, fixture.executions.get());
    }
    for (String point :
        List.of(
            "beforeMapperProceed",
            "beforeTemplateCall",
            "afterTemplateCall",
            "afterMapperProceed",
            "commit",
            "closeMapperScope")) {
      Fixture fixture = new Fixture();
      RuntimeException primary =
          new IllegalStateException("primary", new Exception("original cause"));
      Throwable suppressed = new Exception("original suppressed");
      primary.addSuppressed(suppressed);
      fixture.projectionFailure = primary;
      fixture.observation.setObserverHook(
          actual -> {
            if (point.equals(actual)) throw new AssertionError("observer leaf");
          });
      var scope = fixture.begin();
      boolean threw = true;
      try {
        assertSame(primary, assertThrows(RuntimeException.class, fixture::pair));
      } finally {
        fixture.observation.afterMapperProceed(scope, threw);
        fixture.observation.closeMapperScope(scope);
      }
      assertSame(suppressed, primary.getSuppressed()[0]);
      assertEquals("original cause", primary.getCause().getMessage());
      assertEquals(1, primary.getSuppressed().length);
      assertEquals(2, fixture.executions.get());
      assertEquals(0L, state(fixture).get("activeFrames"));
      assertNotEquals("NONE", state(fixture).get("failureCode"));
      assertFalse((boolean) qualification(fixture).get("collectorHealthy"));
    }
    // A fault recorded after TLS removal must still exclude the whole atomic frame commit.
    for (boolean projectionThrows : new boolean[] {false, true}) {
      Fixture late = new Fixture();
      RuntimeException primary =
          new IllegalStateException("original projection after observer fault");
      if (projectionThrows) late.projectionFailure = primary;
      late.observation.setObserverHook(
          point -> {
            if ("commit".equals(point)) late.observation.recordObserverFailure();
          });
      var scope = late.begin();
      if (projectionThrows) assertSame(primary, assertThrows(RuntimeException.class, late::pair));
      else late.pair();
      late.end(scope, projectionThrows);
      assertEquals(2, late.executions.get());
      assertEquals(1L, state(late).get("completedFrames"));
      assertEquals(1L, state(late).get("partialFrames"));
      assertEquals(0L, state(late).get("normalFullPairs"));
      assertEquals(0L, state(late).get("completeThrownAttempts"));
      assertEquals("OBSERVER_FAILED", state(late).get("failureCode"));
      assertEquals(0L, state(late).get("activeFrames"));
      assertConservation(late);
    }
    // A cold snapshot fault on another thread has no origin TLS to invalidate directly.
    Fixture external = new Fixture();
    var scope = external.begin();
    external.pair();
    external.observation.setObserverHook(
        point -> {
          if ("snapshot".equals(point)) throw new AssertionError("external cold observer fault");
        });
    AtomicReference<Throwable> externalFailure = new AtomicReference<>();
    Thread coldReader =
        new Thread(
            () -> {
              try {
                Map<String, Object> live =
                    external.observation.snapshot(
                        ProductMetricStatementEnvelopeSupport.SnapshotKind.MEASUREMENT_ENDED);
                assertEquals(1L, map(live.get("collectorState")).get("activeFrames"));
                assertEquals(0L, map(live.get("collectorState")).get("completedFrames"));
              } catch (Throwable fault) {
                externalFailure.set(fault);
              }
            });
    coldReader.start();
    coldReader.join(5000);
    assertFalse(coldReader.isAlive());
    assertNull(externalFailure.get());
    external.observation.setObserverHook(null);
    external.end(scope, false);
    assertEquals(2, external.executions.get());
    assertEquals(0L, state(external).get("normalFullPairs"));
    assertEquals(1L, state(external).get("partialFrames"));
    assertEquals("OBSERVER_FAILED", state(external).get("failureCode"));
    assertConservation(external);
  }

  @Test
  void snapshotCommitAndActiveReleaseAreAtomicWithCheckedOverflow() throws Exception {
    Fixture fixture = new Fixture();
    CountDownLatch atCommit = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    AtomicReference<Throwable> failure = new AtomicReference<>();
    fixture.observation.setObserverHook(
        point -> {
          if ("commit".equals(point)) {
            atCommit.countDown();
            try {
              if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("release timeout");
            } catch (InterruptedException fault) {
              throw new AssertionError(fault);
            }
          }
        });
    Thread owner =
        new Thread(
            () -> {
              try {
                var scope = fixture.begin();
                fixture.pair();
                fixture.end(scope, false);
              } catch (Throwable fault) {
                failure.set(fault);
              }
            });
    owner.start();
    assertTrue(atCommit.await(5, TimeUnit.SECONDS));
    for (int index = 0; index < 20; index++) {
      Map<String, Object> snapshot =
          fixture.observation.snapshot(
              ProductMetricStatementEnvelopeSupport.SnapshotKind.COMPLETION);
      assertEquals(1L, map(snapshot.get("collectorState")).get("activeFrames"));
      assertEquals(0L, map(snapshot.get("collectorState")).get("completedFrames"));
      assertFalse((boolean) map(snapshot.get("qualification")).get("closedQuiescent"));
      for (Object row : list(snapshot.get("cells")))
        for (Object role : map(map(row).get("statementRoles")).values())
          assertEquals(0L, map(role).get("entered"));
    }
    release.countDown();
    owner.join(5000);
    assertFalse(owner.isAlive());
    assertNull(failure.get());
    assertConservation(fixture);
    assertEquals(0L, state(fixture).get("activeFrames"));
    assertEquals(1L, state(fixture).get("normalFullPairs"));
    assertTrue((boolean) qualification(fixture).get("closedQuiescent"));
    Fixture overflow = new Fixture();
    Object[] cells = (Object[]) ReflectionTestUtils.getField(overflow.observation, "cells");
    int coordinate = ((MEASUREMENT.ordinal() * 2 + 1) * 4 + 3) * 2;
    Object whole = ReflectionTestUtils.getField(cells[coordinate], "whole");
    ReflectionTestUtils.setField(whole, "sum", Long.MAX_VALUE);
    var before = cell(overflow, false);
    var scope = overflow.begin();
    overflow.pair();
    overflow.end(scope, false);
    assertEquals("COUNTER_OVERFLOW", state(overflow).get("failureCode"));
    assertEquals(0L, state(overflow).get("activeFrames"));
    assertEquals(0L, state(overflow).get("completedFrames"));
    assertEquals(
        new ObjectMapper().valueToTree(before.get("wholeMapperStats")),
        new ObjectMapper().valueToTree(cell(overflow, false).get("wholeMapperStats")));
    assertEquals(0L, role(cell(overflow, false), "STAT_UPDATE").get("entered"));
    assertNull(
        ((ThreadLocal<?>) ReflectionTestUtils.getField(overflow.observation, "currentFrame"))
            .get());
  }

  @Test
  void fourSnapshotSchemaCoverageIsBoundedAndPublicationColdFailuresDoNotEscape() throws Exception {
    Fixture fixture = new Fixture();
    var scope = fixture.begin();
    fixture.pair();
    fixture.end(scope, false);
    AtomicInteger clockReads = new AtomicInteger();
    fixture.observation.setObserverHook(
        point -> {
          if ("snapshot".equals(point)) clockReads.incrementAndGet();
        });
    Path directory = Files.createTempDirectory("cm21-statement-envelope-");
    try {
      for (var kind : ProductMetricStatementEnvelopeSupport.SnapshotKind.values())
        fixture.observation.publish(directory, kind, "owner", "batch1", RUN, "runtime");
      assertEquals(4, clockReads.get());
      Set<String> filenames = new HashSet<>();
      try (var stream = Files.list(directory)) {
        stream.forEach(file -> filenames.add(file.getFileName().toString()));
      }
      assertEquals(
          Set.of(
              "baseline-10.statement-envelope.json",
              "measurement-ended.statement-envelope.json",
              "finish-10.statement-envelope.json",
              "completion.statement-envelope.json"),
          filenames);
      for (String filename : filenames) {
        Map<String, Object> envelope =
            new ObjectMapper()
                .readValue(Files.readAllBytes(directory.resolve(filename)), Map.class);
        assertEquals(
            Set.of(
                "schemaVersion",
                "scope",
                "kind",
                "owner",
                "mode",
                "runIdentity",
                "runtimeIdentity",
                "enabled",
                "observation"),
            envelope.keySet());
        assertEquals("TEST_ONLY_STATEMENT_ENVELOPE_V1", envelope.get("scope"));
        assertEquals(RUN, envelope.get("runIdentity"));
        assertEquals(true, envelope.get("enabled"));
        assertSchema(map(envelope.get("observation")));
      }
      byte[] original = Files.readAllBytes(directory.resolve("completion.statement-envelope.json"));
      assertDoesNotThrow(
          () ->
              fixture.observation.publish(
                  directory,
                  ProductMetricStatementEnvelopeSupport.SnapshotKind.COMPLETION,
                  "owner",
                  "batch1",
                  RUN,
                  "runtime"));
      assertArrayEquals(
          original, Files.readAllBytes(directory.resolve("completion.statement-envelope.json")));
      assertEquals("SIDECAR_PUBLICATION", state(fixture).get("failureCode"));
      Map<String, Object> mutableFacts = map(snapshot(fixture).get("runtimeFacts"));
      map(mutableFacts.get("loggerEnablement")).put("debug", "tampered");
      map(mutableFacts.get("sqlRoleSha256")).put("STAT_UPDATE", "tampered");
      Map<String, Object> freshFacts = map(snapshot(fixture).get("runtimeFacts"));
      assertEquals(false, map(freshFacts.get("loggerEnablement")).get("debug"));
      assertEquals(
          "653f7fbc406d2e3cb2243cc5d012de5b3335f649008f36d8024e387c0e4e5ab5",
          map(freshFacts.get("sqlRoleSha256")).get("STAT_UPDATE"));
      Fixture missing = new Fixture();
      assertDoesNotThrow(
          () ->
              missing.observation.publish(
                  directory.resolve("missing"),
                  ProductMetricStatementEnvelopeSupport.SnapshotKind.BASELINE,
                  "owner",
                  "batch1",
                  RUN,
                  "runtime"));
      assertEquals("SIDECAR_PUBLICATION", state(missing).get("failureCode"));
      assertFalse(Files.exists(directory.resolve("missing")));
      Fixture exportFault = new Fixture();
      exportFault.observation.setObserverHook(
          point -> {
            if ("snapshot".equals(point)) throw new AssertionError("cold observer");
          });
      assertDoesNotThrow(
          () ->
              exportFault.observation.snapshot(
                  ProductMetricStatementEnvelopeSupport.SnapshotKind.COMPLETION));
      assertEquals("OBSERVER_FAILED", state(exportFault).get("failureCode"));
    } finally {
      try (var stream = Files.list(directory)) {
        for (Path file : stream.toList()) Files.delete(file);
      }
      Files.delete(directory);
    }
  }

  /** Deterministic ten-nanosecond marker increments. */
  private static LongSupplier stepClock() {
    AtomicLong ticks = new AtomicLong();
    return () -> ticks.getAndAdd(10);
  }

  /** Build only enabled primitive state; no Spring context or resource actors. */
  private static ProductMetricStatementEnvelopeSupport collector(
      LongSupplier clock,
      java.util.function.Supplier<ProductMetricPipelineLoadSupport.Stage> stage) {
    return new ProductMetricStatementEnvelopeSupport(RUN, MODE, clock, stage);
  }

  /** Completion snapshot is an independent cold read. */
  private static Map<String, Object> snapshot(Fixture fixture) {
    return fixture.observation.snapshot(
        ProductMetricStatementEnvelopeSupport.SnapshotKind.COMPLETION);
  }

  /** Fixed collector-state projection. */
  private static Map<String, Object> state(Fixture fixture) {
    return map(snapshot(fixture).get("collectorState"));
  }

  /** Fixed qualification projection. */
  private static Map<String, Object> qualification(Fixture fixture) {
    return map(snapshot(fixture).get("qualification"));
  }

  /** Select the one known entry coordinate, never infer a live outcome. */
  private static Map<String, Object> cell(Fixture fixture, boolean threw) {
    return list(snapshot(fixture).get("cells")).stream()
        .map(ProductMetricStatementEnvelopeSupportTest::map)
        .filter(
            row ->
                row.get("entryStage").equals("MEASUREMENT")
                    && row.get("operation").equals("applyBatch")
                    && row.get("inputSize").equals(4)
                    && row.get("mapperOutcome").equals(threw ? "THREW" : "NORMAL"))
        .findFirst()
        .orElseThrow();
  }

  /** Select one fixed role. */
  private static Map<String, Object> role(Map<String, Object> cell, String role) {
    return map(map(cell.get("statementRoles")).get(role));
  }

  /** Cast cold schema maps with one bounded helper. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> map(Object value) {
    return (Map<String, Object>) value;
  }

  /** Cast cold schema lists with one bounded helper. */
  @SuppressWarnings("unchecked")
  private static List<Object> list(Object value) {
    return (List<Object>) value;
  }

  /** Verify healthy count conservation and all finalized role identities. */
  private static void assertConservation(Fixture fixture) {
    Map<String, Object> state = state(fixture);
    assertEquals(
        (long) state.get("admittedFrames"),
        (long) state.get("completedFrames") + (long) state.get("activeFrames"));
    assertEquals(
        (long) state.get("completedFrames"),
        (long) state.get("normalFullPairs")
            + (long) state.get("completeThrownAttempts")
            + (long) state.get("partialFrames"));
    for (Object item : list(snapshot(fixture).get("cells")))
      for (Object role : map(map(item).get("statementRoles")).values()) {
        Map<String, Object> values = map(role);
        assertEquals(0L, values.get("inFlight"));
        assertEquals(
            (long) values.get("entered"),
            (long) values.get("returned") + (long) values.get("threw"));
      }
  }

  /** Exact schema and all bounded coordinates are independent of event cardinality. */
  private static void assertSchema(Map<String, Object> observation) {
    assertEquals(
        Set.of(
            "markerNames",
            "segmentNames",
            "histogramUpperBoundsNs",
            "runtimeFacts",
            "collectorState",
            "cells",
            "qualification"),
        observation.keySet());
    assertEquals(112, list(observation.get("cells")).size());
    Set<List<Object>> coordinates = new HashSet<>();
    for (Object row : list(observation.get("cells"))) {
      Map<String, Object> cell = map(row);
      assertEquals(
          Set.of(
              "entryStage",
              "operation",
              "inputSize",
              "mapperOutcome",
              "completedFrames",
              "normalFullPairs",
              "completeThrownAttempts",
              "partialFrames",
              "crossStageFrames",
              "wholeMapperStats",
              "normalFullWholeStats",
              "segments",
              "statementRoles"),
          cell.keySet());
      assertTrue(
          coordinates.add(
              List.of(
                  cell.get("entryStage"),
                  cell.get("operation"),
                  cell.get("inputSize"),
                  cell.get("mapperOutcome"))));
      assertEquals(
          Set.of("PRE", "STAT_CALL", "GAP", "PROJECTION_CALL", "POST"),
          map(cell.get("segments")).keySet());
      assertEquals(
          Set.of("STAT_UPDATE", "PROJECTION_INSERT"), map(cell.get("statementRoles")).keySet());
      assertEquals(
          Set.of("observations", "timedCalls", "sumNs", "maxNs", "buckets"),
          map(cell.get("wholeMapperStats")).keySet());
      for (Object role : map(cell.get("statementRoles")).values()) {
        assertEquals(
            Set.of("entered", "returned", "threw", "inFlight", "returnedRows", "durationStats"),
            map(role).keySet());
        assertEquals(Set.of("ZERO", "ONE", "OTHER"), map(map(role).get("returnedRows")).keySet());
      }
    }
    assertEquals(
        Set.of(
            "failureCode",
            "activeFrames",
            "admittedFrames",
            "completedFrames",
            "normalFullPairs",
            "completeThrownAttempts",
            "partialFrames",
            "crossStageFrames",
            "skippedNoFrame",
            "skippedNested",
            "skippedCapacity",
            "skippedAfterFailure",
            "unexpectedSqlOrTemplate",
            "invalidSequence",
            "invalidTokenOrThread",
            "clockFailures",
            "counterOverflow",
            "observerFailures"),
        map(observation.get("collectorState")).keySet());
    assertEquals(
        Set.of(
            "runtimeVerified",
            "templateClassName",
            "templateBeanName",
            "templateDataSourceSame",
            "mapperTemplateSame",
            "mapperDataSourceSame",
            "transactionManagerDataSourceSame",
            "baseDataSourceUnchanged",
            "additionalDataSourceCreated",
            "transactionManagerAdded",
            "jdbcTemplateProxyAdded",
            "overriddenMethod",
            "sqlRoleSha256",
            "fetchSize",
            "maxRows",
            "queryTimeout",
            "ignoreWarnings",
            "loggerEnablementSame",
            "loggerEnablement"),
        map(observation.get("runtimeFacts")).keySet());
    assertEquals(
        Set.of("trace", "debug", "info", "warn", "error", "fatal"),
        map(map(observation.get("runtimeFacts")).get("loggerEnablement")).keySet());
    Map<String, Object> qualification = map(observation.get("qualification"));
    assertEquals(
        Set.of(
            "collectorHealthy",
            "pairedFullCoverage",
            "closedQuiescent",
            "runtimeQualification",
            "serverSqlMeasured",
            "commitMeasured",
            "physicalAckMeasured",
            "observerOverheadUnknown"),
        qualification.keySet());
    for (String field :
        List.of(
            "runtimeQualification", "serverSqlMeasured", "commitMeasured", "physicalAckMeasured"))
      assertEquals(false, qualification.get(field));
    assertEquals(true, qualification.get("observerOverheadUnknown"));
  }

  /** Restore global properties exactly, including original absence. */
  private static final class PropertiesScope implements AutoCloseable {
    /** Original values, including null. */
    private final Map<String, String> originals = new LinkedHashMap<>();

    /** Preserve before any mutation. */
    private PropertiesScope() {
      for (String property : PROPERTIES) originals.put(property, System.getProperty(property));
    }

    /** Assign one exact property value or remove it. */
    private void set(String property, String value) {
      if (value == null) System.clearProperty(property);
      else System.setProperty(property, value);
    }

    /** Enable exactly the reviewed Single10 batch1 shape. */
    private void valid() {
      set(ProductMetricStatementEnvelopeSupport.FLAG, "true");
      set("cloudmall.cm21.pipeline-batch", "true");
      set("cloudmall.cm21.metric-consumers", "1");
      set("cloudmall.cm21.phase-timing", "false");
      set("cloudmall.cm21.phase-anchor", "false");
      set("cloudmall.cm21.single10.application-timing", "true");
      set("cloudmall.cm21.single10.callback-service-observation", "true");
      set("cloudmall.cm21.single10.service-transaction-observation", "true");
    }

    /** Restore all original property values. */
    @Override
    public void close() {
      originals.forEach(this::set);
    }
  }

  /**
   * Original Spring JdbcTemplate delegates to controlled JDBC leaves; no connection actor exists.
   */
  private static final class Fixture {
    /** Actual stage read at each marker. */
    private final AtomicReference<ProductMetricPipelineLoadSupport.Stage> stage =
        new AtomicReference<>(MEASUREMENT);

    /** Optional stage-supplier failure leaf. */
    private final AtomicReference<Error> stageFault = new AtomicReference<>();

    /** Actual JDBC executeUpdate calls. */
    private final AtomicInteger executions = new AtomicInteger();

    /** Fake original datasource leaf, never a socket or pool. */
    private final DataSource dataSource = mock(DataSource.class);

    /** Real collector under test. */
    private final ProductMetricStatementEnvelopeSupport observation;

    /** Real exact-overload subclass delegating to original Spring. */
    private final JdbcTemplate jdbc;

    /** Controlled stat leaf return. */
    private int statRows = 1;

    /** Controlled projection leaf return. */
    private int projectionRows = 1;

    /** Controlled original stat failure object. */
    private RuntimeException statFailure;

    /** Controlled original projection failure object. */
    private RuntimeException projectionFailure;

    /** Default strictly increasing Java clock. */
    private Fixture() throws Exception {
      this(stepClock());
    }

    /** Construct original JDBC path over only controlled resource leaves. */
    private Fixture(LongSupplier suppliedClock) throws Exception {
      LongSupplier clock = suppliedClock;
      observation =
          collector(
              clock,
              () -> {
                Error fault = stageFault.get();
                if (fault != null) throw fault;
                return stage.get();
              });
      Connection connection = mock(Connection.class);
      when(dataSource.getConnection()).thenReturn(connection);
      when(connection.prepareStatement(anyString()))
          .thenAnswer(
              invocation -> {
                String sql = invocation.getArgument(0);
                PreparedStatement statement = mock(PreparedStatement.class);
                when(statement.executeUpdate())
                    .thenAnswer(
                        execute -> {
                          executions.incrementAndGet();
                          boolean stat =
                              ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL.equals(sql);
                          RuntimeException failure = stat ? statFailure : projectionFailure;
                          if (failure != null) throw failure;
                          return stat ? statRows : projectionRows;
                        });
                return statement;
              });
      jdbc =
          ProductMetricStatementEnvelopeSupport.createTemplate(
              dataSource, new JdbcProperties(), observation);
      verify(dataSource, never()).getConnection();
    }

    /** Enter the one supported semantic coordinate and own M0. */
    private ProductMetricStatementEnvelopeSupport.ScopeToken begin() {
      var scope = observation.openMapperScope(true, MEASUREMENT, 1, 4);
      observation.beforeMapperProceed(scope);
      return scope;
    }

    /** Execute the two original Spring overloads once each. */
    private void pair() {
      jdbc.update(ProductMetricStatementEnvelopeSupport.STAT_UPDATE_SQL, 1);
      jdbc.update(ProductMetricStatementEnvelopeSupport.PROJECTION_INSERT_SQL, 1);
    }

    /** Capture original Mapper outcome and release the creating scope. */
    private void end(ProductMetricStatementEnvelopeSupport.ScopeToken scope, boolean threw) {
      observation.afterMapperProceed(scope, threw);
      observation.closeMapperScope(scope);
    }
  }
}
