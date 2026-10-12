package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserTerminalFrozenOwnershipEvidenceController;
import com.cloudmall.user.domain.vo.UserTerminalFrozenOwnershipEvidenceVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.UserTccReconciliationReadService;
import com.cloudmall.user.service.impl.UserTccReconciliationService;
import com.cloudmall.user.service.impl.UserTerminalFrozenOwnershipEvidenceReadService;
import com.cloudmall.user.service.impl.UserTerminalFrozenOwnershipEvidenceService;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实MyBatis/外层RR验证，不启动User应用、RM、TC、调度器或端口。 */
class UserTerminalFrozenOwnershipEvidenceMySqlIT {
  /** 原库只读指纹及自有数据库DDL管理入口。 */
  private static JdbcTemplate source;

  /** 原库所有表及校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 当前测试拥有的唯一随机库。 */
  private static String database;

  /** 直接准备和核验自有库的连接，不加入被测事务。 */
  private static JdbcTemplate fixture;

  /** 自有库基础数据源。 */
  private static DataSource dataSource;

  /** 禁止被测写入并核实际RR的连接入口。 */
  private static TrackingDataSource tracked;

  /** 真实Mapper模板。 */
  private static SqlSessionTemplate session;

  /** 被测认证门和实际RR报告服务。 */
  private static UserTerminalFrozenOwnershipEvidenceService service;

  /** 第一条接收SELECT之后的独立owned writer钩子。 */
  private static final AtomicReference<Runnable> afterReceiveRead = new AtomicReference<>();

  /** 所有被诊断观察的六张User表。 */
  private static final List<String> TABLES =
      List.of(
          "mall_user",
          "user_tcc_receive",
          "balance_debit_attempt",
          "balance_tcc_debit",
          "balance_ledger",
          "tcc_fence_log");

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?"))
      throw new IllegalStateException("需要显式cloudmall_user测试URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    sourceFingerprint = originalFingerprint();
    database = "cm16t24_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    dataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_user", "/" + database)), "root", "root");
    fixture = new JdbcTemplate(dataSource);
    fixture.execute((String) source.queryForMap("SHOW CREATE TABLE mall_user").get("Create Table"));
    tracked = new TrackingDataSource(dataSource);
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    for (Class<?> mapper :
        List.of(
            UserMapper.class,
            UserTccReceiveMapper.class,
            BalanceDebitAttemptMapper.class,
            BalanceTccDebitMapper.class,
            BalanceLedgerMapper.class,
            TccFenceLogMapper.class)) configuration.addMapper(mapper);
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(tracked);
    factory.setConfiguration(configuration);
    session = new SqlSessionTemplate(factory.getObject());
    UserTccReceiveMapper actualReceives = session.getMapper(UserTccReceiveMapper.class);
    UserTccReceiveMapper receives =
        (UserTccReceiveMapper)
            Proxy.newProxyInstance(
                UserTccReceiveMapper.class.getClassLoader(),
                new Class<?>[] {UserTccReceiveMapper.class},
                (proxy, method, arguments) -> {
                  Object result;
                  try {
                    result = method.invoke(actualReceives, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                  if ("findByPayNo".equals(method.getName())) {
                    Runnable hook = afterReceiveRead.getAndSet(null);
                    if (hook != null) hook.run();
                  }
                  return result;
                });
    var target =
        new UserTccReconciliationReadService(
            receives,
            session.getMapper(BalanceDebitAttemptMapper.class),
            session.getMapper(BalanceTccDebitMapper.class),
            session.getMapper(BalanceLedgerMapper.class),
            session.getMapper(TccFenceLogMapper.class),
            session.getMapper(UserMapper.class));
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(tracked), new AnnotationTransactionAttributeSource()));
    var baseReader = (UserTccReconciliationReadService) proxy.getProxy();
    var extendedTarget =
        new UserTerminalFrozenOwnershipEvidenceReadService(
            baseReader,
            receives,
            session.getMapper(BalanceDebitAttemptMapper.class),
            session.getMapper(BalanceTccDebitMapper.class),
            session.getMapper(BalanceLedgerMapper.class),
            session.getMapper(TccFenceLogMapper.class));
    ProxyFactory extendedProxy = new ProxyFactory(extendedTarget);
    extendedProxy.setProxyTargetClass(true);
    extendedProxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(tracked), new AnnotationTransactionAttributeSource()));
    service =
        new UserTerminalFrozenOwnershipEvidenceService(
            new UserTccReconciliationService(baseReader, true, "ops-secret"),
            (UserTerminalFrozenOwnershipEvidenceReadService) extendedProxy.getProxy(),
            true);
    ensureFixtureTables();
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
    afterReceiveRead.set(null);
    ensureFixtureTables();
    for (String table : TABLES) fixture.update("DELETE FROM " + table);
    tracked.reset();
  }

  @AfterAll
  static void cleanup() {
    if (tracked != null) tracked.checking = false;
    try {
      if (source != null) assertEquals(sourceFingerprint, originalFingerprint());
      System.out.println("CM16T24 original User tables/checksums unchanged");
    } finally {
      if (source != null && database != null && database.matches("cm16t24_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
        System.out.println("CM16T24 fixture dropped: " + database);
      }
    }
  }

  @Test
  void mixedTerminalRowsAndExpiredFenceHaveOnlyBoundedOwnership() {
    for (int peers : List.of(0, 1, 2)) {
      seed("TRYING");
      if (peers >= 1) peer(2, 7L, "10.00", "CONFIRMED", true);
      if (peers == 2) peer(3, 7L, "10.00", "CANCELLED", true);
      fixture.update("DELETE FROM tcc_fence_log WHERE branch_id<>11");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertTrue(report.candidatesComplete);
      assertEquals(peers, report.observedCount);
      assertEquals(peers == 0 ? 0 : 1, report.validatedConfirmedCount);
      assertEquals(peers == 2 ? 1 : 0, report.validatedCancelledCount);
      assertTrue(report.accountCausalityNotProven);
      assertFalse(report.automaticActionsAllowed);
      assertEquals(1, tracked.connections);
      assertEquals(12 + 7 * peers + (peers == 2 ? 1 : 0), tracked.statements.size());
      assertEquals(before, facts());
    }
  }

  @Test
  void missingOriginAndWrongPersistedSourcesRemainBlocked() {
    for (String mutation :
        List.of(
            "receive",
            "attempt",
            "mode",
            "branch",
            "xid",
            "fence",
            "ledger",
            "snapshot",
            "cancelDebit")) {
      seed("TRYING");
      peer(2, 7L, "10.00", "cancelDebit".equals(mutation) ? "CANCELLED" : "CONFIRMED", true);
      switch (mutation) {
        case "receive" -> fixture.update("DELETE FROM user_tcc_receive WHERE id=2");
        case "attempt" -> fixture.update("DELETE FROM balance_debit_attempt WHERE id=2");
        case "mode" -> fixture.update("UPDATE balance_debit_attempt SET mode='LEGACY' WHERE id=2");
        case "branch" -> fixture.update("UPDATE balance_debit_attempt SET branch_id=99 WHERE id=2");
        case "xid" -> fixture.update("UPDATE user_tcc_receive SET xid='wrong' WHERE id=2");
        case "fence" -> fixture.update("UPDATE tcc_fence_log SET status=3 WHERE branch_id=12");
        case "ledger" -> fixture.update("DELETE FROM balance_ledger WHERE id=2");
        case "snapshot" ->
            fixture.update("UPDATE balance_tcc_debit SET balance_after_try=99 WHERE id=2");
        case "cancelDebit" ->
            fixture.update(
                "INSERT INTO"
                    + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                    + " VALUES(100001,7,'legacy:pay2','DEBIT','pay2','202610order2',10,'REJECTED',100,90,'2026-10-11')");
        default -> throw new AssertionError(mutation);
      }
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals(
          "receive".equals(mutation) ? "BLOCKED_LOCAL_EVIDENCE" : "LOCAL_CONFLICT",
          report.classification,
          mutation);
      assertFalse(report.candidatesComplete);
      assertNull(report.vectorSha256);
      assertNull(report.validatedConfirmedCount);
      assertNull(report.validatedCancelledCount);
      assertEquals(before, facts());
    }
  }

  @Test
  void sourceGapsAliasesAndOtherUserRowsNeverBecomeCompleteHistory() {
    seed("CONFIRMED");
    peer(2, 7L, "10.00", "CONFIRMED", true);
    seedRefund("5.00");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    var gap = inspect();
    assertEquals("BLOCKED_LOCAL_EVIDENCE", gap.classification);
    assertNull(gap.vectorSha256);
    assertEquals(before, facts());
    for (String alias : List.of("confirmed", "CONFÍRMED", "CONFIRMED ")) {
      seed("TRYING");
      peer(2, 7L, "10.00", "CONFIRMED", true);
      fixture.update("UPDATE balance_tcc_debit SET state=? WHERE id=2", alias);
      assertEquals(
          1,
          session
              .getMapper(BalanceTccDebitMapper.class)
              .findTerminalOwnershipCandidates(7L)
              .size());
      before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals("LOCAL_CONFLICT", report.classification);
      assertFalse(report.queryAttempted);
      assertNull(report.vectorSha256);
      assertEquals(before, facts());
    }
    seed("TRYING");
    peer(2, 8L, "10.00", "CONFIRMED", false);
    tracked.reset();
    tracked.checking = true;
    assertEquals(0, inspect().observedCount);
  }

  @Test
  void hundredAndSentinelAreRealSqlBoundsWithNoTruncatedPeerQueries() {
    for (int count : List.of(100, 101)) {
      seed("TRYING");
      for (int index = 0; index < count; index++)
        peer(index + 2, 7L, "0.01", "CANCELLED", count == 100);
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals(count, report.observedCount);
      assertEquals(1, tracked.connections);
      if (count == 100) {
        assertTrue(report.candidatesComplete);
        assertEquals(100, report.validatedCancelledCount);
        assertEquals(812, tracked.statements.size());
      } else {
        assertFalse(report.candidatesComplete);
        assertNull(report.vectorSha256);
        assertNull(report.validatedCancelledCount);
        assertEquals(12, tracked.statements.size());
      }
      assertEquals(before, facts());
    }
  }

  @Test
  void independentWriterCannotMixNewTerminalIntoCurrentSnapshot() {
    seed("TRYING");
    peer(2, 7L, "10.00", "CONFIRMED", true);
    afterReceiveRead.set(
        () ->
            new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> peer(3, 7L, "10.00", "CANCELLED", true)));
    tracked.reset();
    tracked.checking = true;
    var first = inspect();
    assertEquals(1, first.observedCount);
    assertEquals(19, tracked.statements.size());
    assertEquals(1, tracked.connections);
    tracked.reset();
    var next = inspect();
    assertEquals(2, next.observedCount);
    assertEquals(27, tracked.statements.size());
    assertEquals(1, tracked.connections);
    assertNotEquals(first.vectorSha256, next.vectorSha256);
  }

  @Test
  void httpNoStoreIdentityIsolationAndMissingTablesStayUnavailable() throws Exception {
    seed("TRYING");
    peer(2, 7L, "10.00", "CONFIRMED", true);
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserTerminalFrozenOwnershipEvidenceController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route =
        "/api/internal/users/7/balance/tcc-reconciliation/pay1/terminal-frozen-ownership-evidence";
    mvc.perform(get(route))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    mvc.perform(get(route.replace("/7/", "/abc/")))
        .andExpect(status().isBadRequest())
        .andExpect(header().string("Cache-Control", "no-store"));
    assertEquals(0, tracked.connections);
    String json =
        mvc.perform(
                get(route)
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret")
                    .param("xid", "wrong-client-xid")
                    .param("operationKey", "wrong-key"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                jsonPath("$.data.scope").value("USER_TERMINAL_FROZEN_OWNERSHIP_OBSERVED_ONLY"))
            .andExpect(jsonPath("$.data.validatedConfirmedCount").value(1))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String secret :
        List.of(
            "pay2",
            "peer-xid-2",
            "tcc-debit:pay2",
            "private-password",
            "private-phone",
            "private-tc:8091:original",
            "ops-secret",
            "wrong-client-xid",
            "wrong-key",
            "validatedFrozenTotal")) assertFalse(json.contains(secret), secret);
    assertEquals(before, facts());
    tracked.checking = false;
    fixture.execute("DROP TABLE user_tcc_receive");
    tracked.reset();
    tracked.checking = true;
    var error = assertThrows(BizException.class, this::inspect);
    assertEquals(503, error.getHttpStatus());
    assertEquals("USER_TERMINAL_FROZEN_OWNERSHIP_EVIDENCE_UNAVAILABLE", error.getCode());
  }

  /** owned其他支付的原终态/frozen delta及可选完整receive/attempt/Fence证据。 */
  private static void peer(
      int identifier, Long userId, String amount, String state, boolean complete) {
    String payNo = "pay" + identifier;
    String orderNo = "202610order" + identifier;
    String xid = "peer-xid-" + identifier;
    long branchId = identifier + 10L;
    fixture.update(
        "INSERT INTO"
            + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,100,100-?,'2026-10-11','2026-10-11')",
        identifier,
        "tcc-debit:" + payNo,
        xid,
        branchId,
        userId,
        payNo,
        orderNo,
        amount,
        state,
        amount);
    if (!complete) return;
    fixture.update(
        "INSERT INTO"
            + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,'CLAIMED','2026-10-11','2026-10-11')",
        identifier,
        payNo,
        xid,
        branchId,
        userId,
        orderNo,
        amount,
        "tcc-debit:" + payNo,
        payNo);
    fixture.update(
        "INSERT INTO"
            + " balance_debit_attempt(id,pay_no,operation_key,mode,xid,ledger_key,branch_id,user_id,order_no,amount,state,created_at,updated_at)"
            + " VALUES(?,?,?,'TCC',?,?,?,?,?,?,?,'2026-10-11','2026-10-11')",
        identifier,
        payNo,
        "tcc-debit:" + payNo,
        xid,
        payNo,
        branchId,
        userId,
        orderNo,
        amount,
        state);
    fixture.update(
        "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
            + " VALUES(?,?,?,?,'2026-10-11','2026-10-11')",
        xid,
        branchId,
        BalanceTccDebitLocalResource.ACTION_NAME,
        "CONFIRMED".equals(state) ? 2 : 3);
    if ("CONFIRMED".equals(state))
      fixture.update(
          "INSERT INTO"
              + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
              + " VALUES(?,?,?,'DEBIT',?,?,?,'APPLIED',100,100-?,'2026-10-11')",
          identifier,
          userId,
          payNo,
          payNo,
          orderNo,
          amount,
          amount);
  }

  /** 认证通过的实际诊断请求。 */
  private UserTerminalFrozenOwnershipEvidenceVO inspect() {
    return service.inspect(7L, "pay1", "cm18-reconciliation", "ops-secret");
  }

  /** 只准备owned schema，绝不在被测请求中执行。 */
  private static void ensureFixtureTables() {
    session.getMapper(UserTccReceiveMapper.class).ensureTable();
    session.getMapper(BalanceDebitAttemptMapper.class).ensureTable();
    session.getMapper(BalanceTccDebitMapper.class).ensureTable();
    session.getMapper(BalanceLedgerMapper.class).ensureTable();
    session.getMapper(TccFenceLogMapper.class).ensureTable();
  }

  /** 准备原接收/资金事实，不注册真实RM或写原业务库。 */
  private static void seed(String state) {
    tracked.checking = false;
    for (String table : TABLES) fixture.update("DELETE FROM " + table);
    fixture.update(
        "INSERT INTO"
            + " mall_user(id,username,password_hash,role,phone,balance,frozen_balance,status,created_at,updated_at)"
            + " VALUES(7,'owned-user','private-password','private-role','private-phone',?,"
            + " ?,1,'2026-10-11','2026-10-11')",
        "CANCELLED".equals(state) || "UNKNOWN".equals(state) ? "100.00" : "90.00",
        "TRYING".equals(state) ? "10.00" : "0.00");
    fixture.update(
        "INSERT INTO"
            + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
            + " VALUES(1,'pay1','private-tc:8091:original',11,7,'202610order1',10,'tcc-debit:pay1','pay1','CLAIMED','2026-10-11','2026-10-11')");
    if ("UNKNOWN".equals(state)) return;
    fixture.update(
        "INSERT INTO"
            + " balance_debit_attempt(id,pay_no,operation_key,mode,xid,ledger_key,branch_id,user_id,order_no,amount,state,created_at,updated_at)"
            + " VALUES(1,'pay1','tcc-debit:pay1','TCC','private-tc:8091:original','pay1',11,7,'202610order1',10,?,'2026-10-11','2026-10-11')",
        state);
    fixture.update(
        "INSERT INTO"
            + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
            + " VALUES(1,'tcc-debit:pay1','private-tc:8091:original',11,7,'pay1','202610order1',10,?,100,90,'2026-10-11','2026-10-11')",
        state);
    fixture.update(
        "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
            + " VALUES('private-tc:8091:original',11,?,?,'2026-10-11','2026-10-11')",
        BalanceTccDebitLocalResource.ACTION_NAME,
        "TRYING".equals(state) ? 1 : "CONFIRMED".equals(state) ? 2 : 3);
    if ("CONFIRMED".equals(state))
      fixture.update(
          "INSERT INTO"
              + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
              + " VALUES(1,7,'pay1','DEBIT','pay1','202610order1',10,'APPLIED',100,90,'2026-10-11')");
  }

  /** 固定退款事实只写当前owned库。 */
  private static void seedRefund(String amount) {
    fixture.update(
        "INSERT INTO"
            + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
            + " VALUES(100001,7,'refund:pay1','REFUND','pay1','pay1','202610order1',?,'APPLIED',100,100+?,'2026-10-11')",
        amount,
        amount);
  }

  /** 全部原字段事实，包含资金和时间，检查读取无副作用。 */
  private static List<List<Map<String, Object>>> facts() {
    return TABLES.stream().map(table -> fixture.queryForList("SELECT * FROM " + table)).toList();
  }

  /** 原库全表清单及只读checksum。 */
  private static List<Map<String, Object>> originalFingerprint() {
    List<Map<String, Object>> values =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_user"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_user", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表");
      values.addAll(source.queryForList("CHECKSUM TABLE cloudmall_user." + table));
    }
    return values;
  }

  /** 项目固定上海时间语义，不改全局设置。 */
  private static String shanghai(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }

  /** 每个被测SELECT必须在真实只读RR事务连接中；不依赖mock事务注解。 */
  private static final class TrackingDataSource extends AbstractDataSource {
    /** owned库基础连接来源。 */
    final DataSource delegate;

    /** 仅被测请求阶段启用写入/事务断言。 */
    boolean checking;

    /** 被测请求取得的实际连接数。 */
    int connections;

    /** 本次请求实际准备的SQL。 */
    final List<String> statements = new ArrayList<>();

    TrackingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    void reset() {
      connections = 0;
      statements.clear();
    }

    /** 为每条被测SQL验证实际只读RR连接。 */
    @Override
    public Connection getConnection() throws java.sql.SQLException {
      Connection connection = delegate.getConnection();
      if (checking) connections++;
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                if (checking && "prepareStatement".equals(method.getName())) {
                  String sql = ((String) arguments[0]).stripLeading().toUpperCase(Locale.ROOT);
                  assertTrue(sql.startsWith("SELECT"), "non-read SQL: " + sql);
                  assertFalse(sql.contains("FOR UPDATE"));
                  assertTrue(connection.isReadOnly());
                  assertEquals(
                      Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                  statements.add(sql);
                }
                try {
                  return method.invoke(connection, arguments);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    /** 本夹具固定数据源，使用同一受监控连接入口。 */
    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
      return getConnection();
    }
  }
}
