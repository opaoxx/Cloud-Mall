package com.cloudmall.product;

import com.cloudmall.product.config.ProductMetricBatchContainerFactory;
import com.rabbitmq.client.Channel;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import org.aopalliance.aop.Advice;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.amqp.rabbit.config.SimpleRabbitListenerContainerFactory;
import org.springframework.test.util.ReflectionTestUtils;

/** Test-only fixed-space callback/Service correlation; never an ACK, commit or runtime proof. */
final class ProductMetricCallbackServiceObservationSupport {
  /** Stages are the original seven entry cohorts and are never reset. */
  private static final ProductMetricPipelineLoadSupport.Stage[] STAGES =
      ProductMetricPipelineLoadSupport.Stage.values();

  /** Inclusive duration bounds, followed by an overflow bucket. */
  private static final long[] UPPER_BOUNDS = {
    100000L,
    500000L,
    1000000L,
    2000000L,
    5000000L,
    10000000L,
    20000000L,
    50000000L,
    100000000L,
    200000000L,
    500000000L,
    1000000000L,
    3000000000L
  };

  /** Fixed cell widths and maximum admitted attempts. */
  private static final int CELL_WIDTH = 18, ATTEMPT_LIMIT = 15, SLOT_WIDTH = 8;

  /** Primitive slot offsets: timestamps, stages, operation, size, flags and outcome. */
  private static final int START = 0,
      END = 1,
      ENTRY_STAGE = 2,
      EXIT_STAGE = 3,
      OPERATION = 4,
      SIZE = 5,
      FLAGS = 6,
      OUTCOME = 7;

  /** Slot validity flags; no reference or business object is stored in slots. */
  private static final int START_VALID = 1, END_VALID = 2, CLOSED = 4, MATCHED = 8;

  /** Fixed global counters; active state is derived from the sole owned frame. */
  private static final int CALLBACK_ENTERED = 0,
      CALLBACK_COMPLETED = 1,
      CLASSIFIED = 2,
      UNCLASSIFIED = 3,
      SERVICE_ENTERED = 4,
      SERVICE_COMPLETED = 5,
      UNPAIRED = 6,
      SUPPRESSED = 7,
      CONCURRENT = 8,
      NESTED_CALLBACK = 9,
      NESTED_SERVICE = 10,
      LIMIT_EXCEEDED = 11,
      COUNTER_OVERFLOW = 12,
      CLOCK_FAILURE = 13,
      TOKEN_FAILURE = 14;

  /** Names of the three eligible single-attempt intervals. */
  private static final String[] SPANS = {
    "PRE_OBSERVED_APPLY", "OBSERVED_APPLY_PROCEED", "POST_OBSERVED_APPLY"
  };

  /** Callback and Service use exactly this clock instance outside aggregate locks. */
  private final LongSupplier clock;

  /** Original stage accessor, called only outside aggregate locks. */
  private final Supplier<ProductMetricPipelineLoadSupport.Stage> stage;

  /** One fixed callback advice, installed only in the enabled test factory. */
  private final MethodInterceptor callback = this::invokeCallback;

  /** Per-thread reference to the sole admitted primitive frame, removed in callback finally. */
  private final ThreadLocal<Frame> current = new ThreadLocal<>();

  /** 70 callback cells of observations/timed/sum/max/fourteen histogram buckets. */
  private final long[][] callbackCells = new long[70][CELL_WIDTH];

  /** 980 callback-stage/Service-stage/operation/size/outcome cells. */
  private final long[][] attemptCells = new long[980][CELL_WIDTH];

  /** 210 callback-cohort/partition-span cells, populated only by eligible single attempts. */
  private final long[][] partitionCells = new long[210][CELL_WIDTH];

  /** Seventeen count buckets per callback cohort, including overflow. */
  private final long[][] attemptCounts = new long[70][17];

  /** Per-cohort partitioned, unknown and cross-stage counts. */
  private final long[][] partitions = new long[70][3];

  /** Exactly fifteen reusable primitive slots, independent of callback/event count. */
  private final long[] slots = new long[ATTEMPT_LIMIT * SLOT_WIDTH];

  /** Fifteen fixed counters, without per-call or per-thread history. */
  private final long[] counters = new long[15];

  /** The single active owner; a reference does not infer cross-thread causality. */
  private Frame activeOwner;

  /** Checked process-local generation prevents stale token reuse after slot reset. */
  private long generation;

  /** First fixed observation failure, separate from legitimate partial partition coverage. */
  private String failure = "NONE";

  /** Actual cold factory facts; no listener/raw-channel identity is inferred from these. */
  private String factoryClass;

  /** Identity hash of the installed original factory, not an independent identity proof. */
  private int factoryIdentity;

  /** Whether the unique cold install and before/after customizer proof completed. */
  private boolean factoryInstalled;

  /** Short-lived identity capability; only the Service stack retains the original timing tag. */
  static final class AttemptToken {
    /** Owning collector, preventing foreign capability consumption. */
    private final ProductMetricCallbackServiceObservationSupport owner;

    /** Old token is an identity tag only and is never consumed or mutated here. */
    private final ProductMetricApplicationTimingSupport.CallToken original;

    /** Owning primitive generation and ordinal; negative slot means excluded observation. */
    private final long generation;

    /** Fixed attempt slot, or -1 when no callback association was admitted. */
    private final int slot;

    /** Single-use state, retained only by this stack capability. */
    private boolean closed;

    /** Bind an identity tag without putting it in the frame or aggregate arrays. */
    private AttemptToken(
        ProductMetricCallbackServiceObservationSupport owner,
        ProductMetricApplicationTimingSupport.CallToken original,
        long generation,
        int slot) {
      this.owner = owner;
      this.original = original;
      this.generation = generation;
      this.slot = slot;
    }
  }

  /** One active primitive-only frame; message/channel/token/Throwable references are forbidden. */
  private static final class Frame {
    /** Checked callback generation. */
    private long generation;

    /** Original arbitrary-origin callback start. */
    private long started;

    /** Original entry stage, or -1 if the accessor failed. */
    private int entryStage;

    /** Raw callback position count, with zero denoting UNKNOWN. */
    private int inputSize;

    /** Admitted attempt count, saturated at sixteen to denote overflow. */
    private int attempts;

    /** Single in-flight Service slot, or -1. */
    private int activeSlot = -1;

    /** Nested suppression depth, restored independently of stage/clock success. */
    private int suppressionDepth;

    /** Whether the callback start clock was valid. */
    private boolean clockValid;

    /** Sticky loss of single-partition eligibility. */
    private boolean partitionInvalid;
  }

  /** Allocate fixed enabled-only state without sampling a clock or installing a factory. */
  ProductMetricCallbackServiceObservationSupport(
      LongSupplier clock, Supplier<ProductMetricPipelineLoadSupport.Stage> stage) {
    this.clock = Objects.requireNonNull(clock);
    this.stage = Objects.requireNonNull(stage);
  }

  /** Return the same observer instance on every cold lookup. */
  MethodInterceptor callbackAdvice() {
    return callback;
  }

  /**
   * Install only into the original empty-chain batch factory, retaining its customizer reference.
   */
  synchronized void installFactory(SimpleRabbitListenerContainerFactory factory) {
    if (factoryInstalled || !(factory instanceof ProductMetricBatchContainerFactory))
      throw new IllegalStateException("CM21_CALLBACK_SERVICE_FACTORY_INVALID");
    Advice[] before = factory.getAdviceChain();
    if (before != null && before.length != 0)
      throw new IllegalStateException("CM21_CALLBACK_SERVICE_FACTORY_INVALID");
    Object customizer = ReflectionTestUtils.getField(factory, "containerCustomizer");
    factory.setAdviceChain(callback);
    Advice[] after = factory.getAdviceChain();
    if (after == null
        || after.length != 1
        || after[0] != callback
        || ReflectionTestUtils.getField(factory, "containerCustomizer") != customizer)
      throw new IllegalStateException("CM21_CALLBACK_SERVICE_FACTORY_INVALID");
    factoryClass = factory.getClass().getName();
    factoryIdentity = System.identityHashCode(factory);
    factoryInstalled = true;
  }

  /** Keep optional-hook failures separate from the original observers and business outcome. */
  synchronized void recordObserverFailure() {
    fail("OBSERVER_FAILED");
    if (activeOwner != null) activeOwner.partitionInvalid = true;
  }

  /** Reserve one primitive attempt and sample before the unchanged Service proceed. */
  AttemptToken serviceEnter(
      ProductMetricApplicationTimingSupport.CallToken originalToken,
      ProductMetricPipelineLoadSupport.Stage enteredStage,
      String operation,
      int inputSize) {
    try {
      Frame frame = current.get();
      int slot;
      synchronized (this) {
        if (frame != null && frame.suppressionDepth > 0) {
          increment(SUPPRESSED);
          return new AttemptToken(this, originalToken, 0, -1);
        }
        if (frame == null || frame != activeOwner) {
          if (activeOwner != null) {
            activeOwner.partitionInvalid = true;
            increment(CONCURRENT);
          } else increment(UNPAIRED);
          return new AttemptToken(this, originalToken, 0, -1);
        }
        if (frame.activeSlot >= 0) {
          increment(NESTED_SERVICE);
          fail("NESTED_SERVICE");
          frame.partitionInvalid = true;
          return new AttemptToken(this, originalToken, 0, -1);
        }
        if (frame.attempts >= ATTEMPT_LIMIT) {
          frame.attempts = ATTEMPT_LIMIT + 1;
          frame.partitionInvalid = true;
          increment(LIMIT_EXCEEDED);
          fail("ATTEMPT_LIMIT_EXCEEDED");
          return new AttemptToken(this, originalToken, 0, -1);
        }
        slot = frame.attempts++;
        frame.activeSlot = slot;
        int offset = slot * SLOT_WIDTH;
        slots[offset + ENTRY_STAGE] = index(enteredStage);
        slots[offset + OPERATION] =
            "apply".equals(operation) ? 0 : "applyBatch".equals(operation) ? 1 : -1;
        slots[offset + SIZE] = inputSize >= 1 && inputSize <= 4 ? inputSize : 0;
        if (originalToken != null) slots[offset + FLAGS] = MATCHED;
        else tokenFailure(frame);
        if (enteredStage == null || slots[offset + OPERATION] < 0 || slots[offset + SIZE] == 0) {
          frame.partitionInvalid = true;
          fail("SERVICE_INPUT_INVALID");
        }
        increment(SERVICE_ENTERED);
      }
      long started;
      try {
        started = clock.getAsLong();
      } catch (Throwable badClock) {
        clockFailure(frame);
        return new AttemptToken(this, originalToken, frame.generation, slot);
      }
      synchronized (this) {
        slots[slot * SLOT_WIDTH + START] = started;
        slots[slot * SLOT_WIDTH + FLAGS] |= START_VALID;
      }
      return new AttemptToken(this, originalToken, frame.generation, slot);
    } catch (Throwable observerFailure) {
      recordObserverFailure();
      return null;
    }
  }

  /** Complete only the owning slot; the caller still passes the untouched old tag to old exit. */
  void serviceExit(
      AttemptToken token,
      ProductMetricApplicationTimingSupport.CallToken originalToken,
      boolean threw,
      ProductMetricPipelineLoadSupport.Stage exitedStage) {
    try {
      Frame frame = current.get();
      synchronized (this) {
        if (token == null || token.owner != this || token.closed) {
          tokenFailure(activeOwner);
          return;
        }
        token.closed = true;
        if (token.slot < 0) {
          if (token.original != originalToken) tokenFailure(activeOwner);
          return;
        }
        if (frame == null
            || frame != activeOwner
            || token.generation != frame.generation
            || frame.activeSlot != token.slot) {
          tokenFailure(activeOwner);
          return;
        }
        if (token.original == null || token.original != originalToken) {
          slots[token.slot * SLOT_WIDTH + FLAGS] &= ~MATCHED;
          tokenFailure(frame);
        }
      }
      long ended = 0;
      boolean valid = false;
      try {
        ended = clock.getAsLong();
        valid = true;
      } catch (Throwable badClock) {
        clockFailure(frame);
      }
      synchronized (this) {
        int offset = token.slot * SLOT_WIDTH;
        slots[offset + END] = ended;
        slots[offset + EXIT_STAGE] = index(exitedStage);
        slots[offset + OUTCOME] = threw ? 1 : 0;
        slots[offset + FLAGS] |= CLOSED | (valid ? END_VALID : 0);
        if (exitedStage == null) {
          frame.partitionInvalid = true;
          fail("SERVICE_INPUT_INVALID");
        }
        long duration =
            duration(
                slots[offset + START],
                ended,
                (slots[offset + FLAGS] & (START_VALID | END_VALID)) == (START_VALID | END_VALID),
                frame);
        int serviceStage = (int) slots[offset + ENTRY_STAGE];
        int operation = (int) slots[offset + OPERATION];
        if (frame.entryStage >= 0 && serviceStage >= 0 && operation >= 0) {
          int cell =
              ((((frame.entryStage * 7 + serviceStage) * 2 + operation) * 5
                          + (int) slots[offset + SIZE])
                      * 2
                  + (threw ? 1 : 0));
          record(attemptCells[cell], duration);
        }
        frame.activeSlot = -1;
        increment(SERVICE_COMPLETED);
      }
    } catch (Throwable observerFailure) {
      recordObserverFailure();
    }
  }

  /**
   * Observe only the fixed framework method; original proceed is never inside a swallowed catch.
   */
  private Object invokeCallback(MethodInvocation invocation) throws Throwable {
    boolean matched;
    try {
      Method method = invocation.getMethod();
      matched =
          method != null
              && "invokeListener".equals(method.getName())
              && "org.springframework.amqp.rabbit.listener.AbstractMessageListenerContainer$ContainerDelegate"
                  .equals(method.getDeclaringClass().getName())
              && Arrays.equals(
                  method.getParameterTypes(), new Class<?>[] {Channel.class, Object.class});
    } catch (Throwable lookupFailure) {
      recordObserverFailure();
      Frame uncertainOuter = null;
      try {
        uncertainOuter = current.get();
      } catch (Throwable ignoredLookupFailure) {
        recordObserverFailure();
      }
      if (uncertainOuter != null) return invokeSuppressed(invocation, uncertainOuter, false);
      return invocation.proceed();
    }
    if (!matched) return invocation.proceed();
    Frame outer;
    try {
      outer = current.get();
    } catch (Throwable lookupFailure) {
      recordObserverFailure();
      return invocation.proceed();
    }
    if (outer != null) return invokeSuppressed(invocation, outer, true);
    Frame frame = beginCallback(invocation);
    Object returned;
    try {
      returned = invocation.proceed();
    } catch (Throwable originalFailure) {
      endCallbackSafely(frame, true);
      throw originalFailure;
    }
    endCallbackSafely(frame, false);
    return returned;
  }

  /** Suppress uncertain/nested children independently of every clock, stage or argument getter. */
  private Object invokeSuppressed(MethodInvocation invocation, Frame outer, boolean matchedCallback)
      throws Throwable {
    int originalDepth;
    synchronized (this) {
      originalDepth = outer.suppressionDepth;
      outer.partitionInvalid = true;
      outer.suppressionDepth =
          originalDepth == Integer.MAX_VALUE ? originalDepth : originalDepth + 1;
      if (matchedCallback) {
        increment(CALLBACK_ENTERED);
        increment(NESTED_CALLBACK);
        fail("NESTED_CALLBACK");
      }
    }
    try {
      return invocation.proceed();
    } finally {
      synchronized (this) {
        outer.suppressionDepth = originalDepth;
        if (matchedCallback) {
          increment(CALLBACK_COMPLETED);
          increment(UNCLASSIFIED);
        }
      }
    }
  }

  /** Admit at most one active frame before reading optional stage/clock facts. */
  private Frame beginCallback(MethodInvocation invocation) {
    Frame frame = null;
    try {
      synchronized (this) {
        increment(CALLBACK_ENTERED);
        if (activeOwner != null) {
          activeOwner.partitionInvalid = true;
          fail("CONCURRENT_CALLBACK");
          return null;
        }
        generation = Math.addExact(generation, 1);
        frame = new Frame();
        frame.generation = generation;
        frame.entryStage = -1;
        Arrays.fill(slots, 0);
        activeOwner = frame;
        current.set(frame);
      }
      try {
        Object[] arguments = invocation.getArguments();
        if (arguments == null || arguments.length != 2) recordObserverFailure();
        else if (arguments[1] instanceof List<?> messages) {
          int count = messages.size();
          frame.inputSize = count >= 1 && count <= 4 ? count : 0;
        }
      } catch (Throwable lookupFailure) {
        recordObserverFailure();
      }
      try {
        frame.entryStage = index(stage.get());
        if (frame.entryStage < 0) recordObserverFailure();
      } catch (Throwable badStage) {
        recordObserverFailure();
      }
      try {
        frame.started = clock.getAsLong();
        frame.clockValid = true;
      } catch (Throwable badClock) {
        clockFailure(frame);
      }
      return frame;
    } catch (Throwable observerFailure) {
      if (observerFailure instanceof ArithmeticException) {
        synchronized (this) {
          counterFailure();
        }
      } else recordObserverFailure();
      return frame;
    }
  }

  /** Sample after native return/throw, then commit completion and release ownership atomically. */
  private void endCallbackSafely(Frame frame, boolean threw) {
    try {
      if (frame == null) {
        synchronized (this) {
          increment(CALLBACK_COMPLETED);
          increment(UNCLASSIFIED);
        }
        return;
      }
      int exitedStage = -1;
      long ended = 0;
      boolean valid = false;
      try {
        exitedStage = index(stage.get());
        if (exitedStage < 0) recordObserverFailure();
      } catch (Throwable badStage) {
        recordObserverFailure();
      }
      try {
        ended = clock.getAsLong();
        valid = true;
      } catch (Throwable badClock) {
        clockFailure(frame);
      }
      synchronized (this) {
        if (frame != activeOwner || current.get() != frame) tokenFailure(frame);
        if (frame.activeSlot >= 0) tokenFailure(frame);
        long total = duration(frame.started, ended, frame.clockValid && valid, frame);
        increment(CALLBACK_COMPLETED);
        if (frame.entryStage < 0) increment(UNCLASSIFIED);
        else {
          increment(CLASSIFIED);
          int cell = (frame.entryStage * 5 + frame.inputSize) * 2 + (threw ? 1 : 0);
          record(callbackCells[cell], total);
          add(attemptCounts[cell], Math.min(frame.attempts, 16));
          boolean crossed = frame.entryStage != exitedStage;
          for (int ordinal = 0; ordinal < Math.min(frame.attempts, ATTEMPT_LIMIT); ordinal++) {
            int offset = ordinal * SLOT_WIDTH;
            crossed |=
                slots[offset + ENTRY_STAGE] != frame.entryStage
                    || slots[offset + EXIT_STAGE] != frame.entryStage;
          }
          if (crossed) add(partitions[cell], 2);
          boolean eligible =
              !frame.partitionInvalid
                  && frame.attempts == 1
                  && frame.activeSlot < 0
                  && !crossed
                  && total >= 0
                  && (slots[FLAGS] & (START_VALID | END_VALID | CLOSED | MATCHED))
                      == (START_VALID | END_VALID | CLOSED | MATCHED);
          long pre = -1, service = -1, post = -1;
          if (eligible) {
            pre = duration(frame.started, slots[START], true, frame);
            service = duration(slots[START], slots[END], true, frame);
            post = duration(slots[END], ended, true, frame);
            try {
              eligible =
                  pre >= 0
                      && service >= 0
                      && post >= 0
                      && Math.addExact(Math.addExact(pre, service), post) == total;
            } catch (ArithmeticException overflow) {
              counterFailure();
              eligible = false;
            }
          }
          if (eligible
              && canRecord(partitionCells[cell * 3], pre)
              && canRecord(partitionCells[cell * 3 + 1], service)
              && canRecord(partitionCells[cell * 3 + 2], post)) {
            record(partitionCells[cell * 3], pre);
            record(partitionCells[cell * 3 + 1], service);
            record(partitionCells[cell * 3 + 2], post);
            add(partitions[cell], 0);
          } else add(partitions[cell], 1);
        }
        activeOwner = null;
        current.remove();
      }
    } catch (Throwable observerFailure) {
      recordObserverFailure();
    } finally {
      synchronized (this) {
        if (frame != null && activeOwner == frame) activeOwner = null;
        if (frame != null) {
          frame.activeSlot = -1;
          frame.suppressionDepth = 0;
          try {
            if (current.get() == frame) current.remove();
          } catch (Throwable cleanupFailure) {
            recordObserverFailure();
          }
        }
      }
    }
  }

  /** Ordinal identity is read without calling user or business code. */
  private static int index(ProductMetricPipelineLoadSupport.Stage stage) {
    return stage == null ? -1 : stage.ordinal();
  }

  /** Checked elapsed time accepts negative origins and real zero, never negative durations. */
  private long duration(long started, long ended, boolean valid, Frame frame) {
    if (!valid) return -1;
    try {
      long elapsed = Math.subtractExact(ended, started);
      if (elapsed < 0) throw new ArithmeticException("CLOCK_BACKWARD");
      return elapsed;
    } catch (ArithmeticException invalidClock) {
      clockFailure(frame);
      return -1;
    }
  }

  /** The caller owns the short aggregate monitor; no clock is sampled here. */
  private void increment(int counter) {
    try {
      counters[counter] = Math.addExact(counters[counter], 1);
    } catch (ArithmeticException overflow) {
      counterFailure();
    }
  }

  /** Increment a fixed bucket with sticky overflow and no partial wraparound. */
  private void add(long[] values, int index) {
    try {
      values[index] = Math.addExact(values[index], 1);
    } catch (ArithmeticException overflow) {
      counterFailure();
    }
  }

  /** Preflight one entire cell so a failed increment cannot publish partial histogram state. */
  private boolean canRecord(long[] cell, long duration) {
    try {
      Math.addExact(cell[0], 1);
      if (duration >= 0) {
        Math.addExact(cell[1], 1);
        Math.addExact(cell[2], duration);
        Math.addExact(cell[4 + bucket(duration)], 1);
      }
      return true;
    } catch (ArithmeticException overflow) {
      counterFailure();
      return false;
    }
  }

  /** Commit only preflighted nonnegative counters; max and histogram retain their actual scope. */
  private void record(long[] cell, long duration) {
    if (!canRecord(cell, duration)) return;
    cell[0]++;
    if (duration < 0) return;
    cell[1]++;
    cell[2] += duration;
    cell[3] = Math.max(cell[3], duration);
    cell[4 + bucket(duration)]++;
  }

  /** Inclusive histogram boundaries are shared only as values with the old collector. */
  private static int bucket(long duration) {
    int index = 0;
    while (index < UPPER_BOUNDS.length && duration > UPPER_BOUNDS[index]) index++;
    return index;
  }

  /** First fault is stable even when a later observer exit also fails. */
  private void fail(String code) {
    if ("NONE".equals(failure)) failure = code;
  }

  /** Arithmetic overflow itself cannot overflow its diagnostic counter. */
  private void counterFailure() {
    fail("COUNT_OVERFLOW");
    if (counters[COUNTER_OVERFLOW] != Long.MAX_VALUE) counters[COUNTER_OVERFLOW]++;
    if (activeOwner != null) activeOwner.partitionInvalid = true;
  }

  /** Clock faults are diagnostic only and leave the original invocation untouched. */
  private synchronized void clockFailure(Frame frame) {
    increment(CLOCK_FAILURE);
    fail("CLOCK_INVALID");
    if (frame != null) frame.partitionInvalid = true;
  }

  /** Foreign/missing/double capabilities never release somebody else's active slot. */
  private void tokenFailure(Frame frame) {
    increment(TOKEN_FAILURE);
    fail("TOKEN_INVALID");
    if (frame != null) frame.partitionInvalid = true;
  }

  /** Build independent cold copies under the same lock as completion/active-state transitions. */
  synchronized Map<String, Object> snapshot() {
    Map<String, Object> runtime = new LinkedHashMap<>();
    runtime.put("factoryClassName", factoryInstalled ? factoryClass : null);
    runtime.put("factoryIdentityHashCode", factoryInstalled ? factoryIdentity : null);
    runtime.put("originalFactoryAdviceCount", factoryInstalled ? 0 : null);
    runtime.put("installedFactoryAdviceCount", factoryInstalled ? 1 : 0);
    runtime.put("factoryCustomizerPreserved", factoryInstalled ? true : null);
    runtime.put("callbackBoundary", "FRAMEWORK_LISTENER_INVOKE_BRACKET");
    runtime.put("serviceBoundary", "OBSERVED_APPLY_PROCEED_BRACKET");
    runtime.put("serviceAdviceAdded", false);
    runtime.put("rawChannelWrapped", false);
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("failureCode", failure);
    state.put("activeCallbacks", activeOwner == null ? 0 : 1);
    state.put("activeServiceAttempts", activeOwner != null && activeOwner.activeSlot >= 0 ? 1 : 0);
    state.put("framesOutstanding", activeOwner == null ? 0 : 1);
    String[] names = {
      "callbacksEntered",
      "callbacksCompleted",
      "classifiedCallbacks",
      "unclassifiedCallbacks",
      "serviceAttemptsEntered",
      "serviceAttemptsCompleted",
      "unpairedServiceCalls",
      "suppressedNestedServiceCalls",
      "concurrentUnassociatedServiceCalls",
      "nestedCallbacks",
      "nestedServiceCalls",
      "attemptLimitExceeded",
      "counterOverflow",
      "clockFailures",
      "tokenFailures"
    };
    for (int index = 0; index < names.length; index++) state.put(names[index], counters[index]);
    state.put("suppressionDepth", activeOwner == null ? 0 : activeOwner.suppressionDepth);
    long eligible = 0, unknown = 0;
    for (long[] values : partitions) {
      eligible = Math.addExact(eligible, values[0]);
      unknown = Math.addExact(unknown, values[1]);
    }
    String coverage =
        eligible == 0
            ? "UNAVAILABLE"
            : unknown != 0
                    || activeOwner != null
                    || counters[UNCLASSIFIED] != 0
                    || !"NONE".equals(failure)
                ? "PARTIAL"
                : "COMPLETE";
    Map<String, Object> qualification = new LinkedHashMap<>();
    qualification.put("collectorHealthy", "NONE".equals(failure));
    qualification.put("partitionCoverage", coverage);
    qualification.put("runtimeQualification", false);
    qualification.put("physicalAckMeasured", false);
    qualification.put("commitMeasured", false);
    qualification.put("observerOverheadUnknown", true);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", 1);
    result.put("scope", "CALLBACK_SERVICE_LOCAL_OBSERVATION_V1");
    result.put("enabled", true);
    result.put("runtimeIdentityFacts", runtime);
    result.put("collectorState", state);
    result.put("histogramUpperBoundsNs", Arrays.stream(UPPER_BOUNDS).boxed().toList());
    result.put("callbackCells", callbackRows());
    result.put("attemptCells", attemptRows());
    result.put("partitionCells", partitionRows());
    result.put("qualification", qualification);
    return result;
  }

  /** Health is distinct from complete partitions: zero/multiple/stage-crossing calls are legal. */
  synchronized void assertHealthyAfterClose() {
    if (!"NONE".equals(failure) || activeOwner != null || current.get() != null)
      throw new IllegalStateException("CM21_CALLBACK_SERVICE_OBSERVER_NOT_HEALTHY");
  }

  /** Copy each original cohort once; never retain per-callback records. */
  private List<Map<String, Object>> callbackRows() {
    List<Map<String, Object>> result = new ArrayList<>(70);
    for (int stage = 0; stage < 7; stage++)
      for (int size = 0; size <= 4; size++)
        for (int outcome = 0; outcome < 2; outcome++) {
          int cell = (stage * 5 + size) * 2 + outcome;
          Map<String, Object> row = coordinates(stage, size, outcome);
          row.put("stats", stats(callbackCells[cell]));
          row.put("attemptCountBuckets", Arrays.stream(attemptCounts[cell]).boxed().toList());
          row.put("partitionedCalls", partitions[cell][0]);
          row.put("unknownPartitionCalls", partitions[cell][1]);
          row.put("crossStageCalls", partitions[cell][2]);
          result.add(row);
        }
    return result;
  }

  /** Fixed callback-entry and Service-entry cohorts are not inferred for excluded calls. */
  private List<Map<String, Object>> attemptRows() {
    List<Map<String, Object>> result = new ArrayList<>(980);
    for (int callbackStage = 0; callbackStage < 7; callbackStage++)
      for (int serviceStage = 0; serviceStage < 7; serviceStage++)
        for (int operation = 0; operation < 2; operation++)
          for (int size = 0; size <= 4; size++)
            for (int outcome = 0; outcome < 2; outcome++) {
              int cell =
                  ((((callbackStage * 7 + serviceStage) * 2 + operation) * 5 + size) * 2 + outcome);
              Map<String, Object> row = new LinkedHashMap<>();
              row.put("callbackEntryStage", STAGES[callbackStage].name());
              row.put("serviceEntryStage", STAGES[serviceStage].name());
              row.put("operation", operation == 0 ? "apply" : "applyBatch");
              row.put("inputSize", size);
              row.put("outcome", outcome == 0 ? "NORMAL" : "THREW");
              row.put("stats", stats(attemptCells[cell]));
              result.add(row);
            }
    return result;
  }

  /** Only real eligible single-attempt intervals contribute any of these cells. */
  private List<Map<String, Object>> partitionRows() {
    List<Map<String, Object>> result = new ArrayList<>(210);
    for (int stage = 0; stage < 7; stage++)
      for (int size = 0; size <= 4; size++)
        for (int outcome = 0; outcome < 2; outcome++)
          for (int span = 0; span < 3; span++) {
            Map<String, Object> row = coordinates(stage, size, outcome);
            row.put("span", SPANS[span]);
            row.put("stats", stats(partitionCells[((stage * 5 + size) * 2 + outcome) * 3 + span]));
            result.add(row);
          }
    return result;
  }

  /** Common cold callback/partition dimensions. */
  private static Map<String, Object> coordinates(int stage, int size, int outcome) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("entryStage", STAGES[stage].name());
    result.put("inputSize", size);
    result.put("outcome", outcome == 0 ? "NORMAL" : "THREW");
    return result;
  }

  /** Export independent primitive values and an independent fourteen-bucket list. */
  private static Map<String, Object> stats(long[] cell) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("observations", cell[0]);
    result.put("timedCalls", cell[1]);
    result.put("sumNs", cell[2]);
    result.put("maxNs", cell[3]);
    result.put("buckets", Arrays.stream(cell, 4, CELL_WIDTH).boxed().toList());
    return result;
  }
}
