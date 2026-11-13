package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.controller.PayTccDeliveryEvidenceController;
import com.cloudmall.pay.domain.vo.PayTccDeliveryReconciliationVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.evidence.PayTccDeliveryEvidenceReadService;
import com.cloudmall.pay.service.impl.evidence.PayTccDeliveryEvidenceService;
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

/** 两层真实RR代理验证八来源同一快照，不启动应用、TC、端口或调度器。 */
class PayTccDeliveryEvidenceMySqlIT {
  /** 原库只读指纹与自有测试库DDL管理入口。 */
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
  private static PayTccDeliveryEvidenceService service;

  /** 真实RR代理。 */
  private static PayTccReconciliationReadService reader;

  /** 第一条支付SELECT返回之后执行的独立测试写入，仅用于RR竞争验证。 */
  private static final AtomicReference<Runnable> afterPaymentRead = new AtomicReference<>();

  /** Inspector涉及的八张表。 */
  private static final List<String> TABLES =
      List.of(
          "pay_record",
          "pay_tcc_execution",
          "pay_tcc_dispatch",
          "pay_tcc_receipt",
          "tcc_fence_log",
          "pay_debit_intent",
          "pay_tcc_delivery",
          "pay_tcc_cancel");

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_pay URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    sourceFingerprint = originalFingerprint();
    database = "cm16t8_" + UUID.randomUUID().toString().replace("-", "");
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
            PayTccFenceMapper.class,
            PayTccDeliveryMapper.class,
            PayTccCancelMapper.class)) configuration.addMapper(mapper);
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
    var extendedTarget =
        new PayTccDeliveryEvidenceReadService(
            reader,
            session.getMapper(PayTccDeliveryMapper.class),
            session.getMapper(PayTccCancelMapper.class));
    ProxyFactory extendedProxy = new ProxyFactory(extendedTarget);
    extendedProxy.setProxyTargetClass(true);
    extendedProxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(tracked), new AnnotationTransactionAttributeSource()));
    service =
        new PayTccDeliveryEvidenceService(
            new PayTccReconciliationService(reader, true, "ops-secret"),
            (PayTccDeliveryEvidenceReadService) extendedProxy.getProxy(),
            true);
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
    try {
      if (source != null) assertEquals(sourceFingerprint, originalFingerprint());
      System.out.println("CM16T8 original Pay tables and checksums unchanged");
    } finally {
      if (database != null && database.matches("cm16t8_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
        System.out.println("CM16T8 fixture dropped: " + database);
      }
    }
  }

  @Test
  void everyValidDeliveryStageUsesOneReadOnlyRrConnectionAndPreservesAllRows() {
    for (String stage :
        List.of(
            "READY",
            "CANCEL_REQUESTED",
            "CANCEL_RECONCILING",
            "ORDER_SYNC_UNKNOWN",
            "REFUND_PENDING",
            "SUCCESS",
            "REFUNDED")) {
      seed("COMMITTED");
      seedDelivery(stage);
      fixture.update(
          "UPDATE pay_record SET status=?",
          List.of("READY", "CANCEL_REQUESTED", "CANCEL_RECONCILING").contains(stage)
              ? "PENDING"
              : stage);
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification, stage);
      assertEquals("COMMITTED", report.localEvidence.snapshot.execution().state());
      assertTrue(report.delivery.identityMatched());
      assertFalse(report.automaticActionsAllowed);
      assertEquals(1, tracked.connections);
      assertEquals(8, tracked.statements.size());
      assertEquals(before, facts());
      assertTrue(report.delivery.leasePresent());
      assertTrue(report.delivery.errorPresent());
      assertEquals(5, report.delivery.attempts());
      assertTrue(report.delivery.nextRetryAt().endsWith("+08:00"));
    }
  }

  @Test
  void preDebitCancellationRemainsBlockedWithoutInventingFundsEvidence() {
    for (String state :
        List.of("REQUESTED", "ORDER_CANCELLED", "CANCEL_UNKNOWN", "COMPLETED", "CONFLICT")) {
      seed("COMMITTED");
      for (String table :
          List.of("pay_tcc_execution", "pay_tcc_dispatch", "pay_tcc_receipt", "tcc_fence_log"))
        fixture.update("DELETE FROM " + table);
      seedCancel(state);
      fixture.update(
          "UPDATE pay_record SET status=?", "COMPLETED".equals(state) ? "FAILED" : "PENDING");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals(
          "CONFLICT".equals(state) ? "LOCAL_CONFLICT" : "BLOCKED_LOCAL_EVIDENCE",
          report.classification,
          state);
      if ("CONFLICT".equals(state))
        assertTrue(report.reasonCodes.contains("CANCEL_RECORDED_CONFLICT"));
      assertTrue(report.cancel.identityMatched());
      assertFalse(report.delivery.present());
      assertTrue(report.reasonCodes.contains("EXECUTION_ROW_MISSING"));
      assertEquals(1, tracked.connections);
      assertEquals(7, tracked.statements.size());
      assertEquals(before, facts());
    }
  }

  @Test
  void ciCandidatesCannotHideDeliveryIdentityOrStateConflicts() {
    for (String mutation :
        List.of(
            "pay_no='PAY1'",
            "order_no='202610ORDER1'",
            "user_id=8",
            "amount=11",
            "xid='TC-private:8091:original'",
            "refund_operation_key='refund:PAY1'",
            "state='ready'",
            "state='private-state-password'")) {
      seed("COMMITTED");
      seedDelivery("READY");
      fixture.update("UPDATE pay_tcc_delivery SET " + mutation);
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals("LOCAL_CONFLICT", report.classification, mutation);
      assertTrue(report.delivery.present());
      assertEquals(before, facts());
    }
    for (String mutation : List.of("status='SUCCESS'", "debit_mode='LEGACY'")) {
      seed("COMMITTED");
      seedDelivery("READY");
      fixture.update("UPDATE pay_record SET " + mutation);
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      assertEquals("LOCAL_CONFLICT", inspect().classification);
      assertEquals(before, facts());
    }
  }

  @Test
  void cancellationIdentityAndFundsCoexistenceConflictsRemainUntouched() {
    for (String mutation :
        List.of(
            "pay_no='PAY1'",
            "order_no='202610ORDER1'",
            "user_id=8",
            "amount=11",
            "state='requested'",
            "state='COMPLETED'")) {
      seed("COMMITTED");
      for (String table :
          List.of("pay_tcc_execution", "pay_tcc_dispatch", "pay_tcc_receipt", "tcc_fence_log"))
        fixture.update("DELETE FROM " + table);
      seedCancel("REQUESTED");
      fixture.update("UPDATE pay_tcc_cancel SET " + mutation);
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      assertEquals("LOCAL_CONFLICT", inspect().classification, mutation);
      assertEquals(before, facts());
    }
    seed("COMMITTED");
    seedDelivery("READY");
    seedCancel("REQUESTED");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    var report = inspect();
    assertEquals("LOCAL_CONFLICT", report.classification);
    assertTrue(report.reasonCodes.contains("CANCEL_FUNDS_EVIDENCE_CONFLICT"));
    assertEquals(before, facts());
  }

  @Test
  void missingDeliveryAndUnknownBeginNeverCreateTasksOrChangeDecisions() {
    seed("COMMITTED");
    var before = facts();
    tracked.checking = true;
    var report = inspect();
    assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
    assertTrue(report.reasonCodes.contains("DELIVERY_ROW_MISSING"));
    assertNull(report.delivery.identityMatched());
    assertEquals(before, facts());
    seed("COMMITTED");
    seedDelivery("READY");
    fixture.update("UPDATE pay_tcc_execution SET state='BEGIN_UNKNOWN',decision='NONE'");
    before = facts();
    tracked.reset();
    tracked.checking = true;
    report = inspect();
    assertEquals("BLOCKED_BEGIN_UNKNOWN", report.classification);
    assertTrue(report.reasonCodes.contains("DELIVERY_COMMIT_EVIDENCE_MISSING"));
    assertEquals(before, facts());
  }

  @Test
  void missingEitherExtraTableIsUnavailableAndNeverRepaired() {
    for (String table : List.of("pay_tcc_delivery", "pay_tcc_cancel")) {
      tracked.checking = false;
      ensureFixtureTables();
      seed("COMMITTED");
      seedDelivery("READY");
      fixture.execute("DROP TABLE " + table);
      var before =
          TABLES.stream()
              .filter(candidate -> !candidate.equals(table))
              .map(candidate -> fixture.queryForList("SELECT * FROM " + candidate))
              .toList();
      tracked.reset();
      tracked.checking = true;
      var failure = assertThrows(BizException.class, this::inspect);
      assertEquals(503, failure.getHttpStatus());
      assertEquals("PAY_TCC_DELIVERY_EVIDENCE_UNAVAILABLE", failure.getCode());
      assertFalse(failure.getMessage().contains(table));
      assertEquals(
          before,
          TABLES.stream()
              .filter(candidate -> !candidate.equals(table))
              .map(candidate -> fixture.queryForList("SELECT * FROM " + candidate))
              .toList());
      assertEquals(
          0,
          fixture.queryForObject(
              "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND"
                  + " table_name=?",
              Integer.class,
              database,
              table));
    }
  }

  @Test
  void independentWriterCannotMixOriginalSixFactsWithDeliveryOrNewCancel() {
    seed("COMMITTED");
    seedDelivery("READY");
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
                              "pay_tcc_receipt",
                              "pay_tcc_delivery"))
                        fixture.update("UPDATE " + table + " SET amount=20 WHERE pay_no='pay1'");
                      seedCancel("REQUESTED");
                      fixture.update("UPDATE pay_tcc_cancel SET amount=20");
                    }));
    tracked.reset();
    tracked.checking = true;
    var report = inspect();
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
    assertEquals("10.00", report.localEvidence.snapshot.amount());
    assertTrue(report.delivery.identityMatched());
    assertFalse(report.cancel.present());
    assertEquals(1, tracked.connections);
    assertEquals(8, tracked.statements.size());
    assertEquals(
        "20.00",
        fixture.queryForObject("SELECT CAST(amount AS CHAR) FROM pay_tcc_delivery", String.class));
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM pay_tcc_cancel", Integer.class));
  }

  @Test
  void httpReportIgnoresSuppliedIdentityAndLeaksNoReplayableFields() throws Exception {
    seed("COMMITTED");
    seedDelivery("REFUNDED");
    fixture.update("UPDATE pay_record SET status='REFUNDED'");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    var mvc =
        MockMvcBuilders.standaloneSetup(new PayTccDeliveryEvidenceController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    var response =
        mvc.perform(
                get("/api/internal/payments/pay1/tcc-reconciliation/delivery-evidence")
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret")
                    .param("xid", "wrong-client-xid")
                    .param("userId", "999")
                    .param("orderNo", "wrong-client-order"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.scope").value("PAY_LOCAL_DELIVERY_CANCEL_ONLY"))
            .andExpect(jsonPath("$.data.classification").value("BLOCKED_EXTERNAL_EVIDENCE"))
            .andExpect(jsonPath("$.data.delivery.state").value("REFUNDED"))
            .andExpect(jsonPath("$.data.delivery.identityMatched").value(true))
            .andExpect(jsonPath("$.data.automaticActionsAllowed").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String secret :
        List.of(
            "tc-private:8091:original",
            "refund:pay1",
            "tcc-debit:pay1",
            "private-error-password",
            "private-lease-token",
            "wrong-client-xid",
            "wrong-client-order",
            "ops-secret")) assertFalse(response.contains(secret), secret);
    assertEquals(before, facts());
    assertEquals(1, tracked.connections);
    assertEquals(8, tracked.statements.size());
  }

  @Test
  void invalidGatesAndAbsentOrAliasedPaymentsNeverReadExtraTables() {
    seed("COMMITTED");
    seedDelivery("READY");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    assertEquals(
        403,
        assertThrows(BizException.class, () -> service.inspect("pay1", "other", "ops-secret"))
            .getHttpStatus());
    assertEquals(
        400,
        assertThrows(
                BizException.class,
                () -> service.inspect("bad%", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    assertEquals(0, tracked.connections);
    assertEquals(0, tracked.statements.size());
    for (String payNo : List.of("absent", "PAY1")) {
      tracked.reset();
      assertEquals(
          "absent".equals(payNo) ? 404 : 409,
          assertThrows(
                  BizException.class,
                  () -> service.inspect(payNo, "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
      assertEquals(1, tracked.statements.size());
      assertEquals(1, tracked.connections);
      assertEquals(before, facts());
    }
  }

  /** 测试专属交付事实，只写owned库，绝不调用真实恢复或资金动作。 */
  private static void seedDelivery(String state) {
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_delivery(id,pay_no,xid,user_id,order_no,amount,state,refund_operation_key,attempts,next_retry_at,lease_owner,lease_until,last_error,created_at,updated_at)"
            + " VALUES(1,'pay1','tc-private:8091:original',7,'202610order1',10,?,'refund:pay1',5,'2026-10-11"
            + " 12:00:00.123','private-lease-token','2099-01-01','private-error-password','2026-10-11"
            + " 12:00:00.123','2026-10-11 12:00:00.123')",
        state);
  }

  /** 测试专属取消事实，不调用Order/Stock/资金写入服务。 */
  private static void seedCancel(String state) {
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_cancel(id,pay_no,user_id,order_no,amount,state,attempts,next_retry_at,lease_owner,lease_until,last_error,created_at,updated_at)"
            + " VALUES(1,'pay1',7,'202610order1',10,?,4,'2026-10-11"
            + " 12:00:00.123','private-cancel-lease','2099-01-01','private-cancel-error','2026-10-11"
            + " 12:00:00.123','2026-10-11 12:00:00.123')",
        state);
  }

  /** 真实只读入口，保留固定运维凭证只在测试内使用。 */
  private PayTccDeliveryReconciliationVO inspect() {
    return service.inspect("pay1", "cm18-reconciliation", "ops-secret");
  }

  /** 创建已有八表的owned测试结构，绝不在被测检查期间运行。 */
  private static void ensureFixtureTables() {
    session.getMapper(PayTccExecutionMapper.class).ensureTable();
    session.getMapper(PayTccDispatchMapper.class).ensureTable();
    session.getMapper(PayTccReceiptMapper.class).ensureTable();
    session.getMapper(PayTccFenceMapper.class).ensureTable();
    session.getMapper(PayTccDeliveryMapper.class).ensureTable();
    session.getMapper(PayTccCancelMapper.class).ensureTable();
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

  /** 完整八表行事实包括资金、租约、attempts、错误与原ID。 */
  private static List<List<Map<String, Object>>> facts() {
    return TABLES.stream().map(table -> fixture.queryForList("SELECT * FROM " + table)).toList();
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
