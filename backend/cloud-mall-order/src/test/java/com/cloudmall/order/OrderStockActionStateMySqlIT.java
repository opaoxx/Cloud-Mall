package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.impl.stockaction.OrderStockActionRecovery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 动作状态精确匹配与终态写入的真实MySQL验证，Stock响应为替身。 */
class OrderStockActionStateMySqlIT {
  /** 原订单库只读核验和隔离库管理入口。 */
  private static JdbcTemplate source;

  /** 原订单库表清单和校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 本例自行创建、可精确清理的数据库名。 */
  private String database;

  /** 隔离库实际数据库事实验证入口。 */
  private JdbcTemplate fixture;

  /** 实际动作认领和状态持久层。 */
  private OrderStockActionMapper mapper;

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
    database = "cm17bm_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolatedUrl =
        System.getProperty("cloudmall.mysql.test.url").replace("/cloudmall_order", "/" + database);
    fixture =
        new JdbcTemplate(new DriverManagerDataSource(shanghaiUrl(isolatedUrl), "root", "root"));
    fixture.execute(
        String.valueOf(
            source.queryForMap("SHOW CREATE TABLE order_stock_action").get("Create Table")));
    mapper = new OrderStockActionMapper(fixture);
  }

  @AfterEach
  void removeFixture() {
    if (database != null && database.matches("cm17bm_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BM fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BM original Order tables and checksums unchanged");
  }

  @Test
  void sourceStateAliasesAreExcludedBeforeLimitAndNeverReachStock() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String state :
        List.of(
            "pending",
            "unknown",
            "PENDING ",
            "UNKNOWN ",
            "PÉNDING",
            "ÚNKNOWN",
            "pending ",
            "unknown ")) {
      seed(state, now, false);
      assertEquals(
          1,
          fixture.queryForObject(
              "SELECT COUNT(*) FROM order_stock_action WHERE id=1 AND status IN"
                  + " ('PENDING','UNKNOWN')",
              Integer.class));
      assertEquals(1, mapper.insert(2L, "202610healthy2", "CONFIRM", now.minusMinutes(1)));
      var before = row();
      var limited = mapper.findDue(now, 1);
      Long candidate = limited.isEmpty() ? null : limited.get(0).id;
      StockClient stock = mock(StockClient.class);
      when(stock.reservation("202610state1")).thenReturn(fact("202610state1", "CONFIRMED"));
      when(stock.reservation("202610healthy2")).thenReturn(fact("202610healthy2", "CONFIRMED"));
      // Fixed fixture time is in the past, so the actual scheduler sees both eligible records.
      new OrderStockActionRecovery(mapper, stock).recoverDue();
      var after = row();
      checks.add(() -> assertEquals(2L, candidate, state));
      checks.add(() -> assertEquals(before, after, state));
      checks.add(() -> verify(stock, never()).reservation("202610state1"));
      assertEquals(
          "SUCCEEDED",
          fixture.queryForObject("SELECT status FROM order_stock_action WHERE id=2", String.class));
    }
    assertAll(checks);
  }

  @Test
  void sourceStateAliasesCannotClaimFinishOrRetry() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String state :
        List.of(
            "pending",
            "unknown",
            "PENDING ",
            "UNKNOWN ",
            "PÉNDING",
            "ÚNKNOWN",
            "pending ",
            "unknown ")) {
      for (String operation : List.of("claim", "finish", "retry")) {
        seed(state, now, !"claim".equals(operation));
        var before = row();
        int changed =
            "claim".equals(operation)
                ? mapper.claim(1L, "state-owner", now)
                : "finish".equals(operation)
                    ? mapper.finish(1L, "state-owner", "SUCCEEDED", null, now)
                    : mapper.retry(1L, "state-owner", "unknown", now.plusMinutes(1), now);
        var after = row();
        checks.add(() -> assertEquals(0, changed, state + operation));
        checks.add(() -> assertEquals(before, after, state + operation));
      }
    }
    assertAll(checks);
  }

  @Test
  void illegalTargetsKeepTheLeaseAndEveryColumnInEitherSqlMode() throws Exception {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        for (String target :
            java.util.Arrays.asList(
                null,
                "",
                " \t",
                "succeeded",
                "conflict",
                "SUCCEEDED ",
                "CÓNFLICT",
                "OTHER",
                "PENDING",
                "UNKNOWN",
                "a".repeat(17))) {
          checks.add(
              () -> {
                modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
                modeJdbc.update("DELETE FROM order_stock_action");
                assertEquals(
                    1, modeMapper.insert(1L, "202610state1", "CONFIRM", now.minusMinutes(1)));
                assertEquals(1, modeMapper.claim(1L, "state-owner", now));
                var before = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
                int changed =
                    assertDoesNotThrow(
                        () -> modeMapper.finish(1L, "state-owner", target, "invalid target", now));
                var after = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
                assertAll(() -> assertEquals(0, changed), () -> assertEquals(before, after));
              });
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void canonicalSourcesAndTerminalTargetsKeepTheOriginalCasEffects() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    for (String sourceState : List.of("PENDING", "UNKNOWN")) {
      for (String target : List.of("SUCCEEDED", "CONFLICT")) {
        seed(sourceState, now, true);
        var before = row();
        assertEquals(
            1,
            mapper.finish(
                1L,
                "state-owner",
                target,
                "CONFLICT".equals(target) ? "actual conflict" : null,
                now));
        var after = row();
        assertSameIdentity(before, after);
        assertEquals(target, after.get("status"));
        assertNull(after.get("lease_owner"));
        assertNull(after.get("lease_until"));
        assertEquals(1, ((Number) after.get("attempts")).intValue());
      }
    }
  }

  /** 只在自建随机库构造历史坏状态，生产入口和原始表不被改变。 */
  private void seed(String state, LocalDateTime now, boolean claimed) {
    fixture.update("DELETE FROM order_stock_action");
    assertEquals(1, mapper.insert(1L, "202610state1", "CONFIRM", now.minusMinutes(1)));
    if (claimed) assertEquals(1, mapper.claim(1L, "state-owner", now));
    fixture.update("UPDATE order_stock_action SET status=? WHERE id=1", state);
  }

  /** 当前动作的完整快照包含状态、租约、错误、计数和所有时间。 */
  private Map<String, Object> row() {
    return fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
  }

  /** 原订单号、方向、操作键和创建时间在错误恢复中均保持。 */
  private static void assertSameIdentity(Map<String, Object> before, Map<String, Object> after) {
    for (String column : List.of("id", "order_no", "action_type", "operation_key", "created_at"))
      assertEquals(before.get(column), after.get(column), column);
  }

  /** 原Stock响应结构，不根据请求自动修复错误身份。 */
  private static ApiResponse<StockReservationStatusVO> fact(String orderNo, String status) {
    StockReservationStatusVO fact = new StockReservationStatusVO();
    fact.orderNo = orderNo;
    fact.status = status;
    fact.items = List.of();
    return ApiResponse.ok(fact);
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
