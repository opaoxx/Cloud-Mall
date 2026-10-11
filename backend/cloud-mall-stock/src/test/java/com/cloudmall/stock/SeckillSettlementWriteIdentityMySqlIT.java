package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.stock.controller.StockController;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.function.Executable;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实owned事务验证写前精确身份，实际INSERT必须随异常回滚，不接RM/TC/Redis或业务端口。 */
class SeckillSettlementWriteIdentityMySqlIT {
  /** 原Stock数据库只读及owned DDL管理入口。 */
  private static JdbcTemplate source;

  /** 源库全表清单与checksum。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 本测试唯一自有schema。 */
  private static String database;

  /** 自有库直连准备/核验入口，不加入被测事务。 */
  private static JdbcTemplate fixture;

  /** 真事务SQL监测入口。 */
  private static TransactionDataSource tracked;

  /** 实际MyBatis结算Mapper。 */
  private static SeckillQuotaSettlementMapper settlements;

  /** 同一数据源上的原普通/legacy SQL入口。 */
  private static StockSqlMapper stocks;

  /** 真实Spring REQUIRED事务的结算服务代理。 */
  private static SeckillQuotaSettlementService service;

  /** 内外层共享事务管理器，禁止假代理只验证注解。 */
  private static DataSourceTransactionManager transactions;

  /** 验证外层没有Redis副作用的替身。 */
  private static StringRedisTemplate redis;

  /** 验证外层没有消息副作用的替身。 */
  private static RabbitTemplate rabbit;

  /** 当前动作的原请求订单号。 */
  private static final String ORDER_NO = "202610proof1";

  /** 全部被核验事实表，包含普通库存。 */
  private static final List<String> TABLES =
      List.of(
          "seckill_quota_settlement",
          "seckill_reservation",
          "stock_flow",
          "stock_reservation_request",
          "stock_sku",
          "stock_cache_repair");

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_stock(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_stock URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    sourceFingerprint = fingerprint();
    database = "cm16t32_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    DataSource ownDataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_stock", "/" + database)), "root", "root");
    fixture = new JdbcTemplate(ownDataSource);
    for (String table : TABLES)
      if (!"seckill_quota_settlement".equals(table))
        fixture.execute("CREATE TABLE " + table + " LIKE cloudmall_stock." + table);
    tracked = new TransactionDataSource(ownDataSource);
    stocks = new StockSqlMapper(new JdbcTemplate(tracked));
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.addMapper(SeckillQuotaSettlementMapper.class);
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(tracked);
    factory.setConfiguration(configuration);
    settlements =
        new SqlSessionTemplate(factory.getObject()).getMapper(SeckillQuotaSettlementMapper.class);
    settlements.ensureTable();
    transactions = new DataSourceTransactionManager(tracked);
    service = transactional(new SeckillQuotaSettlementService(settlements, stocks));
    redis = mock(StringRedisTemplate.class);
    rabbit = mock(RabbitTemplate.class);
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
    tracked.sql.clear();
    for (String table : TABLES) fixture.update("DELETE FROM " + table);
    clearInvocations(redis, rabbit);
  }

  @AfterAll
  static void cleanup() {
    if (tracked != null) tracked.checking = false;
    try {
      if (source != null) assertEquals(sourceFingerprint, fingerprint());
    } finally {
      if (database != null && database.matches("cm16t32_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
      }
    }
    System.out.println(
        "CM16T32 original Stock checksums unchanged; owned transaction database removed");
  }

  @Test
  void lockedAliasesCannotMutateOrAcknowledgeOriginalSettlement() {
    List<Executable> assertions = new ArrayList<>();
    for (String original : List.of("202610PROOF1", "202610proof1 ", "202610próof1"))
      for (String state : List.of("OPEN", "CONFIRMED", "RELEASED"))
        for (String action : List.of("CONFIRM", "ROLLBACK"))
          assertions.add(
              () -> {
                reset();
                seedSettlement(original, state);
                assertEquals(original, settlements.findSettlement(ORDER_NO).orderNo);
                var before = facts();
                tracked.sql.clear();
                tracked.checking = true;
                assertConflict(() -> service.settle(ORDER_NO, action));
                assertEquals(before, facts());
                assertOriginalInsertThenLockOnly();
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              });
    assertAll(assertions);
  }

  @Test
  void acceptedAliasTemporaryOpenMustRollbackEntireTransaction() {
    List<Executable> assertions = new ArrayList<>();
    for (String original : List.of("202610PROOF1", "202610proof1 ", "202610próof1"))
      for (String action : List.of("CONFIRM", "ROLLBACK"))
        assertions.add(
            () -> {
              reset();
              seedAccepted(original);
              assertEquals(original, stocks.findSeckillByOrderNo(ORDER_NO).get("order_no"));
              var before = facts();
              tracked.sql.clear();
              tracked.checking = true;
              assertRejectedTransaction(action, before);
              assertEquals(
                  0,
                  fixture.queryForObject(
                      "SELECT COUNT(*) FROM seckill_quota_settlement", Integer.class));
              assertEquals(
                  "ACCEPTED",
                  fixture.queryForObject("SELECT status FROM seckill_reservation", String.class));
            });
    assertAll(assertions);
  }

  @Test
  void exactCreationCasReplayAndOppositeActionPreserveProtocol() {
    for (String action : List.of("CONFIRM", "ROLLBACK")) {
      reset();
      seedAccepted(ORDER_NO);
      var unaffected = normalAndAcceptanceFacts();
      tracked.checking = true;
      Map<?, ?> response = (Map<?, ?>) service.settle(ORDER_NO, action).data;
      assertEquals(
          "CONFIRM".equals(action) ? Boolean.TRUE : Boolean.FALSE,
          response.get("CONFIRM".equals(action) ? "confirmed" : "rolledBack"));
      assertEquals(3, tracked.sql.size());
      Map<String, Object> settlement =
          fixture.queryForMap("SELECT * FROM seckill_quota_settlement");
      assertEquals(ORDER_NO, settlement.get("order_no"));
      assertEquals(
          "CONFIRM".equals(action) ? "CONFIRMED" : "RELEASE_PENDING", settlement.get("status"));
      assertEquals(2L, ((Number) settlement.get("revision")).longValue());
      assertEquals(ORDER_NO + ":" + action, settlement.get("operation_key"));
      assertEquals(unaffected, normalAndAcceptanceFacts());
      var beforeReplay = facts();
      tracked.sql.clear();
      service.settle(ORDER_NO, action);
      assertEquals(beforeReplay, facts());
      assertEquals(3, tracked.sql.size());
      tracked.sql.clear();
      assertConflict(
          () -> service.settle(ORDER_NO, "CONFIRM".equals(action) ? "ROLLBACK" : "CONFIRM"));
      assertEquals(beforeReplay, facts());
      assertOriginalInsertThenLockOnly();
    }
    reset();
    seedSettlement(ORDER_NO, "RELEASED");
    var released = facts();
    tracked.checking = true;
    assertEquals(true, ((Map<?, ?>) service.settle(ORDER_NO, "ROLLBACK").data).get("idempotent"));
    assertEquals(released, facts());
    assertEquals(3, tracked.sql.size());
    reset();
    var missing = facts();
    tracked.checking = true;
    assertNull(service.settle(ORDER_NO, "CONFIRM"));
    assertEquals(missing, facts());
    assertOriginalInsertThenLockOnly();
  }

  @Test
  void actualControllerConflictStopsNormalStockAndRollsBackInsert() throws Exception {
    for (String action : List.of("CONFIRM", "ROLLBACK")) {
      reset();
      seedAccepted("202610PROOF1");
      var before = facts();
      StockController controller = new StockController();
      ReflectionTestUtils.setField(controller, "stockSqlMapper", stocks);
      ReflectionTestUtils.setField(controller, "seckillSettlementService", service);
      ReflectionTestUtils.setField(controller, "redis", redis);
      ReflectionTestUtils.setField(controller, "rabbit", rabbit);
      ReflectionTestUtils.setField(controller, "mapper", new ObjectMapper());
      StockController outer = transactional(controller);
      var mvc =
          MockMvcBuilders.standaloneSetup(outer)
              .setControllerAdvice(new GlobalExceptionHandler())
              .build();
      tracked.checking = true;
      String body =
          mvc.perform(
                  post(
                      "/api/stock/reservations/"
                          + ORDER_NO
                          + "/"
                          + ("CONFIRM".equals(action) ? "confirm" : "rollback")))
              .andExpect(status().isConflict())
              .andExpect(jsonPath("$.code").value("SECKILL_SETTLEMENT_CONFLICT"))
              .andReturn()
              .getResponse()
              .getContentAsString();
      assertFalse(body.contains("202610PROOF1"));
      assertEquals(before, facts());
      assertOriginalInsertThenLockOnly();
      verifyNoInteractions(redis, rabbit);
    }
  }

  /** 实际CGLIB事务代理；内外层共用同一数据源/事务管理器。 */
  @SuppressWarnings("unchecked")
  private static <Target> Target transactional(Target target) {
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(transactions, new AnnotationTransactionAttributeSource()));
    return (Target) proxy.getProxy();
  }

  /** 同时验证冲突与全行回滚，旧实现即使误返回成功也必须记录其真实持久化变化。 */
  private static void assertRejectedTransaction(
      String action, List<List<Map<String, Object>>> before) {
    BizException failure = null;
    try {
      service.settle(ORDER_NO, action);
    } catch (BizException rejected) {
      failure = rejected;
    }
    BizException captured = failure;
    var after = facts();
    assertAll(
        () -> {
          assertNotNull(captured, "错误身份不得被确认或作为幂等成功");
          assertEquals(409, captured.getHttpStatus());
          assertEquals("SECKILL_SETTLEMENT_CONFLICT", captured.getCode());
        },
        () -> assertEquals(before, after, "错误身份事务必须保持全部原事实不变，包括临时OPEN插入"),
        SeckillSettlementWriteIdentityMySqlIT::assertOriginalInsertThenLockOnly);
  }

  /** 只接受既有冲突码/HTTP，不接受CAS失败或正常状态响应替代身份拒绝。 */
  private static void assertConflict(Executable request) {
    var failure = assertThrows(BizException.class, request);
    assertEquals(409, failure.getHttpStatus());
    assertEquals("SECKILL_SETTLEMENT_CONFLICT", failure.getCode());
  }

  /** 负例只能走原INSERT与锁定SELECT，不增加CAS或普通库存写。 */
  private static void assertOriginalInsertThenLockOnly() {
    assertEquals(2, tracked.sql.size());
    assertTrue(tracked.sql.get(0).startsWith("INSERT IGNORE INTO SECKILL_QUOTA_SETTLEMENT"));
    assertTrue(tracked.sql.get(1).startsWith("SELECT * FROM SECKILL_QUOTA_SETTLEMENT"));
    assertTrue(tracked.sql.get(1).contains("FOR UPDATE"));
  }

  /** 仅owned准备合法单行原结算，不调用被测写API。 */
  private static void seedSettlement(String original, String state) {
    fixture.update(
        "INSERT INTO"
            + " seckill_quota_settlement(id,order_no,activity_id,sku_id,user_id,idempotency_key,quantity,status,revision,operation_key,redis_revision,created_at,updated_at)"
            + " VALUES(1,?,10,20,8,'private-idem',1,?,2,?,?,'2026-10-11','2026-10-11')",
        original,
        state,
        "OPEN".equals(state)
            ? null
            : original + ("RELEASED".equals(state) ? ":ROLLBACK" : ":CONFIRM"),
        "RELEASED".equals(state) ? 4L : null);
    seedAccepted(original);
  }

  /** 原ACCEPTED事实，仅允许旧mapper的INSERT SELECT创建对应OPEN。 */
  private static void seedAccepted(String original) {
    fixture.update(
        "INSERT INTO"
            + " seckill_reservation(id,activity_id,sku_id,user_id,order_no,idempotency_key,status,created_at,updated_at)"
            + " VALUES(1,10,20,8,?,'private-idem','ACCEPTED','2026-10-11','2026-10-11')",
        original);
  }

  /** 全行快照验证回滚和稳定终态，包含时间/租约字段。 */
  private static List<List<Map<String, Object>>> facts() {
    return TABLES.stream().map(table -> fixture.queryForList("SELECT * FROM " + table)).toList();
  }

  /** 合法CAS仅可改settlement，其它原受理/普通库存事实不动。 */
  private static List<List<Map<String, Object>>> normalAndAcceptanceFacts() {
    return TABLES.stream()
        .filter(table -> !"seckill_quota_settlement".equals(table))
        .map(table -> fixture.queryForList("SELECT * FROM " + table))
        .toList();
  }

  /** 原库全表清单/checksum，禁止DDL或写入。 */
  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> values =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_stock"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_stock", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表");
      values.addAll(source.queryForList("CHECKSUM TABLE cloudmall_stock." + table));
    }
    return values;
  }

  /** 上海DATETIME语义仅作用于本连接。 */
  private static String shanghai(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }

  /** 被测写SQL必须在真实Spring事务且autocommit=false；不容许普通库存写或无事务提交。 */
  private static final class TransactionDataSource extends AbstractDataSource {
    /** owned基础数据源。 */
    final DataSource delegate;

    /** 仅被测写阶段启用。 */
    boolean checking;

    /** 当前请求prepared SQL顺序。 */
    final List<String> sql = new ArrayList<>();

    TransactionDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    /** 在每个被测SQL之前核验真实事务及连接提交模式。 */
    @Override
    public Connection getConnection() throws java.sql.SQLException {
      Connection connection = delegate.getConnection();
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                if (checking && "prepareStatement".equals(method.getName())) {
                  String statement =
                      ((String) arguments[0]).stripLeading().toUpperCase(Locale.ROOT);
                  assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  assertFalse(connection.getAutoCommit());
                  assertTrue(
                      statement.startsWith("INSERT IGNORE INTO SECKILL_QUOTA_SETTLEMENT")
                          || statement.startsWith("SELECT * FROM SECKILL_QUOTA_SETTLEMENT")
                          || statement.startsWith("UPDATE SECKILL_QUOTA_SETTLEMENT")
                          || statement.startsWith("SELECT ACCEPTED.ID FROM SECKILL_RESERVATION")
                              && !statement.contains("FOR UPDATE"),
                      statement);
                  sql.add(statement);
                }
                try {
                  return method.invoke(connection, arguments);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    /** 固定owned凭证仍走同一监测入口，不绕过事务校验。 */
    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
      return getConnection();
    }
  }
}
