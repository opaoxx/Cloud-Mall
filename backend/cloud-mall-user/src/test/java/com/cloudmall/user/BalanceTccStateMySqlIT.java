package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.user.config.BalanceLedgerTableInitializer;
import com.cloudmall.user.config.BalanceTccTableInitializer;
import com.cloudmall.user.config.UserTableInitializer;
import com.cloudmall.user.controller.UserController;
import com.cloudmall.user.domain.dto.BalanceDebitRequestDTO;
import com.cloudmall.user.domain.dto.BalanceRefundRequestDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitFinalizeDTO;
import com.cloudmall.user.domain.dto.BalanceTccDebitTryDTO;
import com.cloudmall.user.service.BalanceTccDebitService;
import com.cloudmall.user.service.impl.BalanceTccDebitServiceImpl;
import com.cloudmall.user.service.impl.UserServiceImpl;
import com.zaxxer.hikari.HikariDataSource;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.annotation.MapperScan;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.AopTestUtils;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;

/** 显式运行的 MySQL 资金事务验收；只写随机命名的独立测试库，结束后精确清理。 */
class BalanceTccStateMySqlIT {
  /** 原库只用于读取表结构和创建测试库，不执行资金写入。 */
  private static JdbcTemplate source;

  /** 原库完整表清单/checksum，用于新增缺证验收的无副作用核验。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 由测试生成并严格校验的临时库名。 */
  private static String testDatabase;

  /** 整类共享最多6个物理连接的owned池，允许真实并发且避免每条SQL新socket。 */
  private static DataSource testDataSource;

  /** 测试上下文，提供真实 Mapper 和 Spring 事务代理。 */
  private AnnotationConfigApplicationContext context;

  /** 独立测试库夹具访问入口。 */
  private JdbcTemplate fixture;

  /** 生产 TCC 本地资金服务的事务代理。 */
  private BalanceTccDebitService service;

  /** 生产 Legacy 扣款/退款服务的事务代理。 */
  private UserServiceImpl legacy;

  @BeforeAll
  static void createIsolatedDatabase() throws Exception {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?")) {
      throw new IllegalStateException("显式 IT 需要指向 cloudmall_user 的 cloudmall.mysql.test.url");
    }
    String username = System.getProperty("cloudmall.mysql.test.username", "root");
    String password = System.getProperty("cloudmall.mysql.test.password", "root");
    source = new JdbcTemplate(new DriverManagerDataSource(sourceUrl, username, password));
    sourceFingerprint = originalFingerprint();
    testDatabase = "cm14_tcc_it_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + testDatabase + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    HikariDataSource ownedPool = new HikariDataSource();
    ownedPool.setJdbcUrl(sourceUrl.replace("/cloudmall_user", "/" + testDatabase));
    ownedPool.setUsername(username);
    ownedPool.setPassword(password);
    ownedPool.setMaximumPoolSize(6);
    ownedPool.setMinimumIdle(1);
    ownedPool.setPoolName(testDatabase);
    testDataSource = ownedPool;
    JdbcTemplate setup = new JdbcTemplate(testDataSource);
    try {
      for (String table : List.of("mall_user", "balance_ledger", "user_address")) {
        String definition =
            (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table");
        setup.execute(definition);
      }
      migrate();
    } catch (Exception failure) {
      dropIsolatedDatabase();
      throw failure;
    }
  }

  @AfterAll
  static void dropIsolatedDatabase() {
    if (source != null
        && testDatabase != null
        && testDatabase.matches("cm14_tcc_it_[0-9a-f]{32}")) {
      try {
        if (testDataSource instanceof HikariDataSource ownedPool) {
          try {
            if (ownedPool.getHikariPoolMXBean() != null)
              assertEquals(0, ownedPool.getHikariPoolMXBean().getActiveConnections());
          } finally {
            ownedPool.close();
          }
          assertTrue(ownedPool.isClosed());
          System.out.println("CM16T113 owned JDBC pool closed");
        }
        assertEquals(sourceFingerprint, originalFingerprint());
        System.out.println("CM16T18 original User tables/checksums unchanged");
      } finally {
        source.execute("DROP DATABASE IF EXISTS " + testDatabase);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                testDatabase));
        System.out.println("CM16T18 fixture dropped: " + testDatabase);
      }
    }
  }

  @BeforeEach
  void createTransactionalServices() {
    fixture = new JdbcTemplate(testDataSource);
    fixture.execute("DROP TRIGGER IF EXISTS reject_test_ledger");
    for (String table :
        List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger", "mall_user")) {
      fixture.update("DELETE FROM " + table);
    }
    for (long userId : List.of(7L, 8L)) {
      fixture.update(
          "INSERT INTO"
              + " mall_user(id,username,password_hash,role,balance,status,created_at,updated_at)"
              + " VALUES(?,?,'unused','USER',100,1,NOW(3),NOW(3))",
          userId,
          "state-test-" + userId);
    }
    startContext();
  }

  /** 直接注册生产初始化器和服务，验证依赖顺序及事务注解。 */
  private void startContext() {
    context = new AnnotationConfigApplicationContext();
    context.getEnvironment().setActiveProfiles("cm14-local-it");
    context
        .getEnvironment()
        .getPropertySources()
        .addFirst(
            new MapPropertySource("tcc-test", Map.of("cloudmall.user.tcc-debit.enabled", "true")));
    context.register(
        Beans.class,
        BalanceTccDebitServiceImpl.class,
        UserController.class,
        BalanceTccTableInitializer.class,
        BalanceLedgerTableInitializer.class,
        UserTableInitializer.class);
    context.refresh();
    service = context.getBean(BalanceTccDebitService.class);
    legacy = context.getBean(UserServiceImpl.class);
  }

  @AfterEach
  void closeContext() {
    if (context != null) context.close();
  }

  /** CI Fence别名不得触发真实官方更新和本地资金确认/释放，拒绝保留五表事实。 */
  @Test
  void foreignFenceAliasesCannotDriveRealLocalCommitOrRollback() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (boolean committing : List.of(false, true)) {
      for (String variant : List.of("case", "accent", "space")) {
        assertions.add(
            () -> {
              resetFenceIdentityFacts();
              var original = request("fence-identity", 7L, "10.00");
              service.tryDebit(original);
              String alias =
                  switch (variant) {
                    case "case" -> original.xid.toUpperCase(java.util.Locale.ROOT);
                    case "accent" -> original.xid.replace("test", "tést");
                    case "space" -> original.xid + " ";
                    default -> throw new AssertionError(variant);
                  };
              insertOwnedFence(alias, original.branchId, 1);
              var before = fundsAndFenceFacts();
              withOwnedOfficialFence(
                  () -> {
                    var resource = localFenceResource();
                    var incoming = localFenceContext(original);
                    org.junit.jupiter.api.Assertions.assertFalse(
                        committing ? resource.commit(incoming) : resource.rollback(incoming),
                        variant);
                    assertEquals(before, fundsAndFenceFacts());
                  });
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 原exact Fence仍走实际资金和官方状态，缺Fence终态重放/空回滚不制造新资金。 */
  @Test
  void exactFenceAndMissingFenceReplayKeepLocalLifecycle() throws Exception {
    for (boolean committing : List.of(false, true)) {
      resetFenceIdentityFacts();
      var original = request("fence-healthy", 7L, "10.00");
      service.tryDebit(original);
      insertOwnedFence(original.xid, original.branchId, 1);
      withOwnedOfficialFence(
          () -> {
            var resource = localFenceResource();
            var incoming = localFenceContext(original);
            assertTrue(committing ? resource.commit(incoming) : resource.rollback(incoming));
            assertEquals(
                committing ? "CONFIRMED" : "CANCELLED",
                fixture.queryForObject(
                    "SELECT state FROM balance_debit_attempt WHERE pay_no=?",
                    String.class,
                    original.payNo));
            assertFunds(7L, committing ? "90.00" : "100.00", "0.00");
            assertEquals(committing ? 1 : 0, ledgerCount());
            var beforeReplay = fundsAndFenceFacts();
            assertTrue(committing ? resource.commit(incoming) : resource.rollback(incoming));
            assertEquals(beforeReplay, fundsAndFenceFacts());
            fixture.update("DELETE FROM tcc_fence_log");
            var afterFixtureDelete = fundsAndFenceFacts();
            assertTrue(committing ? resource.commit(incoming) : resource.rollback(incoming));
            assertEquals(afterFixtureDelete, fundsAndFenceFacts());
          });
    }
    resetFenceIdentityFacts();
    var empty = request("fence-empty", 7L, "10.00");
    withOwnedOfficialFence(
        () -> {
          var resource = localFenceResource();
          var incoming = localFenceContext(empty);
          assertTrue(resource.rollback(incoming));
          assertEquals(
              "CANCELLED",
              fixture.queryForObject("SELECT state FROM balance_debit_attempt", String.class));
          assertEquals(
              4, fixture.queryForObject("SELECT status FROM tcc_fence_log", Integer.class));
          assertFunds(7L, "100.00", "0.00");
          assertEquals(0, ledgerCount());
        });
  }

  /** 仅测试owned行重置；Fence使用PAD SPACE CI以实际复现三种SQL别名。 */
  private void resetFenceIdentityFacts() {
    context.getBean(com.cloudmall.user.mapper.TccFenceLogMapper.class).ensureTable();
    fixture.update("DELETE FROM tcc_fence_log");
    fixture.execute(
        "ALTER TABLE tcc_fence_log MODIFY xid VARCHAR(128) CHARACTER SET utf8mb4 COLLATE"
            + " utf8mb4_unicode_ci NOT NULL");
    for (String table : List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
      fixture.update("DELETE FROM " + table);
    fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
  }

  /** 原生产Mapper与真实资金事务代理，不注册RM或绑定全局XID。 */
  private com.cloudmall.user.service.impl.BalanceTccDebitLocalResourceImpl localFenceResource() {
    return new com.cloudmall.user.service.impl.BalanceTccDebitLocalResourceImpl(
        context.getBean(PlatformTransactionManager.class),
        new com.cloudmall.user.service.impl.BalanceTccDebitBusinessCallback(service),
        context.getBean(com.cloudmall.user.mapper.TccFenceLogMapper.class),
        context.getBean(com.cloudmall.user.mapper.BalanceDebitAttemptMapper.class),
        context.getBean(com.cloudmall.user.mapper.BalanceTccResourceEvidenceMapper.class));
  }

  /** 受信任本地分支载荷，context完整identity与原资金请求一致。 */
  private io.seata.rm.tcc.api.BusinessActionContext localFenceContext(
      BalanceTccDebitTryDTO original) {
    var branch = new com.cloudmall.user.domain.dto.BalanceTccBranchDTO();
    branch.userId = original.userId;
    branch.payNo = original.payNo;
    branch.orderNo = original.orderNo;
    branch.amount = original.amount;
    var incoming = new io.seata.rm.tcc.api.BusinessActionContext();
    incoming.setXid(original.xid);
    incoming.setBranchId(original.branchId);
    incoming.setActionName(com.cloudmall.user.service.BalanceTccDebitLocalResource.ACTION_NAME);
    incoming.setActionContext(Map.of("request", branch));
    return incoming;
  }

  /** Fence夹具写仅owned固定表，不混入原业务数据。 */
  private void insertOwnedFence(String xid, Long branchId, int status) {
    fixture.update(
        "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
            + " VALUES(?,?,?,?,NOW(3),NOW(3))",
        xid,
        branchId,
        com.cloudmall.user.service.BalanceTccDebitLocalResource.ACTION_NAME,
        status);
  }

  /** Fence与原四资金表同快照核验，含原XID/状态/时间及用户余额。 */
  private List<List<Map<String, Object>>> fundsAndFenceFacts() {
    var values = new ArrayList<List<Map<String, Object>>>(allFundsFacts());
    values.add(fixture.queryForList("SELECT * FROM tcc_fence_log ORDER BY xid,branch_id"));
    return values;
  }

  /** 绑定官方静态依赖只到owned数据源，finally恢复原值；不调用prepare或启动清理调度。 */
  private void withOwnedOfficialFence(org.junit.jupiter.api.function.Executable action)
      throws Exception {
    Class<?> handler = io.seata.rm.tcc.TCCFenceHandler.class;
    DataSource previousDataSource =
        (DataSource) ReflectionTestUtils.getField(handler, "dataSource");
    var previousTemplate =
        (org.springframework.transaction.support.TransactionTemplate)
            ReflectionTestUtils.getField(handler, "transactionTemplate");
    var manager = new DataSourceTransactionManager(testDataSource);
    var ownedTemplate = new org.springframework.transaction.support.TransactionTemplate(manager);
    io.seata.rm.tcc.TCCFenceHandler.setDataSource(testDataSource);
    io.seata.rm.tcc.TCCFenceHandler.setTransactionTemplate(ownedTemplate);
    try {
      action.execute();
    } catch (Exception failure) {
      throw failure;
    } catch (Throwable failure) {
      throw new AssertionError(failure);
    } finally {
      io.seata.rm.tcc.TCCFenceHandler.setDataSource(previousDataSource);
      io.seata.rm.tcc.TCCFenceHandler.setTransactionTemplate(previousTemplate);
      assertSame(previousDataSource, ReflectionTestUtils.getField(handler, "dataSource"));
      assertSame(previousTemplate, ReflectionTestUtils.getField(handler, "transactionTemplate"));
      var queue =
          (java.util.concurrent.BlockingQueue<?>)
              ReflectionTestUtils.getField(handler, "LOG_QUEUE");
      assertTrue(queue.isEmpty(), "本测试不调用prepare，不产生官方异步清理任务");
    }
  }

  /** 已观察Legacy归属必须复制原扣款事实，矛盾元组不能触发真实新退款。 */
  @Test
  void activeFreshRefundRejectsUnprovenLegacyOwnerWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mutation :
        List.of(
            "state", "user", "pay", "order", "operation", "ledger", "amount", "xid", "branch")) {
      assertions.add(
          () -> {
            for (String table :
                List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
              fixture.update("DELETE FROM " + table);
            fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
            setEnabled(true);
            var original = request("active-refund-owner-" + mutation, 7L, "10.00");
            debitLegacy(original.payNo, "legacy-owner-original-key", 7L, "10.00");
            switch (mutation) {
              case "state" -> fixture.update("UPDATE balance_debit_attempt SET state='REJECTED'");
              case "user" -> fixture.update("UPDATE balance_debit_attempt SET user_id=8");
              case "pay" -> fixture.update("UPDATE balance_debit_attempt SET pay_no=UPPER(pay_no)");
              case "order" ->
                  fixture.update("UPDATE balance_debit_attempt SET order_no='other-order'");
              case "operation" ->
                  fixture.update(
                      "UPDATE balance_debit_attempt SET operation_key='other-operation'");
              case "ledger" ->
                  fixture.update("UPDATE balance_debit_attempt SET ledger_key='other-ledger'");
              case "amount" -> fixture.update("UPDATE balance_debit_attempt SET amount=0");
              case "xid" -> fixture.update("UPDATE balance_debit_attempt SET xid='unexpected-xid'");
              case "branch" -> fixture.update("UPDATE balance_debit_attempt SET branch_id=99");
              default -> throw new AssertionError(mutation);
            }
            var refund = refundRequest(original, "active-refund-owner-request", "1.00");
            refund.originalOperationKey = "legacy-owner-original-key";
            var before = allFundsFacts();
            var response =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        context.getBean(UserController.class))
                    .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                    .build()
                    .perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/internal/users/7/balance/refunds")
                            .header("X-User-Id", "7")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(refund)))
                    .andReturn();
            org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(409, response.getResponse().getStatus(), mutation),
                () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                () -> {
                  var failure =
                      assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                  assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                  assertEquals(409, failure.getHttpStatus());
                },
                () -> assertEquals(before, allFundsFacts()));
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 同pay任意接收事实使Legacy历史待核，双接口与有/无归属历史均不cache或写五表。 */
  @Test
  void legacyDebitReplayRejectsObservedReceiveWithoutWriting() {
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : List.of("debits", "compat")) {
      for (String history : List.of("APPLIED", "REJECTED", "APPLIED-missing", "REJECTED-missing")) {
        for (String variant :
            List.of(
                "branch-null",
                "branch-known",
                "unknown-state",
                "pay-alias",
                "foreign-user",
                "bad-keys")) {
          checks.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                var request = new BalanceDebitRequestDTO();
                request.payNo = "replay-receive-" + entry + "-" + history + "-" + variant;
                request.operationKey =
                    "compat".equals(entry) ? request.payNo : "custom-" + request.payNo;
                request.orderNo = "compat".equals(entry) ? request.payNo : "order-" + request.payNo;
                request.amount =
                    new BigDecimal(history.startsWith("REJECTED") ? "101.00" : "10.00");
                var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                compatible.paymentKey = request.payNo;
                compatible.amount = request.amount;
                org.junit.jupiter.api.function.Executable debit =
                    () -> {
                      if ("compat".equals(entry)) legacy.debit(7L, 7L, compatible);
                      else legacy.debitInternal(7L, 7L, request);
                    };
                if (history.startsWith("REJECTED"))
                  assertEquals("PAYMENT_FAILED", assertThrows(BizException.class, debit).getCode());
                else debit.execute();
                if (history.endsWith("missing"))
                  fixture.update("DELETE FROM balance_debit_attempt");
                try {
                  fixture.update(
                      "INSERT INTO"
                          + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
                          + " VALUES(-118,?,'replay-claimed-xid',?,?,?,10,?,?,?,NOW(3),NOW(3))",
                      "pay-alias".equals(variant)
                          ? request.payNo.toUpperCase(java.util.Locale.ROOT)
                          : request.payNo,
                      "branch-known".equals(variant) ? 1L : null,
                      "foreign-user".equals(variant) ? 8L : 7L,
                      request.orderNo,
                      "bad-keys".equals(variant) ? "wrong-operation" : "tcc-debit:" + request.payNo,
                      "bad-keys".equals(variant) ? "wrong-ledger" : request.payNo,
                      "unknown-state".equals(variant) ? "UNKNOWN" : "CLAIMED");
                  var before = allFundsFacts();
                  var receiveBefore =
                      fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
                  var cache = context.getBean(StringRedisTemplate.class);
                  org.mockito.Mockito.clearInvocations(cache);
                  var response =
                      "compat".equals(entry)
                          ? postCompatibleLegacyDebit(compatible)
                          : postLegacyDebit(request);
                  org.junit.jupiter.api.Assertions.assertAll(
                      () ->
                          assertEquals(
                              409,
                              response.getResponse().getStatus(),
                              entry + ":" + history + ":" + variant),
                      () -> assertHttpCode(response, "BALANCE_DEBIT_UNKNOWN"),
                      () -> {
                        var failure = assertThrows(BizException.class, debit);
                        assertEquals("BALANCE_DEBIT_UNKNOWN", failure.getCode());
                        assertEquals("扣款来源存在 TCC 接收事实", failure.getMessage());
                      },
                      () -> assertEquals(before, allFundsFacts()),
                      () ->
                          assertEquals(
                              receiveBefore,
                              fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id")),
                      () -> org.mockito.Mockito.verifyNoInteractions(cache));
                } finally {
                  fixture.update("DELETE FROM user_tcc_receive WHERE id=-118");
                }
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(checks);
  }

  /** 首次Legacy不能跨过同pay receive，双接口足额/不足额都不写五表事实。 */
  @Test
  void legacyFreshDebitRejectsObservedTccReceiveWithoutWrites() {
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE() AND"
                + " table_name='user_tcc_receive'",
            Integer.class));
    assertEquals(false, context.containsBean("userTccReceiveStore"));
    context.getBean(com.cloudmall.user.mapper.UserTccReceiveMapper.class).ensureTable();
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : List.of("debits", "compat")) {
      for (boolean sufficient : List.of(true, false)) {
        for (String variant :
            List.of(
                "branch-null",
                "branch-known",
                "unknown-state",
                "pay-alias",
                "foreign-user",
                "bad-keys")) {
          checks.add(
              () -> {
                closeContext();
                createTransactionalServices();
                String payNo = "legacy-receive-" + entry + "-" + sufficient + "-" + variant;
                fixture.update(
                    "UPDATE mall_user SET balance=?,frozen_balance=0,status=1 WHERE id=7",
                    sufficient ? new BigDecimal("100.00") : BigDecimal.ONE);
                var request = new BalanceDebitRequestDTO();
                request.operationKey = "legacy-key:" + payNo;
                request.payNo = payNo;
                request.orderNo = "order-" + payNo;
                request.amount = new BigDecimal("10.00");
                var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                compatible.paymentKey = payNo;
                compatible.amount = request.amount;
                try {
                  fixture.update(
                      "INSERT INTO"
                          + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
                          + " VALUES(-117,?,'legacy-claimed-xid',?,?,?,10,?,?,?,NOW(3),NOW(3))",
                      "pay-alias".equals(variant)
                          ? payNo.toUpperCase(java.util.Locale.ROOT)
                          : payNo,
                      "branch-known".equals(variant) ? 1L : null,
                      "foreign-user".equals(variant) ? 8L : 7L,
                      "compat".equals(entry) ? payNo : request.orderNo,
                      "bad-keys".equals(variant) ? "wrong-receive-operation" : "tcc-debit:" + payNo,
                      "bad-keys".equals(variant) ? "wrong-receive-ledger" : payNo,
                      "unknown-state".equals(variant) ? "UNKNOWN" : "CLAIMED");
                  var bankBefore = allFundsFacts();
                  var receiveBefore =
                      fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
                  var response =
                      org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                              context.getBean(UserController.class))
                          .setControllerAdvice(
                              new com.cloudmall.common.web.GlobalExceptionHandler())
                          .build()
                          .perform(
                              org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                  .post(
                                      "compat".equals(entry)
                                          ? "/api/internal/users/7/balance/debit"
                                          : "/api/internal/users/7/balance/debits")
                                  .header("X-User-Id", "7")
                                  .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                  .content(
                                      new com.fasterxml.jackson.databind.ObjectMapper()
                                          .writeValueAsString(
                                              "compat".equals(entry) ? compatible : request)))
                          .andReturn();
                  org.junit.jupiter.api.Assertions.assertAll(
                      () ->
                          assertEquals(
                              409,
                              response.getResponse().getStatus(),
                              entry + ":" + sufficient + ":" + variant),
                      () -> assertHttpCode(response, "IDEMPOTENCY_CONFLICT"),
                      () -> {
                        var failure =
                            assertThrows(
                                BizException.class,
                                () -> {
                                  if ("compat".equals(entry)) legacy.debit(7L, 7L, compatible);
                                  else legacy.debitInternal(7L, 7L, request);
                                });
                        assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
                        assertEquals("支付号已有 TCC 接收事实", failure.getMessage());
                      },
                      () -> assertEquals(bankBefore, allFundsFacts()),
                      () ->
                          assertEquals(
                              receiveBefore,
                              fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id")));
                } finally {
                  fixture.update("DELETE FROM user_tcc_receive WHERE id=-117");
                }
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(checks);
  }

  /** current/source点读的历史多行必须稳定业务409，三模式新建/重放不写资金。 */
  @Test
  void refundPointReadsClassifyMultipleLedgerRowsWithoutWrites() {
    var indexes =
        fixture.queryForList(
            "SHOW INDEX FROM balance_ledger WHERE Column_name='operation_key' AND Non_unique=0");
    assertEquals(1, indexes.size());
    String uniqueIndex = (String) indexes.get(0).get("Key_name");
    assertTrue(uniqueIndex.matches("[A-Za-z0-9_]+"));
    fixture.execute("ALTER TABLE balance_ledger DROP INDEX `" + uniqueIndex + "`");
    try {
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc")) {
        for (String stage : List.of("current", "fresh-source", "replay-source")) {
          for (boolean alias : List.of(false, true)) {
            checks.add(
                () -> {
                  closeContext();
                  createTransactionalServices();
                  setEnabled(true);
                  var original =
                      request("point-multi-" + mode + "-" + stage + "-" + alias, 7L, "10.00");
                  if ("active-legacy".equals(mode))
                    debitLegacy(original.payNo, original.ledgerKey, 7L, "10.00");
                  else {
                    service.tryDebit(original);
                    service.confirmDebit(finalizeRequest(original));
                  }
                  if ("disabled-tcc".equals(mode)) {
                    setEnabled(false);
                    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
                  }
                  var current = refundRequest(original, "point-current-refund", "2.00");
                  if (!"fresh-source".equals(stage)) legacy.refundInternal(7L, 7L, current);
                  String duplicatedKey =
                      "current".equals(stage) ? current.refundOperationKey : original.ledgerKey;
                  String duplicateAlias =
                      alias ? duplicatedKey.toUpperCase(java.util.Locale.ROOT) : duplicatedKey;
                  fixture.update(
                      "INSERT INTO"
                          + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                          + " SELECT -1,8,?,operation_type,CASE WHEN operation_type='REFUND' THEN"
                          + " 'unrelated-point-origin' ELSE NULL"
                          + " END,'unrelated-point-pay','unrelated-point-order',amount,state,balance_before,balance_after,created_at"
                          + " FROM balance_ledger WHERE operation_key=?",
                      duplicateAlias,
                      duplicatedKey);
                  assertEquals(
                      2,
                      fixture.queryForObject(
                          "SELECT COUNT(*) FROM balance_ledger WHERE operation_key=?",
                          Integer.class,
                          duplicatedKey));
                  var before = allFundsFacts();
                  var response =
                      org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                              context.getBean(UserController.class))
                          .setControllerAdvice(
                              new com.cloudmall.common.web.GlobalExceptionHandler())
                          .build()
                          .perform(
                              org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                  .post("/api/internal/users/7/balance/refunds")
                                  .header("X-User-Id", "7")
                                  .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                  .content(
                                      new com.fasterxml.jackson.databind.ObjectMapper()
                                          .writeValueAsString(current)))
                          .andReturn();
                  org.junit.jupiter.api.Assertions.assertAll(
                      () ->
                          assertEquals(
                              409,
                              response.getResponse().getStatus(),
                              mode + ":" + stage + ":" + alias),
                      () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                      () -> {
                        var failure =
                            assertThrows(
                                BizException.class, () -> legacy.refundInternal(7L, 7L, current));
                        assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                        assertEquals("退款累计证据不完整或不一致", failure.getMessage());
                      },
                      () -> assertEquals(before, allFundsFacts()));
                });
          }
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(checks);
    } finally {
      fixture.update("DELETE FROM balance_ledger WHERE id=-1");
      fixture.execute(
          "ALTER TABLE balance_ledger ADD UNIQUE INDEX `" + uniqueIndex + "` (operation_key)");
      assertEquals(
          1,
          fixture
              .queryForList(
                  "SHOW INDEX FROM balance_ledger WHERE Column_name='operation_key' AND"
                      + " Non_unique=0")
              .size());
    }
  }

  /** 退款不能忽略其它pay的同source非DEBIT或同prior退款键，三模式新建/重放不写资金。 */
  @Test
  void refundRejectsGloballyDuplicatedObservedKeysWithoutWrites() {
    var indexes =
        fixture.queryForList(
            "SHOW INDEX FROM balance_ledger WHERE Column_name='operation_key' AND Non_unique=0");
    assertEquals(1, indexes.size());
    String uniqueIndex = (String) indexes.get(0).get("Key_name");
    assertTrue(uniqueIndex.matches("[A-Za-z0-9_]+"));
    fixture.execute("ALTER TABLE balance_ledger DROP INDEX `" + uniqueIndex + "`");
    try {
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc")) {
        for (String entry : List.of("fresh", "replay")) {
          for (String shadow : List.of("source", "prior")) {
            for (boolean alias : List.of(false, true)) {
              checks.add(
                  () -> {
                    closeContext();
                    createTransactionalServices();
                    setEnabled(true);
                    var original =
                        request(
                            "fund-global-" + mode + "-" + entry + "-" + shadow + "-" + alias,
                            7L,
                            "10.00");
                    if ("active-legacy".equals(mode))
                      debitLegacy(original.payNo, original.ledgerKey, 7L, "10.00");
                    else {
                      service.tryDebit(original);
                      service.confirmDebit(finalizeRequest(original));
                    }
                    if ("disabled-tcc".equals(mode)) {
                      setEnabled(false);
                      fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
                    }
                    legacy.refundInternal(
                        7L, 7L, refundRequest(original, "prior-global-refund", "1.00"));
                    var current = refundRequest(original, "current-global-refund", "2.00");
                    if ("replay".equals(entry)) legacy.refundInternal(7L, 7L, current);
                    String duplicatedKey =
                        "source".equals(shadow) ? original.ledgerKey : "prior-global-refund";
                    String duplicateAlias =
                        alias ? duplicatedKey.toUpperCase(java.util.Locale.ROOT) : duplicatedKey;
                    fixture.update(
                        "INSERT INTO"
                            + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                            + " SELECT -1,8,?,?,"
                            + " 'unrelated-global-origin','unrelated-global-pay','unrelated-global-order',amount,state,balance_before,balance_after,created_at"
                            + " FROM balance_ledger WHERE operation_key=?",
                        duplicateAlias,
                        "source".equals(shadow) ? "UNKNOWN" : "REFUND",
                        duplicatedKey);
                    assertEquals(
                        2,
                        fixture.queryForObject(
                            "SELECT COUNT(*) FROM balance_ledger WHERE operation_key=?",
                            Integer.class,
                            duplicatedKey));
                    var before = allFundsFacts();
                    var response =
                        org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                                context.getBean(UserController.class))
                            .setControllerAdvice(
                                new com.cloudmall.common.web.GlobalExceptionHandler())
                            .build()
                            .perform(
                                org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                    .post("/api/internal/users/7/balance/refunds")
                                    .header("X-User-Id", "7")
                                    .contentType(
                                        org.springframework.http.MediaType.APPLICATION_JSON)
                                    .content(
                                        new com.fasterxml.jackson.databind.ObjectMapper()
                                            .writeValueAsString(current)))
                            .andReturn();
                    org.junit.jupiter.api.Assertions.assertAll(
                        () ->
                            assertEquals(
                                409,
                                response.getResponse().getStatus(),
                                mode + ":" + entry + ":" + shadow + ":" + alias),
                        () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                        () -> {
                          var failure =
                              assertThrows(
                                  BizException.class, () -> legacy.refundInternal(7L, 7L, current));
                          assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                          assertEquals("退款累计证据不完整或不一致", failure.getMessage());
                        },
                        () -> assertEquals(before, allFundsFacts()));
                  });
            }
          }
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(checks);
    } finally {
      fixture.update("DELETE FROM balance_ledger WHERE id=-1");
      fixture.execute(
          "ALTER TABLE balance_ledger ADD UNIQUE INDEX `" + uniqueIndex + "` (operation_key)");
      assertEquals(
          1,
          fixture
              .queryForList(
                  "SHOW INDEX FROM balance_ledger WHERE Column_name='operation_key' AND"
                      + " Non_unique=0")
              .size());
    }
  }

  /** owned遗留缺UNIQUE时，重复主/source键及隐藏在其它pay的同退款键必须业务409。 */
  @Test
  void operationQueryRejectsGloballyDuplicatedKeysWithoutWrites() {
    var indexes =
        fixture.queryForList(
            "SHOW INDEX FROM balance_ledger WHERE Column_name='operation_key' AND Non_unique=0");
    assertEquals(1, indexes.size());
    String uniqueIndex = (String) indexes.get(0).get("Key_name");
    assertTrue(uniqueIndex.matches("[A-Za-z0-9_]+"));
    fixture.execute("ALTER TABLE balance_ledger DROP INDEX `" + uniqueIndex + "`");
    try {
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      for (String scenario :
          List.of(
              "primary-debit",
              "primary-refund",
              "primary-rejected",
              "refund-source",
              "hidden-refund")) {
        for (boolean alias : List.of(false, true)) {
          checks.add(
              () -> {
                if ("primary-rejected".equals(scenario)) {
                  closeContext();
                  createTransactionalServices();
                  assertEquals(
                      "PAYMENT_FAILED",
                      assertThrows(
                              BizException.class,
                              () -> debitLegacy("global-rejected", "global-rejected", 7L, "101.00"))
                          .getCode());
                } else resetOperationFixture("refund");
                String primaryKey =
                    "primary-rejected".equals(scenario)
                        ? "global-rejected"
                        : "primary-refund".equals(scenario) || "refund-source".equals(scenario)
                            ? "query-refund"
                            : "query-debit";
                String duplicatedKey =
                    "refund-source".equals(scenario)
                        ? "query-debit"
                        : "hidden-refund".equals(scenario) ? "query-refund" : primaryKey;
                String duplicateAlias =
                    alias ? duplicatedKey.toUpperCase(java.util.Locale.ROOT) : duplicatedKey;
                fixture.update(
                    "INSERT INTO"
                        + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                        + " SELECT -1,8,?,operation_type,CASE WHEN operation_type='REFUND' THEN"
                        + " 'unrelated-origin' ELSE NULL"
                        + " END,'unrelated-global-pay','unrelated-global-order',amount,state,balance_before,balance_after,created_at"
                        + " FROM balance_ledger WHERE operation_key=?",
                    duplicateAlias,
                    duplicatedKey);
                assertEquals(
                    2,
                    fixture.queryForObject(
                        "SELECT COUNT(*) FROM balance_ledger WHERE operation_key=?",
                        Integer.class,
                        duplicatedKey));
                var before = allFundsFacts();
                var response = operationHttp(primaryKey);
                org.junit.jupiter.api.Assertions.assertAll(
                    () ->
                        assertEquals(
                            409, response.getResponse().getStatus(), scenario + ":" + alias),
                    () -> assertHttpCode(response, "COMMON_CONFLICT"),
                    () -> {
                      var failure =
                          assertThrows(
                              BizException.class,
                              () -> legacy.balanceOperation(7L, primaryKey, 7L));
                      assertEquals("COMMON_CONFLICT", failure.getCode());
                      assertEquals("余额账本事实待核对", failure.getMessage());
                    },
                    () -> assertEquals(before, allFundsFacts()));
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(checks);
    } finally {
      fixture.update("DELETE FROM balance_ledger WHERE id=-1");
      fixture.execute(
          "ALTER TABLE balance_ledger ADD UNIQUE INDEX `" + uniqueIndex + "` (operation_key)");
      assertEquals(
          1,
          fixture
              .queryForList(
                  "SHOW INDEX FROM balance_ledger WHERE Column_name='operation_key' AND"
                      + " Non_unique=0")
              .size());
    }
  }

  /** 冻结多结果不能当唯一确认或空范围，十七入口维持原业务拒绝并恢复索引。 */
  @Test
  void freezeDuplicateRowsKeepCallerBusinessFailures() {
    var indexes =
        fixture.queryForList(
            "SHOW INDEX FROM balance_tcc_debit WHERE Column_name='pay_no' AND Non_unique=0");
    assertEquals(1, indexes.size());
    String uniqueIndex = (String) indexes.get(0).get("Key_name");
    assertTrue(uniqueIndex.matches("[A-Za-z0-9_]+"));
    fixture.execute("ALTER TABLE balance_tcc_debit DROP INDEX `" + uniqueIndex + "`");
    try {
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      for (String route :
          List.of(
              "fresh-debits",
              "fresh-compat",
              "applied-replay-debits",
              "applied-replay-compat",
              "rejected-replay-debits",
              "rejected-replay-compat",
              "rejected-get",
              "applied-debit-get",
              "applied-refund-get",
              "active-fresh",
              "active-replay",
              "disabled-fresh",
              "disabled-replay",
              "tcc-debit-get",
              "tcc-refund-get",
              "tcc-active-fresh",
              "tcc-active-replay")) {
        for (boolean alias : List.of(false, true)) {
          checks.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                setEnabled(true);
                boolean fresh = route.startsWith("fresh");
                boolean getter = route.endsWith("get");
                boolean disabled = route.startsWith("disabled");
                boolean confirmed = disabled || route.startsWith("tcc");
                boolean refunding =
                    disabled || route.startsWith("active") || route.startsWith("tcc-active");
                boolean rejected = route.startsWith("rejected");
                var original =
                    request(
                        "freeze-multi-" + route + "-" + alias, 7L, rejected ? "101.00" : "10.00");
                var debit = new BalanceDebitRequestDTO();
                debit.operationKey = original.payNo;
                debit.payNo = original.payNo;
                debit.orderNo = route.endsWith("compat") ? original.payNo : original.orderNo;
                debit.amount = original.amount;
                var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                compatible.paymentKey = original.payNo;
                compatible.amount = original.amount;
                org.junit.jupiter.api.function.Executable legacyDebit =
                    () -> {
                      if (route.endsWith("compat")) legacy.debit(7L, 7L, compatible);
                      else legacy.debitInternal(7L, 7L, debit);
                    };
                if (confirmed) {
                  service.tryDebit(original);
                  service.confirmDebit(finalizeRequest(original));
                } else if (!fresh) {
                  if (rejected)
                    assertEquals(
                        "PAYMENT_FAILED", assertThrows(BizException.class, legacyDebit).getCode());
                  else legacyDebit.execute();
                }
                var refund =
                    refundRequest(original, "freeze-multi-refund:" + original.payNo, "2.00");
                if (List.of(
                        "applied-refund-get",
                        "active-replay",
                        "disabled-replay",
                        "tcc-refund-get",
                        "tcc-active-replay")
                    .contains(route)) legacy.refundInternal(7L, 7L, refund);
                if (disabled) fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
                String queryKey =
                    List.of("applied-refund-get", "tcc-refund-get").contains(route)
                        ? refund.refundOperationKey
                        : original.payNo;
                try {
                  for (int rowNumber = 1; rowNumber <= (confirmed ? 1 : 2); rowNumber++) {
                    fixture.update(
                        "INSERT INTO"
                            + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
                            + " VALUES(?,?,?,1,8,?,?,1,'CONFIRMED',100,99,NOW(3),NOW(3))",
                        rowNumber == 1 ? -124L : -125L,
                        "extra-freeze-operation-" + rowNumber,
                        "extra-freeze-xid-" + rowNumber,
                        alias && (rowNumber == 2 || confirmed)
                            ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                            : original.payNo,
                        "extra-freeze-order-" + rowNumber);
                  }
                  var before = allFundsFacts();
                  var receiptBefore =
                      fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
                  var cache = context.getBean(StringRedisTemplate.class);
                  org.mockito.Mockito.clearInvocations(cache);
                  var response =
                      getter
                          ? operationHttp(queryKey)
                          : refunding
                              ? org.springframework.test.web.servlet.setup.MockMvcBuilders
                                  .standaloneSetup(context.getBean(UserController.class))
                                  .setControllerAdvice(
                                      new com.cloudmall.common.web.GlobalExceptionHandler())
                                  .build()
                                  .perform(
                                      org.springframework.test.web.servlet.request
                                          .MockMvcRequestBuilders.post(
                                              "/api/internal/users/7/balance/refunds")
                                          .header("X-User-Id", "7")
                                          .contentType(
                                              org.springframework.http.MediaType.APPLICATION_JSON)
                                          .content(
                                              new com.fasterxml.jackson.databind.ObjectMapper()
                                                  .writeValueAsString(refund)))
                                  .andReturn()
                              : route.endsWith("compat")
                                  ? postCompatibleLegacyDebit(compatible)
                                  : postLegacyDebit(debit);
                  String expectedCode =
                      disabled
                          ? "COMMON_NOT_FOUND"
                          : getter
                              ? "COMMON_CONFLICT"
                              : refunding
                                  ? "BALANCE_REFUND_INVALID"
                                  : fresh ? "IDEMPOTENCY_CONFLICT" : "BALANCE_DEBIT_UNKNOWN";
                  String expectedMessage =
                      disabled
                          ? "用户不存在"
                          : getter
                              ? "余额账本事实待核对"
                              : refunding ? "原扣款不存在或未应用" : fresh ? "支付号已有TCC冻结事实" : "扣款来源存在TCC冻结事实";
                  org.junit.jupiter.api.Assertions.assertAll(
                      () ->
                          assertEquals(
                              disabled ? 404 : 409,
                              response.getResponse().getStatus(),
                              route + ":" + alias),
                      () -> assertHttpCode(response, expectedCode),
                      () -> {
                        var failure =
                            assertThrows(
                                BizException.class,
                                () -> {
                                  if (getter) legacy.balanceOperation(7L, queryKey, 7L);
                                  else if (refunding) legacy.refundInternal(7L, 7L, refund);
                                  else legacyDebit.execute();
                                });
                        assertEquals(expectedCode, failure.getCode());
                        assertEquals(expectedMessage, failure.getMessage());
                      },
                      () -> assertEquals(before, allFundsFacts()),
                      () ->
                          assertEquals(
                              receiptBefore,
                              fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id")),
                      () -> org.mockito.Mockito.verifyNoInteractions(cache));
                } finally {
                  fixture.update("DELETE FROM balance_tcc_debit WHERE id IN (-124,-125)");
                }
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(checks);
    } finally {
      fixture.update("DELETE FROM balance_tcc_debit WHERE id IN (-124,-125)");
      fixture.execute(
          "ALTER TABLE balance_tcc_debit ADD UNIQUE INDEX `" + uniqueIndex + "` (pay_no)");
      assertEquals(
          1,
          fixture
              .queryForList(
                  "SHOW INDEX FROM balance_tcc_debit WHERE Column_name='pay_no' AND Non_unique=0")
              .size());
    }
  }

  /** 十三入口遇exact/CI重复归属均按原业务拒绝，资金/接收/cache不写且恢复唯一索引。 */
  @Test
  void debitOwnershipDuplicateRowsKeepCallerBusinessFailures() {
    var indexes =
        fixture.queryForList(
            "SHOW INDEX FROM balance_debit_attempt WHERE Column_name='pay_no' AND Non_unique=0");
    assertEquals(1, indexes.size());
    String uniqueIndex = (String) indexes.get(0).get("Key_name");
    assertTrue(uniqueIndex.matches("[A-Za-z0-9_]+"));
    fixture.execute("ALTER TABLE balance_debit_attempt DROP INDEX `" + uniqueIndex + "`");
    try {
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      for (String route :
          List.of(
              "fresh-debits",
              "fresh-compat",
              "applied-replay-debits",
              "applied-replay-compat",
              "rejected-replay-debits",
              "rejected-replay-compat",
              "rejected-get",
              "applied-debit-get",
              "applied-refund-get",
              "active-fresh",
              "active-replay",
              "disabled-fresh",
              "disabled-replay")) {
        for (boolean alias : List.of(false, true)) {
          checks.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                setEnabled(true);
                boolean fresh = route.startsWith("fresh");
                boolean getter = route.endsWith("get");
                boolean disabled = route.startsWith("disabled");
                boolean refunding = disabled || route.startsWith("active");
                boolean rejected = route.startsWith("rejected");
                var original =
                    request(
                        "owner-multi-" + route + "-" + alias, 7L, rejected ? "101.00" : "10.00");
                var debit = new BalanceDebitRequestDTO();
                debit.operationKey = original.payNo;
                debit.payNo = original.payNo;
                debit.orderNo = route.endsWith("compat") ? original.payNo : original.orderNo;
                debit.amount = original.amount;
                var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                compatible.paymentKey = original.payNo;
                compatible.amount = original.amount;
                org.junit.jupiter.api.function.Executable legacyDebit =
                    () -> {
                      if (route.endsWith("compat")) legacy.debit(7L, 7L, compatible);
                      else legacy.debitInternal(7L, 7L, debit);
                    };
                if (disabled) {
                  service.tryDebit(original);
                  service.confirmDebit(finalizeRequest(original));
                } else if (!fresh) {
                  if (rejected)
                    assertEquals(
                        "PAYMENT_FAILED", assertThrows(BizException.class, legacyDebit).getCode());
                  else legacyDebit.execute();
                }
                var refund =
                    refundRequest(original, "owner-multi-refund:" + original.payNo, "2.00");
                if (List.of("applied-refund-get", "active-replay", "disabled-replay")
                    .contains(route)) legacy.refundInternal(7L, 7L, refund);
                if (disabled) fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
                String queryKey =
                    "applied-refund-get".equals(route) ? refund.refundOperationKey : original.payNo;
                try {
                  for (int rowNumber = 1; rowNumber <= (fresh ? 2 : 1); rowNumber++) {
                    fixture.update(
                        "INSERT INTO"
                            + " balance_debit_attempt(id,pay_no,operation_key,mode,xid,ledger_key,branch_id,user_id,order_no,amount,state,created_at,updated_at)"
                            + " VALUES(?,?,?,'LEGACY',NULL,?,NULL,8,?,1,'APPLIED',NOW(3),NOW(3))",
                        rowNumber == 1 ? -123L : -124L,
                        alias && (rowNumber == 2 || !fresh)
                            ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                            : original.payNo,
                        "extra-owner-operation-" + rowNumber,
                        "extra-owner-ledger-" + rowNumber,
                        "extra-owner-order-" + rowNumber);
                  }
                  var before = allFundsFacts();
                  var receiptBefore =
                      fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
                  var cache = context.getBean(StringRedisTemplate.class);
                  org.mockito.Mockito.clearInvocations(cache);
                  var response =
                      getter
                          ? operationHttp(queryKey)
                          : refunding
                              ? org.springframework.test.web.servlet.setup.MockMvcBuilders
                                  .standaloneSetup(context.getBean(UserController.class))
                                  .setControllerAdvice(
                                      new com.cloudmall.common.web.GlobalExceptionHandler())
                                  .build()
                                  .perform(
                                      org.springframework.test.web.servlet.request
                                          .MockMvcRequestBuilders.post(
                                              "/api/internal/users/7/balance/refunds")
                                          .header("X-User-Id", "7")
                                          .contentType(
                                              org.springframework.http.MediaType.APPLICATION_JSON)
                                          .content(
                                              new com.fasterxml.jackson.databind.ObjectMapper()
                                                  .writeValueAsString(refund)))
                                  .andReturn()
                              : route.endsWith("compat")
                                  ? postCompatibleLegacyDebit(compatible)
                                  : postLegacyDebit(debit);
                  String expectedCode =
                      disabled
                          ? "COMMON_NOT_FOUND"
                          : getter
                              ? "COMMON_CONFLICT"
                              : refunding ? "BALANCE_REFUND_INVALID" : "IDEMPOTENCY_CONFLICT";
                  String expectedMessage =
                      disabled
                          ? "用户不存在"
                          : getter ? "余额账本事实待核对" : refunding ? "原扣款不存在或未应用" : "支付号存在重复扣款归属记录";
                  org.junit.jupiter.api.Assertions.assertAll(
                      () ->
                          assertEquals(
                              disabled ? 404 : 409,
                              response.getResponse().getStatus(),
                              route + ":" + alias),
                      () -> assertHttpCode(response, expectedCode),
                      () -> {
                        var failure =
                            assertThrows(
                                BizException.class,
                                () -> {
                                  if (getter) legacy.balanceOperation(7L, queryKey, 7L);
                                  else if (refunding) legacy.refundInternal(7L, 7L, refund);
                                  else legacyDebit.execute();
                                });
                        assertEquals(expectedCode, failure.getCode());
                        assertEquals(expectedMessage, failure.getMessage());
                      },
                      () -> assertEquals(before, allFundsFacts()),
                      () ->
                          assertEquals(
                              receiptBefore,
                              fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id")),
                      () -> org.mockito.Mockito.verifyNoInteractions(cache));
                } finally {
                  fixture.update("DELETE FROM balance_debit_attempt WHERE id IN (-123,-124)");
                }
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(checks);
    } finally {
      fixture.update("DELETE FROM balance_debit_attempt WHERE id IN (-123,-124)");
      fixture.execute(
          "ALTER TABLE balance_debit_attempt ADD UNIQUE INDEX `" + uniqueIndex + "` (pay_no)");
      assertEquals(
          1,
          fixture
              .queryForList(
                  "SHOW INDEX FROM balance_debit_attempt WHERE Column_name='pay_no' AND"
                      + " Non_unique=0")
              .size());
    }
  }

  /** 当前pay候选不能隐藏其它pay同操作键，双Legacy入口多结果必须409且不写/cache。 */
  @Test
  void legacyDebitLedgerDuplicateKeysKeepBusinessConflicts() {
    var indexes =
        fixture.queryForList(
            "SHOW INDEX FROM balance_ledger WHERE Column_name='operation_key' AND Non_unique=0");
    assertEquals(1, indexes.size());
    String uniqueIndex = (String) indexes.get(0).get("Key_name");
    assertTrue(uniqueIndex.matches("[A-Za-z0-9_]+"));
    fixture.execute("ALTER TABLE balance_ledger DROP INDEX `" + uniqueIndex + "`");
    try {
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      for (String entry : List.of("debits", "compat")) {
        for (String state : List.of("fresh", "APPLIED", "REJECTED")) {
          for (boolean alias : List.of(false, true)) {
            checks.add(
                () -> {
                  for (String table :
                      List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                    fixture.update("DELETE FROM " + table);
                  fixture.update(
                      "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                  var request = new BalanceDebitRequestDTO();
                  request.payNo = "legacy-key-multi-" + entry + "-" + state + "-" + alias;
                  request.operationKey =
                      "compat".equals(entry) ? request.payNo : "legacy-key:" + request.payNo;
                  request.orderNo =
                      "compat".equals(entry) ? request.payNo : "order-" + request.payNo;
                  request.amount = new BigDecimal("REJECTED".equals(state) ? "101.00" : "10.00");
                  var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                  compatible.paymentKey = request.payNo;
                  compatible.amount = request.amount;
                  org.junit.jupiter.api.function.Executable debit =
                      () -> {
                        if ("compat".equals(entry)) legacy.debit(7L, 7L, compatible);
                        else legacy.debitInternal(7L, 7L, request);
                      };
                  if ("REJECTED".equals(state))
                    assertEquals(
                        "PAYMENT_FAILED", assertThrows(BizException.class, debit).getCode());
                  else if (!"fresh".equals(state)) debit.execute();
                  try {
                    for (int rowNumber = 1;
                        rowNumber <= ("fresh".equals(state) ? 2 : 1);
                        rowNumber++) {
                      fixture.update(
                          "INSERT INTO"
                              + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                              + " VALUES(?,8,?,'DEBIT',NULL,?,?,1,'APPLIED',100,99,NOW(3))",
                          rowNumber == 1 ? -122L : -123L,
                          alias && (rowNumber == 2 || !"fresh".equals(state))
                              ? request.operationKey.toUpperCase(java.util.Locale.ROOT)
                              : request.operationKey,
                          "other-key-pay-" + rowNumber,
                          "other-key-order-" + rowNumber);
                    }
                    var before = allFundsFacts();
                    var receiptBefore =
                        fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
                    var cache = context.getBean(StringRedisTemplate.class);
                    org.mockito.Mockito.clearInvocations(cache);
                    var response =
                        "compat".equals(entry)
                            ? postCompatibleLegacyDebit(compatible)
                            : postLegacyDebit(request);
                    org.junit.jupiter.api.Assertions.assertAll(
                        () ->
                            assertEquals(
                                409,
                                response.getResponse().getStatus(),
                                entry + ":" + state + ":" + alias),
                        () -> assertHttpCode(response, "IDEMPOTENCY_CONFLICT"),
                        () -> {
                          var failure = assertThrows(BizException.class, debit);
                          assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
                          assertEquals("扣款操作键存在重复账本记录", failure.getMessage());
                        },
                        () -> assertEquals(before, allFundsFacts()),
                        () ->
                            assertEquals(
                                receiptBefore,
                                fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id")),
                        () -> org.mockito.Mockito.verifyNoInteractions(cache));
                  } finally {
                    fixture.update("DELETE FROM balance_ledger WHERE id IN (-122,-123)");
                  }
                });
          }
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(checks);
    } finally {
      fixture.update("DELETE FROM balance_ledger WHERE id IN (-122,-123)");
      fixture.execute(
          "ALTER TABLE balance_ledger ADD UNIQUE INDEX `" + uniqueIndex + "` (operation_key)");
      assertEquals(
          1,
          fixture
              .queryForList(
                  "SHOW INDEX FROM balance_ledger WHERE Column_name='operation_key' AND"
                      + " Non_unique=0")
              .size());
    }
  }

  /** 缺唯一索引的exact/CI重复接收事实须各入口原409，保护五表且finally恢复索引。 */
  @Test
  void receiveDuplicateRowsKeepOriginalBusinessConflicts() {
    var indexes =
        fixture.queryForList(
            "SHOW INDEX FROM user_tcc_receive WHERE Column_name='pay_no' AND Non_unique=0");
    assertEquals(1, indexes.size());
    String uniqueIndex = (String) indexes.get(0).get("Key_name");
    assertTrue(uniqueIndex.matches("[A-Za-z0-9_]+"));
    fixture.execute("ALTER TABLE user_tcc_receive DROP INDEX `" + uniqueIndex + "`");
    try {
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      for (String route :
          List.of(
              "fresh-debits",
              "fresh-compat",
              "replay-debits",
              "replay-compat",
              "rejected-get",
              "applied-debit-get",
              "applied-refund-get",
              "active-fresh",
              "active-replay")) {
        for (boolean alias : List.of(false, true)) {
          checks.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                boolean fresh = route.startsWith("fresh");
                boolean getter = route.endsWith("get");
                boolean refunding = route.startsWith("active");
                var original =
                    request(
                        "receive-multi-" + route + "-" + alias,
                        7L,
                        "rejected-get".equals(route) ? "101.00" : "10.00");
                var debit = new BalanceDebitRequestDTO();
                debit.operationKey = original.payNo;
                debit.payNo = original.payNo;
                debit.orderNo = original.orderNo;
                debit.amount = original.amount;
                var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                compatible.paymentKey = original.payNo;
                compatible.amount = original.amount;
                if (!fresh) {
                  if ("rejected-get".equals(route))
                    assertEquals(
                        "PAYMENT_FAILED",
                        assertThrows(
                                BizException.class,
                                () -> debitLegacy(original.payNo, original.payNo, 7L, "101.00"))
                            .getCode());
                  else debitLegacy(original.payNo, original.payNo, 7L, "10.00");
                  if (route.endsWith("compat"))
                    fixture.update("UPDATE balance_debit_attempt SET order_no=?", original.payNo);
                  if (route.endsWith("compat"))
                    fixture.update("UPDATE balance_ledger SET order_no=?", original.payNo);
                }
                var refund =
                    refundRequest(original, "receive-multi-refund:" + original.payNo, "2.00");
                if (List.of("applied-refund-get", "active-replay").contains(route))
                  legacy.refundInternal(7L, 7L, refund);
                String queryKey =
                    "applied-refund-get".equals(route) ? refund.refundOperationKey : original.payNo;
                try {
                  for (int rowNumber : List.of(1, 2)) {
                    fixture.update(
                        "INSERT INTO"
                            + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
                            + " VALUES(?,?,?,NULL,7,?,10,?,?,'CLAIMED',NOW(3),NOW(3))",
                        rowNumber == 1 ? -121L : -122L,
                        alias && rowNumber == 2
                            ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                            : original.payNo,
                        "receive-multi-xid-" + rowNumber,
                        original.orderNo,
                        "receive-multi-operation-" + rowNumber,
                        "receive-multi-ledger-" + rowNumber);
                  }
                  var before = allFundsFacts();
                  var receiveBefore =
                      fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
                  var response =
                      getter
                          ? operationHttp(queryKey)
                          : refunding
                              ? org.springframework.test.web.servlet.setup.MockMvcBuilders
                                  .standaloneSetup(context.getBean(UserController.class))
                                  .setControllerAdvice(
                                      new com.cloudmall.common.web.GlobalExceptionHandler())
                                  .build()
                                  .perform(
                                      org.springframework.test.web.servlet.request
                                          .MockMvcRequestBuilders.post(
                                              "/api/internal/users/7/balance/refunds")
                                          .header("X-User-Id", "7")
                                          .contentType(
                                              org.springframework.http.MediaType.APPLICATION_JSON)
                                          .content(
                                              new com.fasterxml.jackson.databind.ObjectMapper()
                                                  .writeValueAsString(refund)))
                                  .andReturn()
                              : route.endsWith("compat")
                                  ? postCompatibleLegacyDebit(compatible)
                                  : postLegacyDebit(debit);
                  String expectedCode =
                      fresh
                          ? "IDEMPOTENCY_CONFLICT"
                          : getter
                              ? "COMMON_CONFLICT"
                              : refunding ? "BALANCE_REFUND_INVALID" : "BALANCE_DEBIT_UNKNOWN";
                  String expectedMessage =
                      fresh
                          ? "支付号已有 TCC 接收事实"
                          : getter ? "余额账本事实待核对" : refunding ? "原扣款不存在或未应用" : "扣款来源存在 TCC 接收事实";
                  org.junit.jupiter.api.Assertions.assertAll(
                      () ->
                          assertEquals(
                              409, response.getResponse().getStatus(), route + ":" + alias),
                      () -> assertHttpCode(response, expectedCode),
                      () -> {
                        var failure =
                            assertThrows(
                                BizException.class,
                                () -> {
                                  if (getter) legacy.balanceOperation(7L, queryKey, 7L);
                                  else if (refunding) legacy.refundInternal(7L, 7L, refund);
                                  else if (route.endsWith("compat"))
                                    legacy.debit(7L, 7L, compatible);
                                  else legacy.debitInternal(7L, 7L, debit);
                                });
                        assertEquals(expectedCode, failure.getCode());
                        assertEquals(expectedMessage, failure.getMessage());
                      },
                      () -> assertEquals(before, allFundsFacts()),
                      () ->
                          assertEquals(
                              receiveBefore,
                              fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id")));
                } finally {
                  fixture.update("DELETE FROM user_tcc_receive WHERE id IN (-121,-122)");
                }
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(checks);
    } finally {
      fixture.update("DELETE FROM user_tcc_receive WHERE id IN (-121,-122)");
      fixture.execute(
          "ALTER TABLE user_tcc_receive ADD UNIQUE INDEX `" + uniqueIndex + "` (pay_no)");
      assertEquals(
          1,
          fixture
              .queryForList(
                  "SHOW INDEX FROM user_tcc_receive WHERE Column_name='pay_no' AND Non_unique=0")
              .size());
    }
  }

  /** APPLIED Legacy/null来源的四入口均不得跨过接收事实，退款失败保留五表。 */
  @Test
  void appliedLegacySourcesRejectObservedReceiveWithoutWriting() throws Exception {
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String route : List.of("debit-get", "refund-get", "fresh-refund", "replay-refund")) {
      for (boolean ownerPresent : List.of(true, false)) {
        for (String variant :
            List.of(
                "branch-null",
                "branch-known",
                "unknown-state",
                "pay-alias",
                "foreign-user",
                "bad-keys")) {
          checks.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                var original =
                    request(
                        "applied-receive-" + route + "-" + ownerPresent + "-" + variant,
                        7L,
                        "10.00");
                debitLegacy(original.payNo, original.payNo, 7L, "10.00");
                var refund = refundRequest(original, "receive-refund:" + original.payNo, "2.00");
                if (List.of("refund-get", "replay-refund").contains(route))
                  legacy.refundInternal(7L, 7L, refund);
                if (!ownerPresent) fixture.update("DELETE FROM balance_debit_attempt");
                String queryKey =
                    "refund-get".equals(route) ? refund.refundOperationKey : original.payNo;
                boolean getter = route.endsWith("get");
                if (getter) assertEquals(200, operationHttp(queryKey).getResponse().getStatus());
                try {
                  fixture.update(
                      "INSERT INTO"
                          + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
                          + " VALUES(-120,?,'applied-source-receive-xid',?,?,?,10,?,?,?,NOW(3),NOW(3))",
                      "pay-alias".equals(variant)
                          ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                          : original.payNo,
                      "branch-known".equals(variant) ? 1L : null,
                      "foreign-user".equals(variant) ? 8L : 7L,
                      original.orderNo,
                      "bad-keys".equals(variant) ? "wrong-operation" : original.operationKey,
                      "bad-keys".equals(variant) ? "wrong-ledger" : original.ledgerKey,
                      "unknown-state".equals(variant) ? "UNKNOWN" : "CLAIMED");
                  var before = allFundsFacts();
                  var receiveBefore =
                      fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
                  var response =
                      getter
                          ? operationHttp(queryKey)
                          : org.springframework.test.web.servlet.setup.MockMvcBuilders
                              .standaloneSetup(context.getBean(UserController.class))
                              .setControllerAdvice(
                                  new com.cloudmall.common.web.GlobalExceptionHandler())
                              .build()
                              .perform(
                                  org.springframework.test.web.servlet.request
                                      .MockMvcRequestBuilders.post(
                                          "/api/internal/users/7/balance/refunds")
                                      .header("X-User-Id", "7")
                                      .contentType(
                                          org.springframework.http.MediaType.APPLICATION_JSON)
                                      .content(
                                          new com.fasterxml.jackson.databind.ObjectMapper()
                                              .writeValueAsString(refund)))
                              .andReturn();
                  org.junit.jupiter.api.Assertions.assertAll(
                      () ->
                          assertEquals(
                              409,
                              response.getResponse().getStatus(),
                              route + ":" + ownerPresent + ":" + variant),
                      () ->
                          assertHttpCode(
                              response, getter ? "COMMON_CONFLICT" : "BALANCE_REFUND_INVALID"),
                      () -> {
                        var failure =
                            assertThrows(
                                BizException.class,
                                () -> {
                                  if (getter) legacy.balanceOperation(7L, queryKey, 7L);
                                  else legacy.refundInternal(7L, 7L, refund);
                                });
                        assertEquals(
                            getter ? "COMMON_CONFLICT" : "BALANCE_REFUND_INVALID",
                            failure.getCode());
                        assertEquals(getter ? "余额账本事实待核对" : "原扣款不存在或未应用", failure.getMessage());
                      },
                      () -> assertEquals(before, allFundsFacts()),
                      () ->
                          assertEquals(
                              receiveBefore,
                              fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id")));
                } finally {
                  fixture.update("DELETE FROM user_tcc_receive WHERE id=-120");
                }
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(checks);
    closeContext();
    createTransactionalServices();
    var confirmed = request("applied-receive-tcc-control", 7L, "10.00");
    service.tryDebit(confirmed);
    service.confirmDebit(finalizeRequest(confirmed));
    try {
      fixture.update(
          "INSERT INTO"
              + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
              + " VALUES(-120,?,?,?,?,?,10,?,?,'CLAIMED',NOW(3),NOW(3))",
          confirmed.payNo,
          confirmed.xid,
          confirmed.branchId,
          confirmed.userId,
          confirmed.orderNo,
          confirmed.operationKey,
          confirmed.ledgerKey);
      assertEquals(200, operationHttp(confirmed.ledgerKey).getResponse().getStatus());
      var refund = refundRequest(confirmed, "applied-receive-tcc-refund", "2.00");
      legacy.refundInternal(7L, 7L, refund);
      var before = allFundsFacts();
      var receiptBefore = fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
      legacy.refundInternal(7L, 7L, refund);
      assertEquals(200, operationHttp(refund.refundOperationKey).getResponse().getStatus());
      assertEquals(before, allFundsFacts());
      assertEquals(
          receiptBefore, fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id"));
    } finally {
      fixture.update("DELETE FROM user_tcc_receive WHERE id=-120");
    }
  }

  /** REJECTED查询任意接收来源均待核，归属/冻结/接收同只读RR且五表不写。 */
  @Test
  void rejectedOperationRejectsObservedReceiveWithoutWriting() {
    Object target = AopTestUtils.getUltimateTargetObject(legacy);
    var actualMappers = new java.util.LinkedHashMap<String, Object>();
    var observations = new ArrayList<String>();
    var connectionIds = new ArrayList<Integer>();
    for (String fieldName : List.of("balanceTccDebitMapper", "userTccReceiveMapper")) {
      Object actualMapper = ReflectionTestUtils.getField(target, fieldName);
      actualMappers.put(fieldName, actualMapper);
      Class<?> mapperType =
          "balanceTccDebitMapper".equals(fieldName)
              ? com.cloudmall.user.mapper.BalanceTccDebitMapper.class
              : com.cloudmall.user.mapper.UserTccReceiveMapper.class;
      Object instrumented =
          java.lang.reflect.Proxy.newProxyInstance(
              actualMapper.getClass().getClassLoader(),
              new Class<?>[] {mapperType},
              (proxy, mapperMethod, arguments) -> {
                if (List.of("selectByPayNo", "findByPayNo").contains(mapperMethod.getName())) {
                  observations.add(
                      fieldName
                          + ":"
                          + org.springframework.transaction.support
                              .TransactionSynchronizationManager.isActualTransactionActive()
                          + ":"
                          + org.springframework.transaction.support
                              .TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                          + ":"
                          + org.springframework.transaction.support
                              .TransactionSynchronizationManager
                              .getCurrentTransactionIsolationLevel());
                  java.sql.Connection observedConnection =
                      org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                          testDataSource);
                  try {
                    connectionIds.add(System.identityHashCode(observedConnection));
                  } finally {
                    org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                        observedConnection, testDataSource);
                  }
                }
                try {
                  return mapperMethod.invoke(actualMapper, arguments);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
      ReflectionTestUtils.setField(target, fieldName, instrumented);
    }
    try {
      var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
      for (boolean ownerPresent : List.of(true, false)) {
        for (String variant :
            List.of(
                "branch-null",
                "branch-known",
                "unknown-state",
                "pay-alias",
                "foreign-user",
                "bad-keys")) {
          checks.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                var original =
                    request("rejected-get-receive-" + ownerPresent + "-" + variant, 7L, "101.00");
                assertEquals(
                    "PAYMENT_FAILED",
                    assertThrows(
                            BizException.class,
                            () -> debitLegacy(original.payNo, original.ledgerKey, 7L, "101.00"))
                        .getCode());
                if (!ownerPresent) fixture.update("DELETE FROM balance_debit_attempt");
                assertEquals(200, operationHttp(original.ledgerKey).getResponse().getStatus());
                try {
                  fixture.update(
                      "INSERT INTO"
                          + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
                          + " VALUES(-119,?,'rejected-get-receive-xid',?,?,?,101,?,?,?,NOW(3),NOW(3))",
                      "pay-alias".equals(variant)
                          ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                          : original.payNo,
                      "branch-known".equals(variant) ? 1L : null,
                      "foreign-user".equals(variant) ? 8L : 7L,
                      original.orderNo,
                      "bad-keys".equals(variant) ? "wrong-operation" : original.operationKey,
                      "bad-keys".equals(variant) ? "wrong-ledger" : original.ledgerKey,
                      "unknown-state".equals(variant) ? "UNKNOWN" : "CLAIMED");
                  observations.clear();
                  connectionIds.clear();
                  var before = allFundsFacts();
                  var receiveBefore =
                      fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id");
                  var response = operationHttp(original.ledgerKey);
                  org.junit.jupiter.api.Assertions.assertAll(
                      () ->
                          assertEquals(
                              409,
                              response.getResponse().getStatus(),
                              ownerPresent + ":" + variant),
                      () -> assertHttpCode(response, "COMMON_CONFLICT"),
                      () -> {
                        var failure =
                            assertThrows(
                                BizException.class,
                                () -> legacy.balanceOperation(7L, original.ledgerKey, 7L));
                        assertEquals("COMMON_CONFLICT", failure.getCode());
                        assertEquals("余额账本事实待核对", failure.getMessage());
                      },
                      () -> assertEquals(before, allFundsFacts()),
                      () ->
                          assertEquals(
                              receiveBefore,
                              fixture.queryForList("SELECT * FROM user_tcc_receive ORDER BY id")),
                      () -> {
                        assertEquals(
                            List.of(
                                "balanceTccDebitMapper:true:true:4",
                                "userTccReceiveMapper:true:true:4",
                                "balanceTccDebitMapper:true:true:4",
                                "userTccReceiveMapper:true:true:4"),
                            observations);
                        assertEquals(4, connectionIds.size());
                        assertEquals(connectionIds.get(0), connectionIds.get(1));
                        assertEquals(connectionIds.get(2), connectionIds.get(3));
                      });
                } finally {
                  fixture.update("DELETE FROM user_tcc_receive WHERE id=-119");
                }
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(checks);
    } finally {
      actualMappers.forEach(
          (fieldName, actualMapper) ->
              ReflectionTestUtils.setField(target, fieldName, actualMapper));
    }
  }

  /** 真实拒款账本不能忽略坏归属/冻结，合法旧余额不和当前钱包重新比较。 */
  @Test
  void rejectedOperationRejectsContradictoryOwnershipWithoutWriting() {
    var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String variant :
        List.of(
            "state-applied",
            "state-trying",
            "user",
            "amount",
            "operation-key",
            "mode-tcc",
            "mode-unknown",
            "xid",
            "branch",
            "pay-alias",
            "freeze",
            "missing-owner-freeze",
            "healthy",
            "history-no-owner")) {
      checks.add(
          () -> {
            for (String table :
                List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
              fixture.update("DELETE FROM " + table);
            fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
            setEnabled(true);
            var original = request("rejected-owner-" + variant, 7L, "101.00");
            assertEquals(
                "PAYMENT_FAILED",
                assertThrows(
                        BizException.class,
                        () -> debitLegacy(original.payNo, original.ledgerKey, 7L, "101.00"))
                    .getCode());
            boolean healthy = List.of("healthy", "history-no-owner").contains(variant);
            switch (variant) {
              case "state-applied" ->
                  fixture.update("UPDATE balance_debit_attempt SET state='APPLIED'");
              case "state-trying" ->
                  fixture.update("UPDATE balance_debit_attempt SET state='TRYING'");
              case "user" -> fixture.update("UPDATE balance_debit_attempt SET user_id=8");
              case "amount" -> fixture.update("UPDATE balance_debit_attempt SET amount=0");
              case "operation-key" ->
                  fixture.update(
                      "UPDATE balance_debit_attempt SET operation_key='foreign-rejected-key'");
              case "mode-tcc" -> fixture.update("UPDATE balance_debit_attempt SET mode='TCC'");
              case "mode-unknown" ->
                  fixture.update("UPDATE balance_debit_attempt SET mode='UNKNOWN'");
              case "xid" ->
                  fixture.update("UPDATE balance_debit_attempt SET xid='ghost-rejected-xid'");
              case "branch" -> fixture.update("UPDATE balance_debit_attempt SET branch_id=1");
              case "pay-alias" ->
                  fixture.update(
                      "UPDATE balance_debit_attempt SET pay_no=?",
                      original.payNo.toUpperCase(java.util.Locale.ROOT));
              case "freeze", "missing-owner-freeze" -> {
                fixture.update(
                    "INSERT INTO"
                        + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
                        + " VALUES(-1,?,'rejected-ghost',1,7,?,?,1,'CANCELLED',100,99,NOW(3),NOW(3))",
                    "tcc-debit:" + original.payNo,
                    original.payNo,
                    original.orderNo);
                if ("missing-owner-freeze".equals(variant))
                  fixture.update("DELETE FROM balance_debit_attempt");
              }
              case "history-no-owner" -> fixture.update("DELETE FROM balance_debit_attempt");
              default -> {}
            }
            if (healthy) fixture.update("UPDATE mall_user SET balance=200 WHERE id=7");
            var before = allFundsFacts();
            var response = operationHttp(original.ledgerKey);
            org.junit.jupiter.api.Assertions.assertAll(
                () ->
                    assertEquals(healthy ? 200 : 409, response.getResponse().getStatus(), variant),
                () -> assertHttpCode(response, healthy ? "0" : "COMMON_CONFLICT"),
                () -> {
                  if (healthy) {
                    var result =
                        (com.cloudmall.user.domain.vo.BalanceOperationVO)
                            legacy.balanceOperation(7L, original.ledgerKey, 7L).data;
                    assertEquals("REJECTED", result.state);
                    assertEquals("101.00", result.amount);
                    assertEquals(null, result.refundOperationKey);
                  } else {
                    var failure =
                        assertThrows(
                            BizException.class,
                            () -> legacy.balanceOperation(7L, original.ledgerKey, 7L));
                    assertEquals("COMMON_CONFLICT", failure.getCode());
                    assertEquals("余额账本事实待核对", failure.getMessage());
                  }
                },
                () -> assertEquals(before, allFundsFacts()));
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(checks);
  }

  /** APPLIED原DEBIT的归属矛盾不能通过DEBIT/REFUND两个GET，真实四表不写。 */
  @Test
  void appliedOperationRejectsContradictoryOwnershipWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : List.of("legacy", "tcc")) {
      for (String variant :
          List.of(
              "owner-state",
              "owner-user",
              "owner-amount",
              "freeze-missing-or-ghost",
              "freeze-state-or-mode",
              "owner-missing-with-freeze")) {
        assertions.add(
            () -> {
              for (String table :
                  List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                fixture.update("DELETE FROM " + table);
              fixture.update(
                  "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
              setEnabled(true);
              var original = request("operation-owner-" + mode + "-" + variant, 7L, "10.00");
              if ("tcc".equals(mode)) {
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
              } else debitLegacy(original.payNo, original.payNo, 7L, "10.00");
              var refund = refundRequest(original, "completed-owner-refund", "2.00");
              legacy.refundInternal(7L, 7L, refund);
              switch (variant) {
                case "owner-state" ->
                    fixture.update("UPDATE balance_debit_attempt SET state='TRYING'");
                case "owner-user" -> fixture.update("UPDATE balance_debit_attempt SET user_id=8");
                case "owner-amount" -> fixture.update("UPDATE balance_debit_attempt SET amount=0");
                case "freeze-state-or-mode" -> {
                  if ("tcc".equals(mode))
                    fixture.update("UPDATE balance_tcc_debit SET state='TRYING'");
                  else fixture.update("UPDATE balance_debit_attempt SET mode='UNKNOWN'");
                }
                case "freeze-missing-or-ghost", "owner-missing-with-freeze" -> {
                  if ("legacy".equals(mode))
                    fixture.update(
                        "INSERT INTO"
                            + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
                            + " VALUES(-1,?,'refund-owner-ghost',1,7,?,?,10,'CONFIRMED',100,90,NOW(3),NOW(3))",
                        original.operationKey,
                        original.payNo,
                        original.orderNo);
                  else if ("freeze-missing-or-ghost".equals(variant))
                    fixture.update("DELETE FROM balance_tcc_debit");
                  if ("owner-missing-with-freeze".equals(variant))
                    fixture.update("DELETE FROM balance_debit_attempt");
                }
                default -> throw new AssertionError(variant);
              }
              var before = allFundsFacts();
              var checks = new ArrayList<org.junit.jupiter.api.function.Executable>();
              for (String key : List.of(original.ledgerKey, refund.refundOperationKey)) {
                checks.add(
                    () -> {
                      var response = operationHttp(key);
                      org.junit.jupiter.api.Assertions.assertAll(
                          () ->
                              assertEquals(
                                  409,
                                  response.getResponse().getStatus(),
                                  mode + ":" + variant + ":" + key),
                          () -> assertHttpCode(response, "COMMON_CONFLICT"),
                          () -> {
                            var failure =
                                assertThrows(
                                    BizException.class, () -> legacy.balanceOperation(7L, key, 7L));
                            assertEquals("COMMON_CONFLICT", failure.getCode());
                            assertEquals("余额账本事实待核对", failure.getMessage());
                          },
                          () -> assertEquals(before, allFundsFacts()));
                    });
              }
              org.junit.jupiter.api.Assertions.assertAll(checks);
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** active已退款原DEBIT正常也不能忽略当前owner/freeze矛盾，Legacy/TCC不二次入账。 */
  @Test
  void activeRefundReplayRejectsContradictoryOwnershipWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : List.of("legacy", "tcc")) {
      for (String variant :
          List.of(
              "owner-state",
              "owner-user",
              "owner-amount",
              "freeze-missing-or-ghost",
              "freeze-state-or-mode",
              "owner-missing-with-freeze")) {
        assertions.add(
            () -> {
              for (String table :
                  List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                fixture.update("DELETE FROM " + table);
              fixture.update(
                  "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
              setEnabled(true);
              var original = request("replay-owner-" + mode + "-" + variant, 7L, "10.00");
              if ("tcc".equals(mode)) {
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
              } else debitLegacy(original.payNo, original.payNo, 7L, "10.00");
              var refund = refundRequest(original, "completed-owner-refund", "2.00");
              legacy.refundInternal(7L, 7L, refund);
              switch (variant) {
                case "owner-state" ->
                    fixture.update("UPDATE balance_debit_attempt SET state='TRYING'");
                case "owner-user" -> fixture.update("UPDATE balance_debit_attempt SET user_id=8");
                case "owner-amount" -> fixture.update("UPDATE balance_debit_attempt SET amount=0");
                case "freeze-state-or-mode" -> {
                  if ("tcc".equals(mode))
                    fixture.update("UPDATE balance_tcc_debit SET state='TRYING'");
                  else fixture.update("UPDATE balance_debit_attempt SET mode='UNKNOWN'");
                }
                case "freeze-missing-or-ghost", "owner-missing-with-freeze" -> {
                  if ("legacy".equals(mode))
                    fixture.update(
                        "INSERT INTO"
                            + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
                            + " VALUES(-1,?,'refund-owner-ghost',1,7,?,?,10,'CONFIRMED',100,90,NOW(3),NOW(3))",
                        original.operationKey,
                        original.payNo,
                        original.orderNo);
                  else if ("freeze-missing-or-ghost".equals(variant))
                    fixture.update("DELETE FROM balance_tcc_debit");
                  if ("owner-missing-with-freeze".equals(variant))
                    fixture.update("DELETE FROM balance_debit_attempt");
                }
                default -> throw new AssertionError(variant);
              }
              var before = allFundsFacts();
              var response =
                  org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                          context.getBean(UserController.class))
                      .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                      .build()
                      .perform(
                          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                  "/api/internal/users/7/balance/refunds")
                              .header("X-User-Id", "7")
                              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                              .content(
                                  new com.fasterxml.jackson.databind.ObjectMapper()
                                      .writeValueAsString(refund)))
                      .andReturn();
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals(409, response.getResponse().getStatus(), mode + ":" + variant),
                  () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                  () -> {
                    var failure =
                        assertThrows(
                            BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                    assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                  },
                  () -> assertEquals(before, allFundsFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 正常Legacy历史旁的同pay TCC冻结不能被重放忽略，双入口不cache/写资金。 */
  @Test
  void legacyDebitReplayRejectsObservedTccFreezeWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : List.of("debits", "compat")) {
      for (String state : List.of("APPLIED", "REJECTED", "history-no-attempt")) {
        for (String variant :
            List.of("TRYING", "CONFIRMED", "CANCELLED", "UNKNOWN", "pay-alias", "foreign")) {
          assertions.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                var request = new BalanceDebitRequestDTO();
                request.payNo = "legacy-replay-freeze-" + entry + "-" + state + "-" + variant;
                request.operationKey =
                    "compat".equals(entry) ? request.payNo : "custom-" + request.payNo;
                request.orderNo = "compat".equals(entry) ? request.payNo : "order-" + request.payNo;
                request.amount = new BigDecimal("REJECTED".equals(state) ? "101.00" : "10.00");
                var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                compatible.paymentKey = request.payNo;
                compatible.amount = request.amount;
                org.junit.jupiter.api.function.Executable debit =
                    () -> {
                      if ("compat".equals(entry)) legacy.debit(7L, 7L, compatible);
                      else legacy.debitInternal(7L, 7L, request);
                    };
                if ("REJECTED".equals(state))
                  assertEquals("PAYMENT_FAILED", assertThrows(BizException.class, debit).getCode());
                else debit.execute();
                if ("history-no-attempt".equals(state))
                  fixture.update("DELETE FROM balance_debit_attempt");
                fixture.update(
                    "INSERT INTO"
                        + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
                        + " VALUES(-1,?,'replay-ghost-xid',1,?,?,?,10,?,100,90,NOW(3),NOW(3))",
                    "tcc-debit:" + request.payNo,
                    "foreign".equals(variant) ? 8L : 7L,
                    "pay-alias".equals(variant)
                        ? request.payNo.toUpperCase(java.util.Locale.ROOT)
                        : request.payNo,
                    request.orderNo,
                    List.of("TRYING", "CONFIRMED", "CANCELLED", "UNKNOWN").contains(variant)
                        ? variant
                        : "TRYING");
                var before = allFundsFacts();
                var cache = context.getBean(StringRedisTemplate.class);
                org.mockito.Mockito.clearInvocations(cache);
                var response =
                    "compat".equals(entry)
                        ? postCompatibleLegacyDebit(compatible)
                        : postLegacyDebit(request);
                org.junit.jupiter.api.Assertions.assertAll(
                    () ->
                        assertEquals(
                            409,
                            response.getResponse().getStatus(),
                            entry + ":" + state + ":" + variant),
                    () -> assertHttpCode(response, "BALANCE_DEBIT_UNKNOWN"),
                    () -> {
                      var failure = assertThrows(BizException.class, debit);
                      assertEquals("BALANCE_DEBIT_UNKNOWN", failure.getCode());
                      assertEquals("扣款来源存在TCC冻结事实", failure.getMessage());
                    },
                    () -> assertEquals(before, allFundsFacts()),
                    () -> org.mockito.Mockito.verifyNoInteractions(cache));
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** disabled既有退款单笔不得超过已证原额，真正等额/部分退款仍正常重放且不二次入账。 */
  @Test
  void disabledRefundReplayKeepsOriginalAmountCapAndHealthyBoundaries() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String sourceAmount : List.of("1.00", "2.00", "3.00")) {
      assertions.add(
          () -> {
            for (String table :
                List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
              fixture.update("DELETE FROM " + table);
            fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
            setEnabled(true);
            var original =
                request(
                    "disabled-replay-cap-" + sourceAmount.replace('.', '-'),
                    7L,
                    "1.00".equals(sourceAmount) ? "10.00" : sourceAmount);
            service.tryDebit(original);
            service.confirmDebit(finalizeRequest(original));
            var refund = refundRequest(original, "completed-disabled-cap", "2.00");
            legacy.refundInternal(7L, 7L, refund);
            if ("1.00".equals(sourceAmount)) {
              fixture.update(
                  "UPDATE balance_ledger SET amount=1,balance_after=99 WHERE operation_key=?",
                  original.ledgerKey);
              fixture.update("UPDATE balance_debit_attempt SET amount=1");
              fixture.update("UPDATE balance_tcc_debit SET amount=1,balance_after_try=99");
            }
            fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
            var before = allFundsFacts();
            var response =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        context.getBean(UserController.class))
                    .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                    .build()
                    .perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/internal/users/7/balance/refunds")
                            .header("X-User-Id", "7")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(refund)))
                    .andReturn();
            if ("1.00".equals(sourceAmount)) {
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals(409, response.getResponse().getStatus()),
                  () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                  () -> {
                    var failure =
                        assertThrows(
                            BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                    assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                  },
                  () -> assertEquals(before, allFundsFacts()));
            } else {
              assertEquals(200, response.getResponse().getStatus());
              legacy.refundInternal(7L, 7L, refund);
              assertEquals(before, allFundsFacts());
            }
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 已完成active退款重放不得只凭REFUND行忽略原DEBIT已缺失或矛盾，两模式不二次入账。 */
  @Test
  void activeRefundReplayRejectsMissingOrCorruptOriginalDebitWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : List.of("legacy", "tcc")) {
      for (String variant :
          List.of("missing", "state", "user", "pay", "order", "snapshot", "amount-smaller")) {
        assertions.add(
            () -> {
              for (String table :
                  List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                fixture.update("DELETE FROM " + table);
              fixture.update(
                  "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
              setEnabled(true);
              var original = request("replay-source-" + mode + "-" + variant, 7L, "10.00");
              if ("tcc".equals(mode)) {
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
              } else debitLegacy(original.payNo, original.payNo, 7L, "10.00");
              var refund = refundRequest(original, "completed-refund-source", "2.00");
              legacy.refundInternal(7L, 7L, refund);
              switch (variant) {
                case "missing" ->
                    fixture.update(
                        "DELETE FROM balance_ledger WHERE operation_key=?", original.ledgerKey);
                case "state" ->
                    fixture.update(
                        "UPDATE balance_ledger SET state='REJECTED' WHERE operation_key=?",
                        original.ledgerKey);
                case "user" ->
                    fixture.update(
                        "UPDATE balance_ledger SET user_id=8 WHERE operation_key=?",
                        original.ledgerKey);
                case "pay" ->
                    fixture.update(
                        "UPDATE balance_ledger SET pay_no=UPPER(pay_no) WHERE operation_key=?",
                        original.ledgerKey);
                case "order" ->
                    fixture.update(
                        "UPDATE balance_ledger SET order_no='other-order' WHERE operation_key=?",
                        original.ledgerKey);
                case "snapshot" ->
                    fixture.update(
                        "UPDATE balance_ledger SET balance_after=95 WHERE operation_key=?",
                        original.ledgerKey);
                case "amount-smaller" ->
                    fixture.update(
                        "UPDATE balance_ledger SET amount=1,balance_after=99 WHERE operation_key=?",
                        original.ledgerKey);
                default -> throw new AssertionError(variant);
              }
              var before = allFundsFacts();
              var response =
                  org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                          context.getBean(UserController.class))
                      .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                      .build()
                      .perform(
                          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                  "/api/internal/users/7/balance/refunds")
                              .header("X-User-Id", "7")
                              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                              .content(
                                  new com.fasterxml.jackson.databind.ObjectMapper()
                                      .writeValueAsString(refund)))
                      .andReturn();
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals(409, response.getResponse().getStatus(), mode + ":" + variant),
                  () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                  () -> {
                    var failure =
                        assertThrows(
                            BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                    assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                    assertEquals("原扣款不存在或未应用", failure.getMessage());
                  },
                  () -> assertEquals(before, allFundsFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 首次Legacy不能忽略残留TCC冻结，正常/不足金额的双入口都不能新增归属或资金行。 */
  @Test
  void freshLegacyDebitRejectsObservedTccFreezeWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : List.of("debits", "compat")) {
      for (boolean insufficient : List.of(false, true)) {
        for (String variant :
            List.of("TRYING", "CANCELLED", "CONFIRMED-ghost", "UNKNOWN", "pay-alias", "foreign")) {
          assertions.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                setEnabled(true);
                var original = request("legacy-freeze-" + entry + "-" + variant, 7L, "10.00");
                service.tryDebit(original);
                if ("CANCELLED".equals(variant)) service.cancelDebit(finalizeRequest(original));
                if ("CONFIRMED-ghost".equals(variant)) {
                  service.confirmDebit(finalizeRequest(original));
                  fixture.update("DELETE FROM balance_ledger");
                }
                if ("UNKNOWN".equals(variant))
                  fixture.update("UPDATE balance_tcc_debit SET state='UNKNOWN'");
                if ("pay-alias".equals(variant))
                  fixture.update("UPDATE balance_tcc_debit SET pay_no=UPPER(pay_no)");
                if ("foreign".equals(variant))
                  fixture.update("UPDATE balance_tcc_debit SET user_id=8");
                fixture.update("DELETE FROM balance_debit_attempt");
                var request = new BalanceDebitRequestDTO();
                request.payNo = original.payNo;
                request.operationKey =
                    "compat".equals(entry) ? request.payNo : "custom-" + request.payNo;
                request.orderNo = "compat".equals(entry) ? request.payNo : original.orderNo;
                request.amount = new BigDecimal(insufficient ? "101.00" : "10.00");
                var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                compatible.paymentKey = request.payNo;
                compatible.amount = request.amount;
                var before = allFundsFacts();
                var cache = context.getBean(StringRedisTemplate.class);
                org.mockito.Mockito.clearInvocations(cache);
                var response =
                    "compat".equals(entry)
                        ? postCompatibleLegacyDebit(compatible)
                        : postLegacyDebit(request);
                org.junit.jupiter.api.Assertions.assertAll(
                    () ->
                        assertEquals(
                            409,
                            response.getResponse().getStatus(),
                            entry + ":" + insufficient + ":" + variant),
                    () -> assertHttpCode(response, "IDEMPOTENCY_CONFLICT"),
                    () -> {
                      var failure =
                          assertThrows(
                              BizException.class,
                              () -> {
                                if ("compat".equals(entry)) legacy.debit(7L, 7L, compatible);
                                else legacy.debitInternal(7L, 7L, request);
                              });
                      assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
                      assertEquals("支付号已有TCC冻结事实", failure.getMessage());
                    },
                    () -> assertEquals(before, allFundsFacts()),
                    () -> org.mockito.Mockito.verifyNoInteractions(cache));
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** operation GET不能忽略同pay未/错关联记录，正常扣款/退款/拒款查询都保持只读。 */
  @Test
  void operationQueriesRejectOrphanPaymentFactsWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String scenario :
        List.of("legacy-debit", "tcc-debit", "legacy-refund", "tcc-refund", "rejected")) {
      for (String variant :
          List.of(
              "orphan", "wrong-origin", "unknown-type", "unknown-state", "pay-alias", "foreign")) {
        assertions.add(
            () -> {
              for (String table :
                  List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                fixture.update("DELETE FROM " + table);
              fixture.update(
                  "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
              setEnabled(true);
              var original =
                  request(
                      "operation-membership-" + scenario + "-" + variant,
                      7L,
                      "rejected".equals(scenario) ? "101.00" : "10.00");
              if ("rejected".equals(scenario))
                assertEquals(
                    "PAYMENT_FAILED",
                    assertThrows(
                            BizException.class,
                            () -> debitLegacy(original.payNo, original.payNo, 7L, "101.00"))
                        .getCode());
              else if (scenario.startsWith("tcc")) {
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
              } else debitLegacy(original.payNo, original.payNo, 7L, "10.00");
              String queryKey = original.payNo;
              if (scenario.endsWith("refund")) {
                var refund = refundRequest(original, "valid-operation-refund", "2.00");
                legacy.refundInternal(7L, 7L, refund);
                queryKey = refund.refundOperationKey;
              }
              fixture.update(
                  "INSERT INTO"
                      + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                      + " VALUES(-1,?,'orphan-operation-query',?,?,?,?,2,?,90,92,NOW(3))",
                  "foreign".equals(variant) ? 8L : 7L,
                  "unknown-type".equals(variant) ? "UNKNOWN" : "REFUND",
                  "wrong-origin".equals(variant) ? "absent-original" : null,
                  "pay-alias".equals(variant)
                      ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                      : original.payNo,
                  original.orderNo,
                  "unknown-state".equals(variant) ? "PENDING" : "APPLIED");
              var before = allFundsFacts();
              var response = operationHttp(queryKey);
              String operationKey = queryKey;
              org.junit.jupiter.api.Assertions.assertAll(
                  () ->
                      assertEquals(
                          409, response.getResponse().getStatus(), scenario + ":" + variant),
                  () -> assertHttpCode(response, "COMMON_CONFLICT"),
                  () -> {
                    var failure =
                        assertThrows(
                            BizException.class,
                            () -> legacy.balanceOperation(7L, operationKey, 7L));
                    assertEquals("COMMON_CONFLICT", failure.getCode());
                    assertEquals("余额账本事实待核对", failure.getMessage());
                  },
                  () -> assertEquals(before, allFundsFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 实际Mapper须102行哨兵、按id保留全type/state/CI/foreign候选，不过滤坏历史。 */
  @Test
  void refundPaymentCandidateQueryPreservesAllRowsAndLimit102() {
    var original = request("refund-sentinel-contract", 7L, "10.00");
    service.tryDebit(original);
    service.confirmDebit(finalizeRequest(original));
    fixture.update("UPDATE balance_ledger SET id=1 WHERE operation_key=?", original.ledgerKey);
    for (long identifier = 2; identifier <= 104; identifier++) {
      fixture.update(
          "INSERT INTO"
              + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
              + " VALUES(?,?,?,?,?,?,0.01,?,100,100.01,NOW(3))",
          identifier,
          identifier % 2 == 0 ? 8L : 7L,
          "sentinel-row-" + identifier,
          identifier % 3 == 0 ? "UNKNOWN" : identifier % 3 == 1 ? "DEBIT" : "REFUND",
          identifier % 2 == 0 ? original.payNo.toUpperCase(java.util.Locale.ROOT) : original.payNo,
          original.orderNo,
          identifier % 2 == 0 ? "PENDING" : "APPLIED");
    }
    var before = allFundsFacts();
    var rows =
        context
            .getBean(com.cloudmall.user.mapper.BalanceLedgerMapper.class)
            .findRefundPaymentLedgerCandidates(original.payNo);
    assertEquals(102, rows.size());
    assertEquals(
        java.util.stream.LongStream.rangeClosed(1, 102).boxed().toList(),
        rows.stream().map(row -> row.id).toList());
    assertTrue(rows.stream().anyMatch(row -> "UNKNOWN".equals(row.operationType)));
    assertTrue(rows.stream().anyMatch(row -> "PENDING".equals(row.state)));
    assertTrue(rows.stream().anyMatch(row -> Long.valueOf(8).equals(row.userId)));
    assertTrue(
        rows.stream()
            .anyMatch(row -> original.payNo.toUpperCase(java.util.Locale.ROOT).equals(row.payNo)));
    assertEquals(before, allFundsFacts());
  }

  /** 未/错关联的同pay退款不能被累计遗漏，真实三模式的新退款不超退或增加账本。 */
  @Test
  void freshRefundRejectsUnlinkedPaymentFactsWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc")) {
      for (boolean partial : List.of(false, true)) {
        for (String variant :
            List.of(
                "origin-null",
                "origin-other",
                "unknown-type",
                "unknown-state",
                "pay-alias",
                "foreign-user")) {
          assertions.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                setEnabled(true);
                var original =
                    request("pay-refund-membership-" + mode + "-" + variant, 7L, "10.00");
                String debitKey =
                    "active-legacy".equals(mode) ? "legacy-membership-source" : original.ledgerKey;
                if ("active-legacy".equals(mode))
                  debitLegacy(original.payNo, debitKey, 7L, "10.00");
                else {
                  service.tryDebit(original);
                  service.confirmDebit(finalizeRequest(original));
                }
                if (partial) {
                  var previous = refundRequest(original, "legitimate-linked-refund", "3.00");
                  previous.originalOperationKey = debitKey;
                  legacy.refundInternal(7L, 7L, previous);
                }
                BigDecimal priorBalance =
                    fixture.queryForObject(
                        "SELECT balance FROM mall_user WHERE id=7", BigDecimal.class);
                fixture.update(
                    "INSERT INTO"
                        + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                        + " VALUES(-1,?,'orphan-payment-refund',?,?,?,?,2,?,?,?,NOW(3))",
                    "foreign-user".equals(variant) ? 8L : 7L,
                    "unknown-type".equals(variant) ? "UNKNOWN" : "REFUND",
                    "origin-other".equals(variant) ? "absent-original-key" : null,
                    "pay-alias".equals(variant)
                        ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                        : original.payNo,
                    original.orderNo,
                    "unknown-state".equals(variant) ? "PENDING" : "APPLIED",
                    priorBalance,
                    priorBalance.add(new BigDecimal("2.00")));
                fixture.update("UPDATE mall_user SET balance=balance+2 WHERE id=7");
                if ("disabled-tcc".equals(mode))
                  fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
                var refund =
                    refundRequest(original, "new-refund-membership", partial ? "7.00" : "10.00");
                refund.originalOperationKey = debitKey;
                var before = allFundsFacts();
                var response =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                            context.getBean(UserController.class))
                        .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                        .build()
                        .perform(
                            org.springframework.test.web.servlet.request.MockMvcRequestBuilders
                                .post("/api/internal/users/7/balance/refunds")
                                .header("X-User-Id", "7")
                                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                                .content(
                                    new com.fasterxml.jackson.databind.ObjectMapper()
                                        .writeValueAsString(refund)))
                        .andReturn();
                org.junit.jupiter.api.Assertions.assertAll(
                    () ->
                        assertEquals(
                            409,
                            response.getResponse().getStatus(),
                            mode + ":" + partial + ":" + variant),
                    () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                    () -> {
                      var failure =
                          assertThrows(
                              BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                      assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                      assertEquals(409, failure.getHttpStatus());
                    },
                    () -> assertEquals(before, allFundsFacts()));
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 有效Legacy归属也不能忽略同pay矛盾TCC冻结，新退款不改变任何资金事实。 */
  @Test
  void activeFreshLegacyRefundRejectsTccFreezeWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mutation :
        List.of("CONFIRMED", "TRYING", "CANCELLED", "UNKNOWN", "pay-alias", "other-user")) {
      assertions.add(
          () -> {
            for (String table :
                List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
              fixture.update("DELETE FROM " + table);
            fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
            setEnabled(true);
            var original = request("active-refund-owner-" + mutation, 7L, "10.00");
            debitLegacy(original.payNo, "legacy-ghost-source", 7L, "10.00");
            fixture.update(
                "INSERT INTO"
                    + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
                    + " VALUES(-1,?,'ghost-owned-xid',1,7,?,?,10,'CONFIRMED',100,90,NOW(3),NOW(3))",
                original.operationKey,
                original.payNo,
                original.orderNo);
            switch (mutation) {
              case "CONFIRMED" -> {}
              case "TRYING", "CANCELLED", "UNKNOWN" ->
                  fixture.update("UPDATE balance_tcc_debit SET state=?", mutation);
              case "pay-alias" ->
                  fixture.update("UPDATE balance_tcc_debit SET pay_no=UPPER(pay_no)");
              case "other-user" -> fixture.update("UPDATE balance_tcc_debit SET user_id=8");
              default -> throw new AssertionError(mutation);
            }
            var refund = refundRequest(original, "active-refund-owner-request", "1.00");
            refund.originalOperationKey = "legacy-ghost-source";
            var before = allFundsFacts();
            var response =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        context.getBean(UserController.class))
                    .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                    .build()
                    .perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/internal/users/7/balance/refunds")
                            .header("X-User-Id", "7")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(refund)))
                    .andReturn();
            org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(409, response.getResponse().getStatus(), mutation),
                () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                () -> {
                  var failure =
                      assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                  assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                  assertEquals(409, failure.getHttpStatus());
                },
                () -> assertEquals(before, allFundsFacts()));
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 缺归属但仍有同pay冻结的新退款不得冒充历史Legacy，任何冻结候选都不入账。 */
  @Test
  void activeFreshRefundRejectsMissingOwnerWithTccFreezeWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mutation :
        List.of("CONFIRMED", "TRYING", "CANCELLED", "UNKNOWN", "pay-alias", "other-user")) {
      assertions.add(
          () -> {
            for (String table :
                List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
              fixture.update("DELETE FROM " + table);
            fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
            setEnabled(true);
            var original = request("active-refund-owner-" + mutation, 7L, "10.00");
            service.tryDebit(original);
            service.confirmDebit(finalizeRequest(original));
            fixture.update("DELETE FROM balance_debit_attempt");
            switch (mutation) {
              case "CONFIRMED" -> {}
              case "TRYING", "CANCELLED", "UNKNOWN" ->
                  fixture.update("UPDATE balance_tcc_debit SET state=?", mutation);
              case "pay-alias" ->
                  fixture.update("UPDATE balance_tcc_debit SET pay_no=UPPER(pay_no)");
              case "other-user" -> fixture.update("UPDATE balance_tcc_debit SET user_id=8");
              default -> throw new AssertionError(mutation);
            }
            var refund = refundRequest(original, "active-refund-owner-request", "1.00");
            var before = allFundsFacts();
            var response =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        context.getBean(UserController.class))
                    .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                    .build()
                    .perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/internal/users/7/balance/refunds")
                            .header("X-User-Id", "7")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(refund)))
                    .andReturn();
            org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(409, response.getResponse().getStatus(), mutation),
                () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                () -> {
                  var failure =
                      assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                  assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                  assertEquals(409, failure.getHttpStatus());
                },
                () -> assertEquals(before, allFundsFacts()));
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 已观察TCC归属的active新退款必须证明确认冻结，坏事实不能触发真实credit。 */
  @Test
  void activeFreshRefundRejectsUnprovenTccOwnerWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mutation :
        List.of(
            "missing-freeze",
            "freeze-state",
            "branch",
            "xid",
            "snapshot",
            "attempt-state",
            "attempt-mode",
            "attempt-amount")) {
      assertions.add(
          () -> {
            for (String table :
                List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
              fixture.update("DELETE FROM " + table);
            fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
            setEnabled(true);
            var original = request("active-refund-owner-" + mutation, 7L, "10.00");
            service.tryDebit(original);
            service.confirmDebit(finalizeRequest(original));
            switch (mutation) {
              case "missing-freeze" -> fixture.update("DELETE FROM balance_tcc_debit");
              case "freeze-state" -> fixture.update("UPDATE balance_tcc_debit SET state='TRYING'");
              case "branch" -> fixture.update("UPDATE balance_tcc_debit SET branch_id=99");
              case "xid" -> fixture.update("UPDATE balance_tcc_debit SET xid='other-owned-xid'");
              case "snapshot" ->
                  fixture.update("UPDATE balance_tcc_debit SET balance_after_try=95");
              case "attempt-state" ->
                  fixture.update("UPDATE balance_debit_attempt SET state='TRYING'");
              case "attempt-mode" ->
                  fixture.update("UPDATE balance_debit_attempt SET mode='UNKNOWN'");
              case "attempt-amount" -> fixture.update("UPDATE balance_debit_attempt SET amount=0");
              default -> throw new AssertionError(mutation);
            }
            var refund = refundRequest(original, "active-refund-owner-request", "1.00");
            var before = allFundsFacts();
            var response =
                org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                        context.getBean(UserController.class))
                    .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                    .build()
                    .perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/internal/users/7/balance/refunds")
                            .header("X-User-Id", "7")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(
                                new com.fasterxml.jackson.databind.ObjectMapper()
                                    .writeValueAsString(refund)))
                    .andReturn();
            org.junit.jupiter.api.Assertions.assertAll(
                () -> assertEquals(409, response.getResponse().getStatus(), mutation),
                () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                () -> {
                  var failure =
                      assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                  assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                  assertEquals(409, failure.getHttpStatus());
                },
                () -> assertEquals(before, allFundsFacts()));
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 已退款重放核完整当前pay集合，错误累计不写资金，合法分笔仍返回原快照。 */
  @Test
  void refundReplayRejectsContradictoryCollectionWithoutWriting() throws Exception {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc")) {
      for (String variant :
          List.of(
              "over-cap",
              "orphan",
              "wrong-origin",
              "state",
              "user",
              "pay-alias",
              "type-unknown",
              "limit101",
              "limit100",
              "partial",
              "full")) {
        assertions.add(
            () -> {
              for (String table :
                  List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                fixture.update("DELETE FROM " + table);
              fixture.update(
                  "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
              setEnabled(true);
              var original =
                  request("refund-replay-collection-" + mode + "-" + variant, 7L, "10.00");
              if ("active-legacy".equals(mode))
                debitLegacy(original.payNo, original.ledgerKey, 7L, "10.00");
              else {
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
              }
              var refund = refundRequest(original, "completed-collection-refund", "2.00");
              legacy.refundInternal(7L, 7L, refund);
              boolean healthy = List.of("partial", "full", "limit100").contains(variant);
              if (healthy && !"limit100".equals(variant)) {
                legacy.refundInternal(
                    7L,
                    7L,
                    refundRequest(
                        original,
                        "other-healthy-refund",
                        "full".equals(variant) ? "8.00" : "1.00"));
              } else {
                BigDecimal extraAmount =
                    new BigDecimal(
                        "over-cap".equals(variant)
                            ? "9.00"
                            : variant.startsWith("limit") ? "0.01" : "1.00");
                for (int index = 1;
                    index
                        <= ("limit101".equals(variant) ? 100 : "limit100".equals(variant) ? 99 : 1);
                    index++) {
                  fixture.update(
                      "INSERT INTO"
                          + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                          + " VALUES(?,?,?,?,?,?,?,?,?,92,?,NOW(3))",
                      -index,
                      "user".equals(variant) ? 8L : 7L,
                      "extra-collection-refund-" + index,
                      "type-unknown".equals(variant) ? "UNKNOWN" : "REFUND",
                      "orphan".equals(variant)
                          ? null
                          : "wrong-origin".equals(variant)
                              ? "unrelated-source"
                              : original.ledgerKey,
                      "pay-alias".equals(variant)
                          ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                          : original.payNo,
                      original.orderNo,
                      extraAmount,
                      "state".equals(variant) ? "UNKNOWN" : "APPLIED",
                      new BigDecimal("92.00").add(extraAmount));
                }
              }
              if ("disabled-tcc".equals(mode)) {
                setEnabled(false);
                fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
              }
              var before = allFundsFacts();
              var response =
                  org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                          context.getBean(UserController.class))
                      .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                      .build()
                      .perform(
                          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                  "/api/internal/users/7/balance/refunds")
                              .header("X-User-Id", "7")
                              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                              .content(
                                  new com.fasterxml.jackson.databind.ObjectMapper()
                                      .writeValueAsString(refund)))
                      .andReturn();
              org.junit.jupiter.api.Assertions.assertAll(
                  () ->
                      assertEquals(
                          healthy ? 200 : 409,
                          response.getResponse().getStatus(),
                          mode + ":" + variant),
                  () -> assertHttpCode(response, healthy ? "0" : "BALANCE_REFUND_INVALID"),
                  () -> {
                    if (healthy)
                      assertEquals(
                          "92.00",
                          ((com.cloudmall.user.domain.vo.BalanceRefundVO)
                                  legacy.refundInternal(7L, 7L, refund).data)
                              .balance);
                    else {
                      var failure =
                          assertThrows(
                              BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                      assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                      assertEquals("退款累计证据不完整或不一致", failure.getMessage());
                    }
                  },
                  () -> assertEquals(before, allFundsFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 已退款重放不能忽略同pay重复DEBIT，真实三模式HTTP/service不增资金。 */
  @Test
  void refundReplayRejectsDuplicateDebitSourcesWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc")) {
      for (String variant :
          List.of(
              "APPLIED", "REJECTED", "UNKNOWN", "type-alias", "pay-alias", "three", "other-pay")) {
        assertions.add(
            () -> {
              for (String table :
                  List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                fixture.update("DELETE FROM " + table);
              fixture.update(
                  "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
              setEnabled(true);
              var original = request("refund-replay-source-" + mode + "-" + variant, 7L, "10.00");
              String debitKey =
                  "active-legacy".equals(mode) ? "legacy-original-key" : original.ledgerKey;
              if ("active-legacy".equals(mode)) debitLegacy(original.payNo, debitKey, 7L, "10.00");
              else {
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
              }
              if ("disabled-tcc".equals(mode)) {
                setEnabled(false);
                fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
              }
              var refund = refundRequest(original, "completed-refund-source-check", "1.00");
              refund.originalOperationKey = debitKey;
              legacy.refundInternal(7L, 7L, refund);
              int extraCount = "three".equals(variant) ? 2 : 1;
              for (int index = 1; index <= extraCount; index++) {
                fixture.update(
                    "INSERT INTO"
                        + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                        + " VALUES(?,7,?,?,?,?,2,?,100,98,NOW(3))",
                    -index,
                    "additional-debit-source-" + index,
                    "type-alias".equals(variant) ? "debit" : "DEBIT",
                    "other-pay".equals(variant)
                        ? "unrelated-refund-replay-pay"
                        : "pay-alias".equals(variant)
                            ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                            : original.payNo,
                    original.orderNo,
                    List.of("APPLIED", "REJECTED", "UNKNOWN").contains(variant)
                        ? variant
                        : "APPLIED");
              }
              var before = allFundsFacts();
              var response =
                  org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                          context.getBean(UserController.class))
                      .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                      .build()
                      .perform(
                          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                  "/api/internal/users/7/balance/refunds")
                              .header("X-User-Id", "7")
                              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                              .content(
                                  new com.fasterxml.jackson.databind.ObjectMapper()
                                      .writeValueAsString(refund)))
                      .andReturn();
              org.junit.jupiter.api.Assertions.assertAll(
                  () ->
                      assertEquals(
                          "other-pay".equals(variant) ? 200 : 409,
                          response.getResponse().getStatus(),
                          mode + ":" + variant),
                  () ->
                      assertHttpCode(
                          response, "other-pay".equals(variant) ? "0" : "BALANCE_REFUND_INVALID"),
                  () -> {
                    if ("other-pay".equals(variant)) {
                      assertEquals(
                          "91.00",
                          ((com.cloudmall.user.domain.vo.BalanceRefundVO)
                                  legacy.refundInternal(7L, 7L, refund).data)
                              .balance);
                      return;
                    }
                    BizException failure =
                        assertThrows(
                            BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                    assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                    assertEquals(409, failure.getHttpStatus());
                    assertEquals("原扣款不存在或未应用", failure.getMessage());
                  },
                  () -> assertEquals(before, allFundsFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 新退款不能只凭固定扣款键忽略同pay重复DEBIT，真实三模式HTTP/service不增资金。 */
  @Test
  void freshRefundRejectsDuplicateDebitSourcesWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc")) {
      for (String variant :
          List.of("APPLIED", "REJECTED", "UNKNOWN", "type-alias", "pay-alias", "three")) {
        assertions.add(
            () -> {
              for (String table :
                  List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                fixture.update("DELETE FROM " + table);
              fixture.update(
                  "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
              setEnabled(true);
              var original = request("refund-source-" + mode + "-" + variant, 7L, "10.00");
              String debitKey =
                  "active-legacy".equals(mode) ? "legacy-original-key" : original.ledgerKey;
              if ("active-legacy".equals(mode)) debitLegacy(original.payNo, debitKey, 7L, "10.00");
              else {
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
              }
              if ("disabled-tcc".equals(mode)) {
                setEnabled(false);
                fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
              }
              int extraCount = "three".equals(variant) ? 2 : 1;
              for (int index = 1; index <= extraCount; index++) {
                fixture.update(
                    "INSERT INTO"
                        + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                        + " VALUES(?,7,?,?,?,?,2,?,100,98,NOW(3))",
                    -index,
                    "additional-debit-source-" + index,
                    "type-alias".equals(variant) ? "debit" : "DEBIT",
                    "pay-alias".equals(variant)
                        ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                        : original.payNo,
                    original.orderNo,
                    List.of("APPLIED", "REJECTED", "UNKNOWN").contains(variant)
                        ? variant
                        : "APPLIED");
              }
              var refund = refundRequest(original, "new-refund-source-check", "1.00");
              refund.originalOperationKey = debitKey;
              var before = allFundsFacts();
              var response =
                  org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                          context.getBean(UserController.class))
                      .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                      .build()
                      .perform(
                          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                  "/api/internal/users/7/balance/refunds")
                              .header("X-User-Id", "7")
                              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                              .content(
                                  new com.fasterxml.jackson.databind.ObjectMapper()
                                      .writeValueAsString(refund)))
                      .andReturn();
              org.junit.jupiter.api.Assertions.assertAll(
                  () -> assertEquals(409, response.getResponse().getStatus(), mode + ":" + variant),
                  () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                  () -> {
                    BizException failure =
                        assertThrows(
                            BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                    assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
                    assertEquals(409, failure.getHttpStatus());
                    assertEquals("原扣款不存在或未应用", failure.getMessage());
                  },
                  () -> assertEquals(before, allFundsFacts()));
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** fresh Legacy不能忽略同pay非DEBIT/坏状态账本而扣款或写REJECTED，双入口四表无写。 */
  @Test
  void legacyFreshDebitRejectsOrphanPaymentLedgerWithoutWrites() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : List.of("debits", "compat")) {
      for (boolean insufficient : List.of(false, true)) {
        for (String variant :
            List.of(
                "refund-applied",
                "refund-unknown",
                "type-unknown",
                "pay-alias",
                "other-user",
                "three",
                "limit101")) {
          assertions.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                var request = new BalanceDebitRequestDTO();
                request.payNo = "legacy-orphan-" + entry + "-" + variant;
                request.operationKey =
                    "compat".equals(entry) ? request.payNo : "custom-" + request.payNo;
                request.orderNo = "compat".equals(entry) ? request.payNo : "order-" + request.payNo;
                request.amount = new BigDecimal(insufficient ? "101.00" : "10.00");
                var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                compatible.paymentKey = request.payNo;
                compatible.amount = request.amount;
                int candidateCount =
                    "limit101".equals(variant) ? 101 : "three".equals(variant) ? 3 : 1;
                for (int index = 1; index <= candidateCount; index++) {
                  fixture.update(
                      "INSERT INTO"
                          + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                          + " VALUES(?,?,?,?,'absent-source',?,?,2,?,100,102,NOW(3))",
                      -index,
                      "other-user".equals(variant) ? 8L : 7L,
                      "orphan-fresh-" + index,
                      "type-unknown".equals(variant) ? "UNKNOWN" : "REFUND",
                      "pay-alias".equals(variant)
                          ? request.payNo.toUpperCase(java.util.Locale.ROOT)
                          : request.payNo,
                      request.orderNo,
                      "refund-unknown".equals(variant) ? "UNKNOWN" : "APPLIED");
                }
                var before = allFundsFacts();
                var cache = context.getBean(StringRedisTemplate.class);
                org.mockito.Mockito.clearInvocations(cache);
                var response =
                    "compat".equals(entry)
                        ? postCompatibleLegacyDebit(compatible)
                        : postLegacyDebit(request);
                org.junit.jupiter.api.Assertions.assertAll(
                    () ->
                        assertEquals(
                            409,
                            response.getResponse().getStatus(),
                            entry + ":" + insufficient + ":" + variant),
                    () -> assertHttpCode(response, "IDEMPOTENCY_CONFLICT"),
                    () -> {
                      BizException failure =
                          assertThrows(
                              BizException.class,
                              () -> {
                                if ("compat".equals(entry)) legacy.debit(7L, 7L, compatible);
                                else legacy.debitInternal(7L, 7L, request);
                              });
                      assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
                      assertEquals(409, failure.getHttpStatus());
                      assertEquals("支付号已有资金账本事实", failure.getMessage());
                    },
                    () -> assertEquals(before, allFundsFacts()),
                    () -> org.mockito.Mockito.verifyNoInteractions(cache));
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 其它pay坏历史不阻断fresh；真实退款及无attempt历史不触发新fresh门，保原重放。 */
  @Test
  void legacyFreshScopeKeepsOtherPaymentsAndRefundedHistoricalReplay() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : List.of("debits", "compat")) {
      for (String state : List.of("APPLIED", "REJECTED", "history-no-attempt")) {
        assertions.add(
            () -> {
              for (String table :
                  List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                fixture.update("DELETE FROM " + table);
              fixture.update(
                  "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
              fixture.update(
                  "INSERT INTO"
                      + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                      + " VALUES(-1,7,'other-pay-bad-history','UNKNOWN','other-pay','other-order',2,'UNKNOWN',100,102,NOW(3))");
              var request = new BalanceDebitRequestDTO();
              request.payNo = "legacy-fresh-healthy-" + entry + "-" + state;
              request.operationKey =
                  "compat".equals(entry) ? request.payNo : "custom-" + request.payNo;
              request.orderNo = "compat".equals(entry) ? request.payNo : "order-" + request.payNo;
              request.amount = new BigDecimal("REJECTED".equals(state) ? "101.00" : "10.00");
              var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
              compatible.paymentKey = request.payNo;
              compatible.amount = request.amount;
              org.junit.jupiter.api.function.Executable debit =
                  () -> {
                    if ("compat".equals(entry)) legacy.debit(7L, 7L, compatible);
                    else legacy.debitInternal(7L, 7L, request);
                  };
              if ("REJECTED".equals(state)) {
                assertEquals("PAYMENT_FAILED", assertThrows(BizException.class, debit).getCode());
                assertFunds(7L, "100.00", "0.00");
                fixture.update("UPDATE mall_user SET balance=200 WHERE id=7");
              } else {
                debit.execute();
                assertFunds(7L, "90.00", "0.00");
                var refund = new BalanceRefundRequestDTO();
                refund.refundOperationKey = "refund-" + request.payNo;
                refund.originalOperationKey = request.operationKey;
                refund.payNo = request.payNo;
                refund.orderNo = request.orderNo;
                refund.amount = new BigDecimal("2.00");
                legacy.refundInternal(7L, 7L, refund);
                assertFunds(7L, "92.00", "0.00");
                if ("history-no-attempt".equals(state))
                  fixture.update("DELETE FROM balance_debit_attempt");
              }
              var before = allFundsFacts();
              var response =
                  "compat".equals(entry)
                      ? postCompatibleLegacyDebit(compatible)
                      : postLegacyDebit(request);
              if ("REJECTED".equals(state)) {
                assertEquals(409, response.getResponse().getStatus());
                assertHttpCode(response, "PAYMENT_FAILED");
                assertEquals("PAYMENT_FAILED", assertThrows(BizException.class, debit).getCode());
              } else {
                assertEquals(200, response.getResponse().getStatus());
                var body =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response.getResponse().getContentAsString());
                assertEquals("APPLIED", body.path("data").path("state").asText());
                assertEquals("90.00", body.path("data").path("balance").asText());
                debit.execute();
              }
              assertEquals(before, allFundsFacts());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** Legacy双入口的真实重复扣款必须稳定409，且不修改四表或触碰余额缓存。 */
  @Test
  void legacyDuplicateDebitHistoryReturnsConflictWithoutWrites() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String entry : List.of("debits", "compat")) {
      for (String state : List.of("fresh", "APPLIED", "REJECTED", "history-no-attempt")) {
        for (String variant :
            List.of("APPLIED", "REJECTED", "UNKNOWN", "type-alias", "pay-alias", "three")) {
          assertions.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                var request = new BalanceDebitRequestDTO();
                request.payNo = "legacy-bound-" + entry + "-" + state + "-" + variant;
                request.operationKey =
                    "compat".equals(entry) ? request.payNo : "custom-" + request.payNo;
                request.orderNo = "compat".equals(entry) ? request.payNo : "order-" + request.payNo;
                request.amount = new BigDecimal("REJECTED".equals(state) ? "101.00" : "10.00");
                var compatible = new com.cloudmall.user.domain.dto.DebitRequestDTO();
                compatible.paymentKey = request.payNo;
                compatible.amount = request.amount;
                if (!"fresh".equals(state)) {
                  if ("REJECTED".equals(state)) {
                    var failure =
                        assertThrows(
                            BizException.class,
                            () ->
                                debitLegacy(
                                    request.payNo,
                                    request.operationKey,
                                    7L,
                                    request.amount.toPlainString()));
                    assertEquals("PAYMENT_FAILED", failure.getCode());
                  } else
                    debitLegacy(
                        request.payNo, request.operationKey, 7L, request.amount.toPlainString());
                  if ("compat".equals(entry)) {
                    fixture.update(
                        "UPDATE balance_ledger SET order_no=? WHERE operation_key=?",
                        request.orderNo,
                        request.operationKey);
                    fixture.update(
                        "UPDATE balance_debit_attempt SET order_no=? WHERE pay_no=?",
                        request.orderNo,
                        request.payNo);
                  }
                  if ("history-no-attempt".equals(state))
                    fixture.update("DELETE FROM balance_debit_attempt");
                }
                int candidateCount = "three".equals(variant) ? 3 : 2;
                int extraCount = "fresh".equals(state) ? candidateCount : candidateCount - 1;
                for (int index = 1; index <= extraCount; index++) {
                  fixture.update(
                      "INSERT INTO"
                          + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                          + " VALUES(?,7,?,?,?,?,2,?,100,98,NOW(3))",
                      -index,
                      "legacy-duplicate-" + index,
                      "type-alias".equals(variant) ? "debit" : "DEBIT",
                      "pay-alias".equals(variant)
                          ? request.payNo.toUpperCase(java.util.Locale.ROOT)
                          : request.payNo,
                      request.orderNo,
                      List.of("APPLIED", "REJECTED", "UNKNOWN").contains(variant)
                          ? variant
                          : "APPLIED");
                }
                var before = allFundsFacts();
                var cache = context.getBean(StringRedisTemplate.class);
                org.mockito.Mockito.clearInvocations(cache);
                var response =
                    "compat".equals(entry)
                        ? postCompatibleLegacyDebit(compatible)
                        : postLegacyDebit(request);
                org.junit.jupiter.api.Assertions.assertAll(
                    () ->
                        assertEquals(
                            409,
                            response.getResponse().getStatus(),
                            entry + ":" + state + ":" + variant),
                    () -> assertHttpCode(response, "IDEMPOTENCY_CONFLICT"),
                    () -> {
                      BizException failure =
                          assertThrows(
                              BizException.class,
                              () -> {
                                if ("compat".equals(entry)) legacy.debit(7L, 7L, compatible);
                                else legacy.debitInternal(7L, 7L, request);
                              });
                      assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
                      assertEquals(409, failure.getHttpStatus());
                      assertEquals("支付号已绑定其他扣款操作", failure.getMessage());
                    },
                    () -> assertEquals(before, allFundsFacts()),
                    () -> org.mockito.Mockito.verifyNoInteractions(cache));
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 真实重复DEBIT必须是原业务409；各资金入口保持四表完整facts。 */
  @Test
  void noFundsEntrancesRejectDuplicateDebitsWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String state : List.of("none", "trying", "cancelled-frozen", "cancelled-empty")) {
      for (String operation :
          "trying".equals(state) ? List.of("try", "confirm", "cancel") : List.of("try", "cancel")) {
        for (String variant :
            List.of("APPLIED", "REJECTED", "UNKNOWN", "type-alias", "pay-alias", "three")) {
          assertions.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                var original =
                    request("bound-" + state + "-" + operation + "-" + variant, 7L, "10.00");
                if (List.of("trying", "cancelled-frozen").contains(state))
                  service.tryDebit(original);
                if (state.startsWith("cancelled")) service.cancelDebit(finalizeRequest(original));
                int duplicateCount = "three".equals(variant) ? 3 : 2;
                for (int index = 1; index <= duplicateCount; index++) {
                  fixture.update(
                      "INSERT INTO"
                          + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                          + " VALUES(?,7,?,?,?,?,2,?,100,98,NOW(3))",
                      -index,
                      "duplicate-debit-" + index,
                      "type-alias".equals(variant) ? "debit" : "DEBIT",
                      "pay-alias".equals(variant)
                          ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                          : original.payNo,
                      original.orderNo,
                      List.of("APPLIED", "REJECTED", "UNKNOWN").contains(variant)
                          ? variant
                          : "APPLIED");
                }
                var before = allFundsFacts();
                BizException failure =
                    assertThrows(
                        BizException.class,
                        () -> {
                          switch (operation) {
                            case "try" -> service.tryDebit(original);
                            case "confirm" -> service.confirmDebit(finalizeRequest(original));
                            case "cancel" -> service.cancelDebit(finalizeRequest(original));
                            default -> throw new AssertionError(operation);
                          }
                        },
                        state + ":" + operation + ":" + variant);
                assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
                assertEquals(409, failure.getHttpStatus());
                assertEquals("支付或账本键已有扣款事实", failure.getMessage());
                assertEquals(before, allFundsFacts());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 同pay非DEBIT原行不能作为无账本证明，含仅attempt空取消的历史重放。 */
  @Test
  void noFundsEntrancesRejectOrphanPaymentLedgerRowsWithoutWriting() {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String state : List.of("none", "trying", "cancelled-frozen", "cancelled-empty")) {
      for (String operation :
          "trying".equals(state) ? List.of("try", "confirm", "cancel") : List.of("try", "cancel")) {
        for (String variant :
            List.of(
                "refund-applied", "refund-unknown", "type-unknown", "pay-alias", "other-user")) {
          assertions.add(
              () -> {
                for (String table :
                    List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                  fixture.update("DELETE FROM " + table);
                fixture.update(
                    "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
                var original =
                    request("nofunds-" + state + "-" + operation + "-" + variant, 7L, "10.00");
                if (List.of("trying", "cancelled-frozen").contains(state))
                  service.tryDebit(original);
                if (state.startsWith("cancelled")) service.cancelDebit(finalizeRequest(original));
                fixture.update(
                    "INSERT INTO"
                        + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                        + " VALUES(-1,?,'orphan-candidate',?,'absent-source',?,?,2,?,100,102,NOW(3))",
                    "other-user".equals(variant) ? 8L : 7L,
                    "type-unknown".equals(variant) ? "UNKNOWN" : "REFUND",
                    "pay-alias".equals(variant)
                        ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                        : original.payNo,
                    original.orderNo,
                    "refund-unknown".equals(variant) ? "UNKNOWN" : "APPLIED");
                var before = allFundsFacts();
                BizException failure =
                    assertThrows(
                        BizException.class,
                        () -> {
                          switch (operation) {
                            case "try" -> service.tryDebit(original);
                            case "confirm" -> service.confirmDebit(finalizeRequest(original));
                            case "cancel" -> service.cancelDebit(finalizeRequest(original));
                            default -> throw new AssertionError(operation);
                          }
                        },
                        state + ":" + operation + ":" + variant);
                assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
                assertEquals(409, failure.getHttpStatus());
                assertEquals(before, allFundsFacts());
              });
        }
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 空取消只核同pay范围，其他pay历史行不扩成全账户门；disabled仍可重放已取消。 */
  @Test
  void emptyCancellationKeepsOtherPaymentsAndDisabledNoOpCompatibility() {
    var original = request("nofunds-healthy", 7L, "10.00");
    fixture.update(
        "INSERT INTO"
            + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
            + " VALUES(-1,7,'other-payment-history','REFUND','other-source','other-pay','other-order',2,'APPLIED',100,102,NOW(3))");
    assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(original)).state);
    var before = allFundsFacts();
    assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(original)).state);
    assertEquals("CANCELLED", service.tryDebit(original).state);
    assertEquals(before, allFundsFacts());
    setEnabled(false);
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
    var disabledBefore = allFundsFacts();
    assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(original)).state);
    assertEquals(disabledBefore, allFundsFacts());
  }

  /** 固定账本健康不等于同pay没有其它DEBIT；重放不得忽略真实重复原事实。 */
  @Test
  void confirmedReplaysRejectAdditionalDebitCandidatesWithoutFundsWrites() {
    var original = request("scope-confirmed", 7L, "10.00");
    service.tryDebit(original);
    service.confirmDebit(finalizeRequest(original));
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String variant :
        List.of(
            "APPLIED",
            "REJECTED",
            "UNKNOWN",
            "type-alias",
            "pay-alias",
            "other-user",
            "other-order",
            "refund-origin",
            "multiple")) {
      for (String operation : List.of("try", "confirm")) {
        assertions.add(
            () -> {
              fixture.update("DELETE FROM balance_ledger WHERE id<0");
              fixture.update(
                  "INSERT INTO"
                      + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                      + " VALUES(-1,?,'additional-debit',?,?,?, ?,10,?,100,90,NOW(3))",
                  "other-user".equals(variant) ? 8L : 7L,
                  "type-alias".equals(variant) ? "debit" : "DEBIT",
                  "refund-origin".equals(variant) ? "other-origin" : null,
                  "pay-alias".equals(variant)
                      ? original.payNo.toUpperCase(java.util.Locale.ROOT)
                      : original.payNo,
                  "other-order".equals(variant) ? "order-other" : original.orderNo,
                  List.of("REJECTED", "UNKNOWN").contains(variant) ? variant : "APPLIED");
              if ("multiple".equals(variant))
                fixture.update(
                    "INSERT INTO"
                        + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                        + " VALUES(-2,7,'another-debit','DEBIT',?,?,10,'APPLIED',100,90,NOW(3))",
                    original.payNo,
                    original.orderNo);
              var before = allFundsFacts();
              BizException failure =
                  assertThrows(
                      BizException.class,
                      () -> {
                        if ("try".equals(operation)) service.tryDebit(original);
                        else service.confirmDebit(finalizeRequest(original));
                      },
                      variant + ":" + operation);
              assertEquals("BALANCE_TCC_UNKNOWN", failure.getCode());
              assertEquals(409, failure.getHttpStatus());
              assertEquals(before, allFundsFacts());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 合法退款及后来钱包变化不改变历史原DEBIT唯一证明，也不阻断disabled确认重放。 */
  @Test
  void confirmedReplayScopeKeepsRefundHistoryAndWalletChanges() {
    var original = request("scope-healthy", 7L, "10.000");
    service.tryDebit(original);
    service.confirmDebit(finalizeRequest(original));
    legacy.refundInternal(7L, 7L, refundRequest(original, "scope-partial-refund", "2.00"));
    fixture.update("UPDATE mall_user SET balance=123.45 WHERE id=7");
    var before = allFundsFacts();
    assertEquals("CONFIRMED", service.tryDebit(original).state);
    assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(original)).state);
    assertEquals(before, allFundsFacts());
    setEnabled(false);
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
    var disabledBefore = allFundsFacts();
    assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(original)).state);
    assertEquals(disabledBefore, allFundsFacts());
    assertEquals(
        "PAY_DEBIT_MODE_UNAVAILABLE",
        assertThrows(BizException.class, () -> service.tryDebit(original)).getCode());
  }

  /** 首次原扣款实际写出NULL，损坏origin后两确认重放均拒绝且四表资金事实不变。 */
  @Test
  void confirmedReplayRejectsNonNullDebitOriginWithoutChangingAnyFundsFact() {
    var original = request("origin-contract", 7L, "10.000");
    assertEquals("TRYING", service.tryDebit(original).state);
    assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(original)).state);
    assertEquals(
        null,
        fixture.queryForObject(
            "SELECT original_operation_key FROM balance_ledger WHERE operation_key=?",
            String.class,
            original.ledgerKey));
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String origin :
        List.of("", "null", "origin-contract", "other-pay", "ORIGIN-CONTRACT", " ")) {
      for (String operation : List.of("try", "confirm")) {
        assertions.add(
            () -> {
              fixture.update(
                  "UPDATE balance_ledger SET original_operation_key=? WHERE operation_key=?",
                  origin,
                  original.ledgerKey);
              var before = allFundsFacts();
              var conflict =
                  assertThrows(
                      BizException.class,
                      () -> {
                        if ("try".equals(operation)) service.tryDebit(original);
                        else service.confirmDebit(finalizeRequest(original));
                      });
              assertEquals(409, conflict.getHttpStatus());
              assertEquals("BALANCE_TCC_UNKNOWN", conflict.getCode());
              assertEquals(before, allFundsFacts());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
    fixture.update(
        "UPDATE balance_ledger SET original_operation_key=NULL WHERE operation_key=?",
        original.ledgerKey);
    var before = allFundsFacts();
    assertEquals("CONFIRMED", service.tryDebit(original).state);
    setEnabled(false);
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
    var disabledBefore = allFundsFacts();
    assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(original)).state);
    assertEquals(disabledBefore, allFundsFacts());
    // 健康try重放未改变原资金事实；disabled测试只由fixture显式修改账号状态。
    assertEquals(before.get(1), disabledBefore.get(1));
    assertEquals(before.get(2), disabledBefore.get(2));
    assertEquals(before.get(3), disabledBefore.get(3));
  }

  /** 真实扣款后只破坏原证明，fresh/disabled replay不得credit或创建退款，失败不改四表。 */
  @Test
  void refundsRejectCorruptOriginalDebitWithoutChangingFundsFacts() {
    fixture.execute(
        "ALTER TABLE balance_ledger MODIFY amount DECIMAL(20,3) NULL, MODIFY balance_before"
            + " DECIMAL(20,3) NULL, MODIFY balance_after DECIMAL(20,3) NULL");
    java.util.List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    try {
      for (String mode :
          List.of("active-legacy", "active-tcc", "disabled-fresh", "disabled-replay")) {
        for (String mutation :
            List.of(
                "operation_key=UPPER(operation_key)",
                "operation_type='debit'",
                "original_operation_key='null'",
                "original_operation_key=''",
                "amount=NULL",
                "amount=10.001",
                "balance_before=NULL",
                "balance_after=-1",
                "balance_after=95")) {
          assertions.add(
              () -> {
                closeContext();
                createTransactionalServices();
                var original = request("refund-proof", 7L, "10.00");
                String debitKey =
                    "active-legacy".equals(mode) ? "custom-refund-proof" : original.ledgerKey;
                if ("active-legacy".equals(mode))
                  debitLegacy(original.payNo, debitKey, 7L, "10.00");
                else {
                  service.tryDebit(original);
                  service.confirmDebit(finalizeRequest(original));
                }
                var refund = refundRequest(original, "refund-proof-key", "10.00");
                refund.originalOperationKey = debitKey;
                if ("disabled-replay".equals(mode)) legacy.refundInternal(7L, 7L, refund);
                if (mode.startsWith("disabled"))
                  fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
                fixture.update(
                    "UPDATE balance_ledger SET " + mutation + " WHERE operation_key=?", debitKey);
                var before = allFundsFacts();
                var failure =
                    assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                assertEquals(mode.startsWith("disabled") ? 404 : 409, failure.getHttpStatus());
                assertEquals(
                    mode.startsWith("disabled") ? "COMMON_NOT_FOUND" : "BALANCE_REFUND_INVALID",
                    failure.getCode());
                assertEquals(before, allFundsFacts());
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    } finally {
      fixture.update("DELETE FROM balance_ledger");
      fixture.execute(
          "ALTER TABLE balance_ledger MODIFY amount DECIMAL(18,2) NOT NULL, MODIFY balance_before"
              + " DECIMAL(18,2) NOT NULL, MODIFY balance_after DECIMAL(18,2) NOT NULL");
    }
  }

  /** 原零余额快照由真实全额扣款产生，active/disabled健康refund只credit一次。 */
  @Test
  void healthyRefundOriginalProofKeepsZeroAfterBalanceAndDisabledReplay() {
    for (boolean disabled : List.of(false, true)) {
      closeContext();
      createTransactionalServices();
      var original = request("refund-valid", 7L, "100.000");
      service.tryDebit(original);
      service.confirmDebit(finalizeRequest(original));
      assertFunds(7L, "0.00", "0.00");
      if (disabled) fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
      var refund = refundRequest(original, "refund-valid-key", "100.000");
      legacy.refundInternal(7L, 7L, refund);
      assertFunds(7L, "100.00", "0.00");
      var before = allFundsFacts();
      legacy.refundInternal(7L, 7L, refund);
      assertEquals(before, allFundsFacts());
      assertEquals(2, ledgerCount());
    }
  }

  /** 实际Controller调用真实事务代理，原来源损坏沿active409/disabled404且不改资金。 */
  @Test
  void refundHttpRejectsCorruptOriginalDebitInActualTransactions() throws Exception {
    for (String mode : List.of("active", "disabled-fresh", "disabled-replay")) {
      for (String mutation :
          List.of(
              "operation_key=UPPER(operation_key)",
              "original_operation_key='null'",
              "balance_after=95")) {
        closeContext();
        createTransactionalServices();
        var original = request("http-proof", 7L, "10.00");
        service.tryDebit(original);
        service.confirmDebit(finalizeRequest(original));
        var refund = refundRequest(original, "refund-http-proof", "10.00");
        if ("disabled-replay".equals(mode)) legacy.refundInternal(7L, 7L, refund);
        if (mode.startsWith("disabled")) fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
        fixture.update(
            "UPDATE balance_ledger SET " + mutation + " WHERE operation_key=?", original.ledgerKey);
        var before = allFundsFacts();
        var mvc =
            org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                    context.getBean(UserController.class))
                .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                .build();
        String payload =
            new com.fasterxml.jackson.databind.ObjectMapper()
                .writeValueAsString(
                    Map.of(
                        "refundOperationKey",
                        refund.refundOperationKey,
                        "originalOperationKey",
                        refund.originalOperationKey,
                        "payNo",
                        refund.payNo,
                        "orderNo",
                        refund.orderNo,
                        "amount",
                        refund.amount.toPlainString()));
        mvc.perform(
                org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                        "/api/internal/users/7/balance/refunds")
                    .header("X-User-Id", "7")
                    .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                    .content(payload))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                    .is(mode.startsWith("disabled") ? 404 : 409))
            .andExpect(
                org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code")
                    .value(
                        mode.startsWith("disabled")
                            ? "COMMON_NOT_FOUND"
                            : "BALANCE_REFUND_INVALID"));
        assertEquals(before, allFundsFacts());
      }
    }
  }

  /** 非法精度/容量不得创建零金额事实或部分扣退款，HTTP与真实事务错误均为400。 */
  @Test
  void legacyRequestMoneyDomainRejectsRoundingAndOverflowWithoutWriting() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String operation : List.of("debit", "refund")) {
      for (String amountText : List.of("0.001", "10.001", "10000000000000000.00")) {
        assertions.add(
            () -> {
              closeContext();
              createTransactionalServices();
              var original = request("money-invalid", 7L, "10.00");
              if ("refund".equals(operation)) {
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
              }
              var before = allFundsFacts();
              var failure =
                  assertThrows(
                      BizException.class,
                      () -> {
                        if ("debit".equals(operation))
                          debitLegacy("money-invalid", "money-invalid", 7L, amountText);
                        else
                          legacy.refundInternal(
                              7L, 7L, refundRequest(original, "refund-money-invalid", amountText));
                      });
              assertEquals(400, failure.getHttpStatus());
              assertEquals("COMMON_INVALID_ARGUMENT", failure.getCode());
              assertEquals(before, allFundsFacts());
              var mvc =
                  org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                          context.getBean(UserController.class))
                      .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                      .build();
              Map<String, String> body =
                  "debit".equals(operation)
                      ? Map.of(
                          "operationKey",
                          "money-invalid",
                          "payNo",
                          "money-invalid",
                          "orderNo",
                          "order-money-invalid",
                          "amount",
                          amountText)
                      : Map.of(
                          "refundOperationKey",
                          "refund-money-invalid",
                          "originalOperationKey",
                          original.ledgerKey,
                          "payNo",
                          original.payNo,
                          "orderNo",
                          original.orderNo,
                          "amount",
                          amountText);
              String payload =
                  new com.fasterxml.jackson.databind.ObjectMapper().writeValueAsString(body);
              mvc.perform(
                      org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                              "/api/internal/users/7/balance/"
                                  + ("debit".equals(operation) ? "debits" : "refunds"))
                          .header("X-User-Id", "7")
                          .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                          .content(payload))
                  .andExpect(
                      org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                          .isBadRequest())
                  .andExpect(
                      org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                              "$.code")
                          .value("COMMON_INVALID_ARGUMENT"));
              assertEquals(before, allFundsFacts());
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** cent/合法尾零的实际余额和账本一致，上限合法但余额不足仍沿原PAYMENT_FAILED。 */
  @Test
  void legacyValidMoneyKeepsCentTailZerosMaximumAndInsufficientFundsProtocol() {
    for (String amountText : List.of("0.01", "0.010", "10.000")) {
      closeContext();
      createTransactionalServices();
      BigDecimal amount = new BigDecimal(amountText);
      var original = request("money-valid", 7L, amountText);
      debitLegacy(original.payNo, original.ledgerKey, 7L, amountText);
      assertFunds(
          7L,
          new BigDecimal("100.00")
              .subtract(amount)
              .setScale(2, java.math.RoundingMode.UNNECESSARY)
              .toPlainString(),
          "0.00");
      legacy.refundInternal(7L, 7L, refundRequest(original, "refund-money-valid", amountText));
      assertFunds(7L, "100.00", "0.00");
      for (BigDecimal stored :
          fixture.queryForList("SELECT amount FROM balance_ledger ORDER BY id", BigDecimal.class))
        assertEquals(0, stored.compareTo(amount));
    }
    closeContext();
    createTransactionalServices();
    var failure =
        assertThrows(
            BizException.class,
            () -> debitLegacy("money-max", "money-max", 7L, "9999999999999999.99"));
    assertEquals(409, failure.getHttpStatus());
    assertEquals("PAYMENT_FAILED", failure.getCode());
    assertFunds(7L, "100.00", "0.00");
    assertEquals(1, ledgerCount());
    assertEquals(
        "REJECTED", fixture.queryForObject("SELECT state FROM balance_ledger", String.class));
    assertEquals(
        new BigDecimal("9999999999999999.99"),
        fixture.queryForObject("SELECT amount FROM balance_ledger", BigDecimal.class));
  }

  /** 原DEBIT正确但wallet损坏，不得由refund实际写资金；宽列只限owned并恢复。 */
  @Test
  void refundMalformedWalletAndOverflowLeaveRealFundsFactsUntouched() throws Exception {
    fixture.execute("ALTER TABLE mall_user MODIFY balance DECIMAL(20,3) NULL");
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    try {
      for (boolean disabled : List.of(false, true)) {
        for (String balance :
            new String[] {null, "-1.00", "90.001", "10000000000000000.00", "9999999999999999.99"}) {
          assertions.add(
              () -> {
                closeContext();
                createTransactionalServices();
                var original = request("wallet-proof", 7L, "10.00");
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
                fixture.update(
                    "UPDATE mall_user SET balance=?,status=? WHERE id=7",
                    balance,
                    disabled ? 0 : 1);
                var before = allFundsFacts();
                var refund = refundRequest(original, "refund-wallet-proof", "10.00");
                var failure =
                    assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                assertEquals("BALANCE_REFUND_FAILED", failure.getCode());
                assertEquals(409, failure.getHttpStatus());
                assertEquals(before, allFundsFacts());
                var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                            context.getBean(UserController.class))
                        .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                        .build();
                String payload =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                            Map.of(
                                "refundOperationKey",
                                refund.refundOperationKey,
                                "originalOperationKey",
                                refund.originalOperationKey,
                                "payNo",
                                refund.payNo,
                                "orderNo",
                                refund.orderNo,
                                "amount",
                                "10.00"));
                mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/internal/users/7/balance/refunds")
                            .header("X-User-Id", "7")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isConflict())
                    .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                "$.code")
                            .value("BALANCE_REFUND_FAILED"));
                assertEquals(before, allFundsFacts());
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    } finally {
      fixture.update("DELETE FROM mall_user");
      fixture.execute(
          "ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NOT NULL DEFAULT 10000.00");
    }
  }

  /** 实际credit已执行后故障代理使refund insert返回0/2，Runtime须回滚全部资金。 */
  @Test
  void refundLedgerRowCountFailureRollsBackRealCreditInSpringTransaction() {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (boolean disabled : List.of(false, true)) {
      for (int insertedRows : List.of(0, 2)) {
        assertions.add(
            () -> {
              closeContext();
              createTransactionalServices();
              var original = request("ledger-atomic", 7L, "10.00");
              service.tryDebit(original);
              service.confirmDebit(finalizeRequest(original));
              if (disabled) fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
              var before = allFundsFacts();
              var actual = context.getBean(com.cloudmall.user.mapper.BalanceLedgerMapper.class);
              java.util.concurrent.atomic.AtomicInteger intercepted =
                  new java.util.concurrent.atomic.AtomicInteger();
              var fault =
                  (com.cloudmall.user.mapper.BalanceLedgerMapper)
                      java.lang.reflect.Proxy.newProxyInstance(
                          actual.getClass().getClassLoader(),
                          new Class<?>[] {com.cloudmall.user.mapper.BalanceLedgerMapper.class},
                          (proxy, method, arguments) -> {
                            if ("insert".equals(method.getName())
                                && arguments[0]
                                    instanceof com.cloudmall.user.domain.po.BalanceLedgerPO ledger
                                && "REFUND".equals(ledger.operationType)) {
                              intercepted.incrementAndGet();
                              // 同线程JdbcTemplate加入原事务，直接观察真实credit已发生。
                              assertEquals(
                                  new BigDecimal("100.00"),
                                  fixture.queryForObject(
                                      "SELECT balance FROM mall_user WHERE id=7",
                                      BigDecimal.class));
                              return insertedRows;
                            }
                            try {
                              return method.invoke(actual, arguments);
                            } catch (java.lang.reflect.InvocationTargetException failure) {
                              throw failure.getCause();
                            }
                          });
              Object target = AopTestUtils.getUltimateTargetObject(legacy);
              ReflectionTestUtils.setField(target, "balanceLedgerMapper", fault);
              try {
                assertThrows(
                    IllegalStateException.class,
                    () ->
                        legacy.refundInternal(
                            7L, 7L, refundRequest(original, "refund-ledger-atomic", "10.00")));
                assertEquals(1, intercepted.get());
                assertEquals(before, allFundsFacts());
                assertFunds(7L, "90.00", "0.00");
                assertEquals(1, ledgerCount());
              } finally {
                ReflectionTestUtils.setField(target, "balanceLedgerMapper", actual);
              }
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 余额0/尾零/MAX-after真实credit及ledger完全一致，之后重放不二次入账。 */
  @Test
  void refundHealthyWalletBoundsKeepExactSnapshotAndReplay() {
    for (String balance : List.of("0.00", "90.00", "9999999999999989.99")) {
      closeContext();
      createTransactionalServices();
      var original = request("wallet-valid", 7L, "10.000");
      service.tryDebit(original);
      service.confirmDebit(finalizeRequest(original));
      fixture.update("UPDATE mall_user SET balance=? WHERE id=7", balance);
      var refund = refundRequest(original, "refund-wallet-valid", "10.000");
      legacy.refundInternal(7L, 7L, refund);
      BigDecimal expected = new BigDecimal(balance).add(new BigDecimal("10.00"));
      assertFunds(7L, expected.toPlainString(), "0.00");
      var row =
          fixture.queryForMap(
              "SELECT balance_before,balance_after FROM balance_ledger WHERE"
                  + " operation_type='REFUND'");
      assertEquals(new BigDecimal(balance), row.get("balance_before"));
      assertEquals(expected, row.get("balance_after"));
      var before = allFundsFacts();
      legacy.refundInternal(7L, 7L, refund);
      assertEquals(before, allFundsFacts());
      assertEquals(2, ledgerCount());
    }
  }

  /** 已实际refund后仅破坏其原row，CI key/state/null/delta不能200重放，钱不得二次写。 */
  @Test
  void persistedRefundReplayRejectsCorruptOwnedLedgerThroughRealHttp() {
    fixture.execute(
        "ALTER TABLE balance_ledger MODIFY amount DECIMAL(20,3) NULL, MODIFY balance_before"
            + " DECIMAL(20,3) NULL, MODIFY balance_after DECIMAL(20,3) NULL");
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    try {
      for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc")) {
        for (String mutation :
            List.of(
                "operation_key=UPPER(operation_key)",
                "state='REJECTED'",
                "amount=NULL",
                "balance_before=NULL",
                "balance_before=-1",
                "balance_after=NULL",
                "balance_after=100.001",
                "balance_after=99")) {
          assertions.add(
              () -> {
                closeContext();
                createTransactionalServices();
                var original = request("replay-proof", 7L, "10.00");
                String debitKey =
                    "active-legacy".equals(mode) ? "custom-replay-proof" : original.ledgerKey;
                if ("active-legacy".equals(mode))
                  debitLegacy(original.payNo, debitKey, 7L, "10.00");
                else {
                  service.tryDebit(original);
                  service.confirmDebit(finalizeRequest(original));
                }
                var refund = refundRequest(original, "refund-replay-proof", "10.00");
                refund.originalOperationKey = debitKey;
                legacy.refundInternal(7L, 7L, refund);
                if (mode.startsWith("disabled"))
                  fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
                fixture.update(
                    "UPDATE balance_ledger SET " + mutation + " WHERE operation_key=?",
                    refund.refundOperationKey);
                var before = allFundsFacts();
                var failure =
                    assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                assertEquals(409, failure.getHttpStatus());
                assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
                assertEquals(before, allFundsFacts());
                var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                            context.getBean(UserController.class))
                        .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                        .build();
                String payload =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                            Map.of(
                                "refundOperationKey",
                                refund.refundOperationKey,
                                "originalOperationKey",
                                refund.originalOperationKey,
                                "payNo",
                                refund.payNo,
                                "orderNo",
                                refund.orderNo,
                                "amount",
                                "10.00"));
                mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/internal/users/7/balance/refunds")
                            .header("X-User-Id", "7")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isConflict())
                    .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                "$.code")
                            .value("IDEMPOTENCY_CONFLICT"));
                assertEquals(before, allFundsFacts());
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    } finally {
      fixture.update("DELETE FROM balance_ledger");
      fixture.execute(
          "ALTER TABLE balance_ledger MODIFY amount DECIMAL(18,2) NOT NULL, MODIFY balance_before"
              + " DECIMAL(18,2) NOT NULL, MODIFY balance_after DECIMAL(18,2) NOT NULL");
    }
  }

  /** 历史合法refund快照与当前wallet无因果等价，既有active/disabled重放不二次credit。 */
  @Test
  void persistedRefundReplayKeepsHealthyHistoryAndChangingWallet() throws Exception {
    for (boolean disabled : List.of(false, true)) {
      closeContext();
      createTransactionalServices();
      var original = request("replay-valid", 7L, "10.000");
      service.tryDebit(original);
      service.confirmDebit(finalizeRequest(original));
      fixture.update("UPDATE mall_user SET balance=0 WHERE id=7");
      var refund = refundRequest(original, "refund-replay-valid", "10.000");
      legacy.refundInternal(7L, 7L, refund);
      fixture.update("UPDATE mall_user SET balance=50,status=? WHERE id=7", disabled ? 0 : 1);
      var before = allFundsFacts();
      legacy.refundInternal(7L, 7L, refund);
      assertEquals(before, allFundsFacts());
      assertFunds(7L, "50.00", "0.00");
      assertEquals(2, ledgerCount());
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  context.getBean(UserController.class))
              .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
              .build();
      String payload =
          new com.fasterxml.jackson.databind.ObjectMapper()
              .writeValueAsString(
                  Map.of(
                      "refundOperationKey",
                      refund.refundOperationKey,
                      "originalOperationKey",
                      refund.originalOperationKey,
                      "payNo",
                      refund.payNo,
                      "orderNo",
                      refund.orderNo,
                      "amount",
                      "10.000"));
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                      "/api/internal/users/7/balance/refunds")
                  .header("X-User-Id", "7")
                  .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                  .content(payload))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk());
      assertEquals(before, allFundsFacts());
    }
  }

  /** 确认后仅破坏原冻结/归属事实，禁用fresh/replay不得放行，HTTP亦不二次credit。 */
  @Test
  void disabledRefundRejectsMissingOrCorruptConfirmedFreezeInRealTransactions() {
    fixture.execute("ALTER TABLE balance_debit_attempt MODIFY amount DECIMAL(18,2) NULL");
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    try {
      for (boolean replay : List.of(false, true)) {
        for (String mutation :
            List.of("missing", "state", "branch", "xid", "snapshot", "attempt-null")) {
          assertions.add(
              () -> {
                closeContext();
                createTransactionalServices();
                var original = request("freeze-proof", 7L, "10.000");
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
                var refund = refundRequest(original, "refund-freeze-proof", "10.00");
                if (replay) legacy.refundInternal(7L, 7L, refund);
                fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
                switch (mutation) {
                  case "missing" -> fixture.update("DELETE FROM balance_tcc_debit");
                  case "state" -> fixture.update("UPDATE balance_tcc_debit SET state='TRYING'");
                  case "branch" -> fixture.update("UPDATE balance_tcc_debit SET branch_id=99");
                  case "xid" ->
                      fixture.update("UPDATE balance_tcc_debit SET xid='other-owned-xid'");
                  case "snapshot" ->
                      fixture.update("UPDATE balance_tcc_debit SET balance_after_try=95");
                  case "attempt-null" ->
                      fixture.update("UPDATE balance_debit_attempt SET amount=NULL");
                  default -> throw new AssertionError(mutation);
                }
                var before = allFundsFacts();
                var failure =
                    assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                assertEquals(404, failure.getHttpStatus());
                assertEquals("COMMON_NOT_FOUND", failure.getCode());
                assertEquals(before, allFundsFacts());
                var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                            context.getBean(UserController.class))
                        .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                        .build();
                String payload =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                            Map.of(
                                "refundOperationKey",
                                refund.refundOperationKey,
                                "originalOperationKey",
                                refund.originalOperationKey,
                                "payNo",
                                refund.payNo,
                                "orderNo",
                                refund.orderNo,
                                "amount",
                                "10.00"));
                mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/internal/users/7/balance/refunds")
                            .header("X-User-Id", "7")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isNotFound())
                    .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                "$.code")
                            .value("COMMON_NOT_FOUND"));
                assertEquals(before, allFundsFacts());
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    } finally {
      fixture.update("DELETE FROM balance_debit_attempt");
      fixture.execute("ALTER TABLE balance_debit_attempt MODIFY amount DECIMAL(18,2) NOT NULL");
    }
  }

  @Test
  void confirmIsAtomicIdempotentAndStillWorksWhenDisabled() {
    BalanceTccDebitTryDTO request = request("confirmed", 7L, "10.00");
    assertEquals("TRYING", service.tryDebit(request).state);
    assertEquals("TRYING", service.tryDebit(request).state);
    assertFunds(7L, "90.00", "10.00");
    assertEquals(0, ledgerCount());
    setEnabled(false);
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
    assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(request)).state);
    assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(request)).state);
    assertFunds(7L, "90.00", "0.00");
    assertEquals(1, ledgerCount());
    assertEquals(
        "APPLIED", fixture.queryForObject("SELECT state FROM balance_ledger", String.class));
    assertThrows(BizException.class, () -> service.cancelDebit(finalizeRequest(request)));
    assertFunds(7L, "90.00", "0.00");
  }

  @Test
  void cancelReleasesOnceEvenForDisabledUserAndRejectsLateTry() {
    BalanceTccDebitTryDTO request = request("cancelled", 7L, "10.00");
    service.tryDebit(request);
    setEnabled(false);
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
    assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(request)).state);
    assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(request)).state);
    assertFunds(7L, "100.00", "0.00");
    setEnabled(true);
    fixture.update("UPDATE mall_user SET status=1 WHERE id=7");
    assertEquals("CANCELLED", service.tryDebit(request).state);
    assertEquals(0, ledgerCount());
    assertFunds(7L, "100.00", "0.00");
  }

  @Test
  void emptyCancelPersistsOwnershipWithoutCreatingMoneyAndPreventsLateTry() {
    BalanceTccDebitTryDTO request = request("empty-cancel", 7L, "10.00");
    assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(request)).state);
    assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(request)).state);
    assertEquals("CANCELLED", service.tryDebit(request).state);
    assertThrows(
        BizException.class, () -> debitLegacy(request.payNo, "different-key", 7L, "10.00"));
    assertFunds(7L, "100.00", "0.00");
    assertEquals(0, ledgerCount());
    assertEquals(
        1, fixture.queryForObject("SELECT COUNT(*) FROM balance_debit_attempt", Integer.class));
  }

  @Test
  void rejectedTryLeavesNoOwnershipOrFrozenFunds() {
    BalanceTccDebitTryDTO request = request("rejected", 7L, "101.00");
    assertThrows(BizException.class, () -> service.tryDebit(request));
    setEnabled(false);
    request.amount = new BigDecimal("10.00");
    assertThrows(BizException.class, () -> service.tryDebit(request));
    setEnabled(true);
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
    assertThrows(BizException.class, () -> service.tryDebit(request));
    assertFunds(7L, "100.00", "0.00");
    assertEquals(0, ledgerCount());
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM balance_debit_attempt", Integer.class));
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM balance_tcc_debit", Integer.class));
  }

  @Test
  void immutablePayloadAndBranchRejectConflictingRetries() {
    BalanceTccDebitTryDTO original = request("immutable", 7L, "10.00");
    service.tryDebit(original);
    for (int variant = 0; variant < 5; variant++) {
      BalanceTccDebitTryDTO changed = request("immutable", 7L, "10.00");
      if (variant == 0) changed.xid = "another-xid";
      if (variant == 1) changed.branchId = 99L;
      if (variant == 2) changed.amount = new BigDecimal("11.00");
      if (variant == 3) changed.orderNo = "another-order";
      if (variant == 4) changed.userId = 8L;
      assertThrows(BizException.class, () -> service.tryDebit(changed));
      assertThrows(BizException.class, () -> service.confirmDebit(finalizeRequest(changed)));
      assertThrows(BizException.class, () -> service.cancelDebit(finalizeRequest(changed)));
    }
    assertFunds(7L, "90.00", "10.00");
    assertFunds(8L, "100.00", "0.00");
    assertEquals(0, ledgerCount());
  }

  @Test
  void historicalLegacyDebitWithDifferentOperationKeyBlocksTccAndDuplicateDebit() {
    debitLegacy("legacy-history", "legacy-custom-key", 7L, "10.00");
    fixture.update("DELETE FROM balance_debit_attempt");
    BalanceTccDebitTryDTO request = request("legacy-history", 7L, "10.00");
    assertThrows(BizException.class, () -> service.tryDebit(request));
    debitLegacy("legacy-history", "legacy-custom-key", 7L, "10.00");
    assertThrows(BizException.class, () -> debitLegacy("legacy-history", "new-key", 7L, "10.00"));
    assertFunds(7L, "90.00", "0.00");
    assertEquals(1, ledgerCount());
  }

  @Test
  void tccOwnershipBlocksLegacyAndConfirmedDebitStillSupportsOneRefund() {
    BalanceTccDebitTryDTO request = request("tcc-refund", 7L, "10.00");
    service.tryDebit(request);
    assertThrows(BizException.class, () -> debitLegacy(request.payNo, request.payNo, 7L, "10.00"));
    service.confirmDebit(finalizeRequest(request));
    assertThrows(BizException.class, () -> debitLegacy(request.payNo, request.payNo, 7L, "10.00"));
    BalanceRefundRequestDTO refund = new BalanceRefundRequestDTO();
    refund.refundOperationKey = "refund:" + request.payNo;
    refund.originalOperationKey = request.payNo;
    refund.payNo = request.payNo;
    refund.orderNo = request.orderNo;
    refund.amount = request.amount;
    legacy.refundInternal(7L, 7L, refund);
    legacy.refundInternal(7L, 7L, refund);
    assertFunds(7L, "100.00", "0.00");
    assertEquals(2, ledgerCount());
  }

  @Test
  void disabledConfirmedTccRefundLeavesOtherTryingFreezeAndRejectsOverRefund() {
    BalanceTccDebitTryDTO confirmed = request("disabled-confirmed", 7L, "10.00");
    service.tryDebit(confirmed);
    service.confirmDebit(finalizeRequest(confirmed));
    service.tryDebit(request("other-trying", 7L, "20.00"));
    setEnabled(false);
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");

    BalanceRefundRequestDTO refund = refundRequest(confirmed, "refund:disabled-confirmed", "10.00");
    legacy.refundInternal(7L, 7L, refund);
    legacy.refundInternal(7L, 7L, refund);
    assertFunds(7L, "80.00", "20.00");
    assertEquals(2, ledgerCount());

    BalanceRefundRequestDTO excess =
        refundRequest(confirmed, "refund:disabled-confirmed-extra", "11.00");
    assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, excess));
    assertFunds(7L, "80.00", "20.00");
  }

  @Test
  void disabledLegacyInitialAndExistingRefundReplayRemainNotFound() {
    debitLegacy("disabled-legacy", "legacy-disabled-key", 7L, "10.00");
    setEnabled(false);
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
    BalanceRefundRequestDTO refund = new BalanceRefundRequestDTO();
    refund.refundOperationKey = "refund:disabled-legacy";
    refund.originalOperationKey = "legacy-disabled-key";
    refund.payNo = "disabled-legacy";
    refund.orderNo = "order-disabled-legacy";
    refund.amount = new BigDecimal("10.00");
    assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
    fixture.update(
        "INSERT INTO"
            + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
            + " VALUES(999,7,?,?,?,?,?,?,'APPLIED',90,100,NOW(3))",
        "refund:disabled-legacy",
        "REFUND",
        "legacy-disabled-key",
        "disabled-legacy",
        "order-disabled-legacy",
        new BigDecimal("10.00"));
    assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
    assertFunds(7L, "90.00", "0.00");
  }

  @Test
  void disabledUnconfirmedTccCannotRefund() {
    BalanceTccDebitTryDTO trying = request("disabled-trying", 7L, "10.00");
    service.tryDebit(trying);
    setEnabled(false);
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
    BalanceRefundRequestDTO refund = refundRequest(trying, "refund:disabled-trying", "10.00");

    assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
    assertFunds(7L, "90.00", "10.00");
    assertEquals(0, ledgerCount());
  }

  @Test
  void concurrentTryRequestsCannotFreezeMoreThanAvailable() throws Exception {
    List<Boolean> outcomes =
        race(
            () -> service.tryDebit(request("concurrent-a", 7L, "75.00")),
            () -> service.tryDebit(request("concurrent-b", 7L, "75.00")));
    assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
    assertFunds(7L, "25.00", "75.00");
    assertEquals(
        1, fixture.queryForObject("SELECT COUNT(*) FROM balance_tcc_debit", Integer.class));
  }

  @Test
  void concurrentLegacyAndTccHaveExactlyOneOwner() throws Exception {
    BalanceTccDebitTryDTO request = request("mixed-race", 7L, "10.00");
    List<Boolean> outcomes =
        race(
            () -> service.tryDebit(request),
            () -> debitLegacy(request.payNo, "legacy-race-key", 7L, "10.00"));
    assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
    assertEquals(
        "90.00",
        fixture
            .queryForObject("SELECT balance FROM mall_user WHERE id=7", BigDecimal.class)
            .toPlainString());
    assertEquals(
        1, fixture.queryForObject("SELECT COUNT(*) FROM balance_debit_attempt", Integer.class));
    assertEquals(
        1,
        ledgerCount()
            + fixture.queryForObject("SELECT COUNT(*) FROM balance_tcc_debit", Integer.class));
  }

  @Test
  void globalPayOwnershipSurvivesDifferentUserConcurrency() throws Exception {
    List<Boolean> outcomes =
        race(
            () -> service.tryDebit(request("cross-user", 7L, "10.00")),
            () -> debitLegacy("cross-user", "other-user-key", 8L, "10.00"));
    assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
    assertEquals(
        "190.00",
        fixture
            .queryForObject(
                "SELECT SUM(balance) FROM mall_user WHERE id IN (7,8)", BigDecimal.class)
            .toPlainString());
    assertEquals(
        1, fixture.queryForObject("SELECT COUNT(*) FROM balance_debit_attempt", Integer.class));
  }

  @Test
  void differentPaymentsCannotAliasTheReservedConfirmationLedgerKey() {
    service.tryDebit(request("reserved-ledger", 7L, "10.00"));
    assertThrows(
        BizException.class, () -> debitLegacy("other-payment", "reserved-ledger", 8L, "10.00"));
    debitLegacy("prior-payment", "prior-key", 8L, "10.00");
    assertThrows(BizException.class, () -> service.tryDebit(request("prior-key", 7L, "10.00")));
    assertFunds(7L, "90.00", "10.00");
    assertFunds(8L, "90.00", "0.00");
    assertEquals(1, ledgerCount());
  }

  @Test
  void concurrentLedgerAliasesHaveExactlyOneOwnerAcrossUsers() throws Exception {
    List<Boolean> outcomes =
        race(
            () -> service.tryDebit(request("future-ledger", 7L, "10.00")),
            () -> debitLegacy("different-pay-no", "future-ledger", 8L, "10.00"));
    assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
    assertEquals(
        "190.00",
        fixture
            .queryForObject(
                "SELECT SUM(balance) FROM mall_user WHERE id IN (7,8)", BigDecimal.class)
            .toPlainString());
    assertEquals(
        1, fixture.queryForObject("SELECT COUNT(*) FROM balance_debit_attempt", Integer.class));
    assertEquals(
        1,
        ledgerCount()
            + fixture.queryForObject("SELECT COUNT(*) FROM balance_tcc_debit", Integer.class));
  }

  @Test
  void legacyRejectionRetainsOriginalDurableDecision() {
    assertThrows(
        BizException.class, () -> debitLegacy("legacy-rejected", "rejected-key", 7L, "101.00"));
    assertEquals(
        "REJECTED", fixture.queryForObject("SELECT state FROM balance_ledger", String.class));
    assertEquals(
        "REJECTED",
        fixture.queryForObject("SELECT state FROM balance_debit_attempt", String.class));
    fixture.update("UPDATE mall_user SET balance=200 WHERE id=7");
    assertThrows(
        BizException.class, () -> debitLegacy("legacy-rejected", "rejected-key", 7L, "101.00"));
    assertThrows(
        BizException.class, () -> service.tryDebit(request("legacy-rejected", 7L, "101.00")));
    assertFunds(7L, "200.00", "0.00");
    assertEquals(1, ledgerCount());
  }

  @Test
  void legacyLedgerFailureRollsBackDebitAndOwnershipDespiteBusinessNoRollbackRule() {
    fixture.execute(
        "CREATE TRIGGER reject_test_ledger BEFORE INSERT ON balance_ledger FOR EACH ROW SIGNAL"
            + " SQLSTATE '45000' SET MESSAGE_TEXT='controlled ledger failure'");
    assertThrows(
        RuntimeException.class,
        () -> debitLegacy("legacy-ledger-failure", "legacy-failure-key", 7L, "10.00"));
    assertFunds(7L, "100.00", "0.00");
    assertEquals(0, ledgerCount());
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM balance_debit_attempt", Integer.class));
  }

  @Test
  void ledgerFailureRollsBackConfirmationAndPreservesFreeze() {
    BalanceTccDebitTryDTO request = request("ledger-failure", 7L, "17.00");
    service.tryDebit(request);
    fixture.execute(
        "CREATE TRIGGER reject_test_ledger BEFORE INSERT ON balance_ledger FOR EACH ROW SIGNAL"
            + " SQLSTATE '45000' SET MESSAGE_TEXT='controlled ledger failure'");
    assertThrows(RuntimeException.class, () -> service.confirmDebit(finalizeRequest(request)));
    assertFunds(7L, "83.00", "17.00");
    assertEquals(
        "TRYING", fixture.queryForObject("SELECT state FROM balance_tcc_debit", String.class));
    assertEquals(
        "TRYING", fixture.queryForObject("SELECT state FROM balance_debit_attempt", String.class));
    assertEquals(0, ledgerCount());
    fixture.execute("DROP TRIGGER reject_test_ledger");
    service.confirmDebit(finalizeRequest(request));
    assertFunds(7L, "83.00", "0.00");
    assertEquals(1, ledgerCount());
  }

  @Test
  void missingOwnershipCannotBeMisreportedAsAnEmptyCancellation() {
    BalanceTccDebitTryDTO request = request("orphan-freeze", 7L, "10.00");
    service.tryDebit(request);
    fixture.update("DELETE FROM balance_debit_attempt");

    assertThrows(BizException.class, () -> service.cancelDebit(finalizeRequest(request)));

    assertFunds(7L, "90.00", "10.00");
    assertEquals(
        "TRYING", fixture.queryForObject("SELECT state FROM balance_tcc_debit", String.class));
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM balance_debit_attempt", Integer.class));
  }

  @Test
  void emptyCancelRetainsXidOwnershipAcrossDifferentPayAndBranch() {
    BalanceTccDebitTryDTO cancelled = request("xid-tombstone", 7L, "10.00");
    service.cancelDebit(finalizeRequest(cancelled));
    BalanceTccDebitTryDTO changed = request("xid-reused", 7L, "10.00");
    changed.xid = cancelled.xid;
    changed.branchId = 2L;

    assertThrows(BizException.class, () -> service.tryDebit(changed));

    assertFunds(7L, "100.00", "0.00");
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM balance_tcc_debit", Integer.class));
  }

  @Test
  void concurrentRefundAndTryPreserveAvailableAndFrozenTotal() throws Exception {
    debitLegacy("refund-race", "old-custom-key", 7L, "20.00");
    BalanceRefundRequestDTO refund = new BalanceRefundRequestDTO();
    refund.refundOperationKey = "refund:refund-race";
    refund.originalOperationKey = "old-custom-key";
    refund.payNo = "refund-race";
    refund.orderNo = "order-refund-race";
    refund.amount = new BigDecimal("20.00");
    BalanceTccDebitTryDTO request = request("freeze-with-refund", 7L, "80.00");

    assertEquals(
        List.of(true, true),
        race(() -> service.tryDebit(request), () -> legacy.refundInternal(7L, 7L, refund)));

    assertFunds(7L, "20.00", "80.00");
    service.cancelDebit(finalizeRequest(request));
    assertFunds(7L, "100.00", "0.00");
    assertEquals(2, ledgerCount());
  }

  @Test
  void startupInitializesOldSchemaBeforeUserQueriesNewColumn() {
    context.close();
    context = null;
    fixture.execute("DROP TABLE balance_tcc_debit");
    fixture.execute("DROP TABLE balance_debit_attempt");
    fixture.execute("ALTER TABLE mall_user DROP COLUMN frozen_balance");

    startContext();

    assertFunds(7L, "100.00", "0.00");
    assertEquals("TRYING", service.tryDebit(request("migrated-on-start", 7L, "10.00")).state);
    assertFunds(7L, "90.00", "10.00");
  }

  @Test
  void migrationIsRepeatableAndMissingConfirmationCannotCreateDebit() throws Exception {
    BalanceTccDebitTryDTO request = request("migration", 7L, "10.00");
    assertThrows(BizException.class, () -> service.confirmDebit(finalizeRequest(request)));
    service.tryDebit(request);
    migrate();
    migrate();
    assertFunds(7L, "90.00", "10.00");
    assertEquals(
        "TRYING", fixture.queryForObject("SELECT state FROM balance_tcc_debit", String.class));
    assertEquals(0, ledgerCount());
  }

  /** 缺原金额在重放/清算时返回既有409，所有资金字段/状态/时间不变。 */
  @Test
  void nullPersistedAmountsNeverReachFundsWritesOrUnexpected500() {
    List<String> tables = List.of("balance_debit_attempt", "balance_tcc_debit", "balance_ledger");
    for (String table : tables)
      fixture.execute("ALTER TABLE " + table + " MODIFY amount DECIMAL(18,2) NULL");
    try {
      for (String table : tables) {
        List<String> operations =
            "balance_ledger".equals(table)
                ? List.of("try", "confirm")
                : List.of("try", "confirm", "cancel");
        for (String operation : operations) {
          BalanceTccDebitTryDTO original =
              request("missing-" + table + "-" + operation, 7L, "10.00");
          service.tryDebit(original);
          if ("balance_ledger".equals(table)) service.confirmDebit(finalizeRequest(original));
          fixture.update("UPDATE " + table + " SET amount=NULL WHERE pay_no=?", original.payNo);
          var before = allFundsFacts();
          BizException failure =
              assertThrows(
                  BizException.class,
                  () -> {
                    switch (operation) {
                      case "try" -> service.tryDebit(original);
                      case "confirm" -> service.confirmDebit(finalizeRequest(original));
                      case "cancel" -> service.cancelDebit(finalizeRequest(original));
                      default -> throw new AssertionError(operation);
                    }
                  });
          assertEquals(409, failure.getHttpStatus());
          assertEquals(
              "balance_debit_attempt".equals(table)
                  ? "IDEMPOTENCY_CONFLICT"
                  : "BALANCE_TCC_UNKNOWN",
              failure.getCode());
          assertEquals(before, allFundsFacts());
        }
      }
    } finally {
      for (String table : tables) fixture.update("DELETE FROM " + table);
      fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0");
      for (String table : tables)
        fixture.execute("ALTER TABLE " + table + " MODIFY amount DECIMAL(18,2) NOT NULL");
    }
  }

  /** 非法当前账户不允许实际冻结/清算/释放，真实四表事实在409后保持。 */
  @Test
  void malformedWalletAmountsCannotCommitFundsOrOwnership() {
    fixture.execute(
        "ALTER TABLE mall_user MODIFY balance DECIMAL(20,3) NULL,MODIFY frozen_balance"
            + " DECIMAL(20,3) NULL");
    try {
      for (String operation : List.of("try", "confirm", "cancel")) {
        for (String field : List.of("frozen_balance", "balance")) {
          for (String raw :
              java.util.Arrays.asList(null, "-1", "100.001", "10000000000000000.00")) {
            resetOwnedWalletScenario();
            BalanceTccDebitTryDTO original =
                request("wallet-" + operation + "-" + field, 7L, "10.00");
            if (!"try".equals(operation)) service.tryDebit(original);
            fixture.update("UPDATE mall_user SET " + field + "=? WHERE id=7", raw);
            var before = allFundsFacts();
            BizException failure =
                assertThrows(
                    BizException.class,
                    () -> {
                      switch (operation) {
                        case "try" -> service.tryDebit(original);
                        case "confirm" -> service.confirmDebit(finalizeRequest(original));
                        case "cancel" -> service.cancelDebit(finalizeRequest(original));
                        default -> throw new AssertionError(operation);
                      }
                    });
            assertEquals(409, failure.getHttpStatus());
            assertEquals("BALANCE_TCC_UNKNOWN", failure.getCode());
            assertEquals(before, allFundsFacts());
          }
        }
      }
      resetOwnedWalletScenario();
      fixture.update("UPDATE mall_user SET balance=10.000 WHERE id=7");
      BalanceTccDebitTryDTO valid = request("wallet-valid-zero", 7L, "10.00");
      assertEquals("TRYING", service.tryDebit(valid).state);
      assertFunds(7L, "0.000", "10.000");
      fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
      assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(valid)).state);
      assertFunds(7L, "0.000", "0.000");
    } finally {
      resetOwnedWalletScenario();
      fixture.execute(
          "ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NOT NULL DEFAULT 10000.00,MODIFY"
              + " frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00");
    }
  }

  /** 坏当前钱包不阻断既有无资金写终态replay，空取消仅保原metadata。 */
  @Test
  void malformedWalletStillAllowsExistingTerminalAndEmptyCancelNoOps() {
    fixture.execute(
        "ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NULL,MODIFY frozen_balance"
            + " DECIMAL(18,2) NULL");
    try {
      BalanceTccDebitTryDTO confirmed = request("wallet-confirmed-noop", 7L, "10.00");
      service.tryDebit(confirmed);
      service.confirmDebit(finalizeRequest(confirmed));
      fixture.update("UPDATE mall_user SET balance=NULL,frozen_balance=NULL WHERE id=7");
      var before = allFundsFacts();
      assertEquals("CONFIRMED", service.tryDebit(confirmed).state);
      assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(confirmed)).state);
      assertEquals(before, allFundsFacts());
      resetOwnedWalletScenario();
      BalanceTccDebitTryDTO cancelled = request("wallet-cancelled-noop", 7L, "10.00");
      service.tryDebit(cancelled);
      service.cancelDebit(finalizeRequest(cancelled));
      fixture.update("UPDATE mall_user SET balance=NULL,frozen_balance=NULL,status=0 WHERE id=7");
      setEnabled(false);
      before = allFundsFacts();
      assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(cancelled)).state);
      assertEquals(before, allFundsFacts());
      var accountsBefore = fixture.queryForList("SELECT * FROM mall_user ORDER BY id");
      BalanceTccDebitTryDTO empty = request("wallet-empty-noop", 7L, "10.00");
      assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(empty)).state);
      assertEquals(accountsBefore, fixture.queryForList("SELECT * FROM mall_user ORDER BY id"));
      assertEquals(0, ledgerCount());
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM balance_tcc_debit", Integer.class));
    } finally {
      setEnabled(true);
      resetOwnedWalletScenario();
      fixture.execute(
          "ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NOT NULL DEFAULT 10000.00,MODIFY"
              + " frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00");
    }
  }

  /** 只清理owned演练，保留用户与唯一的账户准备语义。 */
  private void resetOwnedWalletScenario() {
    for (String table : List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
      fixture.update("DELETE FROM " + table);
    fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0,status=1");
  }

  /** 错原冻结快照不允许被复制为确认账本或清算成功。 */
  @Test
  void invalidOriginalFrozenSnapshotCannotBeConfirmedOrReplayed() {
    fixture.execute(
        "ALTER TABLE balance_tcc_debit MODIFY balance_before DECIMAL(20,3) NULL,MODIFY"
            + " balance_after_try DECIMAL(20,3) NULL");
    try {
      for (String operation : List.of("confirm", "try", "cancel")) {
        for (String mutation :
            List.of(
                "balance_after_try=99",
                "balance_before=NULL",
                "balance_after_try=NULL",
                "balance_after_try=-1",
                "balance_before=100.001",
                "balance_before=10000000000000000.00")) {
          resetOwnedWalletScenario();
          BalanceTccDebitTryDTO original = request("bad-original-snapshot", 7L, "10.00");
          service.tryDebit(original);
          fixture.update("UPDATE balance_tcc_debit SET " + mutation);
          assertOriginalSnapshotConflict(operation, original);
        }
      }
      resetOwnedWalletScenario();
      BalanceTccDebitTryDTO valid = request("valid-snapshot-scale", 7L, "10.00");
      service.tryDebit(valid);
      fixture.update(
          "UPDATE balance_tcc_debit SET balance_before=100.000,balance_after_try=90.000");
      assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(valid)).state);
      var before = allFundsFacts();
      assertEquals("CONFIRMED", service.tryDebit(valid).state);
      assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(valid)).state);
      assertEquals(before, allFundsFacts());
    } finally {
      resetOwnedWalletScenario();
      fixture.execute(
          "ALTER TABLE balance_tcc_debit MODIFY balance_before DECIMAL(18,2) NOT NULL,MODIFY"
              + " balance_after_try DECIMAL(18,2) NOT NULL");
    }
  }

  /** 正确金额或CI键候选不足以证明原确认ledger的来源。 */
  @Test
  void confirmedLedgerSnapshotAndOperationKeyMustMatchOriginalFreeze() {
    fixture.execute(
        "ALTER TABLE balance_ledger MODIFY balance_before DECIMAL(20,3) NULL,MODIFY balance_after"
            + " DECIMAL(20,3) NULL");
    try {
      for (String operation : List.of("try", "confirm")) {
        for (String mutation :
            List.of(
                "operation_key=UPPER(operation_key)",
                "balance_before=200,balance_after=190",
                "balance_after=99",
                "balance_before=NULL",
                "balance_after=NULL",
                "balance_after=-1",
                "balance_before=100.001",
                "balance_before=10000000000000000.00")) {
          resetOwnedWalletScenario();
          BalanceTccDebitTryDTO original = request("original-ledger-snapshot", 7L, "10.00");
          service.tryDebit(original);
          service.confirmDebit(finalizeRequest(original));
          fixture.update("UPDATE balance_ledger SET " + mutation);
          if (mutation.startsWith("operation_key"))
            assertEquals(
                1,
                fixture.queryForObject(
                    "SELECT COUNT(*) FROM balance_ledger WHERE operation_key=?",
                    Integer.class,
                    original.ledgerKey));
          assertOriginalSnapshotConflict(operation, original);
        }
      }
    } finally {
      resetOwnedWalletScenario();
      fixture.execute(
          "ALTER TABLE balance_ledger MODIFY balance_before DECIMAL(18,2) NOT NULL,MODIFY"
              + " balance_after DECIMAL(18,2) NOT NULL");
    }
  }

  /** 只读核原坏事实，409后四表完整状态与当前钱包均不变。 */
  private void assertOriginalSnapshotConflict(String operation, BalanceTccDebitTryDTO original) {
    var before = allFundsFacts();
    BizException failure =
        assertThrows(
            BizException.class,
            () -> {
              switch (operation) {
                case "try" -> service.tryDebit(original);
                case "confirm" -> service.confirmDebit(finalizeRequest(original));
                case "cancel" -> service.cancelDebit(finalizeRequest(original));
                default -> throw new AssertionError(operation);
              }
            });
    assertEquals(409, failure.getHttpStatus());
    assertEquals("BALANCE_TCC_UNKNOWN", failure.getCode());
    assertEquals(before, allFundsFacts());
  }

  /** 单值合法但派生容量/可清算量非法，保持明确409且所有资金事实不变。 */
  @Test
  void derivedWalletBoundsAreCheckedBeforeSqlWrites() {
    assertTrue(fixture.queryForObject("SELECT @@sql_mode", String.class).contains("STRICT"));
    for (String scenario :
        List.of("tryOverflow", "confirmUnderflow", "cancelUnderflow", "cancelOverflow")) {
      resetOwnedWalletScenario();
      BalanceTccDebitTryDTO original = request("derived-" + scenario, 7L, "10.00");
      if (!"tryOverflow".equals(scenario)) service.tryDebit(original);
      switch (scenario) {
        case "tryOverflow" ->
            fixture.update("UPDATE mall_user SET frozen_balance=9999999999999999.99 WHERE id=7");
        case "confirmUnderflow", "cancelUnderflow" ->
            fixture.update("UPDATE mall_user SET frozen_balance=9.99 WHERE id=7");
        case "cancelOverflow" ->
            fixture.update("UPDATE mall_user SET balance=9999999999999999.99 WHERE id=7");
        default -> throw new AssertionError(scenario);
      }
      var before = allFundsFacts();
      BizException failure =
          assertThrows(
              BizException.class,
              () -> {
                switch (scenario) {
                  case "tryOverflow" -> service.tryDebit(original);
                  case "confirmUnderflow" -> service.confirmDebit(finalizeRequest(original));
                  case "cancelUnderflow", "cancelOverflow" ->
                      service.cancelDebit(finalizeRequest(original));
                  default -> throw new AssertionError(scenario);
                }
              });
      assertEquals(409, failure.getHttpStatus());
      assertEquals("BALANCE_TCC_UNKNOWN", failure.getCode());
      assertEquals(before, allFundsFacts());
    }
  }

  /** 等于MAX或原冻结量必须可写，NoOp不计算未发生的加法容量。 */
  @Test
  void exactDerivedBoundsAndNoOpsRemainValid() {
    resetOwnedWalletScenario();
    fixture.update("UPDATE mall_user SET frozen_balance=9999999999999989.99 WHERE id=7");
    BalanceTccDebitTryDTO maximumFreeze = request("derived-exact-freeze", 7L, "10.00");
    assertEquals("TRYING", service.tryDebit(maximumFreeze).state);
    assertFunds(7L, "90.00", "9999999999999999.99");
    fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
    assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(maximumFreeze)).state);
    assertFunds(7L, "90.00", "9999999999999989.99");
    fixture.update(
        "UPDATE mall_user SET"
            + " status=1,balance=9999999999999999.99,frozen_balance=9999999999999999.99 WHERE"
            + " id=7");
    var before = allFundsFacts();
    assertEquals("CONFIRMED", service.tryDebit(maximumFreeze).state);
    assertEquals("CONFIRMED", service.confirmDebit(finalizeRequest(maximumFreeze)).state);
    assertEquals(before, allFundsFacts());
    resetOwnedWalletScenario();
    BalanceTccDebitTryDTO maximumReturn = request("derived-exact-release", 7L, "10.00");
    service.tryDebit(maximumReturn);
    fixture.update("UPDATE mall_user SET balance=9999999999999989.99,status=0 WHERE id=7");
    assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(maximumReturn)).state);
    assertFunds(7L, "9999999999999999.99", "0.00");
    resetOwnedWalletScenario();
    BalanceTccDebitTryDTO insufficient = request("derived-insufficient-first", 7L, "10.00");
    fixture.update("UPDATE mall_user SET balance=5,frozen_balance=9999999999999999.99 WHERE id=7");
    before = allFundsFacts();
    assertEquals(
        "PAYMENT_FAILED",
        assertThrows(BizException.class, () -> service.tryDebit(insufficient)).getCode());
    assertEquals(before, allFundsFacts());
  }

  /** 取消及冻结重放均核两类原账本占用，CI查询命中不隐藏矛盾。 */
  @Test
  void unconfirmedReplaysRejectConflictingLedgerFacts() {
    for (String shape : List.of("EMPTY_CANCELLED", "RELEASED_CANCELLED", "TRYING")) {
      List<String> operations = "TRYING".equals(shape) ? List.of("try") : List.of("try", "cancel");
      for (String operation : operations) {
        for (String source :
            List.of(
                "payApplied",
                "payRejected",
                "payAlias",
                "payAccent",
                "payTrailing",
                "keyDebit",
                "keyRefund",
                "keyAlias",
                "keyAccent",
                "keyTrailing")) {
          resetOwnedWalletScenario();
          BalanceTccDebitTryDTO original = request("cancelled-ledger", 7L, "10.00");
          if (!"EMPTY_CANCELLED".equals(shape)) service.tryDebit(original);
          if (!"TRYING".equals(shape)) service.cancelDebit(finalizeRequest(original));
          boolean paySource = source.startsWith("pay");
          String ledgerKey = paySource ? "legacy:cancelled-ledger" : original.ledgerKey;
          String ledgerPayNo = paySource ? original.payNo : "unrelated-pay";
          if ("payAlias".equals(source))
            ledgerPayNo = ledgerPayNo.toUpperCase(java.util.Locale.ROOT);
          if ("keyAlias".equals(source)) ledgerKey = ledgerKey.toUpperCase(java.util.Locale.ROOT);
          if ("payAccent".equals(source)) ledgerPayNo = ledgerPayNo.replace("ledger", "lédger");
          if ("payTrailing".equals(source)) ledgerPayNo += " ";
          if ("keyAccent".equals(source)) ledgerKey = ledgerKey.replace("ledger", "lédger");
          if ("keyTrailing".equals(source)) ledgerKey += " ";
          String ledgerType = paySource || "keyDebit".equals(source) ? "DEBIT" : "REFUND";
          String ledgerState =
              "payRejected".equals(source) || "keyDebit".equals(source) ? "REJECTED" : "APPLIED";
          fixture.update(
              "INSERT INTO balance_ledger"
                  + "(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,"
                  + "balance_before,balance_after,created_at)"
                  + " VALUES(999999,8,?,?,?,'other-order',10,?,100,90,NOW(3))",
              ledgerKey,
              ledgerType,
              ledgerPayNo,
              ledgerState);
          assertEquals(
              1,
              paySource
                  ? fixture.queryForObject(
                      "SELECT COUNT(*) FROM balance_ledger WHERE pay_no=? AND"
                          + " operation_type='DEBIT'",
                      Integer.class,
                      original.payNo)
                  : fixture.queryForObject(
                      "SELECT COUNT(*) FROM balance_ledger WHERE operation_key=?",
                      Integer.class,
                      original.ledgerKey));
          var before = allFundsFacts();
          BizException failure =
              assertThrows(
                  BizException.class,
                  () -> {
                    if ("try".equals(operation)) service.tryDebit(original);
                    else service.cancelDebit(finalizeRequest(original));
                  });
          assertEquals(409, failure.getHttpStatus());
          assertEquals("IDEMPOTENCY_CONFLICT", failure.getCode());
          assertEquals(before, allFundsFacts());
        }
      }
    }
  }

  /** 无目标账本仍按取消摘要重放，不新增钱包门或误阻无关记录。 */
  @Test
  void legitimateCancelledReplaysPreserveNoOpAndDisabledSemantics() {
    fixture.execute(
        "ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NULL,MODIFY frozen_balance"
            + " DECIMAL(18,2) NULL");
    try {
      for (boolean emptyCancellation : List.of(true, false)) {
        resetOwnedWalletScenario();
        BalanceTccDebitTryDTO original = request("cancelled-noop", 7L, "10.00");
        if (!emptyCancellation) service.tryDebit(original);
        service.cancelDebit(finalizeRequest(original));
        fixture.update(
            "INSERT INTO balance_ledger"
                + "(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,"
                + "balance_before,balance_after,created_at)"
                + " VALUES(999999,8,'unrelated-key','DEBIT','unrelated-pay','unrelated-order',"
                + "10,'APPLIED',100,90,NOW(3))");
        fixture.update("UPDATE mall_user SET balance=NULL,frozen_balance=NULL,status=0 WHERE id=7");
        setEnabled(false);
        var before = allFundsFacts();
        assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(original)).state);
        assertEquals(before, allFundsFacts());
        assertEquals(
            "PAY_DEBIT_MODE_UNAVAILABLE",
            assertThrows(BizException.class, () -> service.tryDebit(original)).getCode());
        setEnabled(true);
        assertEquals(
            "IDEMPOTENCY_CONFLICT",
            assertThrows(BizException.class, () -> service.tryDebit(original)).getCode());
        fixture.update("UPDATE mall_user SET status=1 WHERE id=7");
        before = allFundsFacts();
        assertEquals("CANCELLED", service.tryDebit(original).state);
        assertEquals("CANCELLED", service.cancelDebit(finalizeRequest(original)).state);
        assertEquals(before, allFundsFacts());
      }
    } finally {
      resetOwnedWalletScenario();
      setEnabled(true);
      fixture.execute(
          "ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NOT NULL DEFAULT 10000.00,"
              + "MODIFY frozen_balance DECIMAL(18,2) NOT NULL DEFAULT 0.00");
    }
  }

  /** 仅owned模拟遗留nullable state，三入口缺证/旧未知态不变成500或合法状态。 */
  @Test
  void missingOrUnknownPersistedStatesAreBusinessConflicts() {
    for (String table : List.of("balance_debit_attempt", "balance_tcc_debit"))
      fixture.execute("ALTER TABLE " + table + " MODIFY state VARCHAR(16) NULL");
    try {
      for (String state : new String[] {null, "trying", "TRYÍNG", "TRYING ", "FUTURE"}) {
        for (String operation : List.of("try", "confirm", "cancel")) {
          resetOwnedWalletScenario();
          BalanceTccDebitTryDTO original = request("missing-state", 7L, "10.00");
          service.tryDebit(original);
          fixture.update("UPDATE balance_debit_attempt SET state=?", state);
          fixture.update("UPDATE balance_tcc_debit SET state=?", state);
          assertOriginalSnapshotConflict(operation, original);
        }
      }
      resetOwnedWalletScenario();
      BalanceTccDebitTryDTO original = request("trying-noop", 7L, "10.00");
      service.tryDebit(original);
      fixture.update("UPDATE mall_user SET balance=-1,frozen_balance=-1 WHERE id=7");
      var before = allFundsFacts();
      assertEquals("TRYING", service.tryDebit(original).state);
      assertEquals(before, allFundsFacts());
      setEnabled(false);
      assertEquals(
          "PAY_DEBIT_MODE_UNAVAILABLE",
          assertThrows(BizException.class, () -> service.tryDebit(original)).getCode());
      setEnabled(true);
      fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
      before = allFundsFacts();
      assertEquals(
          "IDEMPOTENCY_CONFLICT",
          assertThrows(BizException.class, () -> service.tryDebit(original)).getCode());
      assertEquals(before, allFundsFacts());
    } finally {
      resetOwnedWalletScenario();
      setEnabled(true);
      for (String table : List.of("balance_debit_attempt", "balance_tcc_debit"))
        fixture.execute("ALTER TABLE " + table + " MODIFY state VARCHAR(16) NOT NULL");
    }
  }

  /** 真实Legacy/TCC/REFUND主行坏字段不能经GET输出成功；宽金额列仅限owned并恢复。 */
  @Test
  void operationHttpRejectsMalformedOwnedLedgerRows() throws Exception {
    fixture.execute(
        "ALTER TABLE balance_ledger MODIFY amount DECIMAL(20,3) NULL, MODIFY balance_before"
            + " DECIMAL(20,3) NULL, MODIFY balance_after DECIMAL(20,3) NULL");
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    try {
      for (String mode : List.of("legacy-debit", "tcc-debit", "refund")) {
        for (String mutation :
            List.of(
                "operation_key=UPPER(operation_key)",
                "state='UNKNOWN'",
                "amount=NULL",
                "balance_before=NULL",
                "balance_after=NULL",
                "balance_after=95")) {
          assertions.add(
              () -> {
                resetOperationFixture(mode);
                String key = "refund".equals(mode) ? "query-refund" : "query-debit";
                fixture.update(
                    "UPDATE balance_ledger SET " + mutation + " WHERE operation_key=?", key);
                var before = allFundsFacts();
                assertOperationHttpConflict(key);
                assertEquals(before, allFundsFacts());
              });
        }
      }
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    } finally {
      fixture.update("DELETE FROM balance_ledger");
      fixture.execute(
          "ALTER TABLE balance_ledger MODIFY amount DECIMAL(18,2) NOT NULL, MODIFY balance_before"
              + " DECIMAL(18,2) NOT NULL, MODIFY balance_after DECIMAL(18,2) NOT NULL");
    }
  }

  /** 缺/错原source、被旧selector过滤的坏集合、负数抵消均不得成为查询证据。 */
  @Test
  void operationHttpRejectsBrokenSourceAndLinkedRefundFacts() throws Exception {
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    for (String query : List.of("query-debit", "query-refund")) {
      for (String mutation :
          List.of(
              "negative", "wrong-type", "wrong-state", "alias-origin", "other-user", "other-pay")) {
        assertions.add(
            () -> {
              resetOperationFixture("refund");
              String change =
                  switch (mutation) {
                    case "negative" -> "amount=-2";
                    case "wrong-type" -> "operation_type='DEBIT'";
                    case "wrong-state" -> "state='UNKNOWN'";
                    case "alias-origin" -> "original_operation_key=UPPER(original_operation_key)";
                    case "other-user" -> "user_id=8";
                    case "other-pay" -> "pay_no='other-pay'";
                    default -> throw new AssertionError(mutation);
                  };
              fixture.update(
                  "UPDATE balance_ledger SET " + change + " WHERE operation_key='query-refund'");
              var before = allFundsFacts();
              // foreign主行保持404；DEBIT读取到foreign关联则是坏集合409。
              if ("query-refund".equals(query) && "other-user".equals(mutation)) {
                assertEquals(404, operationHttp(query).getResponse().getStatus());
              } else {
                assertOperationHttpConflict(query);
              }
              assertEquals(before, allFundsFacts());
            });
      }
    }
    for (String mutation : List.of("missing", "source-origin", "source-order", "source-user")) {
      assertions.add(
          () -> {
            resetOperationFixture("refund");
            if ("missing".equals(mutation))
              fixture.update("DELETE FROM balance_ledger WHERE operation_key='query-debit'");
            else {
              String change =
                  switch (mutation) {
                    case "source-origin" -> "original_operation_key='other'";
                    case "source-order" -> "order_no='other-order'";
                    case "source-user" -> "user_id=8";
                    default -> throw new AssertionError(mutation);
                  };
              fixture.update(
                  "UPDATE balance_ledger SET " + change + " WHERE operation_key='query-debit'");
            }
            var before = allFundsFacts();
            assertOperationHttpConflict("query-refund");
            assertEquals(before, allFundsFacts());
          });
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 100条raw候选保持查询，101哨兵和合法行的超额合计均拒绝，两类主行同样校验。 */
  @Test
  void operationQueryCandidateLimitAndTotalUseRealMysqlFacts() throws Exception {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (int count : List.of(100, 101)) {
      assertions.add(
          () -> {
            closeContext();
            createTransactionalServices();
            var original = request("query-limit", 7L, "2.00");
            debitLegacy(original.payNo, original.payNo, 7L, "2.00");
            for (int index = 0; index < count; index++) {
              fixture.update(
                  "INSERT INTO"
                      + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                      + " VALUES(?,7,?,'REFUND',?,?,?,0.01,'APPLIED',98,98.01,NOW(3))",
                  index + 2L,
                  "query-limit-refund-" + index,
                  original.payNo,
                  original.payNo,
                  original.orderNo);
            }
            var before = allFundsFacts();
            for (String key : List.of(original.payNo, "query-limit-refund-0")) {
              if (count == 100) assertEquals(200, operationHttp(key).getResponse().getStatus());
              else assertOperationHttpConflict(key);
            }
            assertEquals(before, allFundsFacts());
          });
    }
    assertions.add(
        () -> {
          resetOperationFixture("refund");
          // 实际已退2，再插入一条自身合法但合计超过原扣款10的owned记录。
          fixture.update(
              "INSERT INTO"
                  + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
                  + " VALUES(2,7,'query-extra','REFUND','query-debit','query-debit','order-query-debit',9,'APPLIED',0,9,NOW(3))");
          var before = allFundsFacts();
          assertOperationHttpConflict("query-debit");
          assertOperationHttpConflict("query-refund");
          assertEquals(before, allFundsFacts());
        });
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 同连接只读RR观察旧快照，外部owned写入不使后续集合读混用新事实；正常历史仍可查询。 */
  @Test
  void operationQueryUsesOneReadOnlySnapshotAndKeepsHealthyHistory() throws Exception {
    resetOperationFixture("refund");
    Object target = AopTestUtils.getUltimateTargetObject(legacy);
    var actualMapper =
        (com.cloudmall.user.mapper.BalanceLedgerMapper)
            ReflectionTestUtils.getField(target, "balanceLedgerMapper");
    var observations = new ArrayList<String>();
    var connectionIds = new java.util.HashSet<Integer>();
    var observedAmounts = new ArrayList<String>();
    var writerRan = new java.util.concurrent.atomic.AtomicBoolean();
    var before = allFundsFacts();
    var instrumented =
        (com.cloudmall.user.mapper.BalanceLedgerMapper)
            java.lang.reflect.Proxy.newProxyInstance(
                actualMapper.getClass().getClassLoader(),
                new Class<?>[] {com.cloudmall.user.mapper.BalanceLedgerMapper.class},
                (proxy, method, arguments) -> {
                  String name = method.getName();
                  if (List.of(
                          "countOperationCandidates",
                          "selectByOperationKey",
                          "findLinkedRefundCandidates",
                          "selectFirstRefundOperationKey")
                      .contains(name)) {
                    observations.add(
                        name
                            + ":"
                            + org.springframework.transaction.support
                                .TransactionSynchronizationManager.isActualTransactionActive()
                            + ":"
                            + org.springframework.transaction.support
                                .TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                            + ":"
                            + org.springframework.transaction.support
                                .TransactionSynchronizationManager
                                .getCurrentTransactionIsolationLevel());
                    java.sql.Connection observedConnection =
                        org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                            testDataSource);
                    try {
                      connectionIds.add(System.identityHashCode(observedConnection));
                    } finally {
                      org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                          observedConnection, testDataSource);
                    }
                  }
                  Object result;
                  try {
                    result = method.invoke(actualMapper, arguments);
                  } catch (java.lang.reflect.InvocationTargetException failure) {
                    throw failure.getCause();
                  }
                  if ("selectByOperationKey".equals(name) && writerRan.compareAndSet(false, true)) {
                    // 直接新物理连接，避免JdbcTemplate加入当前只读事务；只写owned测试库。
                    try (java.sql.Connection writer = testDataSource.getConnection();
                        java.sql.PreparedStatement statement =
                            writer.prepareStatement(
                                "UPDATE balance_ledger SET amount=3 WHERE"
                                    + " operation_key='query-refund'")) {
                      assertEquals(1, statement.executeUpdate());
                      try (java.sql.PreparedStatement ownerWriter =
                          writer.prepareStatement(
                              "UPDATE balance_debit_attempt SET state='TRYING' WHERE"
                                  + " pay_no='query-debit'")) {
                        assertEquals(1, ownerWriter.executeUpdate());
                      }
                    }
                  }
                  if ("findLinkedRefundCandidates".equals(name)) {
                    for (Object candidate : (List<?>) result)
                      observedAmounts.add(
                          ((com.cloudmall.user.domain.po.BalanceLedgerPO) candidate)
                              .amount.toPlainString());
                  }
                  return result;
                });
    ReflectionTestUtils.setField(target, "balanceLedgerMapper", instrumented);
    var ownerMappers = new java.util.LinkedHashMap<String, Object>();
    for (String fieldName :
        List.of("balanceDebitAttemptMapper", "balanceTccDebitMapper", "userTccReceiveMapper")) {
      Object actualOwnerMapper = ReflectionTestUtils.getField(target, fieldName);
      ownerMappers.put(fieldName, actualOwnerMapper);
      Class<?> mapperType =
          "balanceDebitAttemptMapper".equals(fieldName)
              ? com.cloudmall.user.mapper.BalanceDebitAttemptMapper.class
              : "balanceTccDebitMapper".equals(fieldName)
                  ? com.cloudmall.user.mapper.BalanceTccDebitMapper.class
                  : com.cloudmall.user.mapper.UserTccReceiveMapper.class;
      Object observedOwnerMapper =
          java.lang.reflect.Proxy.newProxyInstance(
              actualOwnerMapper.getClass().getClassLoader(),
              new Class<?>[] {mapperType},
              (proxy, mapperMethod, arguments) -> {
                if (List.of("selectByPayNo", "findByPayNo").contains(mapperMethod.getName())) {
                  observations.add(
                      fieldName
                          + ":"
                          + org.springframework.transaction.support
                              .TransactionSynchronizationManager.isActualTransactionActive()
                          + ":"
                          + org.springframework.transaction.support
                              .TransactionSynchronizationManager.isCurrentTransactionReadOnly()
                          + ":"
                          + org.springframework.transaction.support
                              .TransactionSynchronizationManager
                              .getCurrentTransactionIsolationLevel());
                  java.sql.Connection observedConnection =
                      org.springframework.jdbc.datasource.DataSourceUtils.getConnection(
                          testDataSource);
                  try {
                    connectionIds.add(System.identityHashCode(observedConnection));
                  } finally {
                    org.springframework.jdbc.datasource.DataSourceUtils.releaseConnection(
                        observedConnection, testDataSource);
                  }
                }
                try {
                  return mapperMethod.invoke(actualOwnerMapper, arguments);
                } catch (java.lang.reflect.InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
      ReflectionTestUtils.setField(target, fieldName, observedOwnerMapper);
    }
    try {
      assertEquals(200, operationHttp("query-debit").getResponse().getStatus());
      assertEquals(
          List.of(
              "countOperationCandidates:true:true:4",
              "selectByOperationKey:true:true:4",
              "findLinkedRefundCandidates:true:true:4",
              "selectFirstRefundOperationKey:true:true:4",
              "balanceDebitAttemptMapper:true:true:4",
              "balanceTccDebitMapper:true:true:4",
              "userTccReceiveMapper:true:true:4",
              "countOperationCandidates:true:true:4"),
          observations);
      assertEquals(1, connectionIds.size());
      assertEquals(List.of("2.00"), observedAmounts);
      assertEquals(
          new BigDecimal("3.00"),
          fixture.queryForObject(
              "SELECT amount FROM balance_ledger WHERE operation_key='query-refund'",
              BigDecimal.class));
      var expected =
          before.stream()
              .map(rows -> rows.stream().map(row -> new java.util.LinkedHashMap<>(row)).toList())
              .toList();
      for (var row : expected.get(3))
        if ("query-refund".equals(row.get("operation_key")))
          row.put("amount", new BigDecimal("3.00"));
      for (var row : expected.get(1))
        if ("query-debit".equals(row.get("pay_no"))) row.put("state", "TRYING");
      assertEquals(expected, allFundsFacts());
    } finally {
      ReflectionTestUtils.setField(target, "balanceLedgerMapper", actualMapper);
      ownerMappers.forEach(
          (fieldName, mapper) -> ReflectionTestUtils.setField(target, fieldName, mapper));
    }
    for (String scenario : List.of("rejected", "no-attempt", "wallet-null")) {
      closeContext();
      createTransactionalServices();
      if ("rejected".equals(scenario))
        assertThrows(
            BizException.class, () -> debitLegacy("query-history", "query-history", 7L, "101.00"));
      else debitLegacy("query-history", "query-history", 7L, "10.00");
      if ("no-attempt".equals(scenario))
        fixture.update("DELETE FROM balance_debit_attempt WHERE pay_no='query-history'");
      if ("wallet-null".equals(scenario)) {
        fixture.execute("ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NULL");
        fixture.update("UPDATE mall_user SET balance=NULL WHERE id=7");
      }
      try {
        var historyBefore = allFundsFacts();
        var response = operationHttp("query-history");
        assertEquals(200, response.getResponse().getStatus());
        assertEquals(
            "rejected".equals(scenario) ? "REJECTED" : "APPLIED",
            new com.fasterxml.jackson.databind.ObjectMapper()
                .readTree(response.getResponse().getContentAsString())
                .path("data")
                .path("state")
                .asText());
        assertEquals(historyBefore, allFundsFacts());
      } finally {
        if ("wallet-null".equals(scenario)) {
          fixture.update("DELETE FROM mall_user");
          fixture.execute("ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NOT NULL");
        }
      }
    }
  }

  /** 新场景重置owned上下文，真实资金动作完成后才人为损坏查询事实。 */
  private void resetOperationFixture(String mode) {
    closeContext();
    createTransactionalServices();
    var original = request("query-debit", 7L, "10.00");
    if ("tcc-debit".equals(mode)) {
      service.tryDebit(original);
      service.confirmDebit(finalizeRequest(original));
    } else debitLegacy(original.payNo, original.payNo, 7L, "10.00");
    legacy.refundInternal(7L, 7L, refundRequest(original, "query-refund", "2.00"));
  }

  /** 使用原GET协议调用真实Controller，不启动HTTP监听或绕开Spring事务代理。 */
  private org.springframework.test.web.servlet.MvcResult operationHttp(String operationKey)
      throws Exception {
    return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            context.getBean(UserController.class))
        .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
        .build()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                    "/api/internal/users/7/balance/operations/{operationKey}", operationKey)
                .header("X-User-Id", "7"))
        .andReturn();
  }

  /** 坏证据必须是固定409与code/message，查询不产生补写或修账。 */
  private void assertOperationHttpConflict(String operationKey) throws Exception {
    var response = operationHttp(operationKey);
    assertEquals(409, response.getResponse().getStatus());
    var body =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response.getResponse().getContentAsByteArray());
    assertEquals("COMMON_CONFLICT", body.path("code").asText());
    assertEquals("余额账本事实待核对", body.path("message").asText());
  }

  /** 原User查询真实返回单笔退款金额和marker，部分退款不得被消费者推断为全额。 */
  @Test
  void partialAndFullRefundOperationViewsKeepTheirActualAmounts() throws Exception {
    for (String amount : List.of("2.00", "10.00")) {
      closeContext();
      createTransactionalServices();
      var original = request("operation-refund-proof", 7L, "10.00");
      debitLegacy(original.payNo, original.payNo, 7L, "10.00");
      var refund = refundRequest(original, "actual-refund-proof", amount);
      legacy.refundInternal(7L, 7L, refund);
      var before = allFundsFacts();
      var mvc =
          org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                  context.getBean(UserController.class))
              .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
              .build();
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                      "/api/internal/users/7/balance/operations/{operationKey}", original.payNo)
                  .header("X-User-Id", "7"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.type")
                  .value("DEBIT"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.amount")
                  .value("10.00"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.refundOperationKey")
                  .value(refund.refundOperationKey));
      mvc.perform(
              org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get(
                      "/api/internal/users/7/balance/operations/{operationKey}",
                      refund.refundOperationKey)
                  .header("X-User-Id", "7"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.status().isOk())
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.operationKey")
                  .value(refund.refundOperationKey))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.type")
                  .value("REFUND"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.state")
                  .value("APPLIED"))
          .andExpect(
              org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                      "$.data.amount")
                  .value(amount));
      assertEquals(before, allFundsFacts());
      assertFunds(7L, new BigDecimal("90.00").add(new BigDecimal(amount)).toPlainString(), "0.00");
    }
  }

  /** Legacy fresh debit在余额来源非法时于认领前固定拒绝，真实Controller与Service均不写资金。 */
  @Test
  void legacyFreshDebitRejectsInvalidWalletBeforeAnyOwnership() throws Exception {
    fixture.execute("ALTER TABLE mall_user MODIFY balance DECIMAL(20,3) NULL");
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    try {
      for (String raw : List.of("NULL", "-1", "10.001", "10000000000000000.00"))
        assertions.add(
            () -> {
              closeContext();
              createTransactionalServices();
              fixture.update(
                  "UPDATE mall_user SET balance=? WHERE id=7", "NULL".equals(raw) ? null : raw);
              var request = new BalanceDebitRequestDTO();
              request.operationKey = "wallet-invalid";
              request.payNo = "wallet-invalid";
              request.orderNo = "order-wallet-invalid";
              request.amount = new BigDecimal("10.00");
              var before = allFundsFacts();
              var failure =
                  assertThrows(
                      BizException.class,
                      () -> debitLegacy("wallet-invalid", "wallet-invalid", 7L, "10.00"));
              assertEquals(409, failure.getHttpStatus());
              assertEquals("BALANCE_DEBIT_UNKNOWN", failure.getCode());
              assertEquals(before, allFundsFacts());
              var response = postLegacyDebit(request);
              assertEquals(409, response.getResponse().getStatus());
              assertHttpCode(response, "BALANCE_DEBIT_UNKNOWN");
              assertEquals(before, allFundsFacts());
            });
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    } finally {
      fixture.update("DELETE FROM balance_debit_attempt");
      fixture.update("DELETE FROM balance_ledger");
      fixture.update("DELETE FROM mall_user");
      fixture.execute("ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NOT NULL");
    }
  }

  /** Legacy metadata与snapshot坏值按冻结错误码区分，Controller与Service均不写。 */
  @Test
  void legacyExistingTupleAndAttemptMoneyRejectBeforeReplay() throws Exception {
    fixture.execute("ALTER TABLE balance_debit_attempt MODIFY amount DECIMAL(20,3) NULL");
    fixture.execute(
        "ALTER TABLE balance_ledger MODIFY amount DECIMAL(20,3) NULL, MODIFY balance_before"
            + " DECIMAL(20,3) NULL, MODIFY balance_after DECIMAL(20,3) NULL");
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    try {
      for (String mutation :
          List.of(
              "ledger-origin",
              "ledger-amount",
              "ledger-before",
              "ledger-after",
              "ledger-delta",
              "attempt-amount"))
        assertions.add(
            () -> {
              closeContext();
              createTransactionalServices();
              debitLegacy("legacy-tuple", "legacy-tuple-key", 7L, "10.00");
              if ("ledger-origin".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET original_operation_key='other' WHERE"
                        + " operation_key=?",
                    "legacy-tuple-key");
              if ("ledger-amount".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET amount=NULL WHERE operation_key=?",
                    "legacy-tuple-key");
              if ("ledger-before".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET balance_before=NULL WHERE operation_key=?",
                    "legacy-tuple-key");
              if ("ledger-after".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET balance_after=NULL WHERE operation_key=?",
                    "legacy-tuple-key");
              if ("ledger-delta".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET balance_after=95 WHERE operation_key=?",
                    "legacy-tuple-key");
              if ("attempt-amount".equals(mutation))
                fixture.update(
                    "UPDATE balance_debit_attempt SET amount=NULL WHERE operation_key=?",
                    "legacy-tuple-key");
              String expectedCode =
                  List.of("ledger-before", "ledger-after", "ledger-delta").contains(mutation)
                      ? "BALANCE_DEBIT_UNKNOWN"
                      : "IDEMPOTENCY_CONFLICT";
              var before = allFundsFacts();
              var failure =
                  assertThrows(
                      BizException.class,
                      () -> debitLegacy("legacy-tuple", "legacy-tuple-key", 7L, "10.00"));
              assertEquals(409, failure.getHttpStatus());
              assertEquals(expectedCode, failure.getCode());
              assertEquals(before, allFundsFacts());
              var request = new BalanceDebitRequestDTO();
              request.operationKey = "legacy-tuple-key";
              request.payNo = "legacy-tuple";
              request.orderNo = "order-legacy-tuple";
              request.amount = new BigDecimal("10.00");
              var response = postLegacyDebit(request);
              assertEquals(409, response.getResponse().getStatus());
              assertHttpCode(response, expectedCode);
              assertEquals(before, allFundsFacts());
            });
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    } finally {
      fixture.update("DELETE FROM balance_debit_attempt");
      fixture.update("DELETE FROM balance_ledger");
      fixture.execute("ALTER TABLE balance_debit_attempt MODIFY amount DECIMAL(18,2) NOT NULL");
      fixture.execute(
          "ALTER TABLE balance_ledger MODIFY amount DECIMAL(18,2) NOT NULL, MODIFY balance_before"
              + " DECIMAL(18,2) NOT NULL, MODIFY balance_after DECIMAL(18,2) NOT NULL");
    }
  }

  /** REJECTED历史快照必须完整；合法PAYMENT_FAILED与APPLIED replay继续保持原语义。 */
  @Test
  void legacyRejectedAndAppliedSnapshotsPreserveHistoricalReplayRules() throws Exception {
    fixture.execute(
        "ALTER TABLE balance_ledger MODIFY balance_before DECIMAL(20,3) NULL, MODIFY balance_after"
            + " DECIMAL(20,3) NULL");
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    try {
      for (String mutation :
          List.of("before", "after", "null-before", "null-after", "delta", "enough"))
        assertions.add(
            () -> {
              closeContext();
              createTransactionalServices();
              assertThrows(
                  BizException.class,
                  () -> debitLegacy("rejected-proof", "rejected-proof-key", 7L, "101.00"));
              if ("before".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET balance_before=99 WHERE operation_key=?",
                    "rejected-proof-key");
              if ("after".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET balance_after=99 WHERE operation_key=?",
                    "rejected-proof-key");
              if ("null-before".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET balance_before=NULL WHERE operation_key=?",
                    "rejected-proof-key");
              if ("null-after".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET balance_after=NULL WHERE operation_key=?",
                    "rejected-proof-key");
              if ("delta".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET balance_after=99 WHERE operation_key=?",
                    "rejected-proof-key");
              if ("enough".equals(mutation))
                fixture.update(
                    "UPDATE balance_ledger SET balance_before=200,balance_after=200 WHERE"
                        + " operation_key=?",
                    "rejected-proof-key");
              fixture.update("UPDATE mall_user SET balance=200 WHERE id=7");
              var before = allFundsFacts();
              var failure =
                  assertThrows(
                      BizException.class,
                      () -> debitLegacy("rejected-proof", "rejected-proof-key", 7L, "101.00"));
              assertEquals("BALANCE_DEBIT_UNKNOWN", failure.getCode());
              BalanceDebitRequestDTO request = new BalanceDebitRequestDTO();
              request.operationKey = "rejected-proof-key";
              request.payNo = "rejected-proof";
              request.orderNo = "order-rejected-proof";
              request.amount = new BigDecimal("101.00");
              var response = postLegacyDebit(request);
              assertEquals(409, response.getResponse().getStatus());
              assertHttpCode(response, "BALANCE_DEBIT_UNKNOWN");
              assertEquals(before, allFundsFacts());
            });
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    } finally {
      fixture.update("DELETE FROM balance_debit_attempt");
      fixture.update("DELETE FROM balance_ledger");
      fixture.execute(
          "ALTER TABLE balance_ledger MODIFY balance_before DECIMAL(18,2) NOT NULL, MODIFY"
              + " balance_after DECIMAL(18,2) NOT NULL");
    }
  }

  /** 实际拒款与扣款历史重放保持决策；owned钱包NULL不影响合法已存快照。 */
  @Test
  void legacyHealthyRejectedAndAppliedReplayUseHistoricalDecisions() throws Exception {
    closeContext();
    createTransactionalServices();
    assertThrows(
        BizException.class,
        () -> debitLegacy("rejected-replay", "rejected-replay-key", 7L, "101.00"));
    fixture.update("UPDATE mall_user SET balance=200 WHERE id=7");
    var rejectedBefore = allFundsFacts();
    var rejectedFailure =
        assertThrows(
            BizException.class,
            () -> debitLegacy("rejected-replay", "rejected-replay-key", 7L, "101.00"));
    assertEquals("PAYMENT_FAILED", rejectedFailure.getCode());
    var rejectedRequest = new BalanceDebitRequestDTO();
    rejectedRequest.operationKey = "rejected-replay-key";
    rejectedRequest.payNo = "rejected-replay";
    rejectedRequest.orderNo = "order-rejected-replay";
    rejectedRequest.amount = new BigDecimal("101.00");
    var rejectedResponse = postLegacyDebit(rejectedRequest);
    assertEquals(409, rejectedResponse.getResponse().getStatus());
    assertHttpCode(rejectedResponse, "PAYMENT_FAILED");
    assertEquals(rejectedBefore, allFundsFacts());

    closeContext();
    createTransactionalServices();
    BalanceTccDebitTryDTO applied = request("applied-replay", 7L, "10.00");
    debitLegacy(applied.payNo, applied.ledgerKey, 7L, "10.00");
    fixture.execute("ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NULL");
    try {
      fixture.update("UPDATE mall_user SET balance=NULL WHERE id=7");
      var before = allFundsFacts();
      debitLegacy(applied.payNo, applied.ledgerKey, 7L, "10.00");
      assertEquals(before, allFundsFacts());
      var appliedRequest = new BalanceDebitRequestDTO();
      appliedRequest.operationKey = applied.ledgerKey;
      appliedRequest.payNo = applied.payNo;
      appliedRequest.orderNo = applied.orderNo;
      appliedRequest.amount = applied.amount;
      var appliedResponse = postLegacyDebit(appliedRequest);
      assertEquals(200, appliedResponse.getResponse().getStatus());
      assertEquals(
          "APPLIED",
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(appliedResponse.getResponse().getContentAsString())
              .path("data")
              .path("state")
              .asText());
      assertEquals(
          "90.00",
          new com.fasterxml.jackson.databind.ObjectMapper()
              .readTree(appliedResponse.getResponse().getContentAsString())
              .path("data")
              .path("balance")
              .asText());
      assertEquals(before, allFundsFacts());
    } finally {
      fixture.update("DELETE FROM balance_debit_attempt");
      fixture.update("DELETE FROM balance_ledger");
      fixture.update("DELETE FROM mall_user");
      fixture.execute("ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NOT NULL");
    }
    // 兼容入口实际进入相同事务和资金门，独立验证其支付键转为三元组的传递。
    fixture.execute("ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NULL");
    fixture.execute("ALTER TABLE balance_debit_attempt MODIFY amount DECIMAL(18,2) NULL");
    List<org.junit.jupiter.api.function.Executable> compatibleAssertions = new ArrayList<>();
    try {
      for (String scenario :
          List.of(
              "wallet-null",
              "attempt-null",
              "snapshot-delta",
              "applied-replay",
              "rejected-replay")) {
        compatibleAssertions.add(
            () -> {
              closeContext();
              createTransactionalServices();
              var compatibleRequest = new com.cloudmall.user.domain.dto.DebitRequestDTO();
              compatibleRequest.paymentKey = "compatible-proof";
              compatibleRequest.amount = new BigDecimal("10.00");
              String expectedCode = "BALANCE_DEBIT_UNKNOWN";
              if ("wallet-null".equals(scenario)) {
                fixture.update("UPDATE mall_user SET balance=NULL WHERE id=7");
              } else if ("rejected-replay".equals(scenario)) {
                compatibleRequest.amount = new BigDecimal("101.00");
                assertEquals(
                    "PAYMENT_FAILED",
                    assertThrows(BizException.class, () -> legacy.debit(7L, 7L, compatibleRequest))
                        .getCode());
                expectedCode = "PAYMENT_FAILED";
              } else {
                legacy.debit(7L, 7L, compatibleRequest);
                if ("attempt-null".equals(scenario)) {
                  fixture.update(
                      "UPDATE balance_debit_attempt SET amount=NULL WHERE pay_no=?",
                      compatibleRequest.paymentKey);
                  expectedCode = "IDEMPOTENCY_CONFLICT";
                } else if ("snapshot-delta".equals(scenario)) {
                  fixture.update(
                      "UPDATE balance_ledger SET balance_after=95 WHERE operation_key=?",
                      compatibleRequest.paymentKey);
                }
              }
              var beforeCompatibleReplay = allFundsFacts();
              if ("applied-replay".equals(scenario)) {
                legacy.debit(7L, 7L, compatibleRequest);
                var response = postCompatibleLegacyDebit(compatibleRequest);
                assertEquals(200, response.getResponse().getStatus());
                var body =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .readTree(response.getResponse().getContentAsString());
                assertEquals("APPLIED", body.path("data").path("state").asText());
                assertEquals("90.00", body.path("data").path("balance").asText());
              } else {
                assertEquals(
                    expectedCode,
                    assertThrows(BizException.class, () -> legacy.debit(7L, 7L, compatibleRequest))
                        .getCode());
                var response = postCompatibleLegacyDebit(compatibleRequest);
                assertEquals(409, response.getResponse().getStatus());
                assertHttpCode(response, expectedCode);
              }
              assertEquals(beforeCompatibleReplay, allFundsFacts());
            });
      }
      org.junit.jupiter.api.Assertions.assertAll(compatibleAssertions);
    } finally {
      fixture.update("DELETE FROM balance_debit_attempt");
      fixture.update("DELETE FROM balance_ledger");
      fixture.update("DELETE FROM mall_user");
      fixture.execute("ALTER TABLE mall_user MODIFY balance DECIMAL(18,2) NOT NULL");
      fixture.execute("ALTER TABLE balance_debit_attempt MODIFY amount DECIMAL(18,2) NOT NULL");
    }
  }

  /** 兼容接口使用原paymentKey载荷，经真实Controller进入本地事务。 */
  private org.springframework.test.web.servlet.MvcResult postCompatibleLegacyDebit(
      com.cloudmall.user.domain.dto.DebitRequestDTO request) throws Exception {
    String payload =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of("paymentKey", request.paymentKey, "amount", request.amount.toPlainString()));
    return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            context.getBean(UserController.class))
        .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
        .build()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/internal/users/7/balance/debit")
                .header("X-User-Id", "7")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(payload))
        .andReturn();
  }

  /** 真实Controller legacy debit入口，返回原始HTTP结果供错误码断言。 */
  private org.springframework.test.web.servlet.MvcResult postLegacyDebit(
      BalanceDebitRequestDTO request) throws Exception {
    String payload =
        new com.fasterxml.jackson.databind.ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "operationKey", request.operationKey,
                    "payNo", request.payNo,
                    "orderNo", request.orderNo,
                    "amount", request.amount.toPlainString()));
    return org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
            context.getBean(UserController.class))
        .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
        .build()
        .perform(
            org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                    "/api/internal/users/7/balance/debits")
                .header("X-User-Id", "7")
                .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                .content(payload))
        .andReturn();
  }

  /** 读取真实Controller统一响应码，避免只以HTTP状态代替业务错误码证据。 */
  private static void assertHttpCode(
      org.springframework.test.web.servlet.MvcResult response, String expectedCode)
      throws Exception {
    assertEquals(
        expectedCode,
        new com.fasterxml.jackson.databind.ObjectMapper()
            .readTree(response.getResponse().getContentAsString())
            .path("code")
            .asText());
  }

  @Test
  void refundCandidateRowsRejectMalformedTuplesBeforeAnyFundsMutation() {
    fixture.execute("ALTER TABLE balance_ledger MODIFY amount DECIMAL(20,3) NULL");
    List<org.junit.jupiter.api.function.Executable> assertions = new ArrayList<>();
    try {
      for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc"))
        for (String mutation :
            List.of(
                "negative",
                "zero",
                "wrong-type",
                "wrong-state",
                "alias-origin",
                "wrong-user",
                "wrong-pay",
                "wrong-order",
                "null-money",
                "bad-delta")) {
          assertions.add(
              () -> {
                closeContext();
                createTransactionalServices();
                BalanceTccDebitTryDTO original = request("refund-candidates", 7L, "10.00");
                String debitKey =
                    "active-legacy".equals(mode) ? "legacy-refund-candidates" : original.ledgerKey;
                if ("active-legacy".equals(mode)) {
                  debitLegacy(original.payNo, debitKey, 7L, "10.00");
                } else {
                  service.tryDebit(original);
                  service.confirmDebit(finalizeRequest(original));
                }
                BalanceRefundRequestDTO firstRefund =
                    refundRequest(original, "refund-candidate-a", "4.00");
                firstRefund.originalOperationKey = debitKey;
                BalanceRefundRequestDTO secondRefund =
                    refundRequest(original, "refund-candidate-b", "4.00");
                secondRefund.originalOperationKey = debitKey;
                legacy.refundInternal(7L, 7L, firstRefund);
                legacy.refundInternal(7L, 7L, secondRefund);
                if ("disabled-tcc".equals(mode)) {
                  setEnabled(false);
                  fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
                }
                switch (mutation) {
                  case "negative" ->
                      fixture.update(
                          "UPDATE balance_ledger SET amount=-4 WHERE"
                              + " operation_key='refund-candidate-b'");
                  case "zero" ->
                      fixture.update(
                          "UPDATE balance_ledger SET amount=0 WHERE"
                              + " operation_key='refund-candidate-b'");
                  case "wrong-type" ->
                      fixture.update(
                          "UPDATE balance_ledger SET operation_type='DEBIT' WHERE"
                              + " operation_key='refund-candidate-b'");
                  case "wrong-state" ->
                      fixture.update(
                          "UPDATE balance_ledger SET state='PENDING' WHERE"
                              + " operation_key='refund-candidate-b'");
                  case "alias-origin" ->
                      fixture.update(
                          "UPDATE balance_ledger SET original_operation_key=UPPER(?) WHERE"
                              + " operation_key='refund-candidate-b'",
                          debitKey);
                  case "wrong-user" ->
                      fixture.update(
                          "UPDATE balance_ledger SET user_id=8 WHERE"
                              + " operation_key='refund-candidate-b'");
                  case "wrong-pay" ->
                      fixture.update(
                          "UPDATE balance_ledger SET pay_no='other-pay' WHERE"
                              + " operation_key='refund-candidate-b'");
                  case "wrong-order" ->
                      fixture.update(
                          "UPDATE balance_ledger SET order_no='other-order' WHERE"
                              + " operation_key='refund-candidate-b'");
                  case "null-money" ->
                      fixture.update(
                          "UPDATE balance_ledger SET amount=NULL WHERE"
                              + " operation_key='refund-candidate-b'");
                  case "bad-delta" ->
                      fixture.update(
                          "UPDATE balance_ledger SET balance_after=99 WHERE"
                              + " operation_key='refund-candidate-b'");
                  default -> throw new AssertionError(mutation);
                }
                var before = allFundsFacts();
                BalanceRefundRequestDTO refund =
                    refundRequest(
                        original, "refund-new-key", "negative".equals(mutation) ? "5.00" : "1.00");
                refund.originalOperationKey = debitKey;
                var mvc =
                    org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                            context.getBean(UserController.class))
                        .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                        .build();
                String payload =
                    new com.fasterxml.jackson.databind.ObjectMapper()
                        .writeValueAsString(
                            Map.of(
                                "refundOperationKey", refund.refundOperationKey,
                                "originalOperationKey", refund.originalOperationKey,
                                "payNo", refund.payNo,
                                "orderNo", refund.orderNo,
                                "amount", refund.amount.toPlainString()));
                mvc.perform(
                        org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                "/api/internal/users/7/balance/refunds")
                            .header("X-User-Id", "7")
                            .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                            .content(payload))
                    .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.status()
                            .isConflict())
                    .andExpect(
                        org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath(
                                "$.code")
                            .value("BALANCE_REFUND_INVALID"));
                assertEquals(before, allFundsFacts());
                BizException directFailure =
                    assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                assertEquals(409, directFailure.getHttpStatus());
                assertEquals("BALANCE_REFUND_INVALID", directFailure.getCode());
                assertEquals(before, allFundsFacts());
              });
        }
      org.junit.jupiter.api.Assertions.assertAll(assertions);
    } finally {
      fixture.update("DELETE FROM balance_ledger");
      fixture.execute(
          "ALTER TABLE balance_ledger MODIFY amount DECIMAL(18,2) NOT NULL, MODIFY balance_before"
              + " DECIMAL(18,2) NOT NULL, MODIFY balance_after DECIMAL(18,2) NOT NULL");
    }
  }

  /** 99条可受理第100次并恢复，已有100条不得写第101次，三模式资金边界一致。 */
  @Test
  void freshRefundPreservesRecoverableRecordCapacity() throws Exception {
    var assertions = new ArrayList<org.junit.jupiter.api.function.Executable>();
    for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc")) {
      for (int count : List.of(99, 100, 101)) {
        assertions.add(
            () -> {
              for (String table :
                  List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
                fixture.update("DELETE FROM " + table);
              fixture.update(
                  "UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
              setEnabled(true);
              var original = request("refund-capacity-" + mode + "-" + count, 7L, "10.00");
              if ("active-legacy".equals(mode))
                debitLegacy(original.payNo, original.ledgerKey, 7L, "10.00");
              else {
                service.tryDebit(original);
                service.confirmDebit(finalizeRequest(original));
              }
              for (int index = 0; index < count; index++)
                seedLinkedRefundCandidate(
                    -1L - index, original.ledgerKey, original, "capacity-prior-" + index, "0.01");
              if ("disabled-tcc".equals(mode)) {
                setEnabled(false);
                fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
              }
              var refund = refundRequest(original, "capacity-next-refund", "0.01");
              var before = allFundsFacts();
              var response =
                  org.springframework.test.web.servlet.setup.MockMvcBuilders.standaloneSetup(
                          context.getBean(UserController.class))
                      .setControllerAdvice(new com.cloudmall.common.web.GlobalExceptionHandler())
                      .build()
                      .perform(
                          org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post(
                                  "/api/internal/users/7/balance/refunds")
                              .header("X-User-Id", "7")
                              .contentType(org.springframework.http.MediaType.APPLICATION_JSON)
                              .content(
                                  new com.fasterxml.jackson.databind.ObjectMapper()
                                      .writeValueAsString(refund)))
                      .andReturn();
              if (count == 99) {
                assertEquals(200, response.getResponse().getStatus());
                assertEquals(101, ledgerCount());
                assertFunds(7L, "90.01", "0.00");
                var accepted = allFundsFacts();
                assertEquals(
                    "90.01",
                    ((com.cloudmall.user.domain.vo.BalanceRefundVO)
                            legacy.refundInternal(7L, 7L, refund).data)
                        .balance);
                assertEquals(
                    200, operationHttp(refund.refundOperationKey).getResponse().getStatus());
                assertEquals(accepted, allFundsFacts());
              } else {
                org.junit.jupiter.api.Assertions.assertAll(
                    () -> assertEquals(409, response.getResponse().getStatus(), mode + ":" + count),
                    () -> assertHttpCode(response, "BALANCE_REFUND_INVALID"),
                    () -> {
                      var failure =
                          assertThrows(
                              BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
                      assertEquals(
                          count == 100 ? "退款记录数量已达上限" : "退款累计证据不完整或不一致", failure.getMessage());
                    },
                    () -> assertEquals(before, allFundsFacts()));
              }
            });
      }
    }
    org.junit.jupiter.api.Assertions.assertAll(assertions);
  }

  /** 同一用户行锁序列化两个第100条候选，只有一个可写，胜者仍可重放和查询。 */
  @Test
  void concurrentFreshRefundCannotCrossRecordCapacity() throws Exception {
    for (String mode : List.of("active-legacy", "active-tcc", "disabled-tcc")) {
      for (String table : List.of("balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
        fixture.update("DELETE FROM " + table);
      fixture.update("UPDATE mall_user SET balance=100,frozen_balance=0,status=1 WHERE id=7");
      setEnabled(true);
      var original = request("refund-capacity-race-" + mode, 7L, "10.00");
      if ("active-legacy".equals(mode))
        debitLegacy(original.payNo, original.ledgerKey, 7L, "10.00");
      else {
        service.tryDebit(original);
        service.confirmDebit(finalizeRequest(original));
      }
      for (int index = 0; index < 99; index++)
        seedLinkedRefundCandidate(
            -1L - index, original.ledgerKey, original, "race-prior-" + index, "0.01");
      if ("disabled-tcc".equals(mode)) {
        setEnabled(false);
        fixture.update("UPDATE mall_user SET status=0 WHERE id=7");
      }
      var first = refundRequest(original, "capacity-race-first", "0.01");
      var second = refundRequest(original, "capacity-race-second", "0.01");
      var outcomes = race(() -> refundAtCapacity(first), () -> refundAtCapacity(second));
      assertEquals(1, outcomes.stream().filter(Boolean::booleanValue).count());
      assertEquals(101, ledgerCount());
      assertFunds(7L, "90.01", "0.00");
      var accepted = outcomes.get(0) ? first : second;
      var beforeReplay = allFundsFacts();
      assertEquals(
          "90.01",
          ((com.cloudmall.user.domain.vo.BalanceRefundVO)
                  legacy.refundInternal(7L, 7L, accepted).data)
              .balance);
      assertEquals(200, operationHttp(accepted.refundOperationKey).getResponse().getStatus());
      assertEquals(beforeReplay, allFundsFacts());
    }
  }

  /** 并发失败必须是容量409，SQL异常或其它业务错误不能被race归为正常竞争。 */
  private Object refundAtCapacity(BalanceRefundRequestDTO request) {
    try {
      return legacy.refundInternal(7L, 7L, request);
    } catch (BizException failure) {
      assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
      assertEquals("退款记录数量已达上限", failure.getMessage());
      throw failure;
    }
  }

  /** 真实99条可新增到100，已有100容量拒绝，101仍按历史缺证拒绝。 */
  @Test
  void refundCandidateLimitStopsAtOneHundredRows() {
    for (int candidateCount : List.of(99, 100, 101)) {
      closeContext();
      createTransactionalServices();
      BalanceTccDebitTryDTO original = request("refund-limit", 7L, "2.00");
      service.tryDebit(original);
      service.confirmDebit(finalizeRequest(original));
      for (int index = 0; index < candidateCount; index++)
        seedLinkedRefundCandidate(
            2L + index, original.ledgerKey, original, "refund-limit-" + index, "0.01");
      var before = allFundsFacts();
      BalanceRefundRequestDTO refund =
          refundRequest(original, "refund-limit-request", candidateCount == 99 ? "1.00" : "0.01");
      if (candidateCount == 99) {
        int beforeLedgerCount = ledgerCount();
        legacy.refundInternal(7L, 7L, refund);
        assertEquals(beforeLedgerCount + 1, ledgerCount());
        assertEquals(
            "99.00",
            fixture.queryForObject(
                "SELECT CAST(balance AS CHAR) FROM mall_user WHERE id=7", String.class));
      } else {
        BizException failure =
            assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, refund));
        assertEquals(409, failure.getHttpStatus());
        assertEquals("BALANCE_REFUND_INVALID", failure.getCode());
        assertEquals(before, allFundsFacts());
      }
    }
  }

  /** 两条已核退款与本次request恰好达到原扣款，重放幂等，超额request固定拒绝。 */
  @Test
  void refundAggregateExactReplayAndExcessKeepFundsBounded() {
    closeContext();
    createTransactionalServices();
    BalanceTccDebitTryDTO original = request("refund-aggregate", 7L, "10.00");
    service.tryDebit(original);
    service.confirmDebit(finalizeRequest(original));
    BalanceRefundRequestDTO firstRefund = refundRequest(original, "refund-aggregate-a", "4.00");
    legacy.refundInternal(7L, 7L, firstRefund);
    BalanceRefundRequestDTO secondRefund = refundRequest(original, "refund-aggregate-b", "5.00");
    legacy.refundInternal(7L, 7L, secondRefund);
    BalanceRefundRequestDTO refund = refundRequest(original, "refund-aggregate-new", "1.00");
    legacy.refundInternal(7L, 7L, refund);
    var afterFirst = allFundsFacts();
    legacy.refundInternal(7L, 7L, refund);
    assertEquals(afterFirst, allFundsFacts());
    BalanceRefundRequestDTO excess = refundRequest(original, "refund-aggregate-excess", "1.00");
    BizException failure =
        assertThrows(BizException.class, () -> legacy.refundInternal(7L, 7L, excess));
    assertEquals(409, failure.getHttpStatus());
    assertEquals("BALANCE_REFUND_EXCEEDED", failure.getCode());
    assertEquals(afterFirst, allFundsFacts());
  }

  /** owned测试库逐行写入退款候选；不写源库，不调用RM/TC。 */
  private void seedLinkedRefundCandidate(
      long identifier,
      String originalOperationKey,
      BalanceTccDebitTryDTO original,
      String operationKey,
      String amount) {
    BigDecimal refundAmount = new BigDecimal(amount);
    fixture.update(
        "INSERT INTO"
            + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
            + " VALUES(?,7,?,'REFUND',?,?,?,?,'APPLIED',90,90+?,'2026-10-12')",
        identifier,
        operationKey,
        originalOperationKey,
        original.payNo,
        original.orderNo,
        refundAmount,
        refundAmount);
  }

  /** 只从owned库核完整资金四表，包含所有身份、金额、状态和时间。 */
  private List<List<Map<String, Object>>> allFundsFacts() {
    return List.of("mall_user", "balance_debit_attempt", "balance_tcc_debit", "balance_ledger")
        .stream()
        .map(table -> fixture.queryForList("SELECT * FROM " + table + " ORDER BY id"))
        .toList();
  }

  /** 原所有表仅校验checksum，不执行任何原资金写。 */
  private static List<Map<String, Object>> originalFingerprint() {
    List<Map<String, Object>> values =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_user"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_user", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法原表");
      values.addAll(source.queryForList("CHECKSUM TABLE cloudmall_user." + table));
    }
    return values;
  }

  /** 按已批准脚本迁移独立库；同一物理连接保留 PREPARE 和会话变量。 */
  private static void migrate() throws Exception {
    String script = Files.readString(Path.of("../database/migrate_user_tcc_state.sql"));
    script =
        script
            .replaceAll("(?im)^\\s*--.*$", "")
            .replaceAll("(?i)USE\\s+cloudmall_user\\s*;", "USE " + testDatabase + ";");
    try (java.sql.Connection migration = testDataSource.getConnection();
        java.sql.Statement statement = migration.createStatement()) {
      for (String fragment : script.split(";")) {
        if (!fragment.isBlank()) statement.execute(fragment);
      }
    }
  }

  /** 只切换新 Try 开关，验证清算方法不会被错误阻断。 */
  private void setEnabled(boolean enabled) {
    Object target = AopTestUtils.getUltimateTargetObject(service);
    ReflectionTestUtils.setField(target, "enabled", enabled);
  }

  /** 由同一固定操作键构造受信任本地分支载荷。 */
  private BalanceTccDebitTryDTO request(String payNo, Long userId, String amount) {
    BalanceTccDebitTryDTO request = new BalanceTccDebitTryDTO();
    request.userId = userId;
    request.payNo = payNo;
    request.orderNo = "order-" + payNo;
    request.operationKey = "tcc-debit:" + payNo;
    request.ledgerKey = payNo;
    request.xid = "test-xid-" + payNo;
    request.branchId = 1L;
    request.amount = new BigDecimal(amount);
    return request;
  }

  /** 回调完整载荷必须与原始冻结一致。 */
  private BalanceTccDebitFinalizeDTO finalizeRequest(BalanceTccDebitTryDTO request) {
    BalanceTccDebitFinalizeDTO completion = new BalanceTccDebitFinalizeDTO();
    completion.userId = request.userId;
    completion.payNo = request.payNo;
    completion.orderNo = request.orderNo;
    completion.operationKey = request.operationKey;
    completion.ledgerKey = request.ledgerKey;
    completion.xid = request.xid;
    completion.branchId = request.branchId;
    completion.amount = request.amount;
    return completion;
  }

  /** 构造与原 TCC 扣款完全匹配的退款请求。 */
  private BalanceRefundRequestDTO refundRequest(
      BalanceTccDebitTryDTO request, String refundOperationKey, String amount) {
    BalanceRefundRequestDTO refund = new BalanceRefundRequestDTO();
    refund.refundOperationKey = refundOperationKey;
    refund.originalOperationKey = request.payNo;
    refund.payNo = request.payNo;
    refund.orderNo = request.orderNo;
    refund.amount = new BigDecimal(amount);
    return refund;
  }

  /** 直接调用生产 Legacy 事务方法，保持非 payNo 操作键覆盖。 */
  private Object debitLegacy(String payNo, String operationKey, Long userId, String amount) {
    BalanceDebitRequestDTO request = new BalanceDebitRequestDTO();
    request.payNo = payNo;
    request.operationKey = operationKey;
    request.orderNo = "order-" + payNo;
    request.amount = new BigDecimal(amount);
    return legacy.debitInternal(userId, userId, request);
  }

  /** 同时核对可用额与冻结额，避免只看状态掩盖资金遗漏。 */
  private void assertFunds(Long userId, String balance, String frozen) {
    Map<String, Object> money =
        fixture.queryForMap("SELECT balance,frozen_balance FROM mall_user WHERE id=?", userId);
    assertEquals(new BigDecimal(balance), money.get("balance"));
    assertEquals(new BigDecimal(frozen), money.get("frozen_balance"));
  }

  /** 返回测试库的实际资金账本数量。 */
  private int ledgerCount() {
    return fixture.queryForObject("SELECT COUNT(*) FROM balance_ledger", Integer.class);
  }

  /** 两连接同门起跑；超时、SQL 错误和死锁必须失败，只有明确业务冲突是正常拒绝。 */
  private List<Boolean> race(Callable<?> first, Callable<?> second) throws Exception {
    ExecutorService executor = Executors.newFixedThreadPool(2);
    CountDownLatch gate = new CountDownLatch(1);
    try {
      Callable<Boolean> firstTask = () -> runContender(gate, first);
      Callable<Boolean> secondTask = () -> runContender(gate, second);
      Future<Boolean> firstResult = executor.submit(firstTask);
      Future<Boolean> secondResult = executor.submit(secondTask);
      gate.countDown();
      return List.of(firstResult.get(15, TimeUnit.SECONDS), secondResult.get(15, TimeUnit.SECONDS));
    } finally {
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
    }
  }

  /** 业务冲突由服务明确返回，不能把数据库异常当作验收通过。 */
  private boolean runContender(CountDownLatch gate, Callable<?> action) throws Exception {
    gate.await();
    try {
      action.call();
      return true;
    } catch (BizException conflict) {
      assertEquals(409, conflict.getHttpStatus());
      return false;
    }
  }

  /** 使用真实 MyBatis 和事务管理器，仅 Redis 使用 mock，测试不依赖缓存。 */
  @Configuration
  @Profile("cm14-local-it")
  @EnableTransactionManagement(proxyTargetClass = true)
  @MapperScan("com.cloudmall.user.mapper")
  static class Beans {
    /** 共享owned池由AfterAll关闭，子context不得提前销毁。 */
    @Bean(destroyMethod = "")
    DataSource dataSource() {
      return testDataSource;
    }

    /** 启用真实数据库提交与回滚。 */
    @Bean
    PlatformTransactionManager transactionManager(DataSource dataSource) {
      return new DataSourceTransactionManager(dataSource);
    }

    /** 加载生产 Mapper，包括 MyBatis-Plus BaseMapper。 */
    @Bean
    MybatisSqlSessionFactoryBean sqlSessionFactory(DataSource dataSource) {
      MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
      factory.setDataSource(dataSource);
      MybatisConfiguration configuration = new MybatisConfiguration();
      configuration.setMapUnderscoreToCamelCase(true);
      factory.setConfiguration(configuration);
      return factory;
    }

    /** Redis 不参与本轮资金事实，缓存写失败沿用现有可容错语义。 */
    @Bean
    StringRedisTemplate redis() {
      return mock(StringRedisTemplate.class);
    }
  }
}
