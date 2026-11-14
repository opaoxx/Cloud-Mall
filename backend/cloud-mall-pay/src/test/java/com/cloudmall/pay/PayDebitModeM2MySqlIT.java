package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.feign.OrderClient;
import com.cloudmall.pay.feign.UserClient;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.service.PayProtocolMetrics;
import com.cloudmall.pay.service.PaySuccessService;
import com.cloudmall.pay.service.impl.PaySuccessServiceImpl;
import com.cloudmall.pay.service.impl.PayWriteMonitor;
import com.cloudmall.pay.service.impl.callback.PayCallbackReceiptService;
import com.cloudmall.pay.service.impl.entry.PayTccPaymentService;
import com.cloudmall.pay.service.impl.legacy.PayConsistencyService;
import com.cloudmall.pay.service.impl.legacy.PayDebitIntentService;
import com.cloudmall.pay.service.impl.legacy.PayLegacyFailureService;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.zaxxer.hikari.HikariDataSource;
import io.seata.core.context.RootContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.math.BigDecimal;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.stream.Stream;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.parallel.Execution;
import org.junit.jupiter.api.parallel.ExecutionMode;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.support.AopUtils;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.EnableTransactionManagement;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** M2 有限准入族：真实 owned MySQL/Mapper/事务代理，业务依赖仅计数且绝不委派。 */
@Execution(ExecutionMode.SAME_THREAD)
class PayDebitModeM2MySqlIT {
  /** 本族只允许这两张实际 Pay 表。 */
  private static final Set<String> TABLES = Set.of("pay_record", "pay_debit_intent");

  /** 独立证据序列化器；所有时间和金额先转换为标准字符串。 */
  private static final ObjectMapper JSON = new ObjectMapper();

  /** 仅 R08 参数族执行一次接线 smoke，单独 NULL 方法不会启动子进程。 */
  private static boolean admissionSmokePassed;

  /** 固定九个负向变化，未指定属性始终保留 all-valid TCC 基准。 */
  private static Stream<Arguments> admissionCases() {
    return Stream.of(
        Arguments.of("ENTRY_OFF", "cloudmall.pay.tcc-entry.enabled", "false", "tcc-entry"),
        Arguments.of(
            "COORDINATOR_OFF", "cloudmall.pay.tcc-coordinator.enabled", "false", "tcc-coordinator"),
        Arguments.of("RECEIPT_OFF", "cloudmall.pay.tcc-receipt.enabled", "false", "tcc-receipt"),
        Arguments.of("BRIDGE_OFF", "cloudmall.pay.tcc-bridge.enabled", "false", "tcc-bridge"),
        Arguments.of("RECOVERY_OFF", "cloudmall.pay.tcc-recovery.enabled", "false", "tcc-recovery"),
        Arguments.of("DELIVERY_OFF", "cloudmall.pay.tcc-delivery.enabled", "false", "tcc-delivery"),
        Arguments.of("UNSUPPORTED_MODE", "cloudmall.pay.debit-mode", "unknown", "UNSUPPORTED_MODE"),
        Arguments.of("SEATA_OFF", "seata.enabled", "false", "SEATA_AT_ADMISSION"),
        Arguments.of(
            "AT_PROXY_ON", "seata.enable-auto-data-source-proxy", "true", "SEATA_AT_ADMISSION"));
  }

  @ParameterizedTest(name = "{0}")
  @MethodSource("admissionCases")
  void startupRejectsFrozenInvalidConfiguration(
      String caseId, String property, String value, String expectedGate) throws Exception {
    if (!admissionSmokePassed) {
      JsonNode smoke =
          runFork("VALID_TCC_WIRING_SMOKE", newArtifacts("VALID_TCC_WIRING_SMOKE"), null, null);
      assertTrue(smoke.path("ready").booleanValue(), "VALID_TCC_WIRING_MUST_START");
      assertEquals(0, smoke.path("processExit").intValue());
      assertEquals("TCC", smoke.path("newPaymentMode").textValue());
      assertEquals(JSON.valueToTree(baselineProperties()), smoke.path("effectiveProperties"));
      admissionSmokePassed = true;
    }
    JsonNode result = runFork(caseId, newArtifacts(caseId), null, Map.of(property, value));
    Map<String, String> expected = baselineProperties();
    expected.put(property, value);
    assertAll(
        () -> assertFalse(result.path("ready").booleanValue()),
        () -> assertNotEquals(0, result.path("processExit").intValue()),
        () -> assertEquals(expectedGate, result.path("failureGate").textValue()),
        () -> assertTrue(result.path("productionValidateCauseObserved").booleanValue()),
        () -> assertEquals(JSON.valueToTree(expected), result.path("effectiveProperties")),
        () -> assertEquals(0, result.path("businessCalls").intValue()),
        () -> assertFalse(result.path("databaseConfigured").booleanValue()));
  }

  @Test
  void persistedPaySqlNullIsRejectedBeforeAnyWrite() throws Exception {
    persisted("PERSISTED_PAY_SQL_NULL", null, "LEGACY");
  }

  @Test
  void persistedIntentSqlNullIsRejectedBeforeAnyWrite() throws Exception {
    persisted("PERSISTED_INTENT_SQL_NULL", "LEGACY", null);
  }

  @Test
  void mixedProtocolsAreRejectedBeforeAnyWrite() throws Exception {
    persisted("MIXED_PROTOCOL", "TCC", "LEGACY");
  }

  @Test
  void lowercaseProtocolIsRejectedBeforeAnyWrite() throws Exception {
    persisted("LOWERCASE_PROTOCOL", "legacy", "LEGACY");
  }

  @Test
  void trailingSpaceProtocolIsRejectedBeforeAnyWrite() throws Exception {
    persisted("WHITESPACE_PROTOCOL", "LEGACY", "LEGACY ");
  }

  @Test
  void legacyColumnsMigrateThroughOriginalInitializer() throws Exception {
    migration("LEGACY_COLUMNS_MIGRATE", false);
  }

  @Test
  void incompleteSchemaIsRejectedByOriginalInitializer() throws Exception {
    migration("INCOMPLETE_SCHEMA_REJECTED", true);
  }

  /** A 后事实仍完整相同才观察 B；先保存实际结果，不用预期拒绝覆盖真实返回。 */
  private static void persisted(String caseId, String payMode, String intentMode) throws Exception {
    Owned fixture = new Owned(caseId);
    AnnotationConfigApplicationContext context = null;
    Map<String, Object> before = null;
    Map<String, Object> last = null;
    boolean passed = false;
    boolean controlledOnly = false;
    Throwable primary = null;
    List<Map<String, Object>> paths = new ArrayList<>();
    try {
      fixture.create();
      fixture.prepare(
          true, true, true, payMode == null, intentMode == null, payMode, intentMode, false);
      before = facts(fixture.jdbc, fixture.schema);
      writeNew(fixture.artifacts.resolve("initial-facts.json"), before);
      assertInitial(fixture, before, true);
      assertEquals(
          payMode, fixture.jdbc.queryForObject("SELECT debit_mode FROM pay_record", String.class));
      assertEquals(
          intentMode,
          fixture.jdbc.queryForObject("SELECT debit_mode FROM pay_debit_intent", String.class));
      Map<String, Object> exactModes = modeFacts(fixture.jdbc);
      assertExactMode((Map<?, ?>) exactModes.get("pay_record"), payMode);
      assertExactMode((Map<?, ?>) exactModes.get("pay_debit_intent"), intentMode);
      writeNew(fixture.artifacts.resolve("mode-facts.json"), exactModes);
      context = new AnnotationConfigApplicationContext();
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "isolatedM2Routing", Map.of("cloudmall.pay.debit-mode", "LEGACY")));
      fixture.routingContextClosed = false;
      context.getEnvironment().setActiveProfiles("cm18-m2-routing");
      context.registerBean("dataSource", AuditDataSource.class, () -> fixture.dataSource);
      registerBusinessMocks(context);
      context.register(RoutingConfiguration.class);
      context.refresh();
      assertTrue(AopUtils.isAopProxy(context.getBean(PayDebitIntentService.class)));
      assertTrue(
          context.getBeansOfType(org.springframework.scheduling.TaskScheduler.class).isEmpty());
      controlledOnly =
          RootContext.getXID() == null
              && !TransactionSynchronizationManager.isActualTransactionActive();
      for (String path :
          List.of("PaySuccessServiceImpl.succeed", "PayConsistencyService.process")) {
        Map<String, Object> observation = observePath(context, fixture, path);
        paths.add(observation);
        last = facts(fixture.jdbc, fixture.schema);
        writeNew(
            fixture.artifacts.resolve("path-" + paths.size() + ".json"),
            Map.of("observation", observation, "factsBefore", before, "factsAfter", last));
        assertEquals(before, last, "PATH_CHANGED_ORIGINAL_FACTS_STOP_NEXT_PATH");
        assertTrue((Boolean) observation.get("rootContextAbsentAfter"), "UNEXPECTED_TC_CONTEXT");
        assertTrue(
            (Boolean) observation.get("localTransactionAbsentAfter"),
            "UNRESOLVED_LOCAL_TRANSACTION");
        assertTrue((Boolean) observation.get("authContextCleared"), "AUTH_CONTEXT_NOT_CLEARED");
      }
      passed =
          paths.size() == 2
              && paths.stream().allMatch(row -> Boolean.TRUE.equals(row.get("expectedGatePassed")));
      writeNew(
          fixture.artifacts.resolve("result.json"),
          result(
              caseId,
              "R09",
              Map.of(
                  "paths",
                  paths,
                  "expectedGatePassed",
                  passed,
                  "modeFacts",
                  modeFacts(fixture.jdbc),
                  "factsSha256",
                  digest(last),
                  "originalFacts",
                  artifactRef(fixture.artifacts, "initial-facts.json"),
                  "pathFacts",
                  List.of(
                      artifactRef(fixture.artifacts, "path-1.json"),
                      artifactRef(fixture.artifacts, "path-2.json")))));
      assertTrue(passed, "PERSISTED_PROTOCOL_MUST_REJECT_503_BEFORE_CLAIM_OR_WRITE");
    } catch (Throwable failure) {
      primary = failure;
      throw failure;
    } finally {
      Throwable closeFailure = null;
      try {
        if (context != null) context.close();
        fixture.routingContextClosed = context == null || !context.isActive();
      } catch (Throwable failure) {
        closeFailure = failure;
      }
      try {
        fixture.dataSource.close();
      } catch (Throwable failure) {
        if (closeFailure == null) closeFailure = failure;
      }
      if (closeFailure != null && primary != null)
        primary.addSuppressed(new IllegalStateException("OWNED_M2_CONTEXT_OR_POOL_CLOSE_FAILED"));
      fixture.finish(
          before,
          last,
          passed && closeFailure == null,
          controlledOnly && closeFailure == null,
          paths,
          primary);
      if (closeFailure != null && primary == null)
        throw new IllegalStateException("OWNED_M2_CONTEXT_OR_POOL_CLOSE_FAILED");
    }
  }

  /** 目标实现、真实 SQL 与异常都原样观察；这里只投影安全 code/计数，不补成功结果。 */
  private static Map<String, Object> observePath(
      AnnotationConfigApplicationContext context, Owned fixture, String path) {
    assertNull(RootContext.getXID());
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertFalse(AuthContext.authenticated());
    List<Object> dependencies = businessDependencies(context);
    dependencies.forEach(org.mockito.Mockito::clearInvocations);
    PayDebitIntentMapper intents = context.getBean(PayDebitIntentMapper.class);
    clearInvocations(intents);
    fixture.dataSource.events.clear();
    fixture.dataSource.checking = true;
    Map<String, Object> observation = new LinkedHashMap<>();
    observation.put("path", path);
    observation.put("expectedCode", "PAY_DEBIT_MODE_UNAVAILABLE");
    observation.put("expectedHttpStatus", 503);
    Throwable failure = null;
    Object returned = null;
    try {
      if (path.equals("PaySuccessServiceImpl.succeed")) {
        AuthContext.set(7L, "USER");
        returned =
            context
                .getBean(PaySuccessService.class)
                .succeed(fixture.payNo, AuthContext::requireUserId);
      } else returned = context.getBean(PayConsistencyService.class).process(fixture.payNo);
    } catch (Throwable actual) {
      failure = actual;
    } finally {
      fixture.dataSource.checking = false;
      AuthContext.clear();
    }
    long claims =
        mockingDetails(intents).getInvocations().stream()
            .filter(call -> call.getMethod().getName().equals("claim"))
            .count();
    long calls =
        dependencies.stream().mapToLong(mock -> mockingDetails(mock).getInvocations().size()).sum();
    observation.put("failure", safeError(failure));
    observation.put("returnedType", returned == null ? null : returned.getClass().getSimpleName());
    observation.put(
        "returnedState",
        returned instanceof String state
            ? Set.of(
                        "INIT",
                        "SUCCESS",
                        "FAILED",
                        "REFUNDED",
                        "ORDER_SYNC_UNKNOWN",
                        "REFUND_PENDING")
                    .contains(state)
                ? state
                : "UNRECOGNIZED_STATE"
            : null);
    observation.put(
        "actualCode", failure instanceof BizException business ? business.getCode() : null);
    observation.put(
        "actualHttpStatus",
        failure instanceof BizException business ? business.getHttpStatus() : null);
    observation.put("claimCalls", claims);
    observation.put("mockBusinessCalls", calls);
    observation.put("realDelegation", 0);
    observation.put("sql", new ArrayList<>(fixture.dataSource.events));
    observation.put("businessSqlWrites", fixture.dataSource.events.size());
    observation.put("rootContextAbsentAfter", RootContext.getXID() == null);
    observation.put(
        "localTransactionAbsentAfter",
        !TransactionSynchronizationManager.isActualTransactionActive());
    observation.put("authContextCleared", !AuthContext.authenticated());
    boolean expected =
        failure instanceof BizException business
            && "PAY_DEBIT_MODE_UNAVAILABLE".equals(business.getCode())
            && business.getHttpStatus() == 503
            && claims == 0
            && calls == 0
            && fixture.dataSource.events.isEmpty()
            && RootContext.getXID() == null
            && !TransactionSynchronizationManager.isActualTransactionActive();
    observation.put("expectedGatePassed", expected);
    if (RootContext.getXID() != null) RootContext.unbind();
    return observation;
  }

  /** 两条迁移都只由真实 Spring 生命周期执行生产 Initializer，不在测试重写迁移。 */
  private static void migration(String caseId, boolean incomplete) throws Exception {
    Owned fixture = new Owned(caseId);
    Map<String, Object> before = null;
    Map<String, Object> after = null;
    boolean passed = false;
    Throwable primary = null;
    try {
      fixture.create();
      fixture.prepare(!incomplete, true, false, false, false, null, null, true);
      before = facts(fixture.jdbc, fixture.schema);
      writeNew(fixture.artifacts.resolve("initial-facts.json"), before);
      assertInitial(fixture, before, !incomplete);
      assertEquals(
          1,
          fixture.jdbc.queryForObject(
              "SELECT COUNT(*) FROM pay_debit_intent WHERE next_retry_at>=?",
              Integer.class,
              LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusDays(1)));
      JsonNode child = runFork(caseId, fixture.artifacts, fixture, null);
      after = facts(fixture.jdbc, fixture.schema);
      writeNew(fixture.artifacts.resolve("post-fork-facts.json"), after);
      if (incomplete) {
        assertFalse(child.path("ready").booleanValue());
        assertNotEquals(0, child.path("processExit").intValue());
        assertTrue(child.path("productionMigrationCauseObserved").booleanValue());
        assertEquals("42S02", child.at("/failure/sqlState").textValue());
        assertEquals(1146, child.at("/failure/vendorCode").intValue());
        assertEquals(before, after, "FAILED_MIGRATION_MUST_KEEP_ORIGINAL_INTENT");
      } else {
        assertTrue(child.path("ready").booleanValue());
        assertEquals(0, child.path("processExit").intValue());
        assertEquals("LEGACY", child.path("mapperPayMode").textValue());
        assertEquals("LEGACY", child.path("mapperIntentMode").textValue());
        assertEquals(0, child.path("findDueCount").intValue());
        assertTrue(child.path("reentryFactsUnchanged").booleanValue());
        assertEquals(JSON.readTree(JSON.writeValueAsBytes(after)), child.path("factsAfterReentry"));
        assertLegacyMigration(before, after);
      }
      passed = true;
      writeNew(
          fixture.artifacts.resolve("result.json"),
          result(
              caseId,
              "R10",
              Map.of(
                  "expectedGatePassed",
                  true,
                  "beforeSha256",
                  digest(before),
                  "afterSha256",
                  digest(after),
                  "originalFacts",
                  artifactRef(fixture.artifacts, "initial-facts.json"),
                  "postForkFacts",
                  artifactRef(fixture.artifacts, "post-fork-facts.json"),
                  "forkReport",
                  artifactRef(fixture.artifacts, "fork-result.json"))));
    } catch (Throwable failure) {
      primary = failure;
      throw failure;
    } finally {
      boolean closed = false;
      try {
        fixture.dataSource.close();
        closed = fixture.dataSource.pool.isClosed();
      } catch (Throwable closeFailure) {
        if (primary != null)
          primary.addSuppressed(new IllegalStateException("OWNED_M2_POOL_CLOSE_FAILED"));
      } finally {
        fixture.finish(before, after, passed && closed, false, List.of(), primary);
      }
      if (!closed && primary == null) throw new IllegalStateException("OWNED_M2_POOL_CLOSE_FAILED");
    }
  }

  /** 原字段/索引完整保留，仅允许两表末尾新增固定 LEGACY 列。 */
  private static void assertLegacyMigration(Map<String, Object> before, Map<String, Object> after) {
    assertEquals(before.keySet(), after.keySet());
    for (String table : TABLES) {
      Map<?, ?> old = (Map<?, ?>) before.get(table);
      Map<?, ?> current = (Map<?, ?>) after.get(table);
      assertEquals(old.get("indexes"), current.get("indexes"));
      assertEquals(old.get("rowCount"), current.get("rowCount"));
      Map<String, Object> columns =
          new LinkedHashMap<>((Map<String, Object>) current.get("columns"));
      Map<?, ?> mode = (Map<?, ?>) columns.remove("debit_mode");
      assertNotNull(mode);
      assertEquals("varchar(16)", mode.get("COLUMN_TYPE"));
      assertEquals("NO", mode.get("IS_NULLABLE"));
      assertEquals("LEGACY", mode.get("COLUMN_DEFAULT"));
      assertEquals(old.get("columns"), columns);
      List<Map<String, Object>> rows = (List<Map<String, Object>>) current.get("rows");
      Map<String, Object> row = new LinkedHashMap<>(rows.get(0));
      assertEquals("LEGACY", row.remove("debit_mode"));
      assertEquals(((List<?>) old.get("rows")).get(0), row);
    }
  }

  /** 专属测试 profile 隔离普通 PayApplication 扫描；只这里开启本地事务代理，不注册调度器。 */
  @TestConfiguration(proxyBeanMethods = false)
  @Profile("cm18-m2-routing")
  @EnableTransactionManagement(proxyTargetClass = true)
  static class RoutingConfiguration {
    /** 此显式最小SQL测试容器无Boot registry，保持旧业务装配的无资源观察。 */
    @Bean
    PayProtocolMetrics protocolMetrics() {
      return PayProtocolMetrics.noop();
    }

    @Bean
    JdbcTemplate jdbcTemplate(DataSource source) {
      return jdbc(source);
    }

    @Bean
    PlatformTransactionManager transactionManager(DataSource source) {
      return new DataSourceTransactionManager(source);
    }

    @Bean
    PayRecordMapper records(DataSource source) throws Exception {
      return recordMapper(source);
    }

    @Bean
    PayDebitIntentMapper intents(JdbcTemplate jdbc) {
      return spy(new PayDebitIntentMapper(jdbc));
    }

    @Bean
    PayDebitIntentService intentService(PayDebitIntentMapper intents, PayRecordMapper records) {
      return new PayDebitIntentService(intents, records);
    }

    @Bean
    PayDebitModeConfiguration mode() {
      return new PayDebitModeConfiguration();
    }

    @Bean
    PayConsistencyService consistency(
        PayDebitIntentService intents,
        PayDebitIntentMapper mapper,
        PayRecordMapper records,
        UserClient users,
        OrderClient orders,
        PlatformTransactionManager manager) {
      return new PayConsistencyService(intents, mapper, records, users, orders, manager);
    }

    @Bean
    PaySuccessServiceImpl payments(
        PayRecordMapper records,
        PayDebitIntentMapper intents,
        PayConsistencyService consistency,
        org.springframework.beans.factory.ObjectProvider<PayTccPaymentService> tcc,
        PayProtocolMetrics metrics,
        PayWriteMonitor writeMonitor) {
      return new PaySuccessServiceImpl(records, intents, consistency, tcc, metrics, writeMonitor);
    }
  }

  /** 既有业务替身作为已完成单例注册，避免 Spring 对 mock 的原字段另做生产依赖注入。 */
  private static void registerBusinessMocks(AnnotationConfigApplicationContext context) {
    context
        .getBeanFactory()
        .registerSingleton("payWriteMonitor", new com.cloudmall.pay.service.impl.PayWriteMonitor());
    context.getBeanFactory().registerSingleton("users", mock(UserClient.class));
    context.getBeanFactory().registerSingleton("orders", mock(OrderClient.class));
    context.getBeanFactory().registerSingleton("tccPayment", mock(PayTccPaymentService.class));
    context.getBeanFactory().registerSingleton("callbackLog", mock(PayCallbackLogMapper.class));
    context
        .getBeanFactory()
        .registerSingleton("callbackReceipt", mock(PayCallbackReceiptService.class));
    context
        .getBeanFactory()
        .registerSingleton("legacyFailure", mock(PayLegacyFailureService.class));
  }

  private static List<Object> businessDependencies(AnnotationConfigApplicationContext context) {
    return List.of(
        context.getBean(UserClient.class),
        context.getBean(OrderClient.class),
        context.getBean(PayTccPaymentService.class),
        context.getBean(PayCallbackLogMapper.class),
        context.getBean(PayCallbackReceiptService.class),
        context.getBean(PayLegacyFailureService.class));
  }

  /** 真正创建 MyBatis Mapper；关闭会话缓存，查询直接读取实际持久值。 */
  static PayRecordMapper recordMapper(DataSource source) throws Exception {
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    configuration.setDefaultStatementTimeout(5);
    configuration.addMapper(PayRecordMapper.class);
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(source);
    factory.setConfiguration(configuration);
    return new SqlSessionTemplate(Objects.requireNonNull(factory.getObject()))
        .getMapper(PayRecordMapper.class);
  }

  /** 独立拥有一个 schema；任何未证明资格的现场一律保留并报告失败。 */
  private static final class Owned {
    /** 不含业务身份的固定 case。 */
    private final String caseId;

    /** 唯一本轮实际创建并登记的精确 schema。 */
    private final String schema = "cm16m2_" + UUID.randomUUID().toString().replace("-", "");

    /** 只用于内存 SQL 参数；输出只保留 SHA。 */
    private final String payNo = "m2-pay-" + UUID.randomUUID().toString().replace("-", "");

    /** 与原支付一一绑定的受控订单参数。 */
    private final String orderNo = "m2-order-" + UUID.randomUUID().toString().replace("-", "");

    /** 唯一本机管理连接，只允许精确本轮 schema DDL。 */
    private final JdbcTemplate admin;

    /** 自有连接和安全 SQL 计数；不拦截或伪造数据库返回。 */
    private final AuditDataSource dataSource;

    /** 自有 schema 直接事实查询模板。 */
    private final JdbcTemplate jdbc;

    /** CREATE_NEW 证据目录。 */
    private final Path artifacts;

    /** 原 URL 仅用于连接，不序列化到报告或参数文件。 */
    private final String url;

    /** 仅创建成功后赋予清理名单资格。 */
    private boolean created;

    /** 没有返回的业务配置也必须经过调用者 finally 关闭后才能获得清理资格。 */
    private boolean routingContextClosed = true;

    /** 迁移子进程实际创建后，必须明确 wait 完成；超时即使已停止也不授予正常清理。 */
    private boolean childStarted;

    /** 仅 parent wait 证实子进程正常结束时为 true。 */
    private boolean childStoppedAndWaited;

    private Owned(String caseId) throws Exception {
      this.caseId = caseId;
      artifacts = newArtifacts(caseId);
      String source = boundedUrl();
      admin = jdbc(new DriverManagerDataSource(source, "root", "root"));
      url = source.replace("/cloudmall_user?", "/" + schema + "?");
      dataSource = new AuditDataSource(pool(url), schema);
      jdbc = jdbc(dataSource);
    }

    /** 创建动作留在调用者 try/finally 内，即使初始 catalog 验证失败仍能记录保留现场。 */
    private void create() throws Exception {
      assertEquals(0, exists(admin, schema));
      admin.execute(
          "CREATE DATABASE " + schema + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      created = true;
      assertEquals(url, dataSource.pool.getJdbcUrl());
      assertEquals(schema, jdbc.queryForObject("SELECT DATABASE()", String.class));
      writeNew(
          artifacts.resolve("ownership.json"),
          Map.of(
              "schema",
              schema,
              "createdByThisCase",
              true,
              "actualCatalogVerified",
              true,
              "configuredCatalogVerified",
              true,
              "case",
              caseId));
    }

    private void prepare(
        boolean pay,
        boolean intent,
        boolean mode,
        boolean nullPay,
        boolean nullIntent,
        String payMode,
        String intentMode,
        boolean future) {
      assertEquals(
          0,
          jdbc.queryForObject(
              "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=DATABASE()",
              Integer.class));
      String common =
          "id BIGINT PRIMARY KEY,pay_no VARCHAR(64) NOT NULL UNIQUE,order_no VARCHAR(64) NOT NULL"
              + " UNIQUE,user_id BIGINT NOT NULL,amount DECIMAL(18,2) NOT NULL,status VARCHAR(32)"
              + " NOT NULL,";
      if (pay)
        jdbc.execute(
            "CREATE TABLE pay_record ("
                + common
                + (mode
                    ? "debit_mode VARCHAR(16) "
                        + (nullPay ? "NULL" : "NOT NULL")
                        + " DEFAULT 'LEGACY',"
                    : "")
                + "paid_at DATETIME(3),created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT"
                + " NULL)");
      if (intent)
        jdbc.execute(
            "CREATE TABLE pay_debit_intent ("
                + common
                + (mode
                    ? "debit_mode VARCHAR(16) "
                        + (nullIntent ? "NULL" : "NOT NULL")
                        + " DEFAULT 'LEGACY',"
                    : "")
                + "attempts INT NOT NULL DEFAULT 0,next_retry_at DATETIME(3) NOT NULL,lease_owner"
                + " VARCHAR(128),lease_until DATETIME(3),last_error VARCHAR(512),created_at"
                + " DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,KEY"
                + " idx_pay_debit_recovery(status,next_retry_at,lease_until),KEY"
                + " idx_pay_debit_order(order_no))");
      LocalDateTime fixed = LocalDateTime.of(2026, 10, 1, 8, 0);
      LocalDateTime retry =
          LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
              .plusDays(future ? 2 : -1)
              .truncatedTo(ChronoUnit.MILLIS);
      if (pay) {
        List<Object> values =
            new ArrayList<>(List.of(11L, payNo, orderNo, 7L, new BigDecimal("10.00"), "PENDING"));
        if (mode) values.add(payMode);
        values.add(null);
        values.add(fixed);
        values.add(fixed);
        jdbc.update(
            "INSERT INTO pay_record VALUES(" + "?,".repeat(values.size() - 1) + "?)",
            values.toArray());
      }
      if (intent) {
        List<Object> values =
            new ArrayList<>(List.of(12L, payNo, orderNo, 7L, new BigDecimal("10.00"), "INIT"));
        if (mode) values.add(intentMode);
        values.add(0);
        values.add(retry);
        values.add(null);
        values.add(null);
        values.add(null);
        values.add(fixed);
        values.add(fixed);
        jdbc.update(
            "INSERT INTO pay_debit_intent VALUES(" + "?,".repeat(values.size() - 1) + "?)",
            values.toArray());
      }
    }

    /** 先不可变保全真实事实；关池后用第二真实连接重核归属/完整事实，才精确删除。 */
    private void finish(
        Map<String, Object> before,
        Map<String, Object> last,
        boolean passed,
        boolean controlled,
        List<Map<String, Object>> paths,
        Throwable primary)
        throws Exception {
      Map<String, Object> audit =
          result(
              caseId,
              caseId.startsWith("PERSISTED") || caseId.endsWith("PROTOCOL") ? "R09" : "R10",
              Map.of());
      audit.put("schema", schema);
      audit.put("expectedGatePassed", passed);
      audit.put("controlledOnly", controlled);
      audit.put("realDelegation", 0);
      audit.put("paths", paths);
      audit.put("initialFacts", before);
      audit.put("lastObservedFacts", last);
      audit.put("primaryFailure", safeError(primary));
      audit.put("connectionPoolClosed", dataSource.pool.isClosed());
      audit.put("configuredUrlUnchanged", url.equals(dataSource.pool.getJdbcUrl()));
      audit.put("routingContextClosed", routingContextClosed);
      audit.put("childStarted", childStarted);
      audit.put("childStoppedAndWaited", childStoppedAndWaited);
      boolean absent = false;
      try {
        if (!created || !schema.matches("cm16m2_[0-9a-f]{32}"))
          throw new IllegalStateException("OWNERSHIP_NOT_PROVEN");
        JdbcTemplate second = jdbc(new DriverManagerDataSource(url, "root", "root"));
        assertEquals(schema, second.queryForObject("SELECT DATABASE()", String.class));
        Map<String, Object> current = facts(second, schema);
        audit.put("currentFacts", current);
        boolean same = last != null && last.equals(current);
        boolean controlledRed =
            !passed
                && controlled
                && !paths.isEmpty()
                && paths.stream()
                    .allMatch(
                        row ->
                            Boolean.TRUE.equals(row.get("rootContextAbsentAfter"))
                                && Boolean.TRUE.equals(row.get("localTransactionAbsentAfter"))
                                && Boolean.TRUE.equals(row.get("authContextCleared")))
                && controlledCleanupAllowed(before, current);
        boolean allowed =
            dataSource.pool.isClosed()
                && url.equals(dataSource.pool.getJdbcUrl())
                && routingContextClosed
                && (!childStarted || childStoppedAndWaited)
                && same
                && (passed || controlledRed);
        audit.put("factsMatchLastObservation", same);
        audit.put("controlledRedCleanupEligible", controlledRed);
        audit.put("cleanupEligible", allowed);
        writeNew(artifacts.resolve("final-facts.json"), audit);
        if (allowed) {
          admin.execute("DROP DATABASE " + schema);
          absent = exists(admin, schema) == 0;
          audit.put("cleanupStatus", absent ? "DROPPED_VERIFIED_OWNED" : "CLEANUP_FAILED");
        } else audit.put("cleanupStatus", "PRESERVED_UNPROVEN_CLEANUP");
      } catch (Exception | AssertionError failure) {
        audit.put("cleanupStatus", "CLEANUP_FAILED");
        audit.put("cleanupFailure", safeError(failure));
      } finally {
        audit.put("allOwnedSchemasAbsentVerified", absent);
        try {
          writeNew(artifacts.resolve("cleanup-observation.json"), audit);
        } catch (Exception auditFailure) {
          IllegalStateException safe =
              new IllegalStateException("OWNED_M2_CLEANUP_AUDIT_UNAVAILABLE");
          if (primary != null) primary.addSuppressed(safe);
          else throw safe;
        }
      }
      if (!absent) {
        AssertionError cleanup = new AssertionError("OWNED_M2_FIXTURE_PRESERVED_OR_CLEANUP_FAILED");
        if (primary != null) primary.addSuppressed(cleanup);
        else throw cleanup;
      }
    }
  }

  /** 只容许明示的受控本地 claim/retry 字段；任何未知 tuple/表/行或资金状态均保留。 */
  private static boolean controlledCleanupAllowed(
      Map<String, Object> before, Map<String, Object> current) {
    if (before == null || !before.keySet().equals(TABLES) || !current.keySet().equals(TABLES))
      return false;
    for (String table : TABLES) {
      Map<?, ?> old = (Map<?, ?>) before.get(table);
      Map<?, ?> now = (Map<?, ?>) current.get(table);
      if (!Objects.equals(old.get("columns"), now.get("columns"))
          || !Objects.equals(old.get("indexes"), now.get("indexes"))
          || !Long.valueOf(1).equals(now.get("rowCount"))) return false;
      Map<String, Object> originalRow =
          new TreeMap<>((Map<String, Object>) ((List<?>) old.get("rows")).get(0));
      Map<String, Object> currentRow =
          new TreeMap<>((Map<String, Object>) ((List<?>) now.get("rows")).get(0));
      if (table.equals("pay_debit_intent")) {
        if (!"INIT".equals(currentRow.get("status"))) return false;
        for (String field :
            List.of(
                "attempts",
                "next_retry_at",
                "lease_owner",
                "lease_until",
                "last_error",
                "updated_at")) {
          originalRow.remove(field);
          currentRow.remove(field);
        }
      }
      if (!originalRow.equals(currentRow)) return false;
    }
    return true;
  }

  /** 对实际 DataSource 的透明计数，只记录写尝试/影响数/SQL 摘要，绝不替换结果。 */
  static final class AuditDataSource extends AbstractDataSource implements AutoCloseable {
    /** 本 case 最大两条连接，结束必须关闭。 */
    private final HikariDataSource pool;

    /** 真实 catalog 必须等于此精确 owned schema。 */
    private final String schema;

    /** 只在目标 Service 调用窗口计数。 */
    private boolean checking;

    /** 每条实际 DML 尝试，包括零影响行，均保留安全事实。 */
    private final List<Map<String, Object>> events = new ArrayList<>();

    private AuditDataSource(HikariDataSource pool, String schema) {
      this.pool = pool;
      this.schema = schema;
    }

    /** 保持真实 Hikari 连接和异常，只包查询超时/业务写观察。 */
    @Override
    public Connection getConnection() throws SQLException {
      return wrap(pool.getConnection());
    }

    /** 不允许变更本地固定身份。 */
    @Override
    public Connection getConnection(String user, String password) throws SQLException {
      throw new SQLException("FIXED_CREDENTIALS_REQUIRED");
    }

    private Connection wrap(Connection connection) throws SQLException {
      if (!schema.equals(connection.getCatalog())) {
        connection.close();
        throw new SQLException("OWNED_CATALOG_CHANGED");
      }
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, args) -> {
                Object returned = call(connection, method, args);
                if (returned instanceof Statement statement
                    && Set.of("prepareStatement", "createStatement").contains(method.getName())) {
                  statement.setQueryTimeout(5);
                  Class<?> type =
                      returned instanceof java.sql.PreparedStatement
                          ? java.sql.PreparedStatement.class
                          : Statement.class;
                  String prepared =
                      args != null && args.length > 0 && args[0] instanceof String sql ? sql : null;
                  return Proxy.newProxyInstance(
                      Statement.class.getClassLoader(),
                      new Class<?>[] {type},
                      (ignored, action, values) -> {
                        String sql =
                            prepared != null
                                ? prepared
                                : values != null
                                        && values.length > 0
                                        && values[0] instanceof String text
                                    ? text
                                    : "";
                        boolean write =
                            checking
                                && action.getName().startsWith("execute")
                                && !sql.stripLeading()
                                    .toUpperCase(java.util.Locale.ROOT)
                                    .startsWith("SELECT");
                        Map<String, Object> event = new LinkedHashMap<>();
                        if (write) {
                          event.put("sqlSha256", sha(sql.getBytes(StandardCharsets.UTF_8)));
                          event.put("transactionActive", !connection.getAutoCommit());
                          event.put("affectedRows", null);
                          events.add(event);
                        }
                        Object value = call(statement, action, values);
                        if (write)
                          event.put(
                              "affectedRows",
                              value instanceof Integer ? value : statement.getUpdateCount());
                        return value;
                      });
                }
                return returned;
              });
    }

    /** 关闭真实连接池；不触碰其他进程或连接。 */
    @Override
    public void close() {
      pool.close();
    }
  }

  private static Object call(Object target, Method method, Object[] arguments) throws Throwable {
    try {
      return method.invoke(target, arguments);
    } catch (InvocationTargetException failure) {
      throw failure.getCause();
    }
  }

  /** 完整两表结构、固定索引、全部行和原 checksum；未知表拒绝并保留。 */
  static Map<String, Object> facts(JdbcTemplate jdbc, String schema) {
    if (!schema.matches("cm16m2_[0-9a-f]{32}")
        || !schema.equals(jdbc.queryForObject("SELECT DATABASE()", String.class)))
      throw new IllegalStateException("OWNED_CATALOG_CHANGED");
    List<String> tables =
        jdbc.queryForList(
            "SELECT table_name FROM information_schema.tables WHERE table_schema=DATABASE() ORDER"
                + " BY table_name",
            String.class);
    if (!TABLES.containsAll(tables)) throw new IllegalStateException("UNKNOWN_OWNED_TABLE");
    Map<String, Object> result = new TreeMap<>();
    for (String table : tables) {
      Map<String, Object> columns = new LinkedHashMap<>();
      for (Map<String, Object> row :
          jdbc.queryForList(
              "SELECT"
                  + " COLUMN_NAME,ORDINAL_POSITION,COLUMN_TYPE,IS_NULLABLE,COLUMN_DEFAULT,EXTRA,DATETIME_PRECISION,NUMERIC_PRECISION,NUMERIC_SCALE,CHARACTER_SET_NAME,COLLATION_NAME"
                  + " FROM information_schema.columns WHERE table_schema=DATABASE() AND"
                  + " table_name=? ORDER BY ORDINAL_POSITION",
              table)) columns.put(row.get("COLUMN_NAME").toString(), normalize(row));
      List<Map<String, Object>> rows =
          jdbc.queryForList("SELECT * FROM " + table + " ORDER BY id LIMIT 3");
      Long count = jdbc.queryForObject("SELECT COUNT(*) FROM " + table, Long.class);
      if (count == null || count > 2) throw new IllegalStateException("UNKNOWN_OWNED_ROWS");
      Object checksum = jdbc.queryForMap("CHECKSUM TABLE " + schema + "." + table).get("Checksum");
      if (!(checksum instanceof Number)) throw new IllegalStateException("CHECKSUM_UNAVAILABLE");
      result.put(
          table,
          Map.of(
              "columns",
              columns,
              "indexes",
              normalize(
                  jdbc.queryForList(
                      "SELECT"
                          + " INDEX_NAME,NON_UNIQUE,SEQ_IN_INDEX,COLUMN_NAME,SUB_PART,INDEX_TYPE,COLLATION"
                          + " FROM information_schema.statistics WHERE table_schema=DATABASE() AND"
                          + " table_name=? ORDER BY INDEX_NAME,SEQ_IN_INDEX",
                      table)),
              "rowCount",
              count,
              "rows",
              normalize(rows),
              "checksum",
              ((Number) checksum).longValue()));
    }
    return result;
  }

  /** 真实 SQL NULL、HEX、字节长度直接读取，保留大小写和尾空格。 */
  private static Map<String, Object> modeFacts(JdbcTemplate jdbc) {
    Map<String, Object> modes = new TreeMap<>();
    for (String table : TABLES)
      modes.put(
          table,
          normalize(
              jdbc.queryForMap(
                  "SELECT debit_mode,debit_mode IS NULL AS sqlNull,HEX(debit_mode) AS"
                      + " modeHex,OCTET_LENGTH(debit_mode) AS byteLength FROM "
                      + table)));
    return modes;
  }

  /** 不经兼容函数或数据库排序规则，独立核对 SQL 原字节和长度。 */
  private static void assertExactMode(Map<?, ?> actual, String expected) {
    assertEquals(expected, actual.get("debit_mode"));
    assertEquals(
        expected == null
            ? null
            : HexFormat.of().withUpperCase().formatHex(expected.getBytes(StandardCharsets.UTF_8)),
        actual.get("modeHex"));
    assertEquals(
        expected == null
            ? null
            : Integer.toString(expected.getBytes(StandardCharsets.UTF_8).length),
        actual.get("byteLength") == null ? null : actual.get("byteLength").toString());
    Object nullFlag = actual.get("sqlNull");
    assertTrue(nullFlag instanceof Boolean || nullFlag instanceof Number);
    if (nullFlag instanceof Number number)
      assertEquals(expected == null ? 1L : 0L, number.longValue());
    else assertEquals(expected == null, nullFlag);
  }

  private static void assertInitial(Owned fixture, Map<String, Object> facts, boolean hasPay) {
    assertEquals(hasPay ? TABLES : Set.of("pay_debit_intent"), facts.keySet());
    for (String table : facts.keySet()) {
      assertEquals(1L, ((Map<?, ?>) facts.get(table)).get("rowCount"));
      assertEquals(
          1,
          fixture.jdbc.queryForObject(
              "SELECT COUNT(*) FROM "
                  + table
                  + " WHERE id=? AND BINARY pay_no=BINARY ? AND BINARY order_no=BINARY ? AND"
                  + " user_id=7 AND amount=10.00 AND status=?",
              Integer.class,
              table.equals("pay_record") ? 11L : 12L,
              fixture.payNo,
              fixture.orderNo,
              table.equals("pay_record") ? "PENDING" : "INIT"));
    }
    assertEquals(
        1,
        fixture.jdbc.queryForObject(
            "SELECT COUNT(*) FROM pay_debit_intent WHERE attempts=0 AND lease_owner IS NULL AND"
                + " lease_until IS NULL AND last_error IS NULL",
            Integer.class));
    if (hasPay)
      assertEquals(
          1,
          fixture.jdbc.queryForObject(
              "SELECT COUNT(*) FROM pay_record WHERE paid_at IS NULL", Integer.class));
  }

  /** 只投影受控键 SHA，保留原金额字符串、mode 与全部阶段字段。 */
  private static Object normalize(Object value) {
    if (value instanceof Map<?, ?> map) {
      Map<String, Object> safe = new TreeMap<>();
      map.forEach(
          (key, item) -> {
            String name = key.toString();
            safe.put(
                name,
                item != null
                        && Set.of("pay_no", "order_no", "lease_owner", "last_error").contains(name)
                    ? sha(item.toString().getBytes(StandardCharsets.UTF_8))
                    : normalize(item));
          });
      return safe;
    }
    if (value instanceof List<?> list)
      return list.stream().map(PayDebitModeM2MySqlIT::normalize).toList();
    if (value instanceof BigDecimal amount) return amount.toPlainString();
    if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime().toString();
    if (value instanceof LocalDateTime time) return time.toString();
    if (value instanceof byte[] bytes) return HexFormat.of().formatHex(bytes);
    return value;
  }

  static JdbcTemplate jdbc(DataSource source) {
    JdbcTemplate jdbc = new JdbcTemplate(source);
    jdbc.setQueryTimeout(5);
    return jdbc;
  }

  static HikariDataSource pool(String url) {
    HikariDataSource pool = new HikariDataSource();
    pool.setJdbcUrl(url);
    pool.setUsername("root");
    pool.setPassword("root");
    pool.setMaximumPoolSize(2);
    pool.setMinimumIdle(0);
    pool.setConnectionTimeout(3000);
    pool.setValidationTimeout(1000);
    pool.setInitializationFailTimeout(3000);
    return pool;
  }

  /** 只接受显式本机13307既有门，固定私有连接边界，不变更共享配置。 */
  private static String boundedUrl() {
    String value = System.getProperty("cloudmall.mysql.test.url");
    if (value == null || !value.startsWith("jdbc:mysql://"))
      throw new IllegalStateException("EXPLICIT_LOCAL_MYSQL_IT_URL_REQUIRED");
    URI uri = URI.create(value.substring(5));
    if (!Set.of("localhost", "127.0.0.1").contains(uri.getHost())
        || uri.getPort() != 13307
        || !"/cloudmall_user".equals(uri.getRawPath())
        || uri.getUserInfo() != null
        || uri.getFragment() != null)
      throw new IllegalArgumentException("UNSUPPORTED_MYSQL_IT_TARGET");
    Map<String, String> options = new LinkedHashMap<>();
    if (uri.getRawQuery() != null)
      for (String pair : uri.getRawQuery().split("&")) {
        String[] pieces = pair.split("=", -1);
        if (pieces.length != 2
            || !Set.of(
                    "useSSL",
                    "allowPublicKeyRetrieval",
                    "serverTimezone",
                    "connectTimeout",
                    "socketTimeout")
                .contains(pieces[0])
            || options.putIfAbsent(pieces[0], pieces[1]) != null)
          throw new IllegalArgumentException("UNSAFE_MYSQL_IT_OPTIONS");
      }
    options.put("connectTimeout", "3000");
    options.put("socketTimeout", "5000");
    options.put("serverTimezone", "Asia/Shanghai");
    return value.substring(0, value.indexOf('?') >= 0 ? value.indexOf('?') : value.length())
        + "?"
        + options.entrySet().stream()
            .map(row -> row.getKey() + "=" + row.getValue())
            .collect(java.util.stream.Collectors.joining("&"));
  }

  private static int exists(JdbcTemplate jdbc, String schema) {
    return jdbc.queryForObject(
        "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
        Integer.class,
        schema);
  }

  static Map<String, String> baselineProperties() {
    Map<String, String> properties = new TreeMap<>();
    properties.put("cloudmall.pay.debit-mode", "TCC");
    for (String feature :
        List.of("entry", "coordinator", "receipt", "bridge", "recovery", "delivery"))
      properties.put("cloudmall.pay.tcc-" + feature + ".enabled", "true");
    properties.put("seata.enabled", "true");
    properties.put("seata.enable-auto-data-source-proxy", "false");
    return properties;
  }

  /** 标准 argv/Java参数文件，只追踪并停止本次实际创建的一个 PID。 */
  private static JsonNode runFork(
      String caseId, Path artifacts, Owned fixture, Map<String, String> change) throws Exception {
    List<String> arguments =
        new ArrayList<>(
            List.of(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Xmx192m",
                "-Duser.timezone=Asia/Shanghai",
                "-Dfile.encoding=UTF-8",
                "-cp",
                System.getProperty(
                    "surefire.test.class.path", System.getProperty("java.class.path")),
                PayDebitModeM2ForkProcess.class.getName(),
                "--m2.case=" + caseId,
                "--m2.report=" + artifacts.resolve("fork-result.json")));
    Map<String, String> properties = baselineProperties();
    if (fixture != null) {
      properties.put("cloudmall.pay.debit-mode", "LEGACY");
      arguments.add("--m2.schema=" + fixture.schema);
    }
    if (change != null) properties.putAll(change);
    properties.forEach((key, value) -> arguments.add("--" + key + "=" + value));
    Path args = artifacts.resolve("process.args");
    Files.writeString(
        args,
        arguments.stream()
            .map(value -> "\"" + value.replace("\\", "/").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(System.lineSeparator())),
        StandardOpenOption.CREATE_NEW);
    ProcessBuilder builder =
        new ProcessBuilder(
            Path.of(
                    System.getProperty("java.home"),
                    "bin",
                    System.getProperty("os.name").startsWith("Windows") ? "java.exe" : "java")
                .toString(),
            "@" + args);
    if (fixture != null) builder.environment().put("CM18_M2_JDBC_URL", fixture.url);
    builder.redirectErrorStream(true).redirectOutput(artifacts.resolve("process.log").toFile());
    Process process = builder.start();
    if (fixture != null) fixture.childStarted = true;
    Map<String, Object> lifecycle = new LinkedHashMap<>();
    lifecycle.put("pid", process.pid());
    boolean completed = false;
    try {
      lifecycle.put("startedAt", process.info().startInstant().orElseThrow().toString());
      writeNew(artifacts.resolve("process-started.json"), lifecycle);
      completed = process.waitFor(25, TimeUnit.SECONDS);
    } finally {
      if (process.isAlive()) {
        process.destroy();
        if (!process.waitFor(2, TimeUnit.SECONDS)) {
          process.destroyForcibly();
          process.waitFor(3, TimeUnit.SECONDS);
        }
      }
      lifecycle.put("timedOut", !completed);
      lifecycle.put("stoppedAndWaited", !process.isAlive());
      if (fixture != null) fixture.childStoppedAndWaited = completed && !process.isAlive();
      lifecycle.put("endedAt", OffsetDateTime.now().toString());
      if (!process.isAlive()) lifecycle.put("exitCode", process.exitValue());
      writeNew(artifacts.resolve("process-ended.json"), lifecycle);
    }
    assertTrue(completed && !process.isAlive(), "OWNED_FORK_TIMEOUT_OR_UNRESOLVED");
    Path report = artifacts.resolve("fork-result.json");
    assertTrue(
        Files.isRegularFile(report) && Files.size(report) <= 1024 * 1024,
        "FORK_SAFE_REPORT_REQUIRED");
    JsonNode result = JSON.readTree(Files.readAllBytes(report));
    ((com.fasterxml.jackson.databind.node.ObjectNode) result)
        .put("processExit", process.exitValue());
    writeNew(
        artifacts.resolve("fork-observation.json"),
        Map.of(
            "case",
            caseId,
            "lifecycle",
            lifecycle,
            "reportSha256",
            sha(Files.readAllBytes(report)),
            "result",
            result));
    return result;
  }

  private static Path newArtifacts(String caseId) throws Exception {
    Path path =
        Path.of("target", "cm16m2", caseId + "-" + UUID.randomUUID().toString().replace("-", ""))
            .toAbsolutePath();
    Files.createDirectories(path);
    return path;
  }

  static Map<String, Object> result(String caseId, String item, Map<String, Object> fields)
      throws Exception {
    Map<String, Object> report = new LinkedHashMap<>();
    report.put("case", caseId);
    report.put("item", item);
    report.put("scope", "ISOLATED_M2_ADMISSION_OR_MIGRATION");
    report.put("fixtureRole", "CONTROLLED_BUSINESS_DEPENDENCIES_NO_REMOTE_DELEGATION");
    if (!item.equals("R09"))
      report.put("fixtureRole", "ISOLATED_PRODUCTION_COMPONENTS_NO_BUSINESS_BEANS");
    report.put("automaticActionsAllowed", false);
    report.put("owner", "PAY");
    report.put("nextEvidenceSource", "ORIGINAL_IMMUTABLE_CASE_ARTIFACTS");
    report.put("retention", "NO_AUTOMATIC_EXPIRY");
    report.put("minimumRetentionDays", 30);
    report.put("classSha256", classSha(PayDebitModeM2MySqlIT.class));
    report.put("productionModeClassSha256", classSha(PayDebitModeConfiguration.class));
    report.put("productionIntentMapperClassSha256", classSha(PayDebitIntentMapper.class));
    report.put(
        "productionInitializerClassSha256",
        classSha(com.cloudmall.pay.config.PayDebitIntentTableInitializer.class));
    report.put("productionRecordMapperClassSha256", classSha(PayRecordMapper.class));
    report.put("productionPayServiceClassSha256", classSha(PaySuccessServiceImpl.class));
    report.put("productionConsistencyClassSha256", classSha(PayConsistencyService.class));
    report.put("productionIntentServiceClassSha256", classSha(PayDebitIntentService.class));
    report.put("seataRootContextClassSha256", classSha(RootContext.class));
    report.put("testSourceSha256", testSourceSha("PayDebitModeM2MySqlIT"));
    report.put("forkSourceSha256", testSourceSha("PayDebitModeM2ForkProcess"));
    report.put("createdAt", OffsetDateTime.now().toString());
    report.putAll(fields);
    return report;
  }

  static Map<String, Object> safeError(Throwable failure) {
    Map<String, Object> safe = new LinkedHashMap<>();
    if (failure == null) return safe;
    safe.put("errorType", failure.getClass().getSimpleName());
    Throwable cause = failure;
    for (int depth = 0; cause != null && depth < 20; depth++, cause = cause.getCause())
      if (cause instanceof SQLException sql) {
        safe.put("sqlState", sql.getSQLState());
        safe.put("vendorCode", sql.getErrorCode());
        break;
      }
    return safe;
  }

  /** 只引用本 case 已封存的固定 JSON 文件，不发布连接参数或原日志正文。 */
  private static Map<String, Object> artifactRef(Path artifacts, String name) throws Exception {
    return Map.of("relativePath", name, "sha256", sha(Files.readAllBytes(artifacts.resolve(name))));
  }

  /** 只读标准 Maven/IDE 项目路径，不依赖 Agent 的绝对目录。 */
  private static String testSourceSha(String name) throws Exception {
    Path module = Path.of("src", "test", "java", "com", "cloudmall", "pay", name + ".java");
    Path file =
        Files.isRegularFile(module) ? module : Path.of("backend", "cloud-mall-pay").resolve(module);
    if (!Files.isRegularFile(file))
      throw new IllegalStateException("TEST_SOURCE_IDENTITY_UNAVAILABLE");
    return sha(Files.readAllBytes(file));
  }

  static String classSha(Class<?> type) throws Exception {
    try (var resource =
        type.getResourceAsStream("/" + type.getName().replace('.', '/') + ".class")) {
      if (resource == null) throw new IllegalStateException("CLASS_IDENTITY_UNAVAILABLE");
      return sha(resource.readAllBytes());
    }
  }

  static String digest(Object value) throws Exception {
    return sha(JSON.writeValueAsBytes(value));
  }

  static String sha(byte[] bytes) {
    try {
      return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException failure) {
      throw new IllegalStateException(failure);
    }
  }

  static void writeNew(Path path, Object value) throws Exception {
    Files.write(
        path,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsBytes(value),
        StandardOpenOption.CREATE_NEW);
  }
}
