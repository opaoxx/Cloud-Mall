package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;

/** 显式真实MySQL验收普通订单意图严格插入，每例仅写随机库并核对原库校验和。 */
class OrderIntentStrictInsertMySqlIT {
  /** 只读核验原订单表、创建和清理隔离库的连接。 */
  private static JdbcTemplate source;

  /** 原订单库表清单和校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 当前用例自行创建、可精确清理的数据库名。 */
  private String database;

  /** 隔离库的真实数据库验证入口。 */
  private JdbcTemplate fixture;

  /** 实际被测预扣意图持久层。 */
  private OrderReservationIntentMapper mapper;

  @BeforeAll
  static void setupSource() {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?")) {
      throw new IllegalStateException("显式IT需要cloudmall_order的cloudmall.mysql.test.url");
    }
    source =
        new JdbcTemplate(
            new DriverManagerDataSource(withShanghaiTimezone(sourceUrl), "root", "root"));
    sourceFingerprint = fingerprint();
  }

  @BeforeEach
  void setupFixture() {
    database = "cm17ao_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolatedUrl =
        System.getProperty("cloudmall.mysql.test.url").replace("/cloudmall_order", "/" + database);
    fixture =
        new JdbcTemplate(
            new DriverManagerDataSource(withShanghaiTimezone(isolatedUrl), "root", "root"));
    fixture.execute(
        String.valueOf(
            source.queryForMap("SHOW CREATE TABLE order_reservation_intent").get("Create Table")));
    mapper = new OrderReservationIntentMapper(fixture);
  }

  @AfterEach
  void cleanupFixture() {
    if (database != null && database.matches("cm17ao_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17AO fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalTables() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17AO original Order tables and checksums unchanged");
  }

  @Test
  void oversizedColumnsCannotBecomeTruncatedFactsInEitherSqlMode() throws Exception {
    List<Consumer<OrderReservationIntentPO>> corruptions =
        List.of(
            intent -> intent.orderNo = "o".repeat(65),
            intent -> intent.orderNo = "😀".repeat(65),
            intent -> intent.idempotencyKey = "k".repeat(129),
            intent -> intent.idempotencyKey = "😀".repeat(129),
            intent -> intent.status = "s".repeat(33),
            intent -> intent.lastError = "e".repeat(513),
            intent -> intent.lastError = "😀".repeat(513));
    assertRejectedInBothModes(corruptions);
  }

  @Test
  void requiredNullsCannotBecomeDefaultValuesInEitherSqlMode() throws Exception {
    List<Consumer<OrderReservationIntentPO>> corruptions =
        List.of(
            intent -> intent.id = null,
            intent -> intent.userId = null,
            intent -> intent.orderNo = null,
            intent -> intent.idempotencyKey = null,
            intent -> intent.itemsSnapshot = null,
            intent -> intent.status = null,
            intent -> intent.createdAt = null,
            intent -> intent.updatedAt = null);
    assertRejectedInBothModes(corruptions);
    assertThrows(RuntimeException.class, () -> mapper.insertIfAbsent(null));
    assertEquals(0, rowCount());
  }

  @Test
  void blankIdentifiersAndStatusDoNotProduceFacts() throws Exception {
    assertRejectedInBothModes(
        List.of(
            intent -> intent.orderNo = " \t",
            intent -> intent.idempotencyKey = " \t",
            intent -> intent.status = " \t"));
  }

  @Test
  void legalNonBmpColumnBoundariesAndNullableFieldsKeepTheirValues() {
    OrderReservationIntentPO boundary = validIntent();
    for (String character : List.of("a", "中", "😀")) {
      fixture.update("DELETE FROM order_reservation_intent");
      boundary.orderNo = character.repeat(64);
      boundary.idempotencyKey = character.repeat(128);
      boundary.status = "s".repeat(32);
      boundary.lastError = character.repeat(512);
      assertEquals(1, mapper.insertIfAbsent(boundary));
      Map<String, Object> stored = fixture.queryForMap("SELECT * FROM order_reservation_intent");
      assertEquals(boundary.orderNo, stored.get("order_no"));
      assertEquals(boundary.idempotencyKey, stored.get("idempotency_key"));
      assertEquals(boundary.status, stored.get("status"));
      assertEquals(boundary.lastError, stored.get("last_error"));
      assertNull(stored.get("next_retry_at"));
      assertEquals(
          boundary.createdAt, mapper.findByUserAndKey(7, boundary.idempotencyKey).get(0).createdAt);
    }
    fixture.update("DELETE FROM order_reservation_intent");
    OrderReservationIntentPO nullable = validIntent();
    assertEquals(1, mapper.insertIfAbsent(nullable));
    Map<String, Object> stored = fixture.queryForMap("SELECT * FROM order_reservation_intent");
    assertNull(stored.get("last_error"));
    assertNull(stored.get("next_retry_at"));
  }

  @Test
  void eachDuplicateConstraintReturnsZeroWithoutChangingTheFirstFact() {
    OrderReservationIntentPO original = validIntent();
    assertEquals(1, mapper.insertIfAbsent(original));
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM order_reservation_intent");
    assertEquals(0, mapper.insertIfAbsent(original));
    for (Consumer<OrderReservationIntentPO> mutation :
        List.<Consumer<OrderReservationIntentPO>>of(
            intent -> {
              intent.id = 12L;
              intent.orderNo = "202610-second";
            },
            intent -> {
              intent.id = 12L;
              intent.idempotencyKey = "second";
            },
            intent -> {
              intent.orderNo = "202610-second";
              intent.idempotencyKey = "second";
            })) {
      OrderReservationIntentPO duplicate = validIntent();
      mutation.accept(duplicate);
      duplicate.itemsSnapshot = "[{\"skuId\":99,\"quantity\":2}]";
      duplicate.updatedAt = duplicate.updatedAt.plusSeconds(1);
      assertEquals(0, mapper.insertIfAbsent(duplicate));
      assertEquals(before, fixture.queryForMap("SELECT * FROM order_reservation_intent"));
    }
  }

  @Test
  void differentUsersCanKeepIndependentIdenticalKeys() {
    assertEquals(1, mapper.insertIfAbsent(validIntent()));
    OrderReservationIntentPO other = validIntent();
    other.id = 12L;
    other.userId = 8L;
    other.orderNo = "202610-other";
    assertEquals(1, mapper.insertIfAbsent(other));
    assertEquals(2, rowCount());
    assertEquals("202610-original", mapper.findByUserAndKey(7, "original").get(0).orderNo);
    assertEquals(other.orderNo, mapper.findByUserAndKey(8, "original").get(0).orderNo);
  }

  @Test
  void malformedJsonAndSqlFailuresCannotBecomeDuplicateSuccess() {
    OrderReservationIntentPO invalid = validIntent();
    invalid.itemsSnapshot = "{invalid";
    assertThrows(DataAccessException.class, () -> mapper.insertIfAbsent(invalid));
    assertEquals(0, rowCount());
    assertEquals(1, mapper.insertIfAbsent(validIntent()));
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM order_reservation_intent");
    assertThrows(DataAccessException.class, () -> mapper.insertIfAbsent(invalid));
    assertEquals(before, fixture.queryForMap("SELECT * FROM order_reservation_intent"));
    fixture.execute("DROP TABLE order_reservation_intent");
    assertThrows(DataAccessException.class, () -> mapper.insertIfAbsent(validIntent()));
  }

  @Test
  void nonUniqueConstraintFailureDoesNotBecomeAnExistingIntent() {
    fixture.execute(
        "ALTER TABLE order_reservation_intent ADD CONSTRAINT cm17ao_user_positive CHECK(user_id>0)");
    OrderReservationIntentPO invalid = validIntent();
    invalid.userId = -1L;
    assertThrows(DataAccessException.class, () -> mapper.insertIfAbsent(invalid));
    assertEquals(0, rowCount());
  }

  @Test
  void closedConnectionFailureDoesNotBecomeAnExistingIntent() throws Exception {
    assertEquals(1, mapper.insertIfAbsent(validIntent()));
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM order_reservation_intent");
    try (var connection = fixture.getDataSource().getConnection()) {
      connection.close();
      var closedMapper =
          new OrderReservationIntentMapper(
              new JdbcTemplate(new SingleConnectionDataSource(connection, true)));
      assertThrows(DataAccessException.class, () -> closedMapper.insertIfAbsent(validIntent()));
    }
    assertEquals(before, fixture.queryForMap("SELECT * FROM order_reservation_intent"));
  }

  @Test
  void concurrentIndependentTransactionsStillCreateOneIntent() throws Exception {
    var proxyFactory = new ProxyFactory(new OrderReservationIntentService(mapper));
    proxyFactory.setProxyTargetClass(true);
    proxyFactory.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(fixture.getDataSource()),
            new AnnotationTransactionAttributeSource()));
    var service = (OrderReservationIntentService) proxyFactory.getProxy();
    var executor = Executors.newFixedThreadPool(2);
    var ready = new CountDownLatch(2);
    var release = new CountDownLatch(1);
    OrderReservationIntentPO first = validIntent();
    OrderReservationIntentPO second = validIntent();
    second.id = 12L;
    second.orderNo = "202610-second";
    try {
      var firstResult =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return service.createIntent(first);
              });
      var secondResult =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return service.createIntent(second);
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      release.countDown();
      boolean firstWon = firstResult.get(15, TimeUnit.SECONDS);
      boolean secondWon = secondResult.get(15, TimeUnit.SECONDS);
      assertNotEquals(firstWon, secondWon);
      assertEquals(1, rowCount());
      assertEquals(
          firstWon ? first.orderNo : second.orderNo,
          mapper.findByUserAndKey(7, "original").get(0).orderNo);
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(15, TimeUnit.SECONDS));
    }
  }

  /** 使用同一真实连接切换SQL mode，每个非法输入均必须拒绝且保持零行。 */
  private void assertRejectedInBothModes(List<Consumer<OrderReservationIntentPO>> corruptions)
      throws Exception {
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderReservationIntentMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (Consumer<OrderReservationIntentPO> corruption : corruptions) {
          modeJdbc.update("DELETE FROM order_reservation_intent");
          OrderReservationIntentPO invalid = validIntent();
          corruption.accept(invalid);
          assertThrows(RuntimeException.class, () -> modeMapper.insertIfAbsent(invalid));
          assertEquals(
              0,
              modeJdbc.queryForObject(
                  "SELECT COUNT(*) FROM order_reservation_intent", Integer.class));
        }
      }
    }
  }

  /** 查询隔离库中的实际意图数量。 */
  private int rowCount() {
    return fixture.queryForObject("SELECT COUNT(*) FROM order_reservation_intent", Integer.class);
  }

  /** 构造具有原上海毫秒时间、完整身份和合法JSON的首次意图。 */
  private static OrderReservationIntentPO validIntent() {
    OrderReservationIntentPO intent = new OrderReservationIntentPO();
    intent.id = 11L;
    intent.orderNo = "202610-original";
    intent.userId = 7L;
    intent.idempotencyKey = "original";
    intent.itemsSnapshot = "[{\"skuId\":7,\"quantity\":1}]";
    intent.status = "INIT";
    intent.createdAt = LocalDateTime.parse("2026-10-08T12:00:00.123");
    intent.updatedAt = intent.createdAt;
    return intent;
  }

  /** 原库全表清单与CHECKSUM只读核验，禁止夹具污染业务数据库。 */
  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> result =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_order"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_order", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表名");
      result.addAll(source.queryForList("CHECKSUM TABLE cloudmall_order." + table));
    }
    return result;
  }

  /** 明确连接会话采用上海无时区DATETIME语义。 */
  private static String withShanghaiTimezone(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }
}
