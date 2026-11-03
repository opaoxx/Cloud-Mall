package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.product.config.ProductMetricBatchContainerFactory;
import com.cloudmall.product.util.ProductMetricBatchChannelRegistry;
import com.rabbitmq.client.Channel;
import java.lang.reflect.Array;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.LongSupplier;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.test.util.ReflectionTestUtils;

/**
 * Offline collector algorithm/identity tests; real framework/Consumer wiring is tested separately.
 */
class ProductMetricCallbackServiceObservationSupportTest {
  /** The actual pinned private framework interface supplies the invocation signature. */
  private static final Method CALLBACK_METHOD = callbackMethod();

  /** A test body may preserve a precise original Throwable, including Error. */
  @FunctionalInterface
  private interface Body {
    Object run() throws Throwable;
  }

  /** Controlled primitive elapsed-time clock, never a real nanoTime sampler. */
  private static final class Clock implements LongSupplier {
    /** Values supplied by the test, or a monotonic ten-nanosecond sequence. */
    private final long[] values;

    /** Number of attempted samples, also selecting fault injection. */
    private int calls;

    /** Optional exact sampling call that throws an Error. */
    private int failedCall = -1;

    /** Bind a fixed controlled sequence. */
    private Clock(long... values) {
      this.values = values;
    }

    /** Return a controlled value; no sleeping or real clock reads occur. */
    @Override
    public long getAsLong() {
      int selected = calls++;
      if (selected == failedCall) throw new AssertionError("SYNTHETIC_CLOCK_ERROR");
      return values.length == 0 ? selected * 10L : values[Math.min(selected, values.length - 1)];
    }
  }

  /** Stack-local tag management exercises the genuine old CallToken without mutating its fields. */
  private static final class Fixture {
    /** The original entry cohort, independent of wall clocks or framework threads. */
    private final AtomicReference<ProductMetricPipelineLoadSupport.Stage> stage =
        new AtomicReference<>(ProductMetricPipelineLoadSupport.Stage.MEASUREMENT);

    /** Controlled new collector clock. */
    private final Clock clock;

    /** Genuine old timing helper, used only to create and later consume each original tag. */
    private final ProductMetricApplicationTimingSupport original =
        new ProductMetricApplicationTimingSupport(
            "a".repeat(32) + "-batch1", ProductMetricPipelineLoadSupport.Mode.BATCH1, () -> 0L);

    /** Sole collector being tested. */
    private final ProductMetricCallbackServiceObservationSupport support;

    /** Native raw Channel identity; no wrapper is added. */
    private final Channel channel = mock(Channel.class);

    /** Actual message-list and parameter-array references are retained by the invocation. */
    private final Object[] arguments = {
      channel,
      List.of(
          new Message(new byte[0], new MessageProperties()),
          new Message(new byte[0], new MessageProperties()),
          new Message(new byte[0], new MessageProperties()),
          new Message(new byte[0], new MessageProperties()))
    };

    /** Create an enabled collector with no actual factory or resources. */
    private Fixture(long... values) {
      clock = new Clock(values);
      support = new ProductMetricCallbackServiceObservationSupport(clock, stage::get);
    }

    /** Create a synthetic invocation using the actual framework method descriptor. */
    private MethodInvocation invocation(Body body) throws Throwable {
      MethodInvocation invocation = mock(MethodInvocation.class);
      when(invocation.getMethod()).thenReturn(CALLBACK_METHOD);
      when(invocation.getArguments()).thenReturn(arguments);
      when(invocation.proceed()).thenAnswer(ignored -> body.run());
      return invocation;
    }

    /**
     * Run one unchanged callback proceed and verify its argument references and invocation count.
     */
    private Object callback(Body body) throws Throwable {
      MethodInvocation invocation = invocation(body);
      try {
        return support.callbackAdvice().invoke(invocation);
      } finally {
        verify(invocation, times(1)).proceed();
        assertSame(arguments, invocation.getArguments());
        assertSame(channel, arguments[0]);
      }
    }

    /** Enter/exit the new hook around one original tag, then return that tag to the old helper. */
    private void service(String operation, int size, boolean threw) {
      var entered = stage.get();
      var tag = original.serviceEnter(entered, operation, size);
      var token = support.serviceEnter(tag, entered, operation, size);
      support.serviceExit(token, tag, threw, stage.get());
      original.serviceExit(tag, threw, stage.get());
    }
  }

  /** Resolve only metadata from the already-present Spring Rabbit library. */
  private static Method callbackMethod() {
    try {
      return Class.forName(
              "org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer$ContainerDelegate")
          .getDeclaredMethod("invokeListener", Channel.class, Object.class);
    } catch (ReflectiveOperationException failure) {
      throw new ExceptionInInitializerError(failure);
    }
  }

  /** Read an independent cold map with its reviewed schema. */
  @SuppressWarnings("unchecked")
  private static Map<String, Object> section(Map<String, Object> snapshot, String name) {
    return (Map<String, Object>) snapshot.get(name);
  }

  /** Read fixed exported rows without mutating the collector. */
  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> rows(Map<String, Object> snapshot, String name) {
    return (List<Map<String, Object>>) snapshot.get(name);
  }

  /** Sum one nonnegative stat across the fixed matrix. */
  private static long total(Map<String, Object> snapshot, String matrix, String stat) {
    return rows(snapshot, matrix).stream()
        .mapToLong(row -> ((Number) section(row, "stats").get(stat)).longValue())
        .sum();
  }

  /** Read a fixed integer state counter. */
  private static long count(Map<String, Object> snapshot, String name) {
    return ((Number) section(snapshot, "collectorState").get(name)).longValue();
  }

  @Test
  void installFactoryRetainsActualCustomizerAndOnlyAddsItsUniqueAdvice() {
    Fixture fixture = new Fixture();
    var before = section(fixture.support.snapshot(), "runtimeIdentityFacts");
    assertNull(before.get("factoryClassName"));
    var factory = new ProductMetricBatchContainerFactory(new ProductMetricBatchChannelRegistry());
    factory.setContainerCustomizer(container -> container.setShutdownTimeout(15000));
    Object customizer = ReflectionTestUtils.getField(factory, "containerCustomizer");
    assertSame(fixture.support.callbackAdvice(), fixture.support.callbackAdvice());
    fixture.support.installFactory(factory);
    assertSame(customizer, ReflectionTestUtils.getField(factory, "containerCustomizer"));
    assertEquals(1, factory.getAdviceChain().length);
    assertSame(fixture.support.callbackAdvice(), factory.getAdviceChain()[0]);
    var facts = section(fixture.support.snapshot(), "runtimeIdentityFacts");
    assertEquals(System.identityHashCode(factory), facts.get("factoryIdentityHashCode"));
    assertEquals(true, facts.get("factoryCustomizerPreserved"));
    assertEquals(0, fixture.clock.calls);
    assertThrows(IllegalStateException.class, () -> fixture.support.installFactory(factory));
    var occupied = new ProductMetricBatchContainerFactory(new ProductMetricBatchChannelRegistry());
    MethodInterceptor existing = invocation -> invocation.proceed();
    occupied.setAdviceChain(existing);
    assertThrows(IllegalStateException.class, () -> new Fixture().support.installFactory(occupied));
    assertSame(existing, occupied.getAdviceChain()[0]);
  }

  @Test
  void singleAttemptPartitionsExactlyAndPreservesOriginalReturnArgumentsAndTag() throws Throwable {
    Fixture fixture = new Fixture(-100, -80, -30, -10);
    Object returned = new Object();
    assertSame(
        returned,
        fixture.callback(
            () -> {
              fixture.service("applyBatch", 4, false);
              return returned;
            }));
    var snapshot = fixture.support.snapshot();
    assertEquals(90, total(snapshot, "callbackCells", "sumNs"));
    assertEquals(50, total(snapshot, "attemptCells", "sumNs"));
    assertEquals(90, total(snapshot, "partitionCells", "sumNs"));
    assertEquals(3, total(snapshot, "partitionCells", "observations"));
    assertEquals("COMPLETE", section(snapshot, "qualification").get("partitionCoverage"));
    assertEquals(1, count(snapshot, "serviceAttemptsCompleted"));
    assertDoesNotThrow(fixture.support::assertHealthyAfterClose);
    assertNull(
        ReflectionTestUtils.getField(fixture.original, "currentFrame") == null
            ? null
            : ((ThreadLocal<?>) ReflectionTestUtils.getField(fixture.original, "currentFrame"))
                .get());
  }

  @Test
  void zeroAndMultipleAttemptsCanBeHealthyWithoutCompleteSinglePartitions() throws Throwable {
    for (int attempts : new int[] {0, 2, 15}) {
      Fixture fixture = new Fixture();
      fixture.callback(
          () -> {
            for (int ordinal = 0; ordinal < attempts; ordinal++)
              fixture.service(
                  ordinal < 3 ? "applyBatch" : "apply", ordinal < 3 ? 4 : 1, ordinal % 2 == 0);
            return null;
          });
      var snapshot = fixture.support.snapshot();
      assertEquals(attempts, total(snapshot, "attemptCells", "observations"));
      assertEquals(0, total(snapshot, "partitionCells", "observations"));
      assertEquals("UNAVAILABLE", section(snapshot, "qualification").get("partitionCoverage"));
      assertDoesNotThrow(fixture.support::assertHealthyAfterClose);
      assertEquals(0, count(snapshot, "activeServiceAttempts"));
      var cohort =
          rows(snapshot, "callbackCells").stream()
              .filter(row -> ((Number) section(row, "stats").get("observations")).longValue() != 0)
              .findFirst()
              .orElseThrow();
      assertEquals(1L, ((List<?>) cohort.get("attemptCountBuckets")).get(attempts));
    }
  }

  @Test
  void sixteenthAttemptIsUnknownWithoutGrowingSlotsOrDiscardingFirstFifteen() throws Throwable {
    Fixture fixture = new Fixture();
    fixture.callback(
        () -> {
          for (int ordinal = 0; ordinal < 16; ordinal++) fixture.service("apply", 1, false);
          return null;
        });
    var snapshot = fixture.support.snapshot();
    assertEquals(15, total(snapshot, "attemptCells", "observations"));
    assertEquals(1, count(snapshot, "attemptLimitExceeded"));
    assertEquals(0, total(snapshot, "partitionCells", "observations"));
    assertThrows(IllegalStateException.class, fixture.support::assertHealthyAfterClose);
    assertEquals(120, ((long[]) ReflectionTestUtils.getField(fixture.support, "slots")).length);
  }

  @Test
  void nativeErrorAndClockOrLookupFaultNeverReplaceOriginalThrowableOrRepeatProceed()
      throws Throwable {
    for (int failedClock = 0; failedClock < 4; failedClock++) {
      Fixture fixture = new Fixture();
      fixture.clock.failedCall = failedClock;
      AssertionError original = new AssertionError("ORIGINAL_NATIVE_ERROR");
      Throwable visible =
          assertThrows(
              Throwable.class,
              () ->
                  fixture.callback(
                      () -> {
                        fixture.service("applyBatch", 4, true);
                        throw original;
                      }));
      assertSame(original, visible);
      var snapshot = fixture.support.snapshot();
      assertEquals(0, count(snapshot, "framesOutstanding"));
      assertEquals(0, count(snapshot, "activeServiceAttempts"));
      assertFalse((Boolean) section(snapshot, "qualification").get("collectorHealthy"));
    }
    Fixture fixture = new Fixture();
    Object marker = new Object();
    MethodInvocation invocation = fixture.invocation(() -> marker);
    when(invocation.getMethod()).thenThrow(new AssertionError("OBSERVER_METHOD_LOOKUP"));
    assertSame(marker, fixture.support.callbackAdvice().invoke(invocation));
    verify(invocation, times(1)).proceed();
    assertEquals(0, fixture.clock.calls);
  }

  @Test
  void argumentAndStageGetterErrorsAreIsolatedFromNativeOutcome() throws Throwable {
    Fixture fixture = new Fixture();
    AssertionError original = new AssertionError("ORIGINAL_FINALLY_ERROR");
    MethodInvocation invocation =
        fixture.invocation(
            () -> {
              throw original;
            });
    when(invocation.getArguments()).thenThrow(new AssertionError("ARGUMENT_GETTER"));
    assertSame(
        original,
        assertThrows(Throwable.class, () -> fixture.support.callbackAdvice().invoke(invocation)));
    verify(invocation, times(1)).proceed();
    assertEquals(0, count(fixture.support.snapshot(), "framesOutstanding"));
    var support =
        new ProductMetricCallbackServiceObservationSupport(
            () -> 0L,
            () -> {
              throw new AssertionError("STAGE_GETTER");
            });
    MethodInvocation good = fixture.invocation(() -> null);
    assertNull(support.callbackAdvice().invoke(good));
    assertEquals(1, count(support.snapshot(), "unclassifiedCallbacks"));
    assertEquals(0, count(support.snapshot(), "framesOutstanding"));
  }

  @Test
  void nestedCallbackSuppressionSurvivesOriginalErrorAndNeverConsumesOuterSlot() throws Throwable {
    Fixture fixture = new Fixture();
    AssertionError original = new AssertionError("NESTED_ORIGINAL_ERROR");
    fixture.callback(
        () -> {
          MethodInvocation nested =
              fixture.invocation(
                  () -> {
                    fixture.service("apply", 1, false);
                    throw original;
                  });
          when(nested.getArguments()).thenThrow(new AssertionError("NESTED_LOOKUP_MUST_NOT_RUN"));
          assertSame(
              original,
              assertThrows(Throwable.class, () -> fixture.support.callbackAdvice().invoke(nested)));
          verify(nested, times(1)).getMethod();
          verify(nested, never()).getArguments();
          verify(nested, times(1)).proceed();
          assertEquals(0, count(fixture.support.snapshot(), "suppressionDepth"));
          fixture.service("applyBatch", 4, false);
          return null;
        });
    var snapshot = fixture.support.snapshot();
    assertEquals(1, count(snapshot, "nestedCallbacks"));
    assertEquals(1, count(snapshot, "suppressedNestedServiceCalls"));
    assertEquals(1, total(snapshot, "attemptCells", "observations"));
    assertEquals(0, total(snapshot, "partitionCells", "observations"));
    assertEquals(0, count(snapshot, "framesOutstanding"));
    assertThrows(IllegalStateException.class, fixture.support::assertHealthyAfterClose);
  }

  @Test
  void nonmatchingInvocationInsideActiveCallbackDoesNotTouchContextClockOrNestedCounters()
      throws Throwable {
    Fixture fixture = new Fixture();
    Object marker = new Object();
    AssertionError original = new AssertionError("NONMATCHING_ORIGINAL");
    fixture.callback(
        () -> {
          for (boolean throwsOriginal : new boolean[] {false, true}) {
            MethodInvocation other =
                fixture.invocation(
                    () -> {
                      if (throwsOriginal) throw original;
                      return marker;
                    });
            when(other.getMethod()).thenReturn(String.class.getMethod("length"));
            int samplesBefore = fixture.clock.calls;
            if (throwsOriginal)
              assertSame(
                  original,
                  assertThrows(
                      Throwable.class, () -> fixture.support.callbackAdvice().invoke(other)));
            else assertSame(marker, fixture.support.callbackAdvice().invoke(other));
            verify(other, times(1)).proceed();
            verify(other, never()).getArguments();
            assertEquals(samplesBefore, fixture.clock.calls);
            assertEquals(0, count(fixture.support.snapshot(), "nestedCallbacks"));
            assertEquals(0, count(fixture.support.snapshot(), "suppressionDepth"));
          }
          fixture.service("applyBatch", 4, false);
          return null;
        });
    var snapshot = fixture.support.snapshot();
    assertEquals(1, count(snapshot, "callbacksEntered"));
    assertEquals(1, count(snapshot, "callbacksCompleted"));
    assertEquals(3, total(snapshot, "partitionCells", "observations"));
    assertDoesNotThrow(fixture.support::assertHealthyAfterClose);
  }

  @Test
  void unknownMethodLookupInsideParentSuppressesChildWithoutInventingNestedIdentity()
      throws Throwable {
    Fixture fixture = new Fixture();
    Object marker = new Object();
    fixture.callback(
        () -> {
          MethodInvocation unknown =
              fixture.invocation(
                  () -> {
                    fixture.service("apply", 1, false);
                    return marker;
                  });
          when(unknown.getMethod()).thenThrow(new AssertionError("UNKNOWN_METHOD_GETTER"));
          assertSame(marker, fixture.support.callbackAdvice().invoke(unknown));
          verify(unknown, times(1)).proceed();
          assertEquals(0, count(fixture.support.snapshot(), "suppressionDepth"));
          fixture.service("applyBatch", 4, false);
          return null;
        });
    var snapshot = fixture.support.snapshot();
    assertEquals(1, count(snapshot, "callbacksEntered"));
    assertEquals(0, count(snapshot, "nestedCallbacks"));
    assertEquals(1, count(snapshot, "suppressedNestedServiceCalls"));
    assertEquals(1, total(snapshot, "attemptCells", "observations"));
    assertEquals(0, total(snapshot, "partitionCells", "observations"));
    assertThrows(IllegalStateException.class, fixture.support::assertHealthyAfterClose);
  }

  @Test
  void concurrentUnassociatedServiceInvalidatesActiveParentButLaterOutsideCallDoesNot()
      throws Throwable {
    Fixture fixture = new Fixture();
    AtomicReference<Throwable> childFailure = new AtomicReference<>();
    fixture.callback(
        () -> {
          Thread child =
              new Thread(
                  () -> {
                    try {
                      fixture.service("apply", 1, false);
                    } catch (Throwable failure) {
                      childFailure.set(failure);
                    }
                  });
          child.start();
          child.join(5000);
          assertFalse(child.isAlive());
          assertNull(childFailure.get());
          fixture.service("applyBatch", 4, false);
          return null;
        });
    fixture.service("apply", 1, false);
    var snapshot = fixture.support.snapshot();
    assertEquals(1, count(snapshot, "concurrentUnassociatedServiceCalls"));
    assertEquals(1, count(snapshot, "unpairedServiceCalls"));
    assertEquals(1, total(snapshot, "attemptCells", "observations"));
    assertEquals(0, total(snapshot, "partitionCells", "observations"));
    assertDoesNotThrow(fixture.support::assertHealthyAfterClose);
    Fixture afterParent = new Fixture();
    afterParent.callback(
        () -> {
          afterParent.service("applyBatch", 4, false);
          return null;
        });
    Thread later =
        new Thread(
            () -> {
              try {
                afterParent.service("apply", 1, false);
              } catch (Throwable failure) {
                childFailure.set(failure);
              }
            });
    later.start();
    later.join(5000);
    assertFalse(later.isAlive());
    assertNull(childFailure.get());
    assertEquals(1, count(afterParent.support.snapshot(), "unpairedServiceCalls"));
    assertEquals(0, count(afterParent.support.snapshot(), "concurrentUnassociatedServiceCalls"));
    assertEquals(
        "COMPLETE",
        section(afterParent.support.snapshot(), "qualification").get("partitionCoverage"));
  }

  @Test
  void wrongMissingDoubleAndMissingExitTokensAreStickyAndAlwaysReleaseCallbackFrame()
      throws Throwable {
    for (String fault : List.of("wrong", "missing", "double", "missing-exit")) {
      Fixture fixture = new Fixture();
      fixture.callback(
          () -> {
            var tag = fixture.original.serviceEnter(fixture.stage.get(), "applyBatch", 4);
            var token =
                fixture.support.serviceEnter(
                    "missing".equals(fault) ? null : tag, fixture.stage.get(), "applyBatch", 4);
            if (!"missing-exit".equals(fault)) {
              fixture.support.serviceExit(
                  token, "wrong".equals(fault) ? null : tag, false, fixture.stage.get());
              if ("double".equals(fault))
                fixture.support.serviceExit(token, tag, false, fixture.stage.get());
            }
            fixture.original.serviceExit(tag, false, fixture.stage.get());
            return null;
          });
      var snapshot = fixture.support.snapshot();
      assertTrue(count(snapshot, "tokenFailures") > 0);
      assertEquals(0, count(snapshot, "framesOutstanding"));
      assertEquals(0, count(snapshot, "activeServiceAttempts"));
      assertThrows(IllegalStateException.class, fixture.support::assertHealthyAfterClose);
    }
  }

  @Test
  void nestedServiceDoesNotReplaceOriginalActiveSlotAndZeroDurationIsValid() throws Throwable {
    Fixture fixture = new Fixture(0, 0, 0, 0);
    fixture.callback(
        () -> {
          var tag = fixture.original.serviceEnter(fixture.stage.get(), "applyBatch", 4);
          var outer = fixture.support.serviceEnter(tag, fixture.stage.get(), "applyBatch", 4);
          var nested = fixture.support.serviceEnter(tag, fixture.stage.get(), "apply", 1);
          fixture.support.serviceExit(nested, tag, false, fixture.stage.get());
          fixture.support.serviceExit(outer, tag, false, fixture.stage.get());
          fixture.original.serviceExit(tag, false, fixture.stage.get());
          return null;
        });
    assertEquals(1, count(fixture.support.snapshot(), "nestedServiceCalls"));
    assertEquals(1, total(fixture.support.snapshot(), "attemptCells", "observations"));
    Fixture valid = new Fixture(0, 0, 0, 0);
    valid.callback(
        () -> {
          valid.service("applyBatch", 4, false);
          return null;
        });
    assertEquals(0, total(valid.support.snapshot(), "callbackCells", "sumNs"));
    assertEquals(3, total(valid.support.snapshot(), "partitionCells", "timedCalls"));
    assertDoesNotThrow(valid.support::assertHealthyAfterClose);
  }

  @Test
  void stageCrossingPreservesEntryCohortsWhileKeepingHealthSeparateFromPartitionCoverage()
      throws Throwable {
    Fixture fixture = new Fixture();
    fixture.stage.set(ProductMetricPipelineLoadSupport.Stage.WARMUP);
    fixture.callback(
        () -> {
          fixture.stage.set(ProductMetricPipelineLoadSupport.Stage.MEASUREMENT);
          fixture.service("applyBatch", 4, false);
          fixture.stage.set(ProductMetricPipelineLoadSupport.Stage.FINISH_DRAIN);
          return null;
        });
    var snapshot = fixture.support.snapshot();
    assertEquals(1, total(snapshot, "attemptCells", "observations"));
    assertEquals(0, total(snapshot, "partitionCells", "observations"));
    var cohort =
        rows(snapshot, "callbackCells").stream()
            .filter(row -> ((Number) row.get("crossStageCalls")).longValue() != 0)
            .findFirst()
            .orElseThrow();
    assertEquals("WARMUP", cohort.get("entryStage"));
    assertDoesNotThrow(fixture.support::assertHealthyAfterClose);
  }

  @Test
  void backwardAndSubtractOverflowClocksRemainUnknownRatherThanInventingZero() throws Throwable {
    for (long[] values :
        new long[][] {{0, 10, 5, 20}, {Long.MIN_VALUE, -1, Long.MAX_VALUE, Long.MAX_VALUE}}) {
      Fixture fixture = new Fixture(values);
      fixture.callback(
          () -> {
            fixture.service("applyBatch", 4, false);
            return null;
          });
      var snapshot = fixture.support.snapshot();
      assertTrue(count(snapshot, "clockFailures") > 0);
      assertEquals(0, total(snapshot, "partitionCells", "observations"));
      assertThrows(IllegalStateException.class, fixture.support::assertHealthyAfterClose);
    }
  }

  @Test
  void checkedSumCountAndGenerationOverflowDoNotWrapOrChangeOriginalReturn() throws Throwable {
    for (String fault : List.of("sum", "count", "generation")) {
      Fixture fixture = new Fixture();
      if ("sum".equals(fault)) {
        long[][] cells = (long[][]) ReflectionTestUtils.getField(fixture.support, "callbackCells");
        long[] cell =
            cells[(ProductMetricPipelineLoadSupport.Stage.MEASUREMENT.ordinal() * 5 + 4) * 2];
        cell[0] = 1;
        cell[1] = 1;
        cell[2] = Long.MAX_VALUE;
        cell[3] = Long.MAX_VALUE;
        cell[17] = 1;
      } else if ("count".equals(fault))
        ((long[]) ReflectionTestUtils.getField(fixture.support, "counters"))[0] = Long.MAX_VALUE;
      else ReflectionTestUtils.setField(fixture.support, "generation", Long.MAX_VALUE);
      Object marker = new Object();
      assertSame(marker, fixture.callback(() -> marker));
      assertTrue(count(fixture.support.snapshot(), "counterOverflow") > 0);
      assertThrows(IllegalStateException.class, fixture.support::assertHealthyAfterClose);
      assertEquals(0, count(fixture.support.snapshot(), "framesOutstanding"));
    }
  }

  @Test
  void histogramInclusiveBoundsAndOverflowBucketAreExact() throws Throwable {
    long[] bounds = {
      100000,
      500000,
      1000000,
      2000000,
      5000000,
      10000000,
      20000000,
      50000000,
      100000000,
      200000000,
      500000000,
      1000000000,
      3000000000L,
      3000000001L
    };
    for (int selected = 0; selected < bounds.length; selected++) {
      long elapsed = bounds[selected];
      Fixture fixture = new Fixture(0, 0, elapsed, elapsed);
      fixture.callback(
          () -> {
            fixture.service("applyBatch", 4, true);
            return null;
          });
      var row =
          rows(fixture.support.snapshot(), "callbackCells").stream()
              .filter(
                  value -> ((Number) section(value, "stats").get("observations")).longValue() != 0)
              .findFirst()
              .orElseThrow();
      assertEquals(1L, ((List<?>) section(row, "stats").get("buckets")).get(selected));
      assertDoesNotThrow(fixture.support::assertHealthyAfterClose);
    }
  }

  @Test
  void snapshotIsClosedIndependentAndCoherentWhileOneServiceAttemptIsActive() throws Throwable {
    Fixture fixture = new Fixture();
    fixture.callback(
        () -> {
          var tag = fixture.original.serviceEnter(fixture.stage.get(), "applyBatch", 4);
          var token = fixture.support.serviceEnter(tag, fixture.stage.get(), "applyBatch", 4);
          var active = fixture.support.snapshot();
          assertEquals(1, count(active, "activeCallbacks"));
          assertEquals(1, count(active, "activeServiceAttempts"));
          assertEquals(0, count(active, "callbacksCompleted"));
          fixture.support.serviceExit(token, tag, false, fixture.stage.get());
          fixture.original.serviceExit(tag, false, fixture.stage.get());
          return null;
        });
    var snapshot = fixture.support.snapshot();
    assertEquals(
        Set.of(
            "schemaVersion",
            "scope",
            "enabled",
            "runtimeIdentityFacts",
            "collectorState",
            "histogramUpperBoundsNs",
            "callbackCells",
            "attemptCells",
            "partitionCells",
            "qualification"),
        snapshot.keySet());
    assertEquals(9, section(snapshot, "runtimeIdentityFacts").size());
    assertEquals(20, section(snapshot, "collectorState").size());
    assertEquals(6, section(snapshot, "qualification").size());
    assertEquals(70, rows(snapshot, "callbackCells").size());
    assertEquals(980, rows(snapshot, "attemptCells").size());
    assertEquals(210, rows(snapshot, "partitionCells").size());
    section(rows(snapshot, "callbackCells").get(0), "stats").put("observations", 1000L);
    assertEquals(1, total(fixture.support.snapshot(), "callbackCells", "observations"));
    assertFalse((Boolean) section(snapshot, "qualification").get("runtimeQualification"));
  }

  @Test
  void primitiveArraysStayFixedAndFramesRetainNoBusinessTokenOrChannelReferences()
      throws Throwable {
    Fixture fixture = new Fixture();
    long before = primitiveArrays(fixture.support);
    assertEquals(193824, before);
    assertTrue(before < 256 * 1024);
    for (int repetition = 0; repetition < 20; repetition++)
      fixture.callback(
          () -> {
            fixture.service("applyBatch", 4, false);
            return null;
          });
    assertEquals(before, primitiveArrays(fixture.support));
    for (Class<?> nested :
        ProductMetricCallbackServiceObservationSupport.class.getDeclaredClasses())
      if ("Frame".equals(nested.getSimpleName()))
        for (var field : nested.getDeclaredFields())
          assertTrue(field.getType().isPrimitive(), field.getName());
    assertEquals(20, count(fixture.support.snapshot(), "callbacksCompleted"));
  }

  /**
   * Count all primitive arrays (including the static bounds) without counting cold JSON/headers.
   */
  private static long primitiveArrays(Object support) throws IllegalAccessException {
    long total = 0;
    for (var field : support.getClass().getDeclaredFields()) {
      field.setAccessible(true);
      Object value = field.get(Modifier.isStatic(field.getModifiers()) ? null : support);
      if (value != null && value.getClass().isArray()) total += primitiveArrayBytes(value);
    }
    return total;
  }

  /** Recursively count only actual primitive payload, never object-reference array slots. */
  private static long primitiveArrayBytes(Object value) {
    Class<?> component = value.getClass().getComponentType();
    if (component == long.class) return Array.getLength(value) * 8L;
    if (component.isPrimitive()) throw new AssertionError("UNEXPECTED_PRIMITIVE_ARRAY");
    long total = 0;
    for (int index = 0; index < Array.getLength(value); index++) {
      Object nested = Array.get(value, index);
      if (nested != null && nested.getClass().isArray()) total += primitiveArrayBytes(nested);
    }
    return total;
  }
}
