package com.cloudmall.order.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.controller.OrderController;
import com.cloudmall.order.controller.OrderQueryController;
import com.cloudmall.order.domain.dto.*;
import com.cloudmall.order.domain.po.*;
import com.cloudmall.order.domain.vo.*;
import com.cloudmall.order.feign.*;
import com.cloudmall.order.mapper.*;
import com.cloudmall.order.service.impl.*;
import com.cloudmall.order.service.impl.OrderQueryServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.seata.core.context.RootContext;
import java.lang.reflect.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.sql.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.aopalliance.intercept.MethodInterceptor;
import org.junit.jupiter.api.*;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.autoconfigure.jdbc.*;
import org.springframework.boot.autoconfigure.transaction.TransactionAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.*;
import org.springframework.context.annotation.*;
import org.springframework.context.event.ContextRefreshedEvent;
import org.springframework.core.Ordered;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;
import org.springframework.scheduling.support.ScheduledMethodRunnable;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/**
 * Explicit production Bean assembly and two cold publication generations on exact owned catalogs.
 */
class OrderShardingRuntimeMySqlIT {
  /** Real source structures are read only; their rows are never cloned. */
  private static final List<String> FOUNDATION =
      List.of(
          "mall_order_template",
          "mall_order_item_template",
          "order_status_log_template",
          "order_idempotency");

  /** Original non-sharded tables actually created by the reliability initializer. */
  private static final List<String> LEDGERS =
      List.of(
          "order_idempotency",
          "order_reservation_intent",
          "order_outbox",
          "order_stock_action",
          "seckill_order_dedup",
          "seckill_order_dedup_conflict",
          "seckill_message_recovery");

  /** Actual three monthly families; status logs remain unused and empty. */
  private static final List<String> FAMILIES =
      List.of("mall_order", "mall_order_item", "order_status_log");

  /** Fixed local-only event/address facts, with no external service data. */
  private static final Map<String, Object> ADDRESS = Map.of("id", 9L, "receiver", "Fixture");

  /** One known SKU is sufficient to exercise normal and seckill main/item routing. */
  private static final ProductClient.SkuView PRODUCT =
      new ProductClient.SkuView(
          7L, 70L, "Fixture product", "SKU-7", new BigDecimal("8.25"), Map.of("size", "M"));

  /** Safe JSON output uses standard target and CREATE_NEW for every file. */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** Stored local components, independent of JDBC's Timestamp calendar conversion. */
  private static final DateTimeFormatter MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

  /** Unique per-class native run root, also printed with a safe correlation token. */
  private static Path auditRoot;

  /** Source URL is never written to native files or logs. */
  private static String sourceUrl;

  /** Source connection ownership accounting. */
  private static GuardedDataSource sourceDataSource;

  /** Read-only source facts and this class's exact CREATE/DROP entry. */
  private static JdbcTemplate source;

  /** Full source DDL/index/count/checksum fingerprint. */
  private static Map<String, Object> sourceBefore;

  /** Only catalogs whose CREATE returned successfully in this class. */
  private static final Set<String> CREATED = new TreeSet<>();

  /** Only catalogs whose absence was verified after exact DROP. */
  private static final Set<String> DELETED = new TreeSet<>();

  /**
   * Scoped Hikari config logging level prevents connection URL diagnostics and is restored after
   * this class.
   */
  private static ch.qos.logback.classic.Level originalHikariLevel;

  /** Machine Shanghai month sampled at class setup, distinct from simulated publication months. */
  private static YearMonth machineMonth;

  @BeforeAll
  static void sourceBefore() throws Exception {
    assertClean();
    ch.qos.logback.classic.Logger hikariLogger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari.HikariConfig");
    originalHikariLevel = hikariLogger.getLevel();
    hikariLogger.setLevel(ch.qos.logback.classic.Level.INFO);
    machineMonth = YearMonth.now(ZoneId.of("Asia/Shanghai"));
    String run = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
    auditRoot = Path.of("target", "cm17ej-runtime-publication", run).toAbsolutePath();
    Files.createDirectories(auditRoot.getParent());
    Files.createDirectory(auditRoot);
    write(
        auditRoot.resolve("class-start.json"),
        Map.of(
            "review",
            "17EJ",
            "runId",
            run,
            "startedAt",
            Instant.now().toString(),
            "machineShanghaiMonth",
            machineMonth.toString(),
            "actualMethods",
            3));
    System.out.println("CM17EJ_RUNTIME runId=" + run + " startedAt=" + Instant.now());
    sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/?#]+/cloudmall_order(\\?[^#]*)?"))
      throw new IllegalStateException("CM17EJ_SOURCE_REQUIRED");
    sourceDataSource = new GuardedDataSource(boundedUrl(sourceUrl), "cloudmall_order");
    source = jdbc(sourceDataSource);
    sourceBefore = sourceFingerprint();
    write(auditRoot.resolve("source-before.json"), sourceBefore);
    for (String table : FOUNDATION) assertEquals("BASE TABLE", tableTypes(source).get(table));
  }

  @AfterAll
  static void sourceAfter() throws Exception {
    List<Throwable> errors = new ArrayList<>();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("created", CREATED);
    result.put("deleted", DELETED);
    attempt(
        errors,
        () -> {
          Map<String, Object> after = sourceFingerprint();
          result.put("sourceAfter", after);
          assertEquals(sourceBefore, after);
          sourceDataSource.assertClosedConnections();
          assertClean();
          assertEquals(CREATED, DELETED);
        });
    result.put("errors", safeErrors(errors));
    result.put("preserveRequired", !CREATED.equals(DELETED));
    attempt(errors, () -> write(auditRoot.resolve("class-finish.json"), result));
    attempt(
        errors,
        () ->
            ((ch.qos.logback.classic.Logger)
                    org.slf4j.LoggerFactory.getLogger("com.zaxxer.hikari.HikariConfig"))
                .setLevel(originalHikariLevel));
    throwIfErrors(errors);
  }

  @Test
  void explicitModeUsesProductionMappersAfterBootstrapAndAudit() throws Exception {
    try (Fixture fixture =
        new Fixture("explicitModeUsesProductionMappersAfterBootstrapAndAudit", "NORMAL")) {
      fixture.prepare();
      Runtime runtime = fixture.open("sharding-monthly", null, false);
      fixture.verifyNormalTables(OrderTableInitializer.rollingMonths(machineMonth));
      runtime.assertGraph(true);
      runtime.awaitEmptyScans();
      fixture.exercise(runtime);
      fixture.accept();
    }
  }

  @Test
  void invalidAssemblyAndHistoricalInventoryNeverOpenBusiness() throws Exception {
    try (Fixture conflict =
        new Fixture(
            "invalidAssemblyAndHistoricalInventoryNeverOpenBusiness-conflict", "CONFLICT")) {
      conflict.prepare();
      Facts initial = conflict.facts();
      RuntimeException failure =
          assertThrows(RuntimeException.class, () -> conflict.open("sharding-monthly", null, true));
      assertTrue(causeText(failure).contains("ORDER_ROUTING_UNSUPPORTED"));
      assertEquals(initial, conflict.facts());
      conflict.assertRejected();
      conflict.accept();
    }
    try (Fixture gap =
        new Fixture("invalidAssemblyAndHistoricalInventoryNeverOpenBusiness-gap", "HISTORY_GAP")) {
      gap.prepare();
      YearMonth history = machineMonth.minusMonths(3);
      gap.addMonths(List.of(month(history)), Set.of("mall_order", "mall_order_item"));
      gap.allowedMonths.addAll(OrderTableInitializer.rollingMonths(machineMonth));
      gap.allowedReliability = true;
      RuntimeException failure =
          assertThrows(RuntimeException.class, () -> gap.open("sharding-monthly", null, false));
      assertTrue(causeText(failure).contains("ORDER_SHARD_INVENTORY_INVALID"));
      gap.assertRejected();
      gap.verifyInventory();
      gap.assertOnlyInitialRows();
      gap.accept();
    }
    try (Fixture bad =
        new Fixture(
            "invalidAssemblyAndHistoricalInventoryNeverOpenBusiness-admission", "BAD_MONTH")) {
      bad.prepare();
      String suffix = month(machineMonth);
      bad.addMonths(List.of(suffix), Set.of("mall_order"));
      LocalDateTime wrong = machineMonth.minusMonths(1).atDay(16).atTime(9, 10, 11, 123000000);
      OrderPO row = orderPO(month(machineMonth) + "BadMonth", 501L, wrong);
      new OrderSqlMapper(bad.sql).insertOrder("mall_order_" + suffix, row);
      bad.initialRows = bad.rows();
      bad.allowedMonths.addAll(OrderTableInitializer.rollingMonths(machineMonth));
      bad.allowedReliability = true;
      RuntimeException failure =
          assertThrows(RuntimeException.class, () -> bad.open("sharding-monthly", null, false));
      assertTrue(causeText(failure).contains("ORDER_SHARD_ROW_ADMISSION_FAILED"));
      bad.assertRejected();
      bad.verifyInventory();
      bad.assertOnlyInitialRows();
      bad.accept();
    }
  }

  @Test
  void restartPublishesNewNodesAndDynamicRollbackPreservesRows() throws Exception {
    try (Fixture fixture =
        new Fixture("restartPublishesNewNodesAndDynamicRollbackPreservesRows", "PUBLICATION")) {
      fixture.prepare();
      YearMonth december = YearMonth.of(2026, 12), january = december.plusMonths(1);
      Runtime old = fixture.open("sharding-monthly", december, false);
      old.assertGraph(true);
      old.awaitEmptyScans();
      fixture.verifyNormalTables(OrderTableInitializer.rollingMonths(december));
      fixture.seedPublication(old, december);
      Map<String, List<Map<String, Object>>> original = fixture.rows();
      Map<String, Object> oldInfo = old.info();
      old.context.getBean(OrderShardingBootstrap.class).prepareForMonth(january);
      fixture.allowedMonths.addAll(OrderTableInitializer.rollingMonths(january));
      Map<String, Object> prepared = old.info();
      assertEquals(oldInfo.get("publishedMonths"), prepared.get("publishedMonths"));
      assertEquals(oldInfo.get("snapshotSha256"), prepared.get("snapshotSha256"));
      assertEquals(true, prepared.get("requiresRestart"));
      assertTrue(((List<?>) prepared.get("preparedMonths")).contains("202703"));
      int calls = old.probe.sql.size();
      assertThrows(
          org.springframework.dao.DataAccessResourceFailureException.class,
          () -> old.orders.findOrder("mall_order_202703", "202703Unpublished"));
      assertEquals(calls, old.probe.sql.size());
      fixture.assertPriorRows(original);
      old.close();
      Runtime next = fixture.open("sharding-monthly", january, false);
      next.assertGraph(true);
      next.awaitEmptyScans();
      assertEquals(prepared.get("preparedMonths"), next.info().get("publishedMonths"));
      assertEquals(false, next.info().get("requiresRestart"));
      fixture.assertPriorRows(original);
      fixture.insertPublished(next, YearMonth.of(2027, 3));
      Map<String, List<Map<String, Object>>> beforeRollback = fixture.rows();
      next.close();
      YearMonth rollbackMonth = YearMonth.now(ZoneId.of("Asia/Shanghai"));
      Runtime dynamic = fixture.open("dynamic-monthly", null, false);
      dynamic.assertGraph(false);
      dynamic.awaitEmptyScans();
      assertEquals(
          rollbackMonth,
          YearMonth.now(ZoneId.of("Asia/Shanghai")),
          "machine month crossed during bounded rollback; preserve");
      fixture.allowedMonths.addAll(OrderTableInitializer.rollingMonths(rollbackMonth));
      fixture.verifyInventory();
      fixture.assertPriorRows(beforeRollback);
      assertEquals(1, dynamic.orders.findOrder("mall_order_202612", "202612Publication").size());
      assertEquals(1, dynamic.orders.findOrder("mall_order_202703", "202703Publication").size());
      fixture.assertPriorRows(beforeRollback);
      write(
          fixture.path.resolve("publication.json"),
          Map.of(
              "old",
              oldInfo,
              "prepared",
              prepared,
              "new",
              next.info(),
              "dynamicMachineMonth",
              rollbackMonth.toString(),
              "finalMonths",
              fixture.allowedMonths,
              "rowsBeforeRollback",
              beforeRollback,
              "rowsAfterRollback",
              fixture.rows()));
      fixture.accept();
    }
  }

  /** One exact catalog, all context generations, and its independently verified cleanup gate. */
  private static final class Fixture implements AutoCloseable {
    /** Unique catalog ownership is registered only after CREATE succeeds. */
    private final String database = "cm17ej_" + UUID.randomUUID().toString().replace("-", "");

    /** Native files for this fixed role. */
    private final Path path;

    /** Fixed role controls its declared initial exceptions. */
    private final String role;

    /** Direct physical audit/setup connections, never used as Spring business beans. */
    private GuardedDataSource base;

    /** Exact physical audit JDBC with bounded statements. */
    private JdbcTemplate sql;

    /** Every successful and failed Spring context generation belongs to this list. */
    private final List<Runtime> runtimes = new ArrayList<>();

    /**
     * Full-month groups allowed by original preparation, including dynamic rollback's actual
     * window.
     */
    private final SortedSet<String> allowedMonths = new TreeSet<>();

    /** Explicit partial history/negative setup objects, independent of full rolling groups. */
    private final Set<String> extraTables = new TreeSet<>();

    /** Reliability DDL is permitted only after non-conflicting bootstrap enters maintenance. */
    private boolean allowedReliability;

    /** Exact CREATE/terminal/cleanup state; unknown always preserves. */
    private boolean created, accepted;

    /** Initial row facts, including the one intentional bad-month row in its role. */
    private Map<String, List<Map<String, Object>>> initialRows;

    /** Terminal full facts retained for exact pre-delete comparison. */
    private Facts terminal;

    /** Monotonic unique native phase files. */
    private int phase;

    Fixture(String method, String role) throws Exception {
      assertClean();
      this.role = role;
      path = auditRoot.resolve(method);
      Files.createDirectory(path);
      write(
          path.resolve("resource-plan.json"),
          Map.of(
              "database",
              database,
              "role",
              role,
              "initialModels",
              FOUNDATION,
              "modelRows",
              0,
              "maintenanceAlgorithm",
              "original initializers only",
              "oldCatalogsOwned",
              false,
              "createdAt",
              Instant.now().toString()));
    }

    void prepare() throws Exception {
      assertTrue(database.matches("cm17ej_[0-9a-f]{32}"));
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      source.execute(
          "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_0900_ai_ci");
      CREATED.add(database);
      created = true;
      String url = boundedUrl(sourceUrl).replace("/cloudmall_order?", "/" + database + "?");
      base = new GuardedDataSource(url, database);
      sql = jdbc(base);
      for (String table : FOUNDATION) {
        sql.execute("CREATE TABLE " + table + " LIKE cloudmall_order." + table);
        assertEquals(definition(source, table), definition(sql, table));
      }
      initialRows = rows();
      assertTrue(initialRows.values().stream().allMatch(List::isEmpty));
      verifyInventory();
      write(path.resolve("initial-facts.json"), facts());
    }

    Runtime open(String mode, YearMonth simulatedMonth, boolean conflict) throws Exception {
      assertTrue(
          runtimes.stream().allMatch(runtime -> runtime.closed),
          "close old graph before any replacement");
      Runtime runtime = new Runtime(this, mode, simulatedMonth, conflict, runtimes.size());
      runtimes.add(runtime);
      if (!conflict) {
        allowedMonths.addAll(
            OrderTableInitializer.rollingMonths(
                simulatedMonth == null
                    ? YearMonth.now(ZoneId.of("Asia/Shanghai"))
                    : simulatedMonth));
        allowedReliability = true;
      }
      runtime.start();
      return runtime;
    }

    void addMonths(Collection<String> months, Set<String> families) {
      for (String suffix : months)
        for (String family : families) {
          String table = family + "_" + suffix;
          sql.execute("CREATE TABLE " + identifier(table) + " LIKE " + family + "_template");
          extraTables.add(table);
        }
    }

    void verifyNormalTables(Collection<String> months) throws Exception {
      assertEquals(new TreeSet<>(months), allowedMonths);
      verifyInventory();
      assertEquals(22, tableTypes(sql).size());
      assertTrue(rows().values().stream().allMatch(List::isEmpty));
      write(
          path.resolve(String.format(Locale.ROOT, "%02d-bootstrap-facts.json", phase++)), facts());
    }

    void verifyInventory() {
      Set<String> expected = new TreeSet<>(FOUNDATION);
      expected.addAll(extraTables);
      if (allowedReliability) expected.addAll(LEDGERS);
      for (String suffix : allowedMonths)
        for (String family : FAMILIES) expected.add(family + "_" + suffix);
      Map<String, String> actual = tableTypes(sql);
      assertEquals(expected, actual.keySet());
      assertTrue(actual.values().stream().allMatch("BASE TABLE"::equals));
      Map<String, Object> objects = objects(sql);
      assertEquals(List.of(), objects.get("views"));
      assertEquals(List.of(), objects.get("triggers"));
      assertEquals(List.of(), objects.get("events"));
      assertEquals(List.of(), objects.get("routines"));
      for (String table : actual.keySet()) {
        if (table.endsWith("_template") || table.equals("order_idempotency"))
          assertEquals(definition(source, table), definition(sql, table));
        else
          for (String family : FAMILIES)
            if (table.startsWith(family + "_") && table.matches(family + "_[0-9]{6}"))
              assertEquals(definition(source, family + "_template"), definition(sql, table));
      }
    }

    void assertRejected() throws Exception {
      for (Runtime runtime : runtimes) {
        assertTrue(runtime.closed);
        assertFalse(runtime.probe.refreshed);
        assertEquals(0, runtime.probe.scans.size());
        assertEquals(0, runtime.probe.rpc.size());
        assertFalse(runtime.probe.auditReady);
        assertTrue(runtime.probe.sql.isEmpty());
      }
      assertOwnedConnectionsClosed();
      write(
          path.resolve("rejection.json"),
          Map.of(
              "role",
              role,
              "businessOpened",
              false,
              "rpc",
              0,
              "scheduledCompletions",
              0,
              "actualFacts",
              facts()));
    }

    void assertOnlyInitialRows() {
      Map<String, List<Map<String, Object>>> current = rows();
      for (String table : current.keySet())
        assertEquals(initialRows.getOrDefault(table, List.of()), current.get(table));
    }

    Map<String, List<Map<String, Object>>> rows() {
      Map<String, List<Map<String, Object>>> result = new TreeMap<>();
      for (String table : tableTypes(sql).keySet()) result.put(table, tableRows(sql, table));
      return result;
    }

    Facts facts() {
      assertEquals(database, sql.queryForObject("SELECT DATABASE()", String.class));
      Map<String, TableFact> tables = new TreeMap<>();
      for (String table : tableTypes(sql).keySet()) {
        List<Map<String, Object>> rows = tableRows(sql, table);
        long count = sql.queryForObject("SELECT COUNT(*) FROM " + identifier(table), Long.class);
        assertEquals(count, rows.size());
        tables.put(
            table,
            new TableFact(
                count,
                checksum(sql, table),
                definition(sql, table),
                (String)
                    sql.queryForMap("SHOW CREATE TABLE " + identifier(table)).get("Create Table"),
                rows));
      }
      return new Facts(database, tables, objects(sql));
    }

    void assertPriorRows(Map<String, List<Map<String, Object>>> original) {
      Map<String, List<Map<String, Object>>> current = rows();
      for (String table : current.keySet())
        assertEquals(original.getOrDefault(table, List.of()), current.get(table), table);
    }

    void accept() throws Exception {
      verifyInventory();
      for (String table : tableTypes(sql).keySet())
        if (table.startsWith("order_status_log_") || table.endsWith("_template"))
          assertEquals(
              0, sql.queryForObject("SELECT COUNT(*) FROM " + identifier(table), Integer.class));
      terminal = facts();
      write(path.resolve("terminal-facts.json"), terminal);
      accepted = true;
    }

    void assertOwnedConnectionsClosed() {
      base.assertClosedConnections();
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.processlist WHERE DB=?",
              Integer.class,
              database),
          "an owned pool/connection remains; preserve");
    }

    /** All generations close independently before row/ownership/source gates and exact deletion. */
    @Override
    public void close() throws Exception {
      List<Throwable> errors = new ArrayList<>();
      for (Runtime runtime : runtimes) attempt(errors, runtime::close);
      attempt(
          errors,
          () -> {
            assertTrue(created);
            assertTrue(accepted);
            verifyInventory();
            assertEquals(terminal, facts());
            assertOwnedConnectionsClosed();
            assertClean();
            assertEquals(sourceBefore, sourceFingerprint());
          });
      attempt(errors, () -> write(path.resolve("before-delete.json"), facts()));
      if (errors.isEmpty())
        attempt(
            errors,
            () -> {
              assertTrue(CREATED.contains(database));
              assertTrue(database.matches("cm17ej_[0-9a-f]{32}"));
              assertTrue(runtimes.stream().allMatch(runtime -> runtime.closed));
              assertEquals(database, sql.queryForObject("SELECT DATABASE()", String.class));
              verifyInventory();
              assertEquals(terminal, facts());
              assertOwnedConnectionsClosed();
              assertClean();
              write(
                  path.resolve("delete-gate.json"),
                  Map.of(
                      "database",
                      database,
                      "facts",
                      terminal,
                      "sourceStillEqual",
                      sourceBefore.equals(sourceFingerprint()),
                      "contextsClosed",
                      true,
                      "unknownWorker",
                      false));
              source.execute("DROP DATABASE " + identifier(database));
              assertEquals(
                  0,
                  source.queryForObject(
                      "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                      Integer.class,
                      database));
              DELETED.add(database);
            });
      attempt(
          errors,
          () ->
              write(
                  path.resolve("cleanup.json"),
                  Map.of(
                      "database",
                      database,
                      "role",
                      role,
                      "created",
                      created,
                      "accepted",
                      accepted,
                      "deleted",
                      DELETED.contains(database),
                      "preserved",
                      created && !DELETED.contains(database),
                      "errors",
                      safeErrors(errors),
                      "internalFrameworkThreadsJoinedClaimed",
                      false)));
      throwIfErrors(errors);
    }

    void exercise(Runtime runtime) throws Exception {
      OrderController controller = runtime.context.getBean(OrderController.class);
      assertTrue(AopUtils.isAopProxy(controller));
      runtime.probe.operationStarted = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
      String key = "17ej-normal-" + UUID.randomUUID();
      OrderVO normal;
      try {
        AuthContext.set(7L, "USER");
        normal = (OrderVO) controller.create(key, request()).data;
        verifyVO(normal, "PENDING_PAYMENT", 2);
        assertEquals(1, runtime.probe.reserves.get());
        assertEquals(1, runtime.probe.cartCleanup.get());
        Map<String, List<Map<String, Object>>> before = rows();
        int calls = runtime.probe.rpc.size();
        int writes = runtime.probe.writes();
        OrderVO replay = (OrderVO) controller.create(key, null).data;
        assertEquals(JSON.writeValueAsString(normal), JSON.writeValueAsString(replay));
        assertEquals(before, rows());
        assertEquals(calls, runtime.probe.rpc.size());
        assertEquals(writes, runtime.probe.writes());
        assertEquals(
            JSON.writeValueAsString(normal),
            JSON.writeValueAsString(controller.get(normal.orderNo).data));
        PageResult<?> page =
            (PageResult<?>)
                runtime
                    .context
                    .getBean(OrderQueryController.class)
                    .list(
                        null,
                        1,
                        20,
                        machineMonth
                            .atDay(1)
                            .atStartOfDay()
                            .atOffset(ZoneOffset.ofHours(8))
                            .toString(),
                        machineMonth
                            .plusMonths(1)
                            .atDay(1)
                            .atStartOfDay()
                            .atOffset(ZoneOffset.ofHours(8))
                            .toString())
                    .data;
        assertEquals(1, page.total);
        assertEquals(normal.orderNo, ((OrderVO) page.items.get(0)).orderNo);
        runtime.probe.operationStarted = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        OrderVO cancelled = (OrderVO) controller.cancel(normal.orderNo, null).data;
        verifyVO(cancelled, "CANCELLED", 2);
      } finally {
        AuthContext.clear();
      }
      runtime.probe.operationStarted = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
      runtime.context.getBean(OrderStockActionRecovery.class).recoverDue();
      assertEquals(1, runtime.probe.rollbacks.get());
      assertEquals("ROLLED_BACK", runtime.probe.reservations.get(normal.orderNo));
      LocalDateTime occurred =
          LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
      String seckillNo =
          month(YearMonth.from(occurred)) + UUID.randomUUID().toString().replace("-", "");
      String address = JSON.writeValueAsString(ADDRESS);
      Map<String, Object> event = new LinkedHashMap<>();
      event.put("eventType", "SECKILL_ORDER_ACCEPTED");
      event.put("orderNo", seckillNo);
      event.put("idempotencyKey", "17ej-seckill");
      event.put("userId", 7L);
      event.put("activityId", 1L);
      event.put("skuId", 7L);
      event.put("addressSnapshot", address);
      event.put("occurredAt", occurred.atOffset(ZoneOffset.ofHours(8)).toString());
      event.put("eventId", SeckillRequestFingerprint.eventId(seckillNo));
      event.put("requestHash", SeckillRequestFingerprint.hash(1L, 7L, 7L, 1, address));
      runtime.probe.operationStarted = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
      controller.consumeSeckill(event);
      Map<String, List<Map<String, Object>>> first = rows();
      int products = runtime.probe.products.get();
      controller.consumeSeckill(event);
      assertEquals(products, runtime.probe.products.get());
      assertEquals(first, rows());
      try {
        AuthContext.set(7L, "USER");
        verifyVO((OrderVO) controller.get(seckillNo).data, "PENDING_PAYMENT", 1);
      } finally {
        AuthContext.clear();
      }
      assertEquals(1L, rows().get("order_idempotency").size());
      assertEquals(1L, rows().get("order_reservation_intent").size());
      assertEquals(2L, rows().get("order_outbox").size());
      assertEquals("COMMITTED", rows().get("order_reservation_intent").get(0).get("status"));
      assertEquals("SUCCEEDED", rows().get("order_stock_action").get(0).get("status"));
      assertEquals(1L, rows().get("order_stock_action").get(0).get("attempts"));
      assertEquals(
          normal.orderNo + ":ROLLBACK",
          rows().get("order_stock_action").get(0).get("operation_key"));
      assertEquals("COMMITTED", rows().get("seckill_order_dedup").get(0).get("status"));
      assertEquals(0, rows().get("seckill_order_dedup_conflict").size());
      assertEquals(0, rows().get("seckill_message_recovery").size());
      assertEquals(
          JSON.readTree(JSON.writeValueAsString(List.of(new StockClient.Line(7L, 2)))),
          JSON.readTree(
              (String) rows().get("order_reservation_intent").get(0).get("items_snapshot")));
      assertEquals(key, rows().get("order_idempotency").get(0).get("idempotency_key"));
      assertEquals(normal.orderNo, rows().get("order_idempotency").get(0).get("order_no"));
      for (Map<String, Object> outbox : rows().get("order_outbox")) {
        String no = (String) outbox.get("order_no");
        assertEquals("order-timeout:" + no, outbox.get("event_id"));
        assertEquals("ORDER_TIMEOUT", outbox.get("event_type"));
        assertEquals("PENDING", outbox.get("status"));
        assertEquals(0L, outbox.get("attempts"));
        Map<String, Object> order =
            rows().get("mall_order_" + no.substring(0, 6)).stream()
                .filter(row -> no.equals(row.get("order_no")))
                .findFirst()
                .orElseThrow();
        com.fasterxml.jackson.databind.JsonNode payload =
            JSON.readTree((String) outbox.get("payload"));
        assertEquals(no, payload.get("orderNo").textValue());
        assertEquals("ORDER_TIMEOUT", payload.get("eventType").textValue());
        assertEquals(outbox.get("event_id"), payload.get("eventId").textValue());
        assertEquals(
            LocalDateTime.parse((String) order.get("created_at")).atOffset(ZoneOffset.ofHours(8)),
            OffsetDateTime.parse(payload.get("occurredAt").textValue()));
        assertEquals(
            LocalDateTime.parse((String) order.get("expire_at")).atOffset(ZoneOffset.ofHours(8)),
            OffsetDateTime.parse(payload.get("expireAt").textValue()));
      }
      verifyBusinessGolden(runtime);
      assertTrue(
          runtime.probe.sql.stream()
              .anyMatch(
                  call ->
                      normalized((String) call.get("sql")).startsWith("insert into mall_order(")));
      assertTrue(
          runtime.probe.sql.stream()
              .anyMatch(
                  call ->
                      normalized((String) call.get("sql"))
                          .startsWith("insert into mall_order_item(")));
      for (Map<String, Object> call : runtime.probe.sql) {
        String text = normalized((String) call.get("sql"));
        assertFalse(text.matches(".*(?:from|into|update) mall_order(?:_item)?_[0-9]{6}.*"));
        if (text.startsWith("insert into mall_order") || text.startsWith("update mall_order"))
          assertEquals(true, call.get("transactionActive"));
      }
      write(
          path.resolve("business-chain.json"),
          Map.of(
              "normalOrderNo",
              normal.orderNo,
              "seckillEvent",
              event,
              "logicalInputs",
              runtime.probe.sql,
              "clientCalls",
              runtime.probe.rpc,
              "fullFacts",
              facts(),
              "originalFourInitialScans",
              runtime.probe.scans,
              "actualControllerClass",
              controller.getClass().getName()));
      assertClean();
    }

    void seedPublication(Runtime runtime, YearMonth month) throws Exception {
      insertPublicationOrder(runtime, month, "202612Publication", 601L);
      OrderReservationIntentPO intent = new OrderReservationIntentPO();
      intent.id = 701L;
      intent.orderNo = "202612Pending";
      intent.userId = 7L;
      intent.idempotencyKey = "17ej-unresolved-original-key";
      intent.itemsSnapshot = "[]";
      intent.status = "RESERVE_UNKNOWN";
      intent.lastError = "original pending";
      intent.nextRetryAt = LocalDateTime.parse("2099-01-01T00:00:00.123");
      intent.createdAt = month.atDay(16).atTime(9, 10, 11, 123000000);
      intent.updatedAt = intent.createdAt;
      runtime.context.getBean(OrderReservationIntentMapper.class).insertIfAbsent(intent);
      OrderOutboxPO outbox = new OrderOutboxPO();
      outbox.id = 702L;
      outbox.eventId = "17ej-original-pending-event";
      outbox.eventType = "ORDER_TIMEOUT";
      outbox.orderNo = "202612Publication";
      outbox.payload = "{}";
      outbox.status = "PENDING";
      outbox.attempts = 0;
      outbox.nextRetryAt = LocalDateTime.parse("2099-01-01T00:00:00.123");
      outbox.createdAt = intent.createdAt;
      outbox.updatedAt = intent.createdAt;
      runtime.context.getBean(OrderOutboxMapper.class).insert(outbox);
      assertFullRow(poGolden(intent), rows().get("order_reservation_intent").get(0));
      assertFullRow(poGolden(outbox), rows().get("order_outbox").get(0));
      runtime
          .context
          .getBean(OrderStockActionMapper.class)
          .insert(
              703L, "202612Pending", "ROLLBACK", LocalDateTime.parse("2099-01-01T00:00:00.123"));
      assertEquals(
          1,
          sql.update(
              "UPDATE order_stock_action SET lease_owner=?,lease_until=? WHERE id=?",
              "17ej-original-owner",
              LocalDateTime.parse("2099-01-02T00:00:00.123"),
              703L));
      Map<String, Object> actionGolden = new LinkedHashMap<>();
      actionGolden.put("id", 703L);
      actionGolden.put("order_no", "202612Pending");
      actionGolden.put("action_type", "ROLLBACK");
      actionGolden.put("operation_key", "202612Pending:ROLLBACK");
      actionGolden.put("status", "PENDING");
      actionGolden.put("attempts", 0L);
      actionGolden.put("next_retry_at", "2099-01-01T00:00:00.123");
      actionGolden.put("created_at", "2099-01-01T00:00:00.123");
      actionGolden.put("updated_at", "2099-01-01T00:00:00.123");
      actionGolden.put("last_error", null);
      actionGolden.put("lease_owner", "17ej-original-owner");
      actionGolden.put("lease_until", "2099-01-02T00:00:00.123");
      assertFullRow(actionGolden, rows().get("order_stock_action").get(0));
      Map<String, Object> actual = rows().get("order_stock_action").get(0);
      assertEquals("17ej-original-owner", actual.get("lease_owner"));
      assertEquals("2099-01-02T00:00:00.123", actual.get("lease_until"));
      write(
          path.resolve("publication-seed.json"),
          Map.of(
              "purpose",
              "declared pending rows remain ineligible under the real recovery clock",
              "facts",
              facts(),
              "originalOwner",
              actual.get("lease_owner"),
              "originalOperationKey",
              actual.get("operation_key")));
    }

    void insertPublished(Runtime runtime, YearMonth month) throws Exception {
      insertPublicationOrder(runtime, month, "202703Publication", 602L);
    }

    void insertPublicationOrder(Runtime runtime, YearMonth month, String no, long id)
        throws Exception {
      LocalDateTime created = month.atDay(16).atTime(9, 10, 11, 123000000);
      OrderPO order = orderPO(no, id, created);
      assertEquals(1, runtime.orders.insertOrder("mall_order_" + month(month), order));
      Map<String, Object> expected = orderGolden(order);
      assertFullRow(
          expected,
          tableRows(sql, "mall_order_" + month(month)).stream()
              .filter(row -> no.equals(row.get("order_no")))
              .findFirst()
              .orElseThrow());
      assertEquals(1, runtime.orders.findOrder("mall_order_" + month(month), no).size());
    }

    /**
     * Replays only the fixed original successful writes, then checks every column and generated
     * identity.
     */
    void verifyBusinessGolden(Runtime runtime) throws Exception {
      Map<String, List<Map<String, Object>>> expected = new TreeMap<>();
      Map<String, TimeBinding> times = new LinkedHashMap<>();
      for (String table : tableTypes(sql).keySet()) expected.put(table, new ArrayList<>());
      for (Map<String, Object> call : runtime.probe.sql) {
        if (!(call.get("affected") instanceof Number affected) || affected.intValue() == 0)
          continue;
        String text = normalized((String) call.get("sql"));
        List<Object> args = args(call);
        if (text.startsWith("insert ")) {
          int open = text.indexOf('('), close = text.indexOf(')', open);
          String target =
              text.substring(0, open).trim().replaceFirst("insert(?: ignore)? into ", "");
          String[] columns = text.substring(open + 1, close).split(",");
          String values = text.substring(text.indexOf("values", close) + 6).trim();
          String[] expressions = values.substring(1, values.length() - 1).split(",");
          String no =
              String.valueOf(
                  args.get(
                      target.equals("seckill_order_dedup")
                          ? 4
                          : target.equals("order_idempotency")
                              ? 3
                              : target.equals("mall_order_item")
                                  ? 2
                                  : target.equals("order_outbox") ? 3 : 1));
          String table = FAMILIES.contains(target) ? target + "_" + no.substring(0, 6) : target;
          Map<String, Object> row = new LinkedHashMap<>();
          for (String column : columns(sql, table)) row.put(column, null);
          int index = 0;
          for (int column = 0; column < columns.length; column++) {
            String expression = expressions[column].trim();
            Object value =
                expression.equals("?")
                    ? args.get(index++)
                    : expression.startsWith("'")
                        ? expression.substring(1, expression.length() - 1).toUpperCase(Locale.ROOT)
                        : Long.valueOf(expression);
            row.put(columns[column].trim(), value);
          }
          assertEquals(args.size(), index);
          expected.get(table).add(row);
          for (String column : row.keySet())
            if (isTime(column) && row.get(column) != null)
              times.put(
                  table + ":" + row.get("id") + ":" + column,
                  new TimeBinding(table, row, column, row.get(column), call));
        } else if (text.startsWith("update order_reservation_intent set status=?")) {
          Map<String, Object> row = expected.get("order_reservation_intent").get(0);
          row.put("status", args.get(0));
          row.put("last_error", null);
          row.put("next_retry_at", null);
          time(times, "order_reservation_intent", row, "updated_at", args.get(1), call);
          assertEquals(row.get("order_no"), args.get(2));
        } else if (text.startsWith("update mall_order set status=?")) {
          String table = "mall_order_" + String.valueOf(args.get(6)).substring(0, 6);
          Map<String, Object> row =
              expected.get(table).stream()
                  .filter(value -> args.get(6).equals(value.get("order_no")))
                  .findFirst()
                  .orElseThrow();
          assertEquals("CANCELLED", args.get(0));
          assertEquals("PENDING_PAYMENT", row.get("status"));
          row.put("status", "CANCELLED");
          time(times, table, row, "updated_at", args.get(1), call);
          time(times, table, row, "cancelled_at", args.get(5), call);
        } else if (text.startsWith("update order_stock_action set lease_owner=?")) {
          Map<String, Object> row = expected.get("order_stock_action").get(0);
          assertEquals(row.get("id"), args.get(3));
          row.put("lease_owner", args.get(0));
          row.put("attempts", 1L);
          time(times, "order_stock_action", row, "lease_until", args.get(1), call);
          time(times, "order_stock_action", row, "updated_at", args.get(2), call);
        } else if (text.startsWith("update order_stock_action set status=?")) {
          Map<String, Object> row = expected.get("order_stock_action").get(0);
          assertEquals(row.get("id"), args.get(3));
          assertEquals(row.get("lease_owner"), args.get(4));
          assertEquals("SUCCEEDED", args.get(0));
          row.put("status", "SUCCEEDED");
          row.put("last_error", null);
          row.put("lease_owner", null);
          row.put("lease_until", null);
          times.remove("order_stock_action:" + row.get("id") + ":lease_until");
          time(times, "order_stock_action", row, "updated_at", args.get(2), call);
        } else if (text.startsWith("update seckill_order_dedup set status='committed'")) {
          Map<String, Object> row = expected.get("seckill_order_dedup").get(0);
          row.put("status", "COMMITTED");
          assertEquals(row.get("user_id"), args.get(1));
          assertEquals(row.get("idempotency_key"), args.get(2));
          time(times, "seckill_order_dedup", row, "updated_at", args.get(0), call);
        } else throw new AssertionError("CM17EJ_UNDECLARED_BUSINESS_WRITE");
      }
      Map<String, List<Map<String, Object>>> actual = rows();
      List<Map<String, Object>> temporalProof = new ArrayList<>();
      for (TimeBinding binding : times.values()) {
        Map<String, Object> row =
            actual.get(binding.table).stream()
                .filter(value -> value.get("id").equals(binding.row.get("id")))
                .findFirst()
                .orElseThrow();
        LocalDateTime input = LocalDateTime.parse((String) binding.input);
        LocalDateTime stored = LocalDateTime.parse((String) row.get(binding.column));
        LocalDateTime floor = input.truncatedTo(ChronoUnit.MILLIS);
        assertTrue(stored.equals(floor) || stored.equals(floor.plusNanos(1000000)));
        binding.row.put(binding.column, MILLIS.format(stored));
        if (binding.column.equals("updated_at")
            && !input.equals(LocalDateTime.parse(String.valueOf(binding.row.get("created_at"))))) {
          assertFalse(
              input.isBefore(LocalDateTime.parse((String) binding.call.get("operationStarted"))));
          assertFalse(input.isAfter(LocalDateTime.parse((String) binding.call.get("returnedAt"))));
        }
        temporalProof.add(
            Map.of(
                "table",
                binding.table,
                "id",
                binding.row.get("id"),
                "column",
                binding.column,
                "input",
                input.toString(),
                "floor",
                MILLIS.format(floor),
                "nextMillis",
                MILLIS.format(floor.plusNanos(1000000)),
                "actual",
                MILLIS.format(stored)));
      }
      for (String table : expected.keySet()) {
        List<Map<String, Object>> planned =
            expected.get(table).stream()
                .sorted(Comparator.comparingLong(row -> ((Number) row.get("id")).longValue()))
                .toList();
        assertEquals(planned.size(), actual.get(table).size(), table);
        for (int index = 0; index < planned.size(); index++)
          assertFullRow(planned.get(index), actual.get(table).get(index));
      }
      write(
          path.resolve("independent-full-golden.json"),
          Map.of(
              "expected",
              expected,
              "actual",
              actual,
              "originalSqlBindings",
              runtime.probe.sql,
              "temporalComponents",
              temporalProof));
    }
  }

  /**
   * A real Spring graph; only clients, scheduler observation and JDBC observation are test-local.
   */
  private static final class Runtime implements AutoCloseable {
    /** Exact fixture owner. */
    private final Fixture fixture;

    /** Real Spring owns every business bean and its lifecycle. */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** Local transparent observations for this generation. */
    private final Probe probe = new Probe();

    /** Unique files for each generation, including failed refresh. */
    private final Path path;

    /** Selected mode and simulated publication month, never exposed as production properties. */
    private final String mode;

    /** Package-local production construction core is used only by C. */
    private final YearMonth simulatedMonth;

    /** Deliberate conflicting definitions in B only. */
    private final boolean conflict;

    /** Real selected mapper, assigned only after refresh. */
    private OrderSqlMapper orders;

    /** Business pool captured only for its actual close/active-connection evidence. */
    private HikariDataSource physical;

    /** Exact owned scheduler; futures/termination are checked without framework thread claims. */
    private ObservedScheduler scheduler;

    /** Actual lifecycle flags. */
    private boolean started, closed;

    /** Last memory-only info survives close for publication comparison. */
    private Map<String, Object> cachedInfo = Map.of();

    Runtime(
        Fixture fixture, String mode, YearMonth simulatedMonth, boolean conflict, int generation)
        throws Exception {
      this.fixture = fixture;
      this.mode = mode;
      this.simulatedMonth = simulatedMonth;
      this.conflict = conflict;
      path = fixture.path.resolve("generation-" + generation);
      Files.createDirectory(path);
      probe.threadPrefix = "cm17ej-" + UUID.randomUUID() + "-";
      probe.path = path;
    }

    void start() throws Exception {
      context.setAllowBeanDefinitionOverriding(false);
      context.getEnvironment().setActiveProfiles("cm17ej-runtime");
      Map<String, Object> properties = new LinkedHashMap<>();
      properties.put("cloudmall.order.routing.mode", mode);
      properties.put("seata.enabled", "false");
      properties.put("spring.datasource.url", fixture.base.url);
      properties.put("spring.datasource.username", "root");
      properties.put("spring.datasource.password", "root");
      properties.put("spring.datasource.driver-class-name", "com.mysql.cj.jdbc.Driver");
      properties.put("spring.datasource.hikari.maximum-pool-size", "4");
      properties.put("spring.datasource.hikari.minimum-idle", "0");
      properties.put("spring.datasource.hikari.connection-timeout", "5000");
      for (String key :
          List.of(
              "reservation-recovery-interval-ms",
              "outbox.dispatch-interval-ms",
              "stock-action-recovery-interval-ms",
              "seckill-recovery-interval-ms",
              "table-maintenance-ms")) properties.put("cloudmall.order." + key, "86400000");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("owned-runtime", properties));
      context.getBeanFactory().registerSingleton("runtimeProbe", probe);
      context.register(
          Assembly.class,
          OrderRoutingConfiguration.class,
          OrderRoutingAudit.class,
          OrderTableInitializer.class,
          OrderReliabilityTableInitializer.class,
          OrderSqlMapper.class,
          OrderReservationIntentMapper.class,
          OrderOutboxMapper.class,
          OrderStockActionMapper.class,
          SeckillOrderDedupMapper.class,
          SeckillMessageRecoveryMapper.class,
          OrderReservationIntentService.class,
          OrderReservationRecovery.class,
          OrderOutboxDispatcher.class,
          OrderStockActionRecovery.class,
          SeckillMessageRecovery.class,
          OrderController.class,
          OrderQueryController.class,
          OrderQueryServiceImpl.class);
      if (simulatedMonth == null) context.register(OrderShardingRuntimeConfiguration.class);
      else
        context.registerBean(
            "orderShardingRuntimeConfiguration",
            OrderShardingRuntimeConfiguration.class,
            () -> new OrderShardingRuntimeConfiguration(simulatedMonth));
      if (conflict) context.register(Conflict.class);
      try {
        context.refresh();
        started = true;
        orders = context.getBean(OrderSqlMapper.class);
        scheduler = context.getBean(ObservedScheduler.class);
        DataSource selected = context.getBean(DataSource.class);
        physical =
            selected instanceof HikariDataSource raw
                ? raw
                : (HikariDataSource)
                    ReflectionTestUtils.getField(
                        context.getBean(OrderShardingBootstrap.class), "physical");
        cachedInfo = readInfo();
        write(
            path.resolve("refresh.json"),
            Map.of(
                "mode",
                mode,
                "simulatedMonth",
                simulatedMonth == null ? "system-Shanghai" : simulatedMonth.toString(),
                "refreshed",
                true,
                "info",
                cachedInfo,
                "businessMapper",
                orders.getClass().getName()));
      } catch (RuntimeException | Error failure) {
        probe.failures.add(safeError(failure));
        try {
          close();
        } catch (Exception closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
    }

    void assertGraph(boolean logical) throws Exception {
      assertTrue(probe.refreshed);
      assertTrue(probe.auditReady);
      Map<String, DataSource> sources = context.getBeansOfType(DataSource.class);
      assertEquals(Set.of("dataSource"), sources.keySet());
      assertEquals(Set.of("jdbcTemplate"), context.getBeansOfType(JdbcTemplate.class).keySet());
      assertEquals(
          Set.of("transactionManager"),
          context
              .getBeansOfType(org.springframework.transaction.PlatformTransactionManager.class)
              .keySet());
      assertEquals(Set.of("orderSqlMapper"), context.getBeansOfType(OrderSqlMapper.class).keySet());
      DataSource dataSource = context.getBean(DataSource.class);
      JdbcTemplate jdbc = context.getBean(JdbcTemplate.class);
      DataSourceTransactionManager manager = context.getBean(DataSourceTransactionManager.class);
      assertSame(dataSource, jdbc.getDataSource());
      assertSame(dataSource, manager.getDataSource());
      assertEquals(
          logical ? ShardingOrderSqlMapper.class : OrderSqlMapper.class, orders.getClass());
      for (Class<?> type :
          List.of(
              OrderReservationIntentMapper.class,
              OrderOutboxMapper.class,
              OrderStockActionMapper.class,
              SeckillOrderDedupMapper.class,
              SeckillMessageRecoveryMapper.class))
        assertSame(jdbc, ReflectionTestUtils.getField(context.getBean(type), "jdbcTemplate"));
      assertSame(jdbc, ReflectionTestUtils.getField(orders, logical ? "jdbc" : "jdbcTemplate"));
      if (logical) {
        context.getBean(OrderShardingBootstrap.class).verifyBinding(dataSource);
        assertNotSame(physical, dataSource);
        assertEquals(
            "org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.ShardingDataSource",
            dataSource.getClass().getName());
      } else assertSame(physical, dataSource);
      try (Connection connection = dataSource.getConnection()) {
        assertFalse(connection.isClosed());
        if (!logical) assertEquals(fixture.database, connection.getCatalog());
        assertTrue(connection.getAutoCommit());
      }
      try (Connection actual = physical.getConnection()) {
        assertEquals(fixture.database, actual.getCatalog());
      }
      assertTrue(AopUtils.isAopProxy(context.getBean(OrderController.class)));
      assertFalse(AopUtils.isAopProxy(context.getBean(OrderQueryController.class)));
      assertTrue(
          AopUtils.isAopProxy(
              context.getBean(com.cloudmall.order.service.OrderQueryService.class)));
      assertTrue(AopUtils.isAopProxy(context.getBean(OrderReservationIntentService.class)));
      write(
          path.resolve("bean-graph.json"),
          Map.of(
              "mode",
              mode,
              "dataSourceType",
              dataSource.getClass().getName(),
              "sameSelectedJdbcAndTm",
              true,
              "onlyMapper",
              orders.getClass().getName(),
              "reliabilityJdbcIdentity",
              true,
              "controllerSpringProxy",
              true,
              "intentRequiresNewSpringProxy",
              true,
              "info",
              info()));
    }

    void awaitEmptyScans() throws Exception {
      assertTrue(probe.firstFour.await(30, TimeUnit.SECONDS));
      assertEquals(
          Set.of("recoverStaleReservations", "dispatchPending", "recoverDue", "replayDue"),
          probe.scans.keySet());
      assertEquals(List.of(), probe.failures);
      for (Map<String, Object> scan : probe.scans.values()) {
        assertEquals(true, scan.get("auditReady"));
        assertEquals(true, scan.get("refreshObserved"));
        assertEquals(true, scan.get("returned"));
      }
      for (String table :
          List.of(
              "order_reservation_intent",
              "order_outbox",
              "order_stock_action",
              "seckill_message_recovery")) {
        assertTrue(
            probe.sql.stream()
                .anyMatch(
                    call ->
                        String.valueOf(call.get("thread")).startsWith(probe.threadPrefix)
                            && normalized((String) call.get("sql")).contains("from " + table + " ")
                            && Boolean.TRUE.equals(call.get("emptyResult"))),
            table);
      }
      assertTrue(probe.rpc.isEmpty());
      write(
          path.resolve("initial-empty-scans.json"),
          Map.of(
              "sequence",
              "original physical DDL -> actual inventory/admission -> immutable snapshot -> eager"
                  + " bound audit -> refresh event -> four original scheduled calls returned ->"
                  + " seed",
              "scans",
              probe.scans,
              "sql",
              probe.sql,
              "rpcCount",
              0));
    }

    Map<String, Object> readInfo() {
      org.springframework.boot.actuate.info.Info.Builder builder =
          new org.springframework.boot.actuate.info.Info.Builder();
      context.getBean(OrderRoutingAudit.class).contribute(builder);
      @SuppressWarnings("unchecked")
      Map<String, Object> details = (Map<String, Object>) builder.build().get("orderRouting");
      return new LinkedHashMap<>(details);
    }

    Map<String, Object> info() {
      if (!closed) cachedInfo = readInfo();
      return cachedInfo;
    }

    /**
     * Each owned context is closed before independent scheduler, pool and actual-catalog checks.
     */
    @Override
    public void close() throws Exception {
      if (closed) return;
      List<Throwable> errors = new ArrayList<>();
      if (started)
        attempt(
            errors,
            () -> {
              cachedInfo = readInfo();
              assertEquals(0, physical.getHikariPoolMXBean().getActiveConnections());
            });
      attempt(errors, context::close);
      if (scheduler == null) scheduler = probe.scheduler;
      if (scheduler != null)
        attempt(
            errors,
            () -> {
              assertTrue(
                  scheduler
                      .getScheduledThreadPoolExecutor()
                      .awaitTermination(10, TimeUnit.SECONDS));
              assertTrue(scheduler.getScheduledThreadPoolExecutor().isTerminated());
              assertTrue(
                  scheduler.futures.stream()
                      .allMatch(future -> future.isCancelled() || future.isDone()));
              assertEquals(0, probe.activeTasks.get());
            });
      if (physical != null) attempt(errors, () -> assertTrue(physical.isClosed()));
      attempt(errors, fixture::assertOwnedConnectionsClosed);
      attempt(
          errors,
          () ->
              write(
                  path.resolve("close.json"),
                  Map.of(
                      "started",
                      started,
                      "contextCloseReturned",
                      !context.isActive(),
                      "physicalPoolClosed",
                      physical == null
                          ? "constructor failure: actual owned connection count checked"
                          : physical.isClosed(),
                      "schedulerCreated",
                      scheduler != null,
                      "activeTasks",
                      probe.activeTasks.get(),
                      "scheduledFutures",
                      scheduler == null ? 0 : scheduler.futures.size(),
                      "scans",
                      probe.scans,
                      "failures",
                      probe.failures,
                      "closeErrors",
                      safeErrors(errors),
                      "internalFrameworkThreadJoinClaimed",
                      false)));
      if (errors.isEmpty()) closed = true;
      throwIfErrors(errors);
    }
  }

  /** Local observations are attached to actual production JDBC/Bean lifecycle calls. */
  private static final class Probe {
    /** Original scheduled first-return gate, four distinct methods only. */
    private final CountDownLatch firstFour = new CountDownLatch(4);

    /** Thread-safe first-return observations, recorded after real runnable completion. */
    private final Map<String, Map<String, Object>> scans = new ConcurrentHashMap<>();

    /** Business JDBC inputs/returns, not claimed to be physical wire SQL. */
    private final List<Map<String, Object>> sql = new CopyOnWriteArrayList<>();

    /** Exact local client calls; no actual network/broker is created. */
    private final List<Map<String, Object>> rpc = new CopyOnWriteArrayList<>();

    /** Safe failure facts retained even when refresh/first scan fails. */
    private final List<Map<String, Object>> failures = new CopyOnWriteArrayList<>();

    /** Stock substitute's explicit state per original number. */
    private final Map<String, String> reservations = new ConcurrentHashMap<>();

    /** Original calls are counted independently of SQL golden generation. */
    private final AtomicInteger reserves = new AtomicInteger(),
        rollbacks = new AtomicInteger(),
        products = new AtomicInteger(),
        cartCleanup = new AtomicInteger();

    /** Number of owned scheduler calls currently running. */
    private final AtomicInteger activeTasks = new AtomicInteger();

    /** Real lifecycle event/audit completion visibility. */
    private volatile boolean refreshed, auditReady;

    /** Unique owned scheduler thread prefix. */
    private String threadPrefix;

    /** Owning generation's native location. */
    private Path path;

    /** Exact scheduler captured even when context refresh subsequently fails. */
    private ObservedScheduler scheduler;

    /** Original business call window start, kept distinct from the later JDBC entry instant. */
    private volatile LocalDateTime operationStarted = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));

    void rpc(String method, Object identity) {
      rpc.add(
          Map.of(
              "method",
              method,
              "input",
              String.valueOf(identity),
              "transactionActive",
              TransactionSynchronizationManager.isActualTransactionActive(),
              "authUser",
              String.valueOf(AuthContext.userId()),
              "xidAbsent",
              RootContext.getXID() == null));
    }

    int writes() {
      return (int) sql.stream().filter(call -> call.get("affected") instanceof Number).count();
    }

    JdbcTemplate observe(JdbcTemplate target) {
      ProxyFactory factory = new ProxyFactory(target);
      factory.setProxyTargetClass(true);
      factory.addAdvice(
          (MethodInterceptor)
              invocation -> {
                Object[] inputs = invocation.getArguments();
                if (inputs.length == 0 || !(inputs[0] instanceof String statement))
                  return invocation.proceed();
                Map<String, Object> call = new LinkedHashMap<>();
                call.put("sql", statement);
                call.put("method", invocation.getMethod().getName());
                Object[] bound =
                    inputs.length > 1 && inputs[inputs.length - 1] instanceof Object[] array
                        ? array
                        : new Object[0];
                call.put(
                    "arguments",
                    Arrays.stream(bound).map(OrderShardingRuntimeMySqlIT::binding).toList());
                call.put("operationStarted", operationStarted.toString());
                call.put("enteredAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
                call.put("thread", Thread.currentThread().getName());
                call.put(
                    "transactionActive",
                    TransactionSynchronizationManager.isActualTransactionActive());
                call.put("xidAbsent", RootContext.getXID() == null);
                sql.add(call);
                try {
                  Object result = invocation.proceed();
                  if (invocation.getMethod().getName().equals("update") && result instanceof Number)
                    call.put("affected", result);
                  if (result instanceof List<?> rows) call.put("emptyResult", rows.isEmpty());
                  return result;
                } catch (Throwable failure) {
                  call.put("failure", safeError(failure));
                  throw failure;
                } finally {
                  call.put("returnedAt", LocalDateTime.now(ZoneId.of("Asia/Shanghai")).toString());
                }
              });
      return (JdbcTemplate) factory.getProxy();
    }
  }

  /** The same four original @Scheduled methods run; observation surrounds their real return. */
  private static final class ObservedScheduler extends ThreadPoolTaskScheduler {
    /** Associated generation observations. */
    private final Probe probe;

    /** Every actual scheduled future is retained for cancellation/termination evidence. */
    private final List<ScheduledFuture<?>> futures = new CopyOnWriteArrayList<>();

    ObservedScheduler(Probe probe) {
      this.probe = probe;
      setPoolSize(1);
      setThreadNamePrefix(probe.threadPrefix);
      setWaitForTasksToCompleteOnShutdown(false);
      setAwaitTerminationSeconds(10);
    }

    private Runnable observe(Runnable task) {
      String method =
          task instanceof ScheduledMethodRunnable scheduled
              ? scheduled.getMethod().getName()
              : "unknown";
      return () -> {
        probe.activeTasks.incrementAndGet();
        boolean returned = false;
        try {
          assertTrue(probe.auditReady);
          assertTrue(probe.refreshed);
          task.run();
          returned = true;
        } catch (Throwable failure) {
          probe.failures.add(safeError(failure));
          throw failure;
        } finally {
          if (Set.of("recoverStaleReservations", "dispatchPending", "recoverDue", "replayDue")
              .contains(method)) {
            Map<String, Object> event =
                Map.of(
                    "method",
                    method,
                    "auditReady",
                    probe.auditReady,
                    "refreshObserved",
                    probe.refreshed,
                    "returned",
                    returned,
                    "finishedAt",
                    Instant.now().toString());
            if (probe.scans.putIfAbsent(method, event) == null) probe.firstFour.countDown();
          }
          probe.activeTasks.decrementAndGet();
        }
      };
    }

    /** Wraps real immediate fixed-delay scheduling, retaining its original runnable and future. */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(Runnable task, long delay) {
      ScheduledFuture<?> future = super.scheduleWithFixedDelay(observe(task), delay);
      futures.add(future);
      return future;
    }

    /** Wraps original delayed table maintenance without advancing its initial delay. */
    @Override
    public ScheduledFuture<?> scheduleWithFixedDelay(
        Runnable task, java.util.Date start, long delay) {
      ScheduledFuture<?> future = super.scheduleWithFixedDelay(observe(task), start, delay);
      futures.add(future);
      return future;
    }
  }

  /**
   * Refresh event precedes ScheduledAnnotationBeanPostProcessor's event-driven task registration.
   */
  private static final class RefreshObserver
      implements ApplicationListener<ContextRefreshedEvent>, Ordered {
    /** Exact generation observer. */
    private final Probe probe;

    RefreshObserver(Probe probe) {
      this.probe = probe;
    }

    /** Runs before original scheduling's default lowest event order. */
    @Override
    public int getOrder() {
      return Ordered.HIGHEST_PRECEDENCE;
    }

    /** Proves eager audit is available before the first original scheduled task is registered. */
    @Override
    public void onApplicationEvent(ContextRefreshedEvent event) {
      event.getApplicationContext().getBean(OrderRoutingAudit.class);
      probe.auditReady = true;
      probe.refreshed = true;
    }
  }

  /**
   * No component scan or Rabbit listener infrastructure: all business/transaction/scheduled code is
   * original.
   */
  @TestConfiguration(proxyBeanMethods = false)
  @Profile("cm17ej-runtime")
  @EnableScheduling
  @EnableTransactionManagement(proxyTargetClass = true)
  @ImportAutoConfiguration(
      classes = {
        DataSourceAutoConfiguration.class,
        JdbcTemplateAutoConfiguration.class,
        DataSourceTransactionManagerAutoConfiguration.class,
        TransactionAutoConfiguration.class
      })
  static class Assembly {
    /** Observes the original selected JdbcTemplate without replacing its selected data source. */
    @Bean
    public static BeanPostProcessor jdbcObserver(Probe probe) {
      return new BeanPostProcessor() {
        /** Preserves the actual bean identity passed to every production mapper and final audit. */
        @Override
        public Object postProcessAfterInitialization(Object bean, String name) {
          return bean instanceof JdbcTemplate jdbc ? probe.observe(jdbc) : bean;
        }
      };
    }

    /** Only owned scheduling threads/futures are created and closed by this real context. */
    @Bean
    public ObservedScheduler taskScheduler(Probe probe) {
      ObservedScheduler scheduler = new ObservedScheduler(probe);
      probe.scheduler = scheduler;
      return scheduler;
    }

    /** Actual refresh-event ordering observation; no business work is invoked here. */
    @Bean
    public RefreshObserver refreshObserver(Probe probe) {
      return new RefreshObserver(probe);
    }

    /** Uses the same JSON serialization path as the original service. */
    @Bean
    public ObjectMapper objectMapper() {
      return new ObjectMapper();
    }

    /** Local Rabbit substitute; no listener container, broker connection or publisher claim. */
    @Bean
    public RabbitTemplate rabbitTemplate() {
      return mock(RabbitTemplate.class);
    }

    /** Local product facts with exact requested SKU and fixed cent-precision price. */
    @Bean
    public ProductClient productClient(Probe probe) {
      ProductClient client = mock(ProductClient.class);
      when(client.sku(7L))
          .thenAnswer(
              call -> {
                probe.products.incrementAndGet();
                probe.rpc("product.sku", 7L);
                return ApiResponse.ok(PRODUCT);
              });
      return client;
    }

    /** Local original address contract; no HTTP request is performed. */
    @Bean
    public UserClient userClient(Probe probe) {
      UserClient client = mock(UserClient.class);
      when(client.addresses())
          .thenAnswer(
              call -> {
                probe.rpc("user.addresses", 7L);
                return ApiResponse.ok(List.of(ADDRESS));
              });
      return client;
    }

    /** Conditional Cart proof and cleanup are the original inherited service's calls. */
    @Bean
    public CartClient cartClient(Probe probe) {
      CartClient client = mock(CartClient.class);
      CartCleanupSnapshotDTO snapshot =
          new CartCleanupSnapshotDTO(7L, "{\"skuId\":7,\"quantity\":2,\"checked\":true}");
      when(client.cleanupSnapshot(7L, 7L))
          .thenAnswer(
              call -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                probe.rpc("cart.snapshot", snapshot.expectedJson());
                return ApiResponse.ok(snapshot);
              });
      when(client.cleanupItem(7L, 7L, snapshot))
          .thenAnswer(
              call -> {
                probe.cartCleanup.incrementAndGet();
                probe.rpc("cart.cleanup", snapshot.expectedJson());
                return ApiResponse.ok(true);
              });
      return client;
    }

    /**
     * Stable local reservation facts let the original Action recovery complete its real JDBC
     * writes.
     */
    @Bean
    public StockClient stockClient(Probe probe) {
      StockClient client = mock(StockClient.class);
      when(client.reserve(any(StockClient.Reservation.class)))
          .thenAnswer(
              call -> {
                StockClient.Reservation request = call.getArgument(0);
                assertEquals(List.of(new StockClient.Line(7L, 2)), request.items());
                probe.reserves.incrementAndGet();
                probe.rpc("stock.reserve", request);
                probe.reservations.put(request.orderNo(), "RESERVED");
                return ApiResponse.ok(Map.of("orderNo", request.orderNo(), "status", "RESERVED"));
              });
      when(client.reservation(anyString()))
          .thenAnswer(
              call -> {
                String no = call.getArgument(0);
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                probe.rpc("stock.reservation", no);
                StockReservationStatusVO fact = new StockReservationStatusVO();
                fact.orderNo = no;
                fact.status = probe.reservations.getOrDefault(no, "NOT_FOUND");
                return ApiResponse.ok(fact);
              });
      when(client.rollback(anyString(), any(StockClient.Action.class)))
          .thenAnswer(
              call -> {
                String no = call.getArgument(0);
                StockClient.Action action = call.getArgument(1);
                assertEquals(no + ":ROLLBACK", action.operationKey());
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                probe.rollbacks.incrementAndGet();
                probe.rpc("stock.rollback", action.operationKey());
                probe.reservations.put(no, "ROLLED_BACK");
                return ApiResponse.ok(Map.of("orderNo", no, "status", "ROLLED_BACK"));
              });
      return client;
    }
  }

  /** Deliberately conflicting metadata is only active for the one negative context registration. */
  @TestConfiguration(proxyBeanMethods = false)
  @Profile("cm17ej-runtime")
  static class Conflict {
    /** Instantiation would be a harness failure: the metadata-only guard must reject first. */
    @Bean
    public DataSource unexpectedDataSource() {
      throw new AssertionError("CM17EJ_CONFLICT_INSTANTIATED");
    }

    /** Primary cannot hide a second transaction manager definition. */
    @Bean
    public DataSourceTransactionManager unexpectedTransactionManager() {
      throw new AssertionError("CM17EJ_CONFLICT_INSTANTIATED");
    }
  }

  private static final class GuardedDataSource extends AbstractDataSource {
    /** 唯一受界定且不输出的原连接URL。 */
    private final String url;

    /** 每条原生连接必须确认的精确catalog。 */
    private final String catalog;

    /** 成功取得但尚未成功关闭的本次连接。 */
    private final AtomicInteger openConnections = new AtomicInteger();

    /** 本DataSource内原生连接唯一身份。 */
    private final AtomicInteger sequence = new AtomicInteger();

    /** 原生close异常或身份异常会永久阻止清理。 */
    private boolean uncertain;

    /** 仅原业务公开入口窗口记录下发SQL。 */
    private boolean recording;

    /** 原生Statement真实execute时的SQL及参数，不是Mapper意图替代。 */
    private final List<Map<String, Object>> executions = new ArrayList<>();

    GuardedDataSource(String url, String catalog) {
      this.url = url;
      this.catalog = catalog;
    }

    /** 每次真实连接后核当前catalog，任何未知保持失败。 */
    @Override
    public Connection getConnection() throws SQLException {
      // 1. 使用原固定开发认证，URL和认证均不进入日志。
      Connection raw = DriverManager.getConnection(url, "root", "root");
      try (Statement check = raw.createStatement()) {
        check.setQueryTimeout(5);
        try (ResultSet row = check.executeQuery("SELECT DATABASE()")) {
          if (!row.next() || !catalog.equals(row.getString(1)))
            throw new SQLException("CM17EJ_CATALOG_MISMATCH");
        }
      } catch (SQLException | RuntimeException | Error failure) {
        uncertain = true;
        try {
          raw.close();
        } catch (SQLException closeFailure) {
          failure.addSuppressed(closeFailure);
        }
        throw failure;
      }
      // 2. 仅确认归属后计数，每次close必须真正成功才扣减。
      int connectionId = sequence.incrementAndGet();
      openConnections.incrementAndGet();
      boolean[] closed = {false};
      Connection[] wrapped = new Connection[1];
      wrapped[0] =
          (Connection)
              Proxy.newProxyInstance(
                  Connection.class.getClassLoader(),
                  new Class<?>[] {Connection.class},
                  (proxy, method, args) -> {
                    if (method.getName().equals("close")) {
                      if (!closed[0]) {
                        try {
                          invoke(method, raw, args);
                          closed[0] = true;
                          openConnections.decrementAndGet();
                        } catch (Throwable failure) {
                          uncertain = true;
                          throw failure;
                        }
                      }
                      return null;
                    }
                    if (method.getName().equals("setCatalog") && !catalog.equals(args[0])) {
                      uncertain = true;
                      throw new SQLException("CM17EJ_CATALOG_CHANGE_REJECTED");
                    }
                    Object result = invoke(method, raw, args);
                    if (result instanceof Statement statement) {
                      statement.setQueryTimeout(5);
                      String prepared =
                          args != null && args.length > 0 && args[0] instanceof String
                              ? (String) args[0]
                              : null;
                      return wrapStatement(statement, prepared, wrapped[0], connectionId);
                    }
                    return result;
                  });
      // 3. 后续事务与Statement均透传至同一个真实连接。
      return wrapped[0];
    }

    /** 认证不可在fixture内分叉。 */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      // 1. 只接受项目既定认证。
      if (!"root".equals(username) || !"root".equals(password))
        throw new SQLException("CM17EJ_CREDENTIAL_MISMATCH");
      // 2. 同样走精确catalog/归属门。
      Connection connection = getConnection();
      // 3. 不新建第二条隐藏连接。
      return connection;
    }

    private Statement wrapStatement(
        Statement raw, String prepared, Connection connection, int connectionId) {
      Map<Integer, Object> parameters = new TreeMap<>();
      Class<?> statementType =
          raw instanceof CallableStatement
              ? CallableStatement.class
              : raw instanceof PreparedStatement ? PreparedStatement.class : Statement.class;
      return (Statement)
          Proxy.newProxyInstance(
              Statement.class.getClassLoader(),
              new Class<?>[] {statementType},
              (proxy, method, args) -> {
                String name = method.getName();
                if (name.equals("getConnection")) return connection;
                Object result;
                if (name.startsWith("execute")) {
                  String text =
                      prepared != null
                          ? prepared
                          : args != null && args.length > 0 && args[0] instanceof String
                              ? (String) args[0]
                              : null;
                  if (recording && text != null) {
                    Map<String, Object> event = new LinkedHashMap<>();
                    event.put("sql", text);
                    event.put("parameters", new TreeMap<>(parameters));
                    event.put("connectionId", connectionId);
                    event.put(
                        "transactionActive",
                        TransactionSynchronizationManager.isActualTransactionActive());
                    event.put("authUser", AuthContext.userId());
                    event.put("xidAbsent", RootContext.getXID() == null);
                    executions.add(event);
                  }
                }
                result = invoke(method, raw, args);
                if (name.equals("clearParameters")) parameters.clear();
                else if (name.startsWith("set")
                    && args != null
                    && args.length >= 2
                    && args[0] instanceof Integer index) {
                  parameters.put(index, name.equals("setNull") ? null : binding(args[1]));
                }
                return result;
              });
    }

    void assertClosedConnections() {
      assertFalse(uncertain, "原生连接身份/关闭结果未知必须保留");
      assertEquals(0, openConnections.get(), "仍有本次连接未成功关闭");
    }
  }

  /** 完整表事实，保留全字段行而非仅checksum。 */
  private record TableFact(
      long count, String checksum, String definition, String ddl, List<Map<String, Object>> rows) {}

  /** 精确库事实，包含完整对象inventory。 */
  private record Facts(
      String database, Map<String, TableFact> tables, Map<String, Object> objects) {}

  /** 收尾每一步独立执行，不让审计IO跳过后续资源检查。 */
  @FunctionalInterface
  private interface CheckedStep {
    void run() throws Exception;
  }

  private static JdbcTemplate jdbc(DataSource dataSource) {
    JdbcTemplate template = new JdbcTemplate(dataSource);
    template.setQueryTimeout(5);
    return template;
  }

  private static Object invoke(Method method, Object target, Object[] args) throws Throwable {
    try {
      return method.invoke(target, args);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  private static String identifier(String name) {
    assertTrue(name.matches("[a-z0-9_]+"));
    return name;
  }

  private static Map<String, String> tableTypes(JdbcTemplate jdbc) {
    Map<String, String> result = new TreeMap<>();
    for (Map<String, Object> row :
        jdbc.queryForList(
            "SELECT TABLE_NAME,TABLE_TYPE FROM information_schema.tables WHERE"
                + " TABLE_SCHEMA=DATABASE() ORDER BY TABLE_NAME"))
      result.put((String) row.get("TABLE_NAME"), (String) row.get("TABLE_TYPE"));
    return result;
  }

  private static String definition(JdbcTemplate jdbc, String table) {
    String ddl =
        (String) jdbc.queryForMap("SHOW CREATE TABLE " + identifier(table)).get("Create Table");
    assertNotNull(ddl);
    return sha(
        ddl.replaceFirst("CREATE TABLE `[^`]+`", "CREATE TABLE `MODEL`")
            .getBytes(StandardCharsets.UTF_8));
  }

  private static String checksum(JdbcTemplate jdbc, String table) {
    Object checksum = jdbc.queryForMap("CHECKSUM TABLE " + identifier(table)).get("Checksum");
    assertNotNull(checksum);
    return checksum.toString();
  }

  private static Map<String, Object> sourceFingerprint() {
    assertEquals("cloudmall_order", source.queryForObject("SELECT DATABASE()", String.class));
    Map<String, Object> tables = new TreeMap<>();
    for (var entry : tableTypes(source).entrySet()) {
      assertEquals("BASE TABLE", entry.getValue());
      String table = identifier(entry.getKey());
      tables.put(
          table,
          Map.of(
              "count",
              source.queryForObject("SELECT COUNT(*) FROM " + table, Long.class),
              "checksum",
              checksum(source, table),
              "definition",
              definition(source, table)));
    }
    return Map.of("database", "cloudmall_order", "tables", tables, "objects", objects(source));
  }

  private static String boundedUrl(String url) {
    String[] pieces = url.split("\\?", 2);
    List<String> parameters = new ArrayList<>();
    if (pieces.length == 2)
      for (String parameter : pieces[1].split("&")) {
        String key = parameter.split("=", 2)[0];
        if (!Set.of(
                "serverTimezone",
                "forceConnectionTimeZoneToSession",
                "connectTimeout",
                "socketTimeout")
            .contains(key)) parameters.add(parameter);
      }
    parameters.add("serverTimezone=Asia%2FShanghai");
    parameters.add("forceConnectionTimeZoneToSession=true");
    parameters.add("connectTimeout=3000");
    parameters.add("socketTimeout=5000");
    return pieces[0] + "?" + String.join("&", parameters);
  }

  private static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (Exception failure) {
      throw new IllegalStateException("CM17EJ_SHA_UNAVAILABLE", failure);
    }
  }

  private static Map<String, Object> safeError(Throwable failure) {
    String message = String.valueOf(failure.getMessage());
    return Map.of(
        "type",
        failure.getClass().getName(),
        "messageSha256",
        sha(message.getBytes(StandardCharsets.UTF_8)));
  }

  private static List<Map<String, Object>> safeErrors(List<Throwable> errors) {
    return errors.stream().map(OrderShardingRuntimeMySqlIT::safeError).toList();
  }

  private static void write(Path path, Object value) throws Exception {
    Files.writeString(
        path,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value) + System.lineSeparator(),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW,
        StandardOpenOption.WRITE);
  }

  private static void attempt(List<Throwable> errors, CheckedStep step) {
    try {
      step.run();
    } catch (Exception | AssertionError failure) {
      errors.add(failure);
    }
  }

  private static void throwIfErrors(List<Throwable> errors) throws Exception {
    if (errors.isEmpty()) return;
    Exception failure = new Exception("CM17EJ_RESOURCE_OR_FACT_GATE_FAILED");
    for (Throwable error : errors) failure.addSuppressed(error);
    throw failure;
  }

  private static void assertClean() {
    assertNull(AuthContext.userId());
    assertNull(AuthContext.role());
    assertNull(RootContext.getXID());
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
  }

  /** 原参数保留纳秒；实际DATETIME行独立格式化至其已存储的毫秒组件。 */
  private static Object binding(Object value) {
    if (value == null) return null;
    if (value instanceof LocalDateTime local) return local.toString();
    if (value instanceof Timestamp stamp) return stamp.toLocalDateTime().toString();
    if (value instanceof BigDecimal money) return money.toPlainString();
    if (value instanceof Number number) return number.longValue();
    if (value instanceof Boolean) return value;
    return value.toString();
  }

  private static Map<String, Object> tuple(List<String> columns, Object[] values) {
    assertEquals(columns.size(), values.length);
    Map<String, Object> row = new LinkedHashMap<>();
    for (int index = 0; index < values.length; index++)
      row.put(columns.get(index), binding(values[index]));
    return row;
  }

  private static void assertFullRow(Map<String, Object> expected, Map<String, Object> actual)
      throws Exception {
    assertEquals(expected.keySet(), actual.keySet());
    for (String column : expected.keySet())
      assertColumn(column, expected.get(column), actual.get(column));
  }

  private static void assertColumn(String column, Object expected, Object actual) throws Exception {
    if (Set.of("address_snapshot", "sku_snapshot", "items_snapshot", "payload").contains(column)
        && expected != null
        && actual != null)
      assertEquals(JSON.readTree((String) expected), JSON.readTree((String) actual), column);
    else assertEquals(expected, actual, column);
  }

  /** Full field SQL observations still validate against fixed role outcomes independently. */
  private record TimeBinding(
      String table,
      Map<String, Object> row,
      String column,
      Object input,
      Map<String, Object> call) {}

  private static void time(
      Map<String, TimeBinding> times,
      String table,
      Map<String, Object> row,
      String column,
      Object input,
      Map<String, Object> call) {
    assertNotNull(input);
    row.put(column, input);
    times.put(
        table + ":" + row.get("id") + ":" + column,
        new TimeBinding(table, row, column, input, call));
  }

  private static boolean isTime(String column) {
    return column.endsWith("_at") || column.equals("lease_until");
  }

  @SuppressWarnings("unchecked")
  private static List<Object> args(Map<String, Object> call) {
    return (List<Object>) call.get("arguments");
  }

  private static String normalized(String sql) {
    return sql.toLowerCase(Locale.ROOT).replaceAll("\\s+", " ").trim();
  }

  private static String month(YearMonth month) {
    return String.format(Locale.ROOT, "%04d%02d", month.getYear(), month.getMonthValue());
  }

  private static CreateOrderDTO request() {
    CreateOrderDTO request = new CreateOrderDTO();
    request.addressId = 9L;
    OrderItemDTO item = new OrderItemDTO();
    item.skuId = 7L;
    item.quantity = 2;
    request.items = List.of(item);
    return request;
  }

  private static OrderPO orderPO(String no, long id, LocalDateTime created) throws Exception {
    OrderPO order = new OrderPO();
    order.id = id;
    order.orderNo = no;
    order.userId = 7L;
    order.status = "PENDING_PAYMENT";
    order.totalAmount = new BigDecimal("16.50");
    order.payAmount = order.totalAmount;
    order.addressSnapshot = JSON.writeValueAsString(ADDRESS);
    order.createdAt = created;
    order.updatedAt = created;
    order.expireAt = created.plusMinutes(30);
    return order;
  }

  private static Map<String, Object> orderGolden(OrderPO order) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", order.id);
    row.put("order_no", order.orderNo);
    row.put("user_id", 7L);
    row.put("status", "PENDING_PAYMENT");
    row.put("total_amount", "16.50");
    row.put("pay_amount", "16.50");
    row.put("address_snapshot", order.addressSnapshot);
    row.put("expire_at", MILLIS.format(order.expireAt));
    row.put("created_at", MILLIS.format(order.createdAt));
    row.put("updated_at", MILLIS.format(order.updatedAt));
    row.put("paid_at", null);
    row.put("cancelled_at", null);
    return row;
  }

  private static Map<String, Object> poGolden(Object po) throws Exception {
    Map<String, Object> row = new LinkedHashMap<>();
    for (Field field : po.getClass().getFields()) {
      String column =
          field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
      Object value = field.get(po);
      row.put(column, value instanceof LocalDateTime local ? MILLIS.format(local) : binding(value));
    }
    return row;
  }

  private static void verifyVO(OrderVO order, String status, int quantity) throws Exception {
    assertNotNull(order);
    assertEquals(7L, order.userId);
    assertEquals(status, order.status);
    assertEquals(new BigDecimal("8.25").multiply(BigDecimal.valueOf(quantity)), order.totalAmount);
    assertEquals(order.totalAmount, order.payAmount);
    assertEquals(
        JSON.readTree(JSON.writeValueAsString(ADDRESS)), JSON.readTree(order.addressSnapshot));
    OffsetDateTime created = OffsetDateTime.parse(order.createdAt),
        expiry = OffsetDateTime.parse(order.expireAt);
    assertEquals(ZoneOffset.ofHours(8), created.getOffset());
    assertEquals(ZoneOffset.ofHours(8), expiry.getOffset());
    assertEquals(created.plusMinutes(30), expiry);
    assertEquals(1, order.items.size());
    OrderItemVO item = order.items.get(0);
    assertEquals(70L, item.productId());
    assertEquals(7L, item.skuId());
    assertEquals(PRODUCT.productName(), item.productNameSnapshot());
    assertEquals(new BigDecimal("8.25"), item.unitPrice());
    assertEquals(quantity, item.quantity());
    assertEquals(order.totalAmount, item.lineAmount());
    assertEquals(PRODUCT.skuSnapshot(), item.skuSnapshot());
  }

  private static String causeText(Throwable failure) {
    StringBuilder text = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      text.append(cause.getClass().getName()).append(':').append(cause.getMessage()).append('\n');
    return text.toString();
  }

  private static List<String> columns(JdbcTemplate jdbc, String table) {
    identifier(table);
    return jdbc.queryForList(
        "SELECT COLUMN_NAME FROM information_schema.columns WHERE TABLE_SCHEMA=DATABASE() AND"
            + " TABLE_NAME=? ORDER BY ORDINAL_POSITION",
        String.class,
        table);
  }

  /**
   * MySQL CHAR projection is applied to every DATETIME before generic physical full-row
   * materialization.
   */
  private static List<Map<String, Object>> tableRows(JdbcTemplate jdbc, String table) {
    identifier(table);
    List<Map<String, Object>> metadata =
        jdbc.queryForList(
            "SELECT COLUMN_NAME,DATA_TYPE FROM information_schema.columns WHERE"
                + " TABLE_SCHEMA=DATABASE() AND TABLE_NAME=? ORDER BY ORDINAL_POSITION",
            table);
    assertFalse(metadata.isEmpty());
    List<String> projection = new ArrayList<>();
    for (Map<String, Object> column : metadata) {
      String name = identifier((String) column.get("COLUMN_NAME"));
      projection.add(
          "datetime".equals(column.get("DATA_TYPE"))
              ? "CAST(" + name + " AS CHAR) AS " + name
              : name);
    }
    return jdbc.query(
        "SELECT " + String.join(",", projection) + " FROM " + table + " ORDER BY id",
        (row, index) -> {
          Map<String, Object> values = new LinkedHashMap<>();
          for (Map<String, Object> column : metadata) {
            String name = (String) column.get("COLUMN_NAME"),
                type = (String) column.get("DATA_TYPE");
            Object value;
            if (type.equals("datetime")) {
              String text = row.getString(name);
              value =
                  text == null ? null : MILLIS.format(LocalDateTime.parse(text.replace(' ', 'T')));
            } else if (type.equals("json")) value = row.getString(name);
            else {
              value = row.getObject(name);
              if (value instanceof BigDecimal money) value = money.toPlainString();
              else if (value instanceof Number number) value = number.longValue();
              else if (value instanceof byte[] bytes) value = HexFormat.of().formatHex(bytes);
            }
            values.put(name, value);
          }
          return values;
        });
  }

  private static Map<String, Object> objects(JdbcTemplate jdbc) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("tables", tableTypes(jdbc));
    result.put(
        "views",
        jdbc.queryForList(
            "SELECT TABLE_NAME FROM information_schema.views WHERE TABLE_SCHEMA=DATABASE() ORDER BY"
                + " TABLE_NAME",
            String.class));
    result.put(
        "triggers",
        jdbc.queryForList(
            "SELECT TRIGGER_NAME FROM information_schema.triggers WHERE TRIGGER_SCHEMA=DATABASE()"
                + " ORDER BY TRIGGER_NAME",
            String.class));
    result.put(
        "triggerMetadata",
        jdbc.queryForList(
            "SELECT"
                + " TRIGGER_SCHEMA,TRIGGER_NAME,EVENT_OBJECT_TABLE,ACTION_TIMING,EVENT_MANIPULATION,ACTION_STATEMENT"
                + " FROM information_schema.triggers WHERE TRIGGER_SCHEMA=DATABASE() ORDER BY"
                + " TRIGGER_NAME"));
    result.put(
        "events",
        jdbc.queryForList(
            "SELECT EVENT_NAME FROM information_schema.events WHERE EVENT_SCHEMA=DATABASE() ORDER"
                + " BY EVENT_NAME",
            String.class));
    result.put(
        "routines",
        jdbc.queryForList(
            "SELECT ROUTINE_NAME FROM information_schema.routines WHERE ROUTINE_SCHEMA=DATABASE()"
                + " ORDER BY ROUTINE_NAME",
            String.class));
    return result;
  }
}
