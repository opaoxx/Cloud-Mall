package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;

import com.mysql.cj.jdbc.JdbcConnection;
import java.lang.reflect.AccessibleObject;
import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.aopalliance.intercept.MethodInvocation;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.datasource.ConnectionHolder;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** Real Spring transaction ordering over memory-only JDBC; no database equivalence is claimed. */
class TransactionPhaseAnchorBridgeTest {
  /** Complete explicitly fake identity; no server query establishes these values. */
  private static final TransactionPhaseAnchorBridge.Identity IDENTITY =
      new TransactionPhaseAnchorBridge.Identity(
          "0123456789abcdef0123456789abcdef", "fake-uuid", 31, 53);

  /** Callback response completed in memory, not a native history event. */
  private static final TransactionPhaseAnchorBridge.AnchorOutcome COMPLETE =
      new TransactionPhaseAnchorBridge.AnchorOutcome(true, true, true);

  @Test
  void disabledNeverReadsClockIdentityOrCallback() throws Throwable {
    TransactionPhaseAnchorBridge bridge = TransactionPhaseAnchorBridge.disabled();
    Object marker = new Object();
    bridge.captureAcknowledged(1);
    bridge.serviceEntered(1, 0, "apply");
    assertSame(marker, bridge.commit(0, 0, 0, null, () -> marker));
    bridge.serviceTerminal(1, null);
    assertEquals(Map.of("enabled", false), bridge.snapshot());
  }

  @Test
  void trueAndUnknownPropertiesFailClosed() {
    TransactionPhaseAnchorBridge.requireDisabledProperty("false");
    for (String value : new String[] {"true", "TRUE", "", "yes", null}) {
      IllegalArgumentException rejected =
          assertThrows(
              IllegalArgumentException.class,
              () -> TransactionPhaseAnchorBridge.requireDisabledProperty(value));
      assertEquals("CM21_PHASE_ANCHOR_LIVE_NO_GO", rejected.getMessage());
    }
  }

  @Test
  void pipelinePropertyRejectsBeforeResources() throws Exception {
    String previous = System.getProperty("cloudmall.cm21.phase-anchor");
    ProductMetricQueryInfoCacheSupport.ActorHook previousHook =
        ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.get();
    AtomicInteger resourceActions = new AtomicInteger();
    try {
      ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.set(
          (kind, admission) -> resourceActions.incrementAndGet());
      System.setProperty("cloudmall.cm21.phase-anchor", "true");
      Class<?> pipeline =
          Class.forName(ProductOnlineMetricsPipelineIT.class.getName() + "$Pipeline");
      Constructor<?> constructor = pipeline.getDeclaredConstructor(String.class, int.class);
      constructor.setAccessible(true);
      Throwable thrown =
          assertThrows(
                  java.lang.reflect.InvocationTargetException.class,
                  () -> constructor.newInstance("load", 1))
              .getCause();
      assertInstanceOf(IllegalArgumentException.class, thrown);
      assertEquals("CM21_QUERYINFO_PHASE_ANCHOR_DISABLED_REQUIRED", thrown.getMessage());
      assertEquals(0, resourceActions.get());
    } finally {
      if (previousHook == null) ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.remove();
      else ProductMetricQueryInfoCacheSupport.ACTOR_HOOK.set(previousHook);
      if (previous == null) System.clearProperty("cloudmall.cm21.phase-anchor");
      else System.setProperty("cloudmall.cm21.phase-anchor", previous);
    }
  }

  @Test
  void defaultPhaseTimingUsesDisabledBridgeWithRealSpring() throws Throwable {
    Framework fixture = new Framework(TransactionPhaseAnchorBridge.disabled());
    Object marker = new Object();
    assertSame(marker, fixture.apply(() -> fixture.transaction.execute(status -> marker)));
    assertEquals(
        List.of("setAutoCommit:false", "commit", "setAutoCommit:true", "close"),
        fixture.jdbc.calls);
    assertEquals(0, fixture.jdbc.sqlCalls);
    fixture.assertReleased();
  }

  @Test
  void realSpringCommitPostCleanupAndServiceTerminalOrder() throws Throwable {
    AtomicLong clock = new AtomicLong(100);
    FakeJdbc jdbc = new FakeJdbc();
    TransactionPhaseAnchorBridge bridge =
        fake(
            clock,
            (context, side) -> {
              jdbc.calls.add(side.name());
              if (side == TransactionPhaseAnchorBridge.Side.POST) jdbc.dirty = true;
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              return COMPLETE;
            });
    bridge.captureAcknowledged(100);
    Framework fixture = new Framework(bridge, jdbc);
    Object marker = new Object();
    assertSame(
        marker,
        fixture.apply(
            () ->
                fixture.transaction.execute(
                    status -> {
                      ConnectionHolder holder =
                          (ConnectionHolder)
                              TransactionSynchronizationManager.getResource(fixture.observed);
                      assertNotNull(holder);
                      assertTrue(holder.hasTimeout());
                      assertTrue(holder.getTimeToLiveInMillis() > 0);
                      assertTrue(holder.getTimeToLiveInMillis() <= 3000);
                      return marker;
                    })));
    assertEquals(
        List.of("setAutoCommit:false", "PRE", "commit", "POST", "setAutoCommit:true", "close"),
        jdbc.calls);
    assertTrue(
        jdbc.dirtyAtClose, "post-dirty is retained rather than silently reset by the observer");
    Map<String, Object> attempt = selected(bridge).get(0);
    assertEquals(true, attempt.get("serviceTerminal"));
    assertEquals(true, attempt.get("postComplete"));
    assertEquals(0, jdbc.sqlCalls);
    fixture.assertReleased();
  }

  @Test
  void rollbackOnlyDoesNotCreateAnchorOrCommit() throws Throwable {
    AtomicLong clock = new AtomicLong(100);
    AtomicInteger callbacks = new AtomicInteger();
    TransactionPhaseAnchorBridge bridge =
        fake(
            clock,
            (context, side) -> {
              callbacks.incrementAndGet();
              return COMPLETE;
            });
    bridge.captureAcknowledged(100);
    Framework fixture = new Framework(bridge);
    fixture.apply(
        () ->
            fixture.transaction.execute(
                status -> {
                  status.setRollbackOnly();
                  return null;
                }));
    assertEquals(
        List.of("setAutoCommit:false", "rollback", "setAutoCommit:true", "close"),
        fixture.jdbc.calls);
    assertEquals(0, callbacks.get());
    assertEquals(0, selected(bridge).get(0).get("commits"));
    assertEquals(true, selected(bridge).get(0).get("unknown"));
    fixture.assertReleased();
  }

  @Test
  void originalBusinessFailureSurvivesSpringAndAdvice() throws Throwable {
    AtomicLong clock = new AtomicLong(100);
    TransactionPhaseAnchorBridge bridge =
        fake(clock, (context, side) -> fail("rollback cannot anchor"));
    bridge.captureAcknowledged(100);
    Framework fixture = new Framework(bridge);
    RuntimeException original = new RuntimeException("original-business");
    assertSame(
        original,
        assertThrows(
            RuntimeException.class,
            () ->
                fixture.apply(
                    () ->
                        fixture.transaction.execute(
                            status -> {
                              throw original;
                            }))));
    assertTrue(fixture.jdbc.calls.contains("rollback"));
    assertEquals(true, selected(bridge).get(0).get("serviceFailed"));
    fixture.assertReleased();
  }

  @Test
  void jdbcDelegateFailureIsSameObjectAndPostIsSkipped() {
    AtomicLong clock = new AtomicLong(100);
    List<String> calls = new ArrayList<>();
    TransactionPhaseAnchorBridge bridge =
        fake(
            clock,
            (context, side) -> {
              calls.add(side.name());
              return COMPLETE;
            });
    bridge.captureAcknowledged(100);
    bridge.serviceEntered(1, 0, "apply");
    SQLException original = new SQLException("original-commit");
    assertSame(
        original,
        assertThrows(
            SQLException.class,
            () ->
                bridge.commit(
                    1,
                    1,
                    31,
                    null,
                    () -> {
                      calls.add("commit");
                      throw original;
                    })));
    bridge.serviceTerminal(1, original);
    assertEquals(List.of("PRE", "commit"), calls);
    assertEquals(true, selected(bridge).get(0).get("postFinal"));
    assertEquals(false, selected(bridge).get(0).get("postComplete"));
  }

  @Test
  void springMayWrapCommitFailureButKeepsOriginalCauseAndCleanup() throws Throwable {
    AtomicLong clock = new AtomicLong(100);
    List<String> observations = new ArrayList<>();
    TransactionPhaseAnchorBridge bridge =
        fake(
            clock,
            (context, side) -> {
              observations.add(side.name());
              return COMPLETE;
            });
    bridge.captureAcknowledged(100);
    Framework fixture = new Framework(bridge);
    SQLException original = new SQLException("commit-failure");
    fixture.jdbc.commitFailure = original;
    TransactionSystemException failure =
        assertThrows(
            TransactionSystemException.class,
            () -> fixture.apply(() -> fixture.transaction.execute(status -> "marker")));
    assertSame(original, failure.getCause());
    assertEquals(List.of("PRE"), observations);
    assertFalse(fixture.manager.isRollbackOnCommitFailure());
    assertFalse(fixture.jdbc.calls.contains("rollback"));
    assertEquals(
        List.of("setAutoCommit:false", "commit", "setAutoCommit:true", "close"),
        fixture.jdbc.calls);
    fixture.assertReleased();
  }

  @Test
  void preFailureNeverPreventsDelegateAndPostAttempt() throws Throwable {
    List<String> calls = new ArrayList<>();
    TransactionPhaseAnchorBridge bridge =
        fake(
            new AtomicLong(100),
            (context, side) -> {
              calls.add(side.name());
              if (side == TransactionPhaseAnchorBridge.Side.PRE) throw new Error("fake-observer");
              return COMPLETE;
            });
    bridge.captureAcknowledged(100);
    bridge.serviceEntered(1, 0, "apply");
    Object marker = new Object();
    assertSame(
        marker,
        bridge.commit(
            1,
            1,
            31,
            null,
            () -> {
              calls.add("commit");
              return marker;
            }));
    bridge.serviceTerminal(1, null);
    assertEquals(List.of("PRE", "commit", "POST"), calls);
    assertEquals(true, selected(bridge).get(0).get("unknown"));
  }

  @Test
  void postFailureDoesNotReplaceDelegateResult() throws Throwable {
    AtomicInteger delegates = new AtomicInteger();
    TransactionPhaseAnchorBridge bridge =
        fake(
            new AtomicLong(100),
            (context, side) -> {
              if (side == TransactionPhaseAnchorBridge.Side.POST)
                throw new RuntimeException("post");
              return COMPLETE;
            });
    bridge.captureAcknowledged(100);
    bridge.serviceEntered(1, 0, "apply");
    Object marker = new Object();
    assertSame(
        marker,
        bridge.commit(
            1,
            1,
            31,
            null,
            () -> {
              delegates.incrementAndGet();
              return marker;
            }));
    assertEquals(1, delegates.get());
    bridge.serviceTerminal(1, null);
    assertEquals(true, selected(bridge).get(0).get("unknown"));
  }

  @Test
  void firstEightMixedOutcomesAreNeverReplacedByNinth() throws Throwable {
    TransactionPhaseAnchorBridge bridge = fake(new AtomicLong(100), (context, side) -> COMPLETE);
    bridge.captureAcknowledged(100);
    for (int index = 1; index <= 9; index++) {
      bridge.serviceEntered(index, 0, "apply");
      if (index != 2) bridge.commit(index, index, 31, null, () -> null);
      bridge.serviceTerminal(index, index == 2 ? new RuntimeException("original") : null);
    }
    assertEquals(8, selected(bridge).size());
    assertEquals(2L, selected(bridge).get(1).get("call"));
    assertEquals(0, selected(bridge).get(1).get("commits"));
    assertEquals(Map.of("OVER_K", 1L), bridge.snapshot().get("excluded"));
    assertEquals(true, bridge.snapshot().get("freezeReady"));
    assertEquals(true, bridge.snapshot().get("unknown"));
  }

  @Test
  void excludedCallsAndCaptureBeforeActiveAreCounted() {
    AtomicLong clock = new AtomicLong(100);
    TransactionPhaseAnchorBridge bridge = fake(clock, (context, side) -> COMPLETE);
    bridge.serviceEntered(1, 0, "apply");
    bridge.captureAcknowledged(100);
    bridge.serviceTerminal(1, null);
    bridge.serviceEntered(0, 0, "apply");
    bridge.serviceEntered(2, 1, "apply");
    bridge.serviceEntered(3, 0, "dispatch");
    clock.set(1_000_000_100L);
    bridge.serviceEntered(4, 0, "apply");
    assertTrue(selected(bridge).isEmpty());
    assertEquals(
        Map.of("BEFORE_CAPTURE", 1L, "CALL_ZERO", 1L, "NESTED", 1L, "OTHER_CALLER", 1L, "LATE", 1L),
        bridge.snapshot().get("excluded"));
  }

  @Test
  void missingIdentityKeepsOriginalPlaceAndTerminalUnknown() throws Throwable {
    TransactionPhaseAnchorBridge bridge =
        TransactionPhaseAnchorBridge.fakeOnly(
            () -> 100, (context, side) -> fail("missing identity cannot anchor"), Map.of());
    bridge.captureAcknowledged(100);
    for (int index = 1; index <= 8; index++) {
      bridge.serviceEntered(index, 0, "apply");
      bridge.commit(index, index, 31, null, () -> null);
      bridge.serviceTerminal(index, null);
    }
    assertEquals(true, bridge.snapshot().get("freezeReady"));
    assertEquals(true, bridge.snapshot().get("unknown"));
    assertEquals(true, selected(bridge).get(0).get("delegateTerminal"));
  }

  @Test
  void sharedDeadlineDoesNotResetAndUnresolvedCallsCannotFreeze() throws Throwable {
    AtomicLong clock = new AtomicLong(100);
    TransactionPhaseAnchorBridge bridge = fake(clock, (context, side) -> COMPLETE);
    bridge.captureAcknowledged(100);
    for (int index = 1; index <= 8; index++) bridge.serviceEntered(index, 0, "apply");
    for (int index = 1; index <= 7; index++) {
      bridge.commit(index, index, 31, null, () -> null);
      bridge.serviceTerminal(index, null);
    }
    assertEquals(false, bridge.snapshot().get("freezeReady"));
    clock.set(5_000_000_101L);
    bridge.commit(8, 8, 31, null, () -> null);
    bridge.serviceTerminal(8, null);
    assertEquals(false, bridge.snapshot().get("freezeReady"));
  }

  @Test
  void incompleteResponsesCannotBeConvertedIntoFreezeReadiness() throws Throwable {
    TransactionPhaseAnchorBridge bridge =
        fake(
            new AtomicLong(100),
            (context, side) -> new TransactionPhaseAnchorBridge.AnchorOutcome(false, false, false));
    completeEight(bridge);
    assertEquals(false, bridge.snapshot().get("freezeReady"));
    assertEquals(true, bridge.snapshot().get("unknown"));
  }

  @Test
  void invalidButDrainedClosedFakeResponseCanBeTerminalUnknown() throws Throwable {
    TransactionPhaseAnchorBridge bridge =
        fake(
            new AtomicLong(100),
            (context, side) -> new TransactionPhaseAnchorBridge.AnchorOutcome(false, true, true));
    completeEight(bridge);
    assertEquals(true, bridge.snapshot().get("freezeReady"));
    assertEquals(true, bridge.snapshot().get("unknown"));
  }

  @Test
  void repeatedCommitAndBorrowNeverBecomeValidSamples() throws Throwable {
    TransactionPhaseAnchorBridge bridge = fake(new AtomicLong(100), (context, side) -> COMPLETE);
    bridge.captureAcknowledged(100);
    bridge.serviceEntered(1, 0, "apply");
    bridge.commit(1, 1, 31, null, () -> null);
    bridge.commit(1, 1, 31, null, () -> null);
    bridge.serviceTerminal(1, null);
    bridge.serviceEntered(2, 0, "apply");
    bridge.commit(2, 1, 31, null, () -> null);
    bridge.serviceTerminal(2, null);
    assertEquals(2, selected(bridge).get(0).get("commits"));
    assertTrue(selected(bridge).stream().allMatch(row -> Boolean.TRUE.equals(row.get("unknown"))));
  }

  @Test
  void clockFailureNeverPreventsOriginalCommit() throws Throwable {
    AtomicInteger reads = new AtomicInteger();
    TransactionPhaseAnchorBridge bridge =
        TransactionPhaseAnchorBridge.fakeOnly(
            () -> {
              if (reads.incrementAndGet() > 1) throw new Error("clock");
              return 100;
            },
            (context, side) -> COMPLETE,
            Map.of(31L, IDENTITY));
    bridge.captureAcknowledged(100);
    bridge.serviceEntered(1, 0, "apply");
    AtomicInteger delegated = new AtomicInteger();
    bridge.commit(
        1,
        1,
        31,
        null,
        () -> {
          delegated.incrementAndGet();
          return null;
        });
    assertEquals(1, delegated.get());
    assertEquals(true, bridge.snapshot().get("unknown"));
  }

  @Test
  void duplicateAckClockReversalAndSnapshotAreFailClosed() throws Throwable {
    AtomicLong clock = new AtomicLong(100);
    TransactionPhaseAnchorBridge bridge = fake(clock, (context, side) -> COMPLETE);
    completeEight(bridge);
    assertEquals(true, bridge.snapshot().get("freezeReady"));
    assertThrows(UnsupportedOperationException.class, () -> selected(bridge).get(0).put("call", 9));
    bridge.captureAcknowledged(100);
    clock.set(99);
    assertEquals(false, bridge.snapshot().get("freezeReady"));
    assertEquals(true, bridge.snapshot().get("unknown"));
  }

  @Test
  void allCompletedThenLateSnapshotCannotRequestFreeze() throws Throwable {
    AtomicLong clock = new AtomicLong(100);
    TransactionPhaseAnchorBridge bridge = fake(clock, (context, side) -> COMPLETE);
    completeEight(bridge);
    clock.set(5_000_000_100L);
    assertEquals(true, bridge.snapshot().get("freezeReady"));
    clock.set(5_000_000_101L);
    assertEquals(false, bridge.snapshot().get("freezeReady"));
    assertEquals(true, bridge.snapshot().get("unknown"));
  }

  @Test
  void excludedCallIdentityCannotBeReusedToEnterCohort() {
    TransactionPhaseAnchorBridge bridge = fake(new AtomicLong(100), (context, side) -> COMPLETE);
    bridge.serviceEntered(1, 0, "apply");
    bridge.captureAcknowledged(100);
    bridge.serviceEntered(1, 0, "apply");
    bridge.serviceEntered(2, 0, "dispatch");
    bridge.serviceEntered(2, 0, "apply");
    assertTrue(selected(bridge).isEmpty());
    assertEquals(true, bridge.snapshot().get("unknown"));
    assertEquals(
        Map.of("BEFORE_CAPTURE", 1L, "OTHER_CALLER", 1L, "DUPLICATE_CALL", 2L),
        bridge.snapshot().get("excluded"));
  }

  @Test
  void fullEntryIdentityMemoryIsBoundedAndOverflowIsUnknown() throws Exception {
    TransactionPhaseAnchorBridge bridge = fake(new AtomicLong(100), (context, side) -> COMPLETE);
    for (int index = 1; index <= 32768; index++) bridge.serviceEntered(index, 0, "dispatch");
    bridge.captureAcknowledged(100);
    bridge.serviceEntered(32769, 0, "apply");
    Field field = TransactionPhaseAnchorBridge.class.getDeclaredField("seenCalls");
    field.setAccessible(true);
    assertEquals(32768, ((java.util.Set<?>) field.get(bridge)).size());
    assertTrue(selected(bridge).isEmpty());
    assertEquals(true, bridge.snapshot().get("unknown"));
    assertEquals(
        Map.of("BEFORE_CAPTURE", 32768L, "ENTRY_CAPACITY", 1L), bridge.snapshot().get("excluded"));
  }

  @Test
  void nestedAdviceRestoresParentAndNeverStealsApplyCohort() throws Throwable {
    TransactionPhaseAnchorBridge bridge = fake(new AtomicLong(100), (context, side) -> COMPLETE);
    bridge.captureAcknowledged(100);
    Framework fixture = new Framework(bridge);
    fixture.apply(
        () -> {
          Object parent = fixture.current().get();
          fixture.apply(
              () -> {
                assertNotSame(parent, fixture.current().get());
                return null;
              });
          assertSame(parent, fixture.current().get());
          return fixture.transaction.execute(status -> null);
        });
    assertEquals(1, selected(bridge).size());
    assertEquals(Map.of("NESTED", 1L), bridge.snapshot().get("excluded"));
    fixture.assertReleased();
  }

  private static TransactionPhaseAnchorBridge fake(
      AtomicLong clock, TransactionPhaseAnchorBridge.AnchorCallback callback) {
    return TransactionPhaseAnchorBridge.fakeOnly(clock::get, callback, Map.of(31L, IDENTITY));
  }

  @SuppressWarnings("unchecked")
  private static List<Map<String, Object>> selected(TransactionPhaseAnchorBridge bridge) {
    return (List<Map<String, Object>>) bridge.snapshot().get("selected");
  }

  private static void completeEight(TransactionPhaseAnchorBridge bridge) throws Throwable {
    bridge.captureAcknowledged(100);
    for (int index = 1; index <= 8; index++) {
      bridge.serviceEntered(index, 0, "apply");
      bridge.commit(index, index, 31, null, () -> null);
      bridge.serviceTerminal(index, null);
    }
  }

  /**
   * Fake method identity used by the real outer service advice, with no business implementation.
   */
  public void apply() {}

  /** Real framework objects use only the memory fake and existing PhaseTiming proxy. */
  private static final class Framework {
    /** Existing private observer instance, retained only for reflection-based test injection. */
    final Object phase;

    /** Real original outer advice, not a duplicated simulation. */
    final MethodInterceptor advice;

    /** Single memory-only original pool and its unchanged PhaseTiming wrapper. */
    final DataSource observed;

    /** Underlying memory JDBC command log, with no network implementation. */
    final FakeJdbc jdbc;

    /** Actual Spring manager and template exercising begin/commit/rollback/cleanup. */
    final DataSourceTransactionManager manager;

    /** Timeout is framework configuration, not a claim about a real driver query timeout. */
    final TransactionTemplate transaction;

    private Framework(TransactionPhaseAnchorBridge bridge) throws Exception {
      this(bridge, new FakeJdbc());
    }

    private Framework(TransactionPhaseAnchorBridge bridge, FakeJdbc jdbc) throws Exception {
      this.jdbc = jdbc;
      Class<?> type =
          Class.forName(ProductOnlineMetricsPipelineIT.class.getName() + "$PhaseTiming");
      Constructor<?> constructor =
          type.getDeclaredConstructor(TransactionPhaseAnchorBridge.class, boolean.class);
      constructor.setAccessible(true);
      phase = constructor.newInstance(bridge, false);
      Method adviceMethod = type.getDeclaredMethod("serviceAdvice");
      adviceMethod.setAccessible(true);
      advice = (MethodInterceptor) adviceMethod.invoke(phase);
      Method dataSourceMethod = type.getDeclaredMethod("dataSource", DataSource.class);
      dataSourceMethod.setAccessible(true);
      DataSource original =
          (DataSource)
              Proxy.newProxyInstance(
                  DataSource.class.getClassLoader(),
                  new Class<?>[] {DataSource.class},
                  (proxy, method, arguments) -> {
                    if (method.getName().equals("getConnection")) return jdbc.connection;
                    if (method.getName().equals("toString")) return "MemoryOnlyDataSource";
                    return primitiveDefault(method.getReturnType());
                  });
      observed = (DataSource) dataSourceMethod.invoke(phase, original);
      manager = new DataSourceTransactionManager(observed);
      transaction = new TransactionTemplate(manager);
      transaction.setTimeout(3);
    }

    private Object apply(TransactionPhaseAnchorBridge.ThrowingDelegate body) throws Throwable {
      Method method = TransactionPhaseAnchorBridgeTest.class.getDeclaredMethod("apply");
      return advice.invoke(
          new MethodInvocation() {
            /** Supplies the exact apply name for the original observer's existing frame logic. */
            public Method getMethod() {
              return method;
            }

            /** No business payload is used in this framework-only check. */
            public Object[] getArguments() {
              return new Object[0];
            }

            /** Delegates once to the original test body, including its real Spring template. */
            public Object proceed() throws Throwable {
              return body.invoke();
            }

            /** There is no actual business service target in this fake test. */
            public Object getThis() {
              return null;
            }

            /** Preserves the same original reflection identity. */
            public AccessibleObject getStaticPart() {
              return method;
            }
          });
    }

    private ThreadLocal<?> current() throws Exception {
      Field field = phase.getClass().getDeclaredField("current");
      field.setAccessible(true);
      return (ThreadLocal<?>) field.get(phase);
    }

    private void assertReleased() throws Exception {
      assertNull(TransactionSynchronizationManager.getResource(observed));
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
      assertNull(current().get());
    }
  }

  /** A deliberately limited JDBC fake; any attempted SQL immediately fails the test. */
  private static final class FakeJdbc {
    /** Only fixed operation names are retained; no SQL or exception text is recorded. */
    final List<String> calls = new ArrayList<>();

    /** Synthetic state retains the known post-dirty difference through cleanup. */
    boolean autoCommit = true, dirty, dirtyAtClose;

    /** Any attempt to issue SQL is counted and rejected. */
    int sqlCalls;

    /** Exact original JDBC failure for seam/cause-chain checks. */
    SQLException commitFailure;

    /** Memory-only interface proxy also supplies the existing identity getter. */
    final Connection connection =
        (Connection)
            Proxy.newProxyInstance(
                JdbcConnection.class.getClassLoader(),
                new Class<?>[] {JdbcConnection.class},
                (proxy, method, arguments) -> {
                  String name = method.getName();
                  if (name.equals("unwrap")) return proxy;
                  if (name.equals("getId")) return 31L;
                  if (name.equals("getAutoCommit")) return autoCommit;
                  if (name.equals("setAutoCommit")) {
                    autoCommit = (boolean) arguments[0];
                    calls.add("setAutoCommit:" + autoCommit);
                    return null;
                  }
                  if (name.equals("commit")) {
                    calls.add("commit");
                    dirty = false;
                    if (commitFailure != null) throw commitFailure;
                    return null;
                  }
                  if (name.equals("rollback")) {
                    calls.add("rollback");
                    return null;
                  }
                  if (name.equals("close")) {
                    calls.add("close");
                    dirtyAtClose = dirty;
                    return null;
                  }
                  if (name.equals("createStatement")
                      || name.equals("prepareStatement")
                      || name.equals("prepareCall")) {
                    sqlCalls++;
                    throw new AssertionError("SQL prohibited in framework fake");
                  }
                  if (name.equals("toString")) return "MemoryOnlyConnection";
                  return primitiveDefault(method.getReturnType());
                });
  }

  private static Object primitiveDefault(Class<?> type) {
    if (type == boolean.class) return false;
    if (type == int.class) return 0;
    if (type == long.class) return 0L;
    return null;
  }
}
