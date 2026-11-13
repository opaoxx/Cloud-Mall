package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.controller.PayCallbackController;
import com.cloudmall.pay.domain.dto.PayCallbackDTO;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayCallbackService;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.impl.PayCallbackReceiptService;
import com.cloudmall.pay.service.impl.PayCallbackServiceImpl;
import com.cloudmall.pay.service.impl.PayConsistencyService;
import com.cloudmall.pay.service.impl.PayLegacyFailureService;
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
import java.util.Objects;
import java.util.UUID;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实回调日志/支付Mapper及Receipt独立事务；成功编排为替身，不执行资金RPC。 */
class PayCallbackMySqlIT {
  @Test
  void unknownAndTerminalRejectionKeepClaimWhileDirectionPayloadAndGlobalIdRemainBound()
      throws Exception {
    try (var fixture = new Fixture("PENDING")) {
      when(fixture.legacy.initiate(any()))
          .thenAnswer(
              invocation -> {
                assertTrue(Thread.holdsLock(fixture.monitor));
                assertEquals("RECEIVED_SUCCESS", fixture.externalLogStatus("callback-1"));
                return "UNKNOWN";
              });
      fixture.assertCode("PAY_ORDER_SYNC_UNKNOWN", request(true));
      assertNull(
          fixture.jdbc.queryForObject(
              "SELECT processed_at FROM pay_callback_log WHERE callback_id='callback-1'",
              Object.class));
      fixture.assertCode("PAY_CALLBACK_CONFLICT", request(false));
      fixture.jdbc.update("UPDATE pay_record SET amount=20.00 WHERE pay_no='pay-1'");
      var changedAmount = request(true);
      changedAmount.amount = new BigDecimal("20.00");
      fixture.assertCode("PAY_CALLBACK_CONFLICT", changedAmount);
      fixture.jdbc.update("UPDATE pay_record SET amount=10.00 WHERE pay_no='pay-1'");
      fixture.jdbc.update(
          "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at) VALUES(2,'pay-2','order-2',7,10.00,'PENDING','LEGACY','2026-11-13 12:00:00.123','2026-11-13 12:00:00.123')");
      var otherPayment = request(true);
      otherPayment.payNo = "pay-2";
      fixture.assertCode("PAY_CALLBACK_CONFLICT", otherPayment);
      fixture.jdbc.update("UPDATE pay_record SET status='FAILED' WHERE pay_no='pay-1'");
      var terminal = request(true);
      terminal.callbackId = "terminal-rejected";
      fixture.assertCode("PAY_ALREADY_PROCESSED", terminal);
      assertEquals("RECEIVED_SUCCESS", fixture.externalLogStatus("terminal-rejected"));
      assertEquals(
          2, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM pay_callback_log", Integer.class));
      verify(fixture.legacy).initiate(any());
      verifyNoInteractions(fixture.orders);
      fixture.successful = true;
    }
  }

  @Test
  void completeFailureAndFinalReadFailurePreserveAlreadyCommittedStagesForReplay()
      throws Exception {
    try (var fixture = new Fixture("PENDING")) {
      when(fixture.legacy.initiate(any()))
          .thenAnswer(
              invocation -> {
                assertTrue(Thread.holdsLock(fixture.monitor));
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("RECEIVED_SUCCESS", fixture.externalLogStatus("callback-1"));
                fixture.external.update(
                    "UPDATE pay_record SET status='SUCCESS' WHERE pay_no='pay-1'");
                return "SUCCESS";
              });
      fixture.jdbc.execute(
          "CREATE TRIGGER reject_callback_complete BEFORE UPDATE ON pay_callback_log FOR EACH ROW SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='TEST_CALLBACK_COMPLETE_FAILED'");
      assertThrows(
          DataAccessException.class,
          () -> fixture.controller.callback("native-token", request(true)));
      assertEquals(
          "SUCCESS",
          fixture.jdbc.queryForObject(
              "SELECT status FROM pay_record WHERE pay_no='pay-1'", String.class));
      assertEquals("RECEIVED_SUCCESS", fixture.externalLogStatus("callback-1"));
      fixture.jdbc.execute("DROP TRIGGER reject_callback_complete");
      fixture.failFinalRead = true;
      assertThrows(
          DataAccessResourceFailureException.class,
          () -> fixture.controller.callback("native-token", request(true)));
      assertEquals("SUCCESS", fixture.externalLogStatus("callback-1"));
      assertNotNull(
          fixture.jdbc.queryForObject(
              "SELECT processed_at FROM pay_callback_log WHERE callback_id='callback-1'",
              Object.class));
      fixture.failFinalRead = false;
      assertEquals(
          "SUCCESS", fixture.controller.callback("native-token", request(true)).data.status);
      assertEquals(
          1, fixture.jdbc.queryForObject("SELECT COUNT(*) FROM pay_callback_log", Integer.class));
      verify(fixture.legacy).initiate(any());
      fixture.successful = true;
    }
  }

  @Test
  void realLegacyFailureCompletesAndSameDirectionReplayDoesNotRepeatCancellation()
      throws Exception {
    try (var fixture = new Fixture("PENDING")) {
      when(fixture.orders.cancel("order-1", 7L))
          .thenAnswer(
              invocation -> {
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertTrue(Thread.holdsLock(fixture.monitor));
                assertEquals("RECEIVED_FAILURE", fixture.externalLogStatus("pay-1"));
                return ApiResponse.ok(null);
              });
      var request = request(false);
      request.callbackId = null;
      assertEquals("FAILED", fixture.controller.callback("native-token", request).data.status);
      assertEquals("FAILED", fixture.externalLogStatus("pay-1"));
      request.amount = new BigDecimal("10.000");
      assertEquals("FAILED", fixture.controller.callback("native-token", request).data.status);
      verify(fixture.orders).cancel("order-1", 7L);
      verifyNoInteractions(fixture.legacy);
      var payload =
          new ObjectMapper()
              .readTree(
                  fixture.jdbc.queryForObject(
                      "SELECT payload FROM pay_callback_log WHERE callback_id='pay-1'",
                      String.class));
      assertEquals(4, payload.size());
      assertEquals("10.00", payload.get("amount").asText());
      assertFalse(payload.get("success").asBoolean());
      fixture.successful = true;
    }
  }

  @Test
  void callerRepeatableReadRollbackDoesNotUndoIndependentClaimOrComplete() throws Exception {
    try (var fixture = new Fixture("SUCCESS")) {
      var outer = new TransactionTemplate(fixture.transactions);
      outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      outer.execute(
          status -> {
            long outerConnection = fixture.connectionId();
            assertEquals(
                0,
                fixture.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pay_callback_log", Integer.class));
            Object resource = TransactionSynchronizationManager.getResource(fixture.source);
            assertEquals(
                "SUCCESS", fixture.controller.callback("native-token", request(true)).data.status);
            assertSame(resource, TransactionSynchronizationManager.getResource(fixture.source));
            assertEquals(outerConnection, fixture.connectionId());
            assertEquals(
                0,
                fixture.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pay_callback_log", Integer.class));
            assertEquals("SUCCESS", fixture.externalLogStatus("callback-1"));
            assertEquals(2, fixture.lockConnections.size());
            assertNotEquals(outerConnection, fixture.lockConnections.get(0));
            assertNotEquals(outerConnection, fixture.lockConnections.get(1));
            assertNotEquals(fixture.lockConnections.get(0), fixture.lockConnections.get(1));
            status.setRollbackOnly();
            return null;
          });
      assertEquals("SUCCESS", fixture.externalLogStatus("callback-1"));
      verifyNoInteractions(fixture.legacy, fixture.orders);
      fixture.successful = true;
    }
  }

  private static PayCallbackDTO request(boolean success) {
    var request = new PayCallbackDTO();
    request.payNo = "pay-1";
    request.callbackId = "callback-1";
    request.userId = 7L;
    request.amount = new BigDecimal("10.00");
    request.success = success;
    return request;
  }

  /** 仅本测试显式启用原事务拦截。 */
  @Configuration
  @Profile("pay-callback-mysql-it")
  @EnableTransactionManagement(proxyTargetClass = true)
  static class Transactions {}

  /** 独立UUID数据库和真实生产代理，只有业务成功/Order远端为替身。 */
  static class Fixture implements AutoCloseable {
    /** 当前库唯一归属。 */
    private final String schema =
        "pay_callback_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 当前库生命周期连接。 */
    private final JdbcTemplate administrator;

    /** 原事务绑定数据源。 */
    private final DriverManagerDataSource source;

    /** 同事务事实读取。 */
    private final JdbcTemplate jdbc;

    /** 独立连接，用来核验真实提交。 */
    private final JdbcTemplate external;

    /** 原本地事务管理器。 */
    private final DataSourceTransactionManager transactions;

    /** 原共享监视器。 */
    private final PayWriteMonitor monitor = new PayWriteMonitor();

    /** Legacy成功编排替身。 */
    private final PayConsistencyService legacy = mock(PayConsistencyService.class);

    /** Order取消替身。 */
    private final OrderClient orders = mock(OrderClient.class);

    /** 事务/锁观察，不模拟提交。 */
    private final List<Map<String, Object>> observations = new ArrayList<>();

    /** 原支付锁定读使用的连接。 */
    private final List<Long> lockConnections = new ArrayList<>();

    /** 完成日志后注入末读故障的开关。 */
    private boolean failFinalRead;

    /** 只有实际完成CAS成功后才置位。 */
    private boolean rejectNextRead;

    /** 本例实际Spring上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 新回调入口。 */
    private final PayCallbackController controller;

    /** 全部断言通过才允许删除当前库。 */
    private boolean successful;

    Fixture(String status) throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_pay"))
        throw new IllegalStateException("需要独立实验cloudmall_pay URL");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute("CREATE DATABASE " + schema + " CHARACTER SET utf8mb4");
      System.out.println("PAY_CALLBACK_SCHEMA=" + schema);
      String ownedUrl = configured(url.replace("/cloudmall_pay", "/" + schema));
      source = new DriverManagerDataSource(ownedUrl, "root", "root");
      jdbc = new JdbcTemplate(source);
      external = new JdbcTemplate(new DriverManagerDataSource(ownedUrl, "root", "root"));
      transactions = new DataSourceTransactionManager(source);
      try {
        for (String table : List.of("pay_record", "pay_callback_log", "pay_debit_intent"))
          jdbc.execute("CREATE TABLE " + table + " LIKE cloudmall_pay." + table);
        jdbc.update(
            "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at) VALUES(1,'pay-1','order-1',7,10.00,?,'LEGACY','2026-11-13 12:00:00.123','2026-11-13 12:00:00.123')",
            status);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        configuration.setDefaultStatementTimeout(5);
        configuration.addMapper(PayRecordMapper.class);
        configuration.addMapper(PayCallbackLogMapper.class);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(source);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()));
        var records = observe(PayRecordMapper.class, session.getMapper(PayRecordMapper.class));
        var logs =
            observe(PayCallbackLogMapper.class, session.getMapper(PayCallbackLogMapper.class));
        context.getEnvironment().setActiveProfiles("pay-callback-mysql-it");
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(
                new MapPropertySource(
                    "callbackCredential",
                    Map.of("cloudmall.internal.callback-token", "native-token")));
        context.register(
            Transactions.class,
            PayCallbackController.class,
            PayCallbackServiceImpl.class,
            PayCallbackReceiptService.class,
            PayLegacyFailureService.class);
        var beans = context.getBeanFactory();
        beans.registerSingleton("records", records);
        beans.registerSingleton("logs", logs);
        beans.registerSingleton("intents", new PayDebitIntentMapper(jdbc));
        beans.registerSingleton("legacy", legacy);
        beans.registerSingleton("orders", orders);
        beans.registerSingleton("payWriteMonitor", monitor);
        beans.registerSingleton("metrics", PayProtocolMetrics.noop());
        beans.registerSingleton("objectMapper", new ObjectMapper());
        beans.registerSingleton("transactionManager", transactions);
        context.refresh();
        controller = context.getBean(PayCallbackController.class);
        assertFalse(AopUtils.isAopProxy(context.getBean(PayCallbackService.class)));
        assertFalse(AopUtils.isAopProxy(controller));
        assertTrue(AopUtils.isAopProxy(context.getBean(PayCallbackReceiptService.class)));
        assertTrue(AopUtils.isAopProxy(context.getBean(PayLegacyFailureService.class)));
      } catch (Exception | Error failure) {
        context.close();
        System.out.println("PAY_CALLBACK_PRESERVE_SETUP_FAILURE=" + schema);
        throw failure;
      }
    }

    private <Mapper> Mapper observe(Class<Mapper> type, Mapper actual) {
      return type.cast(
          Proxy.newProxyInstance(
              type.getClassLoader(),
              new Class<?>[] {type},
              (proxy, method, arguments) -> {
                if (method.getDeclaringClass() == Object.class)
                  return method.invoke(actual, arguments);
                assertTrue(Thread.holdsLock(monitor));
                boolean active = TransactionSynchronizationManager.isActualTransactionActive();
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
                  lockConnections.add(connectionId());
                }
                if (type == PayCallbackLogMapper.class) assertTrue(active);
                if (method.getName().equals("selectByPayNo") && rejectNextRead) {
                  rejectNextRead = false;
                  throw new DataAccessResourceFailureException("TEST_READ_AFTER_CALLBACK_COMPLETE");
                }
                try {
                  Object result = method.invoke(actual, arguments);
                  if (method.getName().equals("markProcessed") && Integer.valueOf(1).equals(result))
                    rejectNextRead = failFinalRead;
                  return result;
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              }));
    }

    private long connectionId() {
      return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    private String externalLogStatus(String callbackId) {
      return external.queryForObject(
          "SELECT callback_status FROM pay_callback_log WHERE callback_id=?",
          String.class,
          callbackId);
    }

    private void assertCode(String code, PayCallbackDTO request) {
      assertEquals(
          code,
          assertThrows(BizException.class, () -> controller.callback("native-token", request))
              .getCode());
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 先归档事实与事务观察，只有全部验证通过才清理当前UUID库。 */
    @Override
    public void close() throws Exception {
      context.close();
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      Path evidence =
          Path.of(System.getProperty("basedir", "."), "target", "pay-callback-refactor", schema);
      Files.createDirectories(evidence);
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("schema", schema);
      report.put("successful", successful);
      report.put("observations", observations);
      report.put("lockConnections", lockConnections);
      for (String table : List.of("pay_record", "pay_callback_log", "pay_debit_intent"))
        report.put(table, jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id"));
      var json = new ObjectMapper().findAndRegisterModules();
      json.writeValue(evidence.resolve("before-cleanup.json").toFile(), report);
      if (!successful) {
        System.out.println("PAY_CALLBACK_PRESERVED=" + schema);
        return;
      }
      if (!schema.matches("pay_callback_refactor_[0-9a-f]{32}"))
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
