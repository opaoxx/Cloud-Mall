package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.impl.OrderStockActionRecovery;
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
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 库存动作单条数据库故障隔离验证，SQL真实，Stock和提交后异常为替身。 */
class OrderStockActionDatabaseMySqlIT {
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
    database = "cm17ba_" + UUID.randomUUID().toString().replace("-", "");
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
    if (database != null && database.matches("cm17ba_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BA fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BA original Order tables and checksums unchanged");
  }

  @Test
  void aClaimSqlFailureDoesNotPreventTheHealthyFollower() {
    seed("CONFIRM");
    var before = row();
    fixture.execute(
        "CREATE TRIGGER cm17ba_claim_failure BEFORE UPDATE ON order_stock_action FOR EACH ROW BEGIN"
            + " IF OLD.id=1 AND NEW.attempts>OLD.attempts THEN SIGNAL SQLSTATE '45000' SET"
            + " MESSAGE_TEXT='cm17ba claim rejected'; END IF; END");
    StockClient stock = healthyStock("CONFIRM");
    var observedMapper = spy(mapper);

    assertDoesNotThrow(new OrderStockActionRecovery(observedMapper, stock)::recoverDue);

    assertEquals(before, row());
    assertHealthyFollower();
    verify(stock, never()).reservation("202610database1");
    verify(observedMapper, never()).retry(eq(1L), anyString(), anyString(), any(), any());
    verify(observedMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
  }

  @Test
  void failedFinishDoesNotWriteUnknownOrBlockTheFollower() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      String target = "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK";
      String opposite = "CONFIRM".equals(actionType) ? "ROLLED_BACK" : "CONFIRMED";
      for (String branch : List.of("target", "conflict", "written-target", "written-conflict")) {
        seed(actionType);
        fixture.execute("DROP TRIGGER IF EXISTS cm17ba_finish_failure");
        fixture.execute(
            "CREATE TRIGGER cm17ba_finish_failure BEFORE UPDATE ON order_stock_action FOR EACH ROW"
                + " BEGIN IF OLD.id=1 AND NEW.status IN ('SUCCEEDED','CONFLICT') THEN SIGNAL"
                + " SQLSTATE '45000' SET MESSAGE_TEXT='cm17ba finish rejected'; END IF; END");
        var claimedRow = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        StockClient stock = healthyStock(actionType);
        org.mockito.stubbing.Answer<ApiResponse<StockReservationStatusVO>> finishReply =
            invocation -> {
              claimedRow.set(row());
              return fact("202610database1", branch.contains("conflict") ? opposite : target);
            };
        if (branch.startsWith("written")) {
          when(stock.reservation("202610database1"))
              .thenReturn(fact("202610database1", "RESERVED"))
              .thenAnswer(finishReply);
          if ("CONFIRM".equals(actionType))
            doReturn(ApiResponse.ok("confirmed")).when(stock).confirm(eq("202610database1"), any());
          else
            doReturn(ApiResponse.ok("rolledBack"))
                .when(stock)
                .rollback(eq("202610database1"), any());
        } else when(stock.reservation("202610database1")).thenAnswer(finishReply);
        var observedMapper = spy(mapper);

        assertDoesNotThrow(new OrderStockActionRecovery(observedMapper, stock)::recoverDue);

        assertNotNull(claimedRow.get());
        assertEquals(claimedRow.get(), row(), actionType + branch);
        assertEquals("PENDING", row().get("status"));
        assertEquals(1, ((Number) row().get("attempts")).intValue());
        assertNotNull(row().get("lease_owner"));
        assertNotNull(row().get("lease_until"));
        assertHealthyFollower();
        verify(observedMapper)
            .finish(
                eq(1L),
                anyString(),
                eq(branch.contains("conflict") ? "CONFLICT" : "SUCCEEDED"),
                any(),
                any());
        verify(observedMapper, never()).retry(eq(1L), anyString(), anyString(), any(), any());
        if (branch.startsWith("written")) {
          var request = org.mockito.ArgumentCaptor.forClass(StockClient.Action.class);
          if ("CONFIRM".equals(actionType)) {
            verify(stock).confirm(eq("202610database1"), request.capture());
            verify(stock, never()).rollback(anyString(), any());
          } else {
            verify(stock).rollback(eq("202610database1"), request.capture());
            verify(stock, never()).confirm(anyString(), any());
          }
          assertEquals("202610database1:" + actionType, request.getValue().operationKey());
        } else {
          verify(stock, never()).confirm(anyString(), any());
          verify(stock, never()).rollback(anyString(), any());
        }
      }
    }
  }

  @Test
  void failedRetryPreservesTheClaimAndContinuesWithTheFollower() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      seed(actionType);
      fixture.execute("DROP TRIGGER IF EXISTS cm17ba_retry_failure");
      fixture.execute(
          "CREATE TRIGGER cm17ba_retry_failure BEFORE UPDATE ON order_stock_action FOR EACH ROW"
              + " BEGIN IF OLD.id=1 AND NEW.status='UNKNOWN' THEN SIGNAL SQLSTATE '45000' SET"
              + " MESSAGE_TEXT='cm17ba retry rejected'; END IF; END");
      StockClient stock = healthyStock(actionType);
      var claimedRow = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      when(stock.reservation("202610database1"))
          .thenAnswer(
              invocation -> {
                claimedRow.set(row());
                throw new IllegalStateException("cm17ba original Stock error");
              });
      var observedMapper = spy(mapper);

      assertDoesNotThrow(new OrderStockActionRecovery(observedMapper, stock)::recoverDue);

      assertEquals(claimedRow.get(), row());
      assertHealthyFollower();
      verify(observedMapper)
          .retry(eq(1L), anyString(), contains("cm17ba original Stock error"), any(), any());
      verify(observedMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
      verify(stock).reservation("202610database1");
      verify(stock, never()).confirm(anyString(), any());
      verify(stock, never()).rollback(anyString(), any());
    }
  }

  @Test
  void scanSqlFailureStillPropagatesWithoutAnyStockCall() {
    fixture.execute("DROP TABLE order_stock_action");
    StockClient stock = mock(StockClient.class);
    assertThrows(
        DataAccessException.class, new OrderStockActionRecovery(mapper, stock)::recoverDue);
    verifyNoInteractions(stock);
  }

  @Test
  void syntheticExceptionAfterARealCommittedFinishDoesNotCauseAnotherWrite() {
    seed("CONFIRM");
    var before = row();
    StockClient stock = healthyStock("CONFIRM");
    when(stock.reservation("202610database1")).thenReturn(fact("202610database1", "CONFIRMED"));
    var observedMapper = spy(mapper);
    doAnswer(
            invocation -> {
              assertEquals(1, invocation.callRealMethod());
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "synthetic error after real autocommit");
            })
        .when(observedMapper)
        .finish(eq(1L), anyString(), eq("SUCCEEDED"), any(), any());

    assertDoesNotThrow(new OrderStockActionRecovery(observedMapper, stock)::recoverDue);

    var after = row();
    assertSameFacts(before, after);
    assertEquals("SUCCEEDED", after.get("status"));
    assertEquals(1, ((Number) after.get("attempts")).intValue());
    assertNull(after.get("lease_owner"));
    assertNull(after.get("lease_until"));
    assertNull(after.get("last_error"));
    assertHealthyFollower();
    verify(observedMapper, never()).retry(eq(1L), anyString(), anyString(), any(), any());
    verify(stock).reservation("202610database1");
    verify(stock, never()).confirm(anyString(), any());
    verify(stock, never()).rollback(anyString(), any());
  }

  /** 第一条在排序前，两个动作均已到期；只写当前自行创建的随机库。 */
  private void seed(String actionType) {
    fixture.update("DELETE FROM order_stock_action");
    LocalDateTime created = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
    assertEquals(1, mapper.insert(1L, "202610database1", actionType, created));
    assertEquals(1, mapper.insert(2L, "202610healthy2", actionType, created));
  }

  /** 原动作完整快照，检查失败后已提交的认领事实是否保持。 */
  private Map<String, Object> row() {
    return fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=1");
  }

  /** Stock仅为替身，后健康动作直接读取原方向的正确终态。 */
  private static StockClient healthyStock(String actionType) {
    StockClient stock = mock(StockClient.class);
    when(stock.reservation("202610healthy2"))
        .thenReturn(
            fact("202610healthy2", "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK"));
    return stock;
  }

  /** 后候选没有被首条SQL异常饿死，且仅认领一次。 */
  private void assertHealthyFollower() {
    var healthy = fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=2");
    assertEquals("SUCCEEDED", healthy.get("status"));
    assertEquals(1, ((Number) healthy.get("attempts")).intValue());
    assertNull(healthy.get("lease_owner"));
    assertNull(healthy.get("lease_until"));
    assertNull(healthy.get("last_error"));
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
