package com.cloudmall.product;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.LongSupplier;
import java.util.function.Supplier;
import javax.sql.DataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** Test-only bounded logical transaction markers; never a physical commit or ACK measurement. */
final class ProductMetricServiceTransactionObservationSupport {
  /** A healthy collector may still lack a terminal callback or a supported runtime identity. */
  static final class ObservationIncompleteException extends IllegalStateException {
    /** Explain missing observation without marking an observer fault. */
    private ObservationIncompleteException() {
      super("CM21_SERVICE_TRANSACTION_OBSERVATION_INCOMPLETE");
    }
  }

  /** Invalid Java elapsed-clock arithmetic, distinct from aggregate counter overflow. */
  private static final class ClockInvalidException extends RuntimeException {}

  /** Short-lived Service capability; opaque old tags are cleared at its own exit. */
  static final class ServiceToken {
    /** Collector allowed to close this capability. */
    private final ProductMetricServiceTransactionObservationSupport owner;

    /** Original invocation thread identity, never exported. */
    private final Thread thread;

    /** Original timing helper, only retained until E. */
    private ProductMetricApplicationTimingSupport timing;

    /** Original opaque tag, never consumed by this collector. */
    private ProductMetricApplicationTimingSupport.CallToken oldTag;

    /** Primitive marker state of an accepted Service call. */
    private Frame frame;

    /** Whether this token owns one nested suppression level. */
    private boolean suppressed;

    /** Whether its one exit was already received. */
    private boolean closed;

    /** Bind a new capability without reading a clock. */
    private ServiceToken(
        ProductMetricServiceTransactionObservationSupport owner,
        ProductMetricApplicationTimingSupport timing,
        ProductMetricApplicationTimingSupport.CallToken oldTag) {
      this.owner = owner;
      this.thread = Thread.currentThread();
      this.timing = timing;
      this.oldTag = oldTag;
    }
  }

  /** Primitive-only active marker state, with no business payload or old application token. */
  private static final class Frame {
    /** Frozen coordinate before Service outcome is known. */
    private final int coordinate;

    /** Original entry stage ordinal. */
    private final int entryStage;

    /** Exactly six timestamp slots. */
    private final long[] markers = new long[6];

    /** Successfully sampled marker bits. */
    private int validMask;

    /** Received bits independently reject duplicate callbacks after clock failures. */
    private int receivedMask;

    /** Whether any marker observed a different stage. */
    private boolean crossed;

    /** Unknown/nested association disqualifies full coverage without inventing a fault. */
    private boolean unknown;

    /** Registration may be attempted only once. */
    private boolean attempted;

    /** Registration result NONE/CONFIRMED/UNCERTAIN as indices 0/1/2. */
    private int registrationResult;

    /** Framework completion status index; initially NOT_OBSERVED. */
    private int completionStatus = 3;

    /** Whether E froze this cohort. */
    private boolean finalized;

    /** Create the fixed primitive coordinate. */
    private Frame(int coordinate, int entryStage) {
      this.coordinate = coordinate;
      this.entryStage = entryStage;
    }
  }

  /** One bounded synchronization capability; it never retains an opaque application tag. */
  private final class RegistrationToken implements TransactionSynchronization {
    /** Active primitive frame; disconnected immediately at E. */
    private Frame frame;

    /** Original registration thread identity, never exported. */
    private final Thread thread = Thread.currentThread();

    /** Registration state is independent of Service TL lifetime. */
    private String state = "RESERVED";

    /** C releases this capacity at most once. */
    private boolean released;

    /** Callback receipt bits survive clock failure and Service finalization. */
    private int receivedMask;

    /** Bind the sole capacity reservation. */
    private RegistrationToken(Frame frame) {
      this.frame = frame;
    }

    /** Preserve the Spring default order without reading a clock. */
    @Override
    public int getOrder() {
      return Integer.MAX_VALUE;
    }

    /** Observe only the logical beforeCommit callback. */
    @Override
    public void beforeCommit(boolean readOnly) {
      callback(this, 2, -1);
    }

    /** Observe only the logical afterCommit callback. */
    @Override
    public void afterCommit() {
      callback(this, 3, -1);
    }

    /** Always relinquish this token's capacity, including diagnostic failures. */
    @Override
    public void afterCompletion(int status) {
      callback(this, 4, status);
    }
  }

  /** Fixed entry stages, never dynamically labelled. */
  private static final ProductMetricPipelineLoadSupport.Stage[] STAGES =
      ProductMetricPipelineLoadSupport.Stage.values();

  /** Exact Mapper span names already recognized by the original timer. */
  private static final List<String> MAPPER_SPANS =
      List.of(
          "INITIAL_APPLY_SNAPSHOT",
          "ENSURE_STAT",
          "LOCK_STAT",
          "INSERT_APPLIED",
          "UPDATE_STAT_AND_PROJECTION",
          "CONTROL",
          "ADVANCE_BY",
          "ADVANCE");

  /** Six fixed marker labels. */
  private static final String[] MARKER_NAMES = {"S", "M", "B", "A", "C", "E"};

  /** Five same-cohort logical edges. */
  private static final String[] SEGMENT_NAMES = {"S_M", "M_B", "B_A", "A_C", "C_E"};

  /** Completion status labels are independent of Service outcome. */
  private static final String[] STATUS_NAMES = {
    "COMMITTED", "ROLLED_BACK", "UNKNOWN", "NOT_OBSERVED"
  };

  /** Registration result labels frozen at E. */
  private static final String[] REGISTRATION_NAMES = {"NONE", "CONFIRMED", "UNCERTAIN"};

  /** Fixed nonnegative checked counters. */
  private static final String[] COUNTER_NAMES = {
    "servicesEntered",
    "servicesCompleted",
    "framesEntered",
    "framesCompleted",
    "unclassifiedServices",
    "skippedOuterTransaction",
    "skippedRuntime",
    "skippedPendingRegistration",
    "skippedConcurrentService",
    "suppressedNestedServices",
    "unmatchedMapperCalls",
    "registrationAttempts",
    "registrationsConfirmed",
    "registrationsUncertain",
    "reservationsReleased",
    "registrationNotSubmitted",
    "lateCompletions",
    "lateCommitted",
    "lateRolledBack",
    "lateUnknown",
    "duplicateCallbacks",
    "wrongThreadCallbacks",
    "clockFailures",
    "tokenFailures",
    "counterOverflow",
    "observerFailures"
  };

  /** Inclusive upper bounds; a fourteenth bucket represents overflow. */
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

  /** Shared arbitrary-origin Java monotonic clock; called only outside aggregate locks. */
  private final LongSupplier clock;

  /** Original stage accessor; called only outside aggregate locks. */
  private final Supplier<ProductMetricPipelineLoadSupport.Stage> stage;

  /** Sole per-thread active Service capability, removed at E. */
  private final ThreadLocal<ServiceToken> current = new ThreadLocal<>();

  /** 112 cohorts, seven stats each, eighteen longs per stats. */
  private final long[][][] statistics = new long[112][7][18];

  /** All E-frozen valid marker masks, no per-call history. */
  private final long[][] masks = new long[112][64];

  /** E-frozen framework statuses. */
  private final long[][] statuses = new long[112][4];

  /** E-frozen registration results. */
  private final long[][] registrations = new long[112][3];

  /** E-frozen valid marker receipt counts. */
  private final long[][] markerCounts = new long[112][6];

  /** Full, partial and crossed counts. */
  private final long[][] classifications = new long[112][3];

  /** Twenty-six fixed global counters. */
  private final long[] counters = new long[26];

  /** First observer fault, never a business Throwable. */
  private String failure = "NONE";

  /** The sole active capability, distinct from a pending registration. */
  private ServiceToken active;

  /** One jointly budgeted unresolved registration, including after E. */
  private RegistrationToken pending;

  /** Nested levels currently suppressing Mapper association. */
  private int suppressionDepth;

  /** Original cold transaction manager identity; never wrapped or replaced. */
  private PlatformTransactionManager manager;

  /** Original cold datasource identity; never wrapped or queried for a connection. */
  private DataSource dataSource;

  /** Whether the supported original identity was cold-verified. */
  private boolean runtimeVerified;

  /** Cold manager class name, null until supplied. */
  private String managerClassName;

  /** Cold datasource class name, null until supplied. */
  private String dataSourceClassName;

  /** Cold manager's original rollback policy. */
  private Boolean rollbackOnCommitFailure;

  /** Allocate this enabled collector explicitly; construction does not invoke either supplier. */
  ProductMetricServiceTransactionObservationSupport(
      LongSupplier clock, Supplier<ProductMetricPipelineLoadSupport.Stage> stage) {
    this.clock = Objects.requireNonNull(clock);
    this.stage = Objects.requireNonNull(stage);
  }

  /** Cold-check original local manager/DS identities without acquiring a connection. */
  synchronized boolean verifyRuntime(
      PlatformTransactionManager originalManager, DataSource originalDataSource) {
    try {
      if (manager != null || dataSource != null) {
        if (manager != originalManager || dataSource != originalDataSource) {
          fail("RUNTIME_IDENTITY_CHANGED");
          return false;
        }
      }
      manager = originalManager;
      dataSource = originalDataSource;
      managerClassName = originalManager == null ? null : originalManager.getClass().getName();
      dataSourceClassName =
          originalDataSource == null ? null : originalDataSource.getClass().getName();
      if (!(originalManager instanceof DataSourceTransactionManager) || originalDataSource == null)
        return false;
      DataSourceTransactionManager localManager = (DataSourceTransactionManager) originalManager;
      boolean sameDataSource = localManager.getDataSource() == originalDataSource;
      boolean originalRollbackPolicy = localManager.isRollbackOnCommitFailure();
      // Publish runtime proof only after every guarded cold metadata read succeeded.
      rollbackOnCommitFailure = originalRollbackPolicy;
      runtimeVerified = sameDataSource;
      return runtimeVerified;
    } catch (Throwable metadataFailure) {
      runtimeVerified = false;
      observerFault(metadataFailure);
      return false;
    }
  }

  /**
   * Associate a new original Service attempt, rejecting diagnosis without blocking its business.
   */
  ServiceToken serviceEnter(
      ProductMetricApplicationTimingSupport timing,
      ProductMetricApplicationTimingSupport.CallToken oldTag,
      ProductMetricPipelineLoadSupport.Stage enteredStage,
      String operation,
      int inputSize) {
    ServiceToken token = new ServiceToken(this, timing, oldTag);
    try {
      // 1. Nested suppression precedes all association and transaction checks.
      synchronized (this) {
        increment(0);
        if (active != null && active.thread == Thread.currentThread()) {
          suppressionDepth = Math.incrementExact(suppressionDepth);
          token.suppressed = true;
          active.frame.unknown = true;
          increment(9);
          return token;
        }
      }
      int operationIndex = "apply".equals(operation) ? 0 : "applyBatch".equals(operation) ? 1 : -1;
      if (enteredStage == null || operationIndex < 0 || inputSize < 1 || inputSize > 4) {
        synchronized (this) {
          increment(4);
        }
        return token;
      }
      if (timing == null || !timing.matchesCurrentCallToken(oldTag)) {
        tokenFault();
        return token;
      }
      // 2. An already-active external transaction is not attributed to this invocation.
      synchronized (this) {
        if (!runtimeVerified) {
          increment(6);
          return token;
        }
        if (pending != null) {
          increment(7);
          return token;
        }
        if (active != null) {
          increment(8);
          return token;
        }
      }
      if (TransactionSynchronizationManager.isActualTransactionActive()
          || TransactionSynchronizationManager.isSynchronizationActive()) {
        synchronized (this) {
          increment(5);
        }
        return token;
      }
      Frame frame =
          new Frame(
              (enteredStage.ordinal() * 2 + operationIndex) * 4 + inputSize - 1,
              enteredStage.ordinal());
      // 3. Recheck capacity atomically before publishing the sole active frame.
      synchronized (this) {
        if (pending != null) {
          increment(7);
          return token;
        }
        if (active != null) {
          increment(8);
          return token;
        }
        long nextEntered = Math.addExact(counters[2], 1L);
        token.frame = frame;
        active = token;
        current.set(token);
        counters[2] = nextEntered;
      }
      sample(frame, 0);
    } catch (Throwable observerFailure) {
      observerFault(observerFailure);
    }
    return token;
  }

  /** Observe the first already-recognized Mapper entry, before its original proceed. */
  void mapperEnter(ProductMetricApplicationTimingSupport timing, String recognizedSpan) {
    try {
      ServiceToken token;
      synchronized (this) {
        token = active;
        if (token == null || token.thread != Thread.currentThread()) {
          increment(10);
          return;
        }
        if (suppressionDepth != 0) {
          token.frame.unknown = true;
          return;
        }
      }
      if (current.get() != token) {
        tokenFault();
        return;
      }
      if (timing != token.timing
          || !timing.matchesCurrentCallToken(token.oldTag)
          || !MAPPER_SPANS.contains(recognizedSpan)) {
        tokenFault();
        return;
      }
      Frame frame = token.frame;
      synchronized (this) {
        if (frame.attempted) return;
      }
      if (!TransactionSynchronizationManager.isActualTransactionActive()
          || !TransactionSynchronizationManager.isSynchronizationActive()
          || TransactionSynchronizationManager.isCurrentTransactionReadOnly()) {
        synchronized (this) {
          frame.unknown = true;
        }
        return;
      }
      RegistrationToken registration;
      // 1. The unique reservation is acquired before taking M or touching Spring registration.
      synchronized (this) {
        if (pending != null || active != token || frame.finalized) {
          frame.unknown = true;
          return;
        }
        long nextAttempts = Math.addExact(counters[11], 1L);
        registration = new RegistrationToken(frame);
        frame.attempted = true;
        pending = registration;
        counters[11] = nextAttempts;
      }
      // 2. Every failure before the register call begins must relinquish this reservation.
      boolean registrationCallStarted = false;
      try {
        if (!sample(frame, 1)) return;
        synchronized (this) {
          registration.state = "REGISTERING";
        }
        // 3. Once submission starts, an exception is conservatively UNCERTAIN, not a retry.
        registrationCallStarted = true;
        try {
          TransactionSynchronizationManager.registerSynchronization(registration);
        } catch (Throwable registrationFailure) {
          synchronized (this) {
            frame.unknown = true;
            frame.registrationResult = 2;
            if (!registration.released) registration.state = "UNCERTAIN";
            increment(13);
            fail("REGISTRATION_FAILED");
          }
          return;
        }
        // A normally returned API call stays CONFIRMED even if our own bookkeeping fails.
        synchronized (this) {
          if (registration.released) {
            frame.unknown = true;
            fail("REGISTRATION_REENTRY");
          } else {
            registration.state = "CONFIRMED";
            frame.registrationResult = 1;
            try {
              increment(12);
            } catch (Throwable bookkeepingFailure) {
              frame.unknown = true;
              observerFault(bookkeepingFailure);
            }
          }
        }
      } finally {
        if (!registrationCallStarted) {
          synchronized (this) {
            try {
              increment(15);
            } finally {
              release(registration);
            }
          }
        }
      }
    } catch (Throwable observerFailure) {
      observerFault(observerFailure);
    }
  }

  /** Freeze the original Service outcome and clear only this token's short-lived association. */
  void serviceExit(
      ServiceToken token,
      ProductMetricApplicationTimingSupport.CallToken oldTag,
      boolean threw,
      ProductMetricPipelineLoadSupport.Stage exitedStage) {
    try {
      if (token == null
          || token.owner != this
          || token.thread != Thread.currentThread()
          || token.oldTag != oldTag
          || token.closed) {
        tokenFault();
        return;
      }
      if (token.suppressed) {
        synchronized (this) {
          suppressionDepth = Math.decrementExact(suppressionDepth);
          token.closed = true;
          increment(1);
        }
        token.oldTag = null;
        token.timing = null;
        return;
      }
      Frame frame = token.frame;
      if (frame == null) {
        synchronized (this) {
          token.closed = true;
          increment(1);
        }
        token.oldTag = null;
        token.timing = null;
        return;
      }
      if (current.get() != token) {
        tokenFault();
        return;
      }
      try {
        sample(frame, 5);
      } finally {
        // Freeze the cohort and close its active gauges in one snapshot-visible transition.
        synchronized (this) {
          try {
            if (exitedStage == null || exitedStage.ordinal() != frame.entryStage)
              frame.crossed = true;
            finalizeFrame(frame, threw);
          } finally {
            frame.finalized = true;
            if (pending != null && pending.frame == frame) pending.frame = null;
            active = null;
            current.remove();
            token.frame = null;
            token.oldTag = null;
            token.timing = null;
            token.closed = true;
            increment(1);
            increment(3);
          }
        }
      }
    } catch (Throwable observerFailure) {
      observerFault(observerFailure);
    }
  }

  /** Record a safe-hook fault only in this new diagnostic domain. */
  synchronized void recordObserverFailure() {
    try {
      increment(25);
      fail("OBSERVER_FAILED");
    } catch (Throwable observerFailure) {
      observerFault(observerFailure);
    }
  }

  /** Sample a marker outside locks; a receipt is not a valid clock sample. */
  private boolean sample(Frame frame, int marker) {
    synchronized (this) {
      int bit = 1 << marker;
      if ((frame.receivedMask & bit) != 0) {
        increment(20);
        fail("DUPLICATE_MARKER");
        return false;
      }
      frame.receivedMask |= bit;
    }
    try {
      long timestamp = clock.getAsLong();
      ProductMetricPipelineLoadSupport.Stage observedStage = stage.get();
      synchronized (this) {
        frame.markers[marker] = timestamp;
        frame.validMask |= 1 << marker;
        if (observedStage == null || observedStage.ordinal() != frame.entryStage)
          frame.crossed = true;
      }
      return true;
    } catch (Throwable clockFailure) {
      synchronized (this) {
        increment(22);
        fail("CLOCK_FAILED");
      }
      return false;
    }
  }

  /** Callback attribution uses its own token, never the latest ThreadLocal frame. */
  private void callback(RegistrationToken registration, int marker, int status) {
    boolean completion = marker == 4;
    try {
      Frame frame;
      synchronized (this) {
        int bit = 1 << marker;
        if (registration.released || (registration.receivedMask & bit) != 0) {
          increment(20);
          fail("DUPLICATE_CALLBACK");
          return;
        }
        registration.receivedMask |= bit;
        if (registration.thread != Thread.currentThread()) {
          increment(21);
          fail("WRONG_THREAD_CALLBACK");
          if (registration.frame != null) registration.frame.unknown = true;
          return;
        }
        frame = registration.frame;
        if (frame == null || frame.finalized) {
          if (completion) {
            increment(16);
            increment(
                status == TransactionSynchronization.STATUS_COMMITTED
                    ? 17
                    : status == TransactionSynchronization.STATUS_ROLLED_BACK ? 18 : 19);
          }
          return;
        }
        if (!"CONFIRMED".equals(registration.state)) {
          frame.unknown = true;
          fail("REGISTRATION_REENTRY");
        }
        if (completion) frame.completionStatus = statusIndex(status);
      }
      sample(frame, marker);
    } catch (Throwable observerFailure) {
      observerFault(observerFailure);
    } finally {
      if (completion) {
        try {
          synchronized (this) {
            release(registration);
          }
        } catch (Throwable observerFailure) {
          observerFault(observerFailure);
        }
      }
    }
  }

  /** Release only the matching capacity, with bookkeeping failure unable to retain it. */
  private void release(RegistrationToken registration) {
    if (registration.released) return;
    registration.released = true;
    if (pending == registration) pending = null;
    increment(14);
  }

  /** Candidate-copy aggregation gives each completed cohort a single checked commit. */
  private void finalizeFrame(Frame frame, boolean threw) {
    int coordinate = frame.coordinate * 2 + (threw ? 1 : 0);
    long[][] nextStats = new long[7][];
    for (int statsIndex = 0; statsIndex < 7; statsIndex++)
      nextStats[statsIndex] = statistics[coordinate][statsIndex].clone();
    long[] nextMasks = masks[coordinate].clone();
    long[] nextStatuses = statuses[coordinate].clone();
    long[] nextRegistrations = registrations[coordinate].clone();
    long[] nextMarkers = markerCounts[coordinate].clone();
    long[] nextClasses = classifications[coordinate].clone();
    Long whole = null;
    if ((frame.validMask & 33) == 33) whole = elapsed(frame.markers[0], frame.markers[5]);
    boolean full =
        frame.registrationResult == 1
            && frame.validMask == 63
            && frame.completionStatus == 0
            && !frame.crossed
            && !frame.unknown;
    long[] edges = new long[5];
    if (full) {
      long total = 0;
      for (int segment = 0; segment < 5; segment++) {
        edges[segment] = elapsed(frame.markers[segment], frame.markers[segment + 1]);
        total = Math.addExact(total, edges[segment]);
      }
      if (whole == null || total != whole) throw new ArithmeticException("logical edge sum");
    }
    addStats(nextStats[0], whole);
    if (full) {
      addStats(nextStats[1], whole);
      for (int segment = 0; segment < 5; segment++)
        addStats(nextStats[segment + 2], edges[segment]);
    }
    nextMasks[frame.validMask] = Math.addExact(nextMasks[frame.validMask], 1);
    nextStatuses[frame.completionStatus] = Math.addExact(nextStatuses[frame.completionStatus], 1);
    nextRegistrations[frame.registrationResult] =
        Math.addExact(nextRegistrations[frame.registrationResult], 1);
    for (int marker = 0; marker < 6; marker++)
      if ((frame.validMask & (1 << marker)) != 0)
        nextMarkers[marker] = Math.addExact(nextMarkers[marker], 1);
    int classIndex = full ? 0 : 1;
    nextClasses[classIndex] = Math.addExact(nextClasses[classIndex], 1);
    if (frame.crossed) nextClasses[2] = Math.addExact(nextClasses[2], 1);
    statistics[coordinate] = nextStats;
    masks[coordinate] = nextMasks;
    statuses[coordinate] = nextStatuses;
    registrations[coordinate] = nextRegistrations;
    markerCounts[coordinate] = nextMarkers;
    classifications[coordinate] = nextClasses;
  }

  /** Checked subtraction rejects both backward clocks and signed arithmetic overflow. */
  private static long elapsed(long start, long end) {
    try {
      long duration = Math.subtractExact(end, start);
      if (duration < 0) throw new ClockInvalidException();
      return duration;
    } catch (ArithmeticException overflow) {
      throw new ClockInvalidException();
    }
  }

  /** Stats layout is observations/timed/sum/max/fourteen noncumulative bins. */
  private static void addStats(long[] stats, Long duration) {
    stats[0] = Math.addExact(stats[0], 1);
    if (duration == null) return;
    long nextTimed = Math.addExact(stats[1], 1);
    long nextSum = Math.addExact(stats[2], duration);
    int bucket = 0;
    while (bucket < UPPER_BOUNDS.length && duration > UPPER_BOUNDS[bucket]) bucket++;
    long nextBucket = Math.addExact(stats[4 + bucket], 1);
    stats[1] = nextTimed;
    stats[2] = nextSum;
    stats[3] = Math.max(stats[3], duration);
    stats[4 + bucket] = nextBucket;
  }

  /** Unknown framework codes remain UNKNOWN, never inferred from Service outcome. */
  private static int statusIndex(int status) {
    return status == TransactionSynchronization.STATUS_COMMITTED
        ? 0
        : status == TransactionSynchronization.STATUS_ROLLED_BACK ? 1 : 2;
  }

  /** Checked counters never wrap, including the failure counters themselves. */
  private void increment(int counter) {
    try {
      counters[counter] = Math.addExact(counters[counter], 1);
    } catch (ArithmeticException overflow) {
      fail("COUNT_OVERFLOW");
      if (counter != 24 && counters[24] != Long.MAX_VALUE) counters[24]++;
      throw overflow;
    }
  }

  /** First failure is sticky, independently of valid partial logical coverage. */
  private void fail(String code) {
    if ("NONE".equals(failure)) failure = code;
  }

  /** Any hot observation fault is swallowed here; no business Throwable is accepted or mutated. */
  private synchronized void observerFault(Throwable observerFailure) {
    if (observerFailure instanceof ClockInvalidException) {
      fail("CLOCK_INVALID");
      if (counters[22] != Long.MAX_VALUE) counters[22]++;
    } else if (observerFailure instanceof ArithmeticException) {
      fail("COUNT_OVERFLOW");
      if (counters[24] != Long.MAX_VALUE) counters[24]++;
    } else {
      fail("OBSERVER_FAILED");
      if (counters[25] != Long.MAX_VALUE) counters[25]++;
    }
  }

  /** Record invalid capability use without releasing another invocation's state. */
  private synchronized void tokenFault() {
    increment(23);
    fail("TOKEN_INVALID");
  }

  /** Return independent cold aggregate maps; no clock or stage supplier is invoked. */
  synchronized Map<String, Object> snapshot() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("schemaVersion", 1);
    result.put("scope", "SERVICE_TRANSACTION_LOGICAL_OBSERVATION_V1");
    result.put("enabled", true);
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("runtimeVerified", runtimeVerified);
    facts.put("managerClassName", managerClassName);
    facts.put("managerIdentityHashCode", manager == null ? null : System.identityHashCode(manager));
    facts.put("dataSourceClassName", dataSourceClassName);
    facts.put(
        "dataSourceIdentityHashCode",
        dataSource == null ? null : System.identityHashCode(dataSource));
    facts.put("managerDataSourceSame", manager == null ? null : runtimeVerified);
    facts.put("rollbackOnCommitFailure", rollbackOnCommitFailure);
    facts.put("managerWrapped", false);
    facts.put("dataSourceWrapped", false);
    facts.put("newServiceAdviceAdded", false);
    facts.put("newMapperAdviceAdded", false);
    facts.put("synchronizationOrder", Integer.MAX_VALUE);
    facts.put("boundary", "SERVICE_TRANSACTION_LOGICAL_MARKERS");
    result.put("runtimeIdentityFacts", facts);
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("failureCode", failure);
    state.put("activeServices", active == null ? 0L : 1L);
    state.put("framesOutstanding", active == null ? 0L : 1L);
    state.put("pendingRegistrations", pending == null ? 0L : 1L);
    state.put("suppressionDepth", suppressionDepth);
    state.put("pendingRegistrationState", pending == null ? "NONE" : pending.state);
    Map<String, Object> counterMap = new LinkedHashMap<>();
    for (int counter = 0; counter < counters.length; counter++)
      counterMap.put(COUNTER_NAMES[counter], counters[counter]);
    state.put("counters", counterMap);
    result.put("collectorState", state);
    result.put("markerNames", new ArrayList<>(Arrays.asList(MARKER_NAMES)));
    List<Long> bounds = new ArrayList<>();
    for (long bound : UPPER_BOUNDS) bounds.add(bound);
    result.put("histogramUpperBoundsNs", bounds);
    List<Map<String, Object>> rows = new ArrayList<>();
    long fullTotal = 0;
    long partialTotal = 0;
    for (int coordinate = 0; coordinate < 112; coordinate++) {
      int dimensions = coordinate / 2;
      int inputSize = dimensions % 4 + 1;
      dimensions /= 4;
      int operation = dimensions % 2;
      int stageIndex = dimensions / 2;
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("entryStage", STAGES[stageIndex].name());
      row.put("operation", operation == 0 ? "apply" : "applyBatch");
      row.put("inputSize", inputSize);
      row.put("outcome", coordinate % 2 == 0 ? "NORMAL" : "THREW");
      row.put("wholeStats", statsMap(statistics[coordinate][0]));
      row.put("fullWholeStats", statsMap(statistics[coordinate][1]));
      Map<String, Object> segments = new LinkedHashMap<>();
      for (int segment = 0; segment < 5; segment++)
        segments.put(SEGMENT_NAMES[segment], statsMap(statistics[coordinate][segment + 2]));
      row.put("segments", segments);
      row.put("markerMaskBuckets", longs(masks[coordinate]));
      row.put("completionStatusCounts", namedCounts(STATUS_NAMES, statuses[coordinate]));
      row.put(
          "registrationResultCounts", namedCounts(REGISTRATION_NAMES, registrations[coordinate]));
      row.put("markerSeenCounts", namedCounts(MARKER_NAMES, markerCounts[coordinate]));
      row.put("fullLogicalCalls", classifications[coordinate][0]);
      row.put("partialLogicalCalls", classifications[coordinate][1]);
      row.put("crossStageCalls", classifications[coordinate][2]);
      rows.add(row);
      fullTotal = Math.addExact(fullTotal, classifications[coordinate][0]);
      partialTotal = Math.addExact(partialTotal, classifications[coordinate][1]);
    }
    result.put("cells", rows);
    boolean closed = active == null && pending == null && suppressionDepth == 0;
    boolean excluded =
        partialTotal != 0
            || counters[4] != 0
            || counters[5] != 0
            || counters[6] != 0
            || counters[7] != 0
            || counters[8] != 0
            || counters[9] != 0
            || counters[10] != 0;
    Map<String, Object> qualification = new LinkedHashMap<>();
    qualification.put("collectorHealthy", "NONE".equals(failure));
    qualification.put(
        "fullCoverage",
        fullTotal == 0
            ? "UNAVAILABLE"
            : !excluded && closed && "NONE".equals(failure) ? "COMPLETE" : "PARTIAL");
    qualification.put("closedQuiescent", closed);
    qualification.put("runtimeQualification", false);
    qualification.put("commitMeasured", false);
    qualification.put("physicalAckMeasured", false);
    qualification.put("observerOverheadUnknown", true);
    result.put("qualification", qualification);
    return result;
  }

  /** Stats copies do not expose mutable collector arrays. */
  private static Map<String, Object> statsMap(long[] stats) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("observations", stats[0]);
    result.put("timedCalls", stats[1]);
    result.put("sumNs", stats[2]);
    result.put("maxNs", stats[3]);
    result.put("buckets", longs(Arrays.copyOfRange(stats, 4, 18)));
    return result;
  }

  /** Build a fixed label-to-counter cold copy. */
  private static Map<String, Object> namedCounts(String[] names, long[] values) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (int index = 0; index < names.length; index++) result.put(names[index], values[index]);
    return result;
  }

  /** Box only at cold export, without retaining a per-call list. */
  private static List<Long> longs(long[] values) {
    List<Long> result = new ArrayList<>(values.length);
    for (long value : values) result.add(value);
    return result;
  }

  /** Separate observer faults/leaks from healthy missing terminal evidence. */
  synchronized void assertHealthyAfterClose() {
    if (!"NONE".equals(failure) || active != null || suppressionDepth != 0 || current.get() != null)
      throw new IllegalStateException("CM21_SERVICE_TRANSACTION_NOT_HEALTHY");
    if (!runtimeVerified || pending != null) throw new ObservationIncompleteException();
  }
}
