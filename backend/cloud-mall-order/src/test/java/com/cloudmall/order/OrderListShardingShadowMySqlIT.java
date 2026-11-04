package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.config.OrderShardingRuleFactory;
import com.cloudmall.order.controller.OrderQueryController;
import com.cloudmall.order.domain.dto.OrderQueryWindow;
import com.cloudmall.order.domain.po.OrderOutboxPO;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.po.OrderStockActionPO;
import com.cloudmall.order.domain.vo.OrderItemVO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.impl.OrderQueryServiceImpl;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.cloudmall.order.util.OrderJdbcLocalDateTime;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Properties;
import java.util.Set;
import java.util.TimeZone;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.ShardingDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DataSourceUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实模板、原Service/Mapper和4.1.1的列表、详情、恢复读取及回滚CAS隔离对照。 */
class OrderListShardingShadowMySqlIT {
  /** 三类模板/逻辑表的唯一白名单。 */
  private static final List<String> LOGICAL =
      List.of("mall_order", "mall_order_item", "order_status_log");

  /** 固定current=2026-12的完整滚动月份。 */
  private static final List<String> MONTHS = List.of("202611", "202612", "202701", "202702");

  /** 显式跨年半开窗口，不依赖执行当天。 */
  private static final String START = "2026-12-01T00:00:00+08:00";

  /** 排除二月第一毫秒。 */
  private static final String END = "2027-02-01T00:00:00+08:00";

  /** 原列表完整八列，和生产Mapper一致。 */
  private static final String COLUMNS =
      "order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,expire_at";

  /** 所有本测试写入显式声明的十二列。 */
  private static final String SEED_COLUMNS =
      "id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,paid_at,cancelled_at,created_at,updated_at";

  /** 恢复角色唯一允许额外克隆的真实非分片台账。 */
  private static final List<String> RECOVERY_TABLES =
      List.of("order_reservation_intent", "order_outbox", "order_stock_action");

  /** 一月倒序插入两条、十二月一条，完整声明的明细快照模型。 */
  private static final List<DetailSeed> DETAIL_SEEDS =
      List.of(
          new DetailSeed(
              12,
              1,
              "202701Z",
              102,
              202,
              "January second",
              "L",
              new BigDecimal("3.25"),
              2,
              new BigDecimal("6.50"),
              LocalDateTime.parse("2027-01-15T12:00:00.001")),
          new DetailSeed(
              11,
              1,
              "202701Z",
              101,
              201,
              "January first",
              "M",
              new BigDecimal("2.11"),
              1,
              new BigDecimal("2.11"),
              LocalDateTime.parse("2027-01-15T12:00:00.001")),
          new DetailSeed(
              21,
              4,
              "202612Z",
              103,
              203,
              "December control",
              "D",
              new BigDecimal("4.56"),
              3,
              new BigDecimal("13.68"),
              LocalDateTime.parse("2026-12-31T23:59:59.999")));

  /** 固定本地毫秒审计格式，不通过Instant重算月份。 */
  private static final DateTimeFormatter LOCAL_MILLIS =
      DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSS");

  /** 仅序列化安全fixture事实；不序列化异常或连接配置。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 声明的只读模型seed，不是真实下单或资金事实。 */
  private static final List<Seed> SEEDS =
      List.of(
          seed(
              1,
              "202701Z",
              7,
              "PENDING_PAYMENT",
              "12.34",
              "10.11",
              "jz",
              "2027-01-15T12:00:00.001"),
          seed(
              2,
              "202701A",
              7,
              "PENDING_PAYMENT",
              "23.45",
              "20.00",
              "ja",
              "2027-01-15T12:00:00.001"),
          seed(3, "202701P", 7, "PAID", "34.56", "30.01", "jp", "2027-01-10T08:00:00.000"),
          seed(
              4,
              "202612Z",
              7,
              "PENDING_PAYMENT",
              "45.67",
              "40.02",
              "dz",
              "2026-12-31T23:59:59.999"),
          seed(5, "202612C", 7, "CANCELLED", "56.78", "50.03", "dc", "2026-12-20T08:00:00.000"),
          seed(
              6,
              "202612A",
              7,
              "PENDING_PAYMENT",
              "67.89",
              "60.04",
              "da",
              "2026-12-01T00:00:00.000"),
          seed(
              7,
              "202701U",
              8,
              "PENDING_PAYMENT",
              "78.90",
              "70.05",
              "u8",
              "2027-01-31T08:00:00.000"),
          seed(
              8,
              "202611X",
              7,
              "PENDING_PAYMENT",
              "89.01",
              "80.06",
              "nov",
              "2026-11-30T23:59:59.999"),
          seed(
              9,
              "202702X",
              7,
              "PENDING_PAYMENT",
              "90.12",
              "90.07",
              "feb",
              "2027-02-01T00:00:00.000"));

  /** 并发路径唯一允许的额外INSERT，排序靠前，所有字段执行前已声明。 */
  private static final Seed WRITER =
      seed(
          99,
          "202701W",
          7,
          "PENDING_PAYMENT",
          "99.99",
          "91.08",
          "writer",
          "2027-01-31T23:59:59.999");

  /** 原库连接，只用于catalog/模板/checksum与精确CREATE/DROP新库。 */
  private static GuardedDataSource sourceDataSource;

  /** 原库只读审计入口和新库生命周期DDL。 */
  private static JdbcTemplate source;

  /** 已验证的输入URL，永不写入日志或原生审计。 */
  private static String sourceUrl;

  /** 原Order起始表清单/checksum，无源业务行。 */
  private static Map<String, String> sourceBefore;

  /** 三类源BASE TABLE定义指纹，不读取源行。 */
  private static Map<String, String> templateDefinitions;

  /** 每次类执行的唯一原生审计目录。 */
  private static Path auditRoot;

  /** 创建成功的精确随机数据库名单，不以期望数量推断归属。 */
  private static final Set<String> CREATED = Collections.synchronizedSet(new TreeSet<>());

  /** 已确认实际删除的精确名单。 */
  private static final Set<String> DELETED = Collections.synchronizedSet(new TreeSet<>());

  @BeforeAll
  static void establishSourceAndAudit() throws Exception {
    assertEquals(ZoneId.of("Asia/Shanghai"), ZoneId.systemDefault(), "JVM需显式上海时区");
    assertCleanThread();
    String runId = Instant.now().toEpochMilli() + "-" + UUID.randomUUID();
    auditRoot = Path.of("target", "cm17ed-list-shadow", runId).toAbsolutePath();
    Files.createDirectories(auditRoot.getParent());
    Files.createDirectory(auditRoot);
    write(
        auditRoot.resolve("class-start.json"),
        Map.of(
            "runId",
            runId,
            "startedAt",
            Instant.now().toString(),
            "fixtureOnly",
            true,
            "review",
            "17EF"));
    System.out.println("CM17ED_LIST_SHADOW runId=" + runId + " startedAt=" + Instant.now());
    sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/?#]+/cloudmall_order(\\?[^#]*)?"))
      throw new IllegalStateException("CM17ED_SOURCE_URL_REQUIRES_ORDER_CATALOG");
    sourceDataSource = new GuardedDataSource(boundedUrl(sourceUrl), "cloudmall_order");
    source = jdbc(sourceDataSource);
    sourceBefore = sourceFingerprint();
    write(auditRoot.resolve("source-before.json"), sourceBefore);
    templateDefinitions = new TreeMap<>();
    for (String logical : LOGICAL) {
      String template = logical + "_template";
      assertEquals(
          List.of("BASE TABLE"),
          source.queryForList(
              "SELECT TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND"
                  + " TABLE_NAME=?",
              String.class,
              template));
      templateDefinitions.put(template, definition(source, template));
    }
    write(auditRoot.resolve("template-definitions.json"), templateDefinitions);
  }

  @AfterAll
  static void finishSourceAudit() throws Exception {
    List<Throwable> errors = new ArrayList<>();
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("created", new ArrayList<>(CREATED));
    result.put("deleted", new ArrayList<>(DELETED));
    Set<String> preserved = new TreeSet<>(CREATED);
    preserved.removeAll(DELETED);
    result.put("preserveRequired", preserved);
    attempt(
        errors,
        () -> {
          if (sourceBefore != null) {
            Map<String, String> after = sourceFingerprint();
            result.put("sourceAfter", after);
            assertEquals(sourceBefore, after);
          }
          if (sourceDataSource != null) sourceDataSource.assertClosedConnections();
          assertCleanThread();
          assertTrue(preserved.isEmpty(), "未全清，保留原生审计中的精确owned库");
        });
    result.put("errors", safeErrors(errors));
    if (auditRoot != null)
      attempt(errors, () -> write(auditRoot.resolve("class-finish.json"), result));
    throwIfErrors(errors);
  }

  @Test
  void logicalListMatchesPhysicalListAcrossMonthsAndFilters() throws Exception {
    try (Run run = new Run("logicalListMatchesPhysicalListAcrossMonthsAndFilters")) {
      Fixture fixture = run.fixture("static");
      fixture.beforeReads();
      verifyBoth(
          fixture, null, 1, 4, 6, List.of(SEEDS.get(0), SEEDS.get(1), SEEDS.get(2), SEEDS.get(3)));
      verifyBoth(fixture, null, 2, 4, 6, List.of(SEEDS.get(4), SEEDS.get(5)));
      verifyBoth(
          fixture, "PENDING_PAYMENT", 1, 3, 4, List.of(SEEDS.get(0), SEEDS.get(1), SEEDS.get(3)));
      verifyBoth(fixture, "PENDING_PAYMENT", 2, 3, 4, List.of(SEEDS.get(5)));
      fixture.afterReads();
      run.assertionsPassed = true;
    }
  }

  @Test
  void repeatableReadKeepsCountAndPageStableAcrossConcurrentCommit() throws Exception {
    try (Run run = new Run("repeatableReadKeepsCountAndPageStableAcrossConcurrentCommit")) {
      Fixture physical = run.fixture("physical-rr");
      Fixture logical = run.fixture("logical-rr");
      physical.beforeReads();
      logical.beforeReads();
      concurrentRead(physical, false);
      concurrentRead(logical, true);
      physical.afterReads();
      logical.afterReads();
      run.assertionsPassed = true;
    }
  }

  @Test
  void normalizedEmptyWindowExecutesNoOrderSql() throws Exception {
    try (Run run = new Run("normalizedEmptyWindowExecutesNoOrderSql")) {
      Fixture fixture = run.fixture("empty");
      fixture.beforeReads();
      String start = "2026-12-31T23:59:59.999000001+08:00";
      String end = "2027-01-01T00:00:00+08:00";
      assertTrue(window(start, end).isEmpty());
      fixture.base.resetBusinessSql();
      Page physical = fixture.physical(null, 1, 20, start, end, null);
      fixture.base.endBusinessSql();
      assertEquals(new Page("0", 0, 1, 20, List.of()), physical);
      assertEquals(0, fixture.base.businessSql.get());
      write(fixture.path.resolve("empty-physical.json"), fixture.base.sqlAudit());
      fixture.base.resetBusinessSql();
      Page logical = fixture.logical(null, 1, 20, start, end, null);
      fixture.base.endBusinessSql();
      assertEquals(physical, logical);
      assertEquals(0, fixture.base.businessSql.get());
      write(fixture.path.resolve("empty-logical.json"), fixture.base.sqlAudit());
      fixture.afterReads();
      run.assertionsPassed = true;
    }
  }

  @Test
  void missingPhysicalMonthFailsWholeListWithoutPartialResult() throws Exception {
    try (Run run = new Run("missingPhysicalMonthFailsWholeListWithoutPartialResult")) {
      Fixture fixture = run.fixture("missing");
      fixture.beforeReads();
      fixture.plannedDrop = "mall_order_202701";
      write(
          fixture.path.resolve("allowed-drop.json"),
          Map.of("table", fixture.plannedDrop, "rulesAlreadyCreated", true));
      fixture.sql.execute("DROP TABLE " + fixture.plannedDrop);
      fixture.dropped = true;
      BizException physical =
          assertThrows(BizException.class, () -> fixture.physical(null, 1, 4, START, END, null));
      assertEquals("ORDER_QUERY_SHARD_UNAVAILABLE", physical.getCode());
      assertEquals(503, physical.getHttpStatus());
      RuntimeException logical =
          assertThrows(RuntimeException.class, () -> fixture.logical(null, 1, 4, START, END, null));
      assertTrue(isMissingTarget(logical, fixture.plannedDrop), "必须是原目标表缺失，而非任意框架错误");
      write(
          fixture.path.resolve("missing-result.json"),
          Map.of(
              "physicalCode",
              physical.getCode(),
              "physicalHttp",
              physical.getHttpStatus(),
              "logicalMissingTargetProven",
              true,
              "logicalError",
              safeError(logical),
              "noPartialPageReturned",
              true));
      fixture.afterReads();
      run.assertionsPassed = true;
    }
  }

  @Test
  void logicalOrderDetailMatchesPhysicalMainAndItems() throws Exception {
    try (Run run = new Run("logicalOrderDetailMatchesPhysicalMainAndItems")) {
      Fixture fixture = run.fixture("detail", Role.DETAIL);
      fixture.beforeReads();
      for (Seed parent : List.of(SEEDS.get(0), SEEDS.get(3))) {
        Detail golden =
            new Detail(
                parent.item(),
                detailRowsFor(parent.orderNo).stream().map(DetailSeed::line).toList());
        Detail physical = fixture.physicalDetail(parent.orderNo);
        Detail logical = fixture.logicalDetail(parent.orderNo);
        assertEquals(golden, physical);
        assertEquals(golden, logical);
        write(
            fixture.path.resolve("detail-" + parent.orderNo + ".json"),
            Map.of("golden", golden, "physical", physical, "logical", logical));
      }
      fixture.afterReads();
      run.assertionsPassed = true;
    } catch (Exception | Error failure) {
      recordMethodFailure("logicalOrderDetailMatchesPhysicalMainAndItems", failure);
      throw failure;
    }
  }

  @Test
  void logicalRecoveryReadsMatchPhysicalCandidatesAndIdentity() throws Exception {
    try (Run run = new Run("logicalRecoveryReadsMatchPhysicalCandidatesAndIdentity")) {
      Fixture fixture = run.fixture("recovery", Role.RECOVERY);
      fixture.beforeReads();
      fixture.recoveryReads(false);
      fixture.recoveryReads(true);
      String orderNo = SEEDS.get(0).orderNo;
      OrderSqlMapper physical = new OrderSqlMapper(fixture.sql);
      assertTrue(physical.orderExists(SEEDS.get(0).table(), orderNo));
      assertTrue(fixture.logicalExists(orderNo));
      assertFalse(physical.orderExists(SEEDS.get(0).table(), "202701MISSING"));
      assertFalse(fixture.logicalExists("202701MISSING"));
      String alias = orderNo.toLowerCase(java.util.Locale.ROOT);
      assertNotEquals(orderNo, alias);
      assertThrows(
          IllegalStateException.class, () -> physical.orderExists(SEEDS.get(0).table(), alias));
      assertThrows(IllegalStateException.class, () -> fixture.logicalExists(alias));
      write(
          fixture.path.resolve("existence-identity.json"),
          Map.of("original", true, "genuineMissing", false, "collationAliasRejected", true));
      fixture.plannedDrop = SEEDS.get(0).table();
      write(
          fixture.path.resolve("allowed-drop.json"),
          Map.of("table", fixture.plannedDrop, "rulesAlreadyCreated", true));
      fixture.sql.execute("DROP TABLE " + fixture.plannedDrop);
      fixture.dropped = true;
      RuntimeException missingPhysical =
          assertThrows(
              RuntimeException.class, () -> physical.orderExists(fixture.plannedDrop, orderNo));
      RuntimeException missingLogical =
          assertThrows(RuntimeException.class, () -> fixture.logicalExists(orderNo));
      assertTrue(isMissingTarget(missingPhysical, fixture.plannedDrop));
      assertTrue(isMissingTarget(missingLogical, fixture.plannedDrop));
      write(
          fixture.path.resolve("recovery-missing-result.json"),
          Map.of(
              "physical",
              safeError(missingPhysical),
              "logical",
              safeError(missingLogical),
              "target",
              fixture.plannedDrop,
              "notFalseOrEmpty",
              true));
      fixture.afterReads();
      run.assertionsPassed = true;
    } catch (Exception | Error failure) {
      recordMethodFailure("logicalRecoveryReadsMatchPhysicalCandidatesAndIdentity", failure);
      throw failure;
    }
  }

  @Test
  void logicalStatusCasMatchesPhysicalCountsAndRollsBack() throws Exception {
    try (Run run = new Run("logicalStatusCasMatchesPhysicalCountsAndRollsBack")) {
      Fixture fixture = run.fixture("cas", Role.CAS);
      fixture.beforeReads();
      LocalDateTime updatedAt = LocalDateTime.parse("2027-01-16T09:10:11.123");
      fixture.casAndRollback(false, updatedAt);
      assertEquals(fixture.initial, fixture.facts());
      fixture.casAndRollback(true, updatedAt);
      assertEquals(fixture.initial, fixture.facts());
      fixture.afterReads();
      run.assertionsPassed = true;
    } catch (Exception | Error failure) {
      recordMethodFailure("logicalStatusCasMatchesPhysicalCountsAndRollsBack", failure);
      throw failure;
    }
  }

  @Test
  void logicalRecoveryDateTimeComponentsMatchPhysicalAcrossTimeZones() throws Exception {
    try (Run run = new Run("logicalRecoveryDateTimeComponentsMatchPhysicalAcrossTimeZones")) {
      Fixture fixture = run.fixture("components", Role.COMPONENTS);
      fixture.beforeReads();
      TimeZone original = TimeZone.getDefault();
      try {
        for (String zone : List.of("Asia/Shanghai", "UTC")) {
          assertCleanThread();
          fixture.base.assertClosedConnections();
          assertEquals(0, fixture.activeTransactions.get());
          TimeZone.setDefault(TimeZone.getTimeZone(zone));
          assertEquals(zone, ZoneId.systemDefault().getId());
          fixture.componentReads(false, zone);
          fixture.componentReads(true, zone);
          fixture.base.assertClosedConnections();
          assertEquals(0, fixture.activeTransactions.get());
          assertCleanThread();
        }
      } finally {
        TimeZone.setDefault(original);
      }
      fixture.afterReads();
      run.assertionsPassed = true;
    } catch (Exception | Error failure) {
      recordMethodFailure("logicalRecoveryDateTimeComponentsMatchPhysicalAcrossTimeZones", failure);
      throw failure;
    }
  }

  /** 两条实际路径分别对显式golden断言，不将双方相同当作唯一正确性依据。 */
  private static void verifyBoth(
      Fixture fixture, String status, int page, int size, long total, List<Seed> expected)
      throws Exception {
    Page golden = golden(total, page, size, expected);
    fixture.base.resetBusinessSql();
    Page physical;
    try {
      physical = fixture.physical(status, page, size, START, END, null);
    } finally {
      fixture.base.endBusinessSql();
    }
    assertTrue(fixture.base.businessSql.get() > 0);
    assertTrue(fixture.base.sqlKinds.contains("COUNT"));
    assertTrue(fixture.base.sqlKinds.contains("PAGE_OR_ORDER_READ"));
    Map<String, Object> physicalSql = fixture.base.sqlAudit();
    fixture.base.resetBusinessSql();
    Page logical;
    try {
      logical = fixture.logical(status, page, size, START, END, null);
    } finally {
      fixture.base.endBusinessSql();
    }
    assertTrue(fixture.base.businessSql.get() > 0);
    assertTrue(fixture.base.sqlKinds.contains("COUNT"));
    assertTrue(fixture.base.sqlKinds.contains("PAGE_OR_ORDER_READ"));
    assertEquals(golden, physical);
    assertEquals(golden, logical);
    write(
        fixture.path.resolve("page-" + (status == null ? "all" : status) + "-" + page + ".json"),
        Map.of(
            "physical",
            physical,
            "logical",
            logical,
            "golden",
            golden,
            "physicalSql",
            physicalSql,
            "logicalSql",
            fixture.base.sqlAudit()));
  }

  /** COUNT真实完成后暂停reader，writer独立commit且新连接可见后才释放；不使用sleep。 */
  private static void concurrentRead(Fixture fixture, boolean logical) throws Exception {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(45);
    fixture.base.resetBusinessSql();
    Barrier barrier = new Barrier(fixture.base);
    ExecutorService executor = Executors.newFixedThreadPool(2);
    List<Future<?>> futures = new ArrayList<>();
    fixture.workersSettled = false;
    Throwable original = null;
    try {
      Future<Page> reader =
          executor.submit(
              () ->
                  logical
                      ? fixture.logical(null, 1, 4, START, END, barrier)
                      : fixture.physical(null, 1, 4, START, END, barrier));
      futures.add(reader);
      Future<?> writer =
          executor.submit(
              () -> {
                try (ThreadScope scope = new ThreadScope(fixture, false)) {
                  assertTrue(barrier.counted.await(20, TimeUnit.SECONDS), "COUNT屏障未到达");
                  try (Connection connection = fixture.base.getConnection()) {
                    connection.setAutoCommit(false);
                    try {
                      insert(connection, WRITER);
                      connection.commit();
                    } catch (Exception | Error failure) {
                      try {
                        connection.rollback();
                      } catch (Exception cleanup) {
                        failure.addSuppressed(cleanup);
                      }
                      throw failure;
                    }
                  }
                  assertEquals(
                      1,
                      fixture.sql.queryForObject(
                          "SELECT COUNT(*) FROM mall_order_202701 WHERE order_no=?",
                          Integer.class,
                          WRITER.orderNo));
                  fixture.writerApplied = true;
                  write(
                      fixture.path.resolve("writer-committed.json"),
                      Map.of(
                          "declaredRow",
                          WRITER.tuple(),
                          "commitReturned",
                          true,
                          "independentConnectionVisible",
                          true));
                  barrier.committed.countDown();
                  return null;
                }
              });
      futures.add(writer);
      get(writer, deadline);
      Page snapshot = get(reader, deadline);
      assertEquals(
          golden(6, 1, 4, List.of(SEEDS.get(0), SEEDS.get(1), SEEDS.get(2), SEEDS.get(3))),
          snapshot);
      Page fresh =
          logical
              ? fixture.logical(null, 1, 4, START, END, null)
              : fixture.physical(null, 1, 4, START, END, null);
      assertEquals(
          golden(7, 1, 4, List.of(WRITER, SEEDS.get(0), SEEDS.get(1), SEEDS.get(2))), fresh);
      write(
          fixture.path.resolve("rr-result.json"),
          Map.of(
              "path",
              logical ? "logical" : "physical",
              "snapshot",
              snapshot,
              "freshTransaction",
              fresh,
              "barrierAfterFullCount",
              barrier.observed.get(),
              "sqlAtBarrier",
              barrier.sqlAtBarrier,
              "elapsedMillis",
              TimeUnit.NANOSECONDS.toMillis(
                  System.nanoTime() - deadline + TimeUnit.SECONDS.toNanos(45))));
    } catch (Exception | Error failure) {
      original = failure;
      throw failure;
    } finally {
      fixture.base.endBusinessSql();
      barrier.committed.countDown();
      barrier.counted.countDown();
      List<Throwable> cleanup = new ArrayList<>();
      for (Future<?> future : futures) {
        attempt(
            cleanup,
            () -> {
              if (!future.isDone()) get(future, deadline);
              else {
                try {
                  future.get();
                } catch (ExecutionException completedFailure) {
                  throw completedFailure;
                }
              }
            });
      }
      executor.shutdown();
      try {
        if (!executor.awaitTermination(
            Math.max(
                1, Math.min(5000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()))),
            TimeUnit.MILLISECONDS)) {
          executor.shutdownNow();
          cleanup.add(new IllegalStateException("CM17ED_EXECUTOR_EXIT_UNPROVEN"));
        }
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        cleanup.add(failure);
        executor.shutdownNow();
      }
      fixture.workersSettled = executor.isTerminated() && futures.stream().allMatch(Future::isDone);
      attempt(
          cleanup,
          () -> {
            assertTrue(fixture.workersSettled);
            assertEquals(0, fixture.activeTransactions.get());
            fixture.base.assertClosedConnections();
            assertTrue(System.nanoTime() <= deadline, "单路并发超过45秒");
          });
      attempt(
          cleanup,
          () ->
              write(
                  fixture.path.resolve("workers-ended.json"),
                  Map.of(
                      "executorTerminated",
                      executor.isTerminated(),
                      "futuresDone",
                      futures.stream().allMatch(Future::isDone),
                      "errors",
                      safeErrors(cleanup))));
      if (!cleanup.isEmpty()) {
        if (original != null) cleanup.forEach(original::addSuppressed);
        else throwIfErrors(cleanup);
      }
    }
  }

  /** 每次Future等待最多20秒，并受单路45秒总截止时间限制。 */
  private static <T> T get(Future<T> future, long deadline) throws Exception {
    long remaining = deadline - System.nanoTime();
    if (remaining <= 0) throw new IllegalStateException("CM17ED_CONCURRENT_DEADLINE");
    return future.get(Math.min(TimeUnit.SECONDS.toNanos(20), remaining), TimeUnit.NANOSECONDS);
  }

  /** 显式fixture角色，旧四组默认只允许LIST模型。 */
  private enum Role {
    LIST,
    DETAIL,
    RECOVERY,
    CAS,
    COMPONENTS
  }

  /** 测试方法收尾；只有所有断言及资源/facts门均通过，才允许精确删除已创建库。 */
  private static final class Run implements AutoCloseable {
    /** 当前方法的唯一子目录。 */
    private final Path path;

    /** 该方法真正尝试创建的fixture，不按预期数量扩清。 */
    private final List<Fixture> fixtures = new ArrayList<>();

    /** 仅在最后一个业务断言通过后赋true。 */
    private boolean assertionsPassed;

    Run(String method) throws Exception {
      assertCleanThread();
      path = auditRoot.resolve(method);
      Files.createDirectory(path);
      write(
          path.resolve("method-start.json"),
          Map.of("method", method, "startedAt", Instant.now().toString()));
    }

    Fixture fixture(String label) throws Exception {
      return fixture(label, Role.LIST);
    }

    Fixture fixture(String label, Role role) throws Exception {
      Fixture fixture = new Fixture(path.resolve(label), role);
      fixtures.add(fixture);
      fixture.prepare();
      return fixture;
    }

    /** 独立尽力close和审计；失败保留并以suppressed跟随原异常，绝不finally无条件DROP。 */
    @Override
    public void close() throws Exception {
      // 1. 先对所有fixture尽力关闭影子，再分别核连接、事务和线程，审计IO失败不得跳过close。
      List<Throwable> errors = new ArrayList<>();
      for (Fixture fixture : fixtures) attempt(errors, fixture::closeShadow);
      for (Fixture fixture : fixtures) {
        attempt(
            errors,
            () -> {
              fixture.baseEndRead();
              assertTrue(fixture.workersSettled);
              assertEquals(0, fixture.activeTransactions.get());
              if (fixture.base != null) fixture.base.assertClosedConnections();
              assertCleanThread();
              if (fixture.initial != null) {
                Facts facts = fixture.facts();
                fixture.verifyFacts(facts);
                write(fixture.path.resolve("before-delete.json"), facts);
              } else throw new IllegalStateException("CM17ED_SETUP_INCOMPLETE_PRESERVE");
            });
      }
      attempt(
          errors,
          () -> {
            assertTrue(assertionsPassed, "业务/setup失败必须preserve");
            assertEquals(sourceBefore, sourceFingerprint());
            sourceDataSource.assertClosedConnections();
          });
      // 2. 全方法通过后，每个库删除前再次观测catalog、当前完整facts和本次created归属。
      if (errors.isEmpty()) {
        for (Fixture fixture : fixtures) {
          attempt(
              errors,
              () -> {
                assertTrue(CREATED.contains(fixture.database));
                assertTrue(fixture.created);
                assertEquals(
                    fixture.database,
                    fixture.sql.queryForObject("SELECT DATABASE()", String.class));
                Facts fresh = fixture.facts();
                fixture.verifyFacts(fresh);
                write(fixture.path.resolve("delete-gate.json"), fresh);
                fixture.base.assertClosedConnections();
                assertTrue(fixture.shadowClosed);
                assertTrue(fixture.workersSettled);
                assertEquals(0, fixture.activeTransactions.get());
                assertCleanThread();
                source.execute("DROP DATABASE " + fixture.database);
                assertEquals(
                    0,
                    source.queryForObject(
                        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                        Integer.class,
                        fixture.database));
                DELETED.add(fixture.database);
              });
          if (!errors.isEmpty()) break;
        }
      }
      // 3. 审计每个实际fixture的删除或保留，不因一处文件失败跳过其他收尾结果。
      for (Fixture fixture : fixtures)
        attempt(
            errors,
            () ->
                write(
                    fixture.path.resolve("cleanup.json"),
                    Map.of(
                        "database",
                        fixture.database,
                        "createdConfirmed",
                        fixture.created,
                        "deletedConfirmed",
                        DELETED.contains(fixture.database),
                        "preserveRequired",
                        !DELETED.contains(fixture.database),
                        "shadowCloseReturned",
                        fixture.shadowClosed,
                        "workersSettled",
                        fixture.workersSettled,
                        "errors",
                        safeErrors(errors))));
      attempt(
          errors,
          () ->
              write(
                  path.resolve("method-finish.json"),
                  Map.of(
                      "assertionsPassed",
                      assertionsPassed,
                      "finishedAt",
                      Instant.now().toString(),
                      "errors",
                      safeErrors(errors))));
      throwIfErrors(errors);
    }
  }

  /** 一个真实模板模型库及其资源；任何准备失败也已在Run清单中，禁止猜测删除。 */
  private static final class Fixture {
    /** 精确拥有的新数据库名。 */
    private final String database = "cm17ed_" + UUID.randomUUID().toString().replace("-", "");

    /** 当前fixture安全审计目录。 */
    private final Path path;

    /** 原LIST默认行为不变，新组只打开精确声明的角色。 */
    private final Role role;

    /** 当前角色使用的真实源定义指纹，恢复角色仅额外三张固定台账。 */
    private final Map<String, String> definitions = new TreeMap<>(templateDefinitions);

    /** 恢复组单次上海毫秒采样与完整声明行；其他角色为null。 */
    private final RecoveryModel recovery;

    /** 仅COMPONENTS角色的六个固定组件完整模型，其他角色为null。 */
    private final ComponentModel components;

    /** 真实物理透传DataSource。 */
    private GuardedDataSource base;

    /** fixture物理SQL入口。 */
    private JdbcTemplate sql;

    /** 唯一显式创建的影子DataSource。 */
    private ShardingDataSource shadow;

    /** 影子JdbcTemplate，与影子事务管理器引用同一DataSource。 */
    private JdbcTemplate routed;

    /** 初始完整事实，用于所有允许变化的独立核对。 */
    private Facts initial;

    /** CREATE返回且实际catalog存在才true。 */
    private boolean created;

    /** 原ShardingDataSource.close明确返回。 */
    private boolean shadowClosed;

    /** 两个自有Future及executor均已退出，非并发fixture默认无worker。 */
    private volatile boolean workersSettled = true;

    /** 实际活动的测试事务计数。 */
    private final AtomicInteger activeTransactions = new AtomicInteger();

    /** 唯一writer提交且外部新连接可见后才true。 */
    private volatile boolean writerApplied;

    /** 缺表场景事先声明的唯一DROP目标。 */
    private String plannedDrop;

    /** DROP明确返回后才true。 */
    private boolean dropped;

    /** 四个不委派远程替身，必须始终零交互。 */
    private final StockClient stock = mock(StockClient.class);

    /** 不委派Product。 */
    private final ProductClient product = mock(ProductClient.class);

    /** 不委派User。 */
    private final UserClient user = mock(UserClient.class);

    /** 不委派Cart。 */
    private final CartClient cart = mock(CartClient.class);

    /** 为每次真实事务保留实际观察，线程安全且不持有连接。 */
    private final List<Map<String, Object>> transactions =
        Collections.synchronizedList(new ArrayList<>());

    Fixture(Path path, Role role) {
      this.path = path;
      this.role = role;
      this.recovery = role == Role.RECOVERY ? new RecoveryModel() : null;
      this.components = role == Role.COMPONENTS ? new ComponentModel() : null;
    }

    void prepare() throws Exception {
      Files.createDirectory(path);
      write(
          path.resolve("ownership-plan.json"),
          Map.of(
              "database",
              database,
              "declaredSeeds",
              SEEDS.stream().map(Seed::tuple).toList(),
              "declaredWriter",
              role == Role.LIST ? WRITER.tuple() : Map.of(),
              "role",
              role.name(),
              "expectedTableCount",
              hasRecoveryTables() ? 18 : 15,
              "fixtureOnly",
              true));
      if (role == Role.DETAIL)
        write(
            path.resolve("detail-plan.json"),
            DETAIL_SEEDS.stream().map(DetailSeed::tuple).toList());
      if (role == Role.RECOVERY) write(path.resolve("recovery-plan.json"), recovery.plan());
      if (role == Role.COMPONENTS) write(path.resolve("component-plan.json"), components.plan());
      assertTrue(database.matches("cm17ed_[0-9a-f]{32}"));
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      source.execute(
          "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      CREATED.add(database);
      created = true;
      base =
          new GuardedDataSource(
              boundedUrl(sourceUrl.replace("/cloudmall_order", "/" + database)), database);
      sql = jdbc(base);
      assertEquals(database, sql.queryForObject("SELECT DATABASE()", String.class));
      for (String logical : LOGICAL) {
        String template = logical + "_template";
        sql.execute("CREATE TABLE " + template + " LIKE cloudmall_order." + template);
        assertEquals(templateDefinitions.get(template), definition(sql, template));
        assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM " + template, Integer.class));
      }
      if (hasRecoveryTables()) {
        for (String table : RECOVERY_TABLES) {
          assertEquals(
              List.of("BASE TABLE"),
              source.queryForList(
                  "SELECT TABLE_TYPE FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE()"
                      + " AND TABLE_NAME=?",
                  String.class,
                  table));
          definitions.put(table, definition(source, table));
          sql.execute("CREATE TABLE " + table + " LIKE cloudmall_order." + table);
          assertEquals(definitions.get(table), definition(sql, table));
          assertEquals(0, sql.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
        }
      }
      OrderSqlMapper mapper = new OrderSqlMapper(sql);
      for (String month : MONTHS) mapper.createMonthlyOrderTables(month);
      assertEquals(expectedFixtureTables(), tables(sql));
      try (Connection connection = base.getConnection()) {
        assertTrue(connection.getAutoCommit());
        for (Seed seed : SEEDS) insert(connection, seed);
      }
      if (role == Role.DETAIL) seedDetails(sql);
      if (role == Role.RECOVERY) recovery.seed(sql);
      if (role == Role.COMPONENTS) components.seed(sql);
      initial = facts();
      verifyFacts(initial);
      write(path.resolve("initial-facts.json"), initial);
      Map<String, Collection<String>> inventory = new LinkedHashMap<>();
      Set<String> observedTables = tables(sql);
      for (String logical : LOGICAL)
        inventory.put(
            logical,
            observedTables.stream().filter(table -> table.matches(logical + "_[0-9]{6}")).toList());
      assertTrue(
          tables(sql)
              .containsAll(inventory.values().stream().flatMap(Collection::stream).toList()));
      shadow =
          (ShardingDataSource)
              ShardingDataSourceFactory.createDataSource(
                  Map.of("ds0", base),
                  OrderShardingRuleFactory.create(YearMonth.of(2026, 12), inventory),
                  new Properties());
      routed = jdbc(shadow);
      write(
          path.resolve("rules-created.json"),
          Map.of(
              "currentMonth",
              "2026-12",
              "observedTables",
              tables(sql),
              "inventory",
              inventory,
              "templateDefinitions",
              templateDefinitions));
    }

    void beforeReads() throws Exception {
      Facts facts = facts();
      verifyFacts(facts);
      write(path.resolve("before-reads.json"), facts);
      write(
          path.resolve("window.json"),
          Map.of(
              "start",
              window(START, END).getStart().toString(),
              "end",
              window(START, END).getEnd().toString()));
    }

    void afterReads() throws Exception {
      Facts facts = facts();
      verifyFacts(facts);
      write(path.resolve("after-reads.json"), facts);
      verifyNoInteractions(stock, product, user, cart);
      write(path.resolve("transactions.json"), new ArrayList<>(transactions));
    }

    Page physical(String status, int page, int size, String start, String end, Barrier barrier) {
      OrderSqlMapper mapper =
          barrier == null ? new OrderSqlMapper(sql) : new BarrierMapper(sql, barrier);
      OrderQueryController service = new OrderQueryController(new OrderQueryServiceImpl(mapper));
      return transaction(base, sql, () -> project(service.list(status, page, size, start, end)));
    }

    Page logical(String status, int page, int size, String start, String end, Barrier barrier) {
      return transaction(
          shadow,
          routed,
          () -> {
            int normalizedPage = Math.max(1, page),
                normalizedSize = Math.min(Math.max(1, size), 100);
            OrderQueryWindow window = window(start, end);
            if (window.isEmpty())
              return new Page("0", 0, normalizedPage, normalizedSize, List.of());
            long offset = Math.multiplyExact((long) normalizedPage - 1, normalizedSize);
            String condition = " WHERE user_id=? AND created_at>=? AND created_at<?";
            List<Object> arguments =
                new ArrayList<>(
                    List.of(
                        AuthContext.requireUserId(),
                        Timestamp.valueOf(window.getStart().toLocalDateTime()),
                        Timestamp.valueOf(window.getEnd().toLocalDateTime())));
            if (status != null) {
              condition += " AND status=?";
              arguments.add(status);
            }
            Long total =
                routed.queryForObject(
                    "SELECT COUNT(*) FROM mall_order" + condition, Long.class, arguments.toArray());
            assertNotNull(total);
            if (barrier != null) barrier.afterCount();
            if (offset >= total)
              return new Page("0", total, normalizedPage, normalizedSize, List.of());
            arguments.add(normalizedSize);
            arguments.add(offset);
            List<Map<String, Object>> rows =
                routed.queryForList(
                    "SELECT "
                        + COLUMNS
                        + " FROM mall_order"
                        + condition
                        + " ORDER BY created_at DESC,order_no DESC LIMIT ? OFFSET ?",
                    arguments.toArray());
            return new Page(
                "0",
                total,
                normalizedPage,
                normalizedSize,
                rows.stream().map(OrderListShardingShadowMySqlIT::project).toList());
          });
    }

    /** 原get本身无事务；此处RR只属于比较harness，不扩大API快照保证。 */
    Detail physicalDetail(String orderNo) {
      assertEquals(Role.DETAIL, role);
      OrderServiceImpl service =
          new OrderServiceImpl(
              stock, product, user, cart, new OrderSqlMapper(sql), new ObjectMapper());
      return transaction(
          base,
          sql,
          () -> {
            ApiResponse<?> response = service.get(orderNo);
            assertEquals("0", response.code);
            OrderVO order = (OrderVO) response.data;
            Item main =
                new Item(
                    order.orderNo,
                    order.userId,
                    order.status,
                    money(order.totalAmount),
                    money(order.payAmount),
                    order.addressSnapshot,
                    apiLocal(order.createdAt),
                    apiLocal(order.expireAt));
            return new Detail(
                main,
                order.items.stream().map(OrderListShardingShadowMySqlIT::detailLine).toList());
          });
    }

    /** 只按原订单号前缀推导半开原月，主表与明细均携带同一范围。 */
    Detail logicalDetail(String orderNo) {
      assertEquals(Role.DETAIL, role);
      return transaction(
          shadow,
          routed,
          () -> {
            List<Map<String, Object>> rows =
                routed.queryForList(
                    "SELECT "
                        + COLUMNS
                        + " FROM mall_order WHERE order_no=? AND created_at>=? AND created_at<?",
                    monthArguments(orderNo));
            if (rows.isEmpty() || !Objects.equals(orderNo, rows.get(0).get("order_no")))
              throw new BizException(ErrorCodes.NOT_FOUND, "订单不存在", 404);
            Item main = project(rows.get(0));
            if (main.userId != AuthContext.requireUserId())
              throw new BizException(ErrorCodes.FORBIDDEN, "无权访问此订单", 403);
            List<Line> items =
                routed
                    .queryForList(
                        "SELECT"
                            + " product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount"
                            + " FROM mall_order_item WHERE order_no=? AND created_at>=? AND"
                            + " created_at<? ORDER BY id",
                        monthArguments(orderNo))
                    .stream()
                    .map(OrderListShardingShadowMySqlIT::detailLine)
                    .toList();
            return new Detail(main, items);
          });
    }

    /** 保留原collation候选，再执行原Mapper的唯一及Java精确身份拒绝。 */
    boolean logicalExists(String orderNo) {
      return transaction(
          shadow,
          routed,
          () -> {
            List<Map<String, Object>> rows =
                routed.queryForList(
                    "SELECT order_no FROM mall_order WHERE order_no=? AND created_at>=? AND"
                        + " created_at<?",
                    monthArguments(orderNo));
            if (rows.isEmpty()) return false;
            if (rows.size() != 1
                || !(rows.get(0).get("order_no") instanceof String)
                || !Objects.equals(orderNo, rows.get(0).get("order_no")))
              throw new IllegalStateException("订单存在性候选身份与原意图不一致");
            return true;
          });
    }

    /** 三个生产Mapper连原SQL/RowMapper透传；不做claim、恢复、时钟替换或默认DS补丁。 */
    void recoveryReads(boolean logical) throws Exception {
      assertEquals(Role.RECOVERY, role);
      JdbcTemplate reader = logical ? routed : sql;
      List<Map<String, Object>> observations =
          transaction(
              logical ? shadow : base,
              reader,
              () -> {
                OrderReservationIntentMapper intents = new OrderReservationIntentMapper(reader);
                OrderOutboxMapper outbox = new OrderOutboxMapper(reader);
                OrderStockActionMapper actions = new OrderStockActionMapper(reader);
                List<Map<String, Object>> observed = new ArrayList<>();
                for (int limit : List.of(1, 2)) {
                  List<Map<String, Object>> actualIntents =
                      intents.findRecoverable(recovery.staleBefore, recovery.now, limit).stream()
                          .map(OrderListShardingShadowMySqlIT::intentProjection)
                          .toList();
                  LocalDateTime beforeOutbox = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                  List<Map<String, Object>> actualOutbox =
                      outbox.findPendingOutbox(limit).stream()
                          .map(OrderListShardingShadowMySqlIT::outboxProjection)
                          .toList();
                  LocalDateTime afterOutbox = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                  List<Map<String, Object>> actualActions =
                      actions.findDue(recovery.now, limit).stream()
                          .map(OrderListShardingShadowMySqlIT::actionProjection)
                          .toList();
                  assertEquals(
                      recovery.rows("order_reservation_intent").subList(0, limit), actualIntents);
                  assertEquals(recovery.rows("order_outbox").subList(0, limit), actualOutbox);
                  List<Map<String, Object>> actionGolden =
                      recovery.rows("order_stock_action").subList(0, limit).stream()
                          .map(
                              row -> {
                                Map<String, Object> projected = new LinkedHashMap<>();
                                for (String column :
                                    List.of(
                                        "id",
                                        "order_no",
                                        "action_type",
                                        "operation_key",
                                        "status",
                                        "attempts",
                                        "next_retry_at")) projected.put(column, row.get(column));
                                return projected;
                              })
                          .toList();
                  assertEquals(actionGolden, actualActions);
                  assertEquals("PENDING", actions.statusForOrder("202701Z", "CONFIRM"));
                  assertEquals("UNKNOWN", actions.statusForOrder("202612Z", "ROLLBACK"));
                  assertTrue(beforeOutbox.isBefore(recovery.now.plusDays(1)));
                  assertTrue(afterOutbox.isBefore(recovery.now.plusDays(1)));
                  assertFalse(afterOutbox.isBefore(beforeOutbox));
                  observed.add(
                      Map.of(
                          "limit",
                          limit,
                          "intent",
                          actualIntents,
                          "outbox",
                          actualOutbox,
                          "action",
                          actualActions,
                          "outboxInvocationBefore",
                          beforeOutbox.toString(),
                          "outboxInvocationAfter",
                          afterOutbox.toString(),
                          "outboxOriginalClock",
                          true,
                          "actionStatuses",
                          List.of("PENDING", "UNKNOWN")));
                }
                return observed;
              });
      write(path.resolve("recovery-" + (logical ? "logical" : "physical") + ".json"), observations);
    }

    /** 固定六值在原生产Mapper中按物理/真实shadow读取；每路独立事务退出后才切换时区。 */
    void componentReads(boolean logical, String zone) throws Exception {
      assertEquals(Role.COMPONENTS, role);
      DataSource dataSource = logical ? shadow : base;
      ComponentJdbcTemplate reader = new ComponentJdbcTemplate(dataSource);
      String invocationBefore = Instant.now().toString();
      Map<String, Object> observed =
          transaction(
              dataSource,
              reader,
              () -> {
                OrderReservationIntentMapper intentMapper =
                    new OrderReservationIntentMapper(reader);
                OrderOutboxMapper outboxMapper = new OrderOutboxMapper(reader);
                OrderStockActionMapper actionMapper = new OrderStockActionMapper(reader);
                List<Map<String, Object>> actualIntents = new ArrayList<>();
                for (int index = 0; index < 6; index++) {
                  List<OrderReservationIntentPO> candidates =
                      intentMapper.findByUserAndKey(7L, "component-key-" + (index + 1));
                  assertEquals(1, candidates.size());
                  actualIntents.add(intentProjection(candidates.get(0)));
                }
                LocalDateTime outboxBefore = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                List<Map<String, Object>> actualOutbox =
                    outboxMapper.findPendingOutbox(6).stream()
                        .map(OrderListShardingShadowMySqlIT::outboxProjection)
                        .toList();
                LocalDateTime outboxAfter = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
                List<Map<String, Object>> actualActions =
                    actionMapper.findDue(LocalDateTime.parse("9999-12-31T23:59:59.999"), 6).stream()
                        .map(OrderListShardingShadowMySqlIT::actionProjection)
                        .toList();
                List<Map<String, Object>> expectedActions =
                    components.rows("order_stock_action").stream()
                        .map(
                            row ->
                                Map.of(
                                    "id",
                                    row.get("id"),
                                    "order_no",
                                    row.get("order_no"),
                                    "action_type",
                                    row.get("action_type"),
                                    "operation_key",
                                    row.get("operation_key"),
                                    "status",
                                    row.get("status"),
                                    "attempts",
                                    row.get("attempts"),
                                    "next_retry_at",
                                    row.get("next_retry_at")))
                        .toList();
                assertEquals(components.rows("order_reservation_intent"), actualIntents);
                assertEquals(components.rows("order_outbox"), actualOutbox);
                assertEquals(expectedActions, actualActions);
                List<Map<String, Object>> publications = new ArrayList<>();
                List<Map<String, Object>> expectedPublications = new ArrayList<>();
                for (Map<String, Object> expected : components.rows("order_outbox")) {
                  List<OrderOutboxPO> facts =
                      outboxMapper.findPublicationFacts((String) expected.get("event_id"));
                  assertEquals(1, facts.size());
                  OrderOutboxPO fact = facts.get(0);
                  assertEquals(Integer.valueOf(0), fact.attempts);
                  publications.add(
                      canonicalRow(
                          Map.of(
                              "event_id",
                              fact.eventId,
                              "status",
                              fact.status,
                              "event_type",
                              fact.eventType,
                              "order_no",
                              fact.orderNo,
                              "attempts",
                              fact.attempts)));
                  expectedPublications.add(
                      Map.of(
                          "event_id",
                          expected.get("event_id"),
                          "status",
                          expected.get("status"),
                          "event_type",
                          expected.get("event_type"),
                          "order_no",
                          expected.get("order_no"),
                          "attempts",
                          0L));
                }
                assertEquals(expectedPublications, publications);
                assertEquals(8, reader.metadata.size());
                assertFalse(outboxAfter.isBefore(outboxBefore));
                return Map.of(
                    "expected",
                    Map.of(
                        "intent",
                        components.rows("order_reservation_intent"),
                        "outbox",
                        components.rows("order_outbox"),
                        "action",
                        expectedActions,
                        "publicationFacts",
                        expectedPublications),
                    "observed",
                    Map.of(
                        "intent",
                        actualIntents,
                        "outbox",
                        actualOutbox,
                        "action",
                        actualActions,
                        "publicationFacts",
                        publications),
                    "outboxInvocationBefore",
                    outboxBefore.toString(),
                    "outboxInvocationAfter",
                    outboxAfter.toString(),
                    "outboxOriginalClock",
                    true,
                    "actualProjectionMetadata",
                    reader.metadata);
              });
      assertEquals(zone, ZoneId.systemDefault().getId());
      base.assertClosedConnections();
      assertEquals(0, activeTransactions.get());
      assertCleanThread();
      write(
          path.resolve(
              "components-"
                  + zone.replace('/', '_')
                  + "-"
                  + (logical ? "logical" : "physical")
                  + ".json"),
          Map.of(
              "jvmZone",
              zone,
              "connectionParameters",
              Map.of("serverTimezone", "Asia/Shanghai", "forceConnectionTimeZoneToSession", true),
              "path",
              logical ? "logical" : "physical",
              "invocationBefore",
              invocationBefore,
              "invocationAfter",
              Instant.now().toString(),
              "result",
              observed,
              "transactionAndConnectionsClosed",
              true));
    }

    /** 新角色完整事实也在MySQL先转字符，避免审计自身引入1582年Timestamp转换。 */
    private List<Map<String, Object>> componentFactRows(String table) {
      assertEquals(Role.COMPONENTS, role);
      return sql.query(
          components.factSql(table),
          (resultSet, rowNumber) -> {
            Map<String, Object> row = new LinkedHashMap<>();
            ResultSetMetaData metadata = resultSet.getMetaData();
            for (int index = 1; index <= metadata.getColumnCount(); index++) {
              String label = metadata.getColumnLabel(index);
              if (label.startsWith("cm_local_"))
                row.put(
                    label.substring("cm_local_".length()),
                    OrderJdbcLocalDateTime.readNullable(resultSet, label));
              else row.put(label, resultSet.getObject(index));
            }
            return canonicalRow(row);
          });
    }

    /** 两次CAS及完整行读取均在同一绑定连接中；本地事务始终rollbackOnly。 */
    void casAndRollback(boolean logical, LocalDateTime updatedAt) throws Exception {
      assertEquals(Role.CAS, role);
      Seed original = SEEDS.get(0);
      Map<String, Object> golden = new LinkedHashMap<>(original.tuple());
      golden.put("status", "PAID");
      golden.put("updated_at", LOCAL_MILLIS.format(updatedAt));
      golden.put("paid_at", LOCAL_MILLIS.format(updatedAt));
      DataSource dataSource = logical ? shadow : base;
      JdbcTemplate reader = logical ? routed : sql;
      Map<String, Object> proof =
          transaction(
              dataSource,
              reader,
              () -> {
                Connection bound = DataSourceUtils.getConnection(dataSource);
                try {
                  assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
                  assertFalse(bound.isReadOnly());
                  OrderSqlMapper mapper = new OrderSqlMapper(reader);
                  int first =
                      logical
                          ? logicalCas(original.orderNo, updatedAt)
                          : mapper.updateStatus(
                              original.table(),
                              original.orderNo,
                              "PAID",
                              "PENDING_PAYMENT",
                              Timestamp.valueOf(updatedAt));
                  assertEquals(1, first);
                  Map<String, Object> afterFirst = casRow(reader, logical, original);
                  assertEquals(golden, afterFirst);
                  int stale =
                      logical
                          ? logicalCas(original.orderNo, updatedAt)
                          : mapper.updateStatus(
                              original.table(),
                              original.orderNo,
                              "PAID",
                              "PENDING_PAYMENT",
                              Timestamp.valueOf(updatedAt));
                  assertEquals(0, stale);
                  Map<String, Object> afterStale = casRow(reader, logical, original);
                  assertEquals(afterFirst, afterStale);
                  Connection reread = DataSourceUtils.getConnection(dataSource);
                  try {
                    assertSame(bound, reread);
                  } finally {
                    DataSourceUtils.releaseConnection(reread, dataSource);
                  }
                  return Map.of(
                      "firstCount",
                      first,
                      "staleCount",
                      stale,
                      "golden",
                      golden,
                      "afterFirst",
                      afterFirst,
                      "afterStale",
                      afterStale,
                      "sameBoundConnection",
                      true,
                      "readOnly",
                      false,
                      "rollbackOnly",
                      true,
                      "updatedAt",
                      LOCAL_MILLIS.format(updatedAt));
                } catch (SQLException failure) {
                  throw new IllegalStateException(
                      "CM17EE_CAS_CONNECTION_OBSERVATION_FAILED", failure);
                } finally {
                  DataSourceUtils.releaseConnection(bound, dataSource);
                }
              },
              false,
              true);
      Facts restored = facts();
      assertEquals(initial, restored);
      write(
          path.resolve("cas-" + (logical ? "logical" : "physical") + ".json"),
          Map.of(
              "insideTransaction",
              proof,
              "newConnectionAfterRollback",
              restored,
              "rollbackRestoredAllFacts",
              true));
    }

    /** 原Mapper SET/IF及期待状态原样，唯一附加条件是原月半开范围。 */
    private int logicalCas(String orderNo, LocalDateTime updatedAt) {
      Object[] month = monthArguments(orderNo);
      Timestamp timestamp = Timestamp.valueOf(updatedAt);
      return routed.update(
          logicalCasSql(),
          "PAID",
          timestamp,
          "PAID",
          timestamp,
          "PAID",
          timestamp,
          orderNo,
          "PENDING_PAYMENT",
          month[1],
          month[2]);
    }

    /** 只接受当前事务的同一JdbcTemplate，完整行不借外部连接读取未提交数据。 */
    private Map<String, Object> casRow(JdbcTemplate reader, boolean logical, Seed original) {
      return canonicalRow(
          logical
              ? reader.queryForMap(
                  "SELECT * FROM mall_order WHERE order_no=? AND created_at>=? AND created_at<?",
                  monthArguments(original.orderNo))
              : reader.queryForMap(
                  "SELECT * FROM " + original.table() + " WHERE order_no=?", original.orderNo));
    }

    private <T> T transaction(DataSource dataSource, JdbcTemplate jdbc, TransactionRead<T> action) {
      return transaction(dataSource, jdbc, action, true, false);
    }

    private <T> T transaction(
        DataSource dataSource,
        JdbcTemplate jdbc,
        TransactionRead<T> action,
        boolean readOnly,
        boolean rollback) {
      try (ThreadScope scope = new ThreadScope(this, true)) {
        DataSourceTransactionManager manager = new DataSourceTransactionManager(dataSource);
        assertSame(dataSource, jdbc.getDataSource());
        assertSame(dataSource, manager.getDataSource());
        TransactionTemplate template = new TransactionTemplate(manager);
        template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        template.setReadOnly(readOnly);
        return template.execute(
            status -> {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              assertEquals(
                  readOnly, TransactionSynchronizationManager.isCurrentTransactionReadOnly());
              if (rollback) status.setRollbackOnly();
              assertEquals(
                  Connection.TRANSACTION_REPEATABLE_READ,
                  TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
              assertTrue(TransactionSynchronizationManager.hasResource(dataSource));
              Connection connection = DataSourceUtils.getConnection(dataSource);
              try {
                assertFalse(connection.getAutoCommit());
                assertEquals(readOnly, connection.isReadOnly());
                assertEquals(
                    Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                transactions.add(
                    Map.of(
                        "path",
                        dataSource == base ? "physical" : "logical",
                        "bound",
                        true,
                        "readOnly",
                        readOnly,
                        "rollbackOnly",
                        rollback,
                        "role",
                        role.name(),
                        "repeatableRead",
                        true,
                        "autoCommit",
                        false));
              } catch (SQLException failure) {
                throw new IllegalStateException("CM17ED_TRANSACTION_OBSERVATION_FAILED", failure);
              } finally {
                DataSourceUtils.releaseConnection(connection, dataSource);
              }
              return action.read();
            });
      }
    }

    private boolean hasRecoveryTables() {
      return role == Role.RECOVERY || role == Role.COMPONENTS;
    }

    Set<String> expectedFixtureTables() {
      Set<String> expected = expectedTables();
      if (hasRecoveryTables()) expected.addAll(RECOVERY_TABLES);
      return expected;
    }

    Facts facts() {
      assertEquals(database, sql.queryForObject("SELECT DATABASE()", String.class));
      Map<String, TableFact> observed = new TreeMap<>();
      for (String table : tables(sql)) {
        assertTrue(expectedFixtureTables().contains(table));
        Long count = sql.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
        List<Map<String, Object>> rows =
            role == Role.COMPONENTS && RECOVERY_TABLES.contains(table)
                ? componentFactRows(table)
                : sql.queryForList("SELECT * FROM " + table + " ORDER BY id").stream()
                    .map(OrderListShardingShadowMySqlIT::canonicalRow)
                    .toList();
        assertEquals(count, (long) rows.size());
        observed.put(
            table, new TableFact(count, checksum(sql, table), definition(sql, table), rows));
      }
      return new Facts(database, observed);
    }

    void verifyFacts(Facts facts) {
      assertEquals(database, facts.database);
      Set<String> expected = expectedFixtureTables();
      if (dropped) {
        assertTrue(role == Role.LIST || role == Role.RECOVERY);
        expected.remove(plannedDrop);
      }
      if (writerApplied) assertEquals(Role.LIST, role);
      assertEquals(expected, facts.tables.keySet());
      for (Map.Entry<String, TableFact> entry : facts.tables.entrySet()) {
        String table = entry.getKey();
        TableFact fact = entry.getValue();
        String logical =
            table.startsWith("mall_order_item_")
                ? "mall_order_item"
                : table.startsWith("order_status_log_") ? "order_status_log" : "mall_order";
        assertEquals(
            definitions.get(RECOVERY_TABLES.contains(table) ? table : logical + "_template"),
            fact.definition);
        List<Map<String, Object>> expectedRows = new ArrayList<>();
        if (table.matches("mall_order_[0-9]{6}")) {
          for (Seed seed : SEEDS) if (table.equals(seed.table())) expectedRows.add(seed.tuple());
          if (writerApplied && table.equals(WRITER.table())) expectedRows.add(WRITER.tuple());
        } else if (role == Role.DETAIL && table.matches("mall_order_item_[0-9]{6}")) {
          for (DetailSeed seed : DETAIL_SEEDS)
            if (table.equals(seed.table())) expectedRows.add(seed.tuple());
          expectedRows.sort(
              java.util.Comparator.comparingLong(row -> ((Number) row.get("id")).longValue()));
        } else if (role == Role.RECOVERY && RECOVERY_TABLES.contains(table))
          expectedRows.addAll(recovery.rows(table));
        else if (role == Role.COMPONENTS && RECOVERY_TABLES.contains(table))
          expectedRows.addAll(components.rows(table));
        assertEquals(expectedRows.size(), fact.rows.size());
        for (int index = 0; index < expectedRows.size(); index++) {
          Map<String, Object> actual = fact.rows.get(index);
          for (Map.Entry<String, Object> field : expectedRows.get(index).entrySet())
            assertEquals(
                field.getValue(), actual.get(field.getKey()), table + ":" + field.getKey());
        }
        if (initial != null) {
          TableFact before = initial.tables.get(table);
          if (!(writerApplied && table.equals(WRITER.table()))) assertEquals(before, fact);
          else {
            assertEquals(before.count + 1, fact.count);
            assertEquals(before.rows, fact.rows.subList(0, before.rows.size()));
          }
        }
      }
    }

    void baseEndRead() {
      if (base != null) base.endBusinessSql();
    }

    void closeShadow() throws Exception {
      if (shadow != null) {
        shadow.close();
        shadowClosed = true;
      } else shadowClosed = false;
    }
  }

  /** 仅COMPONENTS读取的透传JdbcTemplate；在原RowMapper前观察真实投影元数据。 */
  private static final class ComponentJdbcTemplate extends JdbcTemplate {
    /** 每条原生产查询首行的实际字符列元数据，不持有ResultSet或连接。 */
    private final List<Map<String, Object>> metadata = new ArrayList<>();

    ComponentJdbcTemplate(DataSource dataSource) {
      super(dataSource);
      setQueryTimeout(5);
    }

    /** 透传原SQL/参数/RowMapper，仅旁观首次真实行的字符投影元数据。 */
    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
      // 1. 原SQL与参数交给相同DataSource上的真实JdbcTemplate执行。
      return super.query(
          sql,
          (resultSet, rowNumber) -> {
            // 2. 只读真实元数据；不替换getter、不包装结果集、不改原Mapper SQL。
            if (rowNumber == 0 && sql.contains("cm_local_"))
              metadata.add(characterMetadata(sql, resultSet.getMetaData()));
            // 3. 原生产RowMapper仍读取同一真实ResultSet。
            return mapper.mapRow(resultSet, rowNumber);
          },
          arguments);
    }
  }

  /** 每个固定cm_local别名必须唯一且是字符类型，原日期标签不能与投影别名混用。 */
  private static Map<String, Object> characterMetadata(String sql, ResultSetMetaData metadata)
      throws SQLException {
    Set<String> labels = new TreeSet<>();
    List<Map<String, Object>> columns = new ArrayList<>();
    Set<String> dateAliases = new TreeSet<>();
    for (int index = 1; index <= metadata.getColumnCount(); index++) {
      String label = metadata.getColumnLabel(index);
      assertTrue(labels.add(label));
      int type = metadata.getColumnType(index);
      columns.add(
          Map.of("label", label, "columnName", metadata.getColumnName(index), "jdbcType", type));
      if (label.startsWith("cm_local_")) {
        assertTrue(
            Set.of(
                    Types.CHAR,
                    Types.VARCHAR,
                    Types.LONGVARCHAR,
                    Types.NCHAR,
                    Types.NVARCHAR,
                    Types.LONGNVARCHAR)
                .contains(type));
        dateAliases.add(label);
      }
    }
    assertFalse(dateAliases.isEmpty());
    for (String alias : dateAliases) {
      assertTrue(sql.contains(" AS " + alias));
      assertFalse(labels.contains(alias.substring("cm_local_".length())));
    }
    return Map.of(
        "actualSql",
        sql,
        "columns",
        columns,
        "dateAliases",
        dateAliases,
        "characterBeforeJavaReader",
        true);
  }

  /** 原Mapper计数真实返回后暂停；不替换COUNT/PAGE SQL或数据。 */
  private static final class BarrierMapper extends OrderSqlMapper {
    /** 当前单路屏障。 */
    private final Barrier barrier;

    BarrierMapper(JdbcTemplate jdbc, Barrier barrier) {
      super(jdbc);
      this.barrier = barrier;
    }

    /** 沿原Mapper读真实COUNT，最后一个月结果读完后才允许writer。 */
    @Override
    public long countOrders(String table, long userId, Object start, Object end, String status) {
      // 1. 真实执行并完全读取原COUNT。
      long count = super.countOrders(table, userId, start, end, status);
      // 2. 固定跨年窗口最后一个月完成后才暂停。
      if ("mall_order_202612".equals(table)) barrier.afterCount();
      // 3. 原返回值不变。
      return count;
    }
  }

  /** 明确COUNT已完成/writer已commit且独立连接可见的双向屏障。 */
  private static final class Barrier {
    /** reader真实计数完成。 */
    private final CountDownLatch counted = new CountDownLatch(1);

    /** writer实际提交并外部验证后释放。 */
    private final CountDownLatch committed = new CountDownLatch(1);

    /** 保留真实触发事实，禁止重复入屏障。 */
    private final AtomicBoolean observed = new AtomicBoolean();

    /** 真正透传执行的业务SQL，屏障时必须尚无PAGE。 */
    private final GuardedDataSource base;

    /** COUNT返回时的安全计数，不以后续writer读覆盖。 */
    private Map<String, Object> sqlAtBarrier;

    Barrier(GuardedDataSource base) {
      this.base = base;
    }

    void afterCount() {
      assertTrue(observed.compareAndSet(false, true));
      assertTrue(base.businessSql.get() > 0);
      assertTrue(base.sqlKinds.stream().noneMatch(kind -> kind.equals("PAGE_OR_ORDER_READ")));
      sqlAtBarrier = base.sqlAudit();
      counted.countDown();
      try {
        assertTrue(committed.await(20, TimeUnit.SECONDS), "writer提交未证实");
      } catch (InterruptedException failure) {
        Thread.currentThread().interrupt();
        throw new IllegalStateException("CM17ED_BARRIER_INTERRUPTED", failure);
      }
    }
  }

  /** 每个新连接核实际catalog，透传原JDBC，单独计数真正执行的订单业务SQL。 */
  private static final class GuardedDataSource extends AbstractDataSource {
    /** 不池化的实际MySQL连接入口。 */
    private final String jdbcUrl;

    /** 唯一允许的实际catalog。 */
    private final String catalog;

    /** 尚未得到close成功返回的连接数。 */
    private final AtomicInteger openConnections = new AtomicInteger();

    /** 自有显式业务窗口中执行的订单SQL数。 */
    private final AtomicInteger businessSql = new AtomicInteger();

    /** 元数据/事务准备与业务SQL分开，不冒称零transport。 */
    private final AtomicInteger nonBusinessSql = new AtomicInteger();

    /** 业务窗口开关。 */
    private volatile boolean businessWindow;

    /** 固定安全SQL种类记录，不保存参数或连接字符串。 */
    private final List<String> sqlKinds = Collections.synchronizedList(new ArrayList<>());

    /** 失败关闭/归属不明时禁止清理。 */
    private volatile boolean uncertain;

    GuardedDataSource(String url, String catalog) {
      this.jdbcUrl = url;
      this.catalog = catalog;
    }

    /** 每次实际连接先核catalog；检查失败关闭失败均保留unknown标记。 */
    @Override
    public Connection getConnection() throws SQLException {
      // 1. 只连接冻结的本地入口，并在交给任何调用方前核实际catalog。
      Connection actual = DriverManager.getConnection(jdbcUrl, "root", "root");
      try (Statement statement = actual.createStatement()) {
        statement.setQueryTimeout(5);
        nonBusinessSql.incrementAndGet();
        try (ResultSet rows = statement.executeQuery("SELECT DATABASE()")) {
          if (!rows.next() || !catalog.equals(rows.getString(1)) || rows.next())
            throw new SQLException("CM17ED_CATALOG_MISMATCH");
        }
      } catch (SQLException | RuntimeException failure) {
        try {
          actual.close();
        } catch (SQLException closing) {
          uncertain = true;
          failure.addSuppressed(closing);
        }
        throw failure;
      }
      // 2. close仅成功返回后减计数；框架始终收到同一透传Connection代理。
      openConnections.incrementAndGet();
      AtomicBoolean closed = new AtomicBoolean();
      return (Connection)
          Proxy.newProxyInstance(
              getClass().getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                String name = method.getName();
                if (name.equals("close")) {
                  if (!closed.get()) {
                    try {
                      invoke(actual, method, arguments);
                      closed.set(true);
                      openConnections.decrementAndGet();
                    } catch (Throwable failure) {
                      uncertain = true;
                      throw failure;
                    }
                  }
                  return null;
                }
                if (name.equals("setCatalog") && !catalog.equals(arguments[0])) {
                  uncertain = true;
                  throw new SQLException("CM17ED_CATALOG_CHANGE_REJECTED");
                }
                if (name.equals("unwrap") && arguments[0] == Connection.class) return proxy;
                if (name.equals("isWrapperFor") && arguments[0] == Connection.class) return true;
                Object result = invoke(actual, method, arguments);
                // 3. 原Statement/PreparedStatement执行与返回不变，仅设查询上限并观察执行种类。
                if (result instanceof Statement statement
                    && (name.equals("prepareStatement")
                        || name.equals("createStatement")
                        || name.equals("prepareCall"))) {
                  statement.setQueryTimeout(5);
                  String prepared =
                      arguments != null
                              && arguments.length > 0
                              && arguments[0] instanceof String text
                          ? text
                          : null;
                  Class<?> type =
                      result instanceof PreparedStatement
                          ? PreparedStatement.class
                          : Statement.class;
                  return Proxy.newProxyInstance(
                      getClass().getClassLoader(),
                      new Class<?>[] {type},
                      (ignored, statementMethod, statementArguments) -> {
                        if (statementMethod.getName().equals("getConnection")) return proxy;
                        if (statementMethod.getName().startsWith("execute")) {
                          String sql =
                              prepared != null
                                  ? prepared
                                  : statementArguments != null
                                          && statementArguments.length > 0
                                          && statementArguments[0] instanceof String text
                                      ? text
                                      : "";
                          observe(sql);
                        }
                        return invoke(statement, statementMethod, statementArguments);
                      });
                }
                return result;
              });
    }

    /** 调用方不能通过另一组凭据绕过固定认证/归属。 */
    @Override
    public Connection getConnection(String username, String password) throws SQLException {
      if (!"root".equals(username) || !"root".equals(password))
        throw new SQLException("CM17ED_CREDENTIAL_OVERRIDE_REJECTED");
      return getConnection();
    }

    private void observe(String sql) {
      String normalized = sql.replace("`", "").toLowerCase(java.util.Locale.ROOT);
      boolean orderRead =
          normalized.matches(
              "(?s)\\s*select\\b.*\\bfrom\\s+(?:[a-z0-9_]+\\.)?mall_order(?:_[0-9]{6})?\\b.*");
      if (businessWindow && orderRead) {
        businessSql.incrementAndGet();
        sqlKinds.add(
            normalized.contains("count(") || normalized.contains("count (")
                ? "COUNT"
                : "PAGE_OR_ORDER_READ");
      } else nonBusinessSql.incrementAndGet();
    }

    void resetBusinessSql() {
      businessSql.set(0);
      nonBusinessSql.set(0);
      sqlKinds.clear();
      businessWindow = true;
    }

    void endBusinessSql() {
      businessWindow = false;
    }

    Map<String, Object> sqlAudit() {
      return Map.of(
          "businessOrderSql",
          businessSql.get(),
          "otherExecutedSql",
          nonBusinessSql.get(),
          "businessKinds",
          new ArrayList<>(sqlKinds),
          "zeroTransportClaimed",
          false);
    }

    void assertClosedConnections() {
      assertEquals(0, openConnections.get());
      assertFalse(uncertain, "连接归属或关闭未证实");
    }
  }

  /** try-with-resources保证上下文/计数清理异常不会覆盖原业务或JDBC异常。 */
  private static final class ThreadScope implements AutoCloseable {
    /** 当前真实事务作用域计数的所属fixture。 */
    private final Fixture fixture;

    ThreadScope(Fixture fixture, boolean authenticated) {
      assertCleanThread();
      this.fixture = fixture;
      fixture.activeTransactions.incrementAndGet();
      if (authenticated) AuthContext.set(7L, "USER");
    }

    /** 原事务已退出后清理本线程Auth并核RootContext/连接绑定，无强行清空事务资源。 */
    @Override
    public void close() {
      fixture.activeTransactions.decrementAndGet();
      AuthContext.clear();
      assertCleanThread();
    }
  }

  /** 原字段的独立安全投影，金额仍按精确分值字符串比较。 */
  private record Item(
      String orderNo,
      long userId,
      String status,
      String totalAmount,
      String payAmount,
      String addressSnapshot,
      String createdAt,
      String expireAt) {}

  /** 包括原成功码、total/page/pageSize和原items顺序。 */
  private record Page(String code, long total, int page, int pageSize, List<Item> items) {}

  /** 完整owned表事实，不包含任何源业务行。 */
  private record TableFact(
      long count, String checksum, String definition, List<Map<String, Object>> rows) {}

  /** 实际catalog与完整表清单。 */
  private record Facts(String database, Map<String, TableFact> tables) {}

  /** 详情主表八字段与有序完整七字段明细，SKU按原Map语义比较。 */
  private record Detail(Item main, List<Line> items) {}

  /** 独立golden和实际映射共用的完整响应字段形状。 */
  private record Line(
      long productId,
      long skuId,
      String productNameSnapshot,
      Map<String, String> skuSnapshot,
      String unitPrice,
      int quantity,
      String lineAmount) {}

  /** 仅详情角色可持久化的完整十一列明细模型。 */
  private record DetailSeed(
      long id,
      long orderId,
      String orderNo,
      long productId,
      long skuId,
      String name,
      String size,
      BigDecimal price,
      int quantity,
      BigDecimal amount,
      LocalDateTime created) {
    String table() {
      return "mall_order_item_" + orderNo.substring(0, 6);
    }

    String snapshot() {
      return "{\"size\": \"" + size + "\"}";
    }

    Map<String, Object> tuple() {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", id);
      row.put("order_id", orderId);
      row.put("order_no", orderNo);
      row.put("product_id", productId);
      row.put("sku_id", skuId);
      row.put("product_name_snapshot", name);
      row.put("sku_snapshot", snapshot());
      row.put("unit_price", price.toPlainString());
      row.put("quantity", (long) quantity);
      row.put("line_amount", amount.toPlainString());
      row.put("created_at", LOCAL_MILLIS.format(created));
      return row;
    }

    Line line() {
      return new Line(
          productId,
          skuId,
          name,
          Map.of("size", size),
          price.toPlainString(),
          quantity,
          amount.toPlainString());
    }
  }

  /** 恢复组固定小模型：每表四行，同排序键的两候选及未来/非候选控制。 */
  private static final class RecoveryModel {
    /** 三个扫描复用的唯一上海毫秒采样，不覆盖Outbox原内部时钟。 */
    private final LocalDateTime now =
        LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);

    /** 显式陈旧边界，比候选created晚八分钟。 */
    private final LocalDateTime staleBefore = now.minusMinutes(2);

    /** 固定四行Intent模型，主键顺序即完整facts顺序。 */
    private final List<Map<String, Object>> intents =
        List.of(
            intent(901, "202701Z", "intent-1", "INIT", now.minusMinutes(2)),
            intent(902, "202612Z", "intent-2", "RESERVED", now.minusMinutes(2)),
            intent(903, "202701FUTURE", "intent-3", "INIT", now.plusDays(2)),
            intent(904, "202701DONE", "intent-4", "COMMITTED", now.minusMinutes(2)));

    /** 固定四行Outbox模型，原内部now不影响分钟过去/两天未来的资格。 */
    private final List<Map<String, Object>> outboxes =
        List.of(
            outbox(1001, "202701Z", "cm17ee-event-1", "PENDING", now.minusMinutes(2)),
            outbox(1002, "202612Z", "cm17ee-event-2", "PENDING", now.minusMinutes(2)),
            outbox(1003, "202701FUTURE", "cm17ee-event-3", "PENDING", now.plusDays(2)),
            outbox(1004, "202701DONE", "cm17ee-event-4", "PUBLISHED", now.minusMinutes(2)));

    /** 固定四行Action模型，第四行仍到期但两天有效lease排除。 */
    private final List<Map<String, Object>> actions =
        List.of(
            action(1101, "202701Z", "CONFIRM", "PENDING", now.minusMinutes(2), null),
            action(1102, "202612Z", "ROLLBACK", "UNKNOWN", now.minusMinutes(2), null),
            action(1103, "202701FUTURE", "CONFIRM", "PENDING", now.plusDays(2), null),
            action(
                1104, "202701LEASED", "ROLLBACK", "PENDING", now.minusMinutes(2), now.plusDays(2)));

    private Map<String, Object> intent(
        long id, String orderNo, String key, String status, LocalDateTime retry) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", id);
      row.put("order_no", orderNo);
      row.put("user_id", 7L);
      row.put("idempotency_key", key);
      row.put("items_snapshot", "[{\"skuId\": 101, \"quantity\": 1}]");
      row.put("status", status);
      row.put("last_error", null);
      row.put("next_retry_at", retry);
      row.put("created_at", now.minusMinutes(10));
      row.put("updated_at", now.minusMinutes(10));
      return row;
    }

    private Map<String, Object> outbox(
        long id, String orderNo, String eventId, String status, LocalDateTime retry) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", id);
      row.put("event_id", eventId);
      row.put("event_type", "ORDER_TIMEOUT");
      row.put("order_no", orderNo);
      row.put("payload", "{\"fixture\": \"recovery\"}");
      row.put("status", status);
      row.put("attempts", 0L);
      row.put("next_retry_at", retry);
      row.put("last_error", null);
      row.put("published_at", "PUBLISHED".equals(status) ? now.minusMinutes(1) : null);
      row.put("created_at", now.minusMinutes(10));
      row.put("updated_at", now.minusMinutes(10));
      return row;
    }

    private Map<String, Object> action(
        long id,
        String orderNo,
        String type,
        String status,
        LocalDateTime retry,
        LocalDateTime lease) {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", id);
      row.put("order_no", orderNo);
      row.put("action_type", type);
      row.put("operation_key", orderNo + ":" + type);
      row.put("status", status);
      row.put("attempts", 0L);
      row.put("next_retry_at", retry);
      row.put("lease_owner", lease == null ? null : "cm17ee-owned-lease");
      row.put("lease_until", lease);
      row.put("last_error", null);
      row.put("created_at", now.minusMinutes(10));
      row.put("updated_at", now.minusMinutes(10));
      return row;
    }

    List<Map<String, Object>> rows(String table) {
      return switch (table) {
        case "order_reservation_intent" ->
            intents.stream().map(OrderListShardingShadowMySqlIT::canonicalRow).toList();
        case "order_outbox" ->
            outboxes.stream().map(OrderListShardingShadowMySqlIT::canonicalRow).toList();
        case "order_stock_action" ->
            actions.stream().map(OrderListShardingShadowMySqlIT::canonicalRow).toList();
        default -> throw new IllegalArgumentException("CM17EE_UNDECLARED_RECOVERY_TABLE");
      };
    }

    Map<String, Object> plan() {
      return Map.of(
          "now",
          LOCAL_MILLIS.format(now),
          "staleBefore",
          LOCAL_MILLIS.format(staleBefore),
          "futureAndLeaseMarginDays",
          2,
          "intent",
          rows("order_reservation_intent"),
          "outbox",
          rows("order_outbox"),
          "action",
          rows("order_stock_action"),
          "fixtureOnly",
          true);
    }

    void seed(JdbcTemplate sql) {
      for (Map<String, Object> row : intents)
        assertEquals(
            1,
            sql.update(
                "INSERT INTO"
                    + " order_reservation_intent(id,order_no,user_id,idempotency_key,items_snapshot,status,last_error,next_retry_at,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                row.values().toArray()));
      for (Map<String, Object> row : outboxes)
        assertEquals(
            1,
            sql.update(
                "INSERT INTO"
                    + " order_outbox(id,event_id,event_type,order_no,payload,status,attempts,next_retry_at,last_error,published_at,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                row.values().toArray()));
      for (Map<String, Object> row : actions)
        assertEquals(
            1,
            sql.update(
                "INSERT INTO"
                    + " order_stock_action(id,order_no,action_type,operation_key,status,attempts,next_retry_at,lease_owner,lease_until,last_error,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                row.values().toArray()));
    }
  }

  /** 固定六日期的小型完整模型，只由COMPONENTS角色启用，不覆盖原RecoveryModel。 */
  private static final class ComponentModel {
    /** 固定的有限历史/边界风险族，不借当前时钟补造组件。 */
    private final List<String> values =
        List.of(
            "1000-01-01 00:00:00.001",
            "1582-10-10 12:34:56.123",
            "1900-03-01 01:02:03.001",
            "2000-02-29 23:59:59.999",
            "2038-01-19 03:14:08.456",
            "9999-12-31 23:59:59.999");

    /** 六个完整Intent，首笔nextRetryAt明确NULL。 */
    private final List<Map<String, Object>> intents = new ArrayList<>();

    /** 六个合法待发事件，nextRetry足够过去，publishedAt交替可空。 */
    private final List<Map<String, Object>> outboxes = new ArrayList<>();

    /** 六个到期动作，租约NULL；不调用claim。 */
    private final List<Map<String, Object>> actions = new ArrayList<>();

    ComponentModel() {
      for (int index = 0; index < values.size(); index++) {
        LocalDateTime component = LocalDateTime.parse(values.get(index).replace(' ', 'T'));
        String orderNo = "202701COMP" + (index + 1);
        Map<String, Object> intent = new LinkedHashMap<>();
        intent.put("id", 2001L + index);
        intent.put("order_no", orderNo);
        intent.put("user_id", 7L);
        intent.put("idempotency_key", "component-key-" + (index + 1));
        intent.put("items_snapshot", "[{\"skuId\": 101, \"quantity\": 1}]");
        intent.put("status", "INIT");
        intent.put("last_error", null);
        intent.put("next_retry_at", index == 0 ? null : component);
        intent.put("created_at", component);
        intent.put("updated_at", component);
        intents.add(intent);
        Map<String, Object> outbox = new LinkedHashMap<>();
        outbox.put("id", 2101L + index);
        outbox.put("event_id", "cm17ef-component-" + (index + 1));
        outbox.put("event_type", "ORDER_TIMEOUT");
        outbox.put("order_no", orderNo);
        outbox.put("payload", "{\"fixture\": \"components\"}");
        outbox.put("status", "PENDING");
        outbox.put("attempts", 0L);
        outbox.put("next_retry_at", LocalDateTime.parse("1000-01-01T00:00:00.001"));
        outbox.put("last_error", null);
        outbox.put("published_at", index % 2 == 0 ? component : null);
        outbox.put("created_at", component);
        outbox.put("updated_at", component);
        outboxes.add(outbox);
        Map<String, Object> action = new LinkedHashMap<>();
        String type = index % 2 == 0 ? "CONFIRM" : "ROLLBACK";
        action.put("id", 2201L + index);
        action.put("order_no", orderNo);
        action.put("action_type", type);
        action.put("operation_key", orderNo + ":" + type);
        action.put("status", "PENDING");
        action.put("attempts", 0L);
        action.put("next_retry_at", component);
        action.put("lease_owner", null);
        action.put("lease_until", null);
        action.put("last_error", null);
        action.put("created_at", component);
        action.put("updated_at", component);
        actions.add(action);
      }
    }

    List<Map<String, Object>> rows(String table) {
      return switch (table) {
        case "order_reservation_intent" ->
            intents.stream().map(OrderListShardingShadowMySqlIT::canonicalRow).toList();
        case "order_outbox" ->
            outboxes.stream().map(OrderListShardingShadowMySqlIT::canonicalRow).toList();
        case "order_stock_action" ->
            actions.stream().map(OrderListShardingShadowMySqlIT::canonicalRow).toList();
        default -> throw new IllegalArgumentException("CM17EF_UNDECLARED_COMPONENT_TABLE");
      };
    }

    Map<String, Object> plan() {
      return Map.of(
          "fixtureOnly",
          true,
          "values",
          values,
          "intent",
          rows("order_reservation_intent"),
          "outbox",
          rows("order_outbox"),
          "action",
          rows("order_stock_action"),
          "actionScanNow",
          "9999-12-31T23:59:59.999",
          "outboxOriginalClock",
          true);
    }

    void seed(JdbcTemplate sql) {
      for (Map<String, Object> row : intents)
        assertEquals(
            1,
            sql.update(
                "INSERT INTO"
                    + " order_reservation_intent(id,order_no,user_id,idempotency_key,items_snapshot,status,last_error,next_retry_at,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?)",
                row.values().toArray()));
      for (Map<String, Object> row : outboxes)
        assertEquals(
            1,
            sql.update(
                "INSERT INTO"
                    + " order_outbox(id,event_id,event_type,order_no,payload,status,attempts,next_retry_at,last_error,published_at,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                row.values().toArray()));
      for (Map<String, Object> row : actions)
        assertEquals(
            1,
            sql.update(
                "INSERT INTO"
                    + " order_stock_action(id,order_no,action_type,operation_key,status,attempts,next_retry_at,lease_owner,lease_until,last_error,created_at,updated_at)"
                    + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
                row.values().toArray()));
    }

    /** 只为本角色完整事实审计保组件；原角色的SELECT *路径不变。 */
    String factSql(String table) {
      return switch (table) {
        case "order_reservation_intent" ->
            "SELECT"
                + " id,order_no,user_id,idempotency_key,items_snapshot,status,last_error,CAST(next_retry_at"
                + " AS CHAR) AS cm_local_next_retry_at,CAST(created_at AS CHAR) AS"
                + " cm_local_created_at,CAST(updated_at AS CHAR) AS cm_local_updated_at FROM"
                + " order_reservation_intent ORDER BY id";
        case "order_outbox" ->
            "SELECT id,event_id,event_type,order_no,payload,status,attempts,CAST(next_retry_at AS"
                + " CHAR) AS cm_local_next_retry_at,last_error,CAST(published_at AS CHAR) AS"
                + " cm_local_published_at,CAST(created_at AS CHAR) AS"
                + " cm_local_created_at,CAST(updated_at AS CHAR) AS cm_local_updated_at FROM"
                + " order_outbox ORDER BY id";
        case "order_stock_action" ->
            "SELECT id,order_no,action_type,operation_key,status,attempts,CAST(next_retry_at AS"
                + " CHAR) AS cm_local_next_retry_at,lease_owner,CAST(lease_until AS CHAR) AS"
                + " cm_local_lease_until,last_error,CAST(created_at AS CHAR) AS"
                + " cm_local_created_at,CAST(updated_at AS CHAR) AS cm_local_updated_at FROM"
                + " order_stock_action ORDER BY id";
        default -> throw new IllegalArgumentException("CM17EF_UNDECLARED_COMPONENT_TABLE");
      };
    }
  }

  /** 显式声明的合法模型行，不调用订单创建或任何远端。 */
  private record Seed(
      long id,
      String orderNo,
      long userId,
      String status,
      BigDecimal total,
      BigDecimal pay,
      String address,
      LocalDateTime created) {
    String table() {
      return "mall_order_" + created.format(DateTimeFormatter.ofPattern("yyyyMM"));
    }

    Map<String, Object> tuple() {
      Map<String, Object> row = new LinkedHashMap<>();
      row.put("id", id);
      row.put("order_no", orderNo);
      row.put("user_id", userId);
      row.put("status", status);
      row.put("total_amount", total.toPlainString());
      row.put("pay_amount", pay.toPlainString());
      row.put("address_snapshot", address);
      row.put("expire_at", LOCAL_MILLIS.format(created.plusMinutes(30)));
      row.put(
          "paid_at", "PAID".equals(status) ? LOCAL_MILLIS.format(created.plusMinutes(1)) : null);
      row.put(
          "cancelled_at",
          "CANCELLED".equals(status) ? LOCAL_MILLIS.format(created.plusMinutes(1)) : null);
      row.put("created_at", LOCAL_MILLIS.format(created));
      row.put("updated_at", LOCAL_MILLIS.format(created));
      return row;
    }

    Item item() {
      return new Item(
          orderNo,
          userId,
          status,
          total.toPlainString(),
          pay.toPlainString(),
          address,
          LOCAL_MILLIS.format(created),
          LOCAL_MILLIS.format(created.plusMinutes(30)));
    }
  }

  /** 事务内实际数据库动作；CAS由显式读写及rollback参数限定。 */
  private interface TransactionRead<T> {
    T read();
  }

  /** 独立尽力收尾动作，保留原异常。 */
  private interface Checked {
    void run() throws Exception;
  }

  private static Seed seed(
      long id,
      String orderNo,
      long userId,
      String status,
      String total,
      String pay,
      String label,
      String created) {
    return new Seed(
        id,
        orderNo,
        userId,
        status,
        new BigDecimal(total),
        new BigDecimal(pay),
        "{\"case\": \"" + label + "\"}",
        LocalDateTime.parse(created));
  }

  private static void insert(Connection connection, Seed seed) throws SQLException {
    try (PreparedStatement statement =
        connection.prepareStatement(
            "INSERT INTO "
                + seed.table()
                + "("
                + SEED_COLUMNS
                + ") VALUES(?,?,?,?,?,?,?,?,?,?,?,?)")) {
      statement.setQueryTimeout(5);
      Object[] values = {
        seed.id,
        seed.orderNo,
        seed.userId,
        seed.status,
        seed.total,
        seed.pay,
        seed.address,
        Timestamp.valueOf(seed.created.plusMinutes(30)),
        "PAID".equals(seed.status) ? Timestamp.valueOf(seed.created.plusMinutes(1)) : null,
        "CANCELLED".equals(seed.status) ? Timestamp.valueOf(seed.created.plusMinutes(1)) : null,
        Timestamp.valueOf(seed.created),
        Timestamp.valueOf(seed.created)
      };
      for (int index = 0; index < values.length; index++)
        statement.setObject(index + 1, values[index]);
      assertEquals(1, statement.executeUpdate());
    }
  }

  /** 独立固定期望顺序，不依据查询结果重排明细。 */
  private static List<DetailSeed> detailRowsFor(String orderNo) {
    return switch (orderNo) {
      case "202701Z" -> List.of(DETAIL_SEEDS.get(1), DETAIL_SEEDS.get(0));
      case "202612Z" -> List.of(DETAIL_SEEDS.get(2));
      default -> throw new IllegalArgumentException("CM17EE_UNDECLARED_DETAIL_PARENT");
    };
  }

  private static void seedDetails(JdbcTemplate sql) {
    for (DetailSeed seed : DETAIL_SEEDS)
      assertEquals(
          1,
          sql.update(
              "INSERT INTO "
                  + seed.table()
                  + "(id,order_id,order_no,product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount,created_at)"
                  + " VALUES(?,?,?,?,?,?,?,?,?,?,?)",
              seed.id,
              seed.orderId,
              seed.orderNo,
              seed.productId,
              seed.skuId,
              seed.name,
              seed.snapshot(),
              seed.price,
              seed.quantity,
              seed.amount,
              Timestamp.valueOf(seed.created)));
  }

  /** 本地parser测试与真实shadow CAS使用同一原SET/WHERE镜像，不复制另一条测试SQL。 */
  static String logicalCasSql() {
    return "UPDATE mall_order SET"
               + " status=?,updated_at=?,paid_at=IF(?='PAID',?,paid_at),cancelled_at=IF(?='CANCELLED',?,cancelled_at)"
               + " WHERE order_no=? AND status=? AND created_at>=? AND created_at<?";
  }

  /** 原值前缀仅确定原月，不trim、不改大小写、不以当前时钟补缺失月份。 */
  private static Object[] monthArguments(String orderNo) {
    if (orderNo == null || !orderNo.matches("[0-9]{6}[A-Za-z0-9-]+"))
      throw new IllegalArgumentException("CM17EE_INVALID_ORDER_PREFIX");
    YearMonth month =
        YearMonth.parse(orderNo.substring(0, 6), DateTimeFormatter.ofPattern("uuuuMM"));
    return new Object[] {
      orderNo,
      Timestamp.valueOf(month.atDay(1).atStartOfDay()),
      Timestamp.valueOf(month.plusMonths(1).atDay(1).atStartOfDay())
    };
  }

  private static Line detailLine(OrderItemVO item) {
    return new Line(
        item.productId(),
        item.skuId(),
        item.productNameSnapshot(),
        item.skuSnapshot(),
        money(item.unitPrice()),
        item.quantity(),
        money(item.lineAmount()));
  }

  private static Line detailLine(Map<String, Object> row) {
    return new Line(
        ((Number) row.get("product_id")).longValue(),
        ((Number) row.get("sku_id")).longValue(),
        (String) row.get("product_name_snapshot"),
        skuSnapshot((String) row.get("sku_snapshot")),
        money((BigDecimal) row.get("unit_price")),
        ((Number) row.get("quantity")).intValue(),
        money((BigDecimal) row.get("line_amount")));
  }

  private static Map<String, String> skuSnapshot(String snapshot) {
    try {
      return JSON.readValue(
          snapshot, new com.fasterxml.jackson.core.type.TypeReference<Map<String, String>>() {});
    } catch (java.io.IOException failure) {
      throw new IllegalStateException("CM17EE_INVALID_DECLARED_SKU_SNAPSHOT", failure);
    }
  }

  /** 对照原RowMapper读取的全部十字段，不用手写PO代替真实查询。 */
  private static Map<String, Object> intentProjection(OrderReservationIntentPO intent) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", intent.id);
    row.put("order_no", intent.orderNo);
    row.put("user_id", intent.userId);
    row.put("idempotency_key", intent.idempotencyKey);
    row.put("items_snapshot", intent.itemsSnapshot);
    row.put("status", intent.status);
    row.put("last_error", intent.lastError);
    row.put("next_retry_at", intent.nextRetryAt);
    row.put("created_at", intent.createdAt);
    row.put("updated_at", intent.updatedAt);
    return canonicalRow(row);
  }

  /** 原Outbox完整十二列，原内部now只由调用时间窗旁观。 */
  private static Map<String, Object> outboxProjection(OrderOutboxPO outbox) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", outbox.id);
    row.put("event_id", outbox.eventId);
    row.put("event_type", outbox.eventType);
    row.put("order_no", outbox.orderNo);
    row.put("payload", outbox.payload);
    row.put("status", outbox.status);
    row.put("attempts", outbox.attempts);
    row.put("next_retry_at", outbox.nextRetryAt);
    row.put("last_error", outbox.lastError);
    row.put("published_at", outbox.publishedAt);
    row.put("created_at", outbox.createdAt);
    row.put("updated_at", outbox.updatedAt);
    return canonicalRow(row);
  }

  /** 原findDue只投影七列；lease等其余列由完整owned facts守恒单独证明。 */
  private static Map<String, Object> actionProjection(OrderStockActionPO action) {
    Map<String, Object> row = new LinkedHashMap<>();
    row.put("id", action.id);
    row.put("order_no", action.orderNo);
    row.put("action_type", action.actionType);
    row.put("operation_key", action.operationKey);
    row.put("status", action.status);
    row.put("attempts", action.attempts);
    row.put("next_retry_at", action.nextRetryAt);
    return canonicalRow(row);
  }

  private static Page golden(long total, int page, int size, List<Seed> seeds) {
    return new Page("0", total, page, size, seeds.stream().map(Seed::item).toList());
  }

  private static Page project(ApiResponse<?> response) {
    PageResult<?> page = (PageResult<?>) response.data;
    return new Page(
        response.code,
        page.total,
        page.page,
        page.pageSize,
        page.items.stream()
            .map(
                value -> {
                  OrderVO row = (OrderVO) value;
                  return new Item(
                      row.orderNo,
                      row.userId,
                      row.status,
                      money(row.totalAmount),
                      money(row.payAmount),
                      row.addressSnapshot,
                      apiLocal(row.createdAt),
                      apiLocal(row.expireAt));
                })
            .toList());
  }

  private static Item project(Map<String, Object> row) {
    return new Item(
        (String) row.get("order_no"),
        ((Number) row.get("user_id")).longValue(),
        (String) row.get("status"),
        money((BigDecimal) row.get("total_amount")),
        money((BigDecimal) row.get("pay_amount")),
        (String) row.get("address_snapshot"),
        local(row.get("created_at")),
        local(row.get("expire_at")));
  }

  private static String money(BigDecimal amount) {
    assertNotNull(amount);
    assertEquals(2, amount.scale());
    return amount.toPlainString();
  }

  private static String apiLocal(String value) {
    if (value == null) return null;
    OffsetDateTime parsed = OffsetDateTime.parse(value);
    assertEquals(ZoneOffset.ofHours(8), parsed.getOffset());
    assertEquals(0, parsed.getNano() % 1_000_000);
    return LOCAL_MILLIS.format(parsed.toLocalDateTime());
  }

  private static String local(Object value) {
    if (value == null) return null;
    LocalDateTime local =
        value instanceof Timestamp timestamp ? timestamp.toLocalDateTime() : (LocalDateTime) value;
    assertEquals(0, local.getNano() % 1_000_000);
    return LOCAL_MILLIS.format(local);
  }

  private static Map<String, Object> canonicalRow(Map<String, Object> row) {
    Map<String, Object> result = new LinkedHashMap<>();
    row.forEach(
        (key, value) ->
            result.put(
                key,
                value instanceof BigDecimal decimal
                    ? decimal.toPlainString()
                    : value instanceof Timestamp || value instanceof LocalDateTime
                        ? local(value)
                        : value instanceof Number number ? number.longValue() : value));
    return result;
  }

  private static OrderQueryWindow window(String start, String end) {
    return OrderQueryWindow.from(start, end, OffsetDateTime.parse("2026-12-15T12:00:00+08:00"));
  }

  private static JdbcTemplate jdbc(DataSource source) {
    JdbcTemplate jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(5);
    return jdbc;
  }

  private static Set<String> tables(JdbcTemplate sql) {
    return new TreeSet<>(
        sql.queryForList(
            "SELECT TABLE_NAME FROM information_schema.tables WHERE TABLE_SCHEMA=DATABASE() AND"
                + " TABLE_TYPE='BASE TABLE' ORDER BY TABLE_NAME",
            String.class));
  }

  private static Set<String> expectedTables() {
    Set<String> result = new TreeSet<>();
    for (String logical : LOGICAL) {
      result.add(logical + "_template");
      for (String month : MONTHS) result.add(logical + "_" + month);
    }
    return result;
  }

  private static String definition(JdbcTemplate sql, String table) {
    assertTrue(table.matches("[a-z0-9_]+"));
    List<Map<String, Object>> rows = sql.queryForList("SHOW CREATE TABLE " + table);
    assertEquals(1, rows.size());
    String ddl = (String) rows.get(0).get("Create Table");
    assertNotNull(ddl);
    return hash(
        ddl.replaceFirst("CREATE TABLE `[^`]+`", "CREATE TABLE `MODEL`")
            .getBytes(StandardCharsets.UTF_8));
  }

  private static String checksum(JdbcTemplate sql, String table) {
    assertTrue(table.matches("[a-z0-9_]+"));
    List<Map<String, Object>> rows = sql.queryForList("CHECKSUM TABLE " + table);
    assertEquals(1, rows.size());
    Object checksum = rows.get(0).get("Checksum");
    assertNotNull(checksum);
    return checksum.toString();
  }

  private static Map<String, String> sourceFingerprint() {
    assertEquals("cloudmall_order", source.queryForObject("SELECT DATABASE()", String.class));
    Map<String, String> result = new TreeMap<>();
    for (String table : tables(source)) result.put(table, checksum(source, table));
    return result;
  }

  private static String boundedUrl(String input) {
    String[] parts = input.split("\\?", 2);
    List<String> parameters = new ArrayList<>();
    if (parts.length == 2)
      for (String parameter : parts[1].split("&")) {
        String key = parameter.split("=", 2)[0];
        if (!Set.of(
                "serverTimezone",
                "forceConnectionTimeZoneToSession",
                "connectTimeout",
                "socketTimeout")
            .contains(key)) parameters.add(parameter);
      }
    parameters.addAll(
        List.of(
            "serverTimezone=Asia%2FShanghai",
            "forceConnectionTimeZoneToSession=true", "connectTimeout=3000", "socketTimeout=5000"));
    return parts[0] + "?" + String.join("&", parameters);
  }

  private static boolean isMissingTarget(Throwable failure, String table) {
    for (Throwable current = failure; current != null; current = current.getCause()) {
      String message = current.getMessage();
      if (message != null
          && message.contains(table)
          && ((current instanceof SQLException sql && sql.getErrorCode() == 1146)
              || message.contains("doesn't exist")
              || message.contains("does not exist"))) return true;
    }
    return false;
  }

  private static Object invoke(Object target, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      throw failure.getTargetException();
    }
  }

  private static void assertCleanThread() {
    assertNull(AuthContext.userId());
    assertNull(AuthContext.role());
    assertNull(RootContext.getXID());
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    assertTrue(TransactionSynchronizationManager.getResourceMap().isEmpty());
  }

  /** 新组失败只追加安全错误；审计IO异常跟随原异常，不替换失败或绕过Run收尾。 */
  private static void recordMethodFailure(String method, Throwable failure) {
    try {
      write(auditRoot.resolve(method).resolve("method-error.json"), safeError(failure));
    } catch (Exception auditFailure) {
      failure.addSuppressed(auditFailure);
    }
  }

  private static void write(Path file, Object value) throws Exception {
    Files.writeString(
        file,
        JSON.writeValueAsString(value),
        StandardCharsets.UTF_8,
        StandardOpenOption.CREATE_NEW);
  }

  private static String hash(byte[] value) {
    try {
      return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new AssertionError(failure);
    }
  }

  private static Map<String, String> safeError(Throwable error) {
    return Map.of(
        "type",
        error.getClass().getSimpleName(),
        "messageSha256",
        hash(Objects.toString(error.getMessage(), "").getBytes(StandardCharsets.UTF_8)));
  }

  private static List<Map<String, String>> safeErrors(List<Throwable> errors) {
    return errors.stream().map(OrderListShardingShadowMySqlIT::safeError).toList();
  }

  private static void attempt(List<Throwable> errors, Checked action) {
    try {
      action.run();
    } catch (Exception | AssertionError failure) {
      errors.add(failure);
    }
  }

  private static void throwIfErrors(List<Throwable> errors) throws Exception {
    if (errors.isEmpty()) return;
    AssertionError aggregate = new AssertionError("CM17ED_AUDIT_OR_CLEANUP_FAILED_PRESERVE");
    errors.forEach(aggregate::addSuppressed);
    throw aggregate;
  }
}
