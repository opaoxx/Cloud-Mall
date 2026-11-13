package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.pay.config.PayTccBridgeConfiguration;
import com.cloudmall.pay.config.PayTccCancelTableInitializer;
import com.cloudmall.pay.config.PayTccReceiptConfiguration;
import com.cloudmall.pay.config.PayTccReceiptTableInitializer;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.receipt.PayTccExecutionEvidence;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptBusinessCallback;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptLocalResourceImpl;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptServiceImpl;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import io.seata.rm.tcc.TCCFenceHandler;
import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.rm.tcc.api.LocalTCC;
import io.seata.rm.tcc.api.TwoPhaseBusinessAction;
import io.seata.rm.tcc.config.TCCFenceConfig;
import io.seata.rm.tcc.store.db.TCCFenceStoreDataBaseDAO;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实配置/事务/Fence的隔离MySQL验证；不注册TC分支，Bridge仅提供已持久化锚点。 */
class PayTccReceiptMySqlIT {
  @Test
  void actualPackageScanPreservesConditionalBeansAndTransactionContracts() throws Exception {
    for (boolean enabled : new boolean[] {false, true}) {
      try (var fixture = new Fixture(enabled)) {
        Set<String> beans = new HashSet<>();
        for (String name : fixture.context.getBeanDefinitionNames()) {
          if (AopUtils.getTargetClass(fixture.context.getBean(name))
              .getPackageName()
              .equals("com.cloudmall.pay.service.impl.receipt")) beans.add(name);
        }
        Set<String> expected =
            new HashSet<>(Set.of("payTccReceiptStore", "payTccExecutionEvidence"));
        if (enabled)
          expected.addAll(
              Set.of(
                  "payTccReceiptServiceImpl",
                  "payTccReceiptBusinessCallback",
                  "payTccReceiptLocalResource"));
        assertEquals(expected, beans);
        assertEquals(enabled, fixture.context.containsBean("payTccReceiptConfiguration"));
        assertEquals(enabled, fixture.context.containsBean("tccFenceConfig"));
        assertTrue(AopUtils.isAopProxy(fixture.receipts));
        assertTrue(AopUtils.isAopProxy(fixture.evidence));
        if (enabled) {
          assertFalse(AopUtils.isAopProxy(fixture.facade));
          assertFalse(AopUtils.isAopProxy(fixture.resource));
          assertInstanceOf(PayTccReceiptLocalResourceImpl.class, fixture.resource);
          assertInstanceOf(
              PayTccReceiptBusinessCallback.class,
              fixture.context.getBean("payTccReceiptBusinessCallback"));
          assertSame(fixture.dataSource, fixture.fenceConfig.getDataSource());
          assertSame(fixture.manager, fixture.fenceConfig.getTransactionManager());
        }
        var attributes = new AnnotationTransactionAttributeSource();
        Map<String, Integer> methods = new HashMap<>();
        for (var method : PayTccReceiptStore.class.getDeclaredMethods()) {
          if (!Modifier.isPublic(method.getModifiers())) continue;
          var attribute = attributes.getTransactionAttribute(method, PayTccReceiptStore.class);
          if (attribute != null) methods.put(method.getName(), attribute.getPropagationBehavior());
        }
        assertEquals(
            Map.of(
                "claim",
                TransactionDefinition.PROPAGATION_REQUIRES_NEW,
                "transitionClaimed",
                TransactionDefinition.PROPAGATION_REQUIRED,
                "transition",
                TransactionDefinition.PROPAGATION_REQUIRED),
            methods);
        var classify =
            attributes.getTransactionAttribute(
                PayTccExecutionEvidence.class.getMethod(
                    "classify", PayTccExecutionPO.class, PayTccDispatchVO.class),
                PayTccExecutionEvidence.class);
        assertNotNull(classify);
        assertTrue(classify.isReadOnly());
        assertEquals(TransactionDefinition.ISOLATION_REPEATABLE_READ, classify.getIsolationLevel());
        assertEquals(TransactionDefinition.PROPAGATION_REQUIRED, classify.getPropagationBehavior());
        assertTrue(PayTccReceiptLocalResource.class.isAnnotationPresent(LocalTCC.class));
        var action =
            PayTccReceiptLocalResource.class
                .getMethod("prepare", BusinessActionContext.class, PayTccDispatchDTO.class)
                .getAnnotation(TwoPhaseBusinessAction.class);
        assertEquals("cloudmallPayDebitReceipt", action.name());
        assertEquals("commit", action.commitMethod());
        assertEquals("rollback", action.rollbackMethod());
        assertFalse(action.useTCCFence());
        verifyNoInteractions(fixture.bridge);
        fixture.successful = true;
      }
    }
  }

  @Test
  void independentClaimAndTryConfirmFaultsKeepReceiptAndFenceAtomic() throws Exception {
    try (var fixture = new Fixture(true)) {
      var outer = new TransactionTemplate(fixture.manager);
      outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      outer.executeWithoutResult(
          status -> {
            long connection = fixture.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertEquals(
                0,
                fixture.jdbc.queryForObject("SELECT COUNT(*) FROM pay_tcc_receipt", Integer.class));
            assertTrue(fixture.receipts.claim(fixture.anchor()));
            assertNotEquals(connection, fixture.claimConnection);
            assertEquals(
                0,
                fixture.jdbc.queryForObject("SELECT COUNT(*) FROM pay_tcc_receipt", Integer.class));
            assertEquals(
                connection, fixture.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            status.setRollbackOnly();
          });
      fixture.assertState("CLAIMED", null);
      RootContext.bind("receipt-refactor-xid");
      fixture.failUpdatesTo("TRYING");
      assertThrows(RuntimeException.class, () -> fixture.resource.prepare(action(), request()));
      fixture.assertState("CLAIMED", null);
      fixture.clearFault();
      assertTrue(fixture.resource.prepare(action(), request()));
      fixture.assertState("TRYING", 1);
      fixture.failUpdatesTo("CONFIRMED");
      assertFalse(fixture.resource.commit(action()));
      fixture.assertState("TRYING", 1);
      fixture.clearFault();
      assertTrue(fixture.resource.commit(action()));
      fixture.assertState("CONFIRMED", 2);
      fixture.assertClassification("CONFIRMED", "COMMITTED");
      fixture.assertClassification("CANCELLED", "CONFLICT");
      fixture.jdbc.update("DELETE FROM tcc_fence_log");
      assertTrue(fixture.resource.commit(action()));
      assertFalse(fixture.resource.rollback(action()));
      fixture.assertState("CONFIRMED", null);
      assertEquals("CONFIRMED", fixture.facade.read("pay-1").state);
      assertEquals("CONFIRMED", fixture.facade.prepare(request()).state);
      fixture.successful = true;
    }
  }

  @Test
  void normalAndEmptyCancelRollbackTogetherAndNeverResurrectAfterFenceCleanup() throws Exception {
    for (boolean prepared : new boolean[] {false, true}) {
      try (var fixture = new Fixture(true)) {
        assertTrue(fixture.receipts.claim(fixture.anchor()));
        RootContext.bind("receipt-refactor-xid");
        if (prepared) assertTrue(fixture.resource.prepare(action(), request()));
        fixture.failUpdatesTo("CANCELLED");
        assertFalse(fixture.resource.rollback(action()));
        fixture.assertState(prepared ? "TRYING" : "CLAIMED", prepared ? 1 : null);
        fixture.clearFault();
        assertTrue(fixture.resource.rollback(action()));
        fixture.assertState("CANCELLED", prepared ? 3 : 4);
        assertTrue(fixture.resource.rollback(action()));
        fixture.assertClassification("CANCELLED", "ROLLEDBACK");
        fixture.assertClassification(null, "UNKNOWN");
        fixture.jdbc.update("DELETE FROM tcc_fence_log");
        assertTrue(fixture.resource.rollback(action()));
        assertThrows(RuntimeException.class, () -> fixture.resource.prepare(action(), request()));
        fixture.assertState("CANCELLED", null);
        fixture.successful = true;
      }
    }
  }

  /** 原资源载荷，测试不会借用新的支付号或操作键。 */
  private static PayTccDispatchDTO request() {
    var request = new PayTccDispatchDTO();
    request.payNo = "pay-1";
    request.orderNo = "order-1";
    request.userId = 7L;
    request.amount = BigDecimal.TEN;
    return request;
  }

  /** 仅模拟已存在的分支上下文，不向TC登记分支。 */
  private static BusinessActionContext action() {
    var context = new BusinessActionContext();
    context.setXid("receipt-refactor-xid");
    context.setBranchId(7L);
    context.setActionName(PayTccReceiptLocalResource.ACTION_NAME);
    context.setActionContext(Map.of("request", request()));
    return context;
  }

  /** 通过生产注解实际发现迁移类型，仅专用Profile启用此测试配置。 */
  @Configuration
  @Profile("pay-tcc-receipt-refactor-test")
  @ComponentScan(basePackageClasses = PayTccReceiptStore.class)
  @EnableTransactionManagement(proxyTargetClass = true)
  static class ScannedInfrastructure {}

  /** 原配置及官方Fence绑定只作用于本例UUID数据库，退出恢复全部共享绑定。 */
  private static final class Fixture implements AutoCloseable {
    /** 当前测试唯一数据库。 */
    private final String schema =
        "pay_receipt_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 当前测试元数据及清理前事实。 */
    private final Path artifacts =
        Path.of(System.getProperty("basedir", "."), "target", "pay-receipt-refactor", schema);

    /** 管理连接只访问空源表DDL及本例owned库。 */
    private final JdbcTemplate administrator;

    /** 官方Fence与业务Mapper共用的裸数据源。 */
    private final DataSource dataSource;

    /** owned准备、故障注入及事后查询入口。 */
    private final JdbcTemplate jdbc;

    /** 同数据源的真实事务管理器。 */
    private final DataSourceTransactionManager manager;

    /** 原Spring扫描和配置上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 仅返回已提交派发锚点的替身。 */
    private final PayTccBridgeService bridge = mock(PayTccBridgeService.class);

    /** claim/状态迁移的真实事务代理。 */
    private final PayTccReceiptStore receipts;

    /** classify的真实只读RR代理。 */
    private final PayTccExecutionEvidence evidence;

    /** 启用时由原Bean工厂创建的Facade。 */
    private final PayTccReceiptServiceImpl facade;

    /** 启用时由原Bean工厂创建的官方Fence适配器。 */
    private final PayTccReceiptLocalResource resource;

    /** 原配置创建的清理执行器所有者，包括启动失败时的清理。 */
    private TCCFenceConfig fenceConfig;

    /** 实际claim写入使用的连接编号。 */
    private Long claimConnection;

    /** classify观察窗口，避免把其它合法非事务读取算入RR检查。 */
    private boolean observingEvidence;

    /** 只读RR中的实际Mapper连接记录。 */
    private final List<Long> evidenceConnections = new ArrayList<>();

    /** 已验证的资源分类及读取连接。 */
    private final List<Map<String, Object>> classifications = new ArrayList<>();

    /** 进入前的官方Handler数据源。 */
    private final DataSource previousDataSource =
        (DataSource) ReflectionTestUtils.getField(TCCFenceHandler.class, "dataSource");

    /** 进入前的官方Handler事务模板。 */
    private final TransactionTemplate previousTemplate =
        (TransactionTemplate)
            ReflectionTestUtils.getField(TCCFenceHandler.class, "transactionTemplate");

    /** 官方DAO原日志表名。 */
    private final String previousLogTable =
        (String)
            ReflectionTestUtils.getField(TCCFenceStoreDataBaseDAO.getInstance(), "logTableName");

    /** 原线程XID，退出时精确恢复。 */
    private final String previousXid = RootContext.getXID();

    /** 只有全部断言成功才清理owned库。 */
    private boolean successful;

    private Fixture(boolean enabled) throws Exception {
      assertTrue(queue().isEmpty());
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_pay"))
        throw new IllegalStateException("显式IT需要独立loopback cloudmall_pay裸URL");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute(
          "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      Files.createDirectories(artifacts);
      new ObjectMapper()
          .writeValue(
              artifacts.resolve("fixture.json").toFile(),
              Map.of("schema", schema, "enabled", enabled));
      dataSource =
          new DriverManagerDataSource(
              configured(url.replace("/cloudmall_pay", "/" + schema)), "root", "root");
      jdbc = new JdbcTemplate(dataSource);
      manager = new DataSourceTransactionManager(dataSource);
      try {
        for (String table :
            List.of("pay_record", "pay_tcc_dispatch", "pay_tcc_receipt", "tcc_fence_log"))
          jdbc.execute(
              (String) administrator.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
        jdbc.update(
            "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at) VALUES(1,'pay-1','order-1',7,10.00,'PENDING','TCC',NOW(3),NOW(3))");
        jdbc.update(
            "INSERT INTO pay_tcc_dispatch(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at) VALUES(2,'pay-1','receipt-refactor-xid',7,'order-1',10.00,'tcc-debit:pay-1','pay-1','PREPARED',NOW(3),NOW(3))");
        var configuration = new MybatisConfiguration();
        configuration.setMapUnderscoreToCamelCase(true);
        configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
        var types =
            List.of(
                PayRecordMapper.class,
                PayTccDispatchMapper.class,
                PayTccReceiptMapper.class,
                PayTccFenceMapper.class,
                PayTccCancelMapper.class);
        for (Class<?> type : types) configuration.addMapper(type);
        var factory = new MybatisSqlSessionFactoryBean();
        factory.setDataSource(dataSource);
        factory.setConfiguration(configuration);
        var session = new SqlSessionTemplate(factory.getObject());
        context.getEnvironment().setActiveProfiles("pay-tcc-receipt-refactor-test");
        Map<String, Object> flags =
            new HashMap<>(
                Map.of(
                    "cloudmall.pay.tcc-bridge.enabled",
                    true,
                    "seata.enabled",
                    true,
                    "seata.enable-auto-data-source-proxy",
                    false));
        if (enabled) flags.put("cloudmall.pay.tcc-receipt.enabled", true);
        context
            .getEnvironment()
            .getPropertySources()
            .addFirst(new MapPropertySource("receiptFixture", flags));
        var beans = context.getBeanFactory();
        beans.registerSingleton("dataSource", dataSource);
        beans.registerSingleton("transactionManager", manager);
        beans.registerSingleton("bridge", bridge);
        beans.registerSingleton("metrics", PayProtocolMetrics.noop());
        for (Class<?> type : types)
          beans.registerSingleton(type.getSimpleName(), observed(type, session.getMapper(type)));
        beans.addBeanPostProcessor(
            new BeanPostProcessor() {
              /** 只记录原Fence配置实例，不替换Bean或更改其生命周期。 */
              @Override
              public Object postProcessBeforeInitialization(Object bean, String beanName) {
                if (bean instanceof TCCFenceConfig configuration) fenceConfig = configuration;
                return bean;
              }
            });
        context.register(
            ScannedInfrastructure.class,
            PayTccBridgeConfiguration.class,
            PayTccReceiptConfiguration.class,
            PayTccReceiptTableInitializer.class,
            PayTccCancelTableInitializer.class);
        context.refresh();
        receipts = context.getBean(PayTccReceiptStore.class);
        evidence = context.getBean(PayTccExecutionEvidence.class);
        facade = enabled ? context.getBean(PayTccReceiptServiceImpl.class) : null;
        resource = enabled ? context.getBean(PayTccReceiptLocalResource.class) : null;
        when(bridge.prepare(any())).thenAnswer(invocation -> anchor());
      } catch (Exception | Error failure) {
        stopAndRestore();
        new ObjectMapper()
            .writeValue(
                artifacts.resolve("setup-failure.json").toFile(),
                Map.of("schema", schema, "errorType", failure.getClass().getName()));
        throw failure;
      }
    }

    /** 记录真实SQL所在事务，不替换Mapper结果。 */
    private Object observed(Class<?> type, Object target) {
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          new Class<?>[] {type},
          (proxy, method, arguments) -> {
            if (type == PayTccReceiptMapper.class && method.getName().equals("claim")) {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              claimConnection = jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            }
            if (observingEvidence && method.getDeclaringClass() != Object.class) {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              assertTrue(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
              assertEquals(
                  TransactionDefinition.ISOLATION_REPEATABLE_READ,
                  TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
              evidenceConnections.add(jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            }
            try {
              return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
              throw failure.getCause();
            }
          });
    }

    private PayTccDispatchVO anchor() {
      var anchor = new PayTccDispatchVO();
      anchor.payNo = "pay-1";
      anchor.orderNo = "order-1";
      anchor.xid = "receipt-refactor-xid";
      anchor.userId = 7L;
      anchor.amount = "10.00";
      anchor.operationKey = "tcc-debit:pay-1";
      anchor.ledgerKey = "pay-1";
      anchor.state = "PREPARED";
      return anchor;
    }

    private void assertState(String receiptState, Integer fenceState) {
      assertEquals(receiptState, receipts.read("pay-1").state);
      var rows = jdbc.queryForList("SELECT status FROM tcc_fence_log", Integer.class);
      assertEquals(fenceState == null ? List.of() : List.of(fenceState), rows);
      assertEquals("PENDING", jdbc.queryForObject("SELECT status FROM pay_record", String.class));
    }

    private void failUpdatesTo(String state) {
      assertTrue(Set.of("TRYING", "CONFIRMED", "CANCELLED").contains(state));
      jdbc.execute(
          "CREATE TRIGGER receipt_fault BEFORE UPDATE ON pay_tcc_receipt FOR EACH ROW BEGIN IF NEW.state='"
              + state
              + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled receipt fault'; END IF; END");
    }

    private void clearFault() {
      jdbc.execute("DROP TRIGGER receipt_fault");
    }

    /** 真实RR代理只读取同连接回执/Fence，输入User镜像为受控事实。 */
    private void assertClassification(String userState, String expected) {
      var execution = new PayTccExecutionPO();
      execution.payNo = "pay-1";
      execution.orderNo = "order-1";
      execution.xid = "receipt-refactor-xid";
      execution.userId = 7L;
      execution.amount = BigDecimal.TEN;
      execution.operationKey = "tcc-debit:pay-1";
      execution.ledgerKey = "pay-1";
      var user = userState == null ? null : anchor();
      if (user != null) {
        user.state = userState;
        user.userBranchId = 22L;
      }
      var before = facts();
      evidenceConnections.clear();
      observingEvidence = true;
      try {
        assertEquals(expected, evidence.classify(execution, user));
      } finally {
        observingEvidence = false;
      }
      assertEquals(2, evidenceConnections.size());
      assertEquals(1, new HashSet<>(evidenceConnections).size());
      assertEquals(before, facts());
      classifications.add(
          Map.of("result", expected, "connections", List.copyOf(evidenceConnections)));
    }

    private Map<String, Object> facts() {
      return Map.of(
          "payments",
          jdbc.queryForList("SELECT * FROM pay_record"),
          "dispatches",
          jdbc.queryForList("SELECT * FROM pay_tcc_dispatch"),
          "receipts",
          jdbc.queryForList("SELECT * FROM pay_tcc_receipt"),
          "fences",
          jdbc.queryForList("SELECT * FROM tcc_fence_log"),
          "cancellations",
          jdbc.queryForList("SELECT * FROM pay_tcc_cancel"));
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    private static BlockingQueue<?> queue() {
      return (BlockingQueue<?>) ReflectionTestUtils.getField(TCCFenceHandler.class, "LOG_QUEUE");
    }

    /** 停止本配置的清理执行器，再恢复官方共享绑定；官方初始化daemon不在此测试中伪装成已关闭。 */
    private void stopAndRestore() throws InterruptedException {
      try {
        context.close();
        if (fenceConfig != null) {
          fenceConfig.destroy();
          var executor =
              (ScheduledThreadPoolExecutor)
                  ReflectionTestUtils.getField(fenceConfig, "tccFenceClean");
          assertNotNull(executor);
          assertTrue(executor.awaitTermination(5, TimeUnit.SECONDS));
        }
      } finally {
        TCCFenceHandler.setDataSource(previousDataSource);
        TCCFenceHandler.setTransactionTemplate(previousTemplate);
        TCCFenceStoreDataBaseDAO.getInstance().setLogTableName(previousLogTable);
        RootContext.unbind();
        if (previousXid != null) RootContext.bind(previousXid);
      }
      assertSame(
          previousDataSource, ReflectionTestUtils.getField(TCCFenceHandler.class, "dataSource"));
      assertSame(
          previousTemplate,
          ReflectionTestUtils.getField(TCCFenceHandler.class, "transactionTemplate"));
      assertEquals(
          previousLogTable,
          ReflectionTestUtils.getField(TCCFenceStoreDataBaseDAO.getInstance(), "logTableName"));
      assertTrue(queue().isEmpty());
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    }

    /** 先保存持久事实和静态恢复结果，成功才删除当前UUID库。 */
    @Override
    public void close() throws Exception {
      stopAndRestore();
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("schema", schema);
      report.put("successful", successful);
      report.put("facts", facts());
      report.put("claimConnection", claimConnection);
      report.put("classifications", classifications);
      report.put("fenceBindingsRestored", true);
      report.put("fenceCleanerStopped", fenceConfig != null);
      var json = new ObjectMapper().findAndRegisterModules();
      json.writeValue(artifacts.resolve("before-cleanup.json").toFile(), report);
      if (!successful) {
        System.out.println("PAY_RECEIPT_PRESERVED=" + schema);
        return;
      }
      if (!schema.matches("pay_receipt_refactor_[0-9a-f]{32}"))
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
