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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 库存动作owner写前边界的真实MySQL验证，正常UUID路径本来满足此约束。 */
class OrderStockActionOwnerMySqlIT {
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
    database = "cm17bl_" + UUID.randomUUID().toString().replace("-", "");
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
    if (database != null && database.matches("cm17bl_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17BL fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17BL original Order tables and checksums unchanged");
  }

  @Test
  void invalidClaimOwnersReturnZeroWithoutChangingAnyRowInBothModes() throws Exception {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        for (String owner :
            java.util.Arrays.asList(
                null, "", " \t", "a".repeat(129), "中".repeat(129), "😀".repeat(129))) {
          checks.add(
              () -> {
                modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
                seed(modeJdbc, modeMapper, now);
                var before = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
                int changed = assertDoesNotThrow(() -> modeMapper.claim(1L, owner, now));
                var after = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
                assertAll(() -> assertEquals(0, changed), () -> assertEquals(before, after));
              });
        }
      }
      assertAll(checks);
    }
  }

  @Test
  void legalOwnerWidthsAndOriginalCharactersCanFinishOrRetry() throws Exception {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderStockActionMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (String owner :
            List.of("x", "a".repeat(128), "中".repeat(128), "😀".repeat(128), " Mixed owner ")) {
          for (String status : List.of("PENDING", "UNKNOWN")) {
            for (String operation : List.of("finish", "retry")) {
              seed(modeJdbc, modeMapper, now);
              modeJdbc.update("UPDATE order_stock_action SET status=? WHERE id=1", status);
              assertEquals(1, modeMapper.claim(1L, owner, now));
              var claimed = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
              assertEquals(owner, claimed.get("lease_owner"));
              assertEquals(
                  now.plusSeconds(30),
                  modeJdbc.queryForObject(
                      "SELECT lease_until FROM order_stock_action", LocalDateTime.class));
              assertEquals(1, ((Number) claimed.get("attempts")).intValue());
              assertEquals(
                  1,
                  "finish".equals(operation)
                      ? modeMapper.finish(1L, owner, "SUCCEEDED", null, now.plusSeconds(1))
                      : modeMapper.retry(
                          1L, owner, "unknown", now.plusMinutes(1), now.plusSeconds(1)));
              var after = modeJdbc.queryForMap("SELECT * FROM order_stock_action");
              assertEquals(
                  "finish".equals(operation) ? "SUCCEEDED" : "UNKNOWN", after.get("status"));
              assertEquals("order-owner:CONFIRM", after.get("operation_key"));
              assertEquals(claimed.get("created_at"), after.get("created_at"));
              assertEquals(1, ((Number) after.get("attempts")).intValue());
              assertNull(after.get("lease_owner"));
              assertNull(after.get("lease_until"));
            }
          }
        }
      }
    }
  }

  @Test
  void invalidOwnersTakePrecedenceOverBadTimeInputsAndCannotComplete() {
    LocalDateTime now = LocalDateTime.of(2026, 10, 8, 12, 0, 0, 123_000_000);
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String owner :
        java.util.Arrays.asList(
            null, "", " \t", "a".repeat(129), "中".repeat(129), "😀".repeat(129))) {
      checks.add(
          () -> {
            seed(fixture, mapper, now);
            assertEquals(1, mapper.claim(1L, "valid-owner", now));
            var before = fixture.queryForMap("SELECT * FROM order_stock_action");
            assertEquals(0, assertDoesNotThrow(() -> mapper.claim(1L, owner, null)));
            assertEquals(
                0, assertDoesNotThrow(() -> mapper.finish(1L, owner, "SUCCEEDED", null, null)));
            assertEquals(
                0, assertDoesNotThrow(() -> mapper.retry(1L, owner, "unknown", null, null)));
            assertEquals(before, fixture.queryForMap("SELECT * FROM order_stock_action"));
          });
    }
    assertAll(checks);
  }

  /** 只在自建随机库准备合法到期动作，后续完整行比较包括租约与次数。 */
  private static void seed(JdbcTemplate jdbc, OrderStockActionMapper mapper, LocalDateTime now) {
    jdbc.update("DELETE FROM order_stock_action");
    assertEquals(1, mapper.insert(1L, "order-owner", "CONFIRM", now.minusMinutes(1)));
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
