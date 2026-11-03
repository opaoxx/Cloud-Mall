package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/**
 * No-network fake JDBC checks of delegate/resource semantics, not server transaction equivalence.
 */
class Cm21JdbcAnchorObserverTest {
  /** Fixed qualification-only identity used by every complete fake row. */
  private static final Cm21JdbcAnchorObserver.Context CONTEXT =
      new Cm21JdbcAnchorObserver.Context(
          "0123456789abcdef0123456789abcdef",
          "qualification-1",
          "borrow-1",
          "aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee",
          31,
          55,
          "fake-only");

  @Test
  void disabledDoesNotValidateContextCreateSqlOrCallSink() throws Exception {
    FakeJdbc fake = new FakeJdbc();
    Cm21JdbcAnchorObserver.commit(fake.connection(), null, ignored -> fail("disabled sink"));
    assertEquals(List.of("commit"), fake.calls);
  }

  @Test
  void explicitDisabledPreservesOriginalFailureObject() {
    FakeJdbc fake = new FakeJdbc();
    SQLException original = new SQLException("original");
    fake.commitFailure = original;
    assertSame(
        original,
        assertThrows(
            SQLException.class,
            () -> Cm21JdbcAnchorObserver.commit(fake.connection(), null, false, null)));
    assertEquals(List.of("commit"), fake.calls);
  }

  @Test
  void enabledOrdersPreDelegateAndPostAndClosesOnlyObserverResources() throws Exception {
    FakeJdbc fake = new FakeJdbc();
    List<Cm21JdbcAnchorObserver.Observation> observations = new ArrayList<>();
    Cm21JdbcAnchorObserver.commit(fake.connection(), CONTEXT, true, observations::add);
    assertEquals(
        List.of(
            "create1",
            "query1",
            "rsClose1",
            "stmtClose1",
            "commit",
            "create2",
            "query2",
            "rsClose2",
            "stmtClose2"),
        fake.calls);
    assertEquals(
        List.of("PRE", "DELEGATE_RETURNED", "POST"),
        observations.stream().map(Cm21JdbcAnchorObserver.Observation::kind).toList());
    assertEquals("VALID", observations.get(0).anchor().status());
    assertEquals("VALID", observations.get(2).anchor().status());
    assertEquals(1, fake.commits);
  }

  @Test
  void preCreateFailureDoesNotPreventCommitOrPost() throws Exception {
    assertPreFailure("create1");
  }

  @Test
  void preQueryFailureClosesStatementAndDoesNotPreventCommit() throws Exception {
    assertPreFailure("query1");
  }

  @Test
  void preReadFailureClosesBothResourcesAndDoesNotPreventCommit() throws Exception {
    assertPreFailure("read1");
  }

  @Test
  void preResultSetCloseFailureStillClosesStatementAndCommits() throws Exception {
    assertPreFailure("rsClose1");
  }

  @Test
  void preStatementCloseFailureDoesNotPreventCommit() throws Exception {
    assertPreFailure("stmtClose1");
  }

  @Test
  void postQueryFailureDoesNotChangeOriginalReturn() throws Exception {
    FakeJdbc fake = new FakeJdbc();
    fake.failureAt = "query2";
    List<Cm21JdbcAnchorObserver.Observation> observations = new ArrayList<>();
    Cm21JdbcAnchorObserver.commit(fake.connection(), CONTEXT, true, observations::add);
    assertEquals(1, fake.commits);
    assertEquals(
        "UNKNOWN",
        observations.stream()
            .filter(row -> row.kind().equals("POST"))
            .findFirst()
            .orElseThrow()
            .anchor()
            .status());
    assertTrue(fake.calls.contains("stmtClose2"));
  }

  @Test
  void allSinkFailuresAreIsolatedIncludingDelegateNotification() throws Exception {
    FakeJdbc fake = new FakeJdbc();
    Cm21JdbcAnchorObserver.commit(
        fake.connection(),
        CONTEXT,
        true,
        ignored -> {
          throw new AssertionError("broken journal");
        });
    assertEquals(1, fake.commits);
    assertEquals(2, fake.statements);
  }

  @Test
  void originalSqlExceptionIsSameObjectAndNoPostIsAttempted() {
    assertOriginalFailure(new SQLException("delegate SQL", "08007", 9));
  }

  @Test
  void originalRuntimeExceptionIsSameObjectAndNoPostIsAttempted() {
    assertOriginalFailure(new IllegalStateException("delegate runtime"));
  }

  @Test
  void originalErrorIsSameObjectAndNoPostIsAttempted() {
    assertOriginalFailure(new AssertionError("delegate error"));
  }

  @Test
  void invalidContextIsObservationFailureButDelegateStillRuns() throws Exception {
    FakeJdbc fake = new FakeJdbc();
    Cm21JdbcAnchorObserver.commit(fake.connection(), null, true, null);
    assertEquals(List.of("commit"), fake.calls);
  }

  @Test
  void retainsAllPendingRowsAndRejectsMultipleRows() {
    FakeJdbc fake = new FakeJdbc();
    fake.rows.add(row());
    Cm21JdbcAnchorObserver.AnchorResult result = anchor(fake);
    assertEquals("UNKNOWN", result.status());
    assertEquals("PENDING_ROW_COUNT", result.reason());
    assertEquals(2, result.rows().size());
    assertThrows(
        UnsupportedOperationException.class, () -> result.rows().get(0).put("event_id", 9));
  }

  @Test
  void rejectsNestedRetainedLastAndDifferentStatementWithoutQueryFiltering() {
    for (Map.Entry<String, Object> mutation :
        List.<Map.Entry<String, Object>>of(
            Map.entry("nesting_level", 1),
            Map.entry("end_event_id", 19L),
            Map.entry("event_name", "statement/sql/commit"))) {
      FakeJdbc fake = new FakeJdbc();
      fake.rows.get(0).put(mutation.getKey(), mutation.getValue());
      assertEquals("PENDING_STATEMENT_SHAPE", anchor(fake).reason());
    }
    assertFalse(Cm21JdbcAnchorObserver.ANCHOR_SQL.contains("LIMIT"));
    assertFalse(Cm21JdbcAnchorObserver.ANCHOR_SQL.contains("MAX("));
    assertTrue(Cm21JdbcAnchorObserver.ANCHOR_SQL.endsWith("END_EVENT_ID IS NULL"));
  }

  @Test
  void rejectsWrongUuidConnectionThreadAndNonIntegralIds() {
    for (Map.Entry<String, Object> mutation :
        List.<Map.Entry<String, Object>>of(
            Map.entry("uuid", "other-instance"),
            Map.entry("connection_id", 32L),
            Map.entry("pfs_thread_id", 56L),
            Map.entry("connection_id", true),
            Map.entry("connection_id", new BigDecimal("31.5")))) {
      FakeJdbc fake = new FakeJdbc();
      fake.rows.get(0).put(mutation.getKey(), mutation.getValue());
      assertEquals("IDENTITY_MISMATCH", anchor(fake).reason());
    }
  }

  @Test
  void rejectsMissingHistoryInstrumentationAndNonPositiveEvent() {
    for (Map.Entry<String, Object> mutation :
        List.<Map.Entry<String, Object>>of(
            Map.entry("history", "NO"), Map.entry("instrumented", "NO"),
            Map.entry("event_id", 0), Map.entry("event_id", new BigDecimal("10.5")))) {
      FakeJdbc fake = new FakeJdbc();
      fake.rows.get(0).put(mutation.getKey(), mutation.getValue());
      assertEquals("UNKNOWN", anchor(fake).status());
    }
  }

  @Test
  void emptyPendingCurrentDoesNotUseRetainedHistoryFallback() {
    FakeJdbc fake = new FakeJdbc();
    fake.rows.clear();
    assertEquals("PENDING_ROW_COUNT", anchor(fake).reason());
    assertEquals(1, fake.statements);
  }

  @Test
  void closeErrorsAreSeparatelyRecordedAfterFullyDrainedRows() {
    FakeJdbc fake = new FakeJdbc();
    fake.failureAt = "bothClose";
    Cm21JdbcAnchorObserver.AnchorResult result = anchor(fake);
    assertTrue(result.responseDrained());
    assertFalse(result.resultSetClosed());
    assertFalse(result.statementClosed());
    assertEquals(
        List.of("RESULT_SET_CLOSE", "STATEMENT_CLOSE"),
        result.failures().stream().map(Cm21JdbcAnchorObserver.Failure::stage).toList());
  }

  @Test
  void successfulNoopCannotBecomeCompletedCommitEvidence() throws Exception {
    FakeJdbc fake = new FakeJdbc();
    List<Cm21JdbcAnchorObserver.Observation> observations = new ArrayList<>();
    Cm21JdbcAnchorObserver.commit(fake.connection(), CONTEXT, true, observations::add);
    // This helper deliberately supplies no MATCHED root or historical COMMIT assertion.
    assertEquals(1, fake.commits);
    assertTrue(
        observations.stream()
            .allMatch(
                observation ->
                    !observation.kind().contains("MATCH")
                        && !observation.kind().contains("COMPLETE")));
    assertEquals("VALID", observations.get(2).anchor().status());
    assertNull(observations.get(2).anchor().rows().get(0).get("end_event_id"));
  }

  private static void assertPreFailure(String stage) throws Exception {
    FakeJdbc fake = new FakeJdbc();
    fake.failureAt = stage;
    List<Cm21JdbcAnchorObserver.Observation> observations = new ArrayList<>();
    Cm21JdbcAnchorObserver.commit(fake.connection(), CONTEXT, true, observations::add);
    assertEquals(1, fake.commits);
    assertEquals(2, fake.statements);
    assertEquals("UNKNOWN", observations.get(0).anchor().status());
    assertTrue(observations.stream().anyMatch(row -> row.kind().equals("POST")));
    if (!stage.equals("create1")) {
      assertTrue(fake.calls.contains("stmtClose1"));
    }
    if (!stage.equals("create1") && !stage.equals("query1")) {
      assertTrue(fake.calls.contains("rsClose1"));
    }
  }

  private static void assertOriginalFailure(Throwable original) {
    FakeJdbc fake = new FakeJdbc();
    fake.commitFailure = original;
    Throwable actual =
        assertThrows(
            original.getClass(),
            () ->
                Cm21JdbcAnchorObserver.commit(
                    fake.connection(),
                    CONTEXT,
                    true,
                    ignored -> {
                      throw new AssertionError("broken journal");
                    }));
    assertSame(original, actual);
    assertEquals(1, fake.commits);
    assertEquals(1, fake.statements);
  }

  private static Cm21JdbcAnchorObserver.AnchorResult anchor(FakeJdbc fake) {
    return Cm21JdbcAnchorObserver.readAnchor(
        fake.connection(), CONTEXT, Cm21JdbcAnchorObserver.Side.PRE);
  }

  private static Map<String, Object> row() {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("uuid", CONTEXT.serverUuid());
    row.put("connection_id", 31L);
    row.put("pfs_thread_id", 55L);
    row.put("instrumented", "YES");
    row.put("history", "YES");
    row.put("event_id", 10L);
    row.put("end_event_id", null);
    row.put("event_name", "statement/sql/select");
    row.put("nesting_level", 0);
    return row;
  }

  /** Strict proxies reject every method not needed by the observer, including Connection.close. */
  private static final class FakeJdbc {
    /** All driver/resource operations in occurrence order. */
    private final List<String> calls = new ArrayList<>();

    /** Complete native-shaped pending rows; null columns are retained. */
    private final List<Map<String, Object>> rows = new ArrayList<>(List.of(row()));

    /** Optional observer operation to fail. */
    private String failureAt = "";

    /** Optional original commit throwable, rethrown by the fake unchanged. */
    private Throwable commitFailure;

    /** Number of delegate commit invocations, independent of server SQL execution. */
    private int commits;

    /** Number of requested observer statements. */
    private int statements;

    private Connection connection() {
      return proxy(
          Connection.class,
          (proxy, method, arguments) -> {
            if (method.getName().equals("commit")) {
              calls.add("commit");
              commits++;
              if (commitFailure != null) {
                throw commitFailure;
              }
              return null;
            }
            if (method.getName().equals("createStatement")) {
              int ordinal = ++statements;
              step("create" + ordinal);
              return statement(ordinal);
            }
            throw new AssertionError("Unexpected connection method: " + method.getName());
          });
    }

    private Statement statement(int ordinal) {
      return proxy(
          Statement.class,
          (proxy, method, arguments) -> {
            if (method.getName().equals("executeQuery")) {
              assertEquals(Cm21JdbcAnchorObserver.ANCHOR_SQL, arguments[0]);
              step("query" + ordinal);
              return resultSet(ordinal);
            }
            if (method.getName().equals("close")) {
              step("stmtClose" + ordinal);
              return null;
            }
            throw new AssertionError("Unexpected statement method: " + method.getName());
          });
    }

    private ResultSet resultSet(int ordinal) {
      int[] rowIndex = {-1};
      return proxy(
          ResultSet.class,
          (proxy, method, arguments) -> {
            if (method.getName().equals("next")) {
              failAt("read" + ordinal);
              return ++rowIndex[0] < rows.size();
            }
            if (method.getName().equals("getObject")) {
              return rows.get(rowIndex[0]).get(arguments[0]);
            }
            if (method.getName().equals("close")) {
              step("rsClose" + ordinal);
              return null;
            }
            throw new AssertionError("Unexpected result method: " + method.getName());
          });
    }

    private void step(String stage) throws SQLException {
      calls.add(stage);
      failAt(stage);
    }

    private void failAt(String stage) throws SQLException {
      if (failureAt.equals(stage) || failureAt.equals("bothClose") && stage.contains("Close")) {
        throw new SQLException("fake " + stage);
      }
    }
  }

  private static <Type> Type proxy(Class<Type> type, java.lang.reflect.InvocationHandler handler) {
    return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] {type}, handler));
  }
}
