package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.config.FeignAuthConfiguration;
import com.cloudmall.pay.controller.PayTccUserFrozenOwnershipEvidenceController;
import com.cloudmall.pay.domain.vo.PayTccUserFrozenOwnershipReconciliationVO;
import com.cloudmall.pay.feign.UserFrozenOwnershipEvidenceClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.PayTccReconciliationService;
import com.cloudmall.pay.service.impl.PayTccUserFrozenOwnershipEvidenceService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import io.seata.core.context.RootContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Path;
import java.sql.Connection;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 两个owned库及真实User GET/Feign验证；只监听随机loopback端口，finally精确清理。 */
class PayTccUserFrozenOwnershipEvidenceMySqlIT {
  /** 原库只读指纹和owned schema管理连接。 */
  private static JdbcTemplate source;

  /** 原库表清单和校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 仅本测试创建的数据库。 */
  private static String database;

  /** owned库直接准备/核验入口，不与被测事务混用。 */
  private static JdbcTemplate fixture;

  /** owned库基础数据源。 */
  private static DataSource dataSource;

  /** 被测只读事务数据源，记录连接/SQL并拒绝写语句。 */
  private static TrackingDataSource tracked;

  /** 真实MyBatis模板。 */
  private static SqlSessionTemplate session;

  /** 默认门已启用的测试入口。 */
  private static PayTccReconciliationService service;

  /** 真实RR代理。 */
  private static PayTccReconciliationReadService reader;

  /** Inspector涉及的六张表。 */
  private static final List<String> TABLES =
      List.of(
          "pay_record",
          "pay_tcc_execution",
          "pay_tcc_dispatch",
          "pay_tcc_receipt",
          "tcc_fence_log",
          "pay_debit_intent");

  /** 原User库只读指纹和owned库。 */
  private static JdbcTemplate userSource, userFixture;

  /** 原User库所有表与checksum。 */
  private static List<Map<String, Object>> userFingerprint;

  /** 唯一owned User数据库名。 */
  private static String userDatabase;

  /** 实际User类、Mapper、事务代理和HTTP边界。 */
  private static URLClassLoader userClasses;

  /** 真实User SQL只读RR监测数据源。 */
  private static TrackingDataSource userTracked;

  /** 真实User Mapper模板。 */
  private static SqlSessionTemplate userSession;

  /** 实际User16T9 Controller与ReadService HTTP边界。 */
  private static MockMvc userMvc;

  /** 唯一随机loopback测试监听。 */
  private static HttpServer server;

  /** owned HTTP执行器，结束shutdown。 */
  private static ExecutorService serverExecutor;

  /** 实际原Pay anchor-only采集编排器。 */
  private static PayTccUserFrozenOwnershipEvidenceService collector;

  /** HTTP请求计数、测试故障和SQL断言传播。 */
  private static final AtomicInteger getRequests = new AtomicInteger(),
      writeRequests = new AtomicInteger();

  private static final AtomicReference<Runnable> afterUserRead = new AtomicReference<>();
  private static final AtomicReference<Throwable> transportAssertion = new AtomicReference<>();
  private static volatile boolean remoteUnavailable;

  /** owned User第一条读取之后的独立writer，只写owned数据源。 */
  private static final AtomicReference<Runnable> afterUserReceive = new AtomicReference<>();

  /** 真实线上User身份头，检验全局Feign拦截器不会污染持久锚点。 */
  private static final AtomicReference<List<String>> receivedCallerIds = new AtomicReference<>();

  /** 固定实例认证头与无XID透传观察。 */
  private static final AtomicReference<String> receivedService = new AtomicReference<>(),
      receivedToken = new AtomicReference<>(),
      receivedXid = new AtomicReference<>();

  /** 调用方角色不能混入内部User查询。 */
  private static final AtomicReference<String> receivedCallerRole = new AtomicReference<>();

  /** User九次查询涉及六张表，包含账户资金投影。 */
  private static final List<String> USER_TABLES =
      List.of(
          "mall_user",
          "user_tcc_receive",
          "balance_debit_attempt",
          "balance_tcc_debit",
          "balance_ledger",
          "tcc_fence_log");

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_pay URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    sourceFingerprint = originalFingerprint();
    setupUser(url);
    database = "cm16t26_pay_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    dataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_pay", "/" + database)), "root", "root");
    fixture = new JdbcTemplate(dataSource);
    for (String table : List.of("pay_record", "pay_debit_intent"))
      fixture.execute(
          (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
    tracked = new TrackingDataSource(dataSource);
    MybatisConfiguration configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    for (Class<?> mapper :
        List.of(
            PayRecordMapper.class,
            PayTccExecutionMapper.class,
            PayTccDispatchMapper.class,
            PayTccReceiptMapper.class,
            PayTccFenceMapper.class)) configuration.addMapper(mapper);
    MybatisSqlSessionFactoryBean factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(tracked);
    factory.setConfiguration(configuration);
    session = new SqlSessionTemplate(factory.getObject());
    PayRecordMapper payments = session.getMapper(PayRecordMapper.class);
    var target =
        new PayTccReconciliationReadService(
            payments,
            session.getMapper(PayTccExecutionMapper.class),
            session.getMapper(PayTccDispatchMapper.class),
            session.getMapper(PayTccReceiptMapper.class),
            session.getMapper(PayTccFenceMapper.class),
            new PayDebitIntentMapper(new JdbcTemplate(tracked)));
    ProxyFactory proxy = new ProxyFactory(target);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(tracked), new AnnotationTransactionAttributeSource()));
    reader = (PayTccReconciliationReadService) proxy.getProxy();
    service = new PayTccReconciliationService(reader, true, "ops-secret");
    ensureFixtureTables();
    fixture.execute(
        "ALTER TABLE pay_tcc_execution ADD recovery_attempts INT DEFAULT 0,ADD next_recovery_at"
            + " DATETIME(3),ADD recovery_owner VARCHAR(128),ADD recovery_lease_until"
            + " DATETIME(3),ADD recovery_last_error VARCHAR(512)");
    configureTransport();
  }

  /** 标准Maven sibling classes加载真实User实现，避免依赖Boot可执行jar。 */
  private static void setupUser(String url) throws Exception {
    Path userOutput = Path.of("../cloud-mall-user/target/classes").toAbsolutePath().normalize();
    if (!java.nio.file.Files.isDirectory(userOutput))
      throw new IllegalStateException("先通过Maven构建cloud-mall-user标准classes输出");
    userClasses =
        new URLClassLoader(
            new java.net.URL[] {userOutput.toUri().toURL()},
            PayTccUserFrozenOwnershipEvidenceMySqlIT.class.getClassLoader());
    userSource =
        new JdbcTemplate(
            new DriverManagerDataSource(
                shanghai(url.replace("/cloudmall_pay", "/cloudmall_user")), "root", "root"));
    userFingerprint = fingerprint(userSource, "cloudmall_user");
    userDatabase = "cm16t26_user_" + UUID.randomUUID().toString().replace("-", "");
    userSource.execute(
        "CREATE DATABASE " + userDatabase + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    var userDataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_pay", "/" + userDatabase)), "root", "root");
    userFixture = new JdbcTemplate(userDataSource);
    userFixture.execute(
        (String) userSource.queryForMap("SHOW CREATE TABLE mall_user").get("Create Table"));
    userTracked = new TrackingDataSource(userDataSource);
    var configuration = new MybatisConfiguration();
    configuration.setMapUnderscoreToCamelCase(true);
    configuration.setLocalCacheScope(LocalCacheScope.STATEMENT);
    List<Class<?>> mappers = new ArrayList<>();
    for (String name :
        List.of(
            "UserTccReceiveMapper",
            "BalanceDebitAttemptMapper",
            "BalanceTccDebitMapper",
            "BalanceLedgerMapper",
            "TccFenceLogMapper",
            "UserMapper")) {
      Class<?> mapper = userClasses.loadClass("com.cloudmall.user.mapper." + name);
      configuration.addMapper(mapper);
      mappers.add(mapper);
    }
    var factory = new MybatisSqlSessionFactoryBean();
    factory.setDataSource(userTracked);
    factory.setConfiguration(configuration);
    userSession = new SqlSessionTemplate(factory.getObject());
    Object[] instances = new Object[mappers.size()];
    for (int index = 0; index < mappers.size(); index++) {
      instances[index] = userSession.getMapper(mappers.get(index));
      if (!mappers.get(index).getSimpleName().equals("UserMapper"))
        mappers.get(index).getMethod("ensureTable").invoke(instances[index]);
    }
    Object actualReceive = instances[0];
    instances[0] =
        Proxy.newProxyInstance(
            userClasses,
            new Class<?>[] {mappers.get(0)},
            (receiver, method, arguments) -> {
              Object result;
              try {
                result = method.invoke(actualReceive, arguments);
              } catch (InvocationTargetException failure) {
                throw failure.getCause();
              }
              if ("findByPayNo".equals(method.getName())) {
                Runnable writer = afterUserReceive.getAndSet(null);
                if (writer != null) writer.run();
              }
              return result;
            });
    Class<?> queryType =
        userClasses.loadClass(
            "com.cloudmall.user.service.impl.evidence.UserTccReconciliationReadService");
    var query = queryType.getConstructors()[0].newInstance(instances);
    var proxy = new ProxyFactory(query);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(userTracked),
            new AnnotationTransactionAttributeSource()));
    Object baseProxy = proxy.getProxy(userClasses);
    Class<?> httpType =
        userClasses.loadClass(
            "com.cloudmall.user.service.impl.evidence.UserTccReconciliationService");
    Object baseGate = httpType.getConstructors()[0].newInstance(baseProxy, true, "ops-secret");
    Class<?> frozenType =
        userClasses.loadClass(
            "com.cloudmall.user.service.impl.evidence.UserFrozenOwnershipEvidenceReadService");
    Object frozenQuery =
        frozenType.getConstructors()[0].newInstance(
            baseProxy, instances[0], instances[1], instances[2], instances[3], instances[4]);
    ProxyFactory frozenProxy = new ProxyFactory(frozenQuery);
    frozenProxy.setProxyTargetClass(true);
    frozenProxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(userTracked),
            new AnnotationTransactionAttributeSource()));
    Class<?> frozenGateType =
        userClasses.loadClass(
            "com.cloudmall.user.service.impl.evidence.UserFrozenOwnershipEvidenceService");
    Object frozenGate =
        frozenGateType.getConstructors()[0].newInstance(
            baseGate, frozenProxy.getProxy(userClasses), true);
    Object controller =
        userClasses
            .loadClass("com.cloudmall.user.controller.UserFrozenOwnershipEvidenceController")
            .getConstructors()[0]
            .newInstance(frozenGate);
    userMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  /** 随机loopback HTTP只接受GET，用真实Feign映射、decoder、timeout及retryer。 */
  private static void configureTransport() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "cm16t26-owned-user-http");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(serverExecutor);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())) {
              writeRequests.incrementAndGet();
              exchange.sendResponseHeaders(405, -1);
              return;
            }
            getRequests.incrementAndGet();
            receivedCallerIds.set(exchange.getRequestHeaders().get("X-User-Id"));
            receivedCallerRole.set(exchange.getRequestHeaders().getFirst("X-User-Role"));
            receivedService.set(exchange.getRequestHeaders().getFirst("X-Internal-Service"));
            receivedToken.set(exchange.getRequestHeaders().getFirst("X-Internal-Callback-Token"));
            receivedXid.set(exchange.getRequestHeaders().getFirst("TX_XID"));
            if (remoteUnavailable) {
              exchange.sendResponseHeaders(503, -1);
              return;
            }
            try {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertNull(RootContext.getXID());
              var request = get(URI.create("http://localhost" + exchange.getRequestURI()));
              exchange
                  .getRequestHeaders()
                  .forEach((name, values) -> request.header(name, values.toArray()));
              var result = userMvc.perform(request).andReturn().getResponse();
              byte[] body = result.getContentAsByteArray();
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(result.getStatus(), body.length);
              exchange.getResponseBody().write(body);
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              assertNull(RootContext.getXID());
            } catch (Throwable failure) {
              transportAssertion.set(failure);
              exchange.sendResponseHeaders(500, -1);
            }
          }
        });
    server.start();
    ObjectMapper json = new ObjectMapper();
    UserFrozenOwnershipEvidenceClient http =
        Feign.builder()
            .contract(new SpringMvcContract())
            .requestInterceptor(new FeignAuthConfiguration().authHeaders())
            .decoder(
                (response, type) -> {
                  if (response.body() == null) return null;
                  try (var body = response.body().asInputStream()) {
                    return json.readValue(body, json.constructType(type));
                  }
                })
            .errorDecoder(new feign.codec.ErrorDecoder.Default())
            .retryer(feign.Retryer.NEVER_RETRY)
            .options(new feign.Request.Options(3_000, 5_000))
            .target(
                UserFrozenOwnershipEvidenceClient.class,
                "http://127.0.0.1:" + server.getAddress().getPort());
    UserFrozenOwnershipEvidenceClient onlyGet =
        (UserFrozenOwnershipEvidenceClient)
            Proxy.newProxyInstance(
                UserFrozenOwnershipEvidenceClient.class.getClassLoader(),
                new Class<?>[] {UserFrozenOwnershipEvidenceClient.class},
                (proxy, method, arguments) -> {
                  assertEquals("inspect", method.getName());
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                  assertNull(RootContext.getXID());
                  try {
                    return method.invoke(http, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  } finally {
                    Runnable hook = afterUserRead.getAndSet(null);
                    if (hook != null) hook.run();
                  }
                });
    collector =
        new PayTccUserFrozenOwnershipEvidenceService(service, reader, onlyGet, true, "ops-secret");
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
    userTracked.checking = false;
    afterUserRead.set(null);
    afterUserReceive.set(null);
    remoteUnavailable = false;
    receivedCallerIds.set(null);
    receivedCallerRole.set(null);
    transportAssertion.set(null);
    getRequests.set(0);
    writeRequests.set(0);
    seed("COMMITTED");
    seedUser("CONFIRMED");
    tracked.reset();
    userTracked.reset();
  }

  @AfterAll
  static void cleanup() throws Exception {
    // 先释放所有owned监听，无论数据库断言是否失败都不会留下后台服务。
    if (server != null) server.stop(0);
    if (serverExecutor != null) serverExecutor.shutdownNow();
    if (userClasses != null) userClasses.close();
    if (tracked != null) tracked.checking = false;
    if (userTracked != null) userTracked.checking = false;
    try {
      if (source != null) assertEquals(sourceFingerprint, originalFingerprint());
      if (userSource != null)
        assertEquals(userFingerprint, fingerprint(userSource, "cloudmall_user"));
    } finally {
      try {
        dropOwned(source, database, "cm16t26_pay_");
      } finally {
        dropOwned(userSource, userDatabase, "cm16t26_user_");
      }
    }
    System.out.println(
        "CM16T26 original Pay/User checksums unchanged; owned databases and HTTP listener removed");
  }

  /** 原活跃消费者保其scope，不将未检历史终态解释为逐笔已证明。 */
  @Test
  void terminalOriginRemainsOutsideCurrentTryingConsumerScope() {
    peer(2, "10.00", true);
    userFixture.update("UPDATE balance_tcc_debit SET state='CONFIRMED' WHERE id=2");
    userFixture.update("UPDATE balance_debit_attempt SET state='CONFIRMED' WHERE id=2");
    userFixture.update("DELETE FROM user_tcc_receive WHERE id=2");
    var payBefore = facts();
    var userBefore = userFacts();
    enableChecks();
    var report = inspectUser();
    assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification);
    assertTrue(report.terminalFrozenRowsNotInspected);
    assertEquals(0, report.userFrozenOwnershipEvidence.observedCount());
    assertEquals(12, tracked.statements.size());
    assertEquals(11, userTracked.statements.size());
    assertUnchanged(payBefore, userBefore);
  }

  @Test
  void realUserOwnershipUsesPersistentAnchorAndRestoresCallerContext() {
    for (int peers : List.of(0, 1, 2)) {
      seedUser("CONFIRMED");
      for (int index = 0; index < peers; index++) peer(index + 2, "10.00", true);
      userFixture.update("UPDATE mall_user SET frozen_balance=? WHERE id=7", peers * 10);
      var payBefore = facts();
      var userBefore = userFacts();
      tracked.reset();
      userTracked.reset();
      enableChecks();
      AuthContext.set(99L, "ADMIN");
      try {
        var report = inspectUser();
        assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification);
        assertEquals(peers, report.userFrozenOwnershipEvidence.observedCount());
        assertTrue(report.userFrozenOwnershipEvidence.candidatesComplete());
        assertTrue(report.userAccountEvidence.identityMatched());
        assertFalse(report.automaticActionsAllowed);
        assertTrue(report.terminalFrozenRowsNotInspected);
        assertNull(receivedCallerIds.get());
        assertNull(receivedCallerRole.get());
        assertNull(receivedXid.get());
        assertEquals("cm18-reconciliation", receivedService.get());
        assertEquals("ops-secret", receivedToken.get());
        assertEquals(99L, AuthContext.userId());
        assertEquals("ADMIN", AuthContext.role());
      } finally {
        AuthContext.clear();
      }
      assertEquals(2, tracked.connections);
      assertEquals(12, tracked.statements.size());
      assertEquals(1, userTracked.connections);
      // CONFIRMED基础账户10次、候选1次、每个TRYING peer7次。
      assertEquals(11 + 7 * peers, userTracked.statements.size());
      assertUnchanged(payBefore, userBefore);
    }
  }

  @Test
  void missingPeerOriginAndConflictingFenceStayBlockedInsteadOfJustEqualTotals() {
    for (String mutation : List.of("receive", "fence", "mode", "partial")) {
      seedUser("CONFIRMED");
      peer(2, "10.00", true);
      userFixture.update("UPDATE mall_user SET frozen_balance=10 WHERE id=7");
      switch (mutation) {
        case "receive" -> userFixture.update("DELETE FROM user_tcc_receive WHERE id=2");
        case "fence" -> userFixture.update("UPDATE tcc_fence_log SET status=2 WHERE branch_id=302");
        case "mode" ->
            userFixture.update("UPDATE balance_debit_attempt SET mode='LEGACY' WHERE id=2");
        case "partial" -> seedRefund("5.00");
        default -> throw new AssertionError(mutation);
      }
      var payBefore = facts();
      var userBefore = userFacts();
      tracked.reset();
      userTracked.reset();
      enableChecks();
      var report = inspectUser();
      assertEquals(
          List.of("fence", "mode").contains(mutation)
              ? "USER_EVIDENCE_CONFLICT"
              : "USER_EVIDENCE_INCOMPLETE",
          report.classification,
          mutation);
      assertNull(report.userFrozenOwnershipEvidence.validatedFrozenTotal());
      assertNull(report.userAccountEvidence);
      assertUnchanged(payBefore, userBefore);
    }
  }

  @Test
  void hundredPeersAndSentinelAreVerifiedThroughActualFeignAndUserRr() {
    for (int count : List.of(100, 101)) {
      seedUser("CONFIRMED");
      for (int index = 0; index < count; index++) peer(index + 2, "0.01", count == 100);
      userFixture.update(
          "UPDATE mall_user SET frozen_balance=? WHERE id=7",
          new java.math.BigDecimal("0.01").multiply(java.math.BigDecimal.valueOf(count)));
      var payBefore = facts();
      var userBefore = userFacts();
      tracked.reset();
      userTracked.reset();
      enableChecks();
      var report = inspectUser();
      assertEquals(12, tracked.statements.size());
      assertEquals(1, userTracked.connections);
      if (count == 100) {
        assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification);
        assertEquals(100, report.userFrozenOwnershipEvidence.observedCount());
        assertEquals("1.00", report.userFrozenOwnershipEvidence.validatedFrozenTotal());
        assertEquals(11 + 7 * count, userTracked.statements.size());
      } else {
        assertEquals("USER_EVIDENCE_INCOMPLETE", report.classification);
        assertNull(report.userFrozenOwnershipEvidence.observedCount());
        assertEquals(11, userTracked.statements.size());
      }
      assertUnchanged(payBefore, userBefore);
    }
  }

  @Test
  void concurrentUserFreezeIsOnlyVisibleInTheNextUserSnapshot() {
    peer(2, "0.01", true);
    userFixture.update("UPDATE mall_user SET frozen_balance=0.01 WHERE id=7");
    afterUserReceive.set(
        () ->
            new org.springframework.transaction.support.TransactionTemplate(
                    new DataSourceTransactionManager(userFixture.getDataSource()))
                .executeWithoutResult(
                    status -> {
                      peer(3, "0.01", true);
                      userFixture.update("UPDATE mall_user SET frozen_balance=0.02 WHERE id=7");
                    }));
    enableChecks();
    var first = inspectUser();
    assertEquals(1, first.userFrozenOwnershipEvidence.observedCount());
    assertEquals("0.01", first.userFrozenOwnershipEvidence.validatedFrozenTotal());
    assertEquals(18, userTracked.statements.size());
    tracked.reset();
    userTracked.reset();
    var next = inspectUser();
    assertEquals(2, next.userFrozenOwnershipEvidence.observedCount());
    assertEquals("0.02", next.userFrozenOwnershipEvidence.validatedFrozenTotal());
    assertEquals(25, userTracked.statements.size());
    assertNull(transportAssertion.get());
  }

  @Test
  void unavailableGetIsNotRetriedAndLocalChangeWinsEvenOnFailure() {
    for (boolean fail : List.of(false, true)) {
      tracked.checking = false;
      seed("COMMITTED");
      remoteUnavailable = fail;
      afterUserRead.set(() -> fixture.update("UPDATE pay_record SET status='SUCCESS'"));
      enableChecks();
      assertEquals("LOCAL_EVIDENCE_CHANGED", inspectUser().classification);
      assertNull(transportAssertion.get());
    }
    tracked.checking = false;
    seed("COMMITTED");
    tracked.reset();
    userTracked.reset();
    remoteUnavailable = true;
    getRequests.set(0);
    enableChecks();
    AuthContext.set(99L, "ADMIN");
    try {
      assertEquals("USER_EVIDENCE_UNAVAILABLE", inspectUser().classification);
      assertEquals(99L, AuthContext.userId());
      assertEquals("ADMIN", AuthContext.role());
    } finally {
      AuthContext.clear();
    }
    assertEquals(1, getRequests.get());
    assertEquals(12, tracked.statements.size());
    assertEquals(0, userTracked.connections);
  }

  @Test
  void untrustedAnchorSkipsUserAndHttpInputsCannotChangeOriginalScope() throws Exception {
    fixture.update("UPDATE pay_tcc_execution SET state='UNKNOWN'");
    enableChecks();
    assertFalse(inspectUser().userFrozenOwnershipEvidence.queryAttempted());
    assertEquals(0, getRequests.get());
    assertEquals(0, userTracked.connections);
    tracked.checking = false;
    seed("COMMITTED");
    tracked.reset();
    enableChecks();
    var mvc =
        MockMvcBuilders.standaloneSetup(new PayTccUserFrozenOwnershipEvidenceController(collector))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String route = "/api/internal/payments/pay1/tcc-reconciliation/user-frozen-ownership-evidence";
    mvc.perform(get(route))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    String json =
        mvc.perform(
                get(route)
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret")
                    .param("userId", "8")
                    .param("xid", "caller-private-xid")
                    .param("amount", "999"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.classification").value("BLOCKED_REMAINING_EVIDENCE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String raw :
        List.of(
            "tc-private:8091:original",
            "tcc-debit:pay1",
            "private-password",
            "private-phone",
            "private-role",
            "ops-secret",
            "caller-private-xid",
            "trustedAnchor")) assertFalse(json.contains(raw), raw);
    assertNull(transportAssertion.get());
    assertEquals(0, writeRequests.get());
  }

  @Test
  void actualUserEnableAndCredentialGatesRemainBeforeSql() throws Exception {
    enableChecks();
    userMvc
        .perform(
            get("/api/internal/users/7/balance/tcc-reconciliation/pay1/frozen-ownership-evidence")
                .header("X-Internal-Service", "cm18-reconciliation")
                .header("X-Internal-Callback-Token", "wrong"))
        .andExpect(status().isForbidden())
        .andExpect(header().string("Cache-Control", "no-store"));
    assertEquals(0, userTracked.connections);
    assertEquals(0, tracked.connections);
  }

  /** 只准备owned原TRYING与可选完整来源，不调用资金写服务或注册TC。 */
  private static void peer(int identifier, String amount, boolean complete) {
    String payNo = "peer" + identifier;
    String operationKey = "tcc-debit:" + payNo;
    String xid = "private-peer-xid-" + identifier;
    long branchId = 300L + identifier;
    userFixture.update(
        "INSERT INTO"
            + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
            + " VALUES(?,?,?,?,7,?,'202610peer',?,'TRYING',100,100-?,'2026-10-11','2026-10-11')",
        identifier,
        operationKey,
        xid,
        branchId,
        payNo,
        amount,
        amount);
    if (!complete) return;
    userFixture.update(
        "INSERT INTO"
            + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
            + " VALUES(?,?,?,?,7,'202610peer',?,?,?,'CLAIMED','2026-10-11','2026-10-11')",
        identifier,
        payNo,
        xid,
        branchId,
        amount,
        operationKey,
        payNo);
    userFixture.update(
        "INSERT INTO"
            + " balance_debit_attempt(id,pay_no,operation_key,mode,xid,ledger_key,branch_id,user_id,order_no,amount,state,created_at,updated_at)"
            + " VALUES(?,?,?,'TCC',?,?,?,7,'202610peer',?,'TRYING','2026-10-11','2026-10-11')",
        identifier,
        payNo,
        operationKey,
        xid,
        payNo,
        branchId,
        amount);
    try {
      String action =
          (String)
              userClasses
                  .loadClass("com.cloudmall.user.service.BalanceTccDebitLocalResource")
                  .getField("ACTION_NAME")
                  .get(null);
      userFixture.update(
          "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
              + " VALUES(?,?,?,1,'2026-10-11','2026-10-11')",
          xid,
          branchId,
          action);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  /** 仅在owned User库准备固定退款，不调用资金/RM写接口。 */
  private static void seedRefund(String amount) {
    userFixture.update(
        "INSERT INTO"
            + " balance_ledger(id,user_id,operation_key,operation_type,original_operation_key,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
            + " VALUES(2,7,'refund:pay1','REFUND','pay1','pay1','202610order1',?,'APPLIED',90,90+?,'2026-10-11')",
        amount,
        amount);
  }

  /** 请求期间两端Mapper只允许真实只读RR SELECT。 */
  private static void enableChecks() {
    tracked.checking = true;
    userTracked.checking = true;
  }

  /** 真实受控入口，不接受额外资金参数。 */
  private PayTccUserFrozenOwnershipReconciliationVO inspectUser() {
    return collector.inspect("pay1", "cm18-reconciliation", "ops-secret");
  }

  /** 包括租约/重试/时间/金额的完整行事实核验。 */
  private static void assertUnchanged(Object payBefore, Object userBefore) {
    assertEquals(payBefore, facts());
    assertEquals(userBefore, userFacts());
    assertEquals(0, writeRequests.get());
    assertNull(transportAssertion.get());
  }

  /** User六表完整快照，包含账户。 */
  private static List<List<Map<String, Object>>> userFacts() {
    return USER_TABLES.stream()
        .map(table -> userFixture.queryForList("SELECT * FROM " + table))
        .toList();
  }

  /** 标准合法User持久事实，不调用资金资源或TC。 */
  private static void seedUser(String state) {
    userTracked.checking = false;
    for (String table : USER_TABLES) userFixture.update("DELETE FROM " + table);
    String time = "2026-10-09 12:00:00.123";
    userFixture.update(
        "INSERT INTO"
            + " mall_user(id,username,password_hash,role,phone,balance,frozen_balance,status,created_at,updated_at)"
            + " VALUES(7,'owned-user','private-password','private-role','private-phone',?,?,1,?,?)",
        "CANCELLED".equals(state) ? "100.00" : "90.00",
        "TRYING".equals(state) ? "10.00" : "0.00",
        time,
        time);

    userFixture.update(
        "INSERT INTO"
            + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
            + " VALUES(1,'pay1','tc-private:8091:original',202,7,'202610order1',10,'tcc-debit:pay1','pay1','CLAIMED',?,?)",
        time,
        time);
    userFixture.update(
        "INSERT INTO"
            + " balance_debit_attempt(id,pay_no,operation_key,mode,xid,ledger_key,branch_id,user_id,order_no,amount,state,created_at,updated_at)"
            + " VALUES(1,'pay1','tcc-debit:pay1','TCC','tc-private:8091:original','pay1',202,7,'202610order1',10,?,?,?)",
        state,
        time,
        time);
    userFixture.update(
        "INSERT INTO"
            + " balance_tcc_debit(id,operation_key,xid,branch_id,user_id,pay_no,order_no,amount,state,balance_before,balance_after_try,created_at,updated_at)"
            + " VALUES(1,'tcc-debit:pay1','tc-private:8091:original',202,7,'pay1','202610order1',10,?,100,90,?,?)",
        state,
        time,
        time);
    if ("CONFIRMED".equals(state))
      userFixture.update(
          "INSERT INTO"
              + " balance_ledger(id,user_id,operation_key,operation_type,pay_no,order_no,amount,state,balance_before,balance_after,created_at)"
              + " VALUES(1,7,'pay1','DEBIT','pay1','202610order1',10,'APPLIED',100,90,?)",
          time);
    try {
      String action =
          (String)
              userClasses
                  .loadClass("com.cloudmall.user.service.BalanceTccDebitLocalResource")
                  .getField("ACTION_NAME")
                  .get(null);
      userFixture.update(
          "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
              + " VALUES('tc-private:8091:original',202,?,?,?,?)",
          action,
          "CONFIRMED".equals(state) ? 2 : "CANCELLED".equals(state) ? 3 : 1,
          time,
          time);
    } catch (ReflectiveOperationException failure) {
      throw new AssertionError(failure);
    }
  }

  /** 只对已核验owned名称删除，原库仅查询。 */
  private static void dropOwned(JdbcTemplate admin, String name, String prefix) {
    if (admin == null || name == null) return;
    if (!name.matches(prefix + "[0-9a-f]{32}")) throw new IllegalStateException("非法owned库");
    admin.execute("DROP DATABASE " + name);
    assertEquals(
        0,
        admin.queryForObject(
            "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
            Integer.class,
            name));
  }

  /** 原库表清单和校验和，运行前后对比。 */
  private static List<Map<String, Object>> fingerprint(JdbcTemplate admin, String schema) {
    List<Map<String, Object>> values =
        new ArrayList<>(admin.queryForList("SHOW TABLES FROM " + schema));
    for (String table : admin.queryForList("SHOW TABLES FROM " + schema, String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表");
      values.addAll(admin.queryForList("CHECKSUM TABLE " + schema + "." + table));
    }
    return values;
  }

  /** 创建已有六表的owned测试结构，绝不在被测检查期间运行。 */
  private static void ensureFixtureTables() {
    session.getMapper(PayTccExecutionMapper.class).ensureTable();
    session.getMapper(PayTccDispatchMapper.class).ensureTable();
    session.getMapper(PayTccReceiptMapper.class).ensureTable();
    session.getMapper(PayTccFenceMapper.class).ensureTable();
  }

  /** 用原字段准备本地完整终态；不注册任何TC分支或真实User操作。 */
  private static void seed(String state) {
    tracked.checking = false;
    for (String table : TABLES) fixture.update("DELETE FROM " + table);
    String time = "2026-10-09 12:00:00.123";
    fixture.update(
        "INSERT INTO"
            + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
            + " VALUES(1,'pay1','202610order1',7,10,'PENDING','TCC',?,?)",
        time,
        time);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_execution(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,decision,created_at,updated_at,recovery_attempts,recovery_owner,recovery_lease_until)"
            + " VALUES(1,'pay1','tc-private:8091:original',7,'202610order1',10,'tcc-debit:pay1','pay1',?,?,?,?,5,'existing-owner','2099-01-01')",
        state,
        "COMMITTED".equals(state) ? "COMMIT" : "ROLLBACK",
        time,
        time);
    String branchState = "COMMITTED".equals(state) ? "CONFIRMED" : "CANCELLED";
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_dispatch(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,user_branch_id,created_at,updated_at,last_error)"
            + " VALUES(1,'pay1','tc-private:8091:original',7,'202610order1',10,'tcc-debit:pay1','pay1',?,202,?,?,'private-error-password')",
        branchState,
        time,
        time);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_receipt(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,branch_id,created_at,updated_at)"
            + " VALUES(1,'pay1','tc-private:8091:original',7,'202610order1',10,'tcc-debit:pay1','pay1',?,101,?,?)",
        branchState,
        time,
        time);
    fixture.update(
        "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
            + " VALUES('tc-private:8091:original',101,?,?,?,?)",
        PayTccReceiptLocalResource.ACTION_NAME,
        "COMMITTED".equals(state) ? 2 : 3,
        time,
        time);
  }

  /** 完整六表行事实包括资金、租约、attempts、错误与原ID。 */
  private static List<List<Map<String, Object>>> facts() {
    return TABLES.stream().map(table -> fixture.queryForList("SELECT * FROM " + table)).toList();
  }

  /** 原Pay库只读表清单/校验和。 */
  private static List<Map<String, Object>> originalFingerprint() {
    List<Map<String, Object>> values =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_pay"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_pay", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表");
      values.addAll(source.queryForList("CHECKSUM TABLE cloudmall_pay." + table));
    }
    return values;
  }

  /** 日期遵循项目原上海语义，不设置全局时区。 */
  private static String shanghai(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }

  /** 每个被测SELECT必须在真实只读RR事务连接中；不依赖mock事务注解。 */
  private static final class TrackingDataSource extends AbstractDataSource {
    /** owned库基础连接来源。 */
    final DataSource delegate;

    /** 仅被测请求阶段启用写入/事务断言。 */
    boolean checking;

    /** 被测请求取得的实际连接数。 */
    int connections;

    /** 本次请求实际准备的SQL。 */
    final List<String> statements = new ArrayList<>();

    TrackingDataSource(DataSource delegate) {
      this.delegate = delegate;
    }

    void reset() {
      connections = 0;
      statements.clear();
    }

    /** 为每条被测SQL验证实际只读RR连接。 */
    @Override
    public Connection getConnection() throws java.sql.SQLException {
      Connection connection = delegate.getConnection();
      if (checking) connections++;
      return (Connection)
          Proxy.newProxyInstance(
              Connection.class.getClassLoader(),
              new Class<?>[] {Connection.class},
              (proxy, method, arguments) -> {
                if (checking && "prepareStatement".equals(method.getName())) {
                  String sql = ((String) arguments[0]).stripLeading().toUpperCase(Locale.ROOT);
                  assertTrue(sql.startsWith("SELECT"), "non-read SQL: " + sql);
                  assertFalse(sql.contains("FOR UPDATE"));
                  assertTrue(connection.isReadOnly());
                  assertEquals(
                      Connection.TRANSACTION_REPEATABLE_READ, connection.getTransactionIsolation());
                  statements.add(sql);
                }
                try {
                  return method.invoke(connection, arguments);
                } catch (InvocationTargetException failure) {
                  throw failure.getCause();
                }
              });
    }

    /** 本夹具固定数据源，使用同一受监控连接入口。 */
    @Override
    public Connection getConnection(String username, String password) throws java.sql.SQLException {
      return getConnection();
    }
  }
}
