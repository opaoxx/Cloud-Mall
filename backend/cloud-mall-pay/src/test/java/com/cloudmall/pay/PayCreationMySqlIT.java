package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.controller.PayCreationController;
import com.cloudmall.pay.domain.dto.PayCreateDTO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayCreationService;
import com.cloudmall.pay.service.impl.PayCreationServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 仅在新隔离MySQL上验证真实Mapper/创建事务；Order为替身，不启动TC或资金流程。 */
class PayCreationMySqlIT {
  @Test
  void commitsSelectedModeAndReplaysByKeyOrOrderWithoutChangingPersistedFacts() throws Exception {
    for (String mode : List.of("LEGACY", "TCC")) {
      try (var fixture = new Fixture(mode)) {
        AuthContext.set(7L, "USER");
        PayVO first = fixture.controller.create("original", request()).data;
        assertEquals("PENDING", first.status);
        assertEquals(new BigDecimal("10.00"), first.amount);
        assertNull(first.paidAt);
        assertEquals(
            mode, fixture.jdbc.queryForObject("SELECT debit_mode FROM pay_record", String.class));
        var before = fixture.rows();
        clearInvocations(fixture.orders);
        assertEquals(first.payNo, fixture.controller.create("original", request()).data.payNo);
        verifyNoInteractions(fixture.orders);
        assertEquals(first.payNo, fixture.controller.create("different-key", request()).data.payNo);
        verify(fixture.orders).get("order-1", 7L);
        var conflict = request();
        conflict.payAmount = new BigDecimal("10.01");
        assertEquals(
            409,
            assertThrows(BizException.class, () -> fixture.controller.create("original", conflict))
                .getHttpStatus());
        assertEquals(before, fixture.rows());
        assertEquals(1, fixture.inserts.get());
        fixture.successful = true;
      }
    }
  }

  @Test
  void readFailureAfterRealInsertRollsBackAndOriginalKeyCanBeRetried() throws Exception {
    try (var fixture = new Fixture("LEGACY")) {
      AuthContext.set(7L, "USER");
      fixture.rejectReadAfterInsert = true;
      assertThrows(
          DataAccessResourceFailureException.class,
          () -> fixture.controller.create("original", request()));
      assertEquals(1, fixture.inserts.get());
      assertEquals(0, fixture.rowCount());
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      fixture.rejectReadAfterInsert = false;
      assertEquals("PENDING", fixture.controller.create("original", request()).data.status);
      assertEquals(1, fixture.rowCount());
      assertEquals(2, fixture.inserts.get());
      fixture.successful = true;
    }
  }

  @Test
  void concurrentSameKeyKeepsOneRowAndReplayAfterCompletionWithoutAddingRetries() throws Exception {
    try (var fixture = new Fixture("LEGACY")) {
      var executor = Executors.newFixedThreadPool(4);
      var ready = new CountDownLatch(4);
      var start = new CountDownLatch(1);
      List<Future<String>> attempts = new ArrayList<>();
      try {
        for (int index = 0; index < 4; index++) {
          attempts.add(
              executor.submit(
                  () -> {
                    AuthContext.set(7L, "USER");
                    ready.countDown();
                    try {
                      assertTrue(start.await(5, TimeUnit.SECONDS));
                      try {
                        return fixture.controller.create("concurrent", request()).data.payNo;
                      } catch (DuplicateKeyException originalRace) {
                        return "ORIGINAL_UNIQUE_CONSTRAINT_RACE";
                      }
                    } finally {
                      AuthContext.clear();
                      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                    }
                  }));
        }
        assertTrue(ready.await(5, TimeUnit.SECONDS));
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (var attempt : attempts) outcomes.add(attempt.get(15, TimeUnit.SECONDS));
        assertEquals(1, fixture.rowCount());
        AuthContext.set(7L, "USER");
        String payment = fixture.controller.create("concurrent", request()).data.payNo;
        assertTrue(outcomes.contains(payment));
        assertTrue(
            outcomes.stream()
                .allMatch(
                    outcome ->
                        outcome.equals(payment)
                            || outcome.equals("ORIGINAL_UNIQUE_CONSTRAINT_RACE")));
        fixture.outcomes = outcomes;
        fixture.successful = true;
      } finally {
        start.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
      }
    }
  }

  private static PayCreateDTO request() {
    var request = new PayCreateDTO();
    request.orderNo = "order-1";
    request.payAmount = new BigDecimal("10.00");
    return request;
  }

  /** 只在本类显式启动的上下文开启事务代理。 */
  @Configuration
  @Profile("pay-creation-mysql-it")
  @EnableTransactionManagement
  static class Transactions {}

  /** 每例只拥有一个全新UUID库；任一未通过用例保留现场，不无条件drop。 */
  static class Fixture implements AutoCloseable {
    /** 受控的新测试库。 */
    private final String schema =
        "pay_creation_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 只用于本例UUID库生命周期的管理连接。 */
    private final JdbcTemplate administrator;

    /** 本例实际数据库事实读取。 */
    private final JdbcTemplate jdbc;

    /** 创建时订单读取替身。 */
    private final OrderClient orders = mock(OrderClient.class);

    /** 原共享写锁。 */
    private final PayWriteMonitor monitor = new PayWriteMonitor();

    /** 已实际成功执行的INSERT次数，不代表已经提交。 */
    private final AtomicInteger inserts = new AtomicInteger();

    /** 仅用于故障注入的开关。 */
    private volatile boolean rejectReadAfterInsert;

    /** 同线程INSERT后下一次读取故障标志。 */
    private final ThreadLocal<Boolean> rejectNextRead = ThreadLocal.withInitial(() -> false);

    /** 真正业务事务上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 实际创建Controller，委托Spring事务代理。 */
    private final PayCreationController controller;

    /** 所有断言通过后才允许删除本例库。 */
    private boolean successful;

    /** 并发用例记录实际成功/原唯一约束冲突，不伪造全成功。 */
    private List<String> outcomes = List.of();

    Fixture(String mode) throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_pay"))
        throw new IllegalStateException("需要独立实验cloudmall_pay URL");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      System.out.println("PAY_CREATION_SCHEMA=" + schema);
      var source =
          new DriverManagerDataSource(
              configured(url.replace("/cloudmall_pay", "/" + schema)), "root", "root");
      jdbc = new JdbcTemplate(source);
      try {
        jdbc.execute("CREATE TABLE pay_record LIKE cloudmall_pay.pay_record");
        PayRecordMapper actual = PayDebitModeM2MySqlIT.recordMapper(source);
        PayRecordMapper observed =
            (PayRecordMapper)
                Proxy.newProxyInstance(
                    PayRecordMapper.class.getClassLoader(),
                    new Class<?>[] {PayRecordMapper.class},
                    (proxy, method, arguments) -> {
                      if (method.getDeclaringClass() == Object.class)
                        return method.invoke(actual, arguments);
                      assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                      assertTrue(Thread.holdsLock(monitor));
                      if (method.getName().equals("selectByPayNo") && rejectNextRead.get()) {
                        rejectNextRead.remove();
                        throw new DataAccessResourceFailureException(
                            "TEST_READ_AFTER_PAYMENT_INSERT");
                      }
                      try {
                        Object result = method.invoke(actual, arguments);
                        if (method.getName().equals("insert")) {
                          inserts.incrementAndGet();
                          rejectNextRead.set(rejectReadAfterInsert);
                        }
                        return result;
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
        when(orders.get("order-1", 7L))
            .thenAnswer(
                invocation -> {
                  assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  assertTrue(Thread.holdsLock(monitor));
                  return ApiResponse.ok(
                      new OrderClient.OrderView(
                          "order-1", "PENDING_PAYMENT", new BigDecimal("10.00"), 7L));
                });
        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("cloudmall.pay.debit-mode", mode);
        for (String feature :
            List.of("entry", "coordinator", "receipt", "bridge", "recovery", "delivery"))
          properties.put("cloudmall.pay.tcc-" + feature + ".enabled", "true");
        properties.put("seata.enabled", "true");
        properties.put("seata.enable-auto-data-source-proxy", "false");
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(new MapPropertySource("testMode", properties));
        context.getEnvironment().setActiveProfiles("pay-creation-mysql-it");
        context.register(
            Transactions.class,
            PayCreationController.class,
            PayCreationServiceImpl.class,
            PayDebitModeConfiguration.class);
        context.getBeanFactory().registerSingleton("records", observed);
        context.getBeanFactory().registerSingleton("orders", orders);
        context.getBeanFactory().registerSingleton("payWriteMonitor", monitor);
        context
            .getBeanFactory()
            .registerSingleton("transactionManager", new DataSourceTransactionManager(source));
        context.refresh();
        controller = context.getBean(PayCreationController.class);
        assertTrue(AopUtils.isAopProxy(context.getBean(PayCreationService.class)));
        assertFalse(AopUtils.isAopProxy(controller));
      } catch (Exception | Error failure) {
        context.close();
        System.out.println("PAY_CREATION_PRESERVE_SETUP_FAILURE=" + schema);
        throw failure;
      }
    }

    private int rowCount() {
      return jdbc.queryForObject("SELECT COUNT(*) FROM pay_record", Integer.class);
    }

    private List<Map<String, Object>> rows() {
      return jdbc.queryForList("SELECT * FROM pay_record ORDER BY pay_no");
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 先归档实际事实，再仅在成功门通过时删除本例库；失败库交由后续人工核对。 */
    @Override
    public void close() throws Exception {
      AuthContext.clear();
      rejectNextRead.remove();
      context.close();
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      Path evidence =
          Path.of(System.getProperty("basedir", "."), "target", "pay-creation-refactor", schema);
      Files.createDirectories(evidence);
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("schema", schema);
      report.put("successful", successful);
      report.put("rowCount", rowCount());
      report.put("rows", rows());
      report.put("insertStatementsCompleted", inserts.get());
      report.put("concurrentOutcomes", outcomes);
      var json = new ObjectMapper().findAndRegisterModules();
      json.writeValue(evidence.resolve("before-cleanup.json").toFile(), report);
      if (!successful) {
        System.out.println("PAY_CREATION_PRESERVED=" + schema);
        return;
      }
      if (!schema.matches("pay_creation_refactor_[0-9a-f]{32}"))
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
