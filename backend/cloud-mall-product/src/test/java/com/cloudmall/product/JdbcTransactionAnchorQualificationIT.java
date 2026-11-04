package com.cloudmall.product;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.mysql.cj.NativeSession;
import com.mysql.cj.conf.RuntimeProperty;
import com.mysql.cj.jdbc.JdbcConnection;
import com.mysql.cj.protocol.a.NativeServerSession;
import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import java.io.PrintWriter;
import java.lang.reflect.Field;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;
import javax.sql.DataSource;

/**
 * Standalone bounded driver qualification. It never starts containers, Spring, or workload clients.
 */
public final class JdbcTransactionAnchorQualificationIT {
  /** Serialization is local only; the disabled entry point never creates a JDBC connection. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Fixed URL suffix reproduces the original owned datasource's explicit connection properties. */
  private static final String URL_SUFFIX =
      "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai"
          + "&connectTimeout=3000&socketTimeout=5000";

  /** Read-only property names are explicit rather than inferred from driver defaults. */
  private static final List<String> PROPERTY_NAMES =
      List.of(
          "useLocalTransactionState",
          "useLocalSessionState",
          "elideSetAutoCommits",
          "autoReconnect",
          "autoReconnectForPools",
          "useServerPrepStmts",
          "readOnlyPropagatesToServer",
          "useConfigs");

  /**
   * Java-issued explicit SQL or potentially SQL-emitting transaction API calls cannot exceed this.
   */
  private static final int COMMAND_LIMIT = 67;

  /** Exact fixed local image handed off by the root coordinator. */
  private static final String MYSQL_IMAGE =
      "sha256:7dcddc01f13bab2f15cde676d44d01f61fc9f99fe7785e86196dfc07d358ae2b";

  private JdbcTransactionAnchorQualificationIT() {}

  /**
   * Runs no JDBC action by default; only a checked, root-produced admission permits qualification.
   */
  public static void main(String[] arguments) throws Exception {
    if (arguments.length == 0) {
      System.out.println(
          "{\"resourceAction\":false,\"sqlExecuted\":false,\"liveAdmission\":false}");
      return;
    }
    // 1. Validate every argument and root admission before loading a driver or constructing a pool.
    Admission admission = Admission.parse(arguments);
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("resourceAction", false);
    result.put("sqlExecuted", false);
    result.put("liveAdmission", true);
    result.put("owner", admission.owner());
    result.put("schema", admission.schema());
    result.put("containerId", admission.manifest().get("containerId"));
    result.put("imageId", admission.manifest().get("imageId"));
    result.put("status", "UNKNOWN");
    Ledger ledger = new Ledger(admission.deadlineEpochMillis());
    result.put("commands", ledger.entries);
    List<Map<String, Object>> cases = new ArrayList<>();
    result.put("cases", cases);
    List<Map<String, Object>> closeErrors = new ArrayList<>();
    result.put("closeErrors", closeErrors);
    ledger.reserve("cleanup.allowance", "RESERVED_POOL_ROLLBACK_AND_TWO_PHYSICAL_CLOSES", 3);
    OwnedSource source = new OwnedSource(admission.jdbcUrl(), ledger);
    Connection observer = null;
    HikariDataSource pool = null;
    try {
      // 2. Create only a dedicated, new owner schema and one marker table, without IF NOT EXISTS.
      observer = source.openObserver();
      result.put("sqlExecuted", true);
      identity(observer, admission, ledger, "observer.identity", false);
      result.put("observerDriver", driverFacts(observer));
      if (!((NativeSession) observer.unwrap(JdbcConnection.class).getSession())
          .getServerSession()
          .isAutoCommit()) {
        throw new IllegalStateException("Independent visibility observer must be autocommit=true");
      }
      sql(observer, ledger, "schema.create", "CREATE DATABASE `" + admission.schema() + "`");
      String table = "`" + admission.schema() + "`.anchor_marker";
      sql(
          observer,
          ledger,
          "table.create",
          "CREATE TABLE " + table + " (marker_id INT NOT NULL PRIMARY KEY) ENGINE=InnoDB");
      // 3. This diagnostic pool has one physical connection; it is not the production 12-slot pool.
      HikariConfig configuration = new HikariConfig();
      configuration.setDataSource(source);
      configuration.setMaximumPoolSize(1);
      configuration.setMinimumIdle(1);
      configuration.setConnectionTimeout(3000);
      configuration.setValidationTimeout(1000);
      configuration.setInitializationFailTimeout(3000);
      configuration.setPoolName("cm21-jdbc-" + admission.owner());
      configuration.setMaxLifetime(0);
      configuration.setIdleTimeout(0);
      ledger.reserve(
          "pool.setupAndBorrowValidation.allowance",
          "POTENTIAL_SETUP_QUERIES_AND_TEN_BORROW_VALIDATIONS",
          12);
      pool = new HikariDataSource(configuration);
      result.put(
          "pool",
          Map.of(
              "maximumPoolSize",
              pool.getMaximumPoolSize(),
              "minimumIdle",
              pool.getMinimumIdle(),
              "autoCommit",
              pool.isAutoCommit(),
              "connectionTimeout",
              pool.getConnectionTimeout(),
              "validationTimeout",
              pool.getValidationTimeout(),
              "diagnosticSinglePhysical",
              true));
      try (Connection connection = pool.getConnection()) {
        Map<String, Object> own = identity(connection, admission, ledger, "pool.identity", true);
        result.put("poolIdentity", own);
        result.put("driver", driverFacts(connection));
      }
      springCase(pool, observer, admission, ledger, table, 1, false, cases);
      springCase(pool, observer, admission, ledger, table, 2, true, cases);
      rollbackCase(pool, observer, admission, ledger, table, cases);
      directCloseCase(pool, observer, admission, ledger, table, cases);
      emptyCase(pool, admission, ledger, cases);
      // 4. An explicit later independent query reads raw roots; it is not a hidden helper barrier.
      long thread =
          ((Number) ((Map<?, ?>) result.get("poolIdentity")).get("pfs_thread_id")).longValue();
      result.put(
          "rawCommitHistory",
          query(
              observer,
              ledger,
              "history.raw.roots",
              "SELECT THREAD_ID AS thread_id, EVENT_ID AS event_id, END_EVENT_ID AS end_event_id,"
                  + " EVENT_NAME AS event_name, NESTING_EVENT_ID AS nesting_event_id,"
                  + " NESTING_EVENT_TYPE AS nesting_event_type, NESTING_EVENT_LEVEL AS"
                  + " nesting_level, ERRORS AS errors, MYSQL_ERRNO AS mysql_errno FROM"
                  + " performance_schema.events_statements_history_long WHERE THREAD_ID="
                  + thread
                  + " AND EVENT_NAME='statement/sql/commit'"));
      validateEvidence(cases);
      result.put("status", "QUALIFICATION_EVIDENCE_COLLECTED_NOT_TRANSPARENCY_CERTIFIED");
      result.put("noOpQualification", "UNKNOWN_UNLESS_COMPLETE_NATIVE_HISTORY_PROVES_ZERO_ROOT");
      result.put(
          "historyCompleteness", "UNKNOWN_REQUIRES_ROOT_CAPACITY_LOST_AND_FROZEN_RING_GATES");
    } catch (Throwable failure) {
      result.put("failure", failure(failure));
    } finally {
      // 5. Close ordinary clients without retry or forced resource removal; persist every outcome.
      if (pool != null) {
        try {
          source.captureProtocolCounts("beforePoolClose");
          pool.close();
          source.verifyPoolPhysicalClosed(closeErrors);
        } catch (Throwable failure) {
          closeErrors.add(failure(failure));
        }
      }
      if (observer != null) {
        try {
          source.captureProtocolCounts("beforeObserverClose");
          observer.close();
          verifyClosed(observer, "observer.close", closeErrors);
        } catch (Throwable failure) {
          closeErrors.add(failure(failure));
        }
      }
      source.closeRemaining(closeErrors);
      result.put("protocolCommandCounts", source.protocolCounts);
      result.put("explicitCommandsAndPotentialApiSql", ledger.used);
      result.put("commandLimit", COMMAND_LIMIT);
      result.put(
          "implicitProtocolAccounting", "PROTOCOL_COMMANDS_INCLUDE_PING_NOT_EXACT_SQL_COUNT");
      if (!closeErrors.isEmpty()) {
        result.put("status", "UNKNOWN");
      }
      JSON.writerWithDefaultPrettyPrinter().writeValue(admission.output().toFile(), result);
    }
    if (!"QUALIFICATION_EVIDENCE_COLLECTED_NOT_TRANSPARENCY_CERTIFIED"
        .equals(result.get("status"))) {
      throw new IllegalStateException("JDBC qualification UNKNOWN; see persisted output");
    }
  }

  private static void springCase(
      HikariDataSource pool,
      Connection observer,
      Admission admission,
      Ledger ledger,
      String table,
      int marker,
      boolean enabled,
      List<Map<String, Object>> cases)
      throws Exception {
    String scenario = enabled ? "ON_SPRING" : "OFF_SPRING";
    Map<String, Object> record = caseRecord(scenario, cases);
    try (Connection connection = pool.getConnection()) {
      record.put("borrowed", state(connection));
      tx(ledger, scenario + ".setAutoCommitFalse", () -> connection.setAutoCommit(false));
      sql(
          connection,
          ledger,
          scenario + ".insert",
          "INSERT INTO " + table + " VALUES (" + marker + ")");
      record.put("afterDml", state(connection));
      record.put(
          "visibleBeforePre", visible(observer, ledger, table, marker, scenario + ".beforePre"));
      List<JdbcTransactionAnchorObserver.Observation> observations = new ArrayList<>();
      JdbcTransactionAnchorObserver.Context context = context(connection, admission, scenario);
      // The sink runs after the pre statement closes, and before the original commit.
      JdbcTransactionAnchorObserver.ObservationSink sink =
          observation -> {
            observations.add(observation);
            if (observation.kind().equals("PRE")) {
              record.put("afterPre", state(connection));
              record.put(
                  "visibleAfterPre",
                  visible(observer, ledger, table, marker, scenario + ".afterPre"));
            } else if (observation.kind().equals("DELEGATE_RETURNED")) {
              record.put("afterDelegate", state(connection));
            } else if (observation.kind().equals("POST")) {
              record.put("afterPost", state(connection));
            }
          };
      ledger.reserve(scenario + ".commit", "COMMIT_API_AND_OPTIONAL_TWO_ANCHORS", enabled ? 3 : 1);
      JdbcTransactionAnchorObserver.commit(connection, context, enabled, sink);
      record.put("observations", observations(observations));
      if (enabled && (!record.containsKey("visibleAfterPre") || !record.containsKey("afterPost"))) {
        throw new IllegalStateException("Required sink observation was not retained");
      }
      if (!enabled) {
        record.put("afterDelegate", state(connection));
        record.put("visibleAfterPre", "NOT_APPLICABLE_ANCHOR_OFF");
      }
      record.put(
          "visibleAfterCommit",
          visible(observer, ledger, table, marker, scenario + ".afterCommit"));
      record.put("beforeCleanup", state(connection));
      tx(ledger, scenario + ".setAutoCommitTrue", () -> connection.setAutoCommit(true));
      record.put("afterCleanup", state(connection));
    }
    try (Connection reborrowed = pool.getConnection()) {
      record.put("reborrowed", state(reborrowed));
    }
  }

  private static void rollbackCase(
      HikariDataSource pool,
      Connection observer,
      Admission admission,
      Ledger ledger,
      String table,
      List<Map<String, Object>> cases)
      throws Exception {
    String scenario = "PRE_ROLLBACK";
    Map<String, Object> record = caseRecord(scenario, cases);
    try (Connection connection = pool.getConnection()) {
      tx(ledger, scenario + ".setAutoCommitFalse", () -> connection.setAutoCommit(false));
      sql(connection, ledger, scenario + ".insert", "INSERT INTO " + table + " VALUES (3)");
      record.put("afterDml", state(connection));
      ledger.reserve(scenario + ".pre", "ANCHOR_SQL", 1);
      record.put(
          "pre",
          JdbcTransactionAnchorObserver.readAnchor(
              connection,
              context(connection, admission, scenario),
              JdbcTransactionAnchorObserver.Side.PRE));
      record.put("afterPre", state(connection));
      record.put("visibleAfterPre", visible(observer, ledger, table, 3, scenario + ".afterPre"));
      tx(ledger, scenario + ".rollback", connection::rollback);
      record.put("afterRollback", state(connection));
      record.put(
          "visibleAfterRollback", visible(observer, ledger, table, 3, scenario + ".afterRollback"));
      tx(ledger, scenario + ".setAutoCommitTrue", () -> connection.setAutoCommit(true));
    }
    try (Connection reborrowed = pool.getConnection()) {
      record.put("reborrowed", state(reborrowed));
    }
  }

  private static void directCloseCase(
      HikariDataSource pool,
      Connection observer,
      Admission admission,
      Ledger ledger,
      String table,
      List<Map<String, Object>> cases)
      throws Exception {
    String scenario = "ON_DIRECT_CLOSE_FALSE";
    Map<String, Object> record = caseRecord(scenario, cases);
    try (Connection connection = pool.getConnection()) {
      tx(ledger, scenario + ".setAutoCommitFalse", () -> connection.setAutoCommit(false));
      sql(connection, ledger, scenario + ".insert", "INSERT INTO " + table + " VALUES (4)");
      List<JdbcTransactionAnchorObserver.Observation> observations = new ArrayList<>();
      ledger.reserve(scenario + ".commit", "COMMIT_API_AND_TWO_ANCHORS", 3);
      JdbcTransactionAnchorObserver.commit(
          connection,
          context(connection, admission, scenario),
          true,
          observation -> {
            observations.add(observation);
            if (observation.kind().equals("DELEGATE_RETURNED")) {
              record.put("afterDelegate", state(connection));
            }
          });
      record.put("observations", observations(observations));
      record.put("beforeClose", state(connection));
      // Dirty post-SELECT may make Hikari close issue rollback before resetting autocommit.
      ledger.reserve(scenario + ".proxyClose", "POTENTIAL_ROLLBACK_AND_SET_AUTOCOMMIT", 2);
    }
    try (Connection reborrowed = pool.getConnection()) {
      record.put("reborrowed", state(reborrowed));
    }
    record.put("visibleAfterClose", visible(observer, ledger, table, 4, scenario + ".afterClose"));
  }

  private static void emptyCase(
      HikariDataSource pool, Admission admission, Ledger ledger, List<Map<String, Object>> cases)
      throws Exception {
    String scenario = "NATURAL_EMPTY_COMMIT";
    Map<String, Object> record = caseRecord(scenario, cases);
    try (Connection connection = pool.getConnection()) {
      tx(ledger, scenario + ".setAutoCommitFalse", () -> connection.setAutoCommit(false));
      record.put("beforePre", state(connection));
      List<JdbcTransactionAnchorObserver.Observation> observations = new ArrayList<>();
      ledger.reserve(scenario + ".commit", "COMMIT_API_AND_TWO_ANCHORS", 3);
      JdbcTransactionAnchorObserver.commit(
          connection, context(connection, admission, scenario), true, observations::add);
      record.put("observations", observations(observations));
      record.put("afterPost", state(connection));
      record.put("result", "UNKNOWN_NOOP_NOT_ESTABLISHED_FROM_API_SUCCESS");
      tx(ledger, scenario + ".setAutoCommitTrue", () -> connection.setAutoCommit(true));
    }
  }

  private static Map<String, Object> caseRecord(String scenario, List<Map<String, Object>> cases) {
    Map<String, Object> record = new LinkedHashMap<>();
    record.put("scenario", scenario);
    record.put("status", "RAW_DIAGNOSTIC_NOT_TRANSACTION_EQUIVALENCE");
    cases.add(record);
    return record;
  }

  private static void validateEvidence(List<Map<String, Object>> cases) {
    // These finite visibility/resource checks are necessary; they do not certify TX equivalence.
    for (Map<String, Object> record : cases) {
      String scenario = (String) record.get("scenario");
      if (scenario.endsWith("SPRING")) {
        requireCount(record.get("visibleBeforePre"), 0);
        requireCount(record.get("visibleAfterCommit"), 1);
        if (scenario.equals("ON_SPRING")) {
          requireCount(record.get("visibleAfterPre"), 0);
        }
      } else if (scenario.equals("PRE_ROLLBACK")) {
        requireCount(record.get("visibleAfterPre"), 0);
        requireCount(record.get("visibleAfterRollback"), 0);
        if (!(record.get("pre") instanceof JdbcTransactionAnchorObserver.AnchorResult anchor)
            || !anchor.status().equals("VALID")) {
          throw new IllegalStateException("Rollback pre anchor UNKNOWN");
        }
      } else if (scenario.equals("ON_DIRECT_CLOSE_FALSE")) {
        requireCount(record.get("visibleAfterClose"), 1);
      }
      if (record.get("observations") instanceof List<?> rows) {
        int anchors = 0;
        for (Object raw : rows) {
          Map<?, ?> row = (Map<?, ?>) raw;
          if (row.get("anchor") instanceof Map<?, ?> anchor) {
            anchors++;
            if (!"VALID".equals(anchor.get("status"))) {
              throw new IllegalStateException("Commit anchor UNKNOWN");
            }
          }
          if (row.containsKey("failure")) {
            throw new IllegalStateException("Observation failure retained");
          }
        }
        if (anchors != (scenario.equals("OFF_SPRING") ? 0 : 2)) {
          throw new IllegalStateException("Incomplete two-anchor evidence");
        }
      }
    }
  }

  private static void requireCount(Object raw, long expected) {
    if (!(raw instanceof Number) || ((Number) raw).longValue() != expected) {
      throw new IllegalStateException("Marker visibility differs from expected finite case");
    }
  }

  private static List<Map<String, Object>> observations(
      List<JdbcTransactionAnchorObserver.Observation> observations) {
    List<Map<String, Object>> output = new ArrayList<>();
    for (JdbcTransactionAnchorObserver.Observation observation : observations) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("kind", observation.kind());
      row.put("context", observation.context());
      if (observation.anchor() != null) {
        JdbcTransactionAnchorObserver.AnchorResult anchor = observation.anchor();
        Map<String, Object> value = new LinkedHashMap<>();
        value.put("side", anchor.side());
        value.put("status", anchor.status());
        value.put("reason", anchor.reason());
        value.put("rows", anchor.rows());
        value.put("responseDrained", anchor.responseDrained());
        value.put("resultSetClosed", anchor.resultSetClosed());
        value.put("statementClosed", anchor.statementClosed());
        value.put(
            "failures", anchor.failures().stream().map(item -> failure(item.cause())).toList());
        row.put("anchor", value);
      }
      if (observation.cause() != null) {
        row.put("failure", failure(observation.cause()));
      }
      output.add(row);
    }
    return output;
  }

  private static JdbcTransactionAnchorObserver.Context context(
      Connection connection, Admission admission, String scenario) throws Exception {
    JdbcConnection driver = connection.unwrap(JdbcConnection.class);
    if (driver.getId() != admission.poolConnection) {
      throw new IllegalStateException("Target physical connection changed");
    }
    // The mapping was established by the earlier pool identity query, not guessed from driver ID.
    return new JdbcTransactionAnchorObserver.Context(
        admission.owner(),
        scenario,
        "borrow-" + scenario,
        admission.uuid(),
        driver.getId(),
        admission.poolThread,
        scenario);
  }

  private static Map<String, Object> identity(
      Connection connection, Admission admission, Ledger ledger, String label, boolean pool)
      throws Exception {
    List<Map<String, Object>> rows =
        query(
            connection,
            ledger,
            label,
            "SELECT @@server_uuid AS uuid, VERSION() AS version, CONNECTION_ID() AS connection_id,"
                + " own_thread.THREAD_ID AS pfs_thread_id, own_thread.INSTRUMENTED AS instrumented,"
                + " own_thread.HISTORY AS history, DATABASE() AS catalog FROM"
                + " performance_schema.threads AS own_thread WHERE"
                + " own_thread.PROCESSLIST_ID=CONNECTION_ID()");
    if (rows.size() != 1) {
      throw new IllegalStateException("Identity row count");
    }
    Map<String, Object> row = rows.get(0);
    JdbcConnection driver = connection.unwrap(JdbcConnection.class);
    if (!admission.uuid().equals(row.get("uuid"))
        || !"8.0.46".equals(row.get("version"))
        || !(row.get("connection_id") instanceof Number)
        || ((Number) row.get("connection_id")).longValue() != driver.getId()
        || !(row.get("pfs_thread_id") instanceof Number)
        || ((Number) row.get("pfs_thread_id")).longValue() <= 0
        || !"YES".equals(row.get("instrumented"))
        || !"YES".equals(row.get("history"))
        || (pool ? !admission.schema().equals(row.get("catalog")) : row.get("catalog") != null)) {
      throw new IllegalStateException("Native identity/qualified instance mismatch");
    }
    if (pool) {
      admission.poolThread = ((Number) row.get("pfs_thread_id")).longValue();
      admission.poolConnection = driver.getId();
    }
    return row;
  }

  private static Map<String, Object> driverFacts(Connection connection) throws Exception {
    JdbcConnection driver = connection.unwrap(JdbcConnection.class);
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put("driverName", connection.getMetaData().getDriverName());
    facts.put("driverVersion", connection.getMetaData().getDriverVersion());
    facts.put(
        "driverCodeSource",
        driver.getClass().getProtectionDomain().getCodeSource().getLocation().toString());
    Map<String, Object> properties = new LinkedHashMap<>();
    for (String name : PROPERTY_NAMES) {
      RuntimeProperty<?> property = driver.getPropertySet().getProperty(name);
      Map<String, Object> value = new LinkedHashMap<>();
      value.put("value", property.getValue());
      value.put("explicitlySet", property.isExplicitlySet());
      properties.put(name, value);
    }
    facts.put("properties", properties);
    return facts;
  }

  private static Map<String, Object> state(Connection connection) throws Exception {
    JdbcConnection driver = connection.unwrap(JdbcConnection.class);
    NativeSession session = (NativeSession) driver.getSession();
    NativeServerSession server = (NativeServerSession) session.getServerSession();
    Map<String, Object> state = new LinkedHashMap<>();
    state.put("connection", driver.getId());
    state.put("jdbcAutoCommitCached", server.isAutoCommit());
    state.put("serverStatusFlagsCached", server.getStatusFlags());
    state.put("serverInTransactionCached", server.inTransactionOnServer());
    state.put("serverAutocommitBitCached", server.isAutocommit());
    state.put("driverReadOnlyLocal", driver.isReadOnly(false));
    // getTransactionIsolation may execute a SELECT when useLocalSessionState=false; do not call it.
    Field isolation =
        com.zaxxer.hikari.pool.ProxyConnection.class.getDeclaredField("transactionIsolation");
    isolation.setAccessible(true);
    state.put("poolIsolationCached", isolation.getInt(connection));
    Field dirty =
        com.zaxxer.hikari.pool.ProxyConnection.class.getDeclaredField("isCommitStateDirty");
    dirty.setAccessible(true);
    state.put("poolCommitStateDirty", dirty.getBoolean(connection));
    state.put("protocolCommandCount", session.getCommandCount());
    state.put("statusQualification", "CACHED_LAST_RESPONSE_NOT_SERVER_TRANSACTION_QUERY");
    return state;
  }

  private static long visible(
      Connection observer, Ledger ledger, String table, int marker, String label) throws Exception {
    return ((Number)
            query(
                    observer,
                    ledger,
                    label,
                    "SELECT COUNT(*) AS marker_count FROM " + table + " WHERE marker_id=" + marker)
                .get(0)
                .get("marker_count"))
        .longValue();
  }

  private static List<Map<String, Object>> query(
      Connection connection, Ledger ledger, String label, String sql) throws Exception {
    ledger.reserve(label, "SQL", 1);
    List<Map<String, Object>> rows = new ArrayList<>();
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery(sql)) {
      int columnCount = result.getMetaData().getColumnCount();
      while (result.next()) {
        if (rows.size() >= 256) {
          throw new IllegalStateException("Qualification result row cap");
        }
        Map<String, Object> row = new LinkedHashMap<>();
        for (int column = 1; column <= columnCount; column++) {
          row.put(result.getMetaData().getColumnLabel(column), result.getObject(column));
        }
        rows.add(row);
      }
    }
    return rows;
  }

  private static void sql(Connection connection, Ledger ledger, String label, String sql)
      throws Exception {
    ledger.reserve(label, "SQL", 1);
    try (Statement statement = connection.createStatement()) {
      statement.execute(sql);
    }
  }

  private static void tx(Ledger ledger, String label, SqlAction action) throws Exception {
    ledger.reserve(label, "POTENTIAL_SQL_TRANSACTION_API", 1);
    action.run();
  }

  private static Map<String, Object> failure(Throwable failure) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("type", failure.getClass().getName());
    if (failure instanceof SQLException sqlFailure) {
      result.put("sqlState", sqlFailure.getSQLState());
      result.put("vendorCode", sqlFailure.getErrorCode());
    }
    // Raw exception messages may contain a URL; keep the stable type/state without credentials.
    return result;
  }

  private static void verifyClosed(
      Connection connection, String boundary, List<Map<String, Object>> closeErrors) {
    // A fallback close cannot erase an earlier missing close acknowledgement or getter failure.
    try {
      if (connection != null && connection.isClosed()) {
        return;
      }
      closeErrors.add(
          Map.of(
              "boundary",
              boundary,
              "status",
              "UNKNOWN",
              "hardFailure",
              true,
              "reason",
              "PHYSICAL_CLOSE_NOT_CONFIRMED"));
    } catch (Throwable failure) {
      Map<String, Object> error = new LinkedHashMap<>(failure(failure));
      error.put("boundary", boundary);
      error.put("status", "UNKNOWN");
      error.put("hardFailure", true);
      error.put("reason", "PHYSICAL_CLOSE_STATE_UNAVAILABLE");
      closeErrors.add(error);
    }
  }

  /** A single explicit JDBC action with a checked SQLException. */
  @FunctionalInterface
  private interface SqlAction {
    /** Performs one already-budgeted JDBC action. */
    void run() throws SQLException;
  }

  /**
   * Conservative explicit-command ledger; protocol count is separately reported, never called SQL.
   */
  private static final class Ledger {
    /** Root's original active deadline, never reset by a case or command. */
    private final long deadline;

    /** Ordered labels and conservative reserved command maxima. */
    private final List<Map<String, Object>> entries = new ArrayList<>();

    /** Total reserved explicit commands/potential SQL-emitting API commands. */
    private int used;

    private Ledger(long deadline) {
      this.deadline = deadline;
    }

    private synchronized void reserve(String label, String kind, int maximum) {
      if (System.currentTimeMillis() >= deadline || used + maximum > COMMAND_LIMIT) {
        throw new IllegalStateException("Qualification command/deadline admission exhausted");
      }
      used += maximum;
      entries.add(
          Map.of(
              "label", label, "kind", kind, "reservedMax", maximum, "cumulativeReservedMax", used));
    }
  }

  /**
   * Dedicated datasource captures actual physical sessions; only Hikari wraps the target session.
   */
  private static final class OwnedSource implements DataSource {
    /** Checked fixed-schema JDBC URL; never serialized into facts. */
    private final String jdbcUrl;

    /** Shared finite command ledger. */
    private final Ledger ledger;

    /** Physical sessions retained only for read-only local driver facts. */
    private final List<JdbcConnection> physical = new ArrayList<>();

    /** Local protocol counts, including non-SQL commands, at explicitly named boundaries. */
    private final List<Map<String, Object>> protocolCounts = new ArrayList<>();

    private OwnedSource(String jdbcUrl, Ledger ledger) {
      this.jdbcUrl = jdbcUrl;
      this.ledger = ledger;
    }

    private Connection openObserver() throws SQLException {
      ledger.reserve("observer.physical.open", "DRIVER_CONNECT_NOT_SQL_COUNT", 1);
      String noCatalog = jdbcUrl.replaceFirst("/cm21_jdbc_[0-9a-f]{32}\\?", "/?");
      Connection connection = DriverManager.getConnection(noCatalog, "root", "root");
      physical.add(connection.unwrap(JdbcConnection.class));
      setTimeZone(connection, "observer.timeZone");
      captureProtocolCounts("afterObserverOpen");
      return connection;
    }

    /** Creates at most two dedicated physical connections: observer and the one-slot pool. */
    @Override
    public synchronized Connection getConnection() throws SQLException {
      if (physical.size() >= 2) {
        throw new SQLException("No replacement physical connection is authorized");
      }
      ledger.reserve("physical.open." + physical.size(), "DRIVER_CONNECT_NOT_SQL_COUNT", 1);
      Connection connection = DriverManager.getConnection(jdbcUrl, "root", "root");
      physical.add(connection.unwrap(JdbcConnection.class));
      setTimeZone(connection, "pool.timeZone");
      captureProtocolCounts("afterPhysicalOpen");
      return connection;
    }

    private void setTimeZone(Connection connection, String label) throws SQLException {
      ledger.reserve(label, "SQL", 1);
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET time_zone = '+08:00'");
      }
    }

    private void captureProtocolCounts(String boundary) {
      for (JdbcConnection driver : physical) {
        try {
          if (driver.isClosed()) {
            protocolCounts.add(
                Map.of(
                    "boundary",
                    boundary,
                    "status",
                    "CLOSED",
                    "lastCounter",
                    "RECORDED_BEFORE_PHYSICAL_CLOSE"));
            continue;
          }
          protocolCounts.add(
              Map.of(
                  "boundary",
                  boundary,
                  "connection",
                  driver.getId(),
                  "count",
                  ((NativeSession) driver.getSession()).getCommandCount()));
        } catch (Throwable failure) {
          protocolCounts.add(
              Map.of("boundary", boundary, "status", "UNKNOWN", "failure", failure(failure)));
        }
      }
    }

    private void verifyPoolPhysicalClosed(List<Map<String, Object>> closeErrors) {
      verifyClosed(physical.size() > 1 ? physical.get(1) : null, "pool.close", closeErrors);
    }

    private void closeRemaining(List<Map<String, Object>> closeErrors) {
      // Covers a pool constructor/open failure too; the two physical closes were reserved up front.
      for (JdbcConnection driver : physical) {
        try {
          if (!driver.isClosed()) {
            protocolCounts.add(
                Map.of(
                    "boundary",
                    "beforeRemainingClose",
                    "connection",
                    driver.getId(),
                    "count",
                    ((NativeSession) driver.getSession()).getCommandCount()));
            driver.close();
          }
        } catch (Throwable failure) {
          closeErrors.add(failure(failure));
        }
      }
    }

    /** Credentials are fixed by the local qualification contract. */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      if (!"root".equals(username) || !"root".equals(password)) {
        throw new SQLException("Only fixed local credentials are permitted");
      }
      return getConnection();
    }

    /** No output writer is installed by this bounded datasource. */
    @Override
    public PrintWriter getLogWriter() {
      return null;
    }

    /** Changing datasource logging is unsupported. */
    @Override
    public void setLogWriter(PrintWriter writer) {
      throw new UnsupportedOperationException();
    }

    /** Login timeout is fixed in the checked JDBC URL. */
    @Override
    public void setLoginTimeout(int seconds) {
      throw new UnsupportedOperationException();
    }

    /** The driver URL supplies a three-second connect timeout. */
    @Override
    public int getLoginTimeout() {
      return 3;
    }

    /** Returns an ordinary logger without starting any service. */
    @Override
    public Logger getParentLogger() {
      return Logger.getLogger("cm21.jdbc.qualification");
    }

    /** This datasource is not a wrapper around another datasource. */
    @Override
    public <Type> Type unwrap(Class<Type> type) throws SQLException {
      if (type.isInstance(this)) {
        return type.cast(this);
      }
      throw new SQLException("Unsupported datasource unwrap");
    }

    /** Reports the datasource's own type only. */
    @Override
    public boolean isWrapperFor(Class<?> type) {
      return type.isInstance(this);
    }
  }

  /**
   * Root-produced admission is a checked handoff, not a replacement for external resource proof.
   */
  private static final class Admission {
    /** Exact owner/schema/url/UUID/output/deadline copied from checked root input. */
    private final Map<String, String> options;

    /** Root's finite manifest, checked before any JDBC action. */
    private final Map<String, Object> manifest;

    /** Native target thread established from the pool connection, not guessed. */
    private long poolThread;

    /** Native driver connection identity must remain stable across every target borrow. */
    private long poolConnection;

    private Admission(Map<String, String> options, Map<String, Object> manifest) {
      this.options = options;
      this.manifest = manifest;
    }

    private String owner() {
      return options.get("owner");
    }

    private String schema() {
      return options.get("schema");
    }

    private String jdbcUrl() {
      return options.get("jdbc-url");
    }

    private String uuid() {
      return options.get("expected-uuid");
    }

    private Path output() {
      return Path.of(options.get("output"));
    }

    private long deadlineEpochMillis() {
      return ((Number) manifest.get("deadlineEpochMillis")).longValue();
    }

    private Map<String, Object> manifest() {
      return manifest;
    }

    private static Admission parse(String[] arguments) throws Exception {
      if (!arguments[0].equals("--execute-authorized") || arguments.length != 13) {
        throw new IllegalArgumentException("Explicit admission requires exactly six named values");
      }
      Map<String, String> options = new LinkedHashMap<>();
      List<String> permitted =
          List.of("owner", "schema", "jdbc-url", "expected-uuid", "manifest", "output");
      for (int index = 1; index < arguments.length; index += 2) {
        String key = arguments[index].startsWith("--") ? arguments[index].substring(2) : "";
        if (!permitted.contains(key) || options.put(key, arguments[index + 1]) != null) {
          throw new IllegalArgumentException("Unknown or duplicate admission argument");
        }
      }
      if (options.size() != permitted.size()
          || !options.get("owner").matches("[0-9a-f]{32}")
          || !options.get("schema").equals("cm21_jdbc_" + options.get("owner"))
          || !options
              .get("expected-uuid")
              .matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}")) {
        throw new IllegalArgumentException("Owned qualification identity is incomplete");
      }
      String expectedUrl = "jdbc:mysql://127.0.0.1:9919/" + options.get("schema") + URL_SUFFIX;
      if (!expectedUrl.equals(options.get("jdbc-url"))) {
        throw new IllegalArgumentException("Only the exact fixed loopback URL is permitted");
      }
      Path manifestPath = Path.of(options.get("manifest"));
      if (Files.size(manifestPath) > 65536 || Files.exists(Path.of(options.get("output")))) {
        throw new IllegalArgumentException("Admission too large or output already exists");
      }
      Map<String, Object> manifest =
          JSON.readValue(
              Files.readAllBytes(manifestPath), new TypeReference<Map<String, Object>>() {});
      if (!Boolean.TRUE.equals(manifest.get("resourceVerified"))
          || !options.get("owner").equals(manifest.get("owner"))
          || !options.get("schema").equals(manifest.get("schema"))
          || !expectedUrl.equals(manifest.get("jdbcUrl"))
          || !options.get("expected-uuid").equals(manifest.get("expectedServerUuid"))
          || !(manifest.get("containerId") instanceof String container)
          || !container.matches("[0-9a-f]{64}")
          || !(manifest.get("imageId") instanceof String image)
          || !MYSQL_IMAGE.equals(image)
          || !Integer.valueOf(COMMAND_LIMIT).equals(manifest.get("maxExplicitJavaCommands"))
          || !(manifest.get("deadlineEpochMillis") instanceof Number)) {
        throw new IllegalArgumentException("Root admission fields do not match explicit arguments");
      }
      long deadline = ((Number) manifest.get("deadlineEpochMillis")).longValue();
      long remaining = deadline - System.currentTimeMillis();
      if (remaining <= 0 || remaining > 45000) {
        throw new IllegalArgumentException(
            "Root's remaining active deadline must be within 45 seconds");
      }
      return new Admission(options, manifest);
    }
  }
}
