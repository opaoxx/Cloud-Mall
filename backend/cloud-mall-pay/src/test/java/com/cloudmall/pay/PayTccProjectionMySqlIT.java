package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.projection.PayTccPublicStateStore;
import com.cloudmall.pay.service.impl.projection.PayTccRollbackProjectionService;
import com.cloudmall.pay.service.impl.receipt.PayTccExecutionEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 新子包实际扫描、真实事务与MySQL事实；User仅为受控GET替身，不连接TC。 */
class PayTccProjectionMySqlIT {
  @Test
  void publicProjectionSuspendsCallerAndCommitsEvenWhenCallerRollsBack() throws Exception {
    try (var fixture = new Fixture()) {
      assertEquals(
          Set.of("payTccPublicStateStore"),
          fixture.context.getBeansOfType(PayTccPublicStateStore.class).keySet());
      assertEquals(
          Set.of("payTccRollbackProjectionService"),
          fixture.context.getBeansOfType(PayTccRollbackProjectionService.class).keySet());
      assertTrue(AopUtils.isAopProxy(fixture.states));
      assertTrue(AopUtils.isAopProxy(fixture.projection));
      doAnswer(
              invocation -> {
                fixture.assertRemoteWindow(invocation.getArguments());
                assertEquals("PENDING", fixture.status());
                return ApiResponse.ok(cancelledUser());
              })
          .when(fixture.users)
          .state(anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString());
      var before = fixture.nonPaymentFacts();
      fixture
          .outer()
          .executeWithoutResult(
              transaction -> {
                long caller = fixture.connection();
                assertEquals("PENDING", fixture.status());
                assertEquals("FAILED", fixture.projection.project(payment()));
                assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals(caller, fixture.connection());
                assertEquals("PENDING", fixture.status());
                var proof = fixture.connections(true);
                var write = fixture.connections(false);
                assertEquals(1, proof.size());
                assertEquals(1, write.size());
                assertNotEquals(proof, write);
                assertFalse(proof.contains(caller));
                assertFalse(write.contains(caller));
                transaction.setRollbackOnly();
              });
      assertEquals("FAILED", fixture.status());
      assertEquals(1, fixture.failedWrites());
      assertEquals(before, fixture.nonPaymentFacts());
      verify(fixture.users)
          .state(
              7L,
              "tcc-debit:pay-1",
              7L,
              "cloud-mall-pay",
              "cloudmall-local-callback",
              "projection-xid");
      verifyNoMoreInteractions(fixture.users);
      fixture.successful = true;
    }
  }

  @Test
  void claimedProjectionRechecksTakeoverOrExpiryAfterRemoteWindowWithoutPublicFallback()
      throws Exception {
    for (boolean takeover : new boolean[] {false, true}) {
      try (var fixture = new Fixture()) {
        doAnswer(
                invocation -> {
                  fixture.assertRemoteWindow(invocation.getArguments());
                  if (takeover)
                    fixture.jdbc.update("UPDATE pay_tcc_execution SET recovery_owner='owner-new'");
                  else
                    fixture.jdbc.update(
                        "UPDATE pay_tcc_execution SET recovery_lease_until=DATE_SUB(NOW(3),INTERVAL 1 SECOND)");
                  return ApiResponse.ok(cancelledUser());
                })
            .when(fixture.users)
            .state(anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString());
        fixture
            .outer()
            .executeWithoutResult(
                transaction -> {
                  long caller = fixture.connection();
                  assertEquals(
                      "owner-old",
                      fixture.jdbc.queryForObject(
                          "SELECT recovery_owner FROM pay_tcc_execution", String.class));
                  var failure =
                      assertThrows(
                          BizException.class,
                          () -> fixture.projection.projectClaimed(payment(), "owner-old"));
                  assertEquals(202, failure.getHttpStatus());
                  assertEquals("PAY_ORDER_SYNC_UNKNOWN", failure.getCode());
                  assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  assertEquals(caller, fixture.connection());
                  assertEquals(
                      "owner-old",
                      fixture.jdbc.queryForObject(
                          "SELECT recovery_owner FROM pay_tcc_execution", String.class));
                  transaction.setRollbackOnly();
                });
        assertEquals("PENDING", fixture.status());
        assertEquals(0, fixture.failedWrites());
        assertTrue(
            fixture.operations.stream()
                .anyMatch(
                    row ->
                        row.get("method").equals("PayTccExecutionMapper.selectForUpdate")
                            && Boolean.FALSE.equals(row.get("readOnly"))));
        assertEquals(
            takeover ? "owner-new" : "owner-old",
            fixture.jdbc.queryForObject(
                "SELECT recovery_owner FROM pay_tcc_execution", String.class));
        if (!takeover)
          assertEquals(
              1,
              fixture.jdbc.queryForObject(
                  "SELECT COUNT(*) FROM pay_tcc_execution WHERE recovery_lease_until<NOW(3)",
                  Integer.class));
        assertEquals(
            "ROLLEDBACK",
            fixture.jdbc.queryForObject("SELECT state FROM pay_tcc_execution", String.class));
        assertEquals(
            "ROLLBACK",
            fixture.jdbc.queryForObject("SELECT decision FROM pay_tcc_execution", String.class));
        verify(fixture.users)
            .state(anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString());
        verifyNoMoreInteractions(fixture.users);
        fixture.successful = true;
      }
    }
  }

  @Test
  void unavailableOrIncompleteUserProofRestoresCallerWithoutWritingPayment() throws Exception {
    try (var fixture = new Fixture()) {
      var unexpected = new IllegalStateException("controlled User failure");
      var unavailable = mock(FeignException.class);
      for (String variant : List.of("FEIGN", "MISSING_LEDGER", "NULL", "RUNTIME")) {
        reset(fixture.users);
        fixture.operations.clear();
        var before = fixture.facts();
        doAnswer(
                invocation -> {
                  fixture.assertRemoteWindow(invocation.getArguments());
                  if (variant.equals("FEIGN")) throw unavailable;
                  if (variant.equals("RUNTIME")) throw unexpected;
                  if (variant.equals("NULL")) return null;
                  var user = cancelledUser();
                  user.ledgerAppliedPresent = false;
                  return ApiResponse.ok(user);
                })
            .when(fixture.users)
            .state(anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString());
        fixture
            .outer()
            .executeWithoutResult(
                transaction -> {
                  long caller = fixture.connection();
                  assertEquals("PENDING", fixture.status());
                  RuntimeException failure =
                      assertThrows(
                          RuntimeException.class, () -> fixture.projection.project(payment()));
                  if (variant.equals("RUNTIME")) assertSame(unexpected, failure);
                  else {
                    var business = assertInstanceOf(BizException.class, failure);
                    assertEquals(202, business.getHttpStatus());
                    assertEquals("PAY_ORDER_SYNC_UNKNOWN", business.getCode());
                  }
                  assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
                  assertEquals(caller, fixture.connection());
                  assertEquals("PENDING", fixture.status());
                  transaction.setRollbackOnly();
                });
        assertEquals(before, fixture.facts());
        assertEquals(0, fixture.failedWrites());
        assertTrue(fixture.connections(false).isEmpty());
        verify(fixture.users)
            .state(anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString());
        verifyNoMoreInteractions(fixture.users);
        fixture.observations.add(
            Map.of(
                "variant",
                variant,
                "operations",
                List.copyOf(fixture.operations),
                "factsUnchanged",
                true));
      }
      fixture.successful = true;
    }
  }

  /** 固定原支付载荷，不依赖调用者VO的status授权。 */
  private static PayVO payment() {
    var payment = new PayVO();
    payment.payNo = "pay-1";
    payment.userId = 7L;
    payment.orderNo = "order-1";
    payment.amount = BigDecimal.TEN;
    return payment;
  }

  /** 显式无账本且与本地原分支完全一致的受控User事实。 */
  private static UserTccStateVO cancelledUser() {
    var user = new UserTccStateVO();
    user.payNo = "pay-1";
    user.userId = 7L;
    user.orderNo = "order-1";
    user.amount = "10.00";
    user.xid = "projection-xid";
    user.operationKey = "tcc-debit:pay-1";
    user.ledgerKey = "pay-1";
    user.branchId = 22L;
    user.state = "CANCELLED";
    user.setLedgerApplied(false);
    return user;
  }

  /** 专用Profile隔离测试配置，实际扫描两个生产投影组件。 */
  @Configuration
  @Profile("pay-tcc-projection-refactor-test")
  @ComponentScan(basePackageClasses = PayTccPublicStateStore.class)
  @EnableTransactionManagement(proxyTargetClass = true)
  static class ScannedInfrastructure {}

  /** 每例独立UUID库，所有生产读写均走实际Mapper；失败不删库。 */
  private static final class Fixture implements AutoCloseable {
    /** 当前夹具唯一数据库。 */
    private final String schema =
        "pay_projection_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 连接前后证据目录。 */
    private final Path artifacts =
        Path.of(System.getProperty("basedir", "."), "target", "pay-projection-refactor", schema);

    /** 读取空源DDL及管理当前owned库的连接。 */
    private final JdbcTemplate administrator;

    /** 三段事务共用的原裸数据源。 */
    private final DataSource dataSource;

    /** owned准备、窗口变异与事后核验入口。 */
    private final JdbcTemplate jdbc;

    /** 实际本地事务管理器。 */
    private final DataSourceTransactionManager manager;

    /** 生产扫描及事务代理上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 不发送HTTP的User状态查询替身。 */
    private final UserTccClient users = mock(UserTccClient.class);

    /** 原NOT_SUPPORTED两个入口的代理。 */
    private final PayTccRollbackProjectionService projection;

    /** RR证明与独立提交的实际Store代理。 */
    private final PayTccPublicStateStore states;

    /** 原Mapper实际连接、读写属性及调用方法记录。 */
    private final List<Map<String, Object>> operations = new ArrayList<>();

    /** 多异常变体的已验证事实。 */
    private final List<Map<String, Object>> observations = new ArrayList<>();

    /** 所有断言通过后才允许清理。 */
    private boolean successful;

    private Fixture() throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null
          || !url.matches(
              "jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_pay(?:\\?allowPublicKeyRetrieval=true)?"))
        throw new IllegalStateException("显式IT需要独立loopback cloudmall_pay URL");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute(
          "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      Files.createDirectories(artifacts);
      new ObjectMapper()
          .writeValue(artifacts.resolve("fixture.json").toFile(), Map.of("schema", schema));
      dataSource =
          new DriverManagerDataSource(
              configured(url.replace("/cloudmall_pay", "/" + schema)), "root", "root");
      jdbc = new JdbcTemplate(dataSource);
      manager = new DataSourceTransactionManager(dataSource);
      try {
        for (String table :
            List.of(
                "pay_record",
                "pay_debit_intent",
                "pay_tcc_execution",
                "pay_tcc_dispatch",
                "pay_tcc_receipt",
                "tcc_fence_log",
                "pay_tcc_cancel"))
          jdbc.execute(
              (String) administrator.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
        jdbc.update(
            "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at) VALUES(1,'pay-1','order-1',7,10.00,'PENDING','TCC',NOW(3),NOW(3))");
        jdbc.update(
            "INSERT INTO pay_tcc_execution(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,decision,recovery_owner,recovery_lease_until,created_at,updated_at) VALUES(2,'pay-1','projection-xid',7,'order-1',10.00,'tcc-debit:pay-1','pay-1','ROLLEDBACK','ROLLBACK','owner-old',DATE_ADD(NOW(3),INTERVAL 1 HOUR),NOW(3),NOW(3))");
        jdbc.update(
            "INSERT INTO pay_tcc_dispatch(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,user_branch_id,created_at,updated_at) VALUES(3,'pay-1','projection-xid',7,'order-1',10.00,'tcc-debit:pay-1','pay-1','CANCELLED',22,NOW(3),NOW(3))");
        jdbc.update(
            "INSERT INTO pay_tcc_receipt(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,branch_id,state,created_at,updated_at) VALUES(4,'pay-1','projection-xid',7,'order-1',10.00,'tcc-debit:pay-1','pay-1',7,'CANCELLED',NOW(3),NOW(3))");
        jdbc.update(
            "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified) VALUES('projection-xid',7,?,3,NOW(3),NOW(3))",
            PayTccReceiptLocalResource.ACTION_NAME);
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var types =
            List.of(
                PayRecordMapper.class,
                PayTccExecutionMapper.class,
                PayTccDispatchMapper.class,
                PayTccReceiptMapper.class,
                PayTccFenceMapper.class,
                PayTccCancelMapper.class);
        for (Class<?> type : types) configuration.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        context.getEnvironment().setActiveProfiles("pay-tcc-projection-refactor-test");
        var beans = context.getBeanFactory();
        beans.registerSingleton("dataSource", dataSource);
        beans.registerSingleton("transactionManager", manager);
        beans.registerSingleton("users", users);
        beans.registerSingleton("metrics", PayProtocolMetrics.noop());
        beans.registerSingleton("intents", new PayDebitIntentMapper(jdbc));
        for (Class<?> type : types)
          beans.registerSingleton(type.getSimpleName(), observed(type, session.getMapper(type)));
        context.register(ScannedInfrastructure.class, PayTccExecutionEvidence.class);
        context.refresh();
        projection = context.getBean(PayTccRollbackProjectionService.class);
        states = context.getBean(PayTccPublicStateStore.class);
      } catch (Exception | Error failure) {
        context.close();
        new ObjectMapper()
            .writeValue(
                artifacts.resolve("setup-failure.json").toFile(),
                Map.of("schema", schema, "errorType", failure.getClass().getName()));
        throw failure;
      }
    }

    /** 只观察实际Mapper调用；本切片证明读与写都必须处于各自原事务。 */
    private Object observed(Class<?> type, Object target) {
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          new Class<?>[] {type},
          (proxy, method, arguments) -> {
            if (method.getDeclaringClass() != Object.class) {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              boolean readOnly = TransactionSynchronizationManager.isCurrentTransactionReadOnly();
              if (readOnly)
                assertEquals(
                    TransactionDefinition.ISOLATION_REPEATABLE_READ,
                    TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
              operations.add(
                  Map.of(
                      "method",
                      type.getSimpleName() + "." + method.getName(),
                      "connection",
                      connection(),
                      "readOnly",
                      readOnly));
            }
            try {
              return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
              throw failure.getCause();
            }
          });
    }

    private void assertRemoteWindow(Object[] arguments) {
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertFalse(TransactionSynchronizationManager.hasResource(dataSource));
      assertArrayEquals(
          new Object[] {
            7L,
            "tcc-debit:pay-1",
            7L,
            "cloud-mall-pay",
            "cloudmall-local-callback",
            "projection-xid"
          },
          arguments);
    }

    private TransactionTemplate outer() {
      var outer = new TransactionTemplate(manager);
      outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      return outer;
    }

    private long connection() {
      return jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
    }

    private String status() {
      return jdbc.queryForObject("SELECT status FROM pay_record", String.class);
    }

    private Set<Long> connections(boolean readOnly) {
      return operations.stream()
          .filter(row -> Boolean.valueOf(readOnly).equals(row.get("readOnly")))
          .map(row -> (Long) row.get("connection"))
          .collect(Collectors.toSet());
    }

    private long failedWrites() {
      return operations.stream()
          .filter(row -> row.get("method").equals("PayRecordMapper.markFailed"))
          .count();
    }

    private Map<String, Object> nonPaymentFacts() {
      Map<String, Object> facts = new LinkedHashMap<>();
      for (String table :
          List.of(
              "pay_debit_intent",
              "pay_tcc_execution",
              "pay_tcc_dispatch",
              "pay_tcc_receipt",
              "tcc_fence_log",
              "pay_tcc_cancel")) facts.put(table, jdbc.queryForList("SELECT * FROM " + table));
      return facts;
    }

    private Map<String, Object> facts() {
      var facts = nonPaymentFacts();
      facts.put("pay_record", jdbc.queryForList("SELECT * FROM pay_record"));
      return facts;
    }

    private static String configured(String url) {
      return url
          + (url.contains("?") ? "&" : "?allowPublicKeyRetrieval=true&")
          + "useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 先保存完整事实，成功后只删除当前UUID库，失败现场保留。 */
    @Override
    public void close() throws Exception {
      context.close();
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("schema", schema);
      report.put("successful", successful);
      report.put("facts", facts());
      report.put("operations", operations);
      report.put("observations", observations);
      var json = new ObjectMapper().findAndRegisterModules();
      json.writeValue(artifacts.resolve("before-cleanup.json").toFile(), report);
      if (!successful) {
        System.out.println("PAY_PROJECTION_PRESERVED=" + schema);
        return;
      }
      if (!schema.matches("pay_projection_refactor_[0-9a-f]{32}"))
        throw new IllegalStateException("未知库归属");
      administrator.execute("DROP DATABASE " + schema);
      assertEquals(
          0,
          administrator.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              schema));
      report.put("deleted", true);
      json.writeValue(artifacts.resolve("finished.json").toFile(), report);
    }
  }
}
