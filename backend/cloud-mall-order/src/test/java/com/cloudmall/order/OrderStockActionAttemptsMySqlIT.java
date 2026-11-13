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
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 库存动作attempts饱和与负值隔离的真实MySQL验证，Stock调用为替身。 */
class OrderStockActionAttemptsMySqlIT {
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
    database = "cm17bb_" + UUID.randomUUID().toString().replace("-", "");
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
    if (database != null && database.matches("cm17bb_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BB fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BB original Order tables and checksums unchanged");
  }

  @Test
  void legalAttemptsSaturateAtIntMaxInBothSqlModes() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 18, 0, 0, 123_000_000);
    List<Executable> checks = new ArrayList<>();
    for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
      for (String status : List.of("PENDING", "UNKNOWN")) {
        for (int attempts : List.of(0, 1, Integer.MAX_VALUE - 1, Integer.MAX_VALUE)) {
          checks.add(
              () -> {
                try (var connection = fixture.getDataSource().getConnection()) {
                  var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
                  var modeMapper = new OrderStockActionMapper(modeJdbc);
                  modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
                  seed(modeJdbc, modeMapper, now, attempts, status);
                  var before = modeJdbc.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
                  assertEquals(
                      1, assertDoesNotThrow(() -> modeMapper.claim(1L, "first-owner", now)));
                  var firstClaim =
                      modeJdbc.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
                  assertSameFacts(before, firstClaim);
                  assertEquals(
                      attempts == Integer.MAX_VALUE ? Integer.MAX_VALUE : attempts + 1,
                      ((Number) firstClaim.get("attempts")).intValue());
                  assertEquals(status, firstClaim.get("status"));
                  assertEquals(before.get("next_retry_at"), firstClaim.get("next_retry_at"));
                  assertEquals("first-owner", firstClaim.get("lease_owner"));
                  assertEquals(now.plusSeconds(30), firstClaim.get("lease_until"));
                  assertEquals(now, firstClaim.get("updated_at"));
                  if (attempts >= Integer.MAX_VALUE - 1) {
                    assertEquals(
                        1,
                        assertDoesNotThrow(
                            () -> modeMapper.claim(1L, "second-owner", now.plusSeconds(31))));
                    var secondClaim =
                        modeJdbc.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
                    assertSameFacts(before, secondClaim);
                    assertEquals(
                        Integer.MAX_VALUE, ((Number) secondClaim.get("attempts")).intValue());
                    assertEquals("second-owner", secondClaim.get("lease_owner"));
                    assertEquals(now.plusSeconds(61), secondClaim.get("lease_until"));
                  }
                }
              });
        }
      }
    }
    assertAll(checks);
  }

  @Test
  void negativeAttemptsAreExcludedBeforeLimitAndDoNotReachStock() {
    for (int attempts : List.of(Integer.MIN_VALUE, -1)) {
      for (String status : List.of("PENDING", "UNKNOWN")) {
        LocalDateTime now = LocalDateTime.now(ZoneOffset.ofHours(8));
        seed(fixture, mapper, now, attempts, status);
        assertEquals(1, mapper.insert(2L, "202610healthy2", "CONFIRM", now.minusMinutes(1)));
        var before = row();
        var due = mapper.findDue(now, 1);
        assertEquals(1, due.size());
        assertEquals(2L, due.get(0).id);
        StockClient stock = mock(StockClient.class);
        when(stock.reservation("202610attempt1")).thenReturn(fact("202610attempt1", "CONFIRMED"));
        when(stock.reservation("202610healthy2")).thenReturn(fact("202610healthy2", "CONFIRMED"));

        new OrderStockActionRecovery(mapper, stock).recoverDue();

        assertEquals(before, row());
        assertEquals(
            "SUCCEEDED",
            fixture.queryForObject(
                "SELECT status FROM order_stock_action WHERE id=2", String.class));
        verify(stock, never()).reservation("202610attempt1");
        verify(stock).reservation("202610healthy2");
        verify(stock, never()).confirm(anyString(), any());
        verify(stock, never()).rollback(anyString(), any());
      }
    }
  }

  @Test
  void negativeAttemptsRejectAllDirectUpdatesWithoutChangingAnyColumn() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 18, 0, 0, 123_000_000);
    List<Executable> checks = new ArrayList<>();
    for (int attempts : List.of(Integer.MIN_VALUE, -1)) {
      for (String status : List.of("PENDING", "UNKNOWN")) {
        for (String operation : List.of("claim", "finish", "retry")) {
          seed(fixture, mapper, now, attempts, status);
          if (!"claim".equals(operation))
            fixture.update(
                "UPDATE order_stock_action SET lease_owner='negative-owner',lease_until=? WHERE"
                    + " id=1",
                now.plusSeconds(30));
          var before = row();
          int changed;
          if ("claim".equals(operation)) changed = mapper.claim(1L, "negative-owner", now);
          else if ("finish".equals(operation))
            changed = mapper.finish(1L, "negative-owner", "SUCCEEDED", "invalid count", now);
          else
            changed = mapper.retry(1L, "negative-owner", "invalid count", now.plusMinutes(1), now);
          var after = row();
          checks.add(() -> assertEquals(0, changed, status + operation + attempts));
          checks.add(() -> assertEquals(before, after, status + operation + attempts));
        }
      }
    }
    assertAll(checks);
  }

  @Test
  void maxAttemptsRecoverInBothDirectionsAndRetainTheOriginalBackoff() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      for (boolean stockFailure : List.of(false, true)) {
        LocalDateTime created = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
        fixture.update("DELETE FROM order_stock_action");
        assertEquals(1, mapper.insert(1L, "202610attempt1", actionType, created));
        assertEquals(1, mapper.insert(2L, "202610healthy2", actionType, created));
        fixture.update("UPDATE order_stock_action SET attempts=? WHERE id=1", Integer.MAX_VALUE);
        var before = row();
        StockClient stock = mock(StockClient.class);
        String target = "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK";
        if (stockFailure)
          when(stock.reservation("202610attempt1"))
              .thenThrow(new IllegalStateException("count is saturated, Stock is unknown"));
        else when(stock.reservation("202610attempt1")).thenReturn(fact("202610attempt1", target));
        when(stock.reservation("202610healthy2")).thenReturn(fact("202610healthy2", target));
        var recovery = new OrderStockActionRecovery(mapper, stock);
        LocalDateTime started = LocalDateTime.now(ZoneOffset.ofHours(8)).minusNanos(1_000_000);

        assertDoesNotThrow(recovery::recoverDue);

        LocalDateTime finished = LocalDateTime.now(ZoneOffset.ofHours(8)).plusNanos(1_000_000);
        var after = row();
        assertSameFacts(before, after);
        assertEquals(Integer.MAX_VALUE, ((Number) after.get("attempts")).intValue());
        assertNull(after.get("lease_owner"));
        assertNull(after.get("lease_until"));
        assertEquals(stockFailure ? "UNKNOWN" : "SUCCEEDED", after.get("status"));
        assertEquals(
            "SUCCEEDED",
            fixture.queryForObject(
                "SELECT status FROM order_stock_action WHERE id=2", String.class));
        if (stockFailure) {
          LocalDateTime retryAt =
              fixture.queryForObject(
                  "SELECT next_retry_at FROM order_stock_action WHERE id=1", LocalDateTime.class);
          assertFalse(retryAt.isBefore(started.plusSeconds(60)));
          assertFalse(retryAt.isAfter(finished.plusSeconds(60)));
          assertTrue(String.valueOf(after.get("last_error")).contains("count is saturated"));
          fixture.update("UPDATE order_stock_action SET next_retry_at=? WHERE id=1", created);
          doReturn(fact("202610attempt1", target)).when(stock).reservation("202610attempt1");
          recovery.recoverDue();
          assertEquals("SUCCEEDED", row().get("status"));
          assertEquals(Integer.MAX_VALUE, ((Number) row().get("attempts")).intValue());
          assertNull(row().get("last_error"));
          verify(stock, times(2)).reservation("202610attempt1");
        } else {
          assertNull(after.get("last_error"));
          verify(stock).reservation("202610attempt1");
        }
        verify(stock, never()).confirm(anyString(), any());
        verify(stock, never()).rollback(anyString(), any());
      }
    }
  }

  @Test
  void simultaneousClaimsAtMaxStillHaveExactlyOneWinner() throws Exception {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 18, 0, 0, 123_000_000);
    seed(fixture, mapper, now, Integer.MAX_VALUE, "PENDING");
    var before = row();
    var start = new CountDownLatch(1);
    var ready = new CountDownLatch(2);
    var workers = Executors.newFixedThreadPool(2);
    try {
      var first =
          workers.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mapper.claim(1L, "worker-one", now);
              });
      var second =
          workers.submit(
              () -> {
                ready.countDown();
                assertTrue(start.await(5, TimeUnit.SECONDS));
                return mapper.claim(1L, "worker-two", now);
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      start.countDown();
      int firstResult = assertDoesNotThrow(() -> first.get(5, TimeUnit.SECONDS));
      int secondResult = assertDoesNotThrow(() -> second.get(5, TimeUnit.SECONDS));
      assertEquals(1, firstResult + secondResult);
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
    var after = row();
    assertSameFacts(before, after);
    assertEquals(Integer.MAX_VALUE, ((Number) after.get("attempts")).intValue());
    assertTrue(List.of("worker-one", "worker-two").contains(after.get("lease_owner")));
    assertEquals(now.plusSeconds(30), after.get("lease_until"));
    assertEquals("PENDING", after.get("status"));
  }

  /** 仅在隔离库写计数边界，保留实际NOT NULL列和原表定义。 */
  private static void seed(
      JdbcTemplate jdbc,
      OrderStockActionMapper fixtureMapper,
      LocalDateTime now,
      int attempts,
      String status) {
    jdbc.update("DELETE FROM order_stock_action");
    assertEquals(1, fixtureMapper.insert(1L, "202610attempt1", "CONFIRM", now.minusMinutes(1)));
    jdbc.update("UPDATE order_stock_action SET attempts=?,status=? WHERE id=1", attempts, status);
  }

  /** 当前计数动作的完整数据库快照。 */
  private Map<String, Object> row() {
    return fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
  }

  /** 原动作身份、操作键及创建事实不能被错误记录改写。 */
  private static void assertSameFacts(Map<String, Object> before, Map<String, Object> after) {
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
