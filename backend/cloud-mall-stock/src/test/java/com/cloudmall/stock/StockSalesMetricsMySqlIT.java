package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.mapper.*;
import com.cloudmall.stock.service.*;
import com.cloudmall.stock.service.impl.*;
import com.fasterxml.jackson.databind.*;
import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.sql.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.*;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.*;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Real original Stock transaction proxies prove source mutation and sale append commit or roll back
 * together.
 */
class StockSalesMetricsMySqlIT {
  /** Explicit owned resource provided only during this test's Spring refresh. */
  private static final ThreadLocal<OwnedDatabase> CURRENT = new ThreadLocal<>();

  /** Native full-row JSON writer retains null values and DATETIME components. */
  private static final ObjectMapper JSON = new ObjectMapper();

  @Test
  void normalConfirmationAndOutboxShareRealTransaction() throws Exception {
    try (OwnedDatabase db = new OwnedDatabase("stock", "normal-confirm")) {
      seedNormal(db, "202610normal-success", 71, 41, 3);
      seedNormal(db, "202610normal-failure", 72, 42, 2);
      try (ConfigurableApplicationContext context = start(db)) {
        context.getBean(StockSalesOutboxMapper.class).verifySchema();
        StockReservationServiceImpl service = context.getBean(StockReservationServiceImpl.class);
        assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(service));
        assertTrue(
            org.springframework.aop.support.AopUtils.isAopProxy(
                context.getBean(StockSalesOutboxService.class)));
        service.confirm("202610normal-success");
        assertEquals(3L, db.scalar("SELECT sold_quantity FROM stock_sku WHERE sku_id=71"));
        assertEquals(0L, db.scalar("SELECT reserved_quantity FROM stock_sku WHERE sku_id=71"));
        assertEquals(1L, db.scalar("SELECT version FROM stock_sku WHERE sku_id=71"));
        assertEquals(1L, db.count("stock_sales_outbox"));
        JsonNode event =
            JSON.readTree(
                db.jdbc.queryForObject(
                    "SELECT payload_json FROM stock_sales_outbox WHERE sku_id=71", String.class));
        assertEquals(3, event.path("quantity").asInt());
        assertEquals(41L, event.path("productId").asLong());
        assertEquals(1L, event.path("sourceRevision").asLong());
        assertEquals(
            sha("CM21:1:STOCK:CONFIRM:202610normal-success:71"), event.path("eventId").asText());
        assertEquals(
            "stock_flow:202610normal-success:CONFIRM:71", event.path("sourceRef").asText());
        Map<String, Object> settled = db.allFacts();
        service.confirm("202610normal-success");
        assertThrows(BizException.class, () -> service.rollback("202610normal-success"));
        assertEquals(settled, db.allFacts());
        for (String alias :
            List.of("202610NORMAL-success", "202610nórmal-success", "202610normal-success "))
          assertEquals(
              409, assertThrows(BizException.class, () -> service.confirm(alias)).getHttpStatus());
        assertEquals(settled, db.allFacts());
        Map<String, Object> before = db.allFacts();
        db.trigger("cm21_reject_normal", "stock_sales_outbox", "CM21_NORMAL_OUTBOX_REJECTED");
        BizException failure =
            assertThrows(BizException.class, () -> service.confirm("202610normal-failure"));
        assertEquals("STOCK_SALES_EVENT_UNAVAILABLE", failure.getCode());
        assertEquals(503, failure.getHttpStatus());
        assertNotNull(failure.getCause());
        assertSignal(failure, "CM21_NORMAL_OUTBOX_REJECTED");
        db.dropTrigger("cm21_reject_normal");
        assertEquals(before, db.allFacts());
        service.confirm("202610normal-failure");
        assertEquals(2L, db.count("stock_sales_outbox"));
        assertEquals(
            sha("CM21:1:STOCK:CONFIRM:202610normal-failure:72"),
            db.jdbc.queryForObject(
                "SELECT event_id FROM stock_sales_outbox WHERE sku_id=72", String.class));
        assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
        assertNull(io.seata.core.context.RootContext.getXID());
        db.audit(
            "normal-outcome",
            Map.of(
                "successEvent", event, "failureCode", failure.getCode(), "facts", db.allFacts()));
      }
      db.success();
    }
  }

  @Test
  void seckillConfirmationAndOutboxShareRealTransaction() throws Exception {
    try (OwnedDatabase db = new OwnedDatabase("stock", "seckill-confirm")) {
      seedSeckill(db, "202610seckill-success", 71, 41, 9);
      seedSeckill(db, "202610seckill-failure", 72, 42, 10);
      try (ConfigurableApplicationContext context = start(db)) {
        StockReservationServiceImpl service = context.getBean(StockReservationServiceImpl.class);
        assertTrue(
            org.springframework.aop.support.AopUtils.isAopProxy(
                context.getBean(SeckillQuotaSettlementService.class)));
        service.confirm("202610seckill-success");
        assertEquals(
            "CONFIRMED",
            db.jdbc.queryForObject(
                "SELECT status FROM seckill_quota_settlement WHERE"
                    + " order_no='202610seckill-success'",
                String.class));
        assertEquals(
            2L,
            db.scalar(
                "SELECT revision FROM seckill_quota_settlement WHERE"
                    + " order_no='202610seckill-success'"));
        assertEquals(0L, db.count("stock_flow"));
        JsonNode event =
            JSON.readTree(
                db.jdbc.queryForObject(
                    "SELECT payload_json FROM stock_sales_outbox WHERE sku_id=71", String.class));
        assertEquals(1, event.path("quantity").asInt());
        assertEquals(2L, event.path("sourceRevision").asLong());
        assertEquals(
            "seckill_quota_settlement:202610seckill-success", event.path("sourceRef").asText());
        assertEquals(
            sha("CM21:1:STOCK:CONFIRM:202610seckill-success:71"), event.path("eventId").asText());
        Map<String, Object> settled = db.allFacts();
        service.confirm("202610seckill-success");
        assertThrows(BizException.class, () -> service.rollback("202610seckill-success"));
        assertEquals(settled, db.allFacts());
        Map<String, Object> before = db.allFacts();
        db.trigger("cm21_reject_seckill", "stock_sales_outbox", "CM21_SECKILL_OUTBOX_REJECTED");
        BizException rejected =
            assertThrows(BizException.class, () -> service.confirm("202610seckill-failure"));
        assertEquals(503, rejected.getHttpStatus());
        assertSignal(rejected, "CM21_SECKILL_OUTBOX_REJECTED");
        db.dropTrigger("cm21_reject_seckill");
        assertEquals(before, db.allFacts());
        assertEquals(
            "OPEN",
            db.jdbc.queryForObject(
                "SELECT status FROM seckill_quota_settlement WHERE"
                    + " order_no='202610seckill-failure'",
                String.class));
        assertEquals(
            1L,
            db.scalar(
                "SELECT revision FROM seckill_quota_settlement WHERE"
                    + " order_no='202610seckill-failure'"));
        service.confirm("202610seckill-failure");
        assertEquals(2L, db.count("stock_sales_outbox"));
        assertEquals(
            0L, db.scalar("SELECT SUM(sold_quantity+reserved_quantity+version) FROM stock_sku"));
        assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
        db.audit("seckill-outcome", Map.of("event", event, "facts", db.allFacts()));
      }
      db.success();
    }
  }

  private static void seedNormal(
      OwnedDatabase db, String order, long sku, long product, int quantity) {
    db.jdbc.update(
        "INSERT INTO"
            + " stock_sku(sku_id,product_id,total_quantity,available_quantity,reserved_quantity,sold_quantity,version,updated_at)"
            + " VALUES(?,?,20,?, ?,0,0,'2026-10-16 10:00:00.000')",
        sku,
        product,
        20 - quantity,
        quantity);
    db.jdbc.update(
        "INSERT INTO"
            + " stock_reservation_request(id,order_no,scene,items_snapshot,items_hash,status,created_at,updated_at)"
            + " VALUES(?,?,'NORMAL',CAST(? AS JSON),?,'RESERVED','2026-10-16"
            + " 10:00:00.000','2026-10-16 10:00:00.000')",
        sku,
        order,
        "[{\"skuId\":" + sku + ",\"quantity\":" + quantity + "}]",
        "a".repeat(64));
    db.jdbc.update(
        "INSERT INTO stock_flow(id,sku_id,order_no,flow_type,quantity,idempotency_key,created_at)"
            + " VALUES(?,?,?,'RESERVE',?,?,'2026-10-16 10:00:00.000')",
        sku,
        sku,
        order,
        quantity,
        order + ":RESERVE:" + sku);
  }

  private static void seedSeckill(
      OwnedDatabase db, String order, long sku, long product, long user) {
    db.jdbc.update(
        "INSERT INTO"
            + " stock_sku(sku_id,product_id,total_quantity,available_quantity,reserved_quantity,sold_quantity,version,updated_at)"
            + " VALUES(?,?,20,20,0,0,0,'2026-10-16 10:00:00.000')",
        sku,
        product);
    db.jdbc.update(
        "INSERT INTO"
            + " seckill_reservation(id,activity_id,sku_id,user_id,order_no,idempotency_key,request_hash,request_payload,status,created_at,updated_at)"
            + " VALUES(?,3,?,?,?,?,?,CAST(? AS JSON),'ACCEPTED','2026-10-16"
            + " 10:00:00.000','2026-10-16 10:00:00.000')",
        sku,
        sku,
        user,
        order,
        "cm21-accepted-" + user,
        "b".repeat(64),
        "{\"activityId\":3,\"skuId\":"
            + sku
            + ",\"userId\":"
            + user
            + ",\"idempotencyKey\":\"cm21-accepted-"
            + user
            + "\"}");
  }

  private static ConfigurableApplicationContext start(OwnedDatabase db) {
    CURRENT.set(db);
    try {
      ConfigurableApplicationContext context =
          new SpringApplicationBuilder(SqlApplication.class)
              .web(WebApplicationType.NONE)
              .profiles("cm21-stock-mysql-it")
              .run(
                  "--spring.main.banner-mode=off",
                  "--logging.level.root=WARN",
                  "--spring.cloud.nacos.discovery.enabled=false",
                  "--spring.cloud.nacos.config.enabled=false",
                  "--seata.enabled=false",
                  "--seata.enable-auto-data-source-proxy=false",
                  "--cloudmall.stock.sales-metrics.enabled=true");
      db.contexts.add(context);
      return context;
    } finally {
      CURRENT.remove();
    }
  }

  private static String sha(String value) throws Exception {
    return HexFormat.of()
        .formatHex(
            java.security.MessageDigest.getInstance("SHA-256")
                .digest(value.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
  }

  /**
   * Component fixture wires original services and transaction annotations without starting external
   * workers.
   */
  @SpringBootConfiguration
  @EnableAutoConfiguration(
      excludeName = {
        "org.springframework.boot.autoconfigure.amqp.RabbitAutoConfiguration",
        "org.springframework.boot.autoconfigure.data.redis.RedisAutoConfiguration"
      })
  @Profile("cm21-stock-mysql-it")
  @MapperScan("com.cloudmall.stock.mapper")
  @Import({
    StockReservationServiceImpl.class,
    StockReservationReader.class,
    StockReservationSnapshotCodec.class,
    StockSqlMapper.class,
    StockSalesOutboxMapper.class,
    StockSalesOutboxService.class,
    StockReservationRequestService.class,
    SeckillAcceptanceService.class,
    SeckillRequestClaimService.class,
    SeckillQuotaSettlementService.class
  })
  public static class SqlApplication {
    /** One exact owned resource is shared by JDBC/MyBatis/transaction manager. */
    @Bean
    public DataSource dataSource() {
      return Objects.requireNonNull(CURRENT.get()).dataSource;
    }

    /**
     * No Redis quantity path is exercised by MySQL confirmation; Pipeline uses an actual owned
     * server.
     */
    @Bean
    public StringRedisTemplate redis() {
      return org.mockito.Mockito.mock(StringRedisTemplate.class);
    }

    /**
     * Source confirmation never publishes directly; Pipeline uses the actual dispatcher and broker.
     */
    @Bean
    public RabbitTemplate rabbit() {
      return org.mockito.Mockito.mock(RabbitTemplate.class);
    }
  }

  /** Stock-local exact ownership and full-facts helper; no cross-module fixture dependency. */
  static final class OwnedDatabase implements AutoCloseable {
    /** Immutable source catalog is only read; all test writes target the UUID catalog. */
    final String sourceName;

    /** Exact newly-created owner identity. */
    final String database;

    /** Every evidence file is CREATE_NEW beneath this standard target run. */
    final Path evidence;

    /** Same physical resource used by original business beans. */
    final OwnedDataSource dataSource;

    /** Owned JDBC; SQL here is fixture setup/assertion only. */
    final JdbcTemplate jdbc;

    /** Immutable source DDL/rows/checksums/index facts held for final exact comparison. */
    final Map<String, Object> sourceBefore;

    /** Original structural table inventory. */
    final List<String> originalTables;

    /** Explicitly declared trigger names, never wildcard-drop cleanup. */
    final Set<String> triggers = new TreeSet<>();

    /** Cleanup is authorized only after caller assertions and all close/ownership gates pass. */
    private boolean passed;

    /** Each created Spring context must be closed before facts qualify for cleanup. */
    final List<ConfigurableApplicationContext> contexts = new ArrayList<>();

    /** Exact owned loopback HTTP listeners must refuse connections after context close. */
    final Set<Integer> ownedPorts = new TreeSet<>();

    /** Administrator URL contains no credentials and is never emitted into the audit. */
    private final String base;

    OwnedDatabase(String role, String scenario) throws Exception {
      if (!"stock".equals(role)) throw new IllegalStateException("SOURCE_ROLE_NOT_ADMITTED");
      base = System.getProperty("cloudmall.cm21.mysql.base-url");
      if (base == null || !base.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/"))
        throw new IllegalStateException("CM21_EXPLICIT_LOOPBACK_MYSQL_BASE_REQUIRED");
      sourceName = "cloudmall_" + role;
      database = "cm21_" + UUID.randomUUID().toString().replace("-", "") + "_" + role;
      evidence =
          Path.of(
              "target",
              "cm21-online-metrics",
              scenario,
              System.currentTimeMillis() + "-" + UUID.randomUUID());
      Files.createDirectories(evidence);
      try (Connection source = connection(sourceName)) {
        source.setReadOnly(true);
        originalTables = tables(source);
        if (!originalTables.equals(
            List.of(
                "seata_cm18_stock_demo",
                "seckill_order_outbox",
                "seckill_quota_settlement",
                "seckill_reservation",
                "stock_cache_repair",
                "stock_consistency_audit_state",
                "stock_flow",
                "stock_reservation_request",
                "stock_sku",
                "undo_log"))) throw new IllegalStateException("SOURCE_TABLE_WHITELIST_MISMATCH");
        sourceBefore = facts(source, originalTables);
      }
      audit("source-before", sourceBefore);
      audit(
          "ownership-plan",
          Map.of(
              "catalog",
              database,
              "source",
              sourceName,
              "originalTables",
              originalTables,
              "role",
              role));
      try (Connection admin = connection(sourceName);
          Statement statement = admin.createStatement()) {
        statement.execute(
            "CREATE DATABASE `" + database + "` CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
        for (String table : originalTables)
          statement.execute(
              "CREATE TABLE `"
                  + database
                  + "`.`"
                  + table
                  + "` LIKE `"
                  + sourceName
                  + "`.`"
                  + table
                  + "`");
      }
      dataSource = new OwnedDataSource(base, database);
      jdbc = new JdbcTemplate(dataSource);
      migrate();
      audit("created-facts", allFacts());
    }

    /** Execute only the reviewed idempotent new-table migration on this owned catalog. */
    void migrate() throws Exception {
      String role =
          sourceName.endsWith("product") ? "product_online_metrics" : "stock_sales_outbox";
      Path script = Path.of("..", "database", "cm21_" + role + ".sql");
      if (!Files.isRegularFile(script))
        script = Path.of("backend", "database", "cm21_" + role + ".sql");
      String text = Files.readString(script);
      text = text.replaceAll("(?m)^--.*$", "");
      try (Connection connection = dataSource.getConnection();
          Statement statement = connection.createStatement()) {
        for (String sql : text.split(";")) if (!sql.isBlank()) statement.execute(sql);
      }
    }

    /** Install one declared genuine MySQL SIGNAL fault after recording its exact definition. */
    void trigger(String name, String table, String marker) throws Exception {
      if (!name.matches("cm21_[a-z_]+")
          || !originalTables.contains(table)
              && !table.startsWith("product_metric_")
              && !table.equals("stock_sales_outbox"))
        throw new IllegalArgumentException("TRIGGER_PLAN");
      String ddl =
          "CREATE TRIGGER `"
              + name
              + "` BEFORE INSERT ON `"
              + table
              + "` FOR EACH ROW SIGNAL SQLSTATE '45000' SET MYSQL_ERRNO=1644,MESSAGE_TEXT='"
              + marker
              + "'";
      audit("trigger-plan-" + name, Map.of("name", name, "table", table, "ddl", ddl));
      jdbc.execute(ddl);
      triggers.add(name);
    }

    /**
     * Remove only the exact trigger declared by this fixture after collecting its SHOW CREATE fact.
     */
    void dropTrigger(String name) throws Exception {
      if (!triggers.contains(name)) throw new IllegalStateException("TRIGGER_NOT_OWNED");
      audit("trigger-before-drop-" + name, jdbc.queryForList("SHOW CREATE TRIGGER `" + name + "`"));
      jdbc.execute("DROP TRIGGER `" + name + "`");
      triggers.remove(name);
    }

    /** Complete all business assertions before enabling exact cleanup. */
    void success() {
      passed = true;
    }

    /** Read a fixture scalar without manufacturing a business transition. */
    long scalar(String sql) {
      return Objects.requireNonNull(jdbc.queryForObject(sql, Long.class));
    }

    /** Count an admitted actual table. */
    long count(String table) {
      if (!table.matches("[a-z_]+")) throw new IllegalArgumentException();
      return scalar("SELECT COUNT(*) FROM `" + table + "`");
    }

    /**
     * Full current facts include DDL, indexes, count, checksum and all ordered string components.
     */
    Map<String, Object> allFacts() throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        return facts(connection, tables(connection));
      }
    }

    /** Full facts for one admitted business table. */
    Map<String, Object> tableFacts(String table) throws Exception {
      try (Connection connection = dataSource.getConnection()) {
        return facts(connection, List.of(table));
      }
    }

    /** Write immutable native JSON without exposing connection strings or credentials. */
    void audit(String name, Object value) throws Exception {
      Files.write(
          evidence.resolve(name + ".json"),
          JSON.writeValueAsBytes(value),
          StandardOpenOption.CREATE_NEW);
    }

    /**
     * Physical connections always select and verify the exact expected catalog and +08:00 session.
     */
    private Connection connection(String catalog) throws SQLException {
      Connection connection =
          DriverManager.getConnection(
              base
                  + catalog
                  + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai&connectTimeout=3000&socketTimeout=5000",
              "root",
              "root");
      try (Statement statement = connection.createStatement()) {
        statement.execute("SET time_zone='+08:00'");
      }
      if (!catalog.equals(connection.getCatalog()) || !catalog.equals(actualCatalog(connection))) {
        connection.close();
        throw new SQLException("OWNED_CATALOG_MISMATCH");
      }
      return connection;
    }

    @Override
    public void close() throws Exception {
      List<String> failures = new ArrayList<>();
      try {
        audit("owned-final", allFacts());
        try (Connection source = connection(sourceName)) {
          source.setReadOnly(true);
          Map<String, Object> after = facts(source, tables(source));
          audit("source-after", after);
          if (!sourceBefore.equals(after)) failures.add("SOURCE_CHANGED");
        }
        if (dataSource.open.get() != 0) failures.add("CONNECTIONS_OPEN");
        if (contexts.stream().anyMatch(ConfigurableApplicationContext::isActive))
          failures.add("CONTEXT_ACTIVE");
        for (int port : ownedPorts) {
          try (java.net.Socket socket = new java.net.Socket()) {
            socket.connect(new java.net.InetSocketAddress("127.0.0.1", port), 500);
            failures.add("HTTP_LISTENER_ACTIVE");
          } catch (java.net.ConnectException stopped) {
            /* exact listener refused */
          }
        }
        if (TransactionSynchronizationManager.isActualTransactionActive()
            || TransactionSynchronizationManager.isSynchronizationActive()
            || AuthContext.userId() != null
            || io.seata.core.context.RootContext.getXID() != null)
          failures.add("THREAD_CONTEXT_OPEN");
        if (!triggers.isEmpty()) failures.add("TRIGGERS_REMAIN");
        try (Connection connection = dataSource.getConnection()) {
          assertObjects(connection);
        }
        if (!passed) failures.add("ASSERTIONS_NOT_COMPLETE");
      } catch (Exception failure) {
        failures.add("FINAL_FACTS_OR_OBJECTS_UNKNOWN");
      }
      audit(
          "cleanup-decision", Map.of("catalog", database, "passed", passed, "failures", failures));
      if (!failures.isEmpty())
        throw new IllegalStateException("CM21_PRESERVED_" + String.join("_", failures));
      try (Connection admin = connection(sourceName);
          Statement statement = admin.createStatement()) {
        if (!database.matches("cm21_[0-9a-f]{32}_(product|stock)"))
          throw new IllegalStateException("OWNER_INVALID");
        statement.execute("DROP DATABASE `" + database + "`");
      }
      audit("cleanup-complete", Map.of("catalog", database, "dropped", true));
    }

    private void assertObjects(Connection connection) throws SQLException {
      try (Statement statement = connection.createStatement();
          ResultSet result =
              statement.executeQuery(
                  "SELECT (SELECT COUNT(*) FROM information_schema.views WHERE"
                      + " table_schema=DATABASE())+(SELECT COUNT(*) FROM"
                      + " information_schema.triggers WHERE trigger_schema=DATABASE())+(SELECT"
                      + " COUNT(*) FROM information_schema.routines WHERE"
                      + " routine_schema=DATABASE())+(SELECT COUNT(*) FROM"
                      + " information_schema.events WHERE event_schema=DATABASE())")) {
        result.next();
        if (result.getLong(1) != 0) throw new SQLException("EXTRA_OBJECTS");
      }
      List<String> observed = tables(connection);
      Set<String> expected = new TreeSet<>(originalTables);
      if (sourceName.endsWith("product"))
        expected.addAll(
            List.of(
                "product_metric_control",
                "product_online_stat",
                "product_metric_applied_event",
                "product_metric_outbox",
                "product_metric_projection_outbox",
                "product_metric_quarantine"));
      else expected.add("stock_sales_outbox");
      if (!expected.equals(new TreeSet<>(observed)))
        throw new SQLException("TABLE_INVENTORY_CHANGED");
    }

    private static List<String> tables(Connection connection) throws SQLException {
      List<String> tables = new ArrayList<>();
      try (Statement statement = connection.createStatement();
          ResultSet rows =
              statement.executeQuery(
                  "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE()"
                      + " AND table_type='BASE TABLE' ORDER BY table_name")) {
        while (rows.next()) {
          String table = rows.getString(1);
          if (!table.matches("[a-z0-9_]+")) throw new SQLException("TABLE_NAME_INVALID");
          tables.add(table);
        }
      }
      return tables;
    }

    private static Map<String, Object> facts(Connection connection, List<String> tables)
        throws SQLException {
      Map<String, Object> result = new TreeMap<>();
      for (String table : tables) {
        Map<String, Object> fact = new LinkedHashMap<>();
        try (Statement statement = connection.createStatement();
            ResultSet ddl = statement.executeQuery("SHOW CREATE TABLE `" + table + "`")) {
          ddl.next();
          fact.put("ddl", ddl.getString(2));
        }
        try (Statement statement = connection.createStatement();
            ResultSet indexes = statement.executeQuery("SHOW INDEX FROM `" + table + "`")) {
          fact.put("indexes", cells(indexes));
        }
        try (Statement statement = connection.createStatement();
            ResultSet checksum =
                statement.executeQuery("CHECKSUM TABLE `" + table + "` EXTENDED")) {
          checksum.next();
          fact.put("checksum", checksum.getString(2));
        }
        List<List<String>> rows;
        try (Statement statement = connection.createStatement();
            ResultSet source = statement.executeQuery("SELECT * FROM `" + table + "`")) {
          rows = cells(source);
        }
        rows.sort(
            Comparator.comparing(
                row -> {
                  try {
                    return JSON.writeValueAsString(row);
                  } catch (Exception failure) {
                    throw new IllegalStateException(failure);
                  }
                }));
        fact.put("count", rows.size());
        fact.put("rows", rows);
        result.put(table, fact);
      }
      return result;
    }

    private static List<List<String>> cells(ResultSet result) throws SQLException {
      List<List<String>> rows = new ArrayList<>();
      int count = result.getMetaData().getColumnCount();
      while (result.next()) {
        List<String> row = new ArrayList<>();
        for (int column = 1; column <= count; column++) {
          int type = result.getMetaData().getColumnType(column);
          byte[] bytes;
          if (type == Types.BLOB
              || type == Types.BINARY
              || type == Types.VARBINARY
              || type == Types.LONGVARBINARY) {
            bytes = result.getBytes(column);
            row.add(bytes == null ? null : Base64.getEncoder().encodeToString(bytes));
          } else row.add(result.getString(column));
        }
        rows.add(row);
      }
      return rows;
    }
  }

  /**
   * Raw DriverManager avoids Spring's DEBUG URL logger; every acquired owned connection is counted.
   */
  static final class OwnedDataSource extends AbstractDataSource {
    /** Explicit loopback base is private and never logged. */
    private final String base;

    /** Exact allowed physical catalog. */
    final String catalog;

    /** Independent connection close gate. */
    final AtomicInteger open = new AtomicInteger();

    OwnedDataSource(String base, String catalog) {
      this.base = base;
      this.catalog = catalog;
    }

    @Override
    public Connection getConnection() throws SQLException {
      return getConnection("root", "root");
    }

    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      if (!"root".equals(username) || !"root".equals(password))
        throw new SQLException("LOCAL_CREDENTIAL_CONTRACT");
      Connection raw =
          DriverManager.getConnection(
              base
                  + catalog
                  + "?useSSL=false&allowPublicKeyRetrieval=true&serverTimezone=Asia%2FShanghai&connectTimeout=3000&socketTimeout=5000",
              username,
              password);
      if (!catalog.equals(raw.getCatalog()) || !catalog.equals(actualCatalog(raw))) {
        raw.close();
        throw new SQLException("OWNED_CATALOG_MISMATCH");
      }
      try (Statement statement = raw.createStatement()) {
        statement.execute("SET time_zone='+08:00'");
      }
      open.incrementAndGet();
      boolean[] closed = {false};
      return (Connection)
          java.lang.reflect.Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class[] {Connection.class},
              (proxy, method, args) -> {
                try {
                  if (method.getName().equals("close")) {
                    if (!closed[0]) {
                      raw.close();
                      closed[0] = true;
                      open.decrementAndGet();
                    }
                    return null;
                  }
                  return method.invoke(raw, args);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }
  }

  private static String actualCatalog(Connection connection) throws SQLException {
    try (Statement statement = connection.createStatement();
        ResultSet result = statement.executeQuery("SELECT DATABASE()")) {
      if (!result.next()) throw new SQLException("CURRENT_CATALOG_MISSING");
      return result.getString(1);
    }
  }

  private static void assertSignal(Throwable failure, String marker) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause instanceof SQLException sql
          && "45000".equals(sql.getSQLState())
          && sql.getErrorCode() == 1644
          && sql.getMessage().contains(marker)) return;
    fail("Expected original MySQL SIGNAL proof: " + marker);
  }
}
