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
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 库存动作错误文本的真实隔离MySQL验证，Stock异常为替身。 */
class OrderStockActionErrorMySqlIT {
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
    database = "cm17ay_" + UUID.randomUUID().toString().replace("-", "");
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
    if (database != null && database.matches("cm17ay_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17AY fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17AY original Order tables and checksums unchanged");
  }

  @Test
  void finishAndRetryPreserveCompleteCharactersInBothSqlModes() throws Exception {
    List<String[]> samples =
        List.of(
            new String[] {null, null},
            new String[] {"", ""},
            new String[] {" \t", " \t"},
            new String[] {"短消息😀", "短消息😀"},
            new String[] {"a".repeat(511) + "😀", "a".repeat(511) + "😀"},
            new String[] {"😀".repeat(512), "😀".repeat(512)},
            new String[] {"😀".repeat(513), "😀".repeat(512)},
            new String[] {"a".repeat(510) + "😀中尾", "a".repeat(510) + "😀中"},
            new String[] {"a".repeat(512), "a".repeat(512)},
            new String[] {"a".repeat(513), "a".repeat(512)},
            new String[] {"中".repeat(512), "中".repeat(512)},
            new String[] {"中".repeat(513), "中".repeat(512)});
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 18, 0, 0, 123_000_000);
    LocalDateTime retryAt = now.plusMinutes(1);
    List<Executable> checks = new ArrayList<>();
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (String operation : List.of("finish", "retry")) {
          for (String status : List.of("PENDING", "UNKNOWN")) {
            for (String[] sample : samples) {
              modeJdbc.update("DELETE FROM order_stock_action");
              assertEquals(
                  1, modeMapper.insert(1L, "202610error1", "CONFIRM", now.minusMinutes(2)));
              assertEquals(1, modeMapper.claim(1L, "unicode-owner", now));
              modeJdbc.update("UPDATE order_stock_action SET status=? WHERE id=1", status);
              var before = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
              int changed =
                  "finish".equals(operation)
                      ? modeMapper.finish(1L, "unicode-owner", "CONFLICT", sample[0], now)
                      : modeMapper.retry(1L, "unicode-owner", sample[0], retryAt, now);
              assertEquals(1, changed);
              var after = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
              checks.add(
                  () ->
                      assertEquals(sample[1], after.get("last_error"), mode + operation + status));
              assertSameFacts(before, after);
              assertEquals(
                  "finish".equals(operation) ? "CONFLICT" : "UNKNOWN", after.get("status"));
              assertNull(after.get("lease_owner"));
              assertNull(after.get("lease_until"));
              assertEquals(1, ((Number) after.get("attempts")).intValue());
              assertEquals(
                  now,
                  modeJdbc.queryForObject(
                      "SELECT updated_at FROM order_stock_action", LocalDateTime.class));
              assertEquals(
                  "finish".equals(operation) ? before.get("next_retry_at") : retryAt,
                  after.get("next_retry_at"));
            }
          }
        }
      }
    }
    assertAll(checks);
  }

  @Test
  void recoveryRecordsTheFullErrorPrefixAndContinuesWithTheHealthyAction() {
    String prefix = "java.lang.IllegalStateException: ";
    List<String[]> samples =
        List.of(
            new String[] {
              "a".repeat(511 - prefix.length()) + "😀尾",
              prefix + "a".repeat(511 - prefix.length()) + "😀"
            },
            new String[] {"😀".repeat(512), prefix + "😀".repeat(512 - prefix.length())});
    List<Executable> checks = new ArrayList<>();
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      for (String[] sample : samples) {
        fixture.update("DELETE FROM order_stock_action");
        LocalDateTime created = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
        assertEquals(1, mapper.insert(1L, "202610error1", actionType, created));
        assertEquals(1, mapper.insert(2L, "202610healthy2", actionType, created));
        var before = fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
        StockClient stock = mock(StockClient.class);
        when(stock.reservation("202610error1")).thenThrow(new IllegalStateException(sample[0]));
        when(stock.reservation("202610healthy2"))
            .thenReturn(
                fact("202610healthy2", "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK"));

        assertDoesNotThrow(new OrderStockActionRecovery(mapper, stock)::recoverDue);

        var unknown = fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
        checks.add(() -> assertEquals(sample[1], unknown.get("last_error"), actionType));
        assertSameFacts(before, unknown);
        assertEquals("UNKNOWN", unknown.get("status"));
        assertEquals(1, ((Number) unknown.get("attempts")).intValue());
        assertNull(unknown.get("lease_owner"));
        assertNull(unknown.get("lease_until"));
        assertTrue(
            fixture
                .queryForObject(
                    "SELECT next_retry_at FROM order_stock_action WHERE id=1", LocalDateTime.class)
                .isAfter(LocalDateTime.now(ZoneOffset.ofHours(8))));
        assertEquals(
            "SUCCEEDED",
            fixture.queryForObject(
                "SELECT status FROM order_stock_action WHERE id=2", String.class));
        verify(stock).reservation("202610error1");
        verify(stock).reservation("202610healthy2");
        verify(stock, never()).confirm(anyString(), any(StockClient.Action.class));
        verify(stock, never()).rollback(anyString(), any(StockClient.Action.class));
      }
    }
    assertAll(checks);
  }

  @Test
  void unmatchedOwnersStatesAndMissingIdsRemainCompletelyUntouched() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.ofHours(8));
    for (String status : List.of("PENDING", "UNKNOWN", "SUCCEEDED", "CONFLICT", "OTHER")) {
      fixture.update("DELETE FROM order_stock_action");
      assertEquals(1, mapper.insert(1L, "202610error1", "CONFIRM", now.minusMinutes(1)));
      assertEquals(1, mapper.claim(1L, "unicode-owner", now));
      fixture.update("UPDATE order_stock_action SET status=? WHERE id=1", status);
      var before = fixture.queryForMap("SELECT * FROM order_stock_action");
      for (String wrongOwner : List.of("other-owner", "")) {
        assertEquals(0, mapper.finish(1L, wrongOwner, "CONFLICT", "😀".repeat(513), now));
        assertEquals(0, mapper.retry(1L, wrongOwner, "😀".repeat(513), now.plusMinutes(1), now));
      }
      if (!List.of("PENDING", "UNKNOWN").contains(status)) {
        assertEquals(0, mapper.finish(1L, "unicode-owner", "CONFLICT", "😀".repeat(513), now));
        assertEquals(
            0, mapper.retry(1L, "unicode-owner", "😀".repeat(513), now.plusMinutes(1), now));
      }
      assertEquals(0, mapper.finish(999L, "unicode-owner", "CONFLICT", "😀".repeat(513), now));
      assertEquals(
          0, mapper.retry(999L, "unicode-owner", "😀".repeat(513), now.plusMinutes(1), now));
      assertEquals(before, fixture.queryForMap("SELECT * FROM order_stock_action"));
      fixture.update("UPDATE order_stock_action SET lease_owner=NULL WHERE id=1");
      var unowned = fixture.queryForMap("SELECT * FROM order_stock_action");
      assertEquals(0, mapper.finish(1L, "unicode-owner", "CONFLICT", "😀".repeat(513), now));
      assertEquals(0, mapper.retry(1L, "unicode-owner", "😀".repeat(513), now.plusMinutes(1), now));
      assertEquals(unowned, fixture.queryForMap("SELECT * FROM order_stock_action"));
    }
  }

  @Test
  void sqlFailuresStillPropagateAndPreserveTheClaimedRow() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.ofHours(8));
    assertEquals(1, mapper.insert(1L, "202610error1", "CONFIRM", now.minusMinutes(1)));
    assertEquals(1, mapper.claim(1L, "unicode-owner", now));
    var before = fixture.queryForMap("SELECT * FROM order_stock_action");
    fixture.execute(
        "CREATE TRIGGER cm17ay_update_failure BEFORE UPDATE ON order_stock_action FOR EACH ROW"
            + " SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cm17ay write rejected'");
    assertThrows(
        DataAccessException.class,
        () -> mapper.finish(1L, "unicode-owner", "CONFLICT", "😀".repeat(513), now));
    assertEquals(before, fixture.queryForMap("SELECT * FROM order_stock_action"));
    assertThrows(
        DataAccessException.class,
        () -> mapper.retry(1L, "unicode-owner", "😀".repeat(513), now.plusMinutes(1), now));
    assertEquals(before, fixture.queryForMap("SELECT * FROM order_stock_action"));
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
