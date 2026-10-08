package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderStockActionMapper;
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
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 库存动作首次写入校验的真实隔离MySQL验证，不依赖Stock或业务启动。 */
class OrderStockActionInsertMySqlIT {
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
    database = "cm17bi_" + UUID.randomUUID().toString().replace("-", "");
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
    if (database != null && database.matches("cm17bi_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BI fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BI original Order tables and checksums unchanged");
  }

  @Test
  void invalidInputsAreRejectedWithoutChangingExistingRowsInEitherSqlMode() throws Exception {
    LocalDateTime occurredAt = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    List<Object[]> invalid =
        List.of(
            new Object[] {0L, "order-1", "CONFIRM", occurredAt},
            new Object[] {-1L, "order-1", "CONFIRM", occurredAt},
            new Object[] {1L, null, "CONFIRM", occurredAt},
            new Object[] {1L, "", "CONFIRM", occurredAt},
            new Object[] {1L, " \t", "CONFIRM", occurredAt},
            new Object[] {1L, "a".repeat(65), "CONFIRM", occurredAt},
            new Object[] {1L, "😀".repeat(65), "CONFIRM", occurredAt},
            new Object[] {1L, "order-1", null, occurredAt},
            new Object[] {1L, "order-1", "", occurredAt},
            new Object[] {1L, "order-1", " \t", occurredAt},
            new Object[] {1L, "order-1", "confirm", occurredAt},
            new Object[] {1L, "order-1", "CONFIRM ", occurredAt},
            new Object[] {1L, "order-1", "OTHER", occurredAt},
            new Object[] {1L, "order-1", "a".repeat(17), occurredAt},
            new Object[] {1L, "order-1", "CONFIRM", null});
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        for (Object[] input : invalid) {
          checks.add(
              () -> {
                modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
                modeJdbc.update("DELETE FROM order_stock_action");
                assertEquals(1, modeMapper.insert(7L, "preserved", "CONFIRM", occurredAt));
                var before = modeJdbc.queryForList("SELECT * FROM order_stock_action ORDER BY id");
                assertThrows(
                    IllegalArgumentException.class,
                    () ->
                        modeMapper.insert(
                            (Long) input[0],
                            (String) input[1],
                            (String) input[2],
                            (LocalDateTime) input[3]),
                    () ->
                        "stored identity after invalid insert: "
                            + modeJdbc.queryForList(
                                "SELECT id,order_no,action_type,operation_key FROM"
                                    + " order_stock_action ORDER BY id"));
                assertEquals(
                    before, modeJdbc.queryForList("SELECT * FROM order_stock_action ORDER BY id"));
              });
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void legalUnicodeBoundariesKeepOriginalFieldsAndAllThreeTimes() throws Exception {
    LocalDateTime occurredAt = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (String orderNo :
            List.of("x", "a".repeat(64), "中".repeat(64), "😀".repeat(64), " original ")) {
          for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
            modeJdbc.update("DELETE FROM order_stock_action");
            assertEquals(1, modeMapper.insert(1L, orderNo, actionType, occurredAt));
            var stored = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
            assertEquals(1L, ((Number) stored.get("id")).longValue());
            assertEquals(orderNo, stored.get("order_no"));
            assertEquals(actionType, stored.get("action_type"));
            assertEquals(orderNo + ":" + actionType, stored.get("operation_key"));
            assertEquals("PENDING", stored.get("status"));
            assertEquals(0, ((Number) stored.get("attempts")).intValue());
            for (String column : List.of("next_retry_at", "created_at", "updated_at"))
              assertEquals(
                  occurredAt,
                  modeJdbc.queryForObject(
                      "SELECT " + column + " FROM order_stock_action", LocalDateTime.class));
            assertNull(stored.get("lease_owner"));
            assertNull(stored.get("lease_until"));
            assertNull(stored.get("last_error"));
          }
        }
      }
    }
  }

  @Test
  void duplicateAndSqlFailuresKeepTheirOriginalExceptionContract() {
    LocalDateTime occurredAt = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    assertEquals(1, mapper.insert(1L, "order-1", "CONFIRM", occurredAt));
    var before = fixture.queryForMap("SELECT * FROM order_stock_action");
    assertThrows(
        DuplicateKeyException.class, () -> mapper.insert(1L, "order-1", "CONFIRM", occurredAt));
    assertThrows(
        DuplicateKeyException.class, () -> mapper.insert(2L, "order-1", "ROLLBACK", occurredAt));
    assertEquals(before, fixture.queryForMap("SELECT * FROM order_stock_action"));
    fixture.execute("DROP TABLE order_stock_action");
    assertThrows(
        DataAccessException.class, () -> mapper.insert(3L, "order-3", "CONFIRM", occurredAt));
  }

  @Test
  void submillisecondTimesAreFlooredBeforeDatabaseRoundingInBothModes() throws Exception {
    List<LocalDateTime[]> times =
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
              LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_500_000),
              LocalDateTime.of(2026, 10, 31, 23, 59, 59, 999_000_000)
            },
            new LocalDateTime[] {
              LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_999_999),
              LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000)
            },
            new LocalDateTime[] {
              LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_500_000),
              LocalDateTime.of(9999, 12, 31, 23, 59, 59, 999_000_000)
            },
            new LocalDateTime[] {
              LocalDateTime.of(1000, 1, 1, 0, 0, 0, 123_999_000),
              LocalDateTime.of(1000, 1, 1, 0, 0, 0, 123_000_000)
            },
            new LocalDateTime[] {
              LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000),
              LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000)
            });
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        for (LocalDateTime[] sample : times) {
          checks.add(
              () -> {
                modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
                modeJdbc.update("DELETE FROM order_stock_action");
                assertEquals(
                    1,
                    assertDoesNotThrow(
                        () -> modeMapper.insert(1L, "order-time", "CONFIRM", sample[0])));
                var row =
                    modeJdbc.queryForMap(
                        "SELECT id,order_no,action_type,operation_key,status,attempts FROM"
                            + " order_stock_action");
                assertEquals("order-time", row.get("order_no"));
                assertEquals("CONFIRM", row.get("action_type"));
                assertEquals("order-time:CONFIRM", row.get("operation_key"));
                assertEquals("PENDING", row.get("status"));
                assertEquals(0, ((Number) row.get("attempts")).intValue());
                for (String column : List.of("next_retry_at", "created_at", "updated_at")) {
                  assertEquals(
                      sample[1],
                      modeJdbc.queryForObject(
                          "SELECT " + column + " FROM order_stock_action", LocalDateTime.class),
                      mode + column + sample[0]);
                }
              });
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void outOfRangeTimesFailBeforeSqlAndPreserveThePreviousRow() throws Exception {
    List<LocalDateTime> invalidTimes =
        List.of(
            LocalDateTime.of(999, 12, 31, 23, 59, 59),
            LocalDateTime.of(0, 1, 1, 0, 0),
            LocalDateTime.of(10000, 1, 1, 0, 0),
            LocalDateTime.MIN,
            LocalDateTime.MAX);
    LocalDateTime validTime = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        for (LocalDateTime invalid : invalidTimes) {
          checks.add(
              () -> {
                modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
                modeJdbc.update("DELETE FROM order_stock_action");
                assertEquals(1, modeMapper.insert(7L, "keeper-time", "CONFIRM", validTime));
                var before = modeJdbc.queryForList("SELECT * FROM order_stock_action ORDER BY id");
                assertThrows(
                    IllegalArgumentException.class,
                    () -> modeMapper.insert(1L, "invalid-time", "ROLLBACK", invalid));
                assertEquals(
                    before, modeJdbc.queryForList("SELECT * FROM order_stock_action ORDER BY id"));
              });
        }
      }
      assertAll(checks);
    }
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
