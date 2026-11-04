package com.cloudmall.product;

import java.sql.Connection;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.LongSupplier;

/** Default-off test bridge; fake readiness never authorizes database observation or freezing. */
final class TransactionPhaseAnchorBridge {
  /** Fixed admission count, interval and shared deadline inherited from the offline contract. */
  private static final int COHORT_SIZE = 8;

  /** Admission and completion bounds use only the explicitly injected fake clock. */
  private static final long ADMISSION_NS = 1_000_000_000L, COMPLETION_NS = 5_000_000_000L;

  /** Null dependencies are intentional in the disabled bridge and are never consulted. */
  private final LongSupplier clock;

  /** Explicitly injected fake callback; no SQL implementation exists in this bridge. */
  private final AnchorCallback callback;

  /** Immutable, pre-established fake identities; lookup never acquires a connection. */
  private final Map<Long, Identity> identities;

  /** At most the first eight eligible attempts are retained, including failed observations. */
  private final Map<Long, Attempt> selected = new LinkedHashMap<>();

  /** Exclusions retain counts in fixed categories instead of retaining unlimited calls. */
  private final Map<String, Long> excluded = new LinkedHashMap<>();

  /** Bounded full-entry identity memory rejects reuse even when the first entry was excluded. */
  private final Set<Long> seenCalls = new HashSet<>();

  /** The existing PhaseTiming call bound also limits fake deduplication memory. */
  private static final int SEEN_CALL_LIMIT = 32768;

  /** Explicit fake capture acknowledgement cannot be inferred from PhaseTiming.begin. */
  private boolean acknowledged;

  /** Shared bounds and event sequence are not reset by calls or callbacks. */
  private long captureAck, lastTime = Long.MIN_VALUE, entrySequence;

  /** Model failures are evidence gaps and cannot replace original operation outcomes. */
  private boolean modelUnknown;

  /** A complete identity supplied by a fake test, never inferred from an anchor or clock. */
  record Identity(String owner, String uuid, long connection, long pfsThread) {}

  /** Same-call and same-borrow context passed to the explicitly fake observation. */
  record Context(long call, long borrow, long javaThread, Identity identity) {}

  /** Fake client completion flags are not statements about server history completion. */
  record AnchorOutcome(boolean valid, boolean drained, boolean resourcesClosed) {}

  /** The two fake observations bracket exactly one original successful commit. */
  enum Side {
    PRE,
    POST
  }

  /** Fake observations may fail independently of the original JDBC delegate. */
  @FunctionalInterface
  interface AnchorCallback {
    /** Produces an in-memory observation; implementations in this task must not execute SQL. */
    AnchorOutcome observe(Context context, Side side) throws Throwable;
  }

  /** Original reflection invocation with its unchanged return value and throwable identity. */
  @FunctionalInterface
  interface ThrowingDelegate {
    /** Invokes the original operation exactly once. */
    Object invoke() throws Throwable;
  }

  private TransactionPhaseAnchorBridge(
      LongSupplier clock, AnchorCallback callback, Map<Long, Identity> identities) {
    this.clock = clock;
    this.callback = callback;
    this.identities = identities;
  }

  /** Disabled construction does not read environment, clock, identity or callback state. */
  static TransactionPhaseAnchorBridge disabled() {
    return new TransactionPhaseAnchorBridge(null, null, null);
  }

  /** Rejects any attempted live enablement before the Pipeline constructor creates resources. */
  static void requireDisabledProperty(String value) {
    if (!"false".equals(value)) {
      throw new IllegalArgumentException("CM21_PHASE_ANCHOR_LIVE_NO_GO");
    }
  }

  /** Explicit same-package fake seam; no production-style constructor invokes this factory. */
  static TransactionPhaseAnchorBridge fakeOnly(
      LongSupplier clock, AnchorCallback callback, Map<Long, Identity> identities) {
    if (clock == null || callback == null || identities == null) {
      throw new IllegalArgumentException("CM21_FAKE_DEPENDENCIES_REQUIRED");
    }
    return new TransactionPhaseAnchorBridge(clock, callback, Map.copyOf(identities));
  }

  /** Distinguishes explicit fake tests without inspecting dependencies on the disabled path. */
  boolean enabledForFake() {
    return clock != null;
  }

  /** Sets the sole explicit fake acknowledgement; repeated or overflowing bounds are UNKNOWN. */
  synchronized void captureAcknowledged(long ackNs) {
    if (!enabledForFake()) return;
    if (acknowledged || ackNs > Long.MAX_VALUE - COMPLETION_NS) {
      modelUnknown = true;
      return;
    }
    acknowledged = true;
    captureAck = ackNs;
    lastTime = ackNs;
  }

  /** Selects the first eight top-level apply attempts under this same admission lock. */
  synchronized void serviceEntered(long call, long parent, String method) {
    if (!enabledForFake()) return;
    try {
      long now = now();
      long sequence = ++entrySequence;
      if (sequence <= 0) modelUnknown = true;
      String exclusion =
          !acknowledged || now < captureAck
              ? "BEFORE_CAPTURE"
              : call <= 0
                  ? "CALL_ZERO"
                  : parent != 0
                      ? "NESTED"
                      : !"apply".equals(method)
                          ? "OTHER_CALLER"
                          : now - captureAck >= ADMISSION_NS
                              ? "LATE"
                              : selected.size() == COHORT_SIZE ? "OVER_K" : null;
      if (call > 0 && seenCalls.contains(call)) {
        modelUnknown = true;
        exclusion = "DUPLICATE_CALL";
      } else if (call > 0 && seenCalls.size() == SEEN_CALL_LIMIT) {
        modelUnknown = true;
        exclusion = "ENTRY_CAPACITY";
      } else if (call > 0) {
        seenCalls.add(call);
      }
      if (exclusion != null) {
        excluded.merge(exclusion, 1L, Long::sum);
        return;
      }
      selected.put(call, new Attempt(call, sequence, now, Thread.currentThread().getId()));
    } catch (Throwable observation) {
      modelUnknown = true;
    }
  }

  /** Records the original outer Service terminal, after the original transaction cleanup. */
  synchronized void serviceTerminal(long call, Throwable original) {
    if (!enabledForFake()) return;
    Attempt attempt = selected.get(call);
    if (attempt == null) return;
    try {
      if (attempt.serviceTerminal) attempt.unknown = true;
      attempt.serviceTerminal = true;
      attempt.serviceFailed = original != null;
      attempt.terminalTime = now();
      if (attempt.thread != Thread.currentThread().getId()) attempt.unknown = true;
      if (attempt.commits == 0) attempt.unknown = true;
    } catch (Throwable observation) {
      modelUnknown = true;
      attempt.unknown = true;
    }
  }

  /** Brackets one original commit with fake callbacks; every observer failure stays separate. */
  Object commit(
      long call,
      long borrow,
      long driverConnectionId,
      Connection connection,
      ThrowingDelegate delegate)
      throws Throwable {
    // 1. Disabled operation does not consult the supplied connection or observer dependencies.
    if (!enabledForFake()) return delegate.invoke();
    Prepared prepared = prepare(call, borrow, driverConnectionId);
    Attempt attempt = prepared == null ? null : prepared.attempt();
    if (prepared != null && prepared.observe()) observe(attempt, Side.PRE);
    // 2. An original throwable remains the same object; failure never triggers a post callback.
    Object returned;
    try {
      returned = delegate.invoke();
    } catch (Throwable original) {
      delegateTerminal(attempt, true);
      throw original;
    }
    // 3. Post finishes in the held borrow before the original caller performs cleanup.
    delegateTerminal(attempt, false);
    if (prepared != null && prepared.observe()) observe(attempt, Side.POST);
    else if (attempt != null) {
      synchronized (this) {
        attempt.postFinal = true;
      }
    }
    return returned;
  }

  private synchronized Prepared prepare(long call, long borrow, long connection) {
    Attempt attempt = selected.get(call);
    if (attempt == null) return null;
    try {
      attempt.commits++;
      Identity identity = identities.get(connection);
      if (attempt.commits != 1
          || borrow <= 0
          || attempt.serviceTerminal
          || attempt.thread != Thread.currentThread().getId()
          || !validIdentity(identity, connection)) {
        attempt.unknown = true;
        return new Prepared(attempt, false);
      }
      for (Attempt other : selected.values()) {
        if (other != attempt && other.borrow == borrow) {
          attempt.unknown = true;
          other.unknown = true;
          return new Prepared(attempt, false);
        }
      }
      attempt.borrow = borrow;
      attempt.context = new Context(call, borrow, attempt.thread, identity);
      attempt.observationStarted = true;
      now();
      return new Prepared(attempt, true);
    } catch (Throwable observation) {
      modelUnknown = true;
      attempt.unknown = true;
      return new Prepared(attempt, false);
    }
  }

  private static boolean validIdentity(Identity identity, long connection) {
    return identity != null
        && identity.owner() != null
        && identity.owner().matches("[0-9a-f]{32}")
        && identity.uuid() != null
        && !identity.uuid().isBlank()
        && identity.connection() == connection
        && connection > 0
        && identity.pfsThread() > 0;
  }

  private synchronized void delegateTerminal(Attempt attempt, boolean failed) {
    if (attempt == null) return;
    attempt.delegateTerminal = true;
    attempt.delegateFailed = failed;
    if (failed) {
      attempt.postFinal = true;
      attempt.unknown = true;
    }
  }

  /**
   * Callback execution is deliberately outside the cohort lock; completion is published after it.
   */
  private void observe(Attempt attempt, Side side) {
    AnchorOutcome outcome = null;
    try {
      outcome = callback.observe(attempt.context, side);
    } catch (Throwable observation) {
      // A fake failure is UNKNOWN even when the delegate subsequently succeeds.
    }
    synchronized (this) {
      try {
        now();
      } catch (Throwable observation) {
        modelUnknown = true;
      }
      boolean complete = outcome != null && outcome.drained() && outcome.resourcesClosed();
      if (outcome == null || !outcome.valid() || !complete) attempt.unknown = true;
      if (side == Side.PRE) {
        attempt.preFinal = true;
        attempt.preComplete = complete;
      } else {
        attempt.postFinal = true;
        attempt.postComplete = complete;
      }
    }
  }

  /** Immutable bounded fake facts; no history, root matching, SQL budget or live GO is inferred. */
  synchronized Map<String, Object> snapshot() {
    if (!enabledForFake()) return Map.of("enabled", false);
    long currentTime;
    try {
      currentTime = now();
    } catch (Throwable observation) {
      modelUnknown = true;
      currentTime = lastTime;
    }
    boolean ready = acknowledged && selected.size() == COHORT_SIZE && !modelUnknown;
    boolean unknown = modelUnknown || selected.size() != COHORT_SIZE;
    List<Map<String, Object>> attempts = new ArrayList<>();
    for (Attempt attempt : selected.values()) {
      boolean finalCommit =
          attempt.commits == 0
              ? attempt.serviceTerminal
              : attempt.delegateTerminal && attempt.postFinal;
      if (attempt.observationStarted) {
        finalCommit &= attempt.preComplete && (attempt.delegateFailed || attempt.postComplete);
      }
      boolean timely =
          attempt.serviceTerminal && attempt.terminalTime <= captureAck + COMPLETION_NS;
      ready &= finalCommit && timely;
      unknown |= attempt.unknown || !finalCommit || !timely;
      Map<String, Object> fact = new LinkedHashMap<>();
      fact.put("call", attempt.call);
      fact.put("entrySequence", attempt.sequence);
      fact.put("startedNanos", attempt.started);
      fact.put("javaThread", attempt.thread);
      fact.put("borrow", attempt.borrow);
      fact.put("commits", attempt.commits);
      fact.put("serviceTerminal", attempt.serviceTerminal);
      fact.put("serviceFailed", attempt.serviceFailed);
      fact.put("delegateTerminal", attempt.delegateTerminal);
      fact.put("delegateFailed", attempt.delegateFailed);
      fact.put("preFinal", attempt.preFinal);
      fact.put("preComplete", attempt.preComplete);
      fact.put("postFinal", attempt.postFinal);
      fact.put("postComplete", attempt.postComplete);
      fact.put("unknown", attempt.unknown);
      if (attempt.context != null) {
        fact.put("owner", attempt.context.identity().owner());
        fact.put("uuid", attempt.context.identity().uuid());
        fact.put("connection", attempt.context.identity().connection());
        fact.put("pfsThread", attempt.context.identity().pfsThread());
      }
      attempts.add(Collections.unmodifiableMap(fact));
    }
    if (currentTime > captureAck + COMPLETION_NS) {
      ready = false;
      unknown = true;
    }
    return Map.of(
        "schema",
        "cm21-phase-anchor-fake-v1",
        "enabled",
        true,
        "captureAcknowledged",
        acknowledged,
        "captureAckNanos",
        captureAck,
        "selected",
        List.copyOf(attempts),
        "excluded",
        Map.copyOf(excluded),
        "freezeReady",
        ready,
        "unknown",
        unknown,
        "liveAllowed",
        false);
  }

  private long now() {
    long value = clock.getAsLong();
    if (value < lastTime) modelUnknown = true;
    lastTime = value;
    return value;
  }

  /** A rejected fake observation still records the original selected delegate's terminal. */
  private record Prepared(Attempt attempt, boolean observe) {}

  /** Fixed-size mutable state is never exported directly and never retains JDBC resources. */
  private static final class Attempt {
    /** Exact original identity and lock-ordered entry facts. */
    final long call, sequence, started, thread;

    /** One held-borrow association established before the first fake pre callback. */
    long borrow, terminalTime;

    /** Number of original commit attempts, including rejected duplicate observations. */
    int commits;

    /** Fake-only pre-established identity, absent when the selected sample lacks qualification. */
    Context context;

    /** Independent terminal flags never convert an original failure to a success. */
    boolean serviceTerminal,
        serviceFailed,
        delegateTerminal,
        delegateFailed,
        observationStarted,
        preFinal,
        preComplete,
        postFinal,
        postComplete,
        unknown;

    private Attempt(long call, long sequence, long started, long thread) {
      this.call = call;
      this.sequence = sequence;
      this.started = started;
      this.thread = thread;
    }
  }
}
