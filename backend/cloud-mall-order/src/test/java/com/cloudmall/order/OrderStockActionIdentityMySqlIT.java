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
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 真实MySQL验证订单库存动作不根据错单响应终结，Stock调用为替身。 */
class OrderStockActionIdentityMySqlIT {
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
    database = "cm17av_" + UUID.randomUUID().toString().replace("-", "");
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
    if (database != null && database.matches("cm17av_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17AV fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17AV original Order tables and checksums unchanged");
  }

  @Test
  void wrongFirstIdentityRetainsUnknownAndCorrectFactCanResolveLater() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      fixture.update("DELETE FROM order_stock_action");
      LocalDateTime created = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
      assertEquals(1, mapper.insert(1L, "202610action1", actionType, created));
      var before = fixture.queryForMap("SELECT * FROM order_stock_action");
      StockClient stock = mock(StockClient.class);
      String target = "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK";
      when(stock.reservation("202610action1")).thenReturn(fact("202610other", target));
      var recovery = new OrderStockActionRecovery(mapper, stock);

      recovery.recoverDue();

      var unknown = fixture.queryForMap("SELECT * FROM order_stock_action");
      assertUnknownAction(before, unknown);
      verify(stock, never()).confirm(anyString(), any(StockClient.Action.class));
      verify(stock, never()).rollback(anyString(), any(StockClient.Action.class));
      when(stock.reservation("202610action1")).thenReturn(fact("202610action1", target));
      fixture.update(
          "UPDATE order_stock_action SET next_retry_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND)");
      recovery.recoverDue();
      var succeeded = fixture.queryForMap("SELECT * FROM order_stock_action");
      assertSameIdentity(before, succeeded);
      assertEquals("SUCCEEDED", succeeded.get("status"));
      assertEquals(2, ((Number) succeeded.get("attempts")).intValue());
      assertNull(succeeded.get("last_error"));
      assertNull(succeeded.get("lease_owner"));
      assertNull(succeeded.get("lease_until"));
      verify(stock, never()).confirm(anyString(), any(StockClient.Action.class));
      verify(stock, never()).rollback(anyString(), any(StockClient.Action.class));
    }
  }

  @Test
  void wrongSecondIdentityCannotFinishAfterTheOriginalAuthorizedWrite() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      fixture.update("DELETE FROM order_stock_action");
      LocalDateTime created = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
      assertEquals(1, mapper.insert(1L, "202610action1", actionType, created));
      var before = fixture.queryForMap("SELECT * FROM order_stock_action");
      StockClient stock = mock(StockClient.class);
      String target = "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK";
      when(stock.reservation("202610action1"))
          .thenReturn(fact("202610action1", "RESERVED"), fact("202610other", target));
      if ("CONFIRM".equals(actionType))
        doReturn(ApiResponse.ok("confirmed"))
            .when(stock)
            .confirm(eq("202610action1"), any(StockClient.Action.class));
      else
        doReturn(ApiResponse.ok("rolledBack"))
            .when(stock)
            .rollback(eq("202610action1"), any(StockClient.Action.class));
      var recovery = new OrderStockActionRecovery(mapper, stock);

      recovery.recoverDue();

      assertUnknownAction(before, fixture.queryForMap("SELECT * FROM order_stock_action"));
      var operation = ArgumentCaptor.forClass(StockClient.Action.class);
      if ("CONFIRM".equals(actionType)) {
        verify(stock).confirm(eq("202610action1"), operation.capture());
        verify(stock, never()).rollback(anyString(), any(StockClient.Action.class));
      } else {
        verify(stock).rollback(eq("202610action1"), operation.capture());
        verify(stock, never()).confirm(anyString(), any(StockClient.Action.class));
      }
      assertEquals(before.get("operation_key"), operation.getValue().operationKey());
      when(stock.reservation("202610action1")).thenReturn(fact("202610action1", target));
      fixture.update(
          "UPDATE order_stock_action SET next_retry_at=DATE_SUB(NOW(3), INTERVAL 1 SECOND)");
      recovery.recoverDue();
      var resolved = fixture.queryForMap("SELECT * FROM order_stock_action");
      assertSameIdentity(before, resolved);
      assertEquals("SUCCEEDED", resolved.get("status"));
      assertEquals(2, ((Number) resolved.get("attempts")).intValue());
      assertNull(resolved.get("last_error"));
      if ("CONFIRM".equals(actionType))
        verify(stock).confirm(eq("202610action1"), any(StockClient.Action.class));
      else verify(stock).rollback(eq("202610action1"), any(StockClient.Action.class));
    }
  }

  @Test
  void unsupportedStoredTypesRemainUnknownWithoutAnyStockCall() {
    for (String actionType :
        List.of("", " \t", "confirm", "rollback", "CONFIRM ", "ROLLBACK ", "CANCEL", "OTHER")) {
      fixture.update("DELETE FROM order_stock_action");
      LocalDateTime created = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
      insertHistoricalAction(1L, "202610unknown1", actionType, created);
      var before = fixture.queryForMap("SELECT * FROM order_stock_action");
      StockClient stock = mock(StockClient.class);
      when(stock.reservation("202610unknown1")).thenReturn(fact("202610unknown1", "ROLLED_BACK"));

      new OrderStockActionRecovery(mapper, stock).recoverDue();

      assertUnknownAction(before, fixture.queryForMap("SELECT * FROM order_stock_action"));
      verifyNoInteractions(stock);
    }
  }

  @Test
  void unsupportedFirstTypeDoesNotPreventAHealthyActionFromCompleting() {
    LocalDateTime created = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
    insertHistoricalAction(1L, "202610unknown1", "confirm", created);
    assertEquals(1, mapper.insert(2L, "202610healthy2", "CONFIRM", created));
    var firstBefore = fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
    StockClient stock = mock(StockClient.class);
    when(stock.reservation("202610unknown1")).thenReturn(fact("202610unknown1", "ROLLED_BACK"));
    when(stock.reservation("202610healthy2")).thenReturn(fact("202610healthy2", "CONFIRMED"));

    new OrderStockActionRecovery(mapper, stock).recoverDue();

    assertUnknownAction(
        firstBefore, fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=1"));
    var healthy = fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=2");
    assertEquals("SUCCEEDED", healthy.get("status"));
    assertEquals("202610healthy2:CONFIRM", healthy.get("operation_key"));
    assertEquals(1, ((Number) healthy.get("attempts")).intValue());
    verify(stock, never()).reservation("202610unknown1");
    verify(stock).reservation("202610healthy2");
    verify(stock, never()).confirm(anyString(), any(StockClient.Action.class));
    verify(stock, never()).rollback(anyString(), any(StockClient.Action.class));
  }

  @Test
  void malformedStoredKeysRemainUnknownEvenWhenStockReportsTheTargetState() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      String opposite = "CONFIRM".equals(actionType) ? "ROLLBACK" : "CONFIRM";
      String target = "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK";
      for (String wrongKey :
          List.of(
              "",
              " \t",
              "202610other:" + actionType,
              "202610action1:" + opposite,
              "202610action1:" + actionType.toLowerCase(java.util.Locale.ROOT),
              "202610action1:" + actionType + " ",
              "202610Action1:" + actionType)) {
        fixture.update("DELETE FROM order_stock_action");
        assertEquals(
            1,
            mapper.insert(
                1L,
                "202610action1",
                actionType,
                LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1)));
        fixture.update("UPDATE order_stock_action SET operation_key=? WHERE id=1", wrongKey);
        assertEquals(
            1,
            mapper.insert(
                2L,
                "202610healthy2",
                actionType,
                LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1)));
        var before = fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
        StockClient stock = mock(StockClient.class);
        when(stock.reservation("202610action1")).thenReturn(fact("202610action1", target));
        when(stock.reservation("202610healthy2")).thenReturn(fact("202610healthy2", target));

        new OrderStockActionRecovery(mapper, stock).recoverDue();

        assertUnknownAction(
            before, fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=1"));
        assertEquals(
            "SUCCEEDED",
            fixture.queryForObject(
                "SELECT status FROM order_stock_action WHERE id=2", String.class));
        verify(stock, never()).reservation("202610action1");
        verify(stock).reservation("202610healthy2");
        verify(stock, never()).confirm(anyString(), any(StockClient.Action.class));
        verify(stock, never()).rollback(anyString(), any(StockClient.Action.class));
      }
    }
  }

  @Test
  void blankStoredOrdersCannotBeCompletedByTheirCanonicalLookingKeys() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      for (String orderNo : List.of("", " \t")) {
        fixture.update("DELETE FROM order_stock_action");
        insertHistoricalAction(
            1L, orderNo, actionType, LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1));
        var before = fixture.queryForMap("SELECT * FROM order_stock_action");
        StockClient stock = mock(StockClient.class);
        when(stock.reservation(orderNo))
            .thenReturn(fact(orderNo, "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK"));

        new OrderStockActionRecovery(mapper, stock).recoverDue();

        assertUnknownAction(before, fixture.queryForMap("SELECT * FROM order_stock_action"));
        verifyNoInteractions(stock);
      }
    }
  }

  /** 仅在自行创建的随机库模拟旧坏行；新生产入口始终先写合法初始记录。 */
  private void insertHistoricalAction(
      long actionId, String historicalOrderNo, String historicalType, LocalDateTime createdAt) {
    assertEquals(1, mapper.insert(actionId, "202610fixture1", "CONFIRM", createdAt));
    fixture.update(
        "UPDATE order_stock_action SET order_no=?,action_type=?,operation_key=? WHERE id=?",
        historicalOrderNo,
        historicalType,
        historicalOrderNo + ":" + historicalType,
        actionId);
  }

  /** 错单只按原UNKNOWN重试更新元数据，原动作身份不能变化。 */
  private static void assertUnknownAction(Map<String, Object> before, Map<String, Object> after) {
    assertSameIdentity(before, after);
    assertEquals("UNKNOWN", after.get("status"));
    assertEquals(1, ((Number) after.get("attempts")).intValue());
    assertNotNull(after.get("last_error"));
    assertNotEquals(before.get("next_retry_at"), after.get("next_retry_at"));
    assertNull(after.get("lease_owner"));
    assertNull(after.get("lease_until"));
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
