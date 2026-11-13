package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.controller.PayFailureController;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.service.PayFailureService;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.impl.PayFailureServiceImpl;
import com.cloudmall.pay.service.impl.PayTccPaymentService;
import com.cloudmall.pay.service.impl.PayTccPublicStateStore;
import com.cloudmall.pay.service.impl.PayTccRollbackProjectionService;
import com.cloudmall.pay.service.impl.legacy.PayLegacyFailureService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实Mapper及原授权事务代理；Order/TCC外部事实为替身，不启动资金RPC或TC。 */
class PayFailureMySqlIT {
  @Test
  void legacyCommitsIndependentlyOfCallerRollbackAndTerminalReplayDoesNotCancelAgain()
      throws Exception {
    try (var fixture = new Fixture("LEGACY")) {
      AuthContext.set(7L, "USER");
      fixture
          .outer()
          .execute(
              status -> {
                long outerConnection = fixture.connectionId();
                assertEquals("PENDING", fixture.status());
                assertEquals("FAILED", fixture.controller.fail("pay-1").data.status);
                assertEquals("PENDING", fixture.status(), "outer RR snapshot remains unchanged");
                assertEquals(outerConnection, fixture.connectionId());
                assertNotEquals(outerConnection, fixture.currentReadConnections.get(0));
                status.setRollbackOnly();
                return null;
              });
      assertEquals("FAILED", fixture.status());
      assertEquals("FAILED", fixture.controller.fail("pay-1").data.status);
      verify(fixture.orders).cancel("order-1", 7L);
      assertEquals(2, fixture.currentReadConnections.size());
      fixture.successful = true;
    }
  }

  @Test
  void remoteRejectionAndLocalCasFailurePreserveOriginalRollbackAndErrorRules() throws Exception {
    try (var fixture = new Fixture("LEGACY")) {
      AuthContext.set(7L, "USER");
      doReturn(null).when(fixture.orders).cancel("order-1", 7L);
      assertEquals(
          "订单失败回滚未确认",
          assertThrows(BizException.class, () -> fixture.controller.fail("pay-1")).getMessage());
      assertEquals("PENDING", fixture.status());
      var original = new BizException("ORIGINAL_ORDER_REJECTION", "rejected", 409);
      doThrow(original).when(fixture.orders).cancel("order-1", 7L);
      assertSame(
          original, assertThrows(BizException.class, () -> fixture.controller.fail("pay-1")));
      doThrow(new IllegalStateException("unknown cancellation"))
          .when(fixture.orders)
          .cancel("order-1", 7L);
      var unknown = assertThrows(BizException.class, () -> fixture.controller.fail("pay-1"));
      assertEquals(500, unknown.getHttpStatus());
      assertEquals("订单失败回滚失败", unknown.getMessage());
      assertEquals("PENDING", fixture.status());
      fixture.allowCancellation();
      fixture.jdbc.execute(
          "CREATE TRIGGER reject_failure BEFORE UPDATE ON pay_record FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TEST_FAILURE_CAS_REJECTED'");
      assertThrows(DataAccessException.class, () -> fixture.controller.fail("pay-1"));
      assertEquals("PENDING", fixture.status());
      fixture.jdbc.execute("DROP TRIGGER reject_failure");
      assertEquals("FAILED", fixture.controller.fail("pay-1").data.status);
      assertEquals("FAILED", fixture.status());
      verify(fixture.orders, times(5)).cancel("order-1", 7L);
      fixture.successful = true;
    }
  }

  @Test
  void staleOuterNoIntentSnapshotCannotAuthorizeCancellationAfterCommittedInitAppears()
      throws Exception {
    try (var fixture = new Fixture("LEGACY")) {
      AuthContext.set(7L, "USER");
      fixture
          .outer()
          .execute(
              status -> {
                long originalConnection = fixture.connectionId();
                assertNull(fixture.intents.findByPayNo("pay-1"));
                fixture.external.update(
                    "INSERT INTO pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,next_retry_at,created_at,updated_at) VALUES(2,'pay-1','order-1',7,10.00,'INIT','LEGACY','2026-11-13 12:00:00.123','2026-11-13 12:00:00.123','2026-11-13 12:00:00.123')");
                assertNull(fixture.intents.findByPayNo("pay-1"));
                var outcome =
                    assertThrows(BizException.class, () -> fixture.controller.fail("pay-1"));
                assertEquals(202, outcome.getHttpStatus());
                assertEquals("PAY_ORDER_SYNC_UNKNOWN", outcome.getCode());
                assertEquals(originalConnection, fixture.connectionId());
                assertNotEquals(originalConnection, fixture.currentReadConnections.get(0));
                assertNull(
                    fixture.intents.findByPayNo("pay-1"),
                    "caller snapshot restored after REQUIRES_NEW");
                status.setRollbackOnly();
                return null;
              });
      assertEquals("INIT", fixture.intents.findByPayNo("pay-1").status);
      assertEquals("PENDING", fixture.status());
      verifyNoInteractions(fixture.orders);
      fixture.successful = true;
    }
  }

  @Test
  void realTccFailureSuspendsCallerAndRestoresItOnUnknownAndMutatedViewReturn() throws Exception {
    try (var fixture = new Fixture("TCC")) {
      AuthContext.set(7L, "USER");
      when(fixture.states.execution("pay-1"))
          .thenAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.hasResource(fixture.source));
                var execution = new PayTccExecutionPO();
                execution.state = "RUNNING";
                return execution;
              });
      fixture
          .outer()
          .execute(
              status -> {
                long originalConnection = fixture.connectionId();
                Object originalResource =
                    TransactionSynchronizationManager.getResource(fixture.source);
                assertEquals(
                    202,
                    assertThrows(BizException.class, () -> fixture.controller.fail("pay-1"))
                        .getHttpStatus());
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertSame(
                    originalResource,
                    TransactionSynchronizationManager.getResource(fixture.source));
                assertEquals(originalConnection, fixture.connectionId());
                return null;
              });
      doAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertFalse(TransactionSynchronizationManager.hasResource(fixture.source));
                var execution = new PayTccExecutionPO();
                execution.state = "ROLLEDBACK";
                return execution;
              })
          .when(fixture.states)
          .execution("pay-1");
      when(fixture.projection.project(any()))
          .thenAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                return "FAILED";
              });
      fixture
          .outer()
          .execute(
              status -> {
                long originalConnection = fixture.connectionId();
                assertEquals("FAILED", fixture.controller.fail("pay-1").data.status);
                assertEquals(
                    "PENDING", fixture.status(), "no extra read replaces the TCC-mutated view");
                assertEquals(originalConnection, fixture.connectionId());
                return null;
              });
      assertEquals("PENDING", fixture.status());
      verify(fixture.projection).project(any());
      verifyNoInteractions(fixture.orders);
      assertTrue(fixture.currentReadConnections.isEmpty());
      fixture.successful = true;
    }
  }

  /** 只在本类显式配置中启用原Spring事务拦截。 */
  @Configuration
  @Profile("pay-failure-mysql-it")
  @EnableTransactionManagement(proxyTargetClass = true)
  static class Transactions {}

  /** 每例单独UUID库，原授权类/事务真实，远端和TCC证明为可控替身。 */
  static class Fixture implements AutoCloseable {
    /** 当前库身份。 */
    private final String schema =
        "pay_failure_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 当前库生命周期管理连接。 */
    private final JdbcTemplate administrator;

    /** 与业务事务绑定的真实数据源。 */
    private final DriverManagerDataSource source;

    /** 与业务同数据源的事实读取。 */
    private final JdbcTemplate jdbc;

    /** 不加入当前线程外层事务的独立提交连接。 */
    private final JdbcTemplate external;

    /** 原意图Mapper。 */
    private final PayDebitIntentMapper intents;

    /** 独立当前读使用的连接身份。 */
    private final List<Long> currentReadConnections = new ArrayList<>();

    /** 每次Mapper访问的真实事务观察。 */
    private final List<Map<String, Object>> observations = new ArrayList<>();

    /** Order远端替身。 */
    private final OrderClient orders = mock(OrderClient.class);

    /** TCC执行事实替身，避免访问真实TC/User。 */
    private final PayTccPublicStateStore states = mock(PayTccPublicStateStore.class);

    /** TCC回滚投影替身。 */
    private final PayTccRollbackProjectionService projection =
        mock(PayTccRollbackProjectionService.class);

    /** 原事务管理器。 */
    private final DataSourceTransactionManager transactions;

    /** 实际Spring上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 实际新失败Controller。 */
    private final PayFailureController controller;

    /** 全部断言通过后的清理许可。 */
    private boolean successful;

    Fixture(String mode) throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_pay"))
        throw new IllegalStateException("需要独立实验cloudmall_pay URL");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      System.out.println("PAY_FAILURE_SCHEMA=" + schema);
      String ownedUrl = configured(url.replace("/cloudmall_pay", "/" + schema));
      source = new DriverManagerDataSource(ownedUrl, "root", "root");
      jdbc = new JdbcTemplate(source);
      external = new JdbcTemplate(new DriverManagerDataSource(ownedUrl, "root", "root"));
      transactions = new DataSourceTransactionManager(source);
      intents = new PayDebitIntentMapper(jdbc);
      try {
        jdbc.execute("CREATE TABLE pay_record LIKE cloudmall_pay.pay_record");
        jdbc.execute("CREATE TABLE pay_debit_intent LIKE cloudmall_pay.pay_debit_intent");
        jdbc.update(
            "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at) VALUES(1,'pay-1','order-1',7,10.00,'PENDING',?,'2026-11-13 12:00:00.123','2026-11-13 12:00:00.123')",
            mode);
        var actual = PayDebitModeM2MySqlIT.recordMapper(source);
        var observed =
            (PayRecordMapper)
                Proxy.newProxyInstance(
                    PayRecordMapper.class.getClassLoader(),
                    new Class<?>[] {PayRecordMapper.class},
                    (proxy, method, arguments) -> {
                      if (method.getDeclaringClass() == Object.class)
                        return method.invoke(actual, arguments);
                      boolean active =
                          TransactionSynchronizationManager.isActualTransactionActive();
                      observations.add(
                          Map.of(
                              "method",
                              method.getName(),
                              "transactionActive",
                              active,
                              "connectionId",
                              active ? connectionId() : -1L));
                      if (method.getName().equals("selectForUpdate")) {
                        assertTrue(active);
                        currentReadConnections.add(connectionId());
                      }
                      if (method.getName().equals("markFailed")) assertTrue(active);
                      try {
                        return method.invoke(actual, arguments);
                      } catch (InvocationTargetException failure) {
                        throw failure.getCause();
                      }
                    });
        allowCancellation();
        context.getEnvironment().setActiveProfiles("pay-failure-mysql-it");
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new MapPropertySource(
                    "testTccMethod", Map.of("cloudmall.pay.tcc-entry.enabled", true)));
        context.register(
            Transactions.class,
            PayFailureController.class,
            PayFailureServiceImpl.class,
            PayLegacyFailureService.class);
        var beans = context.getBeanFactory();
        beans.registerSingleton("records", observed);
        beans.registerSingleton("intents", intents);
        beans.registerSingleton("orders", orders);
        beans.registerSingleton("metrics", PayProtocolMetrics.noop());
        beans.registerSingleton("transactionManager", transactions);
        beans.registerSingleton("cancellations", mock(PayTccCancelMapper.class));
        // 只装配真实failure方法及其事务注解，完整TCC启动配置/TC连接不在本测试范围。
        beans.registerSingleton("payTccEntryConfiguration", new Object());
        context.registerBean(
            PayTccPaymentService.class,
            () ->
                new PayTccPaymentService(
                    mock(PayTccCoordinatorService.class),
                    mock(PayTccDeliveryService.class),
                    states,
                    orders,
                    projection));
        context.refresh();
        controller = context.getBean(PayFailureController.class);
        assertFalse(AopUtils.isAopProxy(context.getBean(PayFailureService.class)));
        assertFalse(AopUtils.isAopProxy(controller));
        assertTrue(AopUtils.isAopProxy(context.getBean(PayLegacyFailureService.class)));
        assertTrue(AopUtils.isAopProxy(context.getBean(PayTccPaymentService.class)));
      } catch (Exception | Error failure) {
        context.close();
        System.out.println("PAY_FAILURE_PRESERVE_SETUP_FAILURE=" + schema);
        throw failure;
      }
    }

    private void allowCancellation() {
      doAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                return ApiResponse.ok(null);
              })
          .when(orders)
          .cancel("order-1", 7L);
    }

    private long connectionId() {
      return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    private String status() {
      return jdbc.queryForObject(
          "SELECT status FROM pay_record WHERE pay_no='pay-1'", String.class);
    }

    private TransactionTemplate outer() {
      var template = new TransactionTemplate(transactions);
      template.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      return template;
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 先归档实际事务/事实，再仅对成功用例删除当前UUID库。 */
    @Override
    public void close() throws Exception {
      AuthContext.clear();
      context.close();
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      Path evidence =
          Path.of(System.getProperty("basedir", "."), "target", "pay-failure-refactor", schema);
      Files.createDirectories(evidence);
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("schema", schema);
      report.put("successful", successful);
      report.put("observations", observations);
      report.put("currentReadConnections", currentReadConnections);
      report.put("payments", jdbc.queryForList("SELECT * FROM pay_record"));
      report.put("intents", jdbc.queryForList("SELECT * FROM pay_debit_intent"));
      var json = new ObjectMapper().findAndRegisterModules();
      json.writeValue(evidence.resolve("before-cleanup.json").toFile(), report);
      if (!successful) {
        System.out.println("PAY_FAILURE_PRESERVED=" + schema);
        return;
      }
      if (!schema.matches("pay_failure_refactor_[0-9a-f]{32}"))
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
