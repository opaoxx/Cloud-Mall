package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.controller.OrderQueryController;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderQueryServiceImpl;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 MySQL 验证 dynamic-monthly 订单列表与 DATETIME(3) 查询边界。 */
class OrderQueryPrecisionMySqlIT {
  /** 原 cloudmall_order 连接。 */
  private static JdbcTemplate source;

  /** 原订单库清单与 checksum。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 当前测试随机隔离库。 */
  private String database;

  /** 当前隔离库 JDBC URL。 */
  private String isolatedUrl;

  /** 当前隔离库 SQL 模板。 */
  private JdbcTemplate fixture;

  /** 真实订单服务。 */
  private OrderQueryController service;

  /** 真实 Repeatable Read、read-only 事务模板。 */
  private TransactionTemplate transaction;

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
    database = "cm17d_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    isolatedUrl = withShanghaiTimezone(sourceUrl.replace("/cloudmall_order", "/" + database));
    DataSource dataSource = new DriverManagerDataSource(isolatedUrl, "root", "root");
    fixture = new JdbcTemplate(dataSource);
    createTables();
    seedRows();
    service = new OrderQueryController(new OrderQueryServiceImpl(new OrderSqlMapper(fixture)));
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.setReadOnly(true);
  }

  @AfterEach
  void cleanupFixture() {
    AuthContext.clear();
    if (database != null && database.matches("cm17d_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17D fixture cleaned: " + database);
      database = null;
    }
  }

  @AfterAll
  static void cleanupSource() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17D original table inventory and checksums unchanged");
  }

  @Test
  void endOneNanosecondAfterNewMonthIncludesTheFirstStoredMillisecond() {
    PageResult<?> result =
        page(list(null, 1, 20, "2027-01-01T00:00:00+08:00", "2027-01-01T00:00:00.000000001+08:00"));
    assertEquals(1, result.total);
    assertEquals(List.of("order-701-000"), orderNos(result));
  }

  @Test
  void normalizedEqualNanosecondWindowReturnsEmptyWithoutTouchingMissingMonth() {
    fixture.execute("DROP TABLE mall_order_202612");
    PageResult<?> result =
        page(list(null, 1, 20, "2026-12-31T23:59:59.999000001+08:00", "2027-01-01T00:00:00+08:00"));
    assertEquals(0, result.total);
    assertTrue(result.items.isEmpty());
  }

  @Test
  void startOneNanosecondAfterNewMonthExcludesTheFirstStoredMillisecond() {
    PageResult<?> result =
        page(
            list(
                null,
                1,
                20,
                "2027-01-01T00:00:00.000000001+08:00",
                "2027-01-01T00:00:00.002+08:00"));
    assertEquals(1, result.total);
    assertEquals(List.of("order-701-001"), orderNos(result));
  }

  @Test
  void normalCrossMonthQueryFiltersUserStatusAndPaginatesByCreatedAt() {
    PageResult<?> firstPage =
        page(list("PENDING", 1, 1, "2026-12-01T00:00:00+08:00", "2027-02-01T00:00:00+08:00"));
    PageResult<?> secondPage =
        page(list("PENDING", 2, 1, "2026-12-01T00:00:00+08:00", "2027-02-01T00:00:00+08:00"));
    assertEquals(3, firstPage.total);
    assertEquals(List.of("order-701-001"), orderNos(firstPage));
    assertEquals(List.of("order-701-000"), orderNos(secondPage));
    assertEquals(
        0,
        page(list("CANCELLED", 1, 20, "2026-12-01T00:00:00+08:00", "2027-02-01T00:00:00+08:00"))
            .total);
    assertEquals(
        0,
        page(list(null, 1, 20, "2026-12-01T00:00:00+08:00", "2027-02-01T00:00:00+08:00"))
            .items
            .stream()
            .filter(item -> ((com.cloudmall.order.domain.vo.OrderVO) item).userId.equals(8L))
            .count());
  }

  @Test
  void equivalentNonPlusEightOffsetReturnsTheSameInstantRows() {
    PageResult<?> local =
        page(list(null, 1, 20, "2027-01-01T00:00:00+08:00", "2027-01-01T00:00:00.002+08:00"));
    PageResult<?> utc = page(list(null, 1, 20, "2026-12-31T16:00:00Z", "2026-12-31T16:00:00.002Z"));
    assertEquals(orderNos(local), orderNos(utc));
    assertEquals(local.total, utc.total);
  }

  @Test
  void nonEmptyWindowWithMissingMonthReturnsShardUnavailable503() {
    fixture.execute("DROP TABLE mall_order_202612");
    BizException failure =
        assertThrows(
            BizException.class,
            () -> list(null, 1, 20, "2026-12-01T00:00:00+08:00", "2027-01-01T00:00:00+08:00"));
    assertEquals(503, failure.getHttpStatus());
    assertEquals("ORDER_QUERY_SHARD_UNAVAILABLE", failure.getCode());
  }

  @Test
  void invalidReversedAndDatetimeOverflowWindowsReturn400() {
    for (String[] range :
        List.of(
            new String[] {"2027-01-01T00:00:00+08:00", "2027-01-01T00:00:00+08:00"},
            new String[] {"2027-01-01T00:00:00.001+08:00", "2027-01-01T00:00:00+08:00"},
            new String[] {
              "9999-12-31T23:59:59.999000001+08:00", "9999-12-31T23:59:59.999000002+08:00"
            })) {
      BizException failure =
          assertThrows(BizException.class, () -> list(null, 1, 20, range[0], range[1]));
      assertEquals(400, failure.getHttpStatus());
    }
  }

  private ApiResponse<?> list(
      String status, int page, int pageSize, String startTime, String endTime) {
    AuthContext.set(7L, "USER");
    return transaction.execute(
        statusValue -> service.list(status, page, pageSize, startTime, endTime));
  }

  private PageResult<?> page(ApiResponse<?> response) {
    assertEquals("0", response.code);
    return (PageResult<?>) response.data;
  }

  private List<String> orderNos(PageResult<?> result) {
    return result.items.stream()
        .map(item -> ((com.cloudmall.order.domain.vo.OrderVO) item).orderNo)
        .toList();
  }

  private void createTables() {
    for (String month : List.of("202612", "202701"))
      fixture.execute(
          "CREATE TABLE mall_order_"
              + month
              + " (id BIGINT PRIMARY KEY,order_no VARCHAR(64) NOT NULL,user_id BIGINT NOT"
              + " NULL,status VARCHAR(32) NOT NULL,total_amount DECIMAL(18,2) NOT NULL,pay_amount"
              + " DECIMAL(18,2) NOT NULL,address_snapshot VARCHAR(512) NOT NULL,created_at"
              + " DATETIME(3) NOT NULL,expire_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT"
              + " NULL,UNIQUE KEY uk_order_no(order_no)) ENGINE=InnoDB");
  }

  private void seedRows() {
    insert(1, "order-612", 7L, "PENDING", LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000));
    insert(2, "order-701-000", 7L, "PENDING", LocalDateTime.of(2027, 1, 1, 0, 0));
    insert(3, "order-701-001", 7L, "PENDING", LocalDateTime.of(2027, 1, 1, 0, 0, 0, 1_000_000));
    insert(4, "order-701-user8", 8L, "CANCELLED", LocalDateTime.of(2027, 1, 1, 0, 0, 0, 2_000_000));
  }

  private void insert(
      long id, String orderNo, long userId, String status, LocalDateTime createdAt) {
    String table = createdAt.getMonthValue() == 12 ? "mall_order_202612" : "mall_order_202701";
    fixture.update(
        "INSERT INTO "
            + table
            + "(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,expire_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        id,
        orderNo,
        userId,
        status,
        BigDecimal.TEN,
        BigDecimal.TEN,
        "{}",
        Timestamp.valueOf(createdAt),
        Timestamp.valueOf(createdAt.plusHours(1)),
        Timestamp.valueOf(createdAt));
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
