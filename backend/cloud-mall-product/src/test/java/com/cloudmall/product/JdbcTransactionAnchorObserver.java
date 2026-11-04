package com.cloudmall.product;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Independent, opt-in qualification observer; it does not establish transaction transparency. */
public final class JdbcTransactionAnchorObserver {
  /** The pending current statement query deliberately returns every pending row. */
  public static final String ANCHOR_SQL =
      "SELECT @@server_uuid AS uuid, CONNECTION_ID() AS connection_id,"
          + " own_thread.THREAD_ID AS pfs_thread_id, own_thread.INSTRUMENTED AS instrumented,"
          + " own_thread.HISTORY AS history, own_statement.EVENT_ID AS event_id,"
          + " own_statement.END_EVENT_ID AS end_event_id, own_statement.EVENT_NAME AS event_name,"
          + " own_statement.NESTING_EVENT_LEVEL AS nesting_level"
          + " FROM performance_schema.threads AS own_thread"
          + " JOIN performance_schema.events_statements_current AS own_statement"
          + " ON own_statement.THREAD_ID=own_thread.THREAD_ID"
          + " WHERE own_thread.PROCESSLIST_ID=CONNECTION_ID()"
          + " AND own_statement.END_EVENT_ID IS NULL";

  /** Raw column names, including nullable END_EVENT_ID, in the query's declared order. */
  private static final List<String> COLUMNS =
      List.of(
          "uuid",
          "connection_id",
          "pfs_thread_id",
          "instrumented",
          "history",
          "event_id",
          "end_event_id",
          "event_name",
          "nesting_level");

  private JdbcTransactionAnchorObserver() {}

  /** Qualification identity established before observing the held borrow. */
  public record Context(
      String owner,
      String call,
      String borrow,
      String serverUuid,
      long driverConnectionId,
      long pfsThreadId,
      String scenario) {}

  /** The side of the original JDBC commit. */
  public enum Side {
    PRE,
    POST
  }

  /**
   * A failed observation stage; the original object remains available to the qualification sink.
   */
  public record Failure(String stage, Throwable cause) {}

  /**
   * Full raw rows and independent response/resource outcomes; VALID is only an anchor shape check.
   */
  public record AnchorResult(
      Side side,
      String status,
      String reason,
      List<Map<String, Object>> rows,
      boolean responseDrained,
      boolean resultSetClosed,
      boolean statementClosed,
      List<Failure> failures) {
    /** Copies raw rows without rejecting SQL NULL values or converting native numeric types. */
    public AnchorResult {
      List<Map<String, Object>> copied = new ArrayList<>();
      for (Map<String, Object> row : rows) {
        copied.add(Collections.unmodifiableMap(new LinkedHashMap<>(row)));
      }
      rows = List.copyOf(copied);
      failures = List.copyOf(failures);
    }
  }

  /** One observation notification; an original delegate failure is never wrapped or replaced. */
  public record Observation(String kind, Context context, AnchorResult anchor, Throwable cause) {}

  /** Optional recorder whose failures cannot affect the original JDBC operation. */
  @FunctionalInterface
  public interface ObservationSink {
    /** Records one observation, without authority to alter the delegate's outcome. */
    void accept(Observation observation) throws Throwable;
  }

  /** Disabled by default: delegates directly without inspecting context or touching the sink. */
  public static void commit(Connection connection, Context context, ObservationSink sink)
      throws SQLException {
    commit(connection, context, false, sink);
  }

  /** Observes one held connection's commit; observational failures are isolated on both sides. */
  public static void commit(
      Connection connection, Context context, boolean enabled, ObservationSink sink)
      throws SQLException {
    // 1. The disabled path has exactly the original call and no observer validation or SQL.
    if (!enabled) {
      connection.commit();
      return;
    }
    // 2. Failed pre-observation or recording never prevents the original delegate.
    observe(connection, context, Side.PRE, sink);
    try {
      connection.commit();
    } catch (SQLException | RuntimeException | Error original) {
      emit(sink, "DELEGATE_THREW", context, null, original);
      throw original;
    }
    // 3. Post-observation stays in this borrow, after successful commit and before caller cleanup.
    emit(sink, "DELEGATE_RETURNED", context, null, null);
    observe(connection, context, Side.POST, sink);
  }

  /** Executes one ordinary Statement on the supplied connection and preserves all returned rows. */
  public static AnchorResult readAnchor(Connection connection, Context context, Side side) {
    List<Map<String, Object>> rows = new ArrayList<>();
    List<Failure> failures = new ArrayList<>();
    Statement statement = null;
    ResultSet resultSet = null;
    boolean drained = false;
    boolean resultClosed = false;
    boolean statementClosed = false;
    String stage = "CONTEXT";
    try {
      validateContext(context, side);
      stage = "CREATE_STATEMENT";
      statement = connection.createStatement();
      stage = "QUERY";
      resultSet = statement.executeQuery(ANCHOR_SQL);
      stage = "READ";
      while (resultSet.next()) {
        Map<String, Object> row = new LinkedHashMap<>();
        for (String column : COLUMNS) {
          row.put(column, resultSet.getObject(column));
        }
        rows.add(row);
      }
      drained = true;
    } catch (Throwable failure) {
      failures.add(new Failure(stage, failure));
    } finally {
      if (resultSet != null) {
        try {
          resultSet.close();
          resultClosed = true;
        } catch (Throwable failure) {
          failures.add(new Failure("RESULT_SET_CLOSE", failure));
        }
      }
      if (statement != null) {
        try {
          statement.close();
          statementClosed = true;
        } catch (Throwable failure) {
          failures.add(new Failure("STATEMENT_CLOSE", failure));
        }
      }
    }
    String reason = failures.isEmpty() ? validateRows(rows, context) : "OBSERVATION_FAILURE";
    return new AnchorResult(
        side,
        reason.isEmpty() ? "VALID" : "UNKNOWN",
        reason,
        rows,
        drained,
        resultClosed,
        statementClosed,
        failures);
  }

  private static void validateContext(Context context, Side side) {
    if (context == null
        || side == null
        || context.owner() == null
        || !context.owner().matches("[0-9a-f]{32}")
        || blank(context.call())
        || blank(context.borrow())
        || blank(context.scenario())
        || blank(context.serverUuid())
        || context.driverConnectionId() <= 0
        || context.pfsThreadId() <= 0) {
      throw new IllegalArgumentException("Incomplete qualification identity");
    }
  }

  private static boolean blank(String value) {
    return value == null || value.isBlank();
  }

  private static String validateRows(List<Map<String, Object>> rows, Context context) {
    // Native IDs share an event sequence; no adjacency or cross-class timer inference is made.
    if (rows.size() != 1) {
      return "PENDING_ROW_COUNT";
    }
    Map<String, Object> row = rows.get(0);
    if (!context.serverUuid().equals(row.get("uuid"))
        || !integerEquals(row.get("connection_id"), context.driverConnectionId())
        || !integerEquals(row.get("pfs_thread_id"), context.pfsThreadId())) {
      return "IDENTITY_MISMATCH";
    }
    if (!"YES".equals(row.get("instrumented"))
        || !"YES".equals(row.get("history"))
        || !"statement/sql/select".equals(row.get("event_name"))
        || row.get("end_event_id") != null
        || !integerEquals(row.get("nesting_level"), 0)
        || !positiveInteger(row.get("event_id"))) {
      return "PENDING_STATEMENT_SHAPE";
    }
    return "";
  }

  private static boolean integerEquals(Object raw, long expected) {
    if (!(raw instanceof Number)) {
      return false;
    }
    try {
      return new BigDecimal(raw.toString()).longValueExact() == expected;
    } catch (NumberFormatException | ArithmeticException failure) {
      return false;
    }
  }

  private static boolean positiveInteger(Object raw) {
    if (!(raw instanceof Number)) {
      return false;
    }
    try {
      return new BigDecimal(raw.toString()).toBigIntegerExact().signum() > 0;
    } catch (NumberFormatException | ArithmeticException failure) {
      return false;
    }
  }

  private static void observe(
      Connection connection, Context context, Side side, ObservationSink sink) {
    try {
      AnchorResult result = readAnchor(connection, context, side);
      emit(sink, side.name(), context, result, null);
      for (Failure failure : result.failures()) {
        emit(sink, "OBSERVATION_FAILED", context, result, failure.cause());
      }
    } catch (Throwable failure) {
      emit(sink, "OBSERVATION_FAILED", context, null, failure);
    }
  }

  private static void emit(
      ObservationSink sink, String kind, Context context, AnchorResult anchor, Throwable cause) {
    try {
      if (sink != null) {
        sink.accept(new Observation(kind, context, anchor, cause));
      }
    } catch (Throwable ignored) {
      // Recording is observational, including allocation or journal failures in the sink itself.
    }
  }
}
