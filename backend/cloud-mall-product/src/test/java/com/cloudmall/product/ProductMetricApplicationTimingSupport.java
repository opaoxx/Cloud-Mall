package com.cloudmall.product;

import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.domain.po.ProductOnlineStatPO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.ProductMetricService;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.function.LongSupplier;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.springframework.aop.Advisor;
import org.springframework.aop.framework.Advised;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.context.ApplicationContext;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** Test-only constant-space durations around the original apply proxy and semantic Mapper calls. */
final class ProductMetricApplicationTimingSupport {
  /** Cold export boundaries; none claims an exact JTL sampling instant. */
  enum SnapshotKind {
    BASELINE,
    MEASUREMENT_ENDED,
    FINISH,
    COMPLETION
  }

  /** Fixed dimensions; no event, product, SQL or thread label is retained. */
  private enum Span {
    SERVICE_PROXY_TOTAL,
    INITIAL_APPLY_SNAPSHOT,
    ENSURE_STAT,
    LOCK_STAT,
    INSERT_APPLIED,
    UPDATE_STAT_AND_PROJECTION,
    CONTROL,
    ADVANCE_BY,
    ADVANCE
  }

  /** A short-lived capability bound to one helper and one admitted frame. */
  static final class CallToken {
    /** The helper that may complete this token. */
    private final ProductMetricApplicationTimingSupport owner;

    /** The current call's primitive-only frame, or null for a rejected entry. */
    private final Frame frame;

    /** Whether this token registered an active Service call. */
    private boolean admitted;

    /** Whether an admitted exit has already consumed the token. */
    private boolean closed;

    /** Bind the capability before any active-state mutation. */
    private CallToken(ProductMetricApplicationTimingSupport owner, Frame frame) {
      this.owner = owner;
      this.frame = frame;
    }
  }

  /** Per-active-call primitive state only, removed in the owning Service exit finally block. */
  private static final class Frame {
    /** Original Service entry stage. */
    private final ProductMetricPipelineLoadSupport.Stage enteredStage;

    /** Original operation index, apply=0 or applyBatch=1. */
    private final int operationIndex;

    /** Original input size minus one. */
    private final int sizeIndex;

    /** The arbitrary-origin Java start clock, never exported. */
    private long startedNs;

    /** Whether the Service start clock was obtained successfully. */
    private boolean clockValid;

    /** Depth of the sole permitted active timed Mapper call. */
    private int mapperDepth;

    /** Freeze dimensions before the original business invocation. */
    private Frame(ProductMetricPipelineLoadSupport.Stage stage, int operationIndex, int inputSize) {
      this.enteredStage = stage;
      this.operationIndex = operationIndex;
      this.sizeIndex = inputSize - 1;
    }
  }

  /** Nanosecond upper bounds, inclusive; a final separate overflow bucket follows. */
  private static final long[] UPPER_BOUNDS_NS = {
    100_000L,
    500_000L,
    1_000_000L,
    2_000_000L,
    5_000_000L,
    10_000_000L,
    20_000_000L,
    50_000_000L,
    100_000_000L,
    200_000_000L,
    500_000_000L,
    1_000_000_000L,
    3_000_000_000L
  };

  /** Calls, sum, maximum, fourteen buckets, and cross-boundary count occupy eighteen longs. */
  private static final int CELL_WIDTH = 18;

  /** Exactly seven stages, two operations, four sizes, two local outcomes and nine spans. */
  private static final int CELL_COUNT = 7 * 2 * 4 * 2 * 9;

  /** Whitelist methods are resolved once, never reflected or cached per business invocation. */
  private static final Map<Method, Span> BASE_SPANS = resolveSpans();

  /** Business methods declared by this Mapper, excluding its metadata accessor. */
  private static final Set<Method> BASE_BUSINESS = resolveBusinessMethods();

  /** Exact original Single10 owner-mode identity. */
  private final String runIdentity;

  /** Injected Java elapsed-time clock; no Python or wall clock is used. */
  private final LongSupplier nanoClock;

  /** Independently enabled transaction markers reuse the existing Mapper advice. */
  private final ProductMetricServiceTransactionObservationSupport transactionObservation;

  /** Optional dual-statement observer has its own independent failure and timing domain. */
  private final ProductMetricStatementEnvelopeSupport statementEnvelope;

  /** Constructed once; the primitive cell payload is exactly 145152 bytes. */
  private final long[][] cells = new long[CELL_COUNT][CELL_WIDTH];

  /** Only a currently active apply call is retained on its original thread. */
  private final ThreadLocal<Frame> currentFrame = new ThreadLocal<>();

  /** One original Mapper interceptor object, installed at most once. */
  private final MethodInterceptor advice = this::invokeMapper;

  /** Cold-built target aliases of the fixed method catalogue. */
  private Map<Method, Span> timedMethods = BASE_SPANS;

  /** Cold-built target aliases of declared business methods. */
  private Set<Method> businessMethods = BASE_BUSINESS;

  /** First fixed observational failure, never a business Throwable. */
  private volatile String failureCode = "NONE";

  /** Sticky coverage permission; unknown or failed observation cannot reset it. */
  private volatile boolean coverageComplete = true;

  /** Original Service entries successfully registered in active state. */
  private long serviceEntries;

  /** Completed original Service normal returns, independent of valid duration samples. */
  private long serviceNormalReturns;

  /** Completed original Service throws, independent of valid duration samples. */
  private long serviceThrows;

  /** Currently admitted Service frames, also the number of outstanding owned frames. */
  private long activeServiceCalls;

  /** Currently active timed Mapper calls. */
  private long activeMapperCalls;

  /** Apply-frame calls to declared but unmeasured business Mapper signatures. */
  private long unclassifiedMapperCalls;

  /** Unexpected nested Service entries; their noop exits cannot clear the outer frame. */
  private long nestedServiceCalls;

  /** Exact enhanced Mapper bean returned for constructor injection. */
  private Object installedMapper;

  /** Original target retained by the Mapper proxy. */
  private Object mapperTarget;

  /** Original Mapper datasource, obtained only by a cold metadata getter. */
  private DataSource mapperDataSource;

  /** Expected Mapper advisors after installing this sole timing advice. */
  private Advisor[] mapperAdvisors;

  /** Service baseline is captured only after the original load observer was installed. */
  private Object serviceProxy;

  /** Original Service target, captured by the first runtime proof. */
  private Object serviceTarget;

  /** Service advisor objects and order captured by the first runtime proof. */
  private Advisor[] serviceAdvisors;

  /** Fixed cold proof flags; false means not proven, not fabricated success. */
  private Map<String, Object> runtimeFacts = runtimeFacts(false);

  /** Whether a complete cold identity and transaction proof has succeeded. */
  private boolean runtimeProven;

  /** Allocate a batch1-only collector without installing a proxy, reading a clock or doing IO. */
  ProductMetricApplicationTimingSupport(
      String runIdentity, ProductMetricPipelineLoadSupport.Mode mode, LongSupplier nanoClock) {
    this(runIdentity, mode, nanoClock, null);
  }

  /** Bind optional transaction markers without changing the original advice or timing state. */
  ProductMetricApplicationTimingSupport(
      String runIdentity,
      ProductMetricPipelineLoadSupport.Mode mode,
      LongSupplier nanoClock,
      ProductMetricServiceTransactionObservationSupport transactionObservation) {
    this(runIdentity, mode, nanoClock, transactionObservation, null);
  }

  /** Bind the optional test-only statement observer without changing legacy marker ownership. */
  ProductMetricApplicationTimingSupport(
      String runIdentity,
      ProductMetricPipelineLoadSupport.Mode mode,
      LongSupplier nanoClock,
      ProductMetricServiceTransactionObservationSupport transactionObservation,
      ProductMetricStatementEnvelopeSupport statementEnvelope) {
    this.statementEnvelope = statementEnvelope;
    this.transactionObservation = transactionObservation;
    check(runIdentity != null && runIdentity.matches("[0-9a-f]{32}-batch1"), "IDENTITY_INVALID");
    check(mode == ProductMetricPipelineLoadSupport.Mode.BATCH1, "MODE_INVALID");
    this.runIdentity = runIdentity;
    this.nanoClock = Objects.requireNonNull(nanoClock);
  }

  /**
   * Install once before Service constructor injection, preserving the original target and advisors.
   */
  Object installMapper(Object bean) {
    try {
      // 1. Admit one stable original target and preserve any existing advisor objects.
      check(installedMapper == null && bean instanceof ProductMetricMapper, "INSTALL_INVALID");
      Object target = bean;
      Object enhanced;
      if (bean instanceof Advised advised) {
        check(!advised.isFrozen() && advised.getTargetSource().isStatic(), "INSTALL_INVALID");
        target = advised.getTargetSource().getTarget();
        Advisor[] original = advised.getAdvisors();
        advised.addAdvice(0, advice);
        Advisor[] after = advised.getAdvisors();
        check(
            after.length == original.length + 1 && after[0].getAdvice() == advice,
            "INSTALL_INVALID");
        for (int index = 0; index < original.length; index++)
          check(after[index + 1] == original[index], "INSTALL_INVALID");
        enhanced = bean;
      } else {
        check(!Modifier.isFinal(bean.getClass().getModifiers()), "INSTALL_INVALID");
        ProxyFactory factory = new ProxyFactory(bean);
        factory.setProxyTargetClass(true);
        factory.addAdvice(advice);
        enhanced = factory.getProxy();
      }
      check(target instanceof ProductMetricMapper, "INSTALL_INVALID");
      // 2. Retain only cold runtime identities, without opening a connection.
      mapperTarget = target;
      mapperDataSource = ((ProductMetricMapper) target).dataSource();
      check(mapperDataSource != null, "INSTALL_INVALID");
      installedMapper = enhanced;
      mapperAdvisors = ((Advised) enhanced).getAdvisors().clone();
      // 3. Build a bounded method catalogue before any apply invocation can be exposed.
      Map<Method, Span> aliases = new HashMap<>(BASE_SPANS);
      Set<Method> businessAliases = new HashSet<>(BASE_BUSINESS);
      for (Method candidate : target.getClass().getMethods()) {
        for (Map.Entry<Method, Span> entry : BASE_SPANS.entrySet())
          if (sameSignature(candidate, entry.getKey())) aliases.put(candidate, entry.getValue());
        for (Method original : BASE_BUSINESS)
          if (sameSignature(candidate, original)) businessAliases.add(candidate);
      }
      timedMethods = Map.copyOf(aliases);
      businessMethods = Set.copyOf(businessAliases);
      return enhanced;
    } catch (Throwable invalidInstallation) {
      fail("INSTALL_INVALID");
      throw invalid("INSTALL_INVALID");
    }
  }

  /** Read original association only; this original helper alone consumes its token. */
  boolean matchesCurrentCallToken(CallToken token) {
    return token != null
        && token.owner == this
        && token.admitted
        && !token.closed
        && token.frame != null
        && currentFrame.get() == token.frame;
  }

  /** Establish one primitive apply frame; all observation faults are sticky and non-throwing. */
  CallToken serviceEnter(
      ProductMetricPipelineLoadSupport.Stage enteredStage, String operation, int inputSize) {
    Frame frame = null;
    CallToken token = new CallToken(this, null);
    try {
      // 1. Refuse invalid or nested observation without replacing the original business call.
      if (currentFrame.get() != null) {
        synchronized (this) {
          nestedServiceCalls = Math.addExact(nestedServiceCalls, 1);
        }
        fail("UNEXPECTED_REENTRY");
        return token;
      }
      if (hasFailed()) return token;
      int operationIndex = "apply".equals(operation) ? 0 : "applyBatch".equals(operation) ? 1 : -1;
      if (enteredStage == null || operationIndex < 0 || inputSize < 1 || inputSize > 4) {
        fail("INPUT_UNKNOWN");
        return token;
      }
      frame = new Frame(enteredStage, operationIndex, inputSize);
      token = new CallToken(this, frame);
      currentFrame.set(frame);
      // 2. Commit entry and active state together before admitting this token.
      synchronized (this) {
        long nextEntries = Math.addExact(serviceEntries, 1);
        long nextActive = Math.addExact(activeServiceCalls, 1);
        serviceEntries = nextEntries;
        activeServiceCalls = nextActive;
        token.admitted = true;
      }
      try {
        // 3. Sample only the original Java duration clock outside the collector monitor.
        frame.startedNs = nanoClock.getAsLong();
        frame.clockValid = true;
      } catch (Throwable badClock) {
        fail("CLOCK_INVALID");
      }
      return token;
    } catch (Throwable observationFailure) {
      fail(
          observationFailure instanceof ArithmeticException ? "COUNT_OVERFLOW" : "OBSERVER_FAILED");
      if (frame != null && currentFrame.get() == frame) {
        currentFrame.remove();
        if (token.admitted) releaseService(false, false);
      }
      return new CallToken(this, null);
    }
  }

  /** End only the owning admitted token, attribute to entry stage, and release its frame once. */
  void serviceExit(
      CallToken token, boolean threw, ProductMetricPipelineLoadSupport.Stage observedExitStage) {
    if (token == null || token.owner != this || !token.admitted || token.closed) {
      fail("OBSERVER_FAILED");
      return;
    }
    if (currentFrame.get() != token.frame) {
      fail("OBSERVER_FAILED");
      return;
    }
    token.closed = true;
    long duration = -1;
    try {
      if (token.frame.mapperDepth != 0) fail("UNEXPECTED_REENTRY");
      if (observedExitStage == null) {
        fail("INPUT_UNKNOWN");
      } else if (token.frame.clockValid) {
        duration = elapsed(token.frame.startedNs);
      }
    } catch (Throwable observationFailure) {
      fail("OBSERVER_FAILED");
    } finally {
      // Commit the completed Service cell and state together so inflight snapshots stay coherent.
      synchronized (this) {
        try {
          if (duration >= 0)
            recordCell(
                token.frame,
                Span.SERVICE_PROXY_TOTAL,
                threw,
                duration,
                observedExitStage != token.frame.enteredStage);
        } catch (Throwable observationFailure) {
          fail("OBSERVER_FAILED");
        } finally {
          releaseService(threw, true);
        }
      }
      if (currentFrame.get() == token.frame) currentFrame.remove();
    }
  }

  /** Mark a hook-boundary fault without accepting or retaining an arbitrary Throwable. */
  void recordObserverFailure() {
    fail("OBSERVER_FAILED");
  }

  /** Expose the sole Mapper advice for installation and controlled offline invocations. */
  MethodInterceptor mapperAdvice() {
    return advice;
  }

  /** Prove cold original object/transaction identities; no connection or query is opened. */
  void verifyRuntime(ApplicationContext context, Object service) {
    try {
      // 1. Prove actual constructor-injected Mapper, original target and datasource identity.
      check(
          context != null && service instanceof Advised && installedMapper != null,
          "INSTALL_INVALID");
      Advised serviceAdvised = (Advised) service;
      check(serviceAdvised.getTargetSource().isStatic(), "INSTALL_INVALID");
      Object target = serviceAdvised.getTargetSource().getTarget();
      check(
          context.getBean(ProductMetricService.class) == service
              && context.getBean(ProductMetricMapper.class) == installedMapper
              && ReflectionTestUtils.getField(target, "mapper") == installedMapper,
          "INSTALL_INVALID");
      check(
          ((Advised) installedMapper).getTargetSource().getTarget() == mapperTarget
              && sameAdvisors(((Advised) installedMapper).getAdvisors(), mapperAdvisors)
              && ((ProductMetricMapper) mapperTarget).dataSource() == mapperDataSource
              && context.getBean(DataSource.class) == mapperDataSource,
          "INSTALL_INVALID");
      Advisor[] advisors = serviceAdvised.getAdvisors();
      // 2. Keep the original load observer and exactly one original transaction boundary.
      int loadObservers = 0;
      for (Advisor advisor : advisors)
        if (advisor.getAdvice() instanceof ProductMetricPipelineLoadSupport) loadObservers++;
      check(loadObservers == 1, "INSTALL_INVALID");
      for (String operation : List.of("apply", "applyBatch")) {
        Method method =
            ProductMetricService.class.getMethod(
                operation, "apply".equals(operation) ? ProductMetricEventDTO.class : List.class);
        int transactions = 0;
        for (Advisor advisor : advisors)
          if (advisor.getAdvice() instanceof TransactionInterceptor transaction) {
            var attribute =
                Objects.requireNonNull(transaction.getTransactionAttributeSource())
                    .getTransactionAttribute(method, serviceAdvised.getTargetClass());
            check(
                attribute != null
                    && attribute.getPropagationBehavior()
                        == TransactionDefinition.PROPAGATION_REQUIRES_NEW
                    && attribute.getTimeout() == 3
                    && attribute.getIsolationLevel()
                        == ("applyBatch".equals(operation)
                            ? TransactionDefinition.ISOLATION_REPEATABLE_READ
                            : TransactionDefinition.ISOLATION_DEFAULT),
                "INSTALL_INVALID");
            transactions++;
          }
        check(transactions == 1, "INSTALL_INVALID");
      }
      synchronized (this) {
        // 3. Capture the post-load-install baseline once; later proofs may only confirm it.
        if (serviceProxy == null) {
          serviceProxy = service;
          serviceTarget = target;
          serviceAdvisors = advisors.clone();
        } else {
          check(
              serviceProxy == service
                  && serviceTarget == target
                  && sameAdvisors(advisors, serviceAdvisors),
              "INSTALL_INVALID");
        }
        runtimeFacts = runtimeFacts(true);
        runtimeProven = true;
      }
    } catch (Throwable invalidRuntime) {
      synchronized (this) {
        runtimeProven = false;
        runtimeFacts = runtimeFacts(false);
      }
      fail("INSTALL_INVALID");
      throw invalid("INSTALL_INVALID");
    }
  }

  /** Lightweight control-point gate; it does not wait or attempt business recovery. */
  boolean hasFailed() {
    return !"NONE".equals(failureCode) || !coverageComplete;
  }

  /** Reject unproven, failed or unexpectedly active diagnostics at existing cold control points. */
  synchronized void assertHealthy(boolean requireIdle) {
    check(!hasFailed() && installedMapper != null && runtimeProven, "NOT_QUALIFIED");
    if (requireIdle) check(activeServiceCalls == 0 && activeMapperCalls == 0, "NOT_IDLE");
  }

  /**
   * Copy fixed primitives under a short lock and construct the cold closed-schema map afterward.
   */
  Map<String, Object> snapshot(
      SnapshotKind kind, ProductMetricPipelineLoadSupport.Stage reportedStage) {
    Objects.requireNonNull(kind);
    Objects.requireNonNull(reportedStage);
    long[][] copied = new long[CELL_COUNT][];
    Map<String, Object> state;
    Map<String, Object> facts;
    // 1. Copy all correlated primitive evidence at one collector observation point.
    synchronized (this) {
      for (int index = 0; index < CELL_COUNT; index++) copied[index] = cells[index].clone();
      state = new LinkedHashMap<>();
      state.put("activeServiceCalls", activeServiceCalls);
      state.put("activeMapperCalls", activeMapperCalls);
      state.put("serviceEntries", serviceEntries);
      state.put("serviceNormalReturns", serviceNormalReturns);
      state.put("serviceThrows", serviceThrows);
      state.put("unclassifiedMapperCalls", unclassifiedMapperCalls);
      state.put("nestedServiceCalls", nestedServiceCalls);
      state.put("framesOutstanding", activeServiceCalls);
      state.put("failureCode", failureCode);
      state.put("coverageComplete", coverageComplete);
      facts = new LinkedHashMap<>(runtimeFacts);
    }
    List<Map<String, Object>> exported = new ArrayList<>(CELL_COUNT);
    // 2. Construct fixed cold objects outside the monitor, never retaining call history.
    for (var stage : ProductMetricPipelineLoadSupport.Stage.values())
      for (int operationIndex = 0; operationIndex < 2; operationIndex++)
        for (int inputSize = 1; inputSize <= 4; inputSize++)
          for (int outcomeIndex = 0; outcomeIndex < 2; outcomeIndex++)
            for (Span span : Span.values()) {
              long[] values =
                  copied[cellIndex(stage, operationIndex, inputSize - 1, outcomeIndex, span)];
              Map<String, Object> cell = new LinkedHashMap<>();
              cell.put("stage", stage.name());
              cell.put("operation", operationIndex == 0 ? "apply" : "applyBatch");
              cell.put("inputSize", inputSize);
              cell.put("span", span.name());
              cell.put("outcome", outcomeIndex == 0 ? "NORMAL" : "THREW");
              cell.put("calls", values[0]);
              cell.put("sumNs", values[1]);
              cell.put("maxNs", values[2]);
              cell.put("buckets", Arrays.copyOfRange(values, 3, 17));
              cell.put("crossBoundaryCalls", values[17]);
              exported.add(cell);
            }
    Map<String, Object> result = new LinkedHashMap<>();
    // 3. Export only the reviewed shape; identity proof remains false until actually verified.
    result.put("schemaVersion", 1);
    result.put("scope", "CM21_APPLY_TIMING_DIAGNOSTIC_V1");
    result.put("runIdentity", runIdentity);
    result.put("mode", "batch1");
    result.put("enabled", true);
    result.put("clockScope", "JAVA_PROCESS_MONOTONIC_DURATION_ONLY");
    result.put("stagePolicy", "SERVICE_ENTRY_STAGE");
    result.put("histogramUpperBoundsNs", UPPER_BOUNDS_NS.clone());
    result.put("runtimeFacts", facts);
    result.put("observerState", state);
    result.put("cells", exported);
    result.put("snapshotKind", kind.name());
    result.put("reportedStage", reportedStage.name());
    return result;
  }

  /** Cold-check the sole observed template against the original Mapper target and owned pool. */
  boolean verifyStatementEnvelopeRuntime(ApplicationContext context, DataSource original) {
    return statementEnvelope == null
        || statementEnvelope.verifyRuntime(context, mapperTarget, original);
  }

  /**
   * Each Mapper entry suppresses nested SQL without changing the original timing failure domain.
   */
  private Object invokeMapper(MethodInvocation invocation) throws Throwable {
    if (statementEnvelope == null) return invokeMapperLegacy(invocation, null);
    ProductMetricStatementEnvelopeSupport.ScopeToken statementScope = null;
    try {
      Frame frame = currentFrame.get();
      boolean target = timedMethods.get(invocation.getMethod()) == Span.UPDATE_STAT_AND_PROJECTION;
      statementScope =
          statementEnvelope.openMapperScope(
              target,
              frame == null ? null : frame.enteredStage,
              frame == null ? -1 : frame.operationIndex,
              frame == null ? 0 : frame.sizeIndex + 1);
    } catch (Throwable metadataFailure) {
      statementEnvelope.recordObserverFailure();
    }
    try {
      return invokeMapperLegacy(invocation, statementScope);
    } finally {
      statementEnvelope.closeMapperScope(statementScope);
    }
  }

  /** Keep original Mapper execution outside all collector locks, including every failure path. */
  private Object invokeMapperLegacy(
      MethodInvocation invocation, ProductMetricStatementEnvelopeSupport.ScopeToken statementScope)
      throws Throwable {
    Frame frame = null;
    Span span = null;
    try {
      frame = currentFrame.get();
      if (frame != null) {
        Method method = invocation.getMethod();
        Span candidate = timedMethods.get(method);
        if (candidate == null && businessMethods.contains(method)) {
          synchronized (this) {
            try {
              unclassifiedMapperCalls = Math.addExact(unclassifiedMapperCalls, 1);
            } catch (ArithmeticException overflow) {
              fail("COUNT_OVERFLOW");
            }
            coverageComplete = false;
          }
        } else if (candidate == Span.LOCK_STAT
            && !Boolean.TRUE.equals(invocation.getArguments()[1])) {
          fail("INPUT_UNKNOWN");
        } else if (candidate != null && frame.mapperDepth != 0) {
          fail("UNEXPECTED_REENTRY");
        } else {
          span = candidate;
        }
      }
    } catch (Throwable observationFailure) {
      fail(
          observationFailure instanceof ArithmeticException ? "COUNT_OVERFLOW" : "OBSERVER_FAILED");
    }
    if (span == null) {
      boolean statementThrew = true;
      if (statementEnvelope != null) statementEnvelope.beforeMapperProceed(statementScope);
      try {
        Object returned = invocation.proceed();
        statementThrew = false;
        return returned;
      } finally {
        if (statementEnvelope != null)
          statementEnvelope.afterMapperProceed(statementScope, statementThrew);
      }
    }
    if (transactionObservation != null) {
      try {
        transactionObservation.mapperEnter(this, span.name());
      } catch (Throwable diagnosticFailure) {
        try {
          transactionObservation.recordObserverFailure();
        } catch (Throwable ignoredDiagnosticFailure) {
          // Preserve the original Mapper invocation and legacy timing failure domain.
        }
      }
    }
    long started = 0;
    boolean counted = false, validClock = false;
    frame.mapperDepth++;
    try {
      synchronized (this) {
        activeMapperCalls = Math.addExact(activeMapperCalls, 1);
        counted = true;
      }
      try {
        started = nanoClock.getAsLong();
        validClock = true;
      } catch (Throwable badClock) {
        fail("CLOCK_INVALID");
      }
    } catch (Throwable observerFailure) {
      fail(observerFailure instanceof ArithmeticException ? "COUNT_OVERFLOW" : "OBSERVER_FAILED");
    }
    boolean threw = true;
    if (statementEnvelope != null) statementEnvelope.beforeMapperProceed(statementScope);
    try {
      Object returned = invocation.proceed();
      threw = false;
      return returned;
    } finally {
      if (statementEnvelope != null) statementEnvelope.afterMapperProceed(statementScope, threw);
      try {
        if (validClock) {
          long duration = elapsed(started);
          if (duration >= 0) recordCell(frame, span, threw, duration, false);
        }
      } catch (Throwable observationFailure) {
        fail("OBSERVER_FAILED");
      } finally {
        frame.mapperDepth--;
        if (counted)
          synchronized (this) {
            if (activeMapperCalls > 0) activeMapperCalls--;
            else fail("OBSERVER_FAILED");
          }
      }
    }
  }

  /**
   * A negative elapsed value is invalid; a short signed wrap still produces its correct long delta.
   */
  private long elapsed(long started) {
    try {
      long duration = nanoClock.getAsLong() - started;
      if (duration < 0) fail("CLOCK_INVALID");
      return duration;
    } catch (Throwable badClock) {
      fail("CLOCK_INVALID");
      return -1;
    }
  }

  /**
   * Prepare all cell candidates before committing any primitive, retaining consistency on overflow.
   */
  private synchronized void recordCell(
      Frame frame, Span span, boolean threw, long duration, boolean crossed) {
    long[] cell =
        cells[
            cellIndex(
                frame.enteredStage, frame.operationIndex, frame.sizeIndex, threw ? 1 : 0, span)];
    int bucket = 0;
    while (bucket < UPPER_BOUNDS_NS.length && duration > UPPER_BOUNDS_NS[bucket]) bucket++;
    try {
      long calls = Math.addExact(cell[0], 1);
      long sum = Math.addExact(cell[1], duration);
      long maximum = Math.max(cell[2], duration);
      long bucketCount = Math.addExact(cell[3 + bucket], 1);
      long cross = Math.addExact(cell[17], crossed ? 1 : 0);
      cell[0] = calls;
      cell[1] = sum;
      cell[2] = maximum;
      cell[3 + bucket] = bucketCount;
      cell[17] = cross;
    } catch (ArithmeticException overflow) {
      fail("COUNT_OVERFLOW");
    }
  }

  /** Release the owned active state even when completed-count arithmetic has become invalid. */
  private synchronized void releaseService(boolean threw, boolean completed) {
    try {
      long normal =
          completed && !threw ? Math.addExact(serviceNormalReturns, 1) : serviceNormalReturns;
      long failed = completed && threw ? Math.addExact(serviceThrows, 1) : serviceThrows;
      check(activeServiceCalls > 0, "ACTIVE_INVALID");
      serviceNormalReturns = normal;
      serviceThrows = failed;
      activeServiceCalls--;
    } catch (Throwable invalidState) {
      fail(invalidState instanceof ArithmeticException ? "COUNT_OVERFLOW" : "OBSERVER_FAILED");
      if (activeServiceCalls > 0) activeServiceCalls--;
    }
  }

  /** Preserve the first fixed failure and never recover coverage at a later stage. */
  private synchronized void fail(String code) {
    if ("NONE".equals(failureCode)) failureCode = code;
    coverageComplete = false;
  }

  /** Fixed product index; no key can grow with traffic or contain a business identifier. */
  private static int cellIndex(
      ProductMetricPipelineLoadSupport.Stage stage,
      int operation,
      int size,
      int outcome,
      Span span) {
    return ((((stage.ordinal() * 2 + operation) * 4 + size) * 2 + outcome) * 9 + span.ordinal());
  }

  /** Resolve only the eight reviewed public semantic signatures at class initialization. */
  private static Map<Method, Span> resolveSpans() {
    try {
      Map<Method, Span> result = new HashMap<>();
      result.put(
          ProductMetricMapper.class.getMethod(
              "initialApplySnapshot", String.class, long.class, Long.class),
          Span.INITIAL_APPLY_SNAPSHOT);
      result.put(
          ProductMetricMapper.class.getMethod("ensureStat", long.class, LocalDateTime.class),
          Span.ENSURE_STAT);
      result.put(
          ProductMetricMapper.class.getMethod("stat", long.class, boolean.class), Span.LOCK_STAT);
      result.put(
          ProductMetricMapper.class.getMethod(
              "insertApplied",
              ProductMetricEventDTO.class,
              String.class,
              long.class,
              LocalDateTime.class),
          Span.INSERT_APPLIED);
      result.put(
          ProductMetricMapper.class.getMethod(
              "updateStat", ProductOnlineStatPO.class, String.class, LocalDateTime.class),
          Span.UPDATE_STAT_AND_PROJECTION);
      result.put(ProductMetricMapper.class.getMethod("control", boolean.class), Span.CONTROL);
      result.put(ProductMetricMapper.class.getMethod("advanceBy", int.class), Span.ADVANCE_BY);
      result.put(ProductMetricMapper.class.getMethod("advance"), Span.ADVANCE);
      return Map.copyOf(result);
    } catch (ReflectiveOperationException invalidContract) {
      throw new ExceptionInInitializerError("CM21_TIMING_MAPPER_SIGNATURE_INVALID");
    }
  }

  /** Exclude Object/infrastructure and the pure datasource getter before frame classification. */
  private static Set<Method> resolveBusinessMethods() {
    Set<Method> result = new HashSet<>();
    for (Method method : ProductMetricMapper.class.getDeclaredMethods())
      if (Modifier.isPublic(method.getModifiers()) && !"dataSource".equals(method.getName()))
        result.add(method);
    return Set.copyOf(result);
  }

  /** Compare fixed startup metadata without inspecting invocation arguments. */
  private static boolean sameSignature(Method left, Method right) {
    return left.getName().equals(right.getName())
        && Arrays.equals(left.getParameterTypes(), right.getParameterTypes());
  }

  /** Require the exact advisor objects and original order, not merely equal classes. */
  private static boolean sameAdvisors(Advisor[] current, Advisor[] expected) {
    if (current.length != expected.length) return false;
    for (int index = 0; index < current.length; index++)
      if (current[index] != expected[index]) return false;
    return true;
  }

  /** Build the fixed proof shape; false deliberately represents unproven startup facts. */
  private static Map<String, Object> runtimeFacts(boolean proven) {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String name :
        List.of(
            "serviceProxySame",
            "serviceTargetSame",
            "serviceAdvisorsPreserved",
            "mapperInjectedIdentityMatched",
            "mapperOriginalTargetSame",
            "mapperDataSourceSame",
            "mapperAdvisorsPreserved",
            "singleMapperInstallVerified",
            "transactionContractPreserved")) result.put(name, proven);
    result.put("serviceTimingAdviceAdded", false);
    result.put("batchWaitMeasured", false);
    result.put("physicalAckMeasured", false);
    result.put("commitMeasured", false);
    result.put("boundary", "LOAD_OBSERVER_PROCEED_BRACKET");
    result.put("excludedMapperPaths", "NO_APPLY_FRAME");
    return result;
  }

  /** Throw a fixed cold-path diagnostic code without exposing unknown failure text. */
  private static void check(boolean condition, String code) {
    if (!condition) throw invalid(code);
  }

  /** Create only a bounded fixed diagnostic exception. */
  private static IllegalStateException invalid(String code) {
    return new IllegalStateException("CM21_TIMING_" + code);
  }
}
