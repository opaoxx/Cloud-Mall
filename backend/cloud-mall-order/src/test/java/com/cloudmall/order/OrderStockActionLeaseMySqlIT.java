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
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 库存动作有效租约及旧worker完成写的真实MySQL验证，Stock调用为替身。 */
class OrderStockActionLeaseMySqlIT {
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
    database = "cm17az_" + UUID.randomUUID().toString().replace("-", "");
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
    if (database != null && database.matches("cm17az_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17AZ fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17AZ original Order tables and checksums unchanged");
  }

  @Test
  void expiredEqualNullLeasesAndOwnerAliasesCannotFinishOrRetry() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 18, 0, 0, 123_000_000);
    List<Executable> checks = new ArrayList<>();
    for (String operation : List.of("finish", "retry")) {
      for (String status : List.of("PENDING", "UNKNOWN")) {
        for (String reason :
            List.of("expired", "equal", "null-lease", "case", "accent", "trailing", "other")) {
          seed(now);
          fixture.update("UPDATE order_stock_action SET status=? WHERE id=1", status);
          String owner = "lease-owner";
          if ("expired".equals(reason))
            fixture.update(
                "UPDATE order_stock_action SET lease_until=? WHERE id=1",
                now.minusNanos(1_000_000));
          if ("equal".equals(reason))
            fixture.update("UPDATE order_stock_action SET lease_until=? WHERE id=1", now);
          if ("null-lease".equals(reason))
            fixture.update("UPDATE order_stock_action SET lease_until=NULL WHERE id=1");
          if ("case".equals(reason)) owner = "LEASE-OWNER";
          if ("accent".equals(reason)) owner = "léase-owner";
          if ("trailing".equals(reason)) owner = "lease-owner ";
          if ("other".equals(reason)) owner = "other-owner";
          var before = row();
          int changed = complete(operation, owner, now);
          var after = row();
          checks.add(() -> assertEquals(0, changed, operation + status + reason));
          checks.add(() -> assertEquals(before, after, operation + status + reason));
        }
      }
    }
    assertAll(checks);
  }

  @Test
  void validLeasesKeepTheOriginalCompletionAndRetryEffects() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 18, 0, 0, 123_000_000);
    for (String operation : List.of("finish", "retry")) {
      for (String status : List.of("PENDING", "UNKNOWN")) {
        seed(now);
        fixture.update("UPDATE order_stock_action SET status=? WHERE id=1", status);
        var before = row();
        assertEquals(1, complete(operation, "lease-owner", now));
        var after = row();
        assertSameFacts(before, after);
        assertEquals("finish".equals(operation) ? "CONFLICT" : "UNKNOWN", after.get("status"));
        assertEquals("late😀", after.get("last_error"));
        assertNull(after.get("lease_owner"));
        assertNull(after.get("lease_until"));
        assertEquals(1, ((Number) after.get("attempts")).intValue());
        assertEquals(
            now,
            fixture.queryForObject(
                "SELECT updated_at FROM order_stock_action WHERE id=1", LocalDateTime.class));
        assertEquals(
            "finish".equals(operation) ? before.get("next_retry_at") : now.plusMinutes(1),
            after.get("next_retry_at"));
      }
    }
  }

  @Test
  void nullBlankOwnersTerminalRowsAndMissingIdsCannotBeWritten() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 18, 0, 0, 123_000_000);
    List<Executable> checks = new ArrayList<>();
    for (String operation : List.of("finish", "retry")) {
      for (String owner : java.util.Arrays.asList(null, "", " \t")) {
        seed(now);
        fixture.update("UPDATE order_stock_action SET lease_owner=? WHERE id=1", owner);
        var before = row();
        int changed = complete(operation, owner, now);
        var after = row();
        checks.add(() -> assertEquals(0, changed, operation + owner));
        checks.add(() -> assertEquals(before, after, operation + owner));
      }
      for (String status : List.of("SUCCEEDED", "CONFLICT", "OTHER")) {
        seed(now);
        fixture.update("UPDATE order_stock_action SET status=? WHERE id=1", status);
        var before = row();
        assertEquals(0, complete(operation, "lease-owner", now));
        assertEquals(before, row());
      }
      seed(now);
      var before = row();
      assertEquals(0, mapper.finish(999L, "lease-owner", "SUCCEEDED", null, now));
      assertEquals(0, mapper.retry(999L, "lease-owner", "late", now.plusMinutes(1), now));
      assertEquals(before, row());
    }
    assertAll(checks);
  }

  @Test
  void concurrentClaimAndTakeoverKeepTheNewOwnerAuthoritative() throws Exception {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 18, 0, 0, 123_000_000);
    fixture.update("DELETE FROM order_stock_action");
    assertEquals(1, mapper.insert(1L, "202610lease1", "CONFIRM", now.minusMinutes(1)));
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
      assertEquals(1, first.get(5, TimeUnit.SECONDS) + second.get(5, TimeUnit.SECONDS));
    } finally {
      start.countDown();
      workers.shutdownNow();
      assertTrue(workers.awaitTermination(5, TimeUnit.SECONDS));
    }
    String oldOwner = String.valueOf(row().get("lease_owner"));
    assertEquals(1, ((Number) row().get("attempts")).intValue());
    assertEquals(0, mapper.claim(1L, "new-worker", now.plusSeconds(30)));
    assertEquals(1, mapper.claim(1L, "new-worker", now.plusSeconds(30).plusNanos(1_000_000)));
    var takeover = row();
    assertEquals(0, mapper.finish(1L, oldOwner, "SUCCEEDED", null, now.plusSeconds(31)));
    assertEquals(0, mapper.retry(1L, oldOwner, "late", now.plusMinutes(2), now.plusSeconds(31)));
    assertEquals(takeover, row());
    assertEquals(2, ((Number) takeover.get("attempts")).intValue());
    assertEquals(1, mapper.finish(1L, "new-worker", "SUCCEEDED", null, now.plusSeconds(31)));
    var completed = row();
    assertSameFacts(takeover, completed);
    assertEquals("SUCCEEDED", completed.get("status"));
    assertNull(completed.get("lease_owner"));
    assertNull(completed.get("lease_until"));
    assertEquals(2, ((Number) completed.get("attempts")).intValue());
  }

  @Test
  void lateRecoveryResultsCannotChangeExpiredOrTakenOverRows() {
    List<Executable> checks = new ArrayList<>();
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      String target = "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK";
      String opposite = "CONFIRM".equals(actionType) ? "ROLLED_BACK" : "CONFIRMED";
      for (boolean takeover : List.of(false, true)) {
        for (String branch :
            List.of("target", "conflict", "error", "written-target", "written-conflict")) {
          fixture.update("DELETE FROM order_stock_action");
          LocalDateTime created = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
          assertEquals(1, mapper.insert(1L, "202610lease1", actionType, created));
          assertEquals(1, mapper.insert(2L, "202610healthy2", actionType, created));
          AtomicReference<Map<String, Object>> protectedRow = new AtomicReference<>();
          StockClient stock = mock(StockClient.class);
          org.mockito.stubbing.Answer<ApiResponse<StockReservationStatusVO>> lateReply =
              invocation -> {
                LocalDateTime changedAt = LocalDateTime.now(ZoneOffset.ofHours(8));
                fixture.update(
                    "UPDATE order_stock_action SET lease_until=? WHERE id=1",
                    changedAt.minusSeconds(1));
                if (takeover) assertEquals(1, mapper.claim(1L, "new-worker", changedAt));
                protectedRow.set(row());
                if ("error".equals(branch)) throw new IllegalStateException("late Stock reply");
                return fact("202610lease1", branch.contains("conflict") ? opposite : target);
              };
          if (branch.startsWith("written")) {
            when(stock.reservation("202610lease1"))
                .thenReturn(fact("202610lease1", "RESERVED"))
                .thenAnswer(lateReply);
            if ("CONFIRM".equals(actionType))
              doReturn(ApiResponse.ok("confirmed"))
                  .when(stock)
                  .confirm(eq("202610lease1"), any(StockClient.Action.class));
            else
              doReturn(ApiResponse.ok("rolledBack"))
                  .when(stock)
                  .rollback(eq("202610lease1"), any(StockClient.Action.class));
          } else when(stock.reservation("202610lease1")).thenAnswer(lateReply);
          when(stock.reservation("202610healthy2")).thenReturn(fact("202610healthy2", target));

          assertDoesNotThrow(new OrderStockActionRecovery(mapper, stock)::recoverDue);

          var after = row();
          assertNotNull(protectedRow.get());
          checks.add(() -> assertEquals(protectedRow.get(), after, actionType + branch + takeover));
          assertEquals(
              "SUCCEEDED",
              fixture.queryForObject(
                  "SELECT status FROM order_stock_action WHERE id=2", String.class));
          if (branch.startsWith("written")) {
            var request = org.mockito.ArgumentCaptor.forClass(StockClient.Action.class);
            if ("CONFIRM".equals(actionType)) {
              verify(stock).confirm(eq("202610lease1"), request.capture());
              verify(stock, never()).rollback(anyString(), any());
            } else {
              verify(stock).rollback(eq("202610lease1"), request.capture());
              verify(stock, never()).confirm(anyString(), any());
            }
            assertEquals("202610lease1:" + actionType, request.getValue().operationKey());
          } else {
            verify(stock, never()).confirm(anyString(), any());
            verify(stock, never()).rollback(anyString(), any());
          }
        }
      }
    }
    assertAll(checks);
  }

  /** 固定毫秒时间认领，所有修改仅在本例独立随机库。 */
  private void seed(LocalDateTime now) {
    fixture.update("DELETE FROM order_stock_action");
    assertEquals(1, mapper.insert(1L, "202610lease1", "CONFIRM", now.minusMinutes(1)));
    assertEquals(1, mapper.claim(1L, "lease-owner", now));
  }

  /** 统一两方法调用参数，测试比较完整行而不是只比较更新数。 */
  private int complete(String operation, String owner, LocalDateTime now) {
    return "finish".equals(operation)
        ? mapper.finish(1L, owner, "CONFLICT", "late😀", now)
        : mapper.retry(1L, owner, "late😀", now.plusMinutes(1), now);
  }

  /** 原动作完整快照，不以查询条件隐藏不合法字段。 */
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
