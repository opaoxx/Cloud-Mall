package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.controller.PaySuccessController;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PaySuccessService;
import com.cloudmall.pay.service.impl.PaySuccessServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import com.cloudmall.pay.service.impl.entry.PayTccPaymentService;
import com.cloudmall.pay.service.impl.legacy.PayConsistencyService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 隔离MySQL上的原模式/意图门及成功重读；资金编排为替身，不联系TC或远端服务。 */
class PaySuccessMySqlIT {
  @Test
  void persistedNullMalformedAndConflictingModesRejectBeforeTerminalShortcutOrBusinessCall()
      throws Exception {
    try (var fixture = new Fixture()) {
      AuthContext.set(7L, "USER");
      for (String mode : Arrays.asList(null, "", "legacy", "LEGACY ", "UNKNOWN")) {
        fixture.seed(mode, null, false, "SUCCESS");
        fixture.assertRejectedWithoutChangingFacts();
      }
      for (String intentMode : Arrays.asList(null, "", "legacy", "LEGACY ", "TCC")) {
        fixture.seed("LEGACY", intentMode, true, "SUCCESS");
        fixture.assertRejectedWithoutChangingFacts();
      }
      for (String intentMode : List.of("LEGACY", "TCC")) {
        fixture.seed("TCC", intentMode, true, "SUCCESS");
        fixture.assertRejectedWithoutChangingFacts();
      }
      assertEquals(12, fixture.observations.size());
      fixture.successful = true;
    }
  }

  @Test
  void eachAdmittedProtocolUsesOneOriginalBranchAndSuccessReReadsPersistedProjection()
      throws Exception {
    try (var fixture = new Fixture()) {
      AuthContext.set(7L, "USER");
      for (String mode : List.of("LEGACY", "TCC")) {
        fixture.seed(mode, "LEGACY", mode.equals("LEGACY"), "PENDING");
        org.mockito.stubbing.Answer<String> projectedSuccess =
            invocation -> {
              assertTrue(Thread.holdsLock(fixture.monitor));
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              fixture.jdbc.update(
                  "UPDATE pay_record SET status='SUCCESS',paid_at='2026-11-13 12:30:45.123' WHERE pay_no='pay-1'");
              return "SUCCESS";
            };
        when(fixture.legacy.initiate(any())).thenAnswer(projectedSuccess);
        when(fixture.tcc.pay(any())).thenAnswer(projectedSuccess);
        var response = fixture.controller.success("pay-1");
        assertEquals("SUCCESS", response.data.status);
        assertEquals(new BigDecimal("10.00"), response.data.amount);
        assertEquals("2026-11-13T12:30:45.123+08:00", response.data.paidAt);
        assertEquals(3, fixture.paymentReads.get());
        fixture.verifyBranch(mode);
        fixture.observations.add(
            Map.of(
                "mode",
                mode,
                "paymentReads",
                fixture.paymentReads.get(),
                "facts",
                fixture.facts()));
      }
      fixture.successful = true;
    }
  }

  @Test
  void unknownOrThrownResultKeepsOriginalRowsWithoutRetriesOrSyntheticSuccess() throws Exception {
    try (var fixture = new Fixture()) {
      AuthContext.set(7L, "USER");
      for (String mode : List.of("LEGACY", "TCC")) {
        for (String outcome : Arrays.asList(null, "UNKNOWN", "THROWN")) {
          fixture.seed(mode, null, false, "PENDING");
          var before = fixture.facts();
          var original = new IllegalStateException("controlled uncertain orchestration");
          if ("THROWN".equals(outcome)) {
            when(fixture.legacy.initiate(any())).thenThrow(original);
            when(fixture.tcc.pay(any())).thenThrow(original);
            assertSame(
                original,
                assertThrows(
                    IllegalStateException.class, () -> fixture.controller.success("pay-1")));
          } else {
            when(fixture.legacy.initiate(any())).thenReturn(outcome);
            when(fixture.tcc.pay(any())).thenReturn(outcome);
            var failure =
                assertThrows(BizException.class, () -> fixture.controller.success("pay-1"));
            assertEquals(202, failure.getHttpStatus());
            assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
          }
          assertEquals(before, fixture.facts());
          assertEquals(2, fixture.paymentReads.get());
          fixture.verifyBranch(mode);
          fixture.observations.add(
              Map.of("mode", mode, "outcome", String.valueOf(outcome), "unchanged", true));
        }
      }
      fixture.successful = true;
    }
  }

  /** 每例使用独立UUID库；原SQL真实执行，成功才清理。 */
  static class Fixture implements AutoCloseable {
    /** 当前唯一库名。 */
    private final String schema =
        "pay_success_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 管理当前库生命周期的连接。 */
    private final JdbcTemplate administrator;

    /** 当前库的实际事实读取及受控准备。 */
    private final JdbcTemplate jdbc;

    /** 原资金编排替身。 */
    private final PayConsistencyService legacy = mock(PayConsistencyService.class);

    /** 原TCC业务替身。 */
    private final PayTccPaymentService tcc = mock(PayTccPaymentService.class);

    /** 原指标调用观察。 */
    private final PayProtocolMetrics metrics = mock(PayProtocolMetrics.class);

    /** 实际共享锁。 */
    private final PayWriteMonitor monitor = new PayWriteMonitor();

    /** 原支付查询次数。 */
    private final AtomicInteger paymentReads = new AtomicInteger();

    /** 实际新成功Bean上下文，不导入服务自动配置。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 实际HTTP入口。 */
    private final PaySuccessController controller;

    /** 本例已验证观察结果。 */
    private final List<Map<String, Object>> observations = new ArrayList<>();

    /** 只有全部断言通过后才能清理。 */
    private boolean successful;

    Fixture() throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_pay"))
        throw new IllegalStateException("需要独立实验cloudmall_pay URL");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      System.out.println("PAY_SUCCESS_SCHEMA=" + schema);
      var source =
          new DriverManagerDataSource(
              configured(url.replace("/cloudmall_pay", "/" + schema)), "root", "root");
      jdbc = new JdbcTemplate(source);
      try {
        jdbc.execute("CREATE TABLE pay_record LIKE cloudmall_pay.pay_record");
        jdbc.execute("CREATE TABLE pay_debit_intent LIKE cloudmall_pay.pay_debit_intent");
        // 仅owned夹具放开两列以构造持久SQL null，生产DDL与源表保持NOT NULL。
        jdbc.execute("ALTER TABLE pay_record MODIFY debit_mode VARCHAR(16) NULL");
        jdbc.execute("ALTER TABLE pay_debit_intent MODIFY debit_mode VARCHAR(16) NULL");
        var actual = PayDebitModeM2MySqlIT.recordMapper(source);
        var observed =
            (PayRecordMapper)
                Proxy.newProxyInstance(
                    PayRecordMapper.class.getClassLoader(),
                    new Class<?>[] {PayRecordMapper.class},
                    (proxy, method, arguments) -> {
                      if (method.getDeclaringClass() == Object.class)
                        return method.invoke(actual, arguments);
                      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                      assertTrue(Thread.holdsLock(monitor));
                      assertEquals(
                          "selectByPayNo",
                          method.getName(),
                          "success entry must not add a write or lock query");
                      paymentReads.incrementAndGet();
                      try {
                        return method.invoke(actual, arguments);
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
        context.register(PaySuccessController.class, PaySuccessServiceImpl.class);
        var beans = context.getBeanFactory();
        beans.registerSingleton("records", observed);
        beans.registerSingleton("intents", new PayDebitIntentMapper(jdbc));
        beans.registerSingleton("legacy", legacy);
        beans.registerSingleton("tcc", tcc);
        beans.registerSingleton("metrics", metrics);
        beans.registerSingleton("payWriteMonitor", monitor);
        context.refresh();
        controller = context.getBean(PaySuccessController.class);
        assertFalse(AopUtils.isAopProxy(context.getBean(PaySuccessService.class)));
      } catch (Exception | Error failure) {
        context.close();
        System.out.println("PAY_SUCCESS_PRESERVE_SETUP_FAILURE=" + schema);
        throw failure;
      }
    }

    private void seed(String mode, String intentMode, boolean withIntent, String status) {
      jdbc.update("DELETE FROM pay_debit_intent");
      jdbc.update("DELETE FROM pay_record");
      jdbc.update(
          "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at) VALUES(1,'pay-1','order-1',7,10.00,?,?,'2026-11-13 12:00:00.123','2026-11-13 12:00:00.123')",
          status,
          mode);
      if (withIntent)
        jdbc.update(
            "INSERT INTO pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,next_retry_at,created_at,updated_at) VALUES(2,'pay-1','order-1',7,10.00,'INIT',?,'2026-11-13 12:00:00.123','2026-11-13 12:00:00.123','2026-11-13 12:00:00.123')",
            intentMode);
      paymentReads.set(0);
      reset(legacy, tcc, metrics);
    }

    private Map<String, Object> facts() {
      return Map.of(
          "payments",
          jdbc.queryForList("SELECT * FROM pay_record ORDER BY pay_no"),
          "intents",
          jdbc.queryForList("SELECT * FROM pay_debit_intent ORDER BY pay_no"));
    }

    private void assertRejectedWithoutChangingFacts() {
      var before = facts();
      var failure = assertThrows(BizException.class, () -> controller.success("pay-1"));
      assertEquals(503, failure.getHttpStatus());
      assertEquals("PAY_DEBIT_MODE_UNAVAILABLE", failure.getCode());
      assertEquals(before, facts());
      assertEquals(2, paymentReads.get());
      verifyNoInteractions(metrics, legacy, tcc);
      observations.add(Map.of("result", failure.getCode(), "facts", before));
    }

    private void verifyBranch(String mode) {
      if (mode.equals("LEGACY")) {
        verify(legacy).initiate(any());
        verifyNoMoreInteractions(legacy);
        verifyNoInteractions(tcc);
      } else {
        verify(tcc).pay(any());
        verifyNoMoreInteractions(tcc);
        verifyNoInteractions(legacy);
      }
      verify(metrics).route(PayProtocolMetrics.mode(mode), PayProtocolMetrics.Entry.MOCK_SUCCESS);
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 先保留完整事实，成功门通过后只删除当前UUID库；失败现场保留。 */
    @Override
    public void close() throws Exception {
      context.close();
      AuthContext.clear();
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      Path evidence =
          Path.of(System.getProperty("basedir", "."), "target", "pay-success-refactor", schema);
      Files.createDirectories(evidence);
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("schema", schema);
      report.put("successful", successful);
      report.put("facts", facts());
      report.put("observations", observations);
      var json = new ObjectMapper().findAndRegisterModules();
      json.writeValue(evidence.resolve("before-cleanup.json").toFile(), report);
      if (!successful) {
        System.out.println("PAY_SUCCESS_PRESERVED=" + schema);
        return;
      }
      if (!schema.matches("pay_success_refactor_[0-9a-f]{32}"))
        throw new IllegalStateException("未知库归属");
      administrator.execute("DROP DATABASE " + schema);
      assertEquals(
          0,
          administrator.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              schema));
      report.put("deleted", true);
      json.writeValue(evidence.resolve("finished.json").toFile(), report);
    }
  }
}
