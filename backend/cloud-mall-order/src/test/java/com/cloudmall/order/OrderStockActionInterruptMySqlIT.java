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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 库存动作中断检查点的真实SQL验证，Stock与中断为同步替身。 */
class OrderStockActionInterruptMySqlIT {
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
    database = "cm17bc_" + UUID.randomUUID().toString().replace("-", "");
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
    Thread.interrupted();
    if (database != null && database.matches("cm17bc_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BC fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BC original Order tables and checksums unchanged");
  }

  @Test
  void preInterruptedEntryDoesNotReadOrChangeTheFixture() {
    seed("CONFIRM");
    var before = fixture.queryForList("SELECT * FROM order_stock_action ORDER BY id");
    StockClient stock = healthyStock("CONFIRM");
    var observedMapper = spy(mapper);
    Thread.currentThread().interrupt();
    try {
      new OrderStockActionRecovery(observedMapper, stock).recoverDue();
      assertTrue(Thread.currentThread().isInterrupted());
      verifyNoInteractions(stock);
      verify(observedMapper, never()).findDue(any(), anyInt());
      verify(observedMapper, never()).claim(anyLong(), anyString(), any());
    } finally {
      Thread.interrupted();
    }
    assertEquals(before, fixture.queryForList("SELECT * FROM order_stock_action ORDER BY id"));
  }

  @Test
  void interruptionAfterARealClaimRetainsItsLeaseAndStopsAllStockWork() {
    seed("CONFIRM");
    StockClient stock = healthyStock("CONFIRM");
    var observedMapper = spy(mapper);
    var claimedRow = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
    doAnswer(
            invocation -> {
              Object changed = invocation.callRealMethod();
              claimedRow.set(row());
              Thread.currentThread().interrupt();
              return changed;
            })
        .when(observedMapper)
        .claim(eq(1L), anyString(), any());
    var recovery = new OrderStockActionRecovery(observedMapper, stock);
    try {
      recovery.recoverDue();
      assertTrue(Thread.currentThread().isInterrupted());
      verifyNoInteractions(stock);
      verify(observedMapper, never()).claim(eq(2L), anyString(), any());
    } finally {
      Thread.interrupted();
    }
    assertEquals(claimedRow.get(), row());
    assertEquals("PENDING", row().get("status"));
    assertEquals(1, ((Number) row().get("attempts")).intValue());
    assertNotNull(row().get("lease_owner"));
    assertNotNull(row().get("lease_until"));
    assertFollowerPending();
    recovery.recoverDue();
    assertHealthyFollower();
    assertEquals(claimedRow.get(), row());
  }

  @Test
  void interruptedStockRepliesKeepTheClaimAndDoNotAppendCompletionWrites() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      for (String phase : List.of("GET", "ACTION", "SECOND_GET")) {
        seed(actionType);
        String target = "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK";
        StockClient stock = healthyStock(actionType);
        var protectedRow = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        org.mockito.stubbing.Answer<ApiResponse<StockReservationStatusVO>> statusSignal =
            invocation -> {
              protectedRow.set(row());
              Thread.currentThread().interrupt();
              return fact("202610database1", "GET".equals(phase) ? "RESERVED" : target);
            };
        if ("GET".equals(phase))
          when(stock.reservation("202610database1")).thenAnswer(statusSignal);
        else if ("SECOND_GET".equals(phase))
          when(stock.reservation("202610database1"))
              .thenReturn(fact("202610database1", "RESERVED"))
              .thenAnswer(statusSignal);
        else
          when(stock.reservation("202610database1"))
              .thenReturn(fact("202610database1", "RESERVED"), fact("202610database1", target));
        org.mockito.stubbing.Answer<ApiResponse<?>> actionReply =
            invocation -> {
              if ("ACTION".equals(phase)) {
                protectedRow.set(row());
                Thread.currentThread().interrupt();
              }
              return ApiResponse.ok("action accepted");
            };
        if ("CONFIRM".equals(actionType))
          doAnswer(actionReply).when(stock).confirm(eq("202610database1"), any());
        else doAnswer(actionReply).when(stock).rollback(eq("202610database1"), any());
        var observedMapper = spy(mapper);
        var recovery = new OrderStockActionRecovery(observedMapper, stock);
        try {
          recovery.recoverDue();
          assertTrue(Thread.currentThread().isInterrupted());
          verify(observedMapper, never()).claim(eq(2L), anyString(), any());
          verify(observedMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
          verify(observedMapper, never()).retry(eq(1L), anyString(), anyString(), any(), any());
        } finally {
          Thread.interrupted();
        }
        assertNotNull(protectedRow.get());
        assertEquals(protectedRow.get(), row(), actionType + phase);
        assertFollowerPending();
        if ("GET".equals(phase)) {
          verify(stock, never()).confirm(anyString(), any());
          verify(stock, never()).rollback(anyString(), any());
        } else {
          var operation = org.mockito.ArgumentCaptor.forClass(StockClient.Action.class);
          if ("CONFIRM".equals(actionType)) {
            verify(stock).confirm(eq("202610database1"), operation.capture());
            verify(stock, never()).rollback(anyString(), any());
          } else {
            verify(stock).rollback(eq("202610database1"), operation.capture());
            verify(stock, never()).confirm(anyString(), any());
          }
          assertEquals("202610database1:" + actionType, operation.getValue().operationKey());
        }
        recovery.recoverDue();
        assertHealthyFollower();
        assertEquals(protectedRow.get(), row());
      }
    }
  }

  @Test
  void interruptedCommittedFinishOrRetryPreservesItsResultAndStopsTheFollower() {
    for (String operation : List.of("finish", "retry")) {
      seed("CONFIRM");
      StockClient stock = healthyStock("CONFIRM");
      var observedMapper = spy(mapper);
      var committedRow = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      if ("finish".equals(operation)) {
        when(stock.reservation("202610database1")).thenReturn(fact("202610database1", "CONFIRMED"));
        doAnswer(
                invocation -> {
                  Object changed = invocation.callRealMethod();
                  committedRow.set(row());
                  Thread.currentThread().interrupt();
                  return changed;
                })
            .when(observedMapper)
            .finish(eq(1L), anyString(), anyString(), any(), any());
      } else {
        when(stock.reservation("202610database1"))
            .thenThrow(new IllegalStateException("original Stock unknown"));
        doAnswer(
                invocation -> {
                  Object changed = invocation.callRealMethod();
                  committedRow.set(row());
                  Thread.currentThread().interrupt();
                  return changed;
                })
            .when(observedMapper)
            .retry(eq(1L), anyString(), anyString(), any(), any());
      }
      var recovery = new OrderStockActionRecovery(observedMapper, stock);
      try {
        recovery.recoverDue();
        assertTrue(Thread.currentThread().isInterrupted());
        verify(observedMapper, never()).claim(eq(2L), anyString(), any());
      } finally {
        Thread.interrupted();
      }
      assertEquals(committedRow.get(), row());
      assertEquals("finish".equals(operation) ? "SUCCEEDED" : "UNKNOWN", row().get("status"));
      assertNull(row().get("lease_owner"));
      assertNull(row().get("lease_until"));
      assertFollowerPending();
      recovery.recoverDue();
      assertHealthyFollower();
      assertEquals(committedRow.get(), row());
      if ("finish".equals(operation))
        verify(observedMapper).finish(eq(1L), anyString(), anyString(), any(), any());
      else verify(observedMapper).retry(eq(1L), anyString(), anyString(), any(), any());
    }
  }

  /** 停止后后候选尚未被认领，避免把业务线程取消当作可继续批次。 */
  private void assertFollowerPending() {
    var follower = fixture.queryForMap("SELECT * FROM order_stock_action WHERE id=2");
    assertEquals("PENDING", follower.get("status"));
    assertEquals(0, ((Number) follower.get("attempts")).intValue());
    assertNull(follower.get("lease_owner"));
    assertNull(follower.get("lease_until"));
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

  /** 清除测试中断后，新一轮正常处理后候选且仅认领一次。 */
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
