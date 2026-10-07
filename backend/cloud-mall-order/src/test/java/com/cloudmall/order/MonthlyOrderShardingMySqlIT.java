package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.order.config.MonthlyOrderShardingAlgorithm;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.strategy.StandardShardingStrategyConfiguration;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.ShardingDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 真实 MySQL 与 Sharding-JDBC 4.1.1 隔离影子月路由验证。 */
class MonthlyOrderShardingMySqlIT {
  /** 原 cloudmall_order 连接。 */
  private static JdbcTemplate source;

  /** 隔离影子库连接。 */
  private static JdbcTemplate fixture;

  /** 隔离库名称，只允许删除本测试创建的库。 */
  private static String database;

  /** 原订单库表清单和 checksum。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 已创建的物理月表。 */
  private static final List<String> CREATED_MONTHS = List.of("202612", "202701");

  /** 配置的路由 target，故意包含尚未创建的 202702 以验证缺表失败。 */
  private static final List<String> CONFIGURED_MONTHS = List.of("202612", "202701", "202702");

  /** 隔离库的基础 JDBC URL。 */
  private static String isolatedUrl;

  @BeforeAll
  static void setup() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_order 测试 URL");
    source =
        new JdbcTemplate(new DriverManagerDataSource(withShanghaiTimezone(url), "root", "root"));
    sourceFingerprint = fingerprint();
    database = "cm17b_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    isolatedUrl = withShanghaiTimezone(url.replace("/cloudmall_order", "/" + database));
    fixture = new JdbcTemplate(new DriverManagerDataSource(isolatedUrl, "root", "root"));
    createPhysicalTables();
    seedRows();
  }

  @AfterAll
  static void cleanup() {
    try {
      if (source != null) assertEquals(sourceFingerprint, fingerprint());
    } finally {
      if (database != null && database.matches("cm17b_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
        System.out.println(
            "CM17B fixture cleaned; original tables/checksums unchanged: " + database);
      }
    }
  }

  @BeforeEach
  void verifyNoUnexpectedPhysicalTableWasCreated() {
    assertPhysicalTablesUnchanged();
  }

  @AfterEach
  void verifyNoPhysicalTableWasCreatedAfterTest() {
    assertPhysicalTablesUnchanged();
  }

  private void assertPhysicalTablesUnchanged() {
    assertEquals(
        List.of(
            "mall_order_202612",
            "mall_order_202701",
            "mall_order_item_202612",
            "mall_order_item_202701",
            "order_status_log_202612",
            "order_status_log_202701"),
        fixture.queryForList("SHOW TABLES FROM " + database, String.class));
  }

  @Test
  void preciseTimestampAndLocalDateTimeRouteAllThreeLogicalTables() throws Exception {
    try (ShardingDataSource shadow = createShadowDataSource()) {
      JdbcTemplate routed = new JdbcTemplate(shadow);
      for (String logicTable : List.of("mall_order", "mall_order_item", "order_status_log")) {
        assertEquals(
            List.of("order-612"),
            routed.queryForList(
                "SELECT order_no FROM " + logicTable + " WHERE created_at=?",
                String.class,
                Timestamp.valueOf(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000))));
        assertEquals(
            List.of("order-701"),
            routed.queryForList(
                "SELECT order_no FROM " + logicTable + " WHERE created_at=?",
                String.class,
                LocalDateTime.of(2027, 1, 1, 0, 0)));
      }
    }
  }

  @Test
  void crossYearHalfOpenRangeMatchesDirectPhysicalQueries() throws Exception {
    LocalDateTime lower = LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000);
    LocalDateTime upper = LocalDateTime.of(2027, 1, 1, 0, 0, 0, 1_000_000);
    try (ShardingDataSource shadow = createShadowDataSource()) {
      JdbcTemplate routed = new JdbcTemplate(shadow);
      for (String logicTable : List.of("mall_order", "mall_order_item", "order_status_log")) {
        List<String> routedRows =
            routed.queryForList(
                "SELECT order_no FROM "
                    + logicTable
                    + " WHERE created_at>=? AND created_at<? ORDER BY created_at",
                String.class,
                Timestamp.valueOf(lower),
                Timestamp.valueOf(upper));
        List<String> expected =
            fixture.queryForList(
                "SELECT order_no FROM "
                    + logicTable
                    + "_202612 WHERE created_at>=? AND created_at<?"
                    + " UNION ALL SELECT order_no FROM "
                    + logicTable
                    + "_202701 WHERE created_at>=? AND created_at<? ORDER BY order_no",
                String.class,
                Timestamp.valueOf(lower),
                Timestamp.valueOf(upper),
                Timestamp.valueOf(lower),
                Timestamp.valueOf(upper));
        assertEquals(expected, routedRows.stream().sorted().toList(), logicTable);
      }
    }
  }

  @Test
  void openUpperBoundaryAtMonthStartExcludesTheNewMonth() throws Exception {
    LocalDateTime boundary = LocalDateTime.of(2027, 1, 1, 0, 0);
    try (ShardingDataSource shadow = createShadowDataSource()) {
      JdbcTemplate routed = new JdbcTemplate(shadow);
      assertEquals(
          List.of("order-612"),
          routed
              .queryForList(
                  "SELECT order_no FROM mall_order WHERE created_at>=? AND created_at<?",
                  String.class,
                  Timestamp.valueOf(boundary.minusMonths(1)),
                  Timestamp.valueOf(boundary))
              .stream()
              .sorted()
              .toList());
      assertEquals(
          List.of("order-701"),
          routed
              .queryForList(
                  "SELECT order_no FROM mall_order WHERE created_at>? AND created_at<?",
                  String.class,
                  Timestamp.valueOf(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000)),
                  Timestamp.valueOf(boundary.plusNanos(1_000_000)))
              .stream()
              .sorted()
              .toList());
    }
  }

  @Test
  void equalRangeReportsRecognizableEmptyRangeWithoutBroadcasting() throws Exception {
    LocalDateTime boundary = LocalDateTime.of(2027, 1, 1, 0, 0);
    try (ShardingDataSource shadow = createShadowDataSource()) {
      assertRoutingFailure(
          () ->
              new JdbcTemplate(shadow)
                  .queryForList(
                      "SELECT order_no FROM mall_order WHERE created_at>=? AND created_at<?",
                      String.class,
                      Timestamp.valueOf(boundary),
                      Timestamp.valueOf(boundary)),
          "ORDER_SHARD_ROUTE_EMPTY: no representable DATETIME(3) value");
    }
  }

  @Test
  void configuredMissingMonthAndPhysicalMissingTableFailClosed() throws Exception {
    try (ShardingDataSource shadow = createShadowDataSource()) {
      assertRoutingFailure(
          () ->
              new JdbcTemplate(shadow)
                  .queryForList(
                      "SELECT order_no FROM mall_order WHERE created_at=?",
                      String.class,
                      Timestamp.valueOf("2027-03-01 00:00:00")),
          "requested month unavailable: 2027-03");
      // 返回部分十二月/一月行也是失败：缺少二月物理表时整个查询必须抛错。
      assertRoutingFailure(
          () ->
              new JdbcTemplate(shadow)
                  .queryForList(
                      "SELECT order_no FROM mall_order WHERE created_at>=? AND created_at<?",
                      String.class,
                      Timestamp.valueOf("2026-12-01 00:00:00"),
                      Timestamp.valueOf("2027-03-01 00:00:00")),
          "doesn't exist");
    }
  }

  @Test
  void unboundedRangeFailsClosedAndAllLogicalTablesShareMonthTargets() throws Exception {
    assertEquals(months(), months("mall_order_item"));
    assertEquals(months(), months("order_status_log"));
    try (ShardingDataSource shadow = createShadowDataSource()) {
      assertRoutingFailure(
          () ->
              new JdbcTemplate(shadow)
                  .queryForList(
                      "SELECT order_no FROM mall_order WHERE created_at>=?",
                      String.class,
                      Timestamp.valueOf(LocalDateTime.of(2026, 12, 1, 0, 0))),
          "bounded created_at range required");
    }
  }

  @Test
  void unsupportedPreciseTypeFailsWithoutImplicitTimezoneGuessing() throws Exception {
    try (ShardingDataSource shadow = createShadowDataSource()) {
      assertRoutingFailure(
          () ->
              new JdbcTemplate(shadow)
                  .queryForList(
                      "SELECT order_no FROM mall_order WHERE created_at=?",
                      String.class,
                      "2027-01-01"),
          "LocalDateTime or Timestamp required");
    }
  }

  private ShardingDataSource createShadowDataSource() throws Exception {
    Map<String, DataSource> dataSources =
        Map.of("ds0", new DriverManagerDataSource(isolatedUrl, "root", "root"));
    MonthlyOrderShardingAlgorithm algorithm = new MonthlyOrderShardingAlgorithm();
    ShardingRuleConfiguration configuration = new ShardingRuleConfiguration();
    for (String logicTable : List.of("mall_order", "mall_order_item", "order_status_log")) {
      TableRuleConfiguration table =
          new TableRuleConfiguration(
              logicTable,
              configuredTargets(logicTable).stream()
                  .map(target -> "ds0." + target)
                  .collect(java.util.stream.Collectors.joining(",")));
      table.setTableShardingStrategyConfig(
          new StandardShardingStrategyConfiguration("created_at", algorithm, algorithm));
      configuration.getTableRuleConfigs().add(table);
    }
    return (ShardingDataSource)
        ShardingDataSourceFactory.createDataSource(dataSources, configuration, new Properties());
  }

  private List<String> configuredTargets(String logicTable) {
    return CONFIGURED_MONTHS.stream().map(month -> logicTable + "_" + month).toList();
  }

  private List<String> months() {
    return months("mall_order");
  }

  private List<String> months(String logicTable) {
    return configuredTargets(logicTable).stream()
        .map(target -> target.substring(target.lastIndexOf('_') + 1))
        .toList();
  }

  private void assertRoutingFailure(
      org.junit.jupiter.api.function.Executable operation, String detail) {
    RuntimeException failure = assertThrows(RuntimeException.class, operation);
    boolean containsDetail = false;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause.getMessage() != null && cause.getMessage().contains(detail)) {
        containsDetail = true;
        break;
      }
    }
    assertTrue(containsDetail, () -> "路由失败未包含预期细节: " + detail + ", actual=" + failure);
  }

  private static String withShanghaiTimezone(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }

  private static void createPhysicalTables() {
    for (String logicTable : List.of("mall_order", "mall_order_item", "order_status_log"))
      for (String month : CREATED_MONTHS)
        fixture.execute(
            "CREATE TABLE "
                + logicTable
                + "_"
                + month
                + " (id BIGINT PRIMARY KEY,created_at DATETIME(3) NOT NULL,order_no VARCHAR(64) NOT"
                + " NULL,payload VARCHAR(64)) ENGINE=InnoDB");
  }

  private static void seedRows() {
    for (String logicTable : List.of("mall_order", "mall_order_item", "order_status_log")) {
      fixture.update(
          "INSERT INTO " + logicTable + "_202612(id,created_at,order_no,payload) VALUES(1,?,?,?)",
          Timestamp.valueOf(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000)),
          "order-612",
          logicTable);
      fixture.update(
          "INSERT INTO " + logicTable + "_202701(id,created_at,order_no,payload) VALUES(2,?,?,?)",
          Timestamp.valueOf(LocalDateTime.of(2027, 1, 1, 0, 0)),
          "order-701",
          logicTable);
    }
  }

  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> result = new ArrayList<>();
    List<String> tables = source.queryForList("SHOW TABLES FROM cloudmall_order", String.class);
    result.addAll(source.queryForList("SHOW TABLES FROM cloudmall_order"));
    for (String table : tables) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法表名: " + table);
      result.addAll(source.queryForList("CHECKSUM TABLE cloudmall_order." + table));
    }
    return result;
  }
}
