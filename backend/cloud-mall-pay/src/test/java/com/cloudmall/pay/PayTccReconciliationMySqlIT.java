package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.controller.PayTccReconciliationController;
import com.cloudmall.pay.domain.vo.PayTccReconciliationVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.evidence.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.evidence.PayTccReconciliationService;
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

/** 最小MyBatis/RR代理验证六表只读取证，不启动Pay应用、TC、Web端口或调度器。 */
class PayTccReconciliationMySqlIT {
  /** 原库只读管理连接。 */
  private static JdbcTemplate source;

  /** 原库表清单和校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 仅本测试创建的数据库。 */
  private static String database;

  /** owned库直接准备/核验入口，不与被测事务混用。 */
  private static JdbcTemplate fixture;

  /** owned库基础数据源。 */
  private static DataSource dataSource;

  /** 被测只读事务数据源，记录连接/SQL并拒绝写语句。 */
  private static TrackingDataSource tracked;

  /** 真实MyBatis模板。 */
  private static SqlSessionTemplate session;

  /** 默认门已启用的测试入口。 */
  private static PayTccReconciliationService service;

  /** 真实RR代理。 */
  private static PayTccReconciliationReadService reader;

  /** 第一条支付SELECT返回之后执行的独立测试写入，仅用于RR竞争验证。 */
  private static final AtomicReference<Runnable> afterPaymentRead = new AtomicReference<>();

  /** Inspector涉及的六张表。 */
  private static final List<String> TABLES =
      List.of(
          "pay_record",
          "pay_tcc_execution",
          "pay_tcc_dispatch",
          "pay_tcc_receipt",
          "tcc_fence_log",
          "pay_debit_intent");

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_pay URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    sourceFingerprint = originalFingerprint();
    database = "cm16t1_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    dataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_pay", "/" + database)), "root", "root");
    fixture = new JdbcTemplate(dataSource);
    for (String table : List.of("pay_record", "pay_debit_intent"))
      fixture.execute(
          (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
    tracked = new TrackingDataSource(dataSource);
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    for (Class<?> mapper :
        List.of(
            PayRecordMapper.class,
            PayTccExecutionMapper.class,
            PayTccDispatchMapper.class,
            PayTccReceiptMapper.class,
            PayTccFenceMapper.class)) configuration.addMapper(mapper);
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(tracked);
    factory.setConfiguration(configuration);
    session = new SqlSessionTemplate(factory.getObject());
    PayRecordMapper actualPayments = session.getMapper(PayRecordMapper.class);
    PayRecordMapper payments =
        (PayRecordMapper)
            Proxy.newProxyInstance(
                PayRecordMapper.class.getClassLoader(),
                new Class<?>[] {PayRecordMapper.class},
                (proxy, method, arguments) -> {
                  Object result;
                  try {
                    result = method.invoke(actualPayments, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                  if ("findReconciliationCandidates".equals(method.getName())) {
                    Runnable hook = afterPaymentRead.getAndSet(null);
                    if (hook != null) hook.run();
                  }
                  return result;
                });
    var target =
        new PayTccReconciliationReadService(
            payments,
            session.getMapper(PayTccExecutionMapper.class),
            session.getMapper(PayTccDispatchMapper.class),
            session.getMapper(PayTccReceiptMapper.class),
            session.getMapper(PayTccFenceMapper.class),
            new PayDebitIntentMapper(new JdbcTemplate(tracked)));
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(tracked), new AnnotationTransactionAttributeSource()));
    reader = (PayTccReconciliationReadService) proxy.getProxy();
    service = new PayTccReconciliationService(reader, true, "ops-secret");
    ensureFixtureTables();
    fixture.execute(
        "ALTER TABLE pay_tcc_execution ADD recovery_attempts INT DEFAULT 0,ADD next_recovery_at"
            + " DATETIME(3),ADD recovery_owner VARCHAR(128),ADD recovery_lease_until"
            + " DATETIME(3),ADD recovery_last_error VARCHAR(512)");
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
    afterPaymentRead.set(null);
    ensureFixtureTables();
    for (String table : TABLES) fixture.update("DELETE FROM " + table);
    tracked.reset();
  }

  @AfterAll
  static void cleanup() {
    if (tracked != null) tracked.checking = false;
    if (source != null) assertEquals(sourceFingerprint, originalFingerprint());
    if (database != null && database.matches("cm16t1_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM16T1 fixture dropped: " + database);
    }
    System.out.println("CM16T1 original Pay tables and checksums unchanged");
  }

  @Test
  void committedAndCancelledLocalSnapshotsNeverAuthorizeFundsOrMutateAnyRow() {
    for (String state : List.of("COMMITTED", "ROLLEDBACK")) {
      seed(state);
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
      assertFalse(report.automaticActionsAllowed);
      assertEquals(1, tracked.connections);
      assertEquals(6, tracked.statements.size());
      assertTrue(
          tracked.statements.stream()
              .allMatch(sql -> sql.startsWith("SELECT") && !sql.contains("FOR UPDATE")));
      assertEquals(before, facts());
      tracked.checking = false;
    }
  }

  @Test
  void missingXidAndEachIncompleteLocalAnchorStayVisibleWithoutRecoveryWrites() {
    for (String variant : List.of("begin", "execution", "dispatch", "receipt", "fence")) {
      seed("COMMITTED");
      if ("begin".equals(variant)) {
        fixture.update(
            "UPDATE pay_tcc_execution SET xid=NULL,state='BEGIN_UNKNOWN',decision='NONE'");
        fixture.update("DELETE FROM pay_tcc_dispatch");
        fixture.update("DELETE FROM pay_tcc_receipt");
        fixture.update("DELETE FROM tcc_fence_log");
      } else
        fixture.update(
            "DELETE FROM " + ("fence".equals(variant) ? "tcc_fence_log" : "pay_tcc_" + variant));
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals(
          "begin".equals(variant)
              ? "BLOCKED_BEGIN_UNKNOWN"
              : "execution".equals(variant) ? "EXECUTION_MISSING" : "BLOCKED_LOCAL_EVIDENCE",
          report.classification,
          variant);
      assertFalse(report.reasonCodes.isEmpty());
      assertFalse(report.automaticActionsAllowed);
      assertEquals(before, facts());
      tracked.checking = false;
    }
  }

  @Test
  void legacyAndMixedProtocolsAreNeverRetaggedOrSettled() {
    seed("COMMITTED");
    fixture.update("UPDATE pay_record SET debit_mode='LEGACY'");
    var before = facts();
    tracked.checking = true;
    assertEquals("LOCAL_CONFLICT", inspect().classification);
    assertEquals(before, facts());
    tracked.checking = false;
    for (String table :
        List.of("pay_tcc_execution", "pay_tcc_dispatch", "pay_tcc_receipt", "tcc_fence_log"))
      fixture.update("DELETE FROM " + table);
    before = facts();
    tracked.checking = true;
    assertEquals("LEGACY_NOT_INSPECTED", inspect().classification);
    assertEquals(before, facts());
  }

  @Test
  void gatesAndCollationAliasesCannotOpenOrReadAnotherPayment() {
    seed("COMMITTED");
    tracked.checking = true;
    assertEquals(
        503,
        assertThrows(
                BizException.class,
                () ->
                    new PayTccReconciliationService(reader, false, "ops-secret")
                        .inspect("pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    assertEquals(
        403,
        assertThrows(
                BizException.class, () -> service.inspect("pay1", "cm18-reconciliation", "wrong"))
            .getHttpStatus());
    assertEquals(
        400,
        assertThrows(
                BizException.class,
                () -> service.inspect("pay1\n", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    assertEquals(0, tracked.connections);
    assertTrue(tracked.statements.isEmpty());
    assertEquals(
        409,
        assertThrows(
                BizException.class,
                () -> service.inspect("PAY1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    assertEquals(1, tracked.statements.size());
  }

  @Test
  void httpReportIsNoStoreAndContainsNoRawXidKeyOrErrorText() throws Exception {
    seed("COMMITTED");
    var before = facts();
    tracked.checking = true;
    var mvc =
        MockMvcBuilders.standaloneSetup(new PayTccReconciliationController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String body =
        mvc.perform(
                get("/api/internal/payments/pay1/tcc-reconciliation")
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.scope").value("PAY_LOCAL_ONLY"))
            .andExpect(jsonPath("$.data.automaticActionsAllowed").value(false))
            .andExpect(jsonPath("$.data.snapshot.amount").value("10.00"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    assertFalse(body.contains("tc-private:8091:original"));
    assertFalse(body.contains("tcc-debit:pay1"));
    assertFalse(body.contains("private-error-password"));
    assertEquals(before, facts());
  }

  @Test
  void absentPaymentAndMissingTableAreDifferentFailuresWithoutSchemaRepair() {
    seed("COMMITTED");
    tracked.checking = true;
    assertEquals(
        404,
        assertThrows(
                BizException.class,
                () -> service.inspect("absent", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    assertEquals(1, tracked.statements.size());
    tracked.checking = false;
    fixture.execute("DROP TABLE pay_tcc_receipt");
    var before = remainingFacts();
    tracked.reset();
    tracked.checking = true;
    var failure = assertThrows(BizException.class, this::inspect);
    assertEquals(503, failure.getHttpStatus());
    assertEquals("PAY_TCC_RECONCILIATION_UNAVAILABLE", failure.getCode());
    assertFalse(failure.getMessage().contains("pay_tcc_receipt"));
    assertEquals(before, remainingFacts());
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND"
                + " table_name='pay_tcc_receipt'",
            Integer.class,
            database));
  }

  @Test
  void concurrentExternalCommitCannotMixTheSixTableRepeatableReadSnapshot() {
    seed("COMMITTED");
    afterPaymentRead.set(
        () ->
            new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(
                    status -> {
                      for (String table :
                          List.of(
                              "pay_record",
                              "pay_tcc_execution",
                              "pay_tcc_dispatch",
                              "pay_tcc_receipt"))
                        fixture.update("UPDATE " + table + " SET amount=20 WHERE pay_no='pay1'");
                    }));
    tracked.checking = true;
    var report = inspect();
    assertEquals("10.00", report.snapshot.amount());
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
    assertEquals(
        "20.00",
        fixture.queryForObject("SELECT CAST(amount AS CHAR) FROM pay_record", String.class));
    assertEquals(1, tracked.connections);
  }

  /** 真实只读入口，保留固定运维凭证只在测试内使用。 */
  private PayTccReconciliationVO inspect() {
    return service.inspect("pay1", "cm18-reconciliation", "ops-secret");
  }

  /** 创建已有六表的owned测试结构，绝不在被测检查期间运行。 */
  private static void ensureFixtureTables() {
    session.getMapper(PayTccExecutionMapper.class).ensureTable();
    session.getMapper(PayTccDispatchMapper.class).ensureTable();
    session.getMapper(PayTccReceiptMapper.class).ensureTable();
    session.getMapper(PayTccFenceMapper.class).ensureTable();
  }

  /** 用原字段准备本地完整终态；不注册任何TC分支或真实User操作。 */
  private static void seed(String state) {
    tracked.checking = false;
    for (String table : TABLES) fixture.update("DELETE FROM " + table);
    String time = "2026-10-09 12:00:00.123";
    fixture.update(
        "INSERT INTO"
            + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
            + " VALUES(1,'pay1','202610order1',7,10,'PENDING','TCC',?,?)",
        time,
        time);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_execution(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,decision,created_at,updated_at,recovery_attempts,recovery_owner,recovery_lease_until)"
            + " VALUES(1,'pay1','tc-private:8091:original',7,'202610order1',10,'tcc-debit:pay1','pay1',?,?,?,?,5,'existing-owner','2099-01-01')",
        state,
        "COMMITTED".equals(state) ? "COMMIT" : "ROLLBACK",
        time,
        time);
    String branchState = "COMMITTED".equals(state) ? "CONFIRMED" : "CANCELLED";
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_dispatch(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,user_branch_id,created_at,updated_at,last_error)"
            + " VALUES(1,'pay1','tc-private:8091:original',7,'202610order1',10,'tcc-debit:pay1','pay1',?,202,?,?,'private-error-password')",
        branchState,
        time,
        time);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_receipt(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,branch_id,created_at,updated_at)"
            + " VALUES(1,'pay1','tc-private:8091:original',7,'202610order1',10,'tcc-debit:pay1','pay1',?,101,?,?)",
        branchState,
        time,
        time);
    fixture.update(
        "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
            + " VALUES('tc-private:8091:original',101,?,?,?,?)",
        PayTccReceiptLocalResource.ACTION_NAME,
        "COMMITTED".equals(state) ? 2 : 3,
        time,
        time);
  }

  /** 完整六表行事实包括资金、租约、attempts、错误与原ID。 */
  private static List<List<Map<String, Object>>> facts() {
    return TABLES.stream().map(table -> fixture.queryForList("SELECT * FROM " + table)).toList();
  }

  /** 缺表时只核剩余五表，不能由检查工具重建表掩盖故障。 */
  private static List<List<Map<String, Object>>> remainingFacts() {
    return TABLES.stream()
        .filter(table -> !"pay_tcc_receipt".equals(table))
        .map(table -> fixture.queryForList("SELECT * FROM " + table))
        .toList();
  }

  /** 原Pay库只读表清单/校验和。 */
  private static List<Map<String, Object>> originalFingerprint() {
    List<Map<String, Object>> values =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_pay"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_pay", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表");
      values.addAll(source.queryForList("CHECKSUM TABLE cloudmall_pay." + table));
    }
    return values;
  }

  /** 日期遵循项目原上海语义，不设置全局时区。 */
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
