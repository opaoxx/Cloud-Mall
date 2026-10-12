package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

  /** 多连接访问独立测试库，允许真实行锁与并发验证。 */
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
    testDataSource =
        new DriverManagerDataSource(
            sourceUrl.replace("/cloudmall_user", "/" + testDatabase), username, password);
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
    /** 返回隔离测试数据库。 */
    @Bean
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
