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
import java.time.ZoneId;
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
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 库存动作恢复时间读取/写入边界的真实隔离MySQL验证；Stock为替身，不启动业务应用。 */
class OrderStockActionRecoveryTimeMySqlIT {
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
    database = "cm17bk_" + UUID.randomUUID().toString().replace("-", "");
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
    if (database != null && database.matches("cm17bk_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BK fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BK original Order tables and checksums unchanged");
  }

  @Test
  void impossibleCalendarRetryDatesCannotAbortOrOccupyTheScanLimit() throws Exception {
    System.out.println("CM17EA test JVM timezone: " + ZoneId.systemDefault());
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String readMode : List.of("STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE", "")) {
        for (String invalid :
            List.of(
                "2026-02-29 12:34:56.789",
                "2026-02-30 12:34:56.789",
                "2026-04-31 12:34:56.789",
                "2026-09-31 12:34:56.789",
                "1900-02-29 12:34:56.789",
                "1000-02-29 12:34:56.789")) {
          for (LocalDateTime healthyTime :
              List.of(
                  LocalDateTime.of(2026, 10, 8, 10, 0, 0, 123_000_000),
                  LocalDateTime.of(2026, 10, 9, 0, 0))) {
            checks.add(
                () -> {
                  modeJdbc.execute("SET SESSION sql_mode='ALLOW_INVALID_DATES'");
                  seed(modeJdbc, modeMapper, LocalDateTime.of(2026, 10, 8, 10, 0));
                  assertEquals(
                      1, modeMapper.insert(2L, "healthy-calendar", "CONFIRM", healthyTime));
                  modeJdbc.update(
                      "UPDATE order_stock_action SET next_retry_at=? WHERE id=1", invalid);
                  var before = retryDateEvidence(modeJdbc);
                  assertEquals(invalid, before.get(0).get("next_retry_at"));
                  modeJdbc.execute("SET SESSION sql_mode='" + readMode + "'");
                  var actions = modeMapper.findDue(LocalDateTime.of(2026, 10, 9, 12, 0), 1);
                  var warnings = modeJdbc.queryForList("SHOW WARNINGS");
                  assertTrue(
                      warnings.stream()
                          .allMatch(warning -> ((Number) warning.get("Code")).intValue() == 1287),
                      warnings.toString());
                  assertEquals(1, actions.size());
                  assertEquals(2L, actions.get(0).id);
                  assertEquals("healthy-calendar:CONFIRM", actions.get(0).operationKey);
                  assertEquals(healthyTime, actions.get(0).nextRetryAt);
                  assertEquals(before, retryDateEvidence(modeJdbc));
                });
          }
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void sixtyImpossibleCalendarActionsCannotStarveTheFollowingHealthyRecovery() throws Exception {
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      modeJdbc.execute("SET SESSION sql_mode='ALLOW_INVALID_DATES'");
      LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
      seed(modeJdbc, modeMapper, createdAt);
      for (int identity = 2; identity <= 61; identity++) {
        assertEquals(
            1, modeMapper.insert(identity, "calendar-limit" + identity, "CONFIRM", createdAt));
      }
      modeJdbc.update(
          "UPDATE order_stock_action SET next_retry_at=CASE MOD(id,6) WHEN 0 THEN '2026-02-29"
              + " 12:34:56.789' WHEN 1 THEN '2026-02-30 12:34:56.789' WHEN 2 THEN '2026-04-31"
              + " 12:34:56.789' WHEN 3 THEN '2026-09-31 12:34:56.789' WHEN 4 THEN '1900-02-29"
              + " 12:34:56.789' ELSE '1000-02-29 12:34:56.789' END WHERE id<=60");
      var before = retryDateEvidence(modeJdbc);
      modeJdbc.execute("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE'");
      StockClient stock = mock(StockClient.class);
      StockReservationStatusVO response = new StockReservationStatusVO();
      response.orderNo = "calendar-limit61";
      response.status = "CONFIRMED";
      when(stock.reservation(response.orderNo)).thenReturn(ApiResponse.ok(response));
      OrderStockActionRecovery recovery = new OrderStockActionRecovery(modeMapper, stock);
      assertAll(
          () -> assertDoesNotThrow(recovery::recoverDue),
          () ->
              assertEquals(
                  "SUCCEEDED",
                  modeJdbc.queryForObject(
                      "SELECT status FROM order_stock_action WHERE id=61", String.class)),
          () -> verify(stock).reservation("calendar-limit61"));
      var after = retryDateEvidence(modeJdbc);
      assertEquals(before.subList(0, 60), after.subList(0, 60));
      assertEquals(1, ((Number) after.get(60).get("attempts")).intValue());
      assertNull(after.get(60).get("lease_owner"));
      assertNull(after.get(60).get("lease_until"));
      for (String column :
          List.of(
              "id", "order_no", "action_type", "operation_key", "next_retry_at", "created_at")) {
        assertEquals(before.get(60).get(column), after.get(60).get(column), column);
      }
      verifyNoMoreInteractions(stock);
    }
  }

  @Test
  void validLeapDaysMonthEndsAndYearBoundariesRetainTheirRetryDates() throws Exception {
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (String date :
            List.of(
                "1000-02-28",
                "1900-02-28",
                "2000-02-29",
                "2024-02-29",
                "2026-04-30",
                "9999-12-31")) {
          seed(modeJdbc, modeMapper, LocalDateTime.of(2026, 10, 8, 10, 0));
          LocalDateTime expected = LocalDateTime.parse(date + "T12:34:56.789");
          modeJdbc.update("UPDATE order_stock_action SET next_retry_at=? WHERE id=1", expected);
          var before = retryDateEvidence(modeJdbc);
          var actions = modeMapper.findDue(LocalDateTime.of(9999, 12, 31, 23, 59, 59), 1);
          var warnings = modeJdbc.queryForList("SHOW WARNINGS");
          assertTrue(
              warnings.stream()
                  .allMatch(warning -> ((Number) warning.get("Code")).intValue() == 1287),
              warnings.toString());
          assertEquals(1, actions.size());
          assertEquals(expected, actions.get(0).nextRetryAt);
          assertEquals("order-time:CONFIRM", actions.get(0).operationKey);
          assertEquals(before, retryDateEvidence(modeJdbc));
        }
      }
    }
  }

  @Test
  void zeroMonthOrDayRetryDatesCannotAbortOrOccupyTheScanLimit() throws Exception {
    System.out.println("CM17DZ test JVM timezone: " + ZoneId.systemDefault());
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String readMode : List.of("STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE", "")) {
        for (String invalid :
            List.of(
                "2026-00-15 12:34:56.789", "2026-10-00 12:34:56.789", "2026-00-00 12:34:56.789")) {
          for (LocalDateTime healthyTime :
              List.of(
                  LocalDateTime.of(2026, 10, 8, 10, 0, 0, 123_000_000),
                  LocalDateTime.of(2026, 10, 9, 0, 0))) {
            checks.add(
                () -> {
                  modeJdbc.execute("SET SESSION sql_mode=''");
                  seed(modeJdbc, modeMapper, LocalDateTime.of(2026, 10, 8, 10, 0));
                  assertEquals(
                      1, modeMapper.insert(2L, "healthy-components", "CONFIRM", healthyTime));
                  modeJdbc.update(
                      "UPDATE order_stock_action SET next_retry_at=? WHERE id=1", invalid);
                  var before = retryDateEvidence(modeJdbc);
                  assertEquals(invalid, before.get(0).get("next_retry_at"));
                  modeJdbc.execute("SET SESSION sql_mode='" + readMode + "'");
                  var actions = modeMapper.findDue(LocalDateTime.of(2026, 10, 9, 12, 0), 1);
                  assertEquals(1, actions.size());
                  assertEquals(2L, actions.get(0).id);
                  assertEquals("healthy-components:CONFIRM", actions.get(0).operationKey);
                  assertEquals(healthyTime, actions.get(0).nextRetryAt);
                  assertEquals(before, retryDateEvidence(modeJdbc));
                });
          }
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void sixtyPartialZeroDateActionsCannotStarveTheFollowingHealthyRecovery() throws Exception {
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      modeJdbc.execute("SET SESSION sql_mode=''");
      LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
      seed(modeJdbc, modeMapper, createdAt);
      for (int identity = 2; identity <= 61; identity++) {
        assertEquals(
            1, modeMapper.insert(identity, "zero-components" + identity, "CONFIRM", createdAt));
      }
      modeJdbc.update(
          "UPDATE order_stock_action SET next_retry_at=CASE WHEN MOD(id,2)=0 THEN '2026-10-00"
              + " 12:34:56.789' ELSE '2026-00-15 12:34:56.789' END WHERE id<=60");
      var before = retryDateEvidence(modeJdbc);
      modeJdbc.execute("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE'");
      StockClient stock = mock(StockClient.class);
      StockReservationStatusVO response = new StockReservationStatusVO();
      response.orderNo = "zero-components61";
      response.status = "CONFIRMED";
      when(stock.reservation(response.orderNo)).thenReturn(ApiResponse.ok(response));
      OrderStockActionRecovery recovery = new OrderStockActionRecovery(modeMapper, stock);
      assertAll(
          () -> assertDoesNotThrow(recovery::recoverDue),
          () ->
              assertEquals(
                  "SUCCEEDED",
                  modeJdbc.queryForObject(
                      "SELECT status FROM order_stock_action WHERE id=61", String.class)),
          () -> verify(stock).reservation("zero-components61"));
      var after = retryDateEvidence(modeJdbc);
      assertEquals(before.subList(0, 60), after.subList(0, 60));
      assertEquals(1, ((Number) after.get(60).get("attempts")).intValue());
      assertNull(after.get(60).get("lease_owner"));
      assertNull(after.get(60).get("lease_until"));
      for (String column :
          List.of(
              "id", "order_no", "action_type", "operation_key", "next_retry_at", "created_at")) {
        assertEquals(before.get(60).get(column), after.get(60).get(column), column);
      }
      verifyNoMoreInteractions(stock);
    }
  }

  @Test
  void retryDatesBelowTheSupportedRangeCannotAbortOrOccupyTheScanLimit() throws Exception {
    System.out.println("CM17DY test JVM timezone: " + ZoneId.systemDefault());
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String readMode : List.of("STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE", "")) {
        for (String invalid :
            List.of(
                "0000-00-00 00:00:00.000", "0000-01-01 12:34:56.789", "0999-12-31 23:59:59.999")) {
          for (LocalDateTime healthyTime :
              List.of(
                  LocalDateTime.of(1000, 1, 1, 0, 0),
                  LocalDateTime.of(2026, 10, 8, 10, 0, 0, 123_000_000))) {
            checks.add(
                () -> {
                  modeJdbc.execute("SET SESSION sql_mode=''");
                  seed(modeJdbc, modeMapper, LocalDateTime.of(2026, 10, 8, 10, 0));
                  assertEquals(1, modeMapper.insert(2L, "healthy-retry", "CONFIRM", healthyTime));
                  modeJdbc.update(
                      "UPDATE order_stock_action SET next_retry_at=? WHERE id=1", invalid);
                  var before = retryDateEvidence(modeJdbc);
                  assertEquals(invalid, before.get(0).get("next_retry_at"));
                  modeJdbc.execute("SET SESSION sql_mode='" + readMode + "'");
                  var actions = modeMapper.findDue(LocalDateTime.of(2026, 10, 9, 12, 0), 1);
                  assertEquals(1, actions.size());
                  assertEquals(2L, actions.get(0).id);
                  assertEquals("healthy-retry:CONFIRM", actions.get(0).operationKey);
                  assertEquals(healthyTime, actions.get(0).nextRetryAt);
                  assertEquals(before, retryDateEvidence(modeJdbc));
                });
          }
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void sixtyZeroDateActionsCannotStarveTheFollowingHealthyRecovery() throws Exception {
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      modeJdbc.execute("SET SESSION sql_mode=''");
      LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
      seed(modeJdbc, modeMapper, createdAt);
      for (int identity = 2; identity <= 61; identity++) {
        assertEquals(
            1, modeMapper.insert(identity, "retry-lower" + identity, "CONFIRM", createdAt));
      }
      modeJdbc.update(
          "UPDATE order_stock_action SET next_retry_at='0000-00-00 00:00:00.000' WHERE id<=60");
      var before = retryDateEvidence(modeJdbc);
      modeJdbc.execute("SET SESSION sql_mode='STRICT_TRANS_TABLES,NO_ZERO_DATE,NO_ZERO_IN_DATE'");
      StockClient stock = mock(StockClient.class);
      StockReservationStatusVO response = new StockReservationStatusVO();
      response.orderNo = "retry-lower61";
      response.status = "CONFIRMED";
      when(stock.reservation(response.orderNo)).thenReturn(ApiResponse.ok(response));
      OrderStockActionRecovery recovery = new OrderStockActionRecovery(modeMapper, stock);
      assertAll(
          () -> assertDoesNotThrow(recovery::recoverDue),
          () ->
              assertEquals(
                  "SUCCEEDED",
                  modeJdbc.queryForObject(
                      "SELECT status FROM order_stock_action WHERE id=61", String.class)),
          () -> verify(stock).reservation("retry-lower61"));
      var after = retryDateEvidence(modeJdbc);
      assertEquals(before.subList(0, 60), after.subList(0, 60));
      assertEquals(1, ((Number) after.get(60).get("attempts")).intValue());
      assertNull(after.get(60).get("lease_owner"));
      assertNull(after.get(60).get("lease_until"));
      for (String column :
          List.of(
              "id", "order_no", "action_type", "operation_key", "next_retry_at", "created_at")) {
        assertEquals(before.get(60).get(column), after.get(60).get(column), column);
      }
      verifyNoMoreInteractions(stock);
    }
  }

  @Test
  void validCalendarRetryDatesAreReadWithoutChangingDatabaseComponents() throws Exception {
    System.out.println("CM17DW test JVM timezone: " + ZoneId.systemDefault());
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
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
                seed(modeJdbc, modeMapper, LocalDateTime.of(2026, 10, 8, 10, 0));
                LocalDateTime expected = LocalDateTime.parse(date + "T12:34:56.789");
                modeJdbc.update(
                    "UPDATE order_stock_action SET next_retry_at=? WHERE id=1",
                    date + " 12:34:56.789");
                var before = retryDateEvidence(modeJdbc);
                var actions = modeMapper.findDue(LocalDateTime.of(2026, 10, 9, 12, 0), 1);
                assertEquals(1, actions.size());
                assertEquals(expected, actions.get(0).nextRetryAt);
                assertEquals("order-time", actions.get(0).orderNo);
                assertEquals("order-time:CONFIRM", actions.get(0).operationKey);
                assertEquals(before, retryDateEvidence(modeJdbc));
              });
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void calendarCutoverCandidateCannotBlockHealthyStockActionRecovery() {
    LocalDateTime createdAt = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(1);
    seed(fixture, mapper, createdAt);
    assertEquals(1, mapper.insert(2L, "order-healthy", "CONFIRM", createdAt));
    fixture.update(
        "UPDATE order_stock_action SET next_retry_at='1582-10-10 12:34:56.789' WHERE id=1");
    var before = retryDateEvidence(fixture);
    StockClient stock = mock(StockClient.class);
    when(stock.reservation(anyString()))
        .thenAnswer(
            invocation -> {
              StockReservationStatusVO response = new StockReservationStatusVO();
              response.orderNo = invocation.getArgument(0);
              response.status = "CONFIRMED";
              return ApiResponse.ok(response);
            });
    OrderStockActionRecovery recovery = new OrderStockActionRecovery(mapper, stock);
    assertAll(
        () -> assertDoesNotThrow(recovery::recoverDue),
        () ->
            assertEquals(
                List.of("SUCCEEDED", "SUCCEEDED"),
                fixture.queryForList(
                    "SELECT status FROM order_stock_action ORDER BY id", String.class)),
        () -> verify(stock).reservation("order-time"),
        () -> verify(stock).reservation("order-healthy"));
    var after = retryDateEvidence(fixture);
    for (int actionIndex = 0; actionIndex < before.size(); actionIndex++) {
      for (String column :
          List.of(
              "id", "order_no", "action_type", "operation_key", "next_retry_at", "created_at")) {
        assertEquals(
            before.get(actionIndex).get(column), after.get(actionIndex).get(column), column);
      }
      assertNull(after.get(actionIndex).get("lease_owner"));
      assertNull(after.get(actionIndex).get("lease_until"));
      assertEquals(1, ((Number) after.get(actionIndex).get("attempts")).intValue());
    }
    verifyNoMoreInteractions(stock);
  }

  /** 对整行取证，仅next_retry_at按数据库CHAR组件读，避免Timestamp再次误拒合法日期。 */
  private static List<Map<String, Object>> retryDateEvidence(JdbcTemplate jdbc) {
    return jdbc.queryForList(
        "SELECT id,order_no,action_type,operation_key,status,attempts,CAST(next_retry_at AS CHAR)"
            + " next_retry_at,lease_owner,lease_until,last_error,created_at,updated_at FROM"
            + " order_stock_action ORDER BY id");
  }

  @Test
  void claimUsesExactlyThirtySecondsFromTheFlooredTimeAndRejectsLateCompletion() throws Exception {
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        for (String operation : List.of("finish", "retry")) {
          checks.add(
              () -> {
                modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
                LocalDateTime claimAt = LocalDateTime.of(2026, 10, 31, 23, 59, 29, 999_500_000);
                LocalDateTime floor = LocalDateTime.of(2026, 10, 31, 23, 59, 29, 999_000_000);
                seed(modeJdbc, modeMapper, floor.minusMinutes(1));
                assertEquals(1, modeMapper.claim(1L, "time-owner", claimAt));
                var before = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
                LocalDateTime storedUpdate =
                    modeJdbc.queryForObject(
                        "SELECT updated_at FROM order_stock_action", LocalDateTime.class);
                LocalDateTime storedLease =
                    modeJdbc.queryForObject(
                        "SELECT lease_until FROM order_stock_action", LocalDateTime.class);
                LocalDateTime completedAt = LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_900_000);
                int changed =
                    "finish".equals(operation)
                        ? modeMapper.finish(1L, "time-owner", "SUCCEEDED", null, completedAt)
                        : modeMapper.retry(
                            1L, "time-owner", "late", completedAt.plusMinutes(1), completedAt);
                var after = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
                assertAll(
                    () -> assertEquals(floor, storedUpdate),
                    () -> assertEquals(floor.plusSeconds(30), storedLease),
                    () -> assertEquals(0, changed),
                    () -> assertEquals(before, after));
              });
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void activeCompletionsAndRetrySchedulesPersistFlooredFreshTimes() throws Exception {
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        for (String operation : List.of("finish", "retry")) {
          checks.add(
              () -> {
                modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
                LocalDateTime beforeLease = LocalDateTime.of(2026, 10, 31, 23, 59, 40, 0);
                seed(modeJdbc, modeMapper, beforeLease.minusMinutes(1));
                assertEquals(1, modeMapper.claim(1L, "time-owner", beforeLease));
                LocalDateTime now = LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_500_000);
                LocalDateTime floorNow = LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_000_000);
                LocalDateTime retryAt = LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_999_999);
                LocalDateTime floorRetry = LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000);
                assertEquals(
                    1,
                    "finish".equals(operation)
                        ? modeMapper.finish(1L, "time-owner", "SUCCEEDED", null, now)
                        : modeMapper.retry(1L, "time-owner", "unknown", retryAt, now));
                var row = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
                LocalDateTime storedNow =
                    modeJdbc.queryForObject(
                        "SELECT updated_at FROM order_stock_action", LocalDateTime.class);
                assertEquals(
                    "finish".equals(operation) ? "SUCCEEDED" : "UNKNOWN", row.get("status"));
                assertEquals("order-time:CONFIRM", row.get("operation_key"));
                assertNull(row.get("lease_owner"));
                assertNull(row.get("lease_until"));
                assertEquals(1, ((Number) row.get("attempts")).intValue());
                LocalDateTime storedRetry =
                    modeJdbc.queryForObject(
                        "SELECT next_retry_at FROM order_stock_action", LocalDateTime.class);
                assertAll(
                    () -> assertEquals(floorNow, storedNow),
                    () -> {
                      if ("retry".equals(operation)) assertEquals(floorRetry, storedRetry);
                    });
              });
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void scanAndClaimPreserveStrictMillisecondExpiryAndInclusiveRetryBoundaries() {
    LocalDateTime expiry = LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_000_000);
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String operation : List.of("scan", "claim")) {
      seed(fixture, mapper, expiry.minusMinutes(1));
      fixture.update(
          "UPDATE order_stock_action SET lease_owner='old-owner',lease_until=? WHERE id=1", expiry);
      var before = fixture.queryForMap("SELECT * FROM order_stock_action");
      if ("scan".equals(operation)) {
        int count = mapper.findDue(expiry.plusNanos(900_000), 1).size();
        checks.add(() -> assertEquals(0, count));
      } else {
        int changed = mapper.claim(1L, "new-owner", expiry.plusNanos(900_000));
        checks.add(() -> assertEquals(0, changed));
      }
      var after = fixture.queryForMap("SELECT * FROM order_stock_action");
      checks.add(() -> assertEquals(before, after));
      // 重置独立边界：旧claim若已错误写入新lease，不能污染后续合法扫描断言。
      seed(fixture, mapper, expiry.minusMinutes(1));
      fixture.update("UPDATE order_stock_action SET lease_until=? WHERE id=1", expiry);
      assertEquals(1, mapper.findDue(expiry.plusNanos(1_000_000), 1).size());
    }
    seed(fixture, mapper, expiry);
    assertEquals(0, mapper.findDue(expiry.minusNanos(100_000), 1).size());
    assertEquals(1, mapper.findDue(expiry.plusNanos(900_000), 1).size());
    assertAll(checks);
  }

  @Test
  void invalidTimesAndDerivedLeaseOverflowFailBeforeSql() {
    LocalDateTime normal = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    LocalDateTime invalid = LocalDateTime.of(10000, 1, 1, 0, 0);
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String operation :
        List.of(
            "scan-null",
            "scan-range",
            "claim-null",
            "claim-range",
            "claim-lease-overflow",
            "finish-null",
            "finish-range",
            "retry-now-null",
            "retry-now-range",
            "retry-at-null",
            "retry-at-range")) {
      checks.add(
          () -> {
            seed(fixture, mapper, normal.minusMinutes(1));
            assertEquals(1, mapper.claim(1L, "time-owner", normal));
            var before = fixture.queryForMap("SELECT * FROM order_stock_action");
            assertThrows(
                IllegalArgumentException.class,
                () -> {
                  switch (operation) {
                    case "scan-null" -> mapper.findDue(null, 1);
                    case "scan-range" -> mapper.findDue(invalid, 1);
                    case "claim-null" -> mapper.claim(1L, "time-owner", null);
                    case "claim-range" -> mapper.claim(1L, "time-owner", invalid);
                    case "claim-lease-overflow" ->
                        mapper.claim(1L, "time-owner", LocalDateTime.of(9999, 12, 31, 23, 59, 50));
                    case "finish-null" -> mapper.finish(1L, "time-owner", "SUCCEEDED", null, null);
                    case "finish-range" ->
                        mapper.finish(1L, "time-owner", "SUCCEEDED", null, invalid);
                    case "retry-now-null" ->
                        mapper.retry(1L, "time-owner", "unknown", normal.plusMinutes(1), null);
                    case "retry-now-range" ->
                        mapper.retry(1L, "time-owner", "unknown", normal.plusMinutes(1), invalid);
                    case "retry-at-null" -> mapper.retry(1L, "time-owner", "unknown", null, normal);
                    case "retry-at-range" ->
                        mapper.retry(1L, "time-owner", "unknown", invalid, normal);
                    default -> throw new AssertionError("unknown time case");
                  }
                });
            assertEquals(before, fixture.queryForMap("SELECT * FROM order_stock_action"));
          });
    }
    assertAll(checks);
  }

  @Test
  void claimBoundarySamplesUseTheSameFloorAndKeepTheOriginalThirtySecondLease() throws Exception {
    List<LocalDateTime[]> samples =
        List.of(
            new LocalDateTime[] {
              LocalDateTime.of(2026, 10, 8, 12, 0, 0, 999_499_000),
              LocalDateTime.of(2026, 10, 8, 12, 0, 0, 999_000_000)
            },
            new LocalDateTime[] {
              LocalDateTime.of(2026, 10, 8, 12, 0, 0, 999_500_000),
              LocalDateTime.of(2026, 10, 8, 12, 0, 0, 999_000_000)
            },
            new LocalDateTime[] {
              LocalDateTime.of(2026, 10, 8, 12, 0, 0, 999_999_999),
              LocalDateTime.of(2026, 10, 8, 12, 0, 0, 999_000_000)
            },
            new LocalDateTime[] {
              LocalDateTime.of(1000, 1, 1, 0, 1, 0, 123_999_000),
              LocalDateTime.of(1000, 1, 1, 0, 1, 0, 123_000_000)
            },
            new LocalDateTime[] {
              LocalDateTime.of(9999, 12, 31, 23, 59, 28, 999_500_000),
              LocalDateTime.of(9999, 12, 31, 23, 59, 28, 999_000_000)
            });
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (LocalDateTime[] sample : samples) {
          seed(modeJdbc, modeMapper, sample[1].minusMinutes(1));
          assertEquals(1, modeMapper.claim(1L, "boundary-owner", sample[0]));
          assertEquals(
              sample[1],
              modeJdbc.queryForObject(
                  "SELECT updated_at FROM order_stock_action", LocalDateTime.class));
          assertEquals(
              sample[1].plusSeconds(30),
              modeJdbc.queryForObject(
                  "SELECT lease_until FROM order_stock_action", LocalDateTime.class));
          assertEquals(
              1, modeJdbc.queryForObject("SELECT attempts FROM order_stock_action", Integer.class));
          assertEquals(
              "order-time:CONFIRM",
              modeJdbc.queryForObject(
                  "SELECT operation_key FROM order_stock_action", String.class));
        }
      }
    }
  }

  /** 仅在自建随机库准备合法到期动作，不改原业务时间或状态。 */
  private static void seed(
      JdbcTemplate jdbc, OrderStockActionMapper mapper, LocalDateTime createdAt) {
    jdbc.update("DELETE FROM order_stock_action");
    assertEquals(1, mapper.insert(1L, "order-time", "CONFIRM", createdAt));
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
