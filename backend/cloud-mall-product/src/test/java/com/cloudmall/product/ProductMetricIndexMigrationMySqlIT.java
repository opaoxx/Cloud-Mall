package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.service.impl.metrics.ProductMetricService;
import com.cloudmall.product.util.ProductMetricEvents;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.*;
import java.util.*;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/**
 * Explicit fresh-MySQL migration regression; disabled unless Root supplies every owned boundary.
 */
@EnabledIfSystemProperty(named = "cm21.indexMigration.execute", matches = "true")
class ProductMetricIndexMigrationMySqlIT {
  /** Exact ordinary index names are used only inside newly owned fixtures. */
  private static final String ORDINARY = "idx_metric_applied_product";

  @Test
  void actualProductionGuardUsesExactPhaseLayoutAndRejectsTheOppositeLayout() throws Exception {
    var configuration = configuration();
    var oldDatabase = new MetricIndexMigrationTestSupport.Database(configuration, "guard_old");
    Set<String> nativeIndexes =
        configuration.phase.equals("LEGACY")
            ? Set.of("PRIMARY", "uk_metric_applied_version", ORDINARY)
            : Set.of("PRIMARY", "uk_metric_applied_version");
    assertEquals(
        nativeIndexes,
        indexNames(oldDatabase),
        "fresh initialization must already have its phase-native layout");
    oldDatabase.layout(true);
    var newDatabase = new MetricIndexMigrationTestSupport.Database(configuration, "guard_new");
    assertEquals(
        nativeIndexes,
        indexNames(newDatabase),
        "do not repair an incorrect fresh initialization before testing it");
    newDatabase.layout(false);
    if (configuration.phase.equals("LEGACY")) {
      assertDoesNotThrow(oldDatabase.mapper::verifySchema);
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, newDatabase.mapper::verifySchema);
      assertEquals("METRIC_SCHEMA_INDEXES_product_metric_applied_event", failure.getMessage());
    } else {
      assertDoesNotThrow(newDatabase.mapper::verifySchema);
      IllegalStateException failure =
          assertThrows(IllegalStateException.class, oldDatabase.mapper::verifySchema);
      assertEquals("METRIC_SCHEMA_INDEXES_product_metric_applied_event", failure.getMessage());
    }
    assertEquals(Set.of("PRIMARY", "uk_metric_applied_version", ORDINARY), indexNames(oldDatabase));
    assertEquals(Set.of("PRIMARY", "uk_metric_applied_version"), indexNames(newDatabase));
    oldDatabase.evidence(
        "guard",
        Map.of(
            "layout",
            "OLD3",
            "indexes",
            oldDatabase.indexFacts(),
            "allTablesGuardExpectedPass",
            configuration.phase.equals("LEGACY")));
    newDatabase.evidence(
        "guard",
        Map.of(
            "layout",
            "NEW2",
            "indexes",
            newDatabase.indexFacts(),
            "allTablesGuardExpectedPass",
            configuration.phase.equals("TARGET")));
  }

  @Test
  void fullForwardReverseAndBothNoopsPreserveEveryTypedValueAndDefinition() throws Exception {
    var database = database("roundtrip");
    database.layout(true);
    seedTypedFacts(database);
    Map<String, Object> before = database.dataFacts();
    Map<String, Object> definition = database.definitionFacts();
    List<Map<String, Object>> oldIndexes = database.indexFacts();
    List<Map<String, String>> forwardAudit = database.migrate(true);
    assertStatus(forwardAudit, "MIGRATED_TARGET", "OLD3", "NEW2");
    assertEquals(before, database.dataFacts());
    assertEquals(definition, database.definitionFacts());
    assertEquals(Set.of("PRIMARY", "uk_metric_applied_version"), indexNames(database));
    List<Map<String, Object>> targetIndexes = database.indexFacts();
    List<Map<String, String>> forwardRepeatAudit = database.migrate(true);
    assertStatus(forwardRepeatAudit, "NOOP_TARGET", "NEW2", "NEW2");
    assertEquals(targetIndexes, database.indexFacts());
    assertEquals(before, database.dataFacts());
    assertUnique1062(database);
    List<Map<String, String>> reverseAudit = database.migrate(false);
    assertStatus(reverseAudit, "ROLLED_BACK_OLD", "NEW2", "OLD3");
    assertEquals(before, database.dataFacts());
    assertEquals(definition, database.definitionFacts());
    assertEquals(oldIndexes, database.indexFacts());
    List<Map<String, String>> reverseRepeatAudit = database.migrate(false);
    assertStatus(reverseRepeatAudit, "NOOP_OLD", "OLD3", "OLD3");
    assertEquals(before, database.dataFacts());
    assertEquals(oldIndexes, database.indexFacts());
    database.evidence(
        "roundtrip",
        Map.of(
            "beforeTypedRows",
            before,
            "afterTypedRows",
            database.dataFacts(),
            "definition",
            definition,
            "oldIndexes",
            oldIndexes,
            "targetIndexes",
            targetIndexes,
            "forwardAudit",
            forwardAudit,
            "forwardRepeatAudit",
            forwardRepeatAudit,
            "reverseAudit",
            reverseAudit,
            "reverseRepeatAudit",
            reverseRepeatAudit));
  }

  @Test
  void malformedAndRichIndexesAreRejectedWithoutChangingTheInvalidFixture() throws Exception {
    Map<String, String> changes = new LinkedHashMap<>();
    changes.put(
        "missing_uk",
        "ALTER TABLE product_metric_applied_event DROP INDEX uk_metric_applied_version");
    changes.put(
        "wrong_uk_order",
        "ALTER TABLE product_metric_applied_event DROP INDEX uk_metric_applied_version,ADD UNIQUE"
            + " INDEX uk_metric_applied_version(metric_version,product_id)");
    changes.put(
        "invisible_uk",
        "ALTER TABLE product_metric_applied_event ALTER INDEX uk_metric_applied_version INVISIBLE");
    changes.put("missing_pk", "ALTER TABLE product_metric_applied_event DROP PRIMARY KEY");
    changes.put(
        "wrong_name",
        "ALTER TABLE product_metric_applied_event RENAME INDEX idx_metric_applied_product TO"
            + " unexpected_index");
    changes.put(
        "wrong_columns",
        "ALTER TABLE product_metric_applied_event DROP INDEX idx_metric_applied_product,ADD INDEX"
            + " idx_metric_applied_product(metric_version,product_id)");
    changes.put(
        "wrong_unique",
        "ALTER TABLE product_metric_applied_event DROP INDEX idx_metric_applied_product,ADD UNIQUE"
            + " INDEX idx_metric_applied_product(product_id,metric_version)");
    changes.put(
        "ordinary_comment",
        "ALTER TABLE product_metric_applied_event DROP INDEX idx_metric_applied_product,ADD INDEX"
            + " idx_metric_applied_product(product_id,metric_version) COMMENT"
            + " 'unreviewed-comment'");
    changes.put(
        "extra_index",
        "ALTER TABLE product_metric_applied_event ADD INDEX unreviewed_quantity(quantity)");
    changes.put(
        "invisible",
        "ALTER TABLE product_metric_applied_event ALTER INDEX idx_metric_applied_product"
            + " INVISIBLE");
    changes.put(
        "descending",
        "ALTER TABLE product_metric_applied_event DROP INDEX idx_metric_applied_product,ADD INDEX"
            + " idx_metric_applied_product(product_id DESC,metric_version)");
    changes.put(
        "expression",
        "ALTER TABLE product_metric_applied_event DROP INDEX idx_metric_applied_product,ADD INDEX"
            + " idx_metric_applied_product((product_id+0),metric_version)");
    for (Map.Entry<String, String> mutation : changes.entrySet()) {
      var database = database(mutation.getKey());
      database.layout(
          !mutation.getKey().equals("invisible_uk")
              || database.configuration.phase.equals("LEGACY"));
      try (Connection connection = database.connect();
          Statement statement = connection.createStatement()) {
        statement.execute(mutation.getValue());
      }
      List<Map<String, Object>> before = database.indexFacts();
      Map<String, Object> data = database.dataFacts();
      Map<String, Object> definition = database.definitionFacts();
      SQLException forward = assertThrows(SQLException.class, () -> database.migrate(true));
      assertSessionRestored(forward);
      database.clearOwnProcedures();
      SQLException reverse = assertThrows(SQLException.class, () -> database.migrate(false));
      assertSessionRestored(reverse);
      database.clearOwnProcedures();
      assertEquals(before, database.indexFacts());
      assertEquals(data, database.dataFacts());
      assertEquals(definition, database.definitionFacts());
      // The legacy Java descriptor intentionally cannot distinguish these rich properties.
      if (mutation.getKey().equals("invisible_uk")
          || configuration().phase.equals("LEGACY")
              && Set.of("invisible", "descending", "ordinary_comment").contains(mutation.getKey()))
        assertDoesNotThrow(database.mapper::verifySchema);
      database.evidence(
          "rejected",
          Map.of(
              "mutation",
              mutation.getValue(),
              "indexesBefore",
              before,
              "indexesAfter",
              database.indexFacts(),
              "forwardSqlState",
              String.valueOf(forward.getSQLState()),
              "forwardCode",
              forward.getErrorCode(),
              "reverseSqlState",
              String.valueOf(reverse.getSQLState()),
              "reverseCode",
              reverse.getErrorCode(),
              "typedRowsUnchanged",
              true));
    }
    // The first statement must reject an active transaction before any helper DDL can commit it.
    var dirty = database("dirty_transaction");
    dirty.layout(true);
    List<Map<String, Object>> before = dirty.indexFacts();
    SQLException originalFailure;
    try (Connection connection = dirty.connect()) {
      connection.setAutoCommit(false);
      try (Statement statement = connection.createStatement()) {
        statement.executeUpdate("INSERT INTO product(id) VALUES(99)");
      }
      String script =
          Files.readString(
              dirty.configuration.repository.resolve(
                  "backend/database/migrate_cm21_metric_applied_product_index.sql"));
      originalFailure = assertThrows(SQLException.class, () -> dirty.execute(connection, script));
      assertEquals(1568, originalFailure.getErrorCode());
      assertEquals("25001", originalFailure.getSQLState());
      assertEquals(
          0L, dirty.jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=99", Long.class));
      try (Statement statement = connection.createStatement();
          ResultSet marker = statement.executeQuery("SELECT COUNT(*) FROM product WHERE id=99")) {
        assertTrue(marker.next());
        assertEquals(
            1L,
            marker.getLong(1),
            "first-statement rejection must leave the original transaction uncommitted and intact");
      }
      connection.rollback();
    }
    assertEquals(before, dirty.indexFacts());
    assertEquals(
        0L, dirty.jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=99", Long.class));
    dirty.evidence(
        "dirty-transaction",
        Map.of(
            "mysqlErrorCode",
            originalFailure.getErrorCode(),
            "sqlState",
            originalFailure.getSQLState(),
            "uncommittedRowVisibleExternally",
            false,
            "indexesUnchanged",
            true));
  }

  @Test
  void unreviewedDependenciesAndUnknownProcedureNamesFailWithoutTargetAlter() throws Exception {
    Map<String, String> dependencies = new LinkedHashMap<>();
    dependencies.put(
        "incoming_fk",
        "CREATE TABLE dependent_ledger(event_id CHAR(64) CHARACTER SET ascii COLLATE ascii_bin NOT"
            + " NULL,CONSTRAINT unreviewed_fk FOREIGN KEY(event_id) REFERENCES"
            + " product_metric_applied_event(event_id)) ENGINE=InnoDB");
    dependencies.put(
        "outgoing_fk",
        "ALTER TABLE product_metric_applied_event ADD CONSTRAINT unreviewed_fk FOREIGN"
            + " KEY(product_id) REFERENCES product(id)");
    dependencies.put(
        "trigger",
        "CREATE TRIGGER unreviewed_trigger BEFORE INSERT ON product_metric_applied_event FOR EACH"
            + " ROW SET NEW.quantity=NEW.quantity");
    dependencies.put(
        "view", "CREATE VIEW unreviewed_view AS SELECT event_id FROM product_metric_applied_event");
    dependencies.put(
        "routine_collision",
        "CREATE PROCEDURE cm21_metric_applied_index_e851_migrate() SELECT"
            + " 'unrelated-owner-object'");
    for (Map.Entry<String, String> dependency : dependencies.entrySet()) {
      var database = database(dependency.getKey());
      database.layout(true);
      try (Connection connection = database.connect();
          Statement statement = connection.createStatement()) {
        statement.execute(dependency.getValue());
      }
      List<Map<String, Object>> before = database.indexFacts();
      Map<String, Object> data = database.dataFacts();
      SQLException failure = assertThrows(SQLException.class, () -> database.migrate(true));
      assertSessionRestored(failure);
      if (dependency.getKey().equals("routine_collision")) {
        assertEquals(1304, failure.getErrorCode());
        assertEquals(
            1L,
            database.jdbc.queryForObject(
                "SELECT COUNT(*) FROM information_schema.routines WHERE routine_schema=DATABASE()"
                    + " AND routine_name=?",
                Long.class,
                MetricIndexMigrationTestSupport.FORWARD_PROCEDURE));
        // This helper was explicitly created by this fixture; the script itself must not overwrite
        // it.
      }
      database.clearOwnProcedures();
      assertEquals(before, database.indexFacts());
      assertEquals(data, database.dataFacts());
      database.evidence(
          "dependency",
          Map.of(
              "dependency",
              dependency.getKey(),
              "mysqlCode",
              failure.getErrorCode(),
              "sqlState",
              String.valueOf(failure.getSQLState()),
              "indexesBefore",
              before,
              "indexesAfter",
              database.indexFacts()));
    }
  }

  @Test
  void realSpringTransactionsKeepIdempotencyUnique1062AndProjectionFailureAtomic()
      throws Exception {
    var database = database("transactions");
    database.layout(database.configuration.phase.equals("LEGACY"));
    assertDoesNotThrow(database.mapper::verifySchema);
    ProxyFactory factory = new ProxyFactory(new ProductMetricService(database.mapper));
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(database.dataSource),
            new AnnotationTransactionAttributeSource()));
    ProductMetricService service = (ProductMetricService) factory.getProxy();
    ProductMetricEventDTO first = event(database, "first", 41);
    assertEquals(1, service.applyBatch(List.of(first)).appliedCount);
    Map<String, Object> committed = database.dataFacts();
    assertEquals(0, service.applyBatch(List.of(first)).appliedCount);
    assertEquals(committed, database.dataFacts());
    SQLException eventConflict;
    try (Connection connection = database.connect();
        Statement statement = connection.createStatement()) {
      eventConflict =
          assertThrows(
              SQLException.class,
              () ->
                  statement.executeUpdate(
                      "INSERT INTO product_metric_applied_event SELECT * FROM"
                          + " product_metric_applied_event WHERE event_id='"
                          + first.eventId
                          + "'"));
    }
    assertEquals(1062, eventConflict.getErrorCode());
    try (Connection connection = database.connect();
        Statement statement = connection.createStatement()) {
      statement.execute(
          "CREATE TRIGGER fixture_projection_failure BEFORE INSERT ON"
              + " product_metric_projection_outbox FOR EACH ROW BEGIN IF NEW.product_id=42 THEN"
              + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='CM21_FIXTURE_PROJECTION_FAILURE'; END"
              + " IF; END");
    }
    RuntimeException projectionFailure =
        assertThrows(
            RuntimeException.class,
            () ->
                service.applyBatch(
                    List.of(event(database, "prefix", 41), event(database, "failing", 42))));
    assertNotNull(sqlCause(projectionFailure));
    assertEquals(1644, sqlCause(projectionFailure).getErrorCode());
    assertEquals(committed, database.dataFacts());
    try (Connection connection = database.connect();
        Statement statement = connection.createStatement()) {
      statement.execute("DROP TRIGGER fixture_projection_failure");
    }
    // A future ledger version forces a real unique-version failure after aggregate mutation.
    String conflictingEvent = "f".repeat(64);
    database.jdbc.update(
        "INSERT INTO"
            + " product_metric_applied_event(event_id,fingerprint,event_type,product_id,sku_id,quantity,order_no,source_ref,source_revision,occurred_at,applied_at,metric_version)"
            + " VALUES(?,?,'PRODUCT_VIEW_CONFIRMED',41,NULL,1,NULL,'fixture-version',NULL,'2026-10-29"
            + " 10:00:00.123','2026-10-29 10:00:00.123',2)",
        conflictingEvent,
        "e".repeat(64));
    Map<String, Object> beforeVersionFailure = database.dataFacts();
    RuntimeException versionFailure =
        assertThrows(
            RuntimeException.class,
            () -> service.applyBatch(List.of(event(database, "version", 41))));
    assertNotNull(sqlCause(versionFailure));
    assertEquals(1062, sqlCause(versionFailure).getErrorCode());
    assertEquals(beforeVersionFailure, database.dataFacts());
    database.evidence(
        "transactions",
        Map.of(
            "duplicateAppliedCount",
            0,
            "eventMysqlCode",
            eventConflict.getErrorCode(),
            "versionMysqlCode",
            sqlCause(versionFailure).getErrorCode(),
            "projectionMysqlCode",
            sqlCause(projectionFailure).getErrorCode(),
            "afterFirstCommit",
            committed,
            "afterVersionFixtureAndFailure",
            database.dataFacts(),
            "transactionProxy",
            service.getClass().getName()));
  }

  @Test
  void mdlTimeoutReleasesOnlyOurBlockerAndFreshReadConfirmsOldState() throws Exception {
    var database = database("mdl_timeout");
    database.layout(true);
    List<Map<String, Object>> before = database.indexFacts();
    SQLException failure;
    long elapsed;
    try (Connection blocker = database.connect()) {
      blocker.setAutoCommit(false);
      try (Statement statement = blocker.createStatement();
          ResultSet rows =
              statement.executeQuery("SELECT event_id FROM product_metric_applied_event LIMIT 1")) {
        while (rows.next()) rows.getString(1);
      }
      long started = System.nanoTime();
      try {
        failure = assertThrows(SQLException.class, () -> database.migrate(true));
        elapsed = System.nanoTime() - started;
      } finally {
        blocker.rollback();
      }
    }
    assertSessionRestored(failure);
    assertEquals(1205, failure.getErrorCode());
    assertTrue(
        elapsed >= 8_000_000_000L && elapsed < 28_000_000_000L,
        "MDL wait must be bounded by reviewed session timeout, not a short client disconnect");
    database.clearOwnProcedures();
    assertEquals(before, database.indexFacts());
    List<Map<String, String>> forwardAudit = database.migrate(true);
    assertStatus(forwardAudit, "MIGRATED_TARGET", "OLD3", "NEW2");
    database.evidence(
        "mdl",
        Map.of(
            "mysqlCode",
            failure.getErrorCode(),
            "sqlState",
            String.valueOf(failure.getSQLState()),
            "elapsedNs",
            elapsed,
            "oldIndexesAfterFailure",
            before,
            "targetIndexesAfterExplicitRetry",
            database.indexFacts(),
            "releasedOnlyFixtureBlocker",
            true,
            "permissionNegativeCovered",
            false,
            "unsupportedAlgorithmNegativeCovered",
            false));
  }

  /**
   * Read opt-in configuration from normal Maven system properties before constructing a fixture.
   */
  private static MetricIndexMigrationTestSupport.Configuration configuration() {
    return MetricIndexMigrationTestSupport.configuration(System.getProperties(), Path.of("."));
  }

  /** Create an independent schema, never a historical database or a shared business fixture. */
  private static MetricIndexMigrationTestSupport.Database database(String caseName)
      throws Exception {
    return new MetricIndexMigrationTestSupport.Database(configuration(), caseName);
  }

  /**
   * Require exactly the reviewed migration result row rather than inferring success from no
   * exception.
   */
  private static void assertStatus(
      List<Map<String, String>> results, String status, String before, String after) {
    List<Map<String, String>> statuses =
        results.stream().filter(row -> row.containsKey("migration_status")).toList();
    assertEquals(1, statuses.size());
    assertEquals(status, statuses.get(0).get("migration_status"));
    assertEquals(before, statuses.get(0).get("state_before"));
    assertEquals(after, statuses.get(0).get("state_after"));
  }

  /** Return only exact named target indexes, ignoring cardinality estimates. */
  private static Set<String> indexNames(MetricIndexMigrationTestSupport.Database database) {
    Set<String> names = new TreeSet<>();
    for (Map<String, Object> index : database.indexFacts())
      names.add((String) index.get("INDEX_NAME"));
    return names;
  }

  /**
   * Failed-session verification must preserve the primary error and remain independently
   * successful.
   */
  private static void assertSessionRestored(SQLException failure) {
    assertEquals(
        0,
        failure.getSuppressed().length,
        "session state verification failed; primary JDBC error retained");
  }

  /** Find the original JDBC failure preserved through Spring's genuine exception translation. */
  private static SQLException sqlCause(Throwable failure) {
    for (Throwable current = failure; current != null; current = current.getCause())
      if (current instanceof SQLException) return (SQLException) current;
    return null;
  }

  /** Build deterministic canonical immutable events using the production wire contract. */
  private static ProductMetricEventDTO event(
      MetricIndexMigrationTestSupport.Database database, String label, long productId) {
    return ProductMetricEvents.product(
        UUID.nameUUIDFromBytes((database.name + ":" + label).getBytes(StandardCharsets.UTF_8))
            .toString(),
        productId,
        false,
        null);
  }

  /**
   * Seed nulls, binary bytes, Unicode, exact timestamps, JSON, leases and all metric table rows.
   */
  private static void seedTypedFacts(MetricIndexMigrationTestSupport.Database database)
      throws SQLException {
    try (Connection connection = database.connect();
        Statement statement = connection.createStatement()) {
      statement.executeUpdate(
          "INSERT INTO"
              + " product_online_stat(product_id,view_count,search_count,sold_count,hot_score,metric_version,last_event_id,created_at,updated_at)"
              + " VALUES(41,2,3,4,45,7,'"
              + "a".repeat(64)
              + "','2026-10-29 10:00:00.123','2026-10-29"
              + " 10:00:00.456'),(42,1,0,0,1,1,NULL,'2026-10-29 10:00:00.123','2026-10-29"
              + " 10:00:00.456')");
      statement.executeUpdate(
          "INSERT INTO product_metric_applied_event VALUES('"
              + "a".repeat(64)
              + "','"
              + "b".repeat(64)
              + "','PRODUCT_VIEW_CONFIRMED',41,71,2,'订单-β','typed-preservation',NULL,'2026-10-29"
              + " 09:00:00.123','2026-10-29 10:00:00.456',7),('"
              + "c".repeat(64)
              + "','"
              + "d".repeat(64)
              + "','PRODUCT_SEARCH_EXPOSED',42,NULL,1,NULL,'typed-null',0,'2026-10-29"
              + " 09:00:00.123','2026-10-29 10:00:00.456',1)");
      statement.executeUpdate(
          "INSERT INTO"
              + " product_metric_outbox(event_id,product_id,event_type,fingerprint,payload_json,status,attempts,next_retry_at,lease_owner,lease_until,last_error,created_at,updated_at,published_at)"
              + " VALUES('"
              + "d".repeat(64)
              + "',41,'PRODUCT_VIEW_CONFIRMED','"
              + "b".repeat(64)
              + "',CAST('{\"unicode\":\"事实\",\"nullable\":null}' AS JSON),'UNKNOWN',2,'2026-10-29"
              + " 10:00:01.123','fixture-owner','2026-10-29 10:00:30.123',NULL,'2026-10-29"
              + " 10:00:00.123','2026-10-29 10:00:00.456',NULL)");
      statement.executeUpdate(
          "INSERT INTO"
              + " product_metric_projection_outbox(product_id,metric_version,created_at,updated_at,status,attempts,next_retry_at,lease_owner,lease_until,last_error)"
              + " VALUES(41,7,'2026-10-29 10:00:00.123','2026-10-29"
              + " 10:00:00.456','UNKNOWN',2,'2026-10-29 10:00:01.123',NULL,NULL,'PENDING_RETRY')");
      statement.executeUpdate(
          "INSERT INTO"
              + " product_metric_quarantine(quarantine_id,queue_name,payload_sha256,payload_size,raw_prefix,raw_complete,event_id,reason,status,created_at)"
              + " VALUES('"
              + "e".repeat(64)
              + "','fixture.queue','"
              + "f".repeat(64)
              + "',3,X'007FFF',1,NULL,'BINARY_EVIDENCE','OPEN','2026-10-29 10:00:00.123')");
      statement.executeUpdate(
          "UPDATE product_metric_control SET"
              + " revision=8,index_uuid='fixture-index-identity',index_ready=1,updated_at='2026-10-29"
              + " 10:00:00.456' WHERE singleton_id=1");
    }
  }

  /**
   * Exercise both immutable event identity and product/version uniqueness after the forward change.
   */
  private static void assertUnique1062(MetricIndexMigrationTestSupport.Database database)
      throws SQLException {
    try (Connection connection = database.connect();
        Statement statement = connection.createStatement()) {
      SQLException event =
          assertThrows(
              SQLException.class,
              () ->
                  statement.executeUpdate(
                      "INSERT INTO product_metric_applied_event SELECT * FROM"
                          + " product_metric_applied_event WHERE event_id='"
                          + "a".repeat(64)
                          + "'"));
      assertEquals(1062, event.getErrorCode());
      SQLException version =
          assertThrows(
              SQLException.class,
              () ->
                  statement.executeUpdate(
                      "INSERT INTO product_metric_applied_event SELECT '"
                          + "1".repeat(64)
                          + "',fingerprint,event_type,product_id,sku_id,quantity,order_no,source_ref,source_revision,occurred_at,applied_at,metric_version"
                          + " FROM product_metric_applied_event WHERE event_id='"
                          + "a".repeat(64)
                          + "'"));
      assertEquals(1062, version.getErrorCode());
    }
  }
}
