package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
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
import org.springframework.test.util.ReflectionTestUtils;

/** 存量订单支付状态响应身份的真实只读SQL验证，Stock响应为替身。 */
class OrderLegacyStockIdentityMySqlIT {
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

  /** 隔离月表真实订单读取/准备入口。 */
  private OrderSqlMapper sqlMapper;

  /** 不发真实HTTP的库存响应替身。 */
  private StockClient stock;

  /** 本地Service与真实订单/动作Mapper组成的只读支付状态链。 */
  private OrderServiceImpl service;

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
    database = "cm17bd_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolatedUrl =
        System.getProperty("cloudmall.mysql.test.url").replace("/cloudmall_order", "/" + database);
    fixture =
        new JdbcTemplate(new DriverManagerDataSource(shanghaiUrl(isolatedUrl), "root", "root"));
    fixture.execute(
        String.valueOf(
            source.queryForMap("SHOW CREATE TABLE order_stock_action").get("Create Table")));
    cloneTemplate("mall_order_template", "mall_order_202610");
    cloneTemplate("mall_order_item_template", "mall_order_item_202610");
    mapper = new OrderStockActionMapper(fixture);
    sqlMapper = new OrderSqlMapper(fixture);
    stock = mock(StockClient.class);
    service =
        new OrderServiceImpl(
            stock,
            mock(ProductClient.class),
            mock(UserClient.class),
            mock(CartClient.class),
            sqlMapper,
            new ObjectMapper());
    ReflectionTestUtils.setField(service, "orderStockActionMapper", mapper);
  }

  @AfterEach
  void removeFixture() {
    AuthContext.clear();
    if (database != null && database.matches("cm17bd_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BD fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BD original Order tables and checksums unchanged");
  }

  @Test
  void wrongIdentitiesStayUnknownAndCorrectResponsesCanSettleWithoutAnyWrite() {
    String orderNo = "202610legacy1";
    for (String orderStatus : List.of("PAID", "COMPLETED", "CANCELLED")) {
      String target = "CANCELLED".equals(orderStatus) ? "ROLLED_BACK" : "CONFIRMED";
      for (String responseOrderNo :
          java.util.Arrays.asList(
              null, "", " \t", "202610other", "202610LEGACY1", "202610légacy1", orderNo + " ")) {
        for (String responseStatus : List.of("CONFIRMED", "ROLLED_BACK", "REJECTED")) {
          seed(orderStatus);
          var before = fixtureSnapshot();
          when(stock.reservation(orderNo)).thenReturn(fact(responseOrderNo, responseStatus));

          var wrong = paymentState(8L);

          assertEquals(
              "UNKNOWN",
              wrong.get("stockActionStatus"),
              orderStatus + responseOrderNo + responseStatus);
          assertResponseFacts(wrong, orderStatus);
          assertEquals(before, fixtureSnapshot());
          when(stock.reservation(orderNo)).thenReturn(fact(orderNo, target));
          var matched = paymentState(8L);
          assertEquals("SUCCEEDED", matched.get("stockActionStatus"));
          assertResponseFacts(matched, orderStatus);
          assertEquals(before, fixtureSnapshot());
        }
      }
    }
    verify(stock, never()).confirm(anyString(), any());
    verify(stock, never()).rollback(anyString(), any());
  }

  @Test
  void matchingIdentitiesKeepTheOriginalLegacyStateMatrixReadOnly() {
    for (String orderStatus : List.of("PAID", "COMPLETED", "CANCELLED")) {
      for (String responseStatus :
          List.of("CONFIRMED", "ROLLED_BACK", "REJECTED", "RESERVED", "INIT", "NOT_FOUND")) {
        seed(orderStatus);
        var before = fixtureSnapshot();
        when(stock.reservation("202610legacy1")).thenReturn(fact("202610legacy1", responseStatus));
        var response = paymentState(8L);
        String expected;
        if ("REJECTED".equals(responseStatus)) expected = "CONFLICT";
        else if ("CANCELLED".equals(orderStatus))
          expected =
              "ROLLED_BACK".equals(responseStatus)
                  ? "SUCCEEDED"
                  : "CONFIRMED".equals(responseStatus) ? "CONFLICT" : "UNKNOWN";
        else
          expected =
              "CONFIRMED".equals(responseStatus)
                  ? "SUCCEEDED"
                  : "ROLLED_BACK".equals(responseStatus) ? "CONFLICT" : "UNKNOWN";
        assertEquals(expected, response.get("stockActionStatus"));
        assertResponseFacts(response, orderStatus);
        assertEquals(before, fixtureSnapshot());
      }
    }
    verify(stock, never()).confirm(anyString(), any());
    verify(stock, never()).rollback(anyString(), any());
  }

  @Test
  void pendingExistingActionsAndForbiddenCallersDoNotQueryLegacyStock() {
    seed("PENDING_PAYMENT");
    var beforePending = fixtureSnapshot();
    assertEquals("NONE", paymentState(8L).get("stockActionStatus"));
    assertEquals(beforePending, fixtureSnapshot());
    verifyNoInteractions(stock);
    for (String actionStatus : List.of("PENDING", "UNKNOWN", "SUCCEEDED", "CONFLICT")) {
      seed("PAID");
      assertEquals(
          1, mapper.insert(1L, "202610legacy1", "CONFIRM", LocalDateTime.of(2026, 10, 8, 10, 0)));
      fixture.update("UPDATE order_stock_action SET status=? WHERE id=1", actionStatus);
      var beforeAction = fixtureSnapshot();
      assertEquals(actionStatus, paymentState(8L).get("stockActionStatus"));
      assertEquals(beforeAction, fixtureSnapshot());
      verifyNoInteractions(stock);
    }
    seed("CANCELLED");
    var beforeForbidden = fixtureSnapshot();
    BizException forbidden =
        assertThrows(BizException.class, () -> service.paymentState("202610legacy1", 9L));
    assertEquals(403, forbidden.getHttpStatus());
    assertEquals(beforeForbidden, fixtureSnapshot());
    verifyNoInteractions(stock);
  }

  @Test
  void statusLookupDistinguishesAliasesFromAnActuallyMissingAction() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    String orderNo = "202610legacy1";
    for (String actionStatus : List.of("PENDING", "UNKNOWN", "SUCCEEDED", "CONFLICT")) {
      seed("PAID");
      assertEquals(1, mapper.insert(1L, orderNo, "CONFIRM", LocalDateTime.of(2026, 10, 8, 10, 0)));
      fixture.update("UPDATE order_stock_action SET status=? WHERE id=1", actionStatus);
      var before = fixtureSnapshot();
      assertEquals(actionStatus, mapper.statusForOrder(orderNo));
      for (String alias : List.of("202610LEGACY1", "202610légacy1", orderNo + " ")) {
        assertEquals(
            1,
            fixture.queryForObject(
                "SELECT COUNT(*) FROM order_stock_action WHERE order_no=?", Integer.class, alias));
        String result = mapper.statusForOrder(alias);
        checks.add(() -> assertEquals("UNKNOWN", result, actionStatus + alias));
      }
      assertNull(mapper.statusForOrder("202610missing"));
      assertNull(mapper.statusForOrder(null));
      assertNull(mapper.statusForOrder(""));
      assertEquals(before, fixtureSnapshot());
    }
    assertAll(checks);
  }

  @Test
  void storedActionAliasesNeverAuthorizeAnOrderOrFallBackToLegacyStock() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String orderStatus : List.of("PAID", "COMPLETED", "CANCELLED")) {
      String target = "CANCELLED".equals(orderStatus) ? "ROLLED_BACK" : "CONFIRMED";
      String actionType = "CANCELLED".equals(orderStatus) ? "ROLLBACK" : "CONFIRM";
      when(stock.reservation("202610legacy1")).thenReturn(fact("202610legacy1", target));
      for (String storedOrderNo : List.of("202610LEGACY1", "202610légacy1", "202610legacy1 ")) {
        for (String actionStatus : List.of("PENDING", "UNKNOWN", "SUCCEEDED", "CONFLICT")) {
          seed(orderStatus);
          assertEquals(
              1,
              mapper.insert(1L, storedOrderNo, actionType, LocalDateTime.of(2026, 10, 8, 10, 0)));
          fixture.update("UPDATE order_stock_action SET status=? WHERE id=1", actionStatus);
          assertEquals(
              1,
              fixture.queryForObject(
                  "SELECT COUNT(*) FROM order_stock_action WHERE order_no='202610legacy1'",
                  Integer.class));
          var before = fixtureSnapshot();

          var response = paymentState(8L);

          Object reportedStatus = response.get("stockActionStatus");
          checks.add(
              () ->
                  assertEquals(
                      "UNKNOWN", reportedStatus, orderStatus + storedOrderNo + actionStatus));
          assertResponseFacts(response, orderStatus);
          assertEquals(before, fixtureSnapshot());
        }
      }
    }
    // A binary filter returning null would query this valid Stock response and falsely succeed.
    verifyNoInteractions(stock);
    assertAll(checks);
  }

  @Test
  void lookupSqlFailureStillPropagatesInsteadOfBeingGuessedUnknown() {
    seed("PAID");
    fixture.execute("DROP TABLE order_stock_action");
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> mapper.statusForOrder("202610legacy1"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () -> service.paymentState("202610legacy1", 8L));
    verifyNoInteractions(stock);
  }

  @Test
  void orderAliasesCannotBeReadThroughGetOrPaymentState() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String[] identity :
        List.of(
            new String[] {"202610legacy1", "202610LEGACY1"},
            new String[] {"202610LEGACY1", "202610legacy1"},
            new String[] {"202610légacy1", "202610legacy1"},
            new String[] {"202610legacy1 ", "202610legacy1"})) {
      checks.add(
          () -> {
            seed("PAID");
            fixture.update("UPDATE mall_order_202610 SET order_no=? WHERE id=1", identity[0]);
            assertEquals(
                1,
                fixture.queryForObject(
                    "SELECT COUNT(*) FROM mall_order_202610 WHERE order_no=?",
                    Integer.class,
                    identity[1]));
            var before = fixtureSnapshot();
            AuthContext.set(8L, "USER");
            assertEquals(
                404,
                assertThrows(BizException.class, () -> service.get(identity[1])).getHttpStatus());
            assertEquals(
                404,
                assertThrows(BizException.class, () -> service.paymentState(identity[1], 8L))
                    .getHttpStatus());
            assertEquals(before, fixtureSnapshot());
          });
    }
    assertAll(checks);
    verifyNoInteractions(stock);
  }

  @Test
  void caseAliasesCannotChangeOrderStatusOrCreateStockActions() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String endpoint : List.of("CANCEL", "PAID", "CONFIRM", "TIMEOUT")) {
      checks.add(
          () -> {
            seed("CONFIRM".equals(endpoint) ? "PAID" : "PENDING_PAYMENT");
            assertEquals(
                1,
                fixture.queryForObject(
                    "SELECT COUNT(*) FROM mall_order_202610 WHERE order_no='202610LEGACY1'",
                    Integer.class));
            var before = fixtureSnapshot();
            AuthContext.set(8L, "USER");
            BizException failure =
                assertThrows(
                    BizException.class,
                    () -> {
                      switch (endpoint) {
                        case "CANCEL" -> service.cancel("202610LEGACY1", 8L);
                        case "PAID" -> service.paid("202610LEGACY1");
                        case "CONFIRM" -> service.confirm("202610LEGACY1");
                        case "TIMEOUT" -> service.consumeTimeout("202610LEGACY1");
                        default -> throw new AssertionError("unexpected endpoint");
                      }
                    });
            assertEquals(404, failure.getHttpStatus());
            assertEquals(before, fixtureSnapshot());
          });
    }
    assertAll(checks);
    verifyNoInteractions(stock);
  }

  @Test
  void exactOrdersKeepOwnerChecksAndAuthorizedStatusTransitions() {
    seed("PENDING_PAYMENT");
    AuthContext.set(8L, "USER");
    var before = fixtureSnapshot();
    var order = (com.cloudmall.order.domain.vo.OrderVO) service.get("202610legacy1").data;
    assertEquals("202610legacy1", order.orderNo);
    assertEquals(new BigDecimal("12.30"), order.payAmount);
    assertEquals("PENDING_PAYMENT", order.status);
    assertEquals(before, fixtureSnapshot());
    AuthContext.set(9L, "USER");
    assertEquals(
        403, assertThrows(BizException.class, () -> service.get("202610legacy1")).getHttpStatus());
    assertEquals(before, fixtureSnapshot());
    AuthContext.set(8L, "USER");
    service.paid("202610legacy1");
    assertEquals(
        "PAID",
        fixture.queryForObject("SELECT status FROM mall_order_202610 WHERE id=1", String.class));
    assertEquals(
        "202610legacy1:CONFIRM",
        fixture.queryForObject("SELECT operation_key FROM order_stock_action", String.class));
    service.confirm("202610legacy1");
    assertEquals(
        "COMPLETED",
        fixture.queryForObject("SELECT status FROM mall_order_202610 WHERE id=1", String.class));
    for (String endpoint : List.of("CANCEL", "TIMEOUT")) {
      seed("PENDING_PAYMENT");
      if ("CANCEL".equals(endpoint)) service.cancel("202610legacy1", 8L);
      else service.consumeTimeout("202610legacy1");
      assertEquals(
          "CANCELLED",
          fixture.queryForObject("SELECT status FROM mall_order_202610 WHERE id=1", String.class));
      assertEquals(
          "202610legacy1:ROLLBACK",
          fixture.queryForObject("SELECT operation_key FROM order_stock_action", String.class));
    }
    verifyNoInteractions(stock);
  }

  @Test
  void invalidInputsMissingOrdersAndSqlFailuresStillHaveTheirOriginalBoundaries() {
    seed("PENDING_PAYMENT");
    var observedSqlMapper = spy(sqlMapper);
    var target =
        new OrderServiceImpl(
            stock,
            mock(ProductClient.class),
            mock(UserClient.class),
            mock(CartClient.class),
            observedSqlMapper,
            new ObjectMapper());
    ReflectionTestUtils.setField(target, "orderStockActionMapper", mapper);
    for (String requestedOrderNo :
        java.util.Arrays.asList(
            null, "", " \t", "202610légacy1", "202610legacy1 ", "202613invalid", "not-an-order")) {
      assertEquals(
          404,
          assertThrows(BizException.class, () -> target.get(requestedOrderNo)).getHttpStatus());
    }
    verifyNoInteractions(observedSqlMapper);
    assertEquals(
        404, assertThrows(BizException.class, () -> target.get("202610missing")).getHttpStatus());
    fixture.execute("DROP TABLE mall_order_202610");
    assertThrows(
        org.springframework.dao.DataAccessException.class, () -> target.get("202610legacy1"));
    verifyNoInteractions(stock);
  }

  @Test
  void malformedActionTypesAndKeysRemainUnknownWithoutLegacyFallback() {
    String orderNo = "202610legacy1";
    List<String[]> invalidTuples = new ArrayList<>();
    for (String actionType :
        List.of("", " \t", "confirm", "rollback", "CONFIRM ", "ROLLBACK ", "OTHER"))
      invalidTuples.add(new String[] {actionType, orderNo + ":" + actionType});
    for (String operationKey :
        List.of(
            "",
            " \t",
            "202610other:CONFIRM",
            orderNo + ":ROLLBACK",
            orderNo + ":confirm",
            "202610LEGACY1:CONFIRM",
            "202610légacy1:CONFIRM",
            orderNo + ":CONFIRM ")) invalidTuples.add(new String[] {"CONFIRM", operationKey});
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    when(stock.reservation(orderNo)).thenReturn(fact(orderNo, "CONFIRMED"));
    for (String[] tuple : invalidTuples) {
      for (String actionStatus : List.of("PENDING", "UNKNOWN", "SUCCEEDED", "CONFLICT")) {
        seed("PAID");
        assertEquals(1, mapper.insert(1L, orderNo, tuple[0], LocalDateTime.of(2026, 10, 8, 10, 0)));
        fixture.update(
            "UPDATE order_stock_action SET operation_key=?,status=? WHERE id=1",
            tuple[1],
            actionStatus);
        var before = fixtureSnapshot();
        String directStatus = mapper.statusForOrder(orderNo);
        Object paymentStatus = paymentState(8L).get("stockActionStatus");
        checks.add(() -> assertEquals("UNKNOWN", directStatus, tuple[0] + tuple[1] + actionStatus));
        checks.add(
            () -> assertEquals("UNKNOWN", paymentStatus, tuple[0] + tuple[1] + actionStatus));
        assertEquals(before, fixtureSnapshot());
      }
    }
    assertAll(checks);
    verifyNoInteractions(stock);
  }

  @Test
  void existingActionsMustMatchTheOrderTerminalDirection() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String orderStatus : List.of("PAID", "COMPLETED", "CANCELLED", "PENDING_PAYMENT")) {
      for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
        boolean matches =
            (List.of("PAID", "COMPLETED").contains(orderStatus) && "CONFIRM".equals(actionType))
                || ("CANCELLED".equals(orderStatus) && "ROLLBACK".equals(actionType));
        for (String actionStatus : List.of("PENDING", "UNKNOWN", "SUCCEEDED", "CONFLICT")) {
          seed(orderStatus);
          assertEquals(
              1,
              mapper.insert(1L, "202610legacy1", actionType, LocalDateTime.of(2026, 10, 8, 10, 0)));
          fixture.update("UPDATE order_stock_action SET status=? WHERE id=1", actionStatus);
          var before = fixtureSnapshot();
          assertEquals(actionStatus, mapper.statusForOrder("202610legacy1"));
          Object reportedStatus = paymentState(8L).get("stockActionStatus");
          checks.add(
              () ->
                  assertEquals(
                      matches ? actionStatus : "UNKNOWN",
                      reportedStatus,
                      orderStatus + actionType + actionStatus));
          assertEquals(before, fixtureSnapshot());
        }
      }
    }
    assertAll(checks);
    verifyNoInteractions(stock);
  }

  @Test
  void trulyMissingActionsStillUseLegacyInsteadOfBeingTreatedAsCorruption() {
    for (String orderStatus : List.of("PAID", "COMPLETED", "CANCELLED", "PENDING_PAYMENT")) {
      seed(orderStatus);
      var before = fixtureSnapshot();
      assertNull(mapper.statusForOrder("202610legacy1"));
      if (!"PENDING_PAYMENT".equals(orderStatus))
        when(stock.reservation("202610legacy1"))
            .thenReturn(
                fact(
                    "202610legacy1",
                    "CANCELLED".equals(orderStatus) ? "ROLLED_BACK" : "CONFIRMED"));
      assertEquals(
          "PENDING_PAYMENT".equals(orderStatus) ? "NONE" : "SUCCEEDED",
          paymentState(8L).get("stockActionStatus"));
      assertEquals(before, fixtureSnapshot());
    }
    verify(stock, times(3)).reservation("202610legacy1");
    verify(stock, never()).confirm(anyString(), any());
    verify(stock, never()).rollback(anyString(), any());
  }

  @Test
  void unknownLegacyOrderStatesRemainUnknownWithoutQueryingStockOrRepairingRows() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String orderStatus :
        List.of(
            "",
            " \t",
            "paid",
            "completed",
            "cancelled",
            "PAID ",
            "COMPLETED ",
            "CANCELLED ",
            "PENDING_PAYMENT ",
            "PÁID",
            "OTHER",
            "CLOSED",
            "null")) {
      for (String responseStatus : List.of("CONFIRMED", "ROLLED_BACK", "REJECTED", "RESERVED")) {
        seed(orderStatus);
        assertNull(mapper.statusForOrder("202610legacy1"));
        var before = fixtureSnapshot();
        when(stock.reservation("202610legacy1")).thenReturn(fact("202610legacy1", responseStatus));
        var response = paymentState(8L);
        Object reportedStatus = response.get("stockActionStatus");
        checks.add(() -> assertEquals("UNKNOWN", reportedStatus, orderStatus + responseStatus));
        assertResponseFacts(response, orderStatus);
        assertEquals(before, fixtureSnapshot());
      }
    }
    assertAll(checks);
    verifyNoInteractions(stock);
  }

  @Test
  void unknownOrderStatesWithAnActionKeepTheExistingDirectionGuard() {
    for (String orderStatus : List.of("paid", "cancelled", "PAID ", "OTHER", "CLOSED")) {
      for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
        seed(orderStatus);
        assertEquals(
            1,
            mapper.insert(1L, "202610legacy1", actionType, LocalDateTime.of(2026, 10, 8, 10, 0)));
        fixture.update("UPDATE order_stock_action SET status='SUCCEEDED' WHERE id=1");
        var before = fixtureSnapshot();
        assertEquals("UNKNOWN", paymentState(8L).get("stockActionStatus"));
        assertEquals(before, fixtureSnapshot());
      }
    }
    verifyNoInteractions(stock);
  }

  /** 保留源模板定义，只在本例随机数据库创建两张固定月表。 */
  private void cloneTemplate(String template, String target) {
    String ddl =
        String.valueOf(source.queryForMap("SHOW CREATE TABLE " + template).get("Create Table"));
    fixture.execute(
        ddl.replace("CREATE TABLE `" + template + "`", "CREATE TABLE `" + target + "`"));
  }

  /** 以真实Mapper准备当前例的历史无动作订单，API读取期间不做任何夹具写入。 */
  private void seed(String status) {
    fixture.update("DELETE FROM order_stock_action");
    fixture.update("DELETE FROM mall_order_202610");
    OrderPO order = new OrderPO();
    order.id = 1L;
    order.orderNo = "202610legacy1";
    order.userId = 8L;
    order.status = status;
    order.totalAmount = new BigDecimal("12.30");
    order.payAmount = new BigDecimal("12.30");
    order.addressSnapshot = "{\"receiver\":\"原快照\"}";
    order.createdAt = LocalDateTime.of(2026, 10, 8, 10, 0, 0, 123_000_000);
    order.updatedAt = order.createdAt;
    order.expireAt = order.createdAt.plusMinutes(30);
    assertEquals(1, sqlMapper.insertOrder("mall_order_202610", order));
  }

  /** 真实Service调用，只取既有payment-state字段，不发HTTP或写支付数据。 */
  private Map<?, ?> paymentState(Long callerUserId) {
    return (Map<?, ?>) service.paymentState("202610legacy1", callerUserId).data;
  }

  /** 所有参与查询的隔离表完整快照，验证错误或成功读都没有隐式repair。 */
  private List<List<Map<String, Object>>> fixtureSnapshot() {
    return List.of(
        fixture.queryForList("SELECT * FROM mall_order_202610 ORDER BY id"),
        fixture.queryForList("SELECT * FROM mall_order_item_202610 ORDER BY id"),
        fixture.queryForList("SELECT * FROM order_stock_action ORDER BY id"));
  }

  /** 新身份门不改变原响应字段、金额BigDecimal或订单归属。 */
  private static void assertResponseFacts(Map<?, ?> response, String orderStatus) {
    assertEquals("202610legacy1", response.get("orderNo"));
    assertEquals(orderStatus, response.get("status"));
    assertEquals(8L, response.get("userId"));
    assertEquals(new BigDecimal("12.30"), response.get("payAmount"));
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
