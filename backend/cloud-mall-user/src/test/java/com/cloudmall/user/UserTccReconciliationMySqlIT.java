package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.user.controller.UserFrozenOwnershipEvidenceController;
import com.cloudmall.user.controller.UserLinkedRefundEvidenceController;
import com.cloudmall.user.controller.UserTccController;
import com.cloudmall.user.controller.UserTccReconciliationController;
import com.cloudmall.user.domain.vo.UserTccReconciliationVO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceLedgerMapper;
import com.cloudmall.user.mapper.BalanceTccDebitMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.mapper.UserMapper;
import com.cloudmall.user.mapper.UserTccReceiveMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.UserFrozenOwnershipEvidenceReadService;
import com.cloudmall.user.service.impl.UserFrozenOwnershipEvidenceService;
import com.cloudmall.user.service.impl.UserLinkedRefundEvidenceReadService;
import com.cloudmall.user.service.impl.UserLinkedRefundEvidenceService;
import com.cloudmall.user.service.impl.UserTccHttpService;
import com.cloudmall.user.service.impl.UserTccReceiveStore;
import com.cloudmall.user.service.impl.UserTccReconciliationReadService;
import com.cloudmall.user.service.impl.UserTccReconciliationService;
import com.cloudmall.user.service.impl.UserTccStateQuery;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实MyBatis/外层RR验证，不启动User应用、RM、TC、调度器或端口。 */
class UserTccReconciliationMySqlIT {
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
  private static UserTccReconciliationService service;

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
    database = "cm16t9_" + UUID.randomUUID().toString().replace("-", "");
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
    service =
        new UserTccReconciliationService(
            (UserTccReconciliationReadService) proxy.getProxy(), true, "ops-secret");
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
      System.out.println("CM16T9 original User tables/checksums unchanged");
    } finally {
      if (source != null && database != null && database.matches("cm16t9_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
        System.out.println("CM16T9 fixture dropped: " + database);
      }
    }
  }

  /** 未确认的多DEBIT同样是明确冲突，不能在raw no-funds门前变成500/503。 */
  @Test
  void unconfirmedDuplicateDebitsReturnConflictWithoutChangingFacts() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var mvc = cancelledEvidenceRoutes(resource, receiveStore);
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : List.of("TRYING", "CANCELLED", "empty-cancel")) {
      for (String variant : List.of("APPLIED", "UNKNOWN", "type-alias", "pay-alias", "multiple")) {
        assertions.add(
            () -> {
              seed("TRYING".equals(mode) ? "TRYING" : "CANCELLED");
              if ("empty-cancel".equals(mode)) {
                fixture.update("DELETE FROM balance_tcc_debit");
                fixture.update("UPDATE tcc_fence_log SET status=4");
              }
              String payNo = "pay-alias".equals(variant) ? "PAY1" : "pay1";
              String type = "type-alias".equals(variant) ? "debit" : "DEBIT";
              String state = "UNKNOWN".equals(variant) ? "UNKNOWN" : "APPLIED";
              seedAlternateLedger(payNo, type, state);
              fixture.update(
                  "INSERT INTO"
                      + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                      + " VALUES(3,7,'second-debit',?,?,'202610order1',10,?,100,90,'2026-10-11')",
                  type,
                  payNo,
                  state);
              if ("multiple".equals(variant))
                fixture.update(
                    "INSERT INTO"
                        + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                        + " VALUES(4,7,'third-debit','DEBIT','pay1','202610order1',10,'APPLIED',100,90,'2026-10-11')");
              var before = facts();
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> {
                    tracked.reset();
                    tracked.checking = true;
                    mvc.perform(
                            get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                                .header("X-User-Id", "7")
                                .header("X-Internal-Service", "cloud-mall-pay")
                                .header("X-Internal-Callback-Token", "ops-secret")
                                .param("xid", "private-tc:8091:original"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
                    assertEquals(1, tracked.connections);
                    assertEquals(before, facts());
                  },
                  () -> {
                    tracked.reset();
                    tracked.checking = true;
                    mvc.perform(
                            get("/api/internal/users/7/balance/tcc-reconciliation/pay1")
                                .header("X-Internal-Service", "cm18-reconciliation")
                                .header("X-Internal-Callback-Token", "ops-secret"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"))
                        .andExpect(header().string("Cache-Control", "no-store"));
                    assertEquals(1, tracked.connections);
                    assertEquals(before, facts());
                  });
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    verifyNoInteractions(resource, receiveStore);
  }

  /** 多DEBIT是明确持久事实冲突，不应由单行Mapper变成500/503不可用。 */
  @Test
  void confirmedStateDuplicateDebitsAreConflictRatherThanSystemUnavailable() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var mvc = cancelledEvidenceRoutes(resource, receiveStore);
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String variant :
        List.of("APPLIED", "REJECTED", "UNKNOWN", "type-alias", "pay-alias", "multiple")) {
      assertions.add(
          () -> {
            seed("CONFIRMED");
            seedAlternateLedger(
                "pay-alias".equals(variant) ? "PAY1" : "pay1",
                "type-alias".equals(variant) ? "debit" : "DEBIT",
                List.of("REJECTED", "UNKNOWN").contains(variant) ? variant : "APPLIED");
            if ("multiple".equals(variant))
              fixture.update(
                  "INSERT INTO"
                      + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                      + " VALUES(3,7,'third-debit','DEBIT','pay1','202610order1',10,'APPLIED',100,90,'2026-10-11')");
            var before = facts();
            org.junit.jupiter.api.Assertions.assertAll(
                () -> {
                  tracked.reset();
                  tracked.checking = true;
                  mvc.perform(
                          get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                              .header("X-User-Id", "7")
                              .header("X-Internal-Service", "cloud-mall-pay")
                              .header("X-Internal-Callback-Token", "ops-secret")
                              .param("xid", "private-tc:8091:original"))
                      .andExpect(status().isConflict())
                      .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
                  assertEquals(1, tracked.connections);
                  assertEquals(before, facts());
                },
                () -> {
                  tracked.reset();
                  tracked.checking = true;
                  mvc.perform(
                          get("/api/internal/users/7/balance/tcc-reconciliation/pay1")
                              .header("X-Internal-Service", "cm18-reconciliation")
                              .header("X-Internal-Callback-Token", "ops-secret"))
                      .andExpect(status().isConflict())
                      .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"))
                      .andExpect(header().string("Cache-Control", "no-store"));
                  assertEquals(1, tracked.connections);
                  assertEquals(before, facts());
                });
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    verifyNoInteractions(resource, receiveStore);
  }

  /** 三种无扣款状态不能忽略同pay孤立REFUND/未知类型，HTTP与诊断同只读RR拒绝。 */
  @Test
  void unconfirmedReadsRejectAllPaymentLedgerTypesWithoutChangingFacts() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var mvc = cancelledEvidenceRoutes(resource, receiveStore);
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String mode : List.of("TRYING", "CANCELLED", "empty-cancel")) {
      for (String variant :
          List.of("refund-applied", "refund-unknown", "type-unknown", "pay-alias", "other-user")) {
        assertions.add(
            () -> {
              seed("TRYING".equals(mode) ? "TRYING" : "CANCELLED");
              if ("empty-cancel".equals(mode)) {
                fixture.update("DELETE FROM balance_tcc_debit");
                fixture.update("UPDATE tcc_fence_log SET status=4");
              }
              seedAlternateLedger(
                  "pay-alias".equals(variant) ? "PAY1" : "pay1",
                  "type-unknown".equals(variant) ? "UNKNOWN" : "REFUND",
                  "refund-unknown".equals(variant) ? "UNKNOWN" : "APPLIED");
              if ("other-user".equals(variant))
                fixture.update("UPDATE balance_ledger SET user_id=8 WHERE id=2");
              var before = facts();
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> {
                    tracked.reset();
                    tracked.checking = true;
                    mvc.perform(
                            get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                                .header("X-User-Id", "7")
                                .header("X-Internal-Service", "cloud-mall-pay")
                                .header("X-Internal-Callback-Token", "ops-secret")
                                .param("xid", "private-tc:8091:original"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
                    assertEquals(1, tracked.connections);
                    assertEquals(before, facts());
                  },
                  () -> {
                    tracked.reset();
                    tracked.checking = true;
                    mvc.perform(
                            get("/api/internal/users/7/balance/tcc-reconciliation/pay1")
                                .header("X-Internal-Service", "cm18-reconciliation")
                                .header("X-Internal-Callback-Token", "ops-secret"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"))
                        .andExpect(header().string("Cache-Control", "no-store"));
                    assertEquals(1, tracked.connections);
                    assertEquals(before, facts());
                  });
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    verifyNoInteractions(resource, receiveStore);
  }

  /** 同pay别键DEBIT即使未APPLIED或CI别名，两个取消形态也不得输出已取消。 */
  @Test
  void alternateDebitKeysCannotProveCancelledStateThroughRealReadRoutes() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var mvc = cancelledEvidenceRoutes(resource, receiveStore);
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (boolean emptyCancel : List.of(false, true)) {
      for (String ledgerState : List.of("APPLIED", "PENDING", "UNKNOWN")) {
        for (String payNo : List.of("pay1", "PAY1")) {
          assertions.add(
              () -> {
                prepareCancelledMode(emptyCancel);
                seedAlternateLedger(payNo, "DEBIT", ledgerState);
                var before = facts();
                org.junit.jupiter.api.Assertions.assertAll(
                    () -> {
                      tracked.reset();
                      tracked.checking = true;
                      mvc.perform(
                              get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                                  .header("X-User-Id", "7")
                                  .header("X-Internal-Service", "cloud-mall-pay")
                                  .header("X-Internal-Callback-Token", "ops-secret")
                                  .param("xid", "private-tc:8091:original"))
                          .andExpect(status().isConflict())
                          .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
                      assertEquals(1, tracked.connections);
                      assertEquals(before, facts());
                    },
                    () -> {
                      tracked.reset();
                      tracked.checking = true;
                      mvc.perform(
                              get("/api/internal/users/7/balance/tcc-reconciliation/pay1")
                                  .header("X-Internal-Service", "cm18-reconciliation")
                                  .header("X-Internal-Callback-Token", "ops-secret"))
                          .andExpect(status().isConflict())
                          .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"))
                          .andExpect(header().string("Cache-Control", "no-store"));
                      assertEquals(1, tracked.connections);
                      assertEquals(before, facts());
                    });
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    verifyNoInteractions(resource, receiveStore);
  }

  /** 同pay孤立退款冲突，其它pay或真实无账本的取消与Fence历史TTL保持。 */
  @Test
  void cancelledModesRejectOrphanRefundAndKeepOtherPaymentsWithoutWriting() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var mvc = cancelledEvidenceRoutes(resource, receiveStore);
    for (boolean emptyCancel : List.of(false, true)) {
      for (boolean expiredFence : List.of(false, true)) {
        for (String control : List.of("none", "refund", "other-payment-debit")) {
          prepareCancelledMode(emptyCancel);
          if (expiredFence) fixture.update("DELETE FROM tcc_fence_log");
          if (!"none".equals(control))
            seedAlternateLedger(
                "refund".equals(control) ? "pay1" : "pay2",
                "refund".equals(control) ? "REFUND" : "DEBIT",
                "APPLIED");
          var before = facts();
          if ("refund".equals(control)) {
            tracked.reset();
            tracked.checking = true;
            mvc.perform(
                    get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                        .header("X-User-Id", "7")
                        .header("X-Internal-Service", "cloud-mall-pay")
                        .header("X-Internal-Callback-Token", "ops-secret")
                        .param("xid", "private-tc:8091:original"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
            assertEquals(1, tracked.connections);
            assertEquals(before, facts());
            tracked.reset();
            assertEquals(
                "USER_TCC_STATE_CONFLICT",
                assertThrows(BizException.class, this::inspect).getCode());
            assertEquals(1, tracked.connections);
            assertEquals(before, facts());
            continue;
          }
          tracked.reset();
          tracked.checking = true;
          mvc.perform(
                  get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                      .header("X-User-Id", "7")
                      .header("X-Internal-Service", "cloud-mall-pay")
                      .header("X-Internal-Callback-Token", "ops-secret")
                      .param("xid", "private-tc:8091:original"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.state").value("CANCELLED"))
              .andExpect(jsonPath("$.data.ledgerApplied").value(false));
          assertEquals(1, tracked.connections);
          assertEquals(before, facts());
          tracked.reset();
          var report = inspect();
          assertEquals("CANCELLED", report.tccState);
          assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
          assertEquals(1, tracked.connections);
          assertEquals(before, facts());
        }
      }
    }
    verifyNoInteractions(resource, receiveStore);
  }

  /** 同RR首次读不混入外部新DEBIT，下次快照必须拒绝，读取不撤销writer真实事实。 */
  @Test
  void cancelledDebitScopeRemainsWithinOneSnapshotAndSeesConflictOnNextRead() {
    prepareCancelledMode(true);
    var before = facts();
    afterReceiveRead.set(() -> seedAlternateLedger("pay1", "DEBIT", "APPLIED"));
    tracked.reset();
    tracked.checking = true;
    assertEquals("CANCELLED", inspect().tccState);
    assertEquals(1, tracked.connections);
    var afterWriter = facts();
    for (int tableIndex = 0; tableIndex < TABLES.size(); tableIndex++) {
      if (!"balance_ledger".equals(TABLES.get(tableIndex)))
        assertEquals(before.get(tableIndex), afterWriter.get(tableIndex));
    }
    assertEquals(1, afterWriter.get(TABLES.indexOf("balance_ledger")).size());
    tracked.reset();
    var conflict = assertThrows(BizException.class, () -> inspect());
    assertEquals("USER_TCC_STATE_CONFLICT", conflict.getCode());
    assertEquals(409, conflict.getHttpStatus());
    assertEquals(1, tracked.connections);
    assertEquals(afterWriter, facts());
  }

  /** 固定键缺行的TRYING仍须拒绝同pay其它键/状态/CI候选扣款。 */
  @Test
  void tryingOtherDebitKeysAreRejectedByRealReadRoutes() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var mvc = cancelledEvidenceRoutes(resource, receiveStore);
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String ledgerState : List.of("APPLIED", "PENDING", "UNKNOWN")) {
      for (String payNo : List.of("pay1", "PAY1")) {
        assertions.add(
            () -> {
              seed("TRYING");
              seedAlternateLedger(payNo, "DEBIT", ledgerState);
              var before = facts();
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> {
                    tracked.reset();
                    tracked.checking = true;
                    mvc.perform(
                            get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                                .header("X-User-Id", "7")
                                .header("X-Internal-Service", "cloud-mall-pay")
                                .header("X-Internal-Callback-Token", "ops-secret")
                                .param("xid", "private-tc:8091:original"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
                    assertEquals(1, tracked.connections);
                    assertEquals(before, facts());
                  },
                  () -> {
                    tracked.reset();
                    tracked.checking = true;
                    mvc.perform(
                            get("/api/internal/users/7/balance/tcc-reconciliation/pay1")
                                .header("X-Internal-Service", "cm18-reconciliation")
                                .header("X-Internal-Callback-Token", "ops-secret"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"))
                        .andExpect(header().string("Cache-Control", "no-store"));
                    assertEquals(1, tracked.connections);
                    assertEquals(before, facts());
                  });
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    verifyNoInteractions(resource, receiveStore);
  }

  /** 原固定账本正确加第二DEBIT真实触发非唯一；旧异常渠道必须阻止确认成功。 */
  @Test
  void confirmedMultipleDebitRowsCannotBeHiddenByCorrectFixedLedger() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var mvc = cancelledEvidenceRoutes(resource, receiveStore);
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String ledgerState : List.of("APPLIED", "PENDING", "UNKNOWN")) {
      for (String payNo : List.of("pay1", "PAY1")) {
        assertions.add(
            () -> {
              seed("CONFIRMED");
              seedAlternateLedger(payNo, "DEBIT", ledgerState);
              var before = facts();
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> {
                    tracked.reset();
                    tracked.checking = true;
                    mvc.perform(
                            get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                                .header("X-User-Id", "7")
                                .header("X-Internal-Service", "cloud-mall-pay")
                                .header("X-Internal-Callback-Token", "ops-secret")
                                .param("xid", "private-tc:8091:original"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"))
                        .andExpect(jsonPath("$.data").doesNotExist());
                    assertEquals(1, tracked.connections);
                    assertEquals(before, facts());
                  },
                  () -> {
                    tracked.reset();
                    tracked.checking = true;
                    mvc.perform(
                            get("/api/internal/users/7/balance/tcc-reconciliation/pay1")
                                .header("X-Internal-Service", "cm18-reconciliation")
                                .header("X-Internal-Callback-Token", "ops-secret"))
                        .andExpect(status().isConflict())
                        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"))
                        .andExpect(header().string("Cache-Control", "no-store"))
                        .andExpect(jsonPath("$.data").doesNotExist());
                    assertEquals(1, tracked.connections);
                    assertEquals(before, facts());
                  });
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    verifyNoInteractions(resource, receiveStore);
  }

  /** 唯一原扣款、退款及其它pay对照不扩大scope；确认历史Fence过期兼容保持。 */
  @Test
  void tryingRejectsOrphanRefundWhileConfirmedAndOtherPaymentsKeepTheirScopes() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var mvc = cancelledEvidenceRoutes(resource, receiveStore);
    for (String state : List.of("TRYING", "CONFIRMED")) {
      for (boolean expiredFence : "TRYING".equals(state) ? List.of(false) : List.of(false, true)) {
        for (String control : List.of("none", "refund", "other-payment-debit")) {
          seed(state);
          if (expiredFence) fixture.update("DELETE FROM tcc_fence_log");
          if (!"none".equals(control))
            seedAlternateLedger(
                "refund".equals(control) ? "pay1" : "pay2",
                "refund".equals(control) ? "REFUND" : "DEBIT",
                "APPLIED");
          var before = facts();
          if ("TRYING".equals(state) && "refund".equals(control)) {
            tracked.reset();
            tracked.checking = true;
            mvc.perform(
                    get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                        .header("X-User-Id", "7")
                        .header("X-Internal-Service", "cloud-mall-pay")
                        .header("X-Internal-Callback-Token", "ops-secret")
                        .param("xid", "private-tc:8091:original"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
            assertEquals(1, tracked.connections);
            assertEquals(before, facts());
            tracked.reset();
            assertEquals(
                "USER_TCC_STATE_CONFLICT",
                assertThrows(BizException.class, this::inspect).getCode());
            assertEquals(1, tracked.connections);
            assertEquals(before, facts());
            continue;
          }
          tracked.reset();
          tracked.checking = true;
          mvc.perform(
                  get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                      .header("X-User-Id", "7")
                      .header("X-Internal-Service", "cloud-mall-pay")
                      .header("X-Internal-Callback-Token", "ops-secret")
                      .param("xid", "private-tc:8091:original"))
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.state").value(state))
              .andExpect(jsonPath("$.data.ledgerApplied").value("CONFIRMED".equals(state)));
          assertEquals(1, tracked.connections);
          assertEquals(before, facts());
          tracked.reset();
          var report = inspect();
          assertEquals(state, report.tccState);
          assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
          assertEquals(1, tracked.connections);
          assertEquals(before, facts());
        }
      }
    }
    verifyNoInteractions(resource, receiveStore);
  }

  /** 真实原DEBIT被写入非NULL origin后，state与T9在原scope之前拒绝且无写。 */
  @Test
  void confirmedDebitWithRefundOriginCannotBeProvenByReadRoutes() throws Exception {
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var mvc = cancelledEvidenceRoutes(resource, receiveStore);
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String origin : List.of("", "null", "pay1", "other-pay", "PAY1", " ")) {
      assertions.add(
          () -> {
            seed("CONFIRMED");
            fixture.update("UPDATE balance_ledger SET original_operation_key=? WHERE id=1", origin);
            var before = facts();
            org.junit.jupiter.api.Assertions.assertAll(
                () -> {
                  tracked.reset();
                  tracked.checking = true;
                  mvc.perform(
                          get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                              .header("X-User-Id", "7")
                              .header("X-Internal-Service", "cloud-mall-pay")
                              .header("X-Internal-Callback-Token", "ops-secret")
                              .param("xid", "private-tc:8091:original"))
                      .andExpect(status().isConflict())
                      .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
                  assertEquals(1, tracked.connections);
                  assertEquals(4, tracked.statements.size());
                  assertEquals(before, facts());
                },
                () -> {
                  tracked.reset();
                  tracked.checking = true;
                  mvc.perform(
                          get("/api/internal/users/7/balance/tcc-reconciliation/pay1")
                              .header("X-Internal-Service", "cm18-reconciliation")
                              .header("X-Internal-Callback-Token", "ops-secret"))
                      .andExpect(status().isConflict())
                      .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"))
                      .andExpect(header().string("Cache-Control", "no-store"));
                  assertEquals(1, tracked.connections);
                  assertEquals(5, tracked.statements.size());
                  assertEquals(before, facts());
                });
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    verifyNoInteractions(resource, receiveStore);
  }

  /** 构造原User state及诊断Controller，真实RR与Mapper，不启动端口或资金写资源。 */
  private static MockMvc cancelledEvidenceRoutes(
      BalanceTccDebitLocalResource resource, UserTccReceiveStore receiveStore) {
    var query =
        (UserTccStateQuery)
            readProxy(
                    new UserTccStateQuery(
                        session.getMapper(UserTccReceiveMapper.class),
                        session.getMapper(BalanceDebitAttemptMapper.class),
                        session.getMapper(BalanceTccDebitMapper.class),
                        session.getMapper(BalanceLedgerMapper.class),
                        session.getMapper(TccFenceLogMapper.class)))
                .getProxy();
    var bridge =
        new UserTccHttpService(
            resource, receiveStore, query, session.getMapper(UserMapper.class), "ops-secret", true);
    return MockMvcBuilders.standaloneSetup(
            new UserTccController(bridge), new UserTccReconciliationController(service))
        .setControllerAdvice(new GlobalExceptionHandler())
        .build();
  }

  /** 只在owned库准备既有释放取消或attempt-only空取消，不执行真实资金写。 */
  private static void prepareCancelledMode(boolean emptyCancel) {
    seed("CANCELLED");
    if (emptyCancel) {
      fixture.update("DELETE FROM balance_tcc_debit");
      fixture.update("UPDATE tcc_fence_log SET status=4");
    }
  }

  /** 不占固定pay ledgerKey的候选，只写owned；按原Mapper范围区分DEBIT/REFUND与其它pay。 */
  private static void seedAlternateLedger(String payNo, String operationType, String ledgerState) {
    fixture.update(
        "INSERT INTO"
            + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
            + " VALUES(2,7,'alternate-owned-key',?,?,'202610order1',10,?,100,90,'2026-10-11')",
        operationType,
        payNo,
        ledgerState);
  }

  @Test
  void knownStatesAndOtherTransactionsFrozenFundsUseConditionalReadOnlySelects() {
    for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
      seed(state);
      otherDebit(7L, "TRYING", "20.00");
      fixture.update("UPDATE mall_user SET frozen_balance=frozen_balance+20");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification, state);
      assertTrue(report.account.frozenProjectionMatched());
      assertFalse(report.automaticActionsAllowed);
      assertEquals("TRYING".equals(state) ? "30.00" : "20.00", report.account.tryingFrozenTotal());
      assertEquals(1, tracked.connections);
      assertEquals("CONFIRMED".equals(state) ? 10 : 11, tracked.statements.size());
      assertEquals(before, facts());
      String accountSql =
          tracked.statements.stream()
              .filter(sql -> sql.contains("FROM MALL_USER"))
              .findFirst()
              .orElseThrow();
      assertFalse(accountSql.contains("*"));
      assertFalse(accountSql.contains("PASSWORD"));
      assertFalse(accountSql.contains("PHONE"));
      assertFalse(accountSql.contains("ROLE"));
    }
  }

  @Test
  void unknownAndEmptyCancelledBranchDoNotInventMoneyEvidence() {
    seed("UNKNOWN");
    var before = facts();
    tracked.checking = true;
    var report = inspect();
    assertEquals("BLOCKED_LOCAL_EVIDENCE", report.classification);
    assertEquals(8, tracked.statements.size());
    assertEquals(before, facts());
    seed("CANCELLED");
    fixture.update("DELETE FROM balance_tcc_debit");
    fixture.update("UPDATE tcc_fence_log SET status=4");
    before = facts();
    tracked.reset();
    tracked.checking = true;
    report = inspect();
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
    assertEquals("CANCELLED", report.tccState);
    assertEquals(11, tracked.statements.size());
    assertEquals(before, facts());
  }

  @Test
  void fixedRefundFullPartialAbsentAndOtherKeysDoNotMeanFinancialClosure() {
    for (String variant : List.of("full", "partial", "absent", "other")) {
      seed("CONFIRMED");
      if (!"absent".equals(variant)) seedRefund("partial".equals(variant) ? "5.00" : "10.00");
      if ("other".equals(variant))
        fixture.update("UPDATE balance_ledger SET operation_key='other-refund-key' WHERE id=2");
      fixture.update("UPDATE mall_user SET balance=200,status=0");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals(
          "partial".equals(variant) ? "BLOCKED_LOCAL_EVIDENCE" : "BLOCKED_EXTERNAL_EVIDENCE",
          report.classification,
          variant);
      assertEquals("DISABLED", report.account.status());
      assertTrue(report.otherRefundsNotInspected);
      assertEquals(List.of("PAY", "ORDER", "STOCK", "TC", "MQ"), report.externalSystemsNotQueried);
      assertEquals(List.of("full", "partial").contains(variant), report.refund.present());
      assertEquals(before, facts());
    }
  }

  @Test
  void originalBadFrozenRowsCannotBeHiddenBySumOrCaseInsensitiveStates() {
    for (String[] row :
        new String[][] {
          {"TRYING", "-1"},
          {"TRYING", "0"},
          {"trying", "10"},
          {"TRYING ", "10"},
          {"UNKNOWN", "10"},
          {"CONFIRMED", "-1"}
        }) {
      seed("CONFIRMED");
      otherDebit(7L, row[0], row[1]);
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals("LOCAL_CONFLICT", report.classification, java.util.Arrays.toString(row));
      assertTrue(report.reasonCodes.contains("FROZEN_SOURCE_ROWS_INVALID"));
      assertNull(report.account.frozenProjectionMatched());
      assertEquals(before, facts());
    }
    seed("CONFIRMED");
    otherDebit(8L, "UNKNOWN", "-1");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", inspect().classification);
    assertEquals(before, facts());
  }

  @Test
  void ownedLegacyNullRowsArePreservedAndReportedInsteadOfFiltered() {
    tracked.checking = false;
    fixture.execute(
        "ALTER TABLE balance_tcc_debit MODIFY state VARCHAR(16) NULL,MODIFY amount DECIMAL(18,2)"
            + " NULL");
    for (String column : List.of("state", "amount")) {
      seed("CONFIRMED");
      otherDebit(7L, "TRYING", "10");
      fixture.update("UPDATE balance_tcc_debit SET " + column + "=NULL WHERE id=2");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      var report = inspect();
      assertEquals("LOCAL_CONFLICT", report.classification);
      assertTrue(report.reasonCodes.contains("FROZEN_SOURCE_ROWS_INVALID"));
      assertEquals(before, facts());
    }
  }

  @Test
  void frozenProjectionAndRefundIdentityDeltaConflictsNeverWrite() {
    seed("CONFIRMED");
    fixture.update("UPDATE mall_user SET frozen_balance=1");
    var before = facts();
    tracked.checking = true;
    assertTrue(inspect().reasonCodes.contains("FROZEN_PROJECTION_CONFLICT"));
    assertEquals(before, facts());
    for (String mutation :
        List.of(
            "pay_no='PAY1'",
            "user_id=8",
            "order_no='202610ORDER1'",
            "operation_key='refund:PAY1'",
            "original_operation_key='PAY1'",
            "operation_type='refund'",
            "state='applied'",
            "amount=11",
            "balance_after=111",
            "balance_before=-1")) {
      seed("CONFIRMED");
      seedRefund("10.00");
      fixture.update("UPDATE balance_ledger SET " + mutation + " WHERE id=2");
      before = facts();
      tracked.reset();
      tracked.checking = true;
      assertEquals("LOCAL_CONFLICT", inspect().classification, mutation);
      assertEquals(before, facts());
    }
  }

  @Test
  void missingTableIsUnavailableAndNeverRebuiltByDiagnostics() {
    for (String table : List.of("user_tcc_receive", "balance_tcc_debit")) {
      tracked.checking = false;
      ensureFixtureTables();
      seed("CONFIRMED");
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
      assertEquals("USER_TCC_RECONCILIATION_UNAVAILABLE", failure.getCode());
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
  void receiveAliasOrMissingAndAuthenticationStopBeforeRemainingReads() {
    seed("CONFIRMED");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    assertEquals(
        403,
        assertThrows(BizException.class, () -> service.inspect(7L, "pay1", "other", "ops-secret"))
            .getHttpStatus());
    assertEquals(0, tracked.connections);
    for (String payNo : List.of("absent", "PAY1")) {
      tracked.reset();
      assertEquals(
          "absent".equals(payNo) ? 404 : 409,
          assertThrows(
                  BizException.class,
                  () -> service.inspect(7L, payNo, "cm18-reconciliation", "ops-secret"))
              .getHttpStatus());
      assertEquals(1, tracked.connections);
      assertEquals(1, tracked.statements.size());
      assertEquals(before, facts());
    }
    tracked.reset();
    assertEquals(
        409,
        assertThrows(
                BizException.class,
                () -> service.inspect(8L, "pay1", "cm18-reconciliation", "ops-secret"))
            .getHttpStatus());
    assertEquals(1, tracked.statements.size());
  }

  @Test
  void concurrentOwnedWriterCannotMixAccountFreezeOrNewRefundWithOriginalState() {
    seed("CONFIRMED");
    afterReceiveRead.set(
        () ->
            new TransactionTemplate(new DataSourceTransactionManager(dataSource))
                .executeWithoutResult(
                    status -> {
                      fixture.update("UPDATE mall_user SET balance=200,frozen_balance=20");
                      otherDebit(7L, "TRYING", "20.00");
                      seedRefund("10.00");
                    }));
    tracked.reset();
    tracked.checking = true;
    var report = inspect();
    assertEquals("BLOCKED_EXTERNAL_EVIDENCE", report.classification);
    assertEquals("90.00", report.account.balance());
    assertEquals("0.00", report.account.tryingFrozenTotal());
    assertFalse(report.refund.present());
    assertEquals(1, tracked.connections);
    assertEquals(10, tracked.statements.size());
    assertEquals(
        "200.00",
        fixture.queryForObject("SELECT CAST(balance AS CHAR) FROM mall_user", String.class));
    assertEquals(2, fixture.queryForObject("SELECT COUNT(*) FROM balance_ledger", Integer.class));
  }

  @Test
  void actualHttpNoStoreIgnoresClientKeysAndNeverLeaksCredentialsOrPii() throws Exception {
    seed("CONFIRMED");
    seedRefund("10.00");
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    var mvc =
        MockMvcBuilders.standaloneSetup(new UserTccReconciliationController(service))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    var response =
        mvc.perform(
                get("/api/internal/users/7/balance/tcc-reconciliation/pay1")
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret")
                    .param("xid", "wrong-client-xid")
                    .param("operationKey", "wrong-client-key")
                    .param("amount", "999"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.scope").value("USER_ACCOUNT_FROZEN_REFUND_OBSERVED_ONLY"))
            .andExpect(jsonPath("$.data.classification").value("BLOCKED_EXTERNAL_EVIDENCE"))
            .andExpect(jsonPath("$.data.automaticActionsAllowed").value(false))
            .andExpect(jsonPath("$.data.refund.identityMatched").value(true))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String secret :
        List.of(
            "private-tc:8091:original",
            "tcc-debit:pay1",
            "refund:pay1",
            "private-password",
            "private-phone",
            "private-role",
            "wrong-client-xid",
            "wrong-client-key",
            "ops-secret")) assertFalse(response.contains(secret), secret);
    assertEquals(before, facts());
    assertEquals(10, tracked.statements.size());
    assertEquals(1, tracked.connections);
  }

  /** 实际四来源NULL、非正/超精度/超容量通过原HTTP和三层诊断均拒绝且不写。 */
  @Test
  void originalAmountGatesRejectOwnedLegacyRowsThroughActualHttpAndDiagnostics() throws Exception {
    List<String> moneyTables =
        List.of("user_tcc_receive", "balance_debit_attempt", "balance_tcc_debit", "balance_ledger");
    tracked.checking = false;
    for (String table : moneyTables)
      fixture.execute("ALTER TABLE " + table + " MODIFY amount DECIMAL(20,3) NULL");
    var receives = session.getMapper(UserTccReceiveMapper.class);
    var attempts = session.getMapper(BalanceDebitAttemptMapper.class);
    var debits = session.getMapper(BalanceTccDebitMapper.class);
    var ledgers = session.getMapper(BalanceLedgerMapper.class);
    var fences = session.getMapper(TccFenceLogMapper.class);
    var users = session.getMapper(UserMapper.class);
    var queryTarget = new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
    var queryProxy = readProxy(queryTarget);
    var query = (UserTccStateQuery) queryProxy.getProxy();
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var bridge = new UserTccHttpService(resource, receiveStore, query, users, "ops-secret", true);
    var baseReader =
        (UserTccReconciliationReadService)
            readProxy(
                    new UserTccReconciliationReadService(
                        receives, attempts, debits, ledgers, fences, users))
                .getProxy();
    var baseService = new UserTccReconciliationService(baseReader, true, "ops-secret");
    var linkedReader =
        (UserLinkedRefundEvidenceReadService)
            readProxy(new UserLinkedRefundEvidenceReadService(baseReader, ledgers)).getProxy();
    var frozenReader =
        (UserFrozenOwnershipEvidenceReadService)
            readProxy(
                    new UserFrozenOwnershipEvidenceReadService(
                        baseReader, receives, attempts, debits, ledgers, fences))
                .getProxy();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new UserTccController(bridge),
                new UserTccReconciliationController(baseService),
                new UserLinkedRefundEvidenceController(
                    new UserLinkedRefundEvidenceService(baseService, linkedReader, true)),
                new UserFrozenOwnershipEvidenceController(
                    new UserFrozenOwnershipEvidenceService(baseService, frozenReader, true)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String stateRoute = "/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1";
    String diagnosticRoute = "/api/internal/users/7/balance/tcc-reconciliation/pay1";
    try {
      for (String table : moneyTables) {
        for (String raw :
            java.util.Arrays.asList(null, "0", "-10", "10.001", "10000000000000000.00")) {
          seed("CONFIRMED");
          fixture.update("UPDATE " + table + " SET amount=? WHERE id=1", raw);
          var before = facts();
          tracked.reset();
          tracked.checking = true;
          mvc.perform(
                  get(stateRoute)
                      .header("X-User-Id", "7")
                      .header("X-Internal-Service", "cloud-mall-pay")
                      .header("X-Internal-Callback-Token", "ops-secret")
                      .param("xid", "private-tc:8091:original"))
              .andExpect(status().isConflict())
              .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
          assertEquals(1, tracked.connections);
          assertTrue(tracked.statements.size() <= 5);
          assertEquals(before, facts());
          for (String suffix : List.of("", "/refund-evidence", "/frozen-ownership-evidence")) {
            tracked.reset();
            mvc.perform(
                    get(diagnosticRoute + suffix)
                        .header("X-Internal-Service", "cm18-reconciliation")
                        .header("X-Internal-Callback-Token", "ops-secret"))
                .andExpect(status().isConflict())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(
                    jsonPath("$.code")
                        .value(
                            "user_tcc_receive".equals(table)
                                ? "USER_TCC_RECONCILIATION_CONFLICT"
                                : "USER_TCC_STATE_CONFLICT"));
            assertEquals(1, tracked.connections);
            assertTrue(tracked.statements.size() <= 5);
            assertEquals(before, facts());
          }
        }
      }
      for (String raw : List.of("0", "-10", "10.001", "10000000000000000.00")) {
        seed("CONFIRMED");
        for (String table : moneyTables)
          fixture.update("UPDATE " + table + " SET amount=? WHERE id=1", raw);
        var before = facts();
        tracked.reset();
        tracked.checking = true;
        var failure =
            assertThrows(
                BizException.class,
                () -> query.read(7L, "tcc-debit:pay1", "private-tc:8091:original"));
        assertEquals(409, failure.getHttpStatus());
        assertEquals("USER_TCC_STATE_CONFLICT", failure.getCode());
        assertEquals(before, facts());
      }
      for (String state : List.of("UNKNOWN", "TRYING", "CONFIRMED", "CANCELLED")) {
        for (String raw : List.of("0.01", "1.000", "9999999999999999.99")) {
          seed(state);
          for (String table : moneyTables)
            fixture.update("UPDATE " + table + " SET amount=? WHERE id=1", raw);
          fixture.update(
              "UPDATE balance_tcc_debit SET balance_before=?,balance_after_try=0 WHERE id=1", raw);
          fixture.update(
              "UPDATE balance_ledger SET balance_before=?,balance_after=0 WHERE id=1", raw);
          var before = facts();
          tracked.reset();
          tracked.checking = true;
          assertEquals(state, query.read(7L, "tcc-debit:pay1", "private-tc:8091:original").state);
          assertEquals(1, tracked.connections);
          assertEquals(
              "UNKNOWN".equals(state) ? 4 : "CONFIRMED".equals(state) ? 6 : 7,
              tracked.statements.size());
          assertEquals(before, facts());
        }
      }
      verifyNoInteractions(resource, receiveStore);
    } finally {
      tracked.checking = false;
      seed("CONFIRMED");
      for (String table : moneyTables)
        fixture.execute("ALTER TABLE " + table + " MODIFY amount DECIMAL(18,2) NOT NULL");
      tracked.reset();
    }
  }

  /** 冻结/账本四余额字段及差额、原快照来源通过实际HTTP校验且不写。 */
  @Test
  void originalBalanceSnapshotsRejectOwnedLegacyRowsAndKeepCurrentAccountIndependent()
      throws Exception {
    tracked.checking = false;
    fixture.execute(
        "ALTER TABLE balance_tcc_debit MODIFY balance_before DECIMAL(20,3) NULL,MODIFY"
            + " balance_after_try DECIMAL(20,3) NULL");
    fixture.execute(
        "ALTER TABLE balance_ledger MODIFY balance_before DECIMAL(20,3) NULL,MODIFY balance_after"
            + " DECIMAL(20,3) NULL");
    var receives = session.getMapper(UserTccReceiveMapper.class);
    var attempts = session.getMapper(BalanceDebitAttemptMapper.class);
    var debits = session.getMapper(BalanceTccDebitMapper.class);
    var ledgers = session.getMapper(BalanceLedgerMapper.class);
    var fences = session.getMapper(TccFenceLogMapper.class);
    var users = session.getMapper(UserMapper.class);
    var queryTarget = new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
    var queryProxy = readProxy(queryTarget);
    var query = (UserTccStateQuery) queryProxy.getProxy();
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var bridge = new UserTccHttpService(resource, receiveStore, query, users, "ops-secret", true);
    var baseReader =
        (UserTccReconciliationReadService)
            readProxy(
                    new UserTccReconciliationReadService(
                        receives, attempts, debits, ledgers, fences, users))
                .getProxy();
    var baseService = new UserTccReconciliationService(baseReader, true, "ops-secret");
    var linkedReader =
        (UserLinkedRefundEvidenceReadService)
            readProxy(new UserLinkedRefundEvidenceReadService(baseReader, ledgers)).getProxy();
    var frozenReader =
        (UserFrozenOwnershipEvidenceReadService)
            readProxy(
                    new UserFrozenOwnershipEvidenceReadService(
                        baseReader, receives, attempts, debits, ledgers, fences))
                .getProxy();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new UserTccController(bridge),
                new UserTccReconciliationController(baseService),
                new UserLinkedRefundEvidenceController(
                    new UserLinkedRefundEvidenceService(baseService, linkedReader, true)),
                new UserFrozenOwnershipEvidenceController(
                    new UserFrozenOwnershipEvidenceService(baseService, frozenReader, true)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String stateRoute = "/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1";
    String diagnosticRoute = "/api/internal/users/7/balance/tcc-reconciliation/pay1";
    try {
      for (String[] field :
          new String[][] {
            {"balance_tcc_debit", "balance_before"}, {"balance_tcc_debit", "balance_after_try"},
            {"balance_ledger", "balance_before"}, {"balance_ledger", "balance_after"}
          }) {
        for (String raw : java.util.Arrays.asList(null, "-1", "100.001", "10000000000000000.00")) {
          seed("CONFIRMED");
          fixture.update("UPDATE " + field[0] + " SET " + field[1] + "=? WHERE id=1", raw);
          assertPersistedConflictThroughFourRoutes(mvc, stateRoute, diagnosticRoute, 4, 5);
        }
      }
      for (String mutation :
          List.of(
              "UPDATE balance_tcc_debit SET balance_after_try=91 WHERE id=1",
              "UPDATE balance_ledger SET balance_after=91 WHERE id=1",
              "UPDATE balance_ledger SET balance_before=200,balance_after=190 WHERE id=1")) {
        seed("CONFIRMED");
        fixture.update(mutation);
        assertPersistedConflictThroughFourRoutes(mvc, stateRoute, diagnosticRoute, 4, 5);
      }
      seed("CONFIRMED");
      fixture.update(
          "UPDATE balance_tcc_debit SET balance_before=10,balance_after_try=0 WHERE id=1");
      fixture.update("UPDATE balance_ledger SET balance_before=10,balance_after=0 WHERE id=1");
      fixture.update("UPDATE mall_user SET balance=200");
      var before = facts();
      tracked.reset();
      tracked.checking = true;
      mvc.perform(
              get(stateRoute)
                  .header("X-User-Id", "7")
                  .header("X-Internal-Service", "cloud-mall-pay")
                  .header("X-Internal-Callback-Token", "ops-secret")
                  .param("xid", "private-tc:8091:original"))
          .andExpect(status().isOk())
          .andExpect(jsonPath("$.data.state").value("CONFIRMED"))
          .andExpect(jsonPath("$.data.ledgerApplied").value(true));
      assertEquals(6, tracked.statements.size());
      assertEquals(1, tracked.connections);
      assertEquals(before, facts());
      tracked.reset();
      mvc.perform(
              get(diagnosticRoute)
                  .header("X-Internal-Service", "cm18-reconciliation")
                  .header("X-Internal-Callback-Token", "ops-secret"))
          .andExpect(status().isOk())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.data.classification").value("BLOCKED_EXTERNAL_EVIDENCE"))
          .andExpect(jsonPath("$.data.account.balance").value("200.00"));
      assertEquals(10, tracked.statements.size());
      assertEquals(1, tracked.connections);
      assertEquals(before, facts());
      verifyNoInteractions(resource, receiveStore);
    } finally {
      tracked.checking = false;
      seed("CONFIRMED");
      fixture.execute(
          "ALTER TABLE balance_tcc_debit MODIFY balance_before DECIMAL(18,2) NOT NULL,MODIFY"
              + " balance_after_try DECIMAL(18,2) NOT NULL");
      fixture.execute(
          "ALTER TABLE balance_ledger MODIFY balance_before DECIMAL(18,2) NOT NULL,MODIFY"
              + " balance_after DECIMAL(18,2) NOT NULL");
      tracked.reset();
    }
  }

  /** 真实0900 CI别名及owned PAD SPACE遗留候选不能充当原Fence。 */
  @Test
  void observedFenceSourceAliasesAreConflictWithoutChangingExpirySemantics() throws Exception {
    var receives = session.getMapper(UserTccReceiveMapper.class);
    var attempts = session.getMapper(BalanceDebitAttemptMapper.class);
    var debits = session.getMapper(BalanceTccDebitMapper.class);
    var ledgers = session.getMapper(BalanceLedgerMapper.class);
    var fences = session.getMapper(TccFenceLogMapper.class);
    var users = session.getMapper(UserMapper.class);
    var queryTarget = new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
    var queryProxy = readProxy(queryTarget);
    var query = (UserTccStateQuery) queryProxy.getProxy();
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var bridge = new UserTccHttpService(resource, receiveStore, query, users, "ops-secret", true);
    var baseReader =
        (UserTccReconciliationReadService)
            readProxy(
                    new UserTccReconciliationReadService(
                        receives, attempts, debits, ledgers, fences, users))
                .getProxy();
    var baseService = new UserTccReconciliationService(baseReader, true, "ops-secret");
    var linkedReader =
        (UserLinkedRefundEvidenceReadService)
            readProxy(new UserLinkedRefundEvidenceReadService(baseReader, ledgers)).getProxy();
    var frozenReader =
        (UserFrozenOwnershipEvidenceReadService)
            readProxy(
                    new UserFrozenOwnershipEvidenceReadService(
                        baseReader, receives, attempts, debits, ledgers, fences))
                .getProxy();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new UserTccController(bridge),
                new UserTccReconciliationController(baseService),
                new UserLinkedRefundEvidenceController(
                    new UserLinkedRefundEvidenceService(baseService, linkedReader, true)),
                new UserFrozenOwnershipEvidenceController(
                    new UserFrozenOwnershipEvidenceService(baseService, frozenReader, true)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String stateRoute = "/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1";
    String diagnosticRoute = "/api/internal/users/7/balance/tcc-reconciliation/pay1";
    String originalCollation =
        (String)
            fixture
                .queryForMap("SHOW FULL COLUMNS FROM tcc_fence_log WHERE Field='xid'")
                .get("Collation");
    assertEquals("utf8mb4_0900_ai_ci", originalCollation);
    try {
      for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
        for (String alias : List.of("PRIVATE-TC:8091:ORIGINAL", "prívate-tc:8091:original")) {
          seed(state);
          fixture.update("UPDATE tcc_fence_log SET xid=?", alias);
          assertEquals(
              1,
              fixture.queryForObject(
                  "SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND branch_id=11",
                  Integer.class,
                  "private-tc:8091:original"));
          assertPersistedConflictThroughFourRoutes(
              mvc,
              stateRoute,
              diagnosticRoute,
              "CONFIRMED".equals(state) ? 6 : 7,
              "CONFIRMED".equals(state) ? 7 : 8);
        }
      }
      tracked.checking = false;
      fixture.execute(
          "ALTER TABLE tcc_fence_log MODIFY xid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE"
              + " utf8mb4_unicode_ci NOT NULL");
      for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
        seed(state);
        fixture.update("UPDATE tcc_fence_log SET xid='private-tc:8091:original '");
        assertEquals(
            1,
            fixture.queryForObject(
                "SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND branch_id=11",
                Integer.class,
                "private-tc:8091:original"));
        assertPersistedConflictThroughFourRoutes(
            mvc,
            stateRoute,
            diagnosticRoute,
            "CONFIRMED".equals(state) ? 6 : 7,
            "CONFIRMED".equals(state) ? 7 : 8);
      }
      tracked.checking = false;
      fixture.execute(
          "ALTER TABLE tcc_fence_log MODIFY xid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE"
              + " utf8mb4_0900_ai_ci NOT NULL");
      for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
        seed(state);
        fixture.update("DELETE FROM tcc_fence_log");
        var before = facts();
        tracked.reset();
        tracked.checking = true;
        var request =
            get(stateRoute)
                .header("X-User-Id", "7")
                .header("X-Internal-Service", "cloud-mall-pay")
                .header("X-Internal-Callback-Token", "ops-secret")
                .param("xid", "private-tc:8091:original");
        if ("TRYING".equals(state))
          mvc.perform(request)
              .andExpect(status().isConflict())
              .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
        else
          mvc.perform(request)
              .andExpect(status().isOk())
              .andExpect(jsonPath("$.data.state").value(state));
        assertEquals(1, tracked.connections);
        assertEquals("CONFIRMED".equals(state) ? 6 : 7, tracked.statements.size());
        assertEquals(before, facts());
      }
      verifyNoInteractions(resource, receiveStore);
    } finally {
      tracked.checking = false;
      seed("CONFIRMED");
      fixture.execute(
          "ALTER TABLE tcc_fence_log MODIFY xid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE"
              + " utf8mb4_0900_ai_ci NOT NULL");
      tracked.reset();
    }
  }

  /** 原接收标记/固定键损坏早停，合法CLAIMED未bind原分支保留。 */
  @Test
  void originalReceiveSourceContractIsCheckedBeforeAllFundsQueries() throws Exception {
    tracked.checking = false;
    fixture.execute(
        "ALTER TABLE user_tcc_receive MODIFY state VARCHAR(32) NULL,MODIFY order_no VARCHAR(64)"
            + " NULL");
    var receives = session.getMapper(UserTccReceiveMapper.class);
    var attempts = session.getMapper(BalanceDebitAttemptMapper.class);
    var debits = session.getMapper(BalanceTccDebitMapper.class);
    var ledgers = session.getMapper(BalanceLedgerMapper.class);
    var fences = session.getMapper(TccFenceLogMapper.class);
    var users = session.getMapper(UserMapper.class);
    var queryTarget = new UserTccStateQuery(receives, attempts, debits, ledgers, fences);
    var queryProxy = readProxy(queryTarget);
    var query = (UserTccStateQuery) queryProxy.getProxy();
    var resource = mock(BalanceTccDebitLocalResource.class);
    var receiveStore = mock(UserTccReceiveStore.class);
    var bridge = new UserTccHttpService(resource, receiveStore, query, users, "ops-secret", true);
    var baseReader =
        (UserTccReconciliationReadService)
            readProxy(
                    new UserTccReconciliationReadService(
                        receives, attempts, debits, ledgers, fences, users))
                .getProxy();
    var baseService = new UserTccReconciliationService(baseReader, true, "ops-secret");
    var linkedReader =
        (UserLinkedRefundEvidenceReadService)
            readProxy(new UserLinkedRefundEvidenceReadService(baseReader, ledgers)).getProxy();
    var frozenReader =
        (UserFrozenOwnershipEvidenceReadService)
            readProxy(
                    new UserFrozenOwnershipEvidenceReadService(
                        baseReader, receives, attempts, debits, ledgers, fences))
                .getProxy();
    MockMvc mvc =
        MockMvcBuilders.standaloneSetup(
                new UserTccController(bridge),
                new UserTccReconciliationController(baseService),
                new UserLinkedRefundEvidenceController(
                    new UserLinkedRefundEvidenceService(baseService, linkedReader, true)),
                new UserFrozenOwnershipEvidenceController(
                    new UserFrozenOwnershipEvidenceService(baseService, frozenReader, true)))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String stateRoute = "/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1";
    String diagnosticRoute = "/api/internal/users/7/balance/tcc-reconciliation/pay1";
    try {
      for (String mutation :
          List.of(
              "state=NULL",
              "state='claimed'",
              "state='CLAIMED '",
              "state='APPLIED'",
              "order_no=NULL",
              "order_no=' '",
              "ledger_key='other-ledger'",
              "branch_id=0",
              "branch_id=-1")) {
        seed("UNKNOWN");
        fixture.update("UPDATE user_tcc_receive SET " + mutation + " WHERE id=1");
        assertReceiveConflictThroughFourRoutes(mvc, stateRoute, diagnosticRoute);
      }
      for (String state : List.of("TRYING", "CONFIRMED", "CANCELLED")) {
        for (String variant : List.of("operation", "ledger")) {
          seed(state);
          String actualStateRoute = stateRoute;
          if ("operation".equals(variant)) {
            fixture.update(
                "UPDATE user_tcc_receive SET operation_key='tcc-debit:other' WHERE id=1");
            fixture.update(
                "UPDATE balance_debit_attempt SET operation_key='tcc-debit:other' WHERE id=1");
            fixture.update(
                "UPDATE balance_tcc_debit SET operation_key='tcc-debit:other' WHERE id=1");
            actualStateRoute = "/api/internal/users/7/balance/tcc-debits/tcc-debit:other";
          } else {
            fixture.update("UPDATE user_tcc_receive SET ledger_key='other-ledger' WHERE id=1");
            fixture.update("UPDATE balance_debit_attempt SET ledger_key='other-ledger' WHERE id=1");
            fixture.update("UPDATE balance_ledger SET operation_key='other-ledger' WHERE id=1");
          }
          assertReceiveConflictThroughFourRoutes(mvc, actualStateRoute, diagnosticRoute);
        }
      }
      for (String state : List.of("UNKNOWN", "TRYING", "CONFIRMED", "CANCELLED")) {
        seed(state);
        fixture.update("UPDATE user_tcc_receive SET branch_id=NULL WHERE id=1");
        var before = facts();
        tracked.reset();
        tracked.checking = true;
        mvc.perform(
                get(stateRoute)
                    .header("X-User-Id", "7")
                    .header("X-Internal-Service", "cloud-mall-pay")
                    .header("X-Internal-Callback-Token", "ops-secret")
                    .param("xid", "private-tc:8091:original"))
            .andExpect(status().isOk())
            .andExpect(jsonPath("$.data.state").value(state));
        assertEquals(
            "UNKNOWN".equals(state) ? 4 : "CONFIRMED".equals(state) ? 6 : 7,
            tracked.statements.size());
        assertEquals(1, tracked.connections);
        assertEquals(before, facts());
        tracked.reset();
        mvc.perform(
                get(diagnosticRoute)
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(
                jsonPath("$.data.classification")
                    .value(
                        "UNKNOWN".equals(state)
                            ? "BLOCKED_LOCAL_EVIDENCE"
                            : "BLOCKED_EXTERNAL_EVIDENCE"));
        assertEquals(
            "UNKNOWN".equals(state) ? 8 : "CONFIRMED".equals(state) ? 10 : 11,
            tracked.statements.size());
        assertEquals(1, tracked.connections);
        assertEquals(before, facts());
      }
      verifyNoInteractions(resource, receiveStore);
    } finally {
      tracked.checking = false;
      seed("CONFIRMED");
      fixture.execute(
          "ALTER TABLE user_tcc_receive MODIFY state VARCHAR(16) NOT NULL,MODIFY order_no"
              + " VARCHAR(64) NOT NULL");
      tracked.reset();
    }
  }

  /** 原state和已有诊断原错误码保留，坏receive只有一个SELECT/只读RR且不改行。 */
  private static void assertReceiveConflictThroughFourRoutes(
      MockMvc mvc, String stateRoute, String diagnosticRoute) throws Exception {
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    mvc.perform(
            get(stateRoute)
                .header("X-User-Id", "7")
                .header("X-Internal-Service", "cloud-mall-pay")
                .header("X-Internal-Callback-Token", "ops-secret")
                .param("xid", "private-tc:8091:original"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
    assertEquals(1, tracked.statements.size());
    assertEquals(1, tracked.connections);
    assertEquals(before, facts());
    for (String suffix : List.of("", "/refund-evidence", "/frozen-ownership-evidence")) {
      tracked.reset();
      mvc.perform(
              get(diagnosticRoute + suffix)
                  .header("X-Internal-Service", "cm18-reconciliation")
                  .header("X-Internal-Callback-Token", "ops-secret"))
          .andExpect(status().isConflict())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.code").value("USER_TCC_RECONCILIATION_CONFLICT"));
      assertEquals(1, tracked.statements.size());
      assertEquals(1, tracked.connections);
      assertEquals(before, facts());
    }
  }

  /** 坏历史沿原409与诊断no-store传播，单只读RR且全部行/键/时间不变。 */
  private static void assertPersistedConflictThroughFourRoutes(
      MockMvc mvc,
      String stateRoute,
      String diagnosticRoute,
      int expectedStateSelects,
      int expectedDiagnosticSelects)
      throws Exception {
    var before = facts();
    tracked.reset();
    tracked.checking = true;
    mvc.perform(
            get(stateRoute)
                .header("X-User-Id", "7")
                .header("X-Internal-Service", "cloud-mall-pay")
                .header("X-Internal-Callback-Token", "ops-secret")
                .param("xid", "private-tc:8091:original"))
        .andExpect(status().isConflict())
        .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
    assertEquals(1, tracked.connections);
    assertEquals(expectedStateSelects, tracked.statements.size());
    assertEquals(before, facts());
    for (String suffix : List.of("", "/refund-evidence", "/frozen-ownership-evidence")) {
      tracked.reset();
      mvc.perform(
              get(diagnosticRoute + suffix)
                  .header("X-Internal-Service", "cm18-reconciliation")
                  .header("X-Internal-Callback-Token", "ops-secret"))
          .andExpect(status().isConflict())
          .andExpect(header().string("Cache-Control", "no-store"))
          .andExpect(jsonPath("$.code").value("USER_TCC_STATE_CONFLICT"));
      assertEquals(1, tracked.connections);
      assertEquals(expectedDiagnosticSelects, tracked.statements.size());
      assertEquals(before, facts());
    }
  }

  /** 仅由owned数据源提供实际readOnly RR代理，不注入RM或启动应用。 */
  private static ProxyFactory readProxy(Object target) {
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(tracked), new AnnotationTransactionAttributeSource()));
    return proxy;
  }

  /** 认证通过的实际诊断请求。 */
  private UserTccReconciliationVO inspect() {
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
