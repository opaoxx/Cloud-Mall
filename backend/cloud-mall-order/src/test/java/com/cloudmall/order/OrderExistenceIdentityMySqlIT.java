package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderReservationRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
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

/** 订单存在性必须核原始身份，别名不等于已提交或真正缺行；Mapper/Recovery/SQL真实。 */
class OrderExistenceIdentityMySqlIT {
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
    database = "cm17bo_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolatedUrl =
        System.getProperty("cloudmall.mysql.test.url").replace("/cloudmall_order", "/" + database);
    fixture =
        new JdbcTemplate(new DriverManagerDataSource(shanghaiUrl(isolatedUrl), "root", "root"));
    fixture.execute(
        String.valueOf(
            source.queryForMap("SHOW CREATE TABLE order_reservation_intent").get("Create Table")));
    fixture.execute(
        String.valueOf(
            source.queryForMap("SHOW CREATE TABLE mall_order_template").get("Create Table")));
    fixture.execute("CREATE TABLE mall_order_202610 LIKE mall_order_template");
    mapper = new OrderReservationIntentMapper(fixture);
  }

  @AfterEach
  void removeFixture() {
    if (database != null && database.matches("cm17bo_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BO fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BO original Order tables and checksums unchanged");
  }

  @Test
  void collationAliasesCannotProveCommitOrPretendTheOriginalOrderIsMissing() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String stored :
        List.of("202610RECOVER1", "202610récovér1", "202610recover1 ", "202610RECOVER1 ")) {
      reset();
      OrderReservationIntentPO original = insertIntent(1L);
      insertOrder(stored, 1L);
      OrderSqlMapper orders = new OrderSqlMapper(fixture);
      assertEquals(
          1L,
          fixture.queryForObject(
              "SELECT COUNT(*) FROM mall_order_202610 WHERE order_no=?",
              Long.class,
              original.orderNo));
      Map<String, Object> before = intentRow(1L);
      List<Map<String, Object>> orderBefore =
          fixture.queryForList("SELECT * FROM mall_order_202610");
      StockClient stock = mock(StockClient.class);
      when(stock.reservation(original.orderNo)).thenReturn(fact(original.orderNo, "ROLLED_BACK"));
      new OrderReservationRecovery(stock, mapper, orders, new ObjectMapper())
          .recoverStaleReservations();
      Map<String, Object> after = intentRow(1L);
      boolean rejectedIdentity = rejectsIdentity(orders, original.orderNo);
      checks.add(() -> assertTrue(rejectedIdentity, stored));
      checks.add(() -> assertDeferred(before, after));
      checks.add(() -> verifyNoInteractions(stock));
      assertEquals(orderBefore, fixture.queryForList("SELECT * FROM mall_order_202610"));
    }
    assertAll(checks);
  }

  @Test
  void aliasCandidateIsDeferredWhileLaterExactOrderCanCommit() {
    OrderReservationIntentPO first = insertIntent(1L);
    OrderReservationIntentPO second = insertIntent(2L);
    insertOrder("202610RECOVER1", 1L);
    insertOrder(second.orderNo, 2L);
    Map<String, Object> before = intentRow(1L);
    StockClient stock = mock(StockClient.class);
    new OrderReservationRecovery(stock, mapper, new OrderSqlMapper(fixture), new ObjectMapper())
        .recoverStaleReservations();
    assertDeferred(before, intentRow(1L));
    assertEquals("COMMITTED", intentRow(2L).get("status"));
    assertNull(intentRow(2L).get("last_error"));
    assertNull(intentRow(2L).get("next_retry_at"));
    verifyNoInteractions(stock);
  }

  @Test
  void exactAndGenuinelyAbsentRowsKeepTheirOriginalRecoveryBranches() {
    OrderReservationIntentPO present = insertIntent(1L);
    OrderReservationIntentPO absent = insertIntent(2L);
    insertOrder(present.orderNo, 1L);
    OrderSqlMapper orders = new OrderSqlMapper(fixture);
    assertTrue(orders.orderExists("mall_order_202610", present.orderNo));
    assertFalse(orders.orderExists("mall_order_202610", absent.orderNo));
    List<Map<String, Object>> before = fixture.queryForList("SELECT * FROM mall_order_202610");
    StockClient stock = mock(StockClient.class);
    when(stock.reservation(absent.orderNo)).thenReturn(fact(absent.orderNo, "ROLLED_BACK"));
    new OrderReservationRecovery(stock, mapper, orders, new ObjectMapper())
        .recoverStaleReservations();
    assertEquals("COMMITTED", intentRow(1L).get("status"));
    assertEquals("RELEASED", intentRow(2L).get("status"));
    verify(stock, never()).reservation(present.orderNo);
    verify(stock).reservation(absent.orderNo);
    verify(stock, never()).reserve(any());
    verify(stock, never()).rollback(anyString());
    assertEquals(before, fixture.queryForList("SELECT * FROM mall_order_202610"));
  }

  @Test
  void databaseFailureIsStillUnknownAndNeverInventsMissingOrderEvidence() {
    OrderReservationIntentPO original = insertIntent(1L);
    Map<String, Object> before = intentRow(1L);
    fixture.execute("DROP TABLE mall_order_202610");
    OrderSqlMapper orders = new OrderSqlMapper(fixture);
    assertThrows(
        DataAccessException.class, () -> orders.orderExists("mall_order_202610", original.orderNo));
    OrderReservationIntentMapper observed = spy(mapper);
    StockClient stock = mock(StockClient.class);
    assertDoesNotThrow(
        () ->
            new OrderReservationRecovery(stock, observed, orders, new ObjectMapper())
                .recoverStaleReservations());
    Map<String, Object> after = intentRow(1L);
    assertOriginalFacts(before, after);
    assertNull(after.get("last_error"));
    assertNotNull(after.get("next_retry_at"));
    verifyNoInteractions(stock);
    verify(observed, never()).recordFailure(anyString(), anyString(), any());
    verify(observed, never()).forceCommitted(anyString(), any());
    verify(observed, never()).markReleased(anyString(), any());
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND table_name='mall_order_202610'",
            Integer.class,
            database));
  }

  /** 立即核当前夹具，保存结果供整组断言，不能在下一夹具上重跑旧SQL。 */
  private static boolean rejectsIdentity(OrderSqlMapper orders, String orderNo) {
    try {
      orders.orderExists("mall_order_202610", orderNo);
      return false;
    } catch (IllegalStateException invalidEvidence) {
      return true;
    }
  }

  /** 只清理当前随机库的夹具表，原订单库完全只读。 */
  private void reset() {
    fixture.update("DELETE FROM order_reservation_intent");
    fixture.update("DELETE FROM mall_order_202610");
  }

  /** 构造正常陈旧意图，并保留原始大小写身份。 */
  private OrderReservationIntentPO insertIntent(long identity) {
    OrderReservationIntentPO intent = new OrderReservationIntentPO();
    intent.id = identity;
    intent.orderNo = "202610recover" + identity;
    intent.userId = 7L;
    intent.idempotencyKey = "existence-identity-" + identity;
    intent.itemsSnapshot = "[{\"skuId\":7,\"quantity\":2}]";
    intent.status = "RESERVE_UNKNOWN";
    intent.createdAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(5).withNano(0);
    intent.updatedAt = intent.createdAt;
    assertEquals(1, mapper.insertIfAbsent(intent));
    return intent;
  }

  /** 使用真实Mapper保存精确或别名订单，查询期间不清洗任何行。 */
  private void insertOrder(String orderNo, long identity) {
    OrderPO order = new OrderPO();
    order.id = identity;
    order.orderNo = orderNo;
    order.userId = 7L;
    order.status = "PENDING_PAYMENT";
    order.totalAmount = new BigDecimal("12.30");
    order.payAmount = new BigDecimal("12.30");
    order.addressSnapshot = "{}";
    order.createdAt = LocalDateTime.of(2026, 10, 8, 10, 0, 0, 123_000_000);
    order.updatedAt = order.createdAt;
    order.expireAt = order.createdAt.plusMinutes(30);
    assertEquals(1, new OrderSqlMapper(fixture).insertOrder("mall_order_202610", order));
  }

  /** 读取意图完整行，区分原事实与认领/退避元数据。 */
  private Map<String, Object> intentRow(long identity) {
    return fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=?", identity);
  }

  /** 别名不能转COMMITTED/RELEASED，只有失败元数据可以变化。 */
  private static void assertDeferred(Map<String, Object> before, Map<String, Object> after) {
    assertOriginalFacts(before, after);
    assertNotNull(after.get("last_error"));
    assertNotNull(after.get("next_retry_at"));
  }

  /** 原状态、身份、快照和创建时间保持，不把候选当作可信已提交事实。 */
  private static void assertOriginalFacts(Map<String, Object> before, Map<String, Object> after) {
    for (String column :
        List.of(
            "id",
            "order_no",
            "user_id",
            "idempotency_key",
            "items_snapshot",
            "status",
            "created_at")) assertEquals(before.get(column), after.get(column), column);
  }

  /** 若误把别名当缺行，这个替身会暴露意图被错误释放的回归。 */
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
