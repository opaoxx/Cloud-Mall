package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.product.mapper.ProductMetricMapper;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** Resource-free checks of the explicit migration runner's parsing and ownership boundaries. */
class ProductMetricSchemaIndexContractTest {
  @Test
  void delimiterParserKeepsProcedureBodyAndQuotedSemicolonsInOneStatement() {
    String script =
        "-- reviewed fixture\n"
            + "DELIMITER $$\n"
            + "CREATE PROCEDURE owned()\n"
            + "BEGIN\n"
            + " SELECT 'one;two';\n"
            + " SELECT 'it''s quoted';\n"
            + "END$$\n"
            + "DELIMITER ;\n"
            + "CALL owned();\n"
            + "DROP PROCEDURE owned();\n";
    List<String> statements = MetricIndexMigrationTestSupport.statements(script);
    assertEquals(3, statements.size());
    assertTrue(statements.get(0).contains("SELECT 'one;two';"));
    assertTrue(statements.get(0).endsWith("END"));
    assertEquals("CALL owned()", statements.get(1));
    assertEquals("DROP PROCEDURE owned()", statements.get(2));
    assertThrows(
        IllegalArgumentException.class,
        () -> MetricIndexMigrationTestSupport.statements("SELECT 'unfinished"));
  }

  @Test
  void disabledOrMalformedConfigurationIsRejectedBeforeAnyDatabaseConnection() {
    Properties properties = new Properties();
    assertThrows(
        IllegalStateException.class,
        () -> MetricIndexMigrationTestSupport.configuration(properties, Path.of(".")));
    properties.setProperty("cm21.indexMigration.execute", "TRUE");
    assertThrows(
        IllegalStateException.class,
        () -> MetricIndexMigrationTestSupport.configuration(properties, Path.of(".")));
    properties.setProperty("cm21.indexMigration.execute", "true");
    properties.setProperty("cm21.indexMigration.owner", "mysql");
    assertThrows(
        IllegalStateException.class,
        () -> MetricIndexMigrationTestSupport.configuration(properties, Path.of(".")));
    properties.setProperty("cm21.indexMigration.owner", "0123456789abcdef0123456789abcdef");
    properties.setProperty("cm21.indexMigration.phase", "LEGACY");
    properties.setProperty("cm21.indexMigration.port", "0");
    assertThrows(
        IllegalStateException.class,
        () -> MetricIndexMigrationTestSupport.configuration(properties, Path.of(".")));
    properties.setProperty("cm21.indexMigration.port", "3306");
    properties.setProperty("cm21.indexMigration.serverUuid", "not-a-server-uuid");
    assertThrows(
        IllegalStateException.class,
        () -> MetricIndexMigrationTestSupport.configuration(properties, Path.of(".")));
  }

  @Test
  void ownedSchemaNamesCannotSelectSystemForeignOrUnboundedIdentifiers() {
    String owner = "0123456789abcdef0123456789abcdef";
    assertEquals(
        "cm21_ix_0123456789ab_legacy_roundtrip",
        MetricIndexMigrationTestSupport.schema(owner, "LEGACY", "roundtrip"));
    assertEquals(
        "cm21_ix_0123456789ab_legacy_mysql",
        MetricIndexMigrationTestSupport.schema(owner, "LEGACY", "mysql"));
    for (String invalid : List.of("a-b", "a`b", "../outside", "a".repeat(49)))
      assertThrows(
          IllegalArgumentException.class,
          () -> MetricIndexMigrationTestSupport.schema(owner, "LEGACY", invalid));
    assertThrows(
        IllegalArgumentException.class,
        () -> MetricIndexMigrationTestSupport.schema(owner, "OTHER", "case"));
    assertThrows(
        IllegalArgumentException.class,
        () -> MetricIndexMigrationTestSupport.schema("f".repeat(31), "TARGET", "case"));
  }
}

/** Package-private fixture shared only by these two standard Maven test classes. */
final class MetricIndexMigrationTestSupport {
  /** Exact forward helper owned by the reviewed SQL script. */
  static final String FORWARD_PROCEDURE = "cm21_metric_applied_index_e851_migrate";

  /** Exact reverse helper owned by the reviewed SQL script. */
  static final String REVERSE_PROCEDURE = "cm21_metric_applied_index_e851_rollback";

  /** Exact target table; no arbitrary table identifier is accepted by helper methods. */
  static final String LEDGER = "product_metric_applied_event";

  /** Independent immutable evidence encoder. */
  static final ObjectMapper JSON = new ObjectMapper();

  /** Fixed application tables and deterministic primary-key ordering for typed snapshots. */
  private static final Map<String, String> TABLE_ORDER =
      Map.of(
          "product_metric_control",
          "singleton_id",
          "product_online_stat",
          "product_id",
          LEDGER,
          "event_id",
          "product_metric_outbox",
          "event_id",
          "product_metric_projection_outbox",
          "product_id,metric_version",
          "product_metric_quarantine",
          "quarantine_id");

  /**
   * The sole explicit opt-in connection configuration; fields have no external resource handles.
   */
  static final class Configuration {
    /** Canonical repository root found from the standard Maven working directory. */
    final Path repository;

    /** Precreated canonical evidence directory under this repository's target. */
    final Path evidence;

    /** Root-approved experiment owner, never inferred from a database. */
    final String owner;

    /** Test assertion phase, never used to switch production guard behavior. */
    final String phase;

    /** Root-captured UUID of the one approved fresh MySQL server. */
    final String serverUuid;

    /** Explicit fresh MySQL loopback port. */
    final int port;

    /** Bind already validated configuration values. */
    private Configuration(
        Path repository, Path evidence, String owner, String phase, int port, String serverUuid) {
      this.repository = repository;
      this.evidence = evidence;
      this.owner = owner;
      this.phase = phase;
      this.port = port;
      this.serverUuid = serverUuid;
    }
  }

  /** Validate every ownership/configuration field before a JDBC connection is possible. */
  static Configuration configuration(Properties properties, Path startingDirectory) {
    require(
        "true".equals(properties.getProperty("cm21.indexMigration.execute")),
        "CM21_INDEX_EXECUTION_DISABLED");
    String owner = properties.getProperty("cm21.indexMigration.owner", "");
    String phase = properties.getProperty("cm21.indexMigration.phase", "");
    require(owner.matches("[0-9a-f]{32}"), "CM21_INDEX_OWNER_INVALID");
    require(phase.equals("LEGACY") || phase.equals("TARGET"), "CM21_INDEX_PHASE_INVALID");
    int port;
    try {
      port = Integer.parseInt(properties.getProperty("cm21.indexMigration.port", ""));
    } catch (NumberFormatException invalidPort) {
      throw new IllegalStateException("CM21_INDEX_PORT_INVALID", invalidPort);
    }
    require(port >= 1 && port <= 65535, "CM21_INDEX_PORT_INVALID");
    String serverUuid = properties.getProperty("cm21.indexMigration.serverUuid", "");
    require(
        serverUuid.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}"),
        "CM21_INDEX_SERVER_UUID_INVALID");
    try {
      Path repository = startingDirectory.toAbsolutePath().normalize();
      while (repository != null
          && !Files.isRegularFile(
              repository.resolve("backend/database/cm21_product_online_metrics.sql")))
        repository = repository.getParent();
      require(repository != null, "CM21_INDEX_REPOSITORY_NOT_FOUND");
      repository = repository.toRealPath();
      Path evidence = Path.of(properties.getProperty("cm21.indexMigration.evidenceDirectory", ""));
      require(
          evidence.isAbsolute()
              && evidence.equals(evidence.toRealPath())
              && evidence.startsWith(repository.resolve("target"))
              && Files.isDirectory(evidence),
          "CM21_INDEX_EVIDENCE_INVALID");
      return new Configuration(repository, evidence, owner, phase, port, serverUuid);
    } catch (IOException | java.nio.file.InvalidPathException failure) {
      throw new IllegalStateException("CM21_INDEX_PATH_INVALID", failure);
    }
  }

  /** Construct only the reviewed non-system identifier, with no SQL quoting escape surface. */
  static String schema(String owner, String phase, String caseName) {
    if (owner == null
        || !owner.matches("[0-9a-f]{32}")
        || !("LEGACY".equals(phase) || "TARGET".equals(phase))
        || caseName == null
        || !caseName.matches("[a-z][a-z0-9_]{0,39}"))
      throw new IllegalArgumentException("CM21_INDEX_SCHEMA_INVALID");
    String result =
        "cm21_ix_" + owner.substring(0, 12) + "_" + phase.toLowerCase(Locale.ROOT) + "_" + caseName;
    if (result.length() > 64) throw new IllegalArgumentException("CM21_INDEX_SCHEMA_INVALID");
    return result;
  }

  /**
   * Parse the two reviewed scripts' line-oriented DELIMITER syntax without sending client commands
   * to MySQL.
   */
  static List<String> statements(String script) {
    List<String> result = new ArrayList<>();
    StringBuilder pending = new StringBuilder();
    String delimiter = ";";
    for (String line : script.split("\\R", -1)) {
      String trimmed = line.trim();
      if (trimmed.toUpperCase(Locale.ROOT).startsWith("DELIMITER ")) {
        delimiter = trimmed.substring("DELIMITER ".length()).trim();
        if (!(delimiter.equals(";") || delimiter.equals("$$")))
          throw new IllegalArgumentException("CM21_INDEX_DELIMITER_INVALID");
        continue;
      }
      pending.append(line).append('\n');
      String accumulated = pending.toString().trim();
      if (accumulated.endsWith(delimiter)) {
        result.add(accumulated.substring(0, accumulated.length() - delimiter.length()).trim());
        pending.setLength(0);
      }
    }
    String remainder = pending.toString().replaceAll("(?m)^\\s*--.*$", "").trim();
    if (!remainder.isEmpty()) throw new IllegalArgumentException("CM21_INDEX_SQL_UNTERMINATED");
    return result;
  }

  /** A fresh independent schema; creation never uses IF NOT EXISTS or another owner's schema. */
  static final class Database {
    /** Validated explicit execution configuration. */
    final Configuration configuration;

    /** Unique schema for this exact test and phase. */
    final String name;

    /** Original real JDBC datasource injected into the production Mapper/transaction manager. */
    final DriverManagerDataSource dataSource;

    /**
     * Real original production Mapper, including its unchanged current verifySchema implementation.
     */
    final ProductMetricMapper mapper;

    /** Evidence-only test JdbcTemplate bound exclusively to this owned schema. */
    final JdbcTemplate jdbc;

    /** Create only this test's database through a dedicated, bounded loopback root connection. */
    Database(Configuration configuration, String caseName) throws Exception {
      this.configuration = configuration;
      this.name =
          schema(
              configuration.owner,
              configuration.phase,
              caseName + "_" + UUID.randomUUID().toString().replace("-", "").substring(0, 8));
      try (Connection admin = DriverManager.getConnection(url(configuration, ""), "root", "root");
          Statement statement = admin.createStatement()) {
        try (ResultSet identity = statement.executeQuery("SELECT @@server_uuid")) {
          if (!identity.next()
              || !configuration.serverUuid.equals(identity.getString(1))
              || identity.next()) throw new SQLException("CM21_INDEX_SERVER_UUID_MISMATCH");
        }
        statement.execute(
            "CREATE DATABASE `" + name + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_bin");
      }
      dataSource = new DriverManagerDataSource(url(configuration, name), "root", "root");
      jdbc = new JdbcTemplate(dataSource);
      mapper = new ProductMetricMapper(jdbc);
      try (Connection connection = connect()) {
        execute(
            connection,
            Files.readString(
                configuration.repository.resolve(
                    "backend/database/cm21_product_online_metrics.sql")));
        execute(
            connection,
            "CREATE TABLE product(id BIGINT NOT NULL PRIMARY KEY) ENGINE=InnoDB;\n"
                + "CREATE TABLE product_sku(id BIGINT NOT NULL PRIMARY KEY,product_id BIGINT NOT"
                + " NULL) ENGINE=InnoDB;\n"
                + "INSERT INTO product(id) VALUES(41),(42);\n");
      }
    }

    /** Open a new connection and verify that its selected catalog is exactly owned. */
    Connection connect() throws SQLException {
      Connection connection = dataSource.getConnection();
      if (!name.equals(connection.getCatalog())) {
        connection.close();
        throw new SQLException("CM21_INDEX_CATALOG_MISMATCH");
      }
      return connection;
    }

    /** Explicitly choose an OLD3 or NEW2 fixture without changing the production guard. */
    void layout(boolean old) throws Exception {
      boolean exists =
          jdbc.queryForObject(
                  "SELECT COUNT(*) FROM information_schema.statistics WHERE table_schema=DATABASE()"
                      + " AND table_name=? AND index_name='idx_metric_applied_product'",
                  Long.class,
                  LEDGER)
              > 0;
      try (Connection connection = connect();
          Statement statement = connection.createStatement()) {
        if (old && !exists)
          statement.execute(
              "ALTER TABLE product_metric_applied_event ADD INDEX"
                  + " idx_metric_applied_product(product_id,metric_version)");
        if (!old && exists)
          statement.execute(
              "ALTER TABLE product_metric_applied_event DROP INDEX idx_metric_applied_product");
      }
    }

    /** Run only a reviewed explicit SQL file, preserving the original SQLException on failure. */
    List<Map<String, String>> migrate(boolean forward) throws Exception {
      Path script =
          configuration.repository.resolve(
              "backend/database/"
                  + (forward
                      ? "migrate_cm21_metric_applied_product_index.sql"
                      : "rollback_cm21_metric_applied_product_index.sql"));
      try (Connection connection = connect()) {
        require(connection.getAutoCommit(), "CM21_INDEX_MIGRATION_REQUIRES_AUTOCOMMIT");
        long beforeTimeout = lockWaitTimeout(connection);
        try {
          List<Map<String, String>> results = execute(connection, Files.readString(script));
          long afterTimeout = lockWaitTimeout(connection);
          if (afterTimeout != beforeTimeout)
            throw new SQLException("CM21_INDEX_SESSION_TIMEOUT_NOT_RESTORED");
          results.add(
              Map.of(
                  "fixture_session_lock_wait_timeout_before",
                  Long.toString(beforeTimeout),
                  "fixture_session_lock_wait_timeout_after",
                  Long.toString(afterTimeout),
                  "fixture_session_timeout_restored",
                  "true"));
          return results;
        } catch (SQLException originalFailure) {
          try {
            if (lockWaitTimeout(connection) != beforeTimeout)
              originalFailure.addSuppressed(
                  new SQLException("CM21_INDEX_SESSION_TIMEOUT_NOT_RESTORED"));
          } catch (SQLException verificationFailure) {
            originalFailure.addSuppressed(verificationFailure);
          }
          throw originalFailure;
        }
      }
    }

    /** Independently read this same connection session without changing its state. */
    private long lockWaitTimeout(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement();
          ResultSet rows = statement.executeQuery("SELECT @@SESSION.lock_wait_timeout")) {
        if (!rows.next()) throw new SQLException("CM21_INDEX_SESSION_TIMEOUT_UNAVAILABLE");
        return rows.getLong(1);
      }
    }

    /**
     * Execute only on a catalog-checked owned connection, draining procedure result sets
     * explicitly.
     */
    List<Map<String, String>> execute(Connection connection, String script) throws SQLException {
      if (!name.equals(connection.getCatalog()))
        throw new SQLException("CM21_INDEX_CATALOG_MISMATCH");
      List<Map<String, String>> results = new ArrayList<>();
      for (String sql : statements(script)) {
        try (Statement statement = connection.createStatement()) {
          boolean hasResult = statement.execute(sql);
          while (true) {
            if (hasResult) {
              try (ResultSet rows = statement.getResultSet()) {
                ResultSetMetaData metadata = rows.getMetaData();
                while (rows.next()) {
                  Map<String, String> row = new LinkedHashMap<>();
                  for (int column = 1; column <= metadata.getColumnCount(); column++)
                    row.put(metadata.getColumnLabel(column), rows.getString(column));
                  results.add(row);
                }
              }
            } else if (statement.getUpdateCount() == -1) break;
            hasResult = statement.getMoreResults(Statement.CLOSE_CURRENT_RESULT);
          }
        }
      }
      return results;
    }

    /**
     * Remove only these two exactly named helpers from this newly owned fixture after a failed
     * CALL.
     */
    void clearOwnProcedures() throws SQLException {
      try (Connection connection = connect();
          Statement statement = connection.createStatement()) {
        statement.execute("DROP PROCEDURE IF EXISTS `" + FORWARD_PROCEDURE + "`");
        statement.execute("DROP PROCEDURE IF EXISTS `" + REVERSE_PROCEDURE + "`");
      }
    }

    /**
     * Snapshot every original metric table with JDBC type/null/value information, not row counts
     * alone.
     */
    Map<String, Object> dataFacts() throws SQLException {
      Map<String, Object> facts = new TreeMap<>();
      try (Connection connection = connect()) {
        for (Map.Entry<String, String> table : TABLE_ORDER.entrySet()) {
          List<Object> values = new ArrayList<>();
          try (Statement statement = connection.createStatement();
              ResultSet rows =
                  statement.executeQuery(
                      "SELECT * FROM `" + table.getKey() + "` ORDER BY " + table.getValue())) {
            ResultSetMetaData metadata = rows.getMetaData();
            while (rows.next()) {
              Map<String, Object> row = new LinkedHashMap<>();
              for (int column = 1; column <= metadata.getColumnCount(); column++) {
                Object value = rows.getObject(column);
                Map<String, Object> cell = new LinkedHashMap<>();
                cell.put("jdbcType", metadata.getColumnType(column));
                cell.put("sqlType", metadata.getColumnTypeName(column));
                cell.put("javaType", value == null ? null : value.getClass().getName());
                cell.put(
                    "value",
                    value instanceof byte[]
                        ? HexFormat.of().formatHex((byte[]) value)
                        : value instanceof Timestamp
                            ? ((Timestamp) value).toLocalDateTime().toString()
                            : value == null ? null : value.toString());
                row.put(metadata.getColumnName(column), cell);
              }
              values.add(row);
            }
          }
          facts.put(table.getKey(), values);
        }
      }
      return facts;
    }

    /**
     * Read the complete stable target definition except the one explicitly migratable ordinary
     * index.
     */
    Map<String, Object> definitionFacts() {
      Map<String, Object> facts = new LinkedHashMap<>();
      facts.put(
          "columns",
          jdbc.queryForList(
              "SELECT"
                  + " ordinal_position,column_name,column_type,is_nullable,column_default,extra,generation_expression,character_set_name,collation_name,datetime_precision,numeric_precision,numeric_scale"
                  + " FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                  + " table_name='product_metric_applied_event' ORDER BY ordinal_position"));
      facts.put(
          "checks",
          jdbc.queryForList(
              "SELECT tc.constraint_name,cc.check_clause,tc.enforced FROM"
                  + " information_schema.table_constraints tc JOIN"
                  + " information_schema.check_constraints cc ON"
                  + " tc.constraint_schema=cc.constraint_schema AND"
                  + " tc.constraint_name=cc.constraint_name WHERE tc.table_schema=DATABASE() AND"
                  + " tc.table_name='product_metric_applied_event' AND tc.constraint_type='CHECK'"
                  + " ORDER BY tc.constraint_name"));
      facts.put(
          "table",
          jdbc.queryForList(
              "SELECT table_type,engine,table_collation FROM information_schema.tables WHERE"
                  + " table_schema=DATABASE() AND table_name='product_metric_applied_event'"));
      return facts;
    }

    /** Capture rich fixed index metadata separately from volatile cardinality estimates. */
    List<Map<String, Object>> indexFacts() {
      return jdbc.queryForList(
          "SELECT"
              + " index_name,non_unique,seq_in_index,column_name,collation,sub_part,index_type,is_visible,expression,index_comment"
              + " FROM information_schema.statistics WHERE table_schema=DATABASE() AND"
              + " table_name='product_metric_applied_event' ORDER BY index_name,seq_in_index");
    }

    /**
     * Write one create-new owned evidence document; the caller retains all databases for Root
     * inspection.
     */
    void evidence(String suffix, Map<String, Object> facts) throws IOException {
      Map<String, Object> envelope = new LinkedHashMap<>();
      envelope.put("schema", name);
      envelope.put("phase", configuration.phase);
      envelope.putAll(facts);
      Files.write(
          configuration.evidence.resolve(name + "-" + suffix + ".json"),
          JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(envelope),
          StandardOpenOption.CREATE_NEW);
    }
  }

  /**
   * Fixed account and connection bounds; socket timeout exceeds the explicit ten-second MDL budget.
   */
  private static String url(Configuration configuration, String schema) {
    return "jdbc:mysql://127.0.0.1:"
        + configuration.port
        + "/"
        + schema
        + "?serverTimezone=Asia/Shanghai&useSSL=false&allowPublicKeyRetrieval=true&connectTimeout=3000&socketTimeout=30000";
  }

  /** Reject invalid setup before any resource action. */
  private static void require(boolean condition, String message) {
    if (!condition) throw new IllegalStateException(message);
  }
}
