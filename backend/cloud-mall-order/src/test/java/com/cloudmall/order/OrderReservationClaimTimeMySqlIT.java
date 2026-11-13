package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.reservation.OrderReservationRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 普通恢复认领必须在每个候选准备就绪后采新时间；SQL真实，远端/订单查询为替身。 */
class OrderReservationClaimTimeMySqlIT {
  /** 原订单库只读核验和隔离库管理入口。 */
  private static JdbcTemplate source;

  /** 原订单库表清单和校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 本例自行创建、可精确清理的数据库名。 */
  private String database;

  /** 隔离库实际数据库事实验证入口。 */
  private JdbcTemplate fixture;

  /** 实际预扣意图认领和状态持久层。 */
  private OrderReservationIntentMapper mapper;

  @BeforeAll
  static void readOriginalDatabase() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_order的cloudmall.mysql.test.url");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghaiUrl(url), "root", "root"));
    sourceFingerprint = fingerprint();
  }

  @BeforeEach
  void createFixture() {
    database = "cm17bp_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolatedUrl =
        System.getProperty("cloudmall.mysql.test.url").replace("/cloudmall_order", "/" + database);
    fixture =
        new JdbcTemplate(new DriverManagerDataSource(shanghaiUrl(isolatedUrl), "root", "root"));
    fixture.execute(
        String.valueOf(
            source.queryForMap("SHOW CREATE TABLE order_reservation_intent").get("Create Table")));
    mapper = new OrderReservationIntentMapper(fixture);
  }

  @AfterEach
  void removeFixture() {
    if (database != null && database.matches("cm17bp_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BP fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BP original Order tables and checksums unchanged");
  }

  @Test
  void delayedScanCannotSpendTheLeaseBeforeClaimStarts() {
    OrderReservationIntentPO original = insertIntent(1L);
    Map<String, Object> before = row(1L);
    OrderReservationIntentMapper observed = spy(mapper);
    AtomicReference<LocalDateTime> scanReadyAt = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Object candidates = invocation.callRealMethod();
              Thread.sleep(30);
              scanReadyAt.set(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
              return candidates;
            })
        .when(observed)
        .findRecoverable(any(), any(), anyInt());
    OrderSqlMapper orders = mock(OrderSqlMapper.class);
    when(orders.orderExists(anyString(), eq(original.orderNo)))
        .thenThrow(new DataAccessResourceFailureException("hold claimed facts"));
    StockClient stock = mock(StockClient.class);
    new OrderReservationRecovery(stock, observed, orders, new ObjectMapper())
        .recoverStaleReservations();
    Map<String, Object> after = row(1L);
    assertOriginalFacts(before, after);
    assertFreshLease(after, scanReadyAt.get());
    verifyNoInteractions(stock);
    verify(observed, never()).recordFailure(anyString(), anyString(), any());
    verify(observed, never()).markReleased(anyString(), any());
    LocalDateTime leaseUntil = timestamp(after.get("next_retry_at"));
    assertEquals(
        0,
        mapper.claimRecovery(
            original.orderNo, leaseUntil.minusNanos(1_000_000), leaseUntil.plusMinutes(1)));
    assertEquals(after, row(1L));
    assertEquals(1, mapper.claimRecovery(original.orderNo, leaseUntil, leaseUntil.plusMinutes(1)));
  }

  @Test
  void laterCandidateReceivesItsOwnMinuteAfterAnEarlierCandidateFailsSlowly() {
    OrderReservationIntentPO first = insertIntent(1L);
    OrderReservationIntentPO second = insertIntent(2L);
    Map<String, Object> before = row(2L);
    OrderReservationIntentMapper observed = spy(mapper);
    AtomicReference<LocalDateTime> predecessorFinishedAt = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Thread.sleep(30);
              predecessorFinishedAt.set(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
              throw new DataAccessResourceFailureException("slow predecessor finish");
            })
        .when(observed)
        .forceCommitted(eq(first.orderNo), any());
    OrderSqlMapper orders = mock(OrderSqlMapper.class);
    when(orders.orderExists(anyString(), eq(first.orderNo))).thenReturn(true);
    when(orders.orderExists(anyString(), eq(second.orderNo)))
        .thenThrow(new DataAccessResourceFailureException("hold second claimed facts"));
    StockClient stock = mock(StockClient.class);
    new OrderReservationRecovery(stock, observed, orders, new ObjectMapper())
        .recoverStaleReservations();
    Map<String, Object> after = row(2L);
    assertOriginalFacts(before, after);
    assertFreshLease(after, predecessorFinishedAt.get());
    verify(observed).forceCommitted(eq(first.orderNo), any());
    verify(observed).claimRecovery(eq(second.orderNo), any(), any());
    verify(observed, never()).recordFailure(anyString(), anyString(), any());
    verifyNoInteractions(stock);
  }

  /** 准备两分钟窗口外的合法未完成意图，时间只在本例随机库中写入。 */
  private OrderReservationIntentPO insertIntent(long identity) {
    OrderReservationIntentPO intent = new OrderReservationIntentPO();
    intent.id = identity;
    intent.orderNo = "202610claimtime" + identity;
    intent.userId = 7L;
    intent.idempotencyKey = "claim-time-" + identity;
    intent.itemsSnapshot = "[{\"skuId\":7,\"quantity\":2}]";
    intent.status = "RESERVE_UNKNOWN";
    intent.createdAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(5).withNano(0);
    intent.updatedAt = intent.createdAt;
    assertEquals(1, mapper.insertIfAbsent(intent));
    return intent;
  }

  /** 原数据行完整快照。 */
  private Map<String, Object> row(long identity) {
    return fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=?", identity);
  }

  /** 已提交认领只改变租约元数据，不猜订单提交结果或保存业务错误。 */
  private static void assertOriginalFacts(Map<String, Object> before, Map<String, Object> after) {
    for (String column :
        List.of(
            "id",
            "order_no",
            "user_id",
            "idempotency_key",
            "items_snapshot",
            "status",
            "created_at",
            "last_error")) assertEquals(before.get(column), after.get(column), column);
  }

  /** 数据库毫秒列沿原绑定语义，候选准备后仍获得完整一分钟，并非从scan起算。 */
  private static void assertFreshLease(Map<String, Object> row, LocalDateTime readyAt) {
    LocalDateTime earliestLease =
        readyAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS).plusMinutes(1);
    LocalDateTime leaseUntil = timestamp(row.get("next_retry_at"));
    assertFalse(
        leaseUntil.isBefore(earliestLease),
        "lease " + leaseUntil + " precedes candidate-ready bound " + earliestLease);
    assertEquals(timestamp(row.get("updated_at")).plusMinutes(1), leaseUntil);
  }

  /** Connector/J Map对DATETIME可能提供LocalDateTime或Timestamp，不改变时间比较断言。 */
  private static LocalDateTime timestamp(Object value) {
    return value instanceof LocalDateTime
        ? (LocalDateTime) value
        : ((java.sql.Timestamp) value).toLocalDateTime();
  }

  /** 原库只读表清单及校验和。 */
  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> result =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_order"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_order", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表名");
      result.addAll(source.queryForList("CHECKSUM TABLE cloudmall_order." + table));
    }
    return result;
  }

  /** JDBC会话固定上海DATETIME语义。 */
  private static String shanghaiUrl(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }
}
