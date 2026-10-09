package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderReservationRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.Arrays;
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

/** 普通预扣意图时间读取和回滚持久终态验证；意图SQL真实，Stock与订单查询为替身。 */
class OrderReservationRollbackProofMySqlIT {
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
    database = "cm17bn_" + UUID.randomUUID().toString().replace("-", "");
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
    if (database != null && database.matches("cm17bn_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BN fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BN original Order tables and checksums unchanged");
  }

  @Test
  void intentDatesRetainNativeComponentsInBothReadPaths() throws Exception {
    System.out.println("CM17DX test JVM timezone: " + ZoneId.systemDefault());
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderReservationIntentMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        for (String column : List.of("next_retry_at", "created_at", "updated_at")) {
          for (String date :
              List.of(
                  "1000-01-01",
                  "1582-10-04",
                  "1582-10-05",
                  "1582-10-10",
                  "1582-10-14",
                  "1582-10-15",
                  "2026-10-08")) {
            checks.add(
                () -> {
                  modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
                  OrderReservationIntentPO original = seed(1L);
                  modeJdbc.update(
                      "UPDATE order_reservation_intent SET created_at='2026-10-08"
                          + " 10:00:00.123',updated_at='2026-10-08"
                          + " 10:00:00.123',next_retry_at='2026-10-08 10:00:00.123' WHERE id=1");
                  LocalDateTime expected = LocalDateTime.parse(date + "T12:34:56.789");
                  modeJdbc.update(
                      "UPDATE order_reservation_intent SET " + column + "=? WHERE id=1",
                      date + " 12:34:56.789");
                  var before = intentDateEvidence(modeJdbc);
                  assertAll(
                      () ->
                          assertReadDate(
                              modeMapper.findByUserAndKey(original.userId, original.idempotencyKey),
                              original,
                              column,
                              expected),
                      () ->
                          assertReadDate(
                              modeMapper.findRecoverable(
                                  LocalDateTime.of(2026, 10, 10, 0, 0),
                                  LocalDateTime.of(2026, 10, 10, 0, 0),
                                  1),
                              original,
                              column,
                              expected),
                      () -> assertEquals(before, intentDateEvidence(modeJdbc)));
                });
          }
        }
        checks.add(
            () -> {
              OrderReservationIntentPO original = seed(1L);
              var before = intentDateEvidence(modeJdbc);
              var direct = modeMapper.findByUserAndKey(original.userId, original.idempotencyKey);
              LocalDateTime staleBefore = original.createdAt.plusMinutes(1);
              var scanned = modeMapper.findRecoverable(staleBefore, staleBefore, 1);
              assertEquals(1, direct.size());
              assertEquals(1, scanned.size());
              assertNull(direct.get(0).nextRetryAt);
              assertNull(scanned.get(0).nextRetryAt);
              assertEquals(before, intentDateEvidence(modeJdbc));
            });
      }
      assertAll(checks);
    }
  }

  @Test
  void calendarCutoverIntentCannotPreventFollowingHealthyRecovery() {
    OrderReservationIntentPO ancient = seed(1L);
    OrderReservationIntentPO healthy = insert(2L);
    fixture.update(
        "UPDATE order_reservation_intent SET next_retry_at='1582-10-10 12:34:56.789' WHERE id=1");
    var before = intentDateEvidence(fixture);
    StockClient stock = mock(StockClient.class);
    when(stock.reservation(ancient.orderNo)).thenReturn(fact(ancient.orderNo, "ROLLED_BACK"));
    when(stock.reservation(healthy.orderNo)).thenReturn(fact(healthy.orderNo, "ROLLED_BACK"));
    assertAll(
        () -> assertDoesNotThrow(() -> recovery(stock).recoverStaleReservations()),
        () ->
            assertEquals(
                List.of("RELEASED", "RELEASED"),
                fixture.queryForList(
                    "SELECT status FROM order_reservation_intent ORDER BY id", String.class)),
        () -> verify(stock).reservation(ancient.orderNo),
        () -> verify(stock).reservation(healthy.orderNo));
    var after = intentDateEvidence(fixture);
    for (int intentIndex = 0; intentIndex < before.size(); intentIndex++) {
      for (String column :
          List.of("id", "order_no", "user_id", "idempotency_key", "items_snapshot", "created_at")) {
        assertEquals(
            before.get(intentIndex).get(column), after.get(intentIndex).get(column), column);
      }
      assertNull(after.get(intentIndex).get("next_retry_at"));
      assertNull(after.get(intentIndex).get("last_error"));
    }
    verifyNoMoreInteractions(stock);
  }

  /** 两入口应返回同一原身份和本地时间组件，不通过字符串或Timestamp转回预期值。 */
  private static void assertReadDate(
      List<OrderReservationIntentPO> intents,
      OrderReservationIntentPO original,
      String column,
      LocalDateTime expected) {
    assertEquals(1, intents.size());
    OrderReservationIntentPO actual = intents.get(0);
    LocalDateTime actualDate =
        switch (column) {
          case "next_retry_at" -> actual.nextRetryAt;
          case "created_at" -> actual.createdAt;
          case "updated_at" -> actual.updatedAt;
          default -> throw new AssertionError("Unknown intent date column");
        };
    assertEquals(expected, actualDate);
    assertEquals(original.orderNo, actual.orderNo);
    assertEquals(original.userId, actual.userId);
    assertEquals(original.idempotencyKey, actual.idempotencyKey);
    assertEquals(original.status, actual.status);
  }

  /** 数据库原生CHAR取三个时间列整行证据，避免取证本身经过Calendar。 */
  private static List<Map<String, Object>> intentDateEvidence(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "SELECT"
            + " id,order_no,user_id,idempotency_key,items_snapshot,status,last_error,CAST(next_retry_at"
            + " AS CHAR) next_retry_at,CAST(created_at AS CHAR) created_at,CAST(updated_at AS CHAR)"
            + " updated_at FROM order_reservation_intent ORDER BY id");
  }

  @Test
  void successfulRollbackEnvelopeCannotReleaseWithoutDurableTerminalProof() {
    for (String initial : List.of("RESERVED", "ALREADY_RESERVED")) {
      for (String after :
          Arrays.asList(
              null,
              "",
              "UNKNOWN",
              "INIT",
              "NOT_FOUND",
              "RESERVED",
              "ALREADY_RESERVED",
              "CONFIRMED",
              "rolled_back",
              "ROLLED_BACK ",
              "RÓLLED_BACK",
              "rejected",
              "REJECTED ",
              "RÉJECTED")) {
        OrderReservationIntentPO original = seed(1L);
        Map<String, Object> before = row(1L);
        StockClient stock = mock(StockClient.class);
        when(stock.reservation(original.orderNo))
            .thenReturn(fact(original.orderNo, initial), fact(original.orderNo, after));
        doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stock).rollback(original.orderNo);
        recovery(stock).recoverStaleReservations();
        assertDeferred(before, row(1L));
        verify(stock, times(2)).reservation(original.orderNo);
        verify(stock).rollback(original.orderNo);
        verify(stock, never()).reserve(any());
      }
    }
  }

  @Test
  void postRollbackWrongIdentityEnvelopeAndQueryFailuresKeepTheOriginalIntent() {
    for (String badOrder :
        Arrays.asList(
            "202610other", "202610RECOVER1", "202610récovér1", "202610recover1 ", null, " ")) {
      OrderReservationIntentPO original = seed(1L);
      Map<String, Object> before = row(1L);
      StockClient stock = mock(StockClient.class);
      when(stock.reservation(original.orderNo))
          .thenReturn(fact(original.orderNo, "RESERVED"), fact(badOrder, "ROLLED_BACK"));
      doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stock).rollback(original.orderNo);
      recovery(stock).recoverStaleReservations();
      assertDeferred(before, row(1L));
      verify(stock, times(2)).reservation(original.orderNo);
      verify(stock).rollback(original.orderNo);
    }
    for (String failure : List.of("null-envelope", "null-data", "nonzero-code", "timeout")) {
      OrderReservationIntentPO original = seed(1L);
      Map<String, Object> before = row(1L);
      StockClient stock = mock(StockClient.class);
      var answers =
          when(stock.reservation(original.orderNo)).thenReturn(fact(original.orderNo, "RESERVED"));
      switch (failure) {
        case "null-envelope" -> answers.thenReturn(null);
        case "null-data" -> answers.thenReturn(ApiResponse.ok(null));
        case "nonzero-code" -> {
          ApiResponse<StockReservationStatusVO> rejected = fact(original.orderNo, "ROLLED_BACK");
          rejected.code = "503";
          answers.thenReturn(rejected);
        }
        case "timeout" -> answers.thenThrow(new IllegalStateException("post-write query timeout"));
        default -> throw new AssertionError(failure);
      }
      doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stock).rollback(original.orderNo);
      recovery(stock).recoverStaleReservations();
      assertDeferred(before, row(1L));
      verify(stock, times(2)).reservation(original.orderNo);
      verify(stock).rollback(original.orderNo);
    }
  }

  @Test
  void exactTerminalProofReleasesAndOriginalReplayBranchKeepsItsIdentity() {
    for (String initial : List.of("RESERVED", "ALREADY_RESERVED", "NOT_FOUND", "INIT")) {
      for (String terminal : List.of("ROLLED_BACK", "REJECTED")) {
        OrderReservationIntentPO original = seed(1L);
        StockClient stock = mock(StockClient.class);
        boolean needsReserve = "NOT_FOUND".equals(initial) || "INIT".equals(initial);
        if (needsReserve) {
          when(stock.reservation(original.orderNo))
              .thenReturn(
                  fact(original.orderNo, initial),
                  fact(original.orderNo, "RESERVED"),
                  fact(original.orderNo, terminal));
          doReturn(ApiResponse.ok(Map.of("reserved", true))).when(stock).reserve(any());
        } else {
          when(stock.reservation(original.orderNo))
              .thenReturn(fact(original.orderNo, initial), fact(original.orderNo, terminal));
        }
        doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stock).rollback(original.orderNo);
        recovery(stock).recoverStaleReservations();
        assertEquals("RELEASED", row(1L).get("status"));
        assertNull(row(1L).get("last_error"));
        assertNull(row(1L).get("next_retry_at"));
        verify(stock, times(needsReserve ? 3 : 2)).reservation(original.orderNo);
        verify(stock).rollback(original.orderNo);
        if (needsReserve) {
          var replay = org.mockito.ArgumentCaptor.forClass(StockClient.Reservation.class);
          verify(stock).reserve(replay.capture());
          assertEquals(original.orderNo, replay.getValue().orderNo());
          assertEquals(List.of(new StockClient.Line(7L, 2)), replay.getValue().items());
        } else verify(stock, never()).reserve(any());
        clearInvocations(stock);
        recovery(stock).recoverStaleReservations();
        verifyNoInteractions(stock);
      }
    }
  }

  @Test
  void unresolvedFirstProofDoesNotBlockLaterHealthyIntent() {
    OrderReservationIntentPO first = seed(1L);
    OrderReservationIntentPO second = insert(2L);
    Map<String, Object> before = row(1L);
    StockClient stock = mock(StockClient.class);
    when(stock.reservation(first.orderNo))
        .thenReturn(fact(first.orderNo, "RESERVED"), fact(first.orderNo, "UNKNOWN"));
    doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stock).rollback(first.orderNo);
    when(stock.reservation(second.orderNo)).thenReturn(fact(second.orderNo, "ROLLED_BACK"));
    recovery(stock).recoverStaleReservations();
    assertDeferred(before, row(1L));
    assertEquals("RELEASED", row(2L).get("status"));
    verify(stock).reservation(second.orderNo);
    verify(stock, never()).rollback(second.orderNo);
  }

  /** 原任务只在随机库中构造，重复组合清理本例自己的意图表。 */
  private OrderReservationIntentPO seed(long identity) {
    fixture.update("DELETE FROM order_reservation_intent");
    return insert(identity);
  }

  /** 合法原始预扣意图使用原Mapper插入，时钟按当前上海时间陈旧五分钟。 */
  private OrderReservationIntentPO insert(long identity) {
    OrderReservationIntentPO intent = new OrderReservationIntentPO();
    intent.id = identity;
    intent.orderNo = "202610recover" + identity;
    intent.userId = 7L;
    intent.idempotencyKey = "rollback-proof-" + identity;
    intent.itemsSnapshot = "[{\"skuId\":7,\"quantity\":2}]";
    intent.status = "RESERVE_UNKNOWN";
    intent.createdAt = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(5).withNano(0);
    intent.updatedAt = intent.createdAt;
    assertEquals(1, mapper.insertIfAbsent(intent));
    return intent;
  }

  /** 恢复逻辑与意图SQL真实，订单不存在判据和远端查询均为明确替身。 */
  private OrderReservationRecovery recovery(StockClient stock) {
    return new OrderReservationRecovery(
        stock, mapper, mock(OrderSqlMapper.class), new ObjectMapper());
  }

  /** 获取完整行，用于核对原始身份、载荷、状态及恢复元数据。 */
  private Map<String, Object> row(long identity) {
    return fixture.queryForMap("SELECT * FROM order_reservation_intent WHERE id=?", identity);
  }

  /** 未获原单终态证明只允许原失败/退避字段变化，不可结束意图。 */
  private static void assertDeferred(Map<String, Object> before, Map<String, Object> after) {
    for (String column :
        List.of(
            "id",
            "order_no",
            "user_id",
            "idempotency_key",
            "items_snapshot",
            "status",
            "created_at")) assertEquals(before.get(column), after.get(column), column);
    assertNotNull(after.get("last_error"));
    Object retryValue = after.get("next_retry_at");
    LocalDateTime retryAt =
        retryValue instanceof LocalDateTime
            ? (LocalDateTime) retryValue
            : ((java.sql.Timestamp) retryValue).toLocalDateTime();
    assertTrue(retryAt.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(30)));
  }

  /** 查询响应携带显式原身份，不能根据请求补全不可信身份。 */
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
