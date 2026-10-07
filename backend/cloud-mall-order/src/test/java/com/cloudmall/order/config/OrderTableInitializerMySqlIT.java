package com.cloudmall.order.config;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.order.mapper.OrderSqlMapper;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
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

/** 真实 MySQL 验证订单月表模板引导、fallback 和跨年幂等建表。 */
class OrderTableInitializerMySqlIT {
  /** 原 cloudmall_order 连接。 */
  private static JdbcTemplate source;

  /** 原库清单和 checksum。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 当前测试隔离库。 */
  private String database;

  /** 当前测试隔离库 SQL 模板。 */
  private JdbcTemplate fixture;

  /** 当前测试隔离库连接 URL。 */
  private String isolatedUrl;

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
  void setupFixture() {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    database = "cm17e_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    isolatedUrl = withShanghaiTimezone(sourceUrl.replace("/cloudmall_order", "/" + database));
    fixture = new JdbcTemplate(new DriverManagerDataSource(isolatedUrl, "root", "root"));
    cloneFromSource("mall_order_template", "mall_order_template");
  }

  @AfterEach
  void cleanupFixture() {
    if (database != null && database.matches("cm17e_[0-9a-f]{32}")) {
      String droppedDatabase = database;
      source.execute("DROP DATABASE " + droppedDatabase);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              droppedDatabase));
      System.out.println("已清理订单模板测试隔离库: " + droppedDatabase);
      database = null;
    }
  }

  @AfterAll
  static void cleanupSource() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17E original tables and checksums unchanged");
  }

  @Test
  void formalCompanionTemplatesWorkWithoutLegacyAndRemainIdempotentAcrossJanuary() {
    cloneFromSource("mall_order_item_template", "mall_order_item_template");
    cloneFromSource("order_status_log_template", "order_status_log_template");
    OrderTableInitializer initializer = initializer();

    initializer.prepareForMonth(YearMonth.of(2026, 12));
    assertBusinessMonths(List.of("202611", "202612", "202701", "202702"));
    assertFalse(tables().contains("mall_order_item_202608"));
    assertFalse(tables().contains("order_status_log_202608"));

    insertSentinel();
    String sentinelSchema = schema("mall_order_202612");
    List<Map<String, Object>> checksum = fixture.queryForList("CHECKSUM TABLE mall_order_202612");
    initializer.prepareForMonth(YearMonth.of(2026, 12));
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM mall_order_202612 WHERE order_no='sentinel-612'", Integer.class));
    assertEquals(sentinelSchema, schema("mall_order_202612"));
    assertEquals(checksum, fixture.queryForList("CHECKSUM TABLE mall_order_202612"));

    initializer.prepareForMonth(YearMonth.of(2027, 1));
    assertBusinessMonths(List.of("202611", "202612", "202701", "202702", "202703"));
    assertEquals(checksum, fixture.queryForList("CHECKSUM TABLE mall_order_202612"));
    for (String table : List.of("mall_order", "mall_order_item", "order_status_log")) {
      assertEquals(schema(table + "_template"), schema(table + "_202703"));
    }
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM mall_order_202612 WHERE order_no='sentinel-612'", Integer.class));
  }

  @Test
  void missingFormalCompanionsFallbackToTheirOwnLegacyTables() {
    cloneFromSource("mall_order_item_template", "mall_order_item_202608");
    cloneFromSource("order_status_log_template", "order_status_log_202608");
    OrderTableInitializer initializer = initializer();

    initializer.prepareForMonth(YearMonth.of(2026, 12));
    assertTrue(tables().contains("mall_order_item_template"));
    assertTrue(tables().contains("order_status_log_template"));
    assertEquals(schema("mall_order_item_202608"), schema("mall_order_item_template"));
    assertEquals(schema("order_status_log_202608"), schema("order_status_log_template"));
    assertBusinessMonths(List.of("202611", "202612", "202701", "202702"));
  }

  @Test
  void missingFormalAndLegacyCompanionFailsBeforeBusinessMonthDdl() {
    OrderTableInitializer initializer = initializer();
    DataAccessException failure =
        assertThrows(
            DataAccessException.class, () -> initializer.prepareForMonth(YearMonth.of(2026, 12)));
    assertTrue(
        failureChainContains(failure, "mall_order_item")
            || failureChainContains(failure, "202608"));
    assertEquals(List.of("mall_order_template"), tables());
  }

  @Test
  void mixedFormalAndLegacySourcesAreResolvedIndependently() {
    cloneFromSource("mall_order_item_template", "mall_order_item_template");
    cloneFromSource("order_status_log_template", "order_status_log_202608");
    initializer().prepareForMonth(YearMonth.of(2026, 12));
    assertBusinessMonths(List.of("202611", "202612", "202701", "202702"));
    assertFalse(tables().contains("mall_order_item_202608"));
    assertEquals(schema("order_status_log_202608"), schema("order_status_log_template"));
  }

  private OrderTableInitializer initializer() {
    return new OrderTableInitializer(new OrderSqlMapper(fixture));
  }

  private void cloneFromSource(String sourceTable, String targetTable) {
    String ddl =
        String.valueOf(
            source
                .queryForMap("SHOW CREATE TABLE cloudmall_order." + sourceTable)
                .get("Create Table"));
    String sourceQuoted = "`" + sourceTable + "`";
    if (ddl.contains(sourceQuoted)) ddl = ddl.replace(sourceQuoted, "`" + targetTable + "`");
    else ddl = ddl.replaceFirst("(?i)CREATE TABLE " + sourceTable, "CREATE TABLE " + targetTable);
    fixture.execute(ddl);
  }

  private void insertSentinel() {
    fixture.update(
        "INSERT INTO mall_order_202612"
            + "(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
            + " VALUES(1,'sentinel-612',7,'PENDING',10,10,JSON_OBJECT(),?,?,?)",
        Timestamp.valueOf(LocalDateTime.of(2027, 1, 1, 0, 0)),
        Timestamp.valueOf(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000)),
        Timestamp.valueOf(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000)));
  }

  private void assertBusinessMonths(List<String> months) {
    for (String month : months)
      for (String table : List.of("mall_order_", "mall_order_item_", "order_status_log_"))
        assertTrue(tables().contains(table + month), table + month);
  }

  private List<String> tables() {
    return fixture.queryForList("SHOW TABLES FROM " + database, String.class);
  }

  private String schema(String table) {
    String ddl =
        String.valueOf(fixture.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
    return ddl.replace("`" + table + "`", "`<table>`");
  }

  private boolean failureChainContains(Throwable failure, String detail) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      if (cause.getMessage() != null && cause.getMessage().contains(detail)) return true;
    return false;
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
}
