package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserLinkedRefundEvidenceController;
import com.cloudmall.user.domain.vo.UserLinkedRefundEvidenceVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.UserLinkedRefundEvidenceReadService;
import com.cloudmall.user.service.impl.UserLinkedRefundEvidenceService;
import com.cloudmall.user.service.impl.UserTccReconciliationReadService;
import com.cloudmall.user.service.impl.UserTccReconciliationService;
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
class UserLinkedRefundEvidenceMySqlIT {
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
  private static UserLinkedRefundEvidenceService service;

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
    database = "cm16t11_" + UUID.randomUUID().toString().replace("-", "");
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
        new UserLinkedRefundEvidenceReadService(
            baseReader, session.getMapper(BalanceLedgerMapper.class));
    ProxyFactory extendedProxy = new ProxyFactory(extendedTarget);
    extendedProxy.setProxyTargetClass(true);
    extendedProxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(tracked), new AnnotationTransactionAttributeSource()));
    service =
        new UserLinkedRefundEvidenceService(
            new UserTccReconciliationService(baseReader, true, "ops-secret"),
            (UserLinkedRefundEvidenceReadService) extendedProxy.getProxy(),
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
      System.out.println("CM16T11 original User tables/checksums unchanged");
    } finally {
      if (source != null && database != null && database.matches("cm16t11_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
        System.out.println("CM16T11 fixture dropped: " + database);
      }
    }
  }

  /** 旧关联入口保持原范围，NULL/错link同pay退款须由新入口补证。 */
  @Test
  void unlinkedSamePaymentRowsStayOutsideOriginalKeyScope() {
    for (String originalKey : new String[] {null, "other-private-key"}) {
      seed("CONFIRMED");
      linkedRefund(2, "orphan-private-key", "10.00");
      fixture.update("UPDATE balance_ledger SET original_operation_key=? WHERE id=2", originalKey);
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertTrue(report.unlinkedLedgerRowsNotInspected);
      assertTrue(report.candidatesComplete);
      assertEquals(0, report.observedCount);
      assertEquals("0.00", report.validatedTotalAmount);
      assertEquals(11, tracked.statements.size());
      assertEquals(before, facts());
    }
  }

  @Test
  void multipleDifferentKeysAreObservedAndFixedPartialCanBeResolvedInOneRr() {
    for (String variant : List.of("custom", "fixedPartial", "partial", "empty")) {
      seed("CONFIRMED");
      if ("custom".equals(variant)) {
        linkedRefund(2, "custom-a", "4.00");
        linkedRefund(3, "custom-b", "6.00");
      }
      if ("fixedPartial".equals(variant)) {
        seedRefund("4.00");
        linkedRefund(3, "custom-b", "6.00");
      }
      if ("partial".equals(variant)) linkedRefund(2, "custom-a", "4.00");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals(
          "partial".equals(variant) ? "BLOCKED_LOCAL_EVIDENCE" : "BLOCKED_EXTERNAL_EVIDENCE",
          report.classification,
          variant);
      assertTrue(report.candidatesComplete);
      assertEquals(
          "empty".equals(variant) ? "0.00" : "partial".equals(variant) ? "4.00" : "10.00",
          report.validatedTotalAmount);
      assertNotNull(report.vectorSha256);
      assertFalse(report.automaticActionsAllowed);
      assertTrue(report.unlinkedLedgerRowsNotInspected);
      if ("fixedPartial".equals(variant))
        assertEquals("BLOCKED_LOCAL_EVIDENCE", report.baseEvidence.classification);
      int expectedCandidates = "empty".equals(variant) ? 0 : "partial".equals(variant) ? 1 : 2;
      assertEquals(expectedCandidates, report.observedCount);
      assertEquals(1, tracked.connections);
      assertEquals(11 + expectedCandidates, tracked.statements.size());
      assertEquals(before, facts());
    }
  }

  @Test
  void badCiLinkedCandidatesAndOriginalFieldsAreNotFilteredOrRepaired() {
    for (String mutation :
        List.of(
            "original_operation_key='PAY1'",
            "original_operation_key='pay1 '",
            "user_id=8",
            "pay_no='PAY1'",
            "order_no='202610ORDER1'",
            "operation_type='refund'",
            "operation_type='DEBIT'",
            "state='applied'",
            "state='REJECTED'",
            "amount=0",
            "amount=-1",
            "balance_before=-1",
            "balance_after=999")) {
      seed("CONFIRMED");
      linkedRefund(2, "custom-a", "5.00");
      fixture.update("UPDATE balance_ledger SET " + mutation + " WHERE id=2");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      if ("operation_type='DEBIT'".equals(mutation)) {
        assertEquals(503, assertThrows(BizException.class, this::inspect).getHttpStatus());
        assertEquals(before, facts());
        continue;
      }
      var report = inspect();
      assertEquals("LOCAL_CONFLICT", report.classification, mutation);
      assertEquals(1, report.observedCount);
      assertNull(report.validatedTotalAmount);
      assertNull(report.vectorSha256);
      assertEquals(before, facts());
    }
  }

  @Test
  void candidateTotalOverOriginalIsConflictAndUnlinkedRangeIsExplicit() {
    seed("CONFIRMED");
    linkedRefund(2, "custom-a", "6.00");
    linkedRefund(3, "custom-b", "6.00");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    var report = inspect();
    assertTrue(report.reasonCodes.contains("REFUND_TOTAL_EXCEEDS_ORIGINAL"));
    assertNull(report.vectorSha256);
    assertEquals(before, facts());
    seed("CONFIRMED");
    linkedRefund(2, "unlinked-key", "10.00");
    fixture.update("UPDATE balance_ledger SET original_operation_key='other-ledger' WHERE id=2");
    before = facts();
    tracked.reset();
    tracked.checking = true;
    report = inspect();
    assertEquals(0, report.observedCount);
    assertEquals("0.00", report.validatedTotalAmount);
    assertTrue(report.unlinkedLedgerRowsNotInspected);
    assertEquals(before, facts());
  }

  @Test
  void hundredCandidateBoundaryAndSentinelNeverExposeATruncatedTotal() {
    for (int candidateCount : List.of(100, 101)) {
      seed("CONFIRMED");
      for (int index = 0; index < candidateCount; index++)
        linkedRefund(index + 2, "custom-" + index, "0.01");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals(candidateCount, report.observedCount);
      assertEquals(1, tracked.connections);
      if (candidateCount == 100) {
        assertTrue(report.candidatesComplete);
        assertEquals("1.00", report.validatedTotalAmount);
        assertEquals(111, tracked.statements.size());
      } else {
        assertFalse(report.candidatesComplete);
        assertTrue(report.reasonCodes.contains("REFUND_CANDIDATE_LIMIT_EXCEEDED"));
        assertNull(report.validatedTotalAmount);
        assertNull(report.vectorSha256);
        assertEquals(11, tracked.statements.size());
      }
      assertEquals(before, facts());
    }
  }

  @Test
  void ownedLegacyMissingUniqueIndexCannotHideGlobalCiDuplicateOperations() {
    tracked.checking = false;
    fixture.execute("ALTER TABLE balance_ledger DROP INDEX operation_key");
    try {
      for (String[] aliases :
          new String[][] {
            {"custom-a", "custom-a"},
            {"custom-a", "CUSTOM-A"},
            {"custom-a", "custom-a "},
            {"cafe", "caf\u00e9"}
          }) {
        seed("CONFIRMED");
        linkedRefund(2, aliases[0], "4.00");
        linkedRefund(3, aliases[1], "6.00");
        var before = facts();
        tracked.reset();
        tracked.checking = true;
        var report = inspect();
        assertEquals("LOCAL_CONFLICT", report.classification, java.util.Arrays.toString(aliases));
        assertTrue(report.reasonCodes.contains("REFUND_KEY_CARDINALITY_CONFLICT"));
        assertNull(report.vectorSha256);
        assertNull(report.validatedTotalAmount);
        assertEquals(before, facts());
      }
    } finally {
      tracked.checking = false;
      fixture.update("DELETE FROM balance_ledger");
      fixture.execute("ALTER TABLE balance_ledger ADD UNIQUE KEY operation_key(operation_key)");
    }
  }

  @Test
  void ownedLegacyNullMoneyRemainsMissingAndOriginalCancelledFundsConflict() {
    tracked.checking = false;
    fixture.execute(
        "ALTER TABLE balance_ledger MODIFY amount DECIMAL(18,2) NULL,MODIFY balance_before"
            + " DECIMAL(18,2) NULL,MODIFY balance_after DECIMAL(18,2) NULL");
    for (String column : List.of("amount", "balance_before", "balance_after")) {
      seed("CONFIRMED");
      linkedRefund(2, "custom-a", "5.00");
      fixture.update("UPDATE balance_ledger SET " + column + "=NULL WHERE id=2");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
      assertTrue(report.reasonCodes.contains("LINKED_REFUND_MONEY_MISSING"));
      assertNull(report.vectorSha256);
      assertEquals(before, facts());
    }
    seed("CANCELLED");
    linkedRefund(2, "custom-a", "5.00");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    assertTrue(inspect().reasonCodes.contains("REFUND_ORIGINAL_STATE_CONFLICT"));
    assertEquals(before, facts());
  }

  @Test
  void otherSourceGapsStayBlockedAndSourceConflictDoesNotReadCandidates() {
    seed("CONFIRMED");
    linkedRefund(2, "custom-a", "10.00");
    fixture.update("DELETE FROM mall_user");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    var missing = inspect();
    assertEquals("BLOCKED_LOCAL_EVIDENCE", missing.classification);
    assertNull(missing.validatedTotalAmount);
    assertNull(missing.vectorSha256);
    assertEquals(before, facts());
    seed("CONFIRMED");
    fixture.update("UPDATE mall_user SET frozen_balance=1");
    before = facts();
    tracked.reset();
    tracked.checking = true;
    var conflict = inspect();
    assertEquals("LOCAL_CONFLICT", conflict.classification);
    assertFalse(conflict.queryAttempted);
    assertEquals(10, tracked.statements.size());
    assertEquals(before, facts());
  }

  @Test
  void concurrentRefundInsertionIsInvisibleUntilTheNextReadSnapshot() {
    seed("CONFIRMED");
    linkedRefund(2, "custom-a", "4.00");
    afterReceiveRead.set(
        () ->
            new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(status -> linkedRefund(3, "custom-b", "6.00")));
    tracked.reset();
    tracked.checking = true;
    var first = inspect();
    assertEquals(1, first.observedCount);
    assertEquals("4.00", first.validatedTotalAmount);
    assertTrue(first.reasonCodes.contains("REFUND_TOTAL_PARTIAL"));
    assertEquals(12, tracked.statements.size());
    assertEquals(1, tracked.connections);
    tracked.reset();
    var next = inspect();
    assertEquals(2, next.observedCount);
    assertEquals("10.00", next.validatedTotalAmount);
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", next.classification);
    assertEquals(13, tracked.statements.size());
    assertEquals(1, tracked.connections);
  }

  @Test
  void httpGateProofNoStoreRedactionAndUnavailableNeverCreateEvidence() throws Exception {
    seed("CONFIRMED");
    linkedRefund(2, "custom-private-key", "10.00");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserLinkedRefundEvidenceController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route = "/api/internal/users/7/balance/tcc-reconciliation/pay1/refund-evidence";
    mvc.perform(get(route))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    assertEquals(0, tracked.connections);
    String response =
        mvc.perform(
                get(route)
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret")
                    .param("originalOperationKey", "wrong-client-key")
                    .param("xid", "wrong-client-xid"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.scope").value("USER_LINKED_REFUNDS_OBSERVED_ONLY"))
            .andExpect(jsonPath("$.data.validatedTotalAmount").value("10.00"))
            .andExpect(jsonPath("$.data.automaticActionsAllowed").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String raw :
        List.of(
            "custom-private-key",
            "private-tc:8091:original",
            "private-password",
            "private-phone",
            "tcc-debit:pay1",
            "ops-secret",
            "wrong-client-key",
            "wrong-client-xid")) assertFalse(response.contains(raw), raw);
    assertEquals(1, tracked.connections);
    assertEquals(12, tracked.statements.size());
    assertEquals(before, facts());
    tracked.checking = false;
    fixture.execute("DROP TABLE user_tcc_receive");
    tracked.reset();
    tracked.checking = true;
    assertEquals(503, assertThrows(BizException.class, this::inspect).getHttpStatus());
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND"
                + " table_name='user_tcc_receive'",
            Integer.class,
            database));
  }

  /** 自有库原账本关联退款行；不调用资金写入服务，不写源业务库。 */
  private static void linkedRefund(int identifier, String key, String amount) {
    fixture.update(
        "INSERT INTO"
            + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
            + " VALUES(?,7,?,'REFUND','pay1','pay1','202610order1',?,'APPLIED',90,90+?,'2026-10-11')",
        identifier,
        key,
        amount,
        amount);
  }

  /** 认证通过的实际诊断请求。 */
  private UserLinkedRefundEvidenceVO inspect() {
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

  /** 另笔冻结原行，仅用于aggregate观察（不冒称验证了其attempt关联）。 */
  private static void otherDebit(Long userId, String state, String amount) {
    fixture.update(
        "INSERT INTO"
            + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
            + " VALUES(2,'tcc-debit:pay2','other-private-xid',12,?,'pay2','202610order2',?,?,100,80,'2026-10-11','2026-10-11')",
        userId,
        amount,
        state);
  }

  /** 固定退款事实只写当前owned库。 */
  private static void seedRefund(String amount) {
    fixture.update(
        "INSERT INTO"
            + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
            + " VALUES(2,7,'refund:pay1','REFUND','pay1','pay1','202610order1',?,'APPLIED',100,100+?,'2026-10-11')",
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
