package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.order.config.OrderShardingRuleFactory;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.shardingsphere.api.config.sharding.ShardingRuleConfiguration;
import org.apache.shardingsphere.api.config.sharding.TableRuleConfiguration;
import org.apache.shardingsphere.shardingjdbc.api.ShardingDataSourceFactory;
import org.apache.shardingsphere.shardingjdbc.jdbc.core.datasource.ShardingDataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 真实 MySQL 与 Sharding-JDBC 4.1.1 节点清单快照及跨年路由验证。 */
class MonthlyOrderShardingInventoryMySqlIT {
  /** 原 cloudmall_order 数据库连接。 */
  private static JdbcTemplate source;

  /** 原库表清单和 checksum。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 每个测试独立创建的隔离数据库。 */
  private String database;

  /** 当前测试隔离库连接。 */
  private JdbcTemplate fixture;

  /** 当前测试隔离库 JDBC URL。 */
  private String isolatedUrl;

  /** 初始历史月与滚动窗口，合计五个月、三类表十五张。 */
  private static final List<String> INITIAL_MONTHS =
      List.of("202610", "202611", "202612", "202701", "202702");

  @BeforeAll
  static void setupSource() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_order 测试 URL");
    source =
        new JdbcTemplate(new DriverManagerDataSource(withShanghaiTimezone(url), "root", "root"));
    sourceFingerprint = fingerprint();
  }

  @BeforeEach
  void createFixture() {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    database = "cm17c_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    isolatedUrl = withShanghaiTimezone(sourceUrl.replace("/cloudmall_order", "/" + database));
    fixture = new JdbcTemplate(new DriverManagerDataSource(isolatedUrl, "root", "root"));
    createPhysicalTables(INITIAL_MONTHS);
    seedRows(INITIAL_MONTHS);
    assertEquals(15, fixture.queryForList("SHOW TABLES FROM " + database, String.class).size());
  }

  @AfterEach
  void dropFixture() {
    if (database != null && database.matches("cm17c_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17C fixture cleaned: " + database);
      database = null;
    }
  }

  @AfterAll
  static void cleanupSource() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17C original table inventory and checksums unchanged");
  }

  @Test
  void factorySnapshotRoutesPreciseAndRangeQueriesAcrossThreeTables() throws Exception {
    Map<String, Collection<String>> inventory = inventory();
    ShardingRuleConfiguration rules =
        OrderShardingRuleFactory.create(YearMonth.of(2026, 12), inventory);
    assertRulesUseDs0AndAllObservedMonths(rules, inventory);
    try (ShardingDataSource shadow = createDataSource(rules)) {
      JdbcTemplate routed = new JdbcTemplate(shadow);
      for (String logicTable : LOGICAL_TABLES) {
        assertEquals(
            List.of("order-202612"),
            routed.queryForList(
                "SELECT order_no FROM " + logicTable + " WHERE created_at=?",
                String.class,
                Timestamp.valueOf(LocalDateTime.of(2026, 12, 15, 12, 0))));
        assertEquals(
            directRange(logicTable),
            routed
                .queryForList(
                    "SELECT order_no FROM "
                        + logicTable
                        + " WHERE created_at>=? AND created_at<? ORDER BY created_at",
                    String.class,
                    Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 0, 0)),
                    Timestamp.valueOf(LocalDateTime.of(2027, 2, 1, 0, 0)))
                .stream()
                .sorted()
                .toList(),
            logicTable);
      }
    }
  }

  @Test
  void oldDecemberSnapshotCannotSeeMarchUntilJanuarySnapshotIsRebuilt() throws Exception {
    Map<String, Collection<String>> decemberInventory = inventory();
    ShardingRuleConfiguration decemberRules =
        OrderShardingRuleFactory.create(YearMonth.of(2026, 12), decemberInventory);
    try (ShardingDataSource oldShadow = createDataSource(decemberRules)) {
      createPhysicalTables(List.of("202703"));
      seedRows(List.of("202703"));
      assertRoutingFailure(
          () ->
              new JdbcTemplate(oldShadow)
                  .queryForList(
                      "SELECT order_no FROM mall_order WHERE created_at=?",
                      String.class,
                      Timestamp.valueOf(LocalDateTime.of(2027, 3, 15, 12, 0))),
          "requested month unavailable: 2027-03");
    }
    Map<String, Collection<String>> januaryInventory = inventory();
    ShardingRuleConfiguration januaryRules =
        OrderShardingRuleFactory.create(YearMonth.of(2027, 1), januaryInventory);
    try (ShardingDataSource januaryShadow = createDataSource(januaryRules)) {
      JdbcTemplate routed = new JdbcTemplate(januaryShadow);
      assertEquals(
          List.of("order-202703"),
          routed.queryForList(
              "SELECT order_no FROM mall_order WHERE created_at=?",
              String.class,
              Timestamp.valueOf(LocalDateTime.of(2027, 3, 15, 12, 0))));
      assertEquals(
          List.of("order-202610"),
          routed.queryForList(
              "SELECT order_no FROM mall_order WHERE created_at=?",
              String.class,
              Timestamp.valueOf(LocalDateTime.of(2026, 10, 15, 12, 0))));
    }
  }

  @Test
  void missingCompanionOrHistoricalMonthFailsBeforeDataSourceCreation() {
    fixture.execute("DROP TABLE mall_order_item_202701");
    Map<String, Collection<String>> missingCompanion = inventory();
    IllegalArgumentException companionFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> OrderShardingRuleFactory.create(YearMonth.of(2026, 12), missingCompanion));
    assertEquals(
        "ORDER_SHARD_INVENTORY_INVALID: companion month sets differ",
        companionFailure.getMessage());
    assertEquals(14, fixture.queryForList("SHOW TABLES FROM " + database, String.class).size());
  }

  @Test
  void commonHistoricalGapIsRejectedEvenWhenAllThreeListsAgree() {
    for (String table : LOGICAL_TABLES) fixture.execute("DROP TABLE " + table + "_202611");
    Map<String, Collection<String>> missingHistory = inventory();
    IllegalArgumentException historyFailure =
        assertThrows(
            IllegalArgumentException.class,
            () -> OrderShardingRuleFactory.create(YearMonth.of(2026, 12), missingHistory));
    assertEquals(
        "ORDER_SHARD_INVENTORY_INVALID: historical month gap", historyFailure.getMessage());
    assertEquals(12, fixture.queryForList("SHOW TABLES FROM " + database, String.class).size());
  }

  @Test
  void deletingPhysicalTargetAfterSnapshotFailsWholeRangeWithoutRebuilding() throws Exception {
    ShardingRuleConfiguration rules =
        OrderShardingRuleFactory.create(YearMonth.of(2026, 12), inventory());
    try (ShardingDataSource shadow = createDataSource(rules)) {
      fixture.execute("DROP TABLE mall_order_202701");
      assertRoutingFailure(
          () ->
              new JdbcTemplate(shadow)
                  .queryForList(
                      "SELECT order_no FROM mall_order WHERE created_at>=? AND created_at<?",
                      String.class,
                      Timestamp.valueOf(LocalDateTime.of(2026, 12, 1, 0, 0)),
                      Timestamp.valueOf(LocalDateTime.of(2027, 2, 1, 0, 0))),
          "doesn't exist");
    }
    assertFalse(
        fixture
            .queryForList("SHOW TABLES FROM " + database, String.class)
            .contains("mall_order_202701"));
  }

  private ShardingDataSource createDataSource(ShardingRuleConfiguration rules) throws Exception {
    Map<String, DataSource> dataSources =
        Map.of("ds0", new DriverManagerDataSource(isolatedUrl, "root", "root"));
    return (ShardingDataSource)
        ShardingDataSourceFactory.createDataSource(dataSources, rules, new Properties());
  }

  private Map<String, Collection<String>> inventory() {
    Map<String, Collection<String>> result = new LinkedHashMap<>();
    for (String logicTable : LOGICAL_TABLES) result.put(logicTable, new ArrayList<>());
    for (String table : fixture.queryForList("SHOW TABLES FROM " + database, String.class)) {
      if (table.startsWith("mall_order_item_")) result.get("mall_order_item").add(table);
      else if (table.startsWith("mall_order_")) result.get("mall_order").add(table);
      else if (table.startsWith("order_status_log_")) result.get("order_status_log").add(table);
      else fail("发现未归属的物理表: " + table);
    }
    result.values().forEach(values -> ((List<String>) values).sort(Comparator.naturalOrder()));
    return result;
  }

  private List<String> directRange(String logicTable) {
    List<String> rows = new ArrayList<>();
    for (String month : INITIAL_MONTHS)
      rows.addAll(
          fixture.queryForList(
              "SELECT order_no FROM "
                  + logicTable
                  + "_"
                  + month
                  + " WHERE created_at>=? AND created_at<?",
              String.class,
              Timestamp.valueOf(LocalDateTime.of(2026, 11, 1, 0, 0)),
              Timestamp.valueOf(LocalDateTime.of(2027, 2, 1, 0, 0))));
    return rows.stream().sorted().toList();
  }

  private void assertRulesUseDs0AndAllObservedMonths(
      ShardingRuleConfiguration rules, Map<String, Collection<String>> inventory) {
    for (TableRuleConfiguration table : rules.getTableRuleConfigs()) {
      assertTrue(table.getActualDataNodes().contains("ds0."));
      for (String physicalTable : inventory.get(table.getLogicTable()))
        assertTrue(table.getActualDataNodes().contains("ds0." + physicalTable));
    }
  }

  private void createPhysicalTables(Collection<String> months) {
    for (String logicTable : LOGICAL_TABLES)
      for (String month : months)
        fixture.execute(
            "CREATE TABLE "
                + logicTable
                + "_"
                + month
                + " (id BIGINT PRIMARY KEY,created_at DATETIME(3) NOT NULL,order_no VARCHAR(64) NOT"
                + " NULL,payload VARCHAR(64)) ENGINE=InnoDB");
  }

  private void seedRows(Collection<String> months) {
    long id = 1;
    for (String logicTable : LOGICAL_TABLES)
      for (String month : months) {
        int year = Integer.parseInt(month.substring(0, 4));
        int monthNumber = Integer.parseInt(month.substring(4));
        fixture.update(
            "INSERT INTO "
                + logicTable
                + "_"
                + month
                + "(id,created_at,order_no,payload) VALUES(?,?,?,?)",
            id++,
            Timestamp.valueOf(LocalDateTime.of(year, monthNumber, 15, 12, 0)),
            "order-" + month,
            logicTable);
      }
  }

  private void assertRoutingFailure(
      org.junit.jupiter.api.function.Executable operation, String expectedDetail) {
    RuntimeException failure = assertThrows(RuntimeException.class, operation);
    boolean found = false;
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause.getMessage() != null && cause.getMessage().contains(expectedDetail)) found = true;
    assertTrue(found, () -> "路由错误未包含预期细节: " + expectedDetail + ", actual=" + failure);
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

  private static String withShanghaiTimezone(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }

  /** 三张逻辑表，按固定顺序生成夹具和验证路由。 */
  private static final List<String> LOGICAL_TABLES =
      List.of("mall_order", "mall_order_item", "order_status_log");
}
