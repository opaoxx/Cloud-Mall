package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayTccBridgeConfiguration;
import com.cloudmall.pay.config.PayTccCancelTableInitializer;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.service.impl.dispatch.PayTccBridgeServiceImpl;
import com.cloudmall.pay.service.impl.dispatch.PayTccDispatchPersistence;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Modifier;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.MapPropertySource;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;

/** 实际扫描派发子包与真实MySQL事务；User为替身，不连接TC或发送资金请求。 */
class PayTccDispatchMySqlIT {
  @Test
  void defaultDisabledBridgeKeepsBothBeansAndRejectsBeforeBusinessSql() throws Exception {
    try (var fixture = new Fixture(false)) {
      assertEquals(
          Set.of("payTccBridgeServiceImpl"),
          fixture.context.getBeansOfType(PayTccBridgeServiceImpl.class).keySet());
      assertEquals(
          Set.of("payTccDispatchPersistence"),
          fixture.context.getBeansOfType(PayTccDispatchPersistence.class).keySet());
      assertFalse(AopUtils.isAopProxy(fixture.bridge));
      assertTrue(AopUtils.isAopProxy(fixture.persistence));
      var attributes = new AnnotationTransactionAttributeSource();
      Set<String> methods = new HashSet<>();
      for (var method : PayTccDispatchPersistence.class.getDeclaredMethods()) {
        if (!Modifier.isPublic(method.getModifiers())) continue;
        var attribute = attributes.getTransactionAttribute(method, PayTccDispatchPersistence.class);
        assertNotNull(attribute);
        assertEquals(
            TransactionDefinition.PROPAGATION_REQUIRES_NEW, attribute.getPropagationBehavior());
        assertFalse(attribute.isReadOnly());
        methods.add(method.getName());
      }
      assertEquals(Set.of("prepare", "markPostAttempted", "mirror"), methods);
      RootContext.bind("refactor-xid");
      var failure = assertThrows(BizException.class, () -> fixture.bridge.prepare(request()));
      assertEquals("PAY_TCC_UNKNOWN", failure.getCode());
      assertEquals(503, failure.getHttpStatus());
      assertTrue(fixture.calls.isEmpty());
      verifyNoInteractions(fixture.user);
      fixture.successful = true;
    }
  }

  @Test
  void threeIndependentCommitsSurviveCallerSnapshotAndRollback() throws Exception {
    try (var fixture = new Fixture(true)) {
      var outer = new TransactionTemplate(fixture.manager);
      outer.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
      outer.executeWithoutResult(
          status -> {
            long callerConnection =
                fixture.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class);
            assertEquals(
                0,
                fixture.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pay_tcc_dispatch", Integer.class));
            var original = dispatch();
            assertSame(original, fixture.persistence.prepare(original));
            assertTrue(fixture.persistence.markPostAttempted(original, LocalDateTime.now()));
            assertTrue(
                fixture.persistence.mirror(
                    original, "PREPARED", "TRY_UNKNOWN", null, "unknown", LocalDateTime.now()));
            assertEquals(3, fixture.writes.size());
            assertEquals(
                List.of("insert", "markPostAttempted", "mirrorState"),
                fixture.writes.stream().map(row -> row.get("method")).toList());
            for (var write : fixture.writes)
              assertNotEquals(callerConnection, write.get("connection"));
            assertEquals(
                callerConnection,
                fixture.jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class));
            assertEquals(
                0,
                fixture.jdbc.queryForObject(
                    "SELECT COUNT(*) FROM pay_tcc_dispatch", Integer.class));
            status.setRollbackOnly();
          });
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      assertEquals("TRY_UNKNOWN", fixture.state());
      assertNotNull(
          fixture.jdbc.queryForObject(
              "SELECT post_attempted_at FROM pay_tcc_dispatch", LocalDateTime.class));
      assertEquals(
          "unknown",
          fixture.jdbc.queryForObject("SELECT last_error FROM pay_tcc_dispatch", String.class));
      assertEquals(
          "PENDING", fixture.jdbc.queryForObject("SELECT status FROM pay_record", String.class));
      verifyNoInteractions(fixture.user);
      fixture.successful = true;
    }
  }

  @Test
  void lostPostAndFailedMirrorKeepPermissionWhileReplayOnlyReadsUser() throws Exception {
    try (var fixture = new Fixture(true)) {
      RootContext.bind("refactor-xid");
      fixture.jdbc.execute(
          "CREATE TRIGGER fail_unknown BEFORE UPDATE ON pay_tcc_dispatch FOR EACH ROW BEGIN IF NEW.state='TRY_UNKNOWN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='controlled mirror fault'; END IF; END");
      doAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("POST_ATTEMPTED", fixture.state());
                assertEquals("refactor-xid", invocation.getArgument(4));
                throw new IllegalStateException("controlled lost User response");
              })
          .when(fixture.user)
          .prepare(
              eq(7L),
              eq(7L),
              eq("cloud-mall-pay"),
              eq("cloudmall-local-callback"),
              eq("refactor-xid"),
              any());
      assertThrows(DataAccessException.class, () -> fixture.bridge.dispatch(request()));
      assertEquals("POST_ATTEMPTED", fixture.state());
      assertNotNull(
          fixture.jdbc.queryForObject(
              "SELECT post_attempted_at FROM pay_tcc_dispatch", LocalDateTime.class));
      assertNull(
          fixture.jdbc.queryForObject("SELECT last_error FROM pay_tcc_dispatch", String.class));
      fixture.jdbc.execute("DROP TRIGGER fail_unknown");
      doAnswer(
              invocation -> {
                assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                assertEquals("POST_ATTEMPTED", fixture.state());
                return null;
              })
          .when(fixture.user)
          .state(
              eq(7L),
              eq("tcc-debit:pay-1"),
              eq(7L),
              eq("cloud-mall-pay"),
              eq("cloudmall-local-callback"),
              eq("refactor-xid"));
      assertEquals("TRY_UNKNOWN", fixture.bridge.dispatch(request()).state);
      verify(fixture.user, times(1))
          .prepare(anyLong(), anyLong(), anyString(), anyString(), anyString(), any());
      verify(fixture.user, times(1))
          .state(anyLong(), anyString(), anyLong(), anyString(), anyString(), anyString());
      verifyNoMoreInteractions(fixture.user);
      assertEquals(
          "PENDING", fixture.jdbc.queryForObject("SELECT status FROM pay_record", String.class));
      fixture.successful = true;
    }
  }

  /** 原请求载荷，不使用新XID或退款键。 */
  private static PayTccDispatchDTO request() {
    var request = new PayTccDispatchDTO();
    request.payNo = "pay-1";
    request.orderNo = "order-1";
    request.userId = 7L;
    request.amount = BigDecimal.TEN;
    return request;
  }

  /** 三个实际事务方法共用的原PREPARED快照。 */
  private static PayTccDispatchPO dispatch() {
    var dispatch = new PayTccDispatchPO();
    dispatch.id = 2L;
    dispatch.payNo = "pay-1";
    dispatch.orderNo = "order-1";
    dispatch.userId = 7L;
    dispatch.amount = BigDecimal.TEN;
    dispatch.xid = "refactor-xid";
    dispatch.operationKey = "tcc-debit:pay-1";
    dispatch.ledgerKey = "pay-1";
    dispatch.state = "PREPARED";
    dispatch.createdAt = LocalDateTime.now();
    dispatch.updatedAt = dispatch.createdAt;
    return dispatch;
  }

  /** 实际生产扫描及配置；专用Profile避免被其它Spring测试上下文加载。 */
  @Configuration
  @Profile("pay-tcc-dispatch-refactor-test")
  @ComponentScan(basePackageClasses = PayTccBridgeServiceImpl.class)
  @EnableTransactionManagement(proxyTargetClass = true)
  static class ScannedInfrastructure {}

  /** 单用例UUID数据库，所有业务SQL走原Mapper；成功门关闭时保留库和证据。 */
  private static final class Fixture implements AutoCloseable {
    /** 只用于创建/删除当前自有库和读取原表DDL的管理连接。 */
    private final JdbcTemplate administrator;

    /** 唯一自有库名。 */
    private final String schema =
        "pay_dispatch_refactor_" + UUID.randomUUID().toString().replace("-", "");

    /** 当前夹具SQL准备、故障注入和事后检查入口。 */
    private final JdbcTemplate jdbc;

    /** 原裸数据源事务管理器。 */
    private final DataSourceTransactionManager manager;

    /** 实际扫描上下文。 */
    private final AnnotationConfigApplicationContext context =
        new AnnotationConfigApplicationContext();

    /** 本地可观测User替身。 */
    private final UserTccClient user = mock(UserTccClient.class);

    /** 扫描得到的无外层事务派发桥。 */
    private final PayTccBridgeServiceImpl bridge;

    /** 扫描得到的独立事务代理。 */
    private final PayTccDispatchPersistence persistence;

    /** 启动完成后的实际Mapper调用记录。 */
    private final List<String> calls = new ArrayList<>();

    /** 三种写SQL所处事务的MySQL连接编号。 */
    private final List<Map<String, Object>> writes = new ArrayList<>();

    /** 测试进入前的Seata线程上下文。 */
    private final String originalXid = RootContext.getXID();

    /** 必须全部断言成功后才允许删库。 */
    private boolean successful;

    private Fixture(boolean enabled) throws Exception {
      String url = System.getProperty("cloudmall.mysql.test.url");
      if (url == null || !url.matches("jdbc:mysql://127\\.0\\.0\\.1:[0-9]+/cloudmall_pay"))
        throw new IllegalStateException("显式IT需要独立loopback cloudmall_pay裸URL");
      administrator =
          new JdbcTemplate(new DriverManagerDataSource(configured(url), "root", "root"));
      administrator.execute(
          "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      var dataSource =
          new DriverManagerDataSource(
              configured(url.replace("/cloudmall_pay", "/" + schema)), "root", "root");
      jdbc = new JdbcTemplate(dataSource);
      jdbc.execute(
          (String) administrator.queryForMap("SHOW CREATE TABLE pay_record").get("Create Table"));
      jdbc.update(
          "INSERT INTO pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at) VALUES(1,'pay-1','order-1',7,10.00,'PENDING','TCC',NOW(3),NOW(3))");
      manager = new DataSourceTransactionManager(dataSource);
      var configuration = new MybatisConfiguration();
      configuration.setMapUnderscoreToCamelCase(true);
      configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
      for (Class<?> type :
          List.of(PayRecordMapper.class, PayTccDispatchMapper.class, PayTccCancelMapper.class))
        configuration.addMapper(type);
      var factory = new MybatisSqlSessionFactoryBean();
      factory.setDataSource(dataSource);
      factory.setConfiguration(configuration);
      var session = new SqlSessionTemplate(factory.getObject());
      session.getMapper(PayTccDispatchMapper.class).ensureTable();
      context.getEnvironment().setActiveProfiles("pay-tcc-dispatch-refactor-test");
      Map<String, Object> properties = new LinkedHashMap<>();
      if (enabled) properties.put("cloudmall.pay.tcc-bridge.enabled", true);
      properties.put("seata.enabled", true);
      properties.put("seata.enable-auto-data-source-proxy", false);
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(new MapPropertySource("fixture", properties));
      context.getBeanFactory().registerSingleton("dataSource", dataSource);
      context.getBeanFactory().registerSingleton("transactionManager", manager);
      context.getBeanFactory().registerSingleton("userTccClient", user);
      for (Class<?> type :
          List.of(PayRecordMapper.class, PayTccDispatchMapper.class, PayTccCancelMapper.class))
        context
            .getBeanFactory()
            .registerSingleton(type.getSimpleName(), observed(type, session.getMapper(type)));
      context.register(
          ScannedInfrastructure.class,
          PayTccBridgeConfiguration.class,
          PayTccCancelTableInitializer.class);
      context.refresh();
      bridge = context.getBean(PayTccBridgeServiceImpl.class);
      persistence = context.getBean(PayTccDispatchPersistence.class);
      calls.clear();
    }

    /** 观察原Mapper的实际事务和连接，不替代SQL或修改返回事实。 */
    private Object observed(Class<?> type, Object target) {
      return Proxy.newProxyInstance(
          type.getClassLoader(),
          new Class<?>[] {type},
          (proxy, method, arguments) -> {
            if (method.getDeclaringClass() != Object.class) calls.add(method.getName());
            if (type == PayTccDispatchMapper.class
                && Set.of("insert", "markPostAttempted", "mirrorState")
                    .contains(method.getName())) {
              assertTrue(TransactionSynchronizationManager.isActualTransactionActive());
              assertFalse(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
              writes.add(
                  Map.of(
                      "method",
                      method.getName(),
                      "connection",
                      jdbc.queryForObject("SELECT CONNECTION_ID()", Long.class)));
            }
            try {
              return method.invoke(target, arguments);
            } catch (InvocationTargetException failure) {
              throw failure.getCause();
            }
          });
    }

    private String state() {
      return jdbc.queryForObject(
          "SELECT state FROM pay_tcc_dispatch WHERE pay_no='pay-1'", String.class);
    }

    private static String configured(String url) {
      return url + "?allowPublicKeyRetrieval=true&useSSL=false&serverTimezone=Asia/Shanghai";
    }

    /** 先归档所有持久事实；成功才删除精确UUID库，失败现场保留。 */
    @Override
    public void close() throws Exception {
      context.close();
      RootContext.unbind();
      if (originalXid != null) RootContext.bind(originalXid);
      assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
      Path evidence =
          Path.of(System.getProperty("basedir", "."), "target", "pay-dispatch-refactor", schema);
      Files.createDirectories(evidence);
      Map<String, Object> report = new LinkedHashMap<>();
      report.put("schema", schema);
      report.put("successful", successful);
      report.put("calls", calls);
      report.put("writes", writes);
      report.put("payments", jdbc.queryForList("SELECT * FROM pay_record"));
      report.put("dispatches", jdbc.queryForList("SELECT * FROM pay_tcc_dispatch"));
      report.put("cancellations", jdbc.queryForList("SELECT * FROM pay_tcc_cancel"));
      var json = new ObjectMapper().findAndRegisterModules();
      json.writeValue(evidence.resolve("before-cleanup.json").toFile(), report);
      if (!successful) {
        System.out.println("PAY_DISPATCH_PRESERVED=" + schema);
        return;
      }
      if (!schema.matches("pay_dispatch_refactor_[0-9a-f]{32}"))
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
