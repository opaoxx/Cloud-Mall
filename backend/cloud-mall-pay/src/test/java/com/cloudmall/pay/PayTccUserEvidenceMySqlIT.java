package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.config.FeignAuthConfiguration;
import com.cloudmall.pay.config.UserTccFeignConfiguration;
import com.cloudmall.pay.controller.PayTccUserEvidenceController;
import com.cloudmall.pay.domain.vo.PayTccUserReconciliationVO;
import com.cloudmall.pay.feign.UserTccClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.PayTccReconciliationService;
import com.cloudmall.pay.service.impl.PayTccUserEvidenceService;
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
class PayTccUserEvidenceMySqlIT {
  /** 原库只读管理连接。 */
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

  private static List<Map<String, Object>> userFingerprint;
  private static String userDatabase;

  /** 实际User类、Mapper、事务代理和HTTP边界。 */
  private static URLClassLoader userClasses;

  private static TrackingDataSource userTracked;
  private static SqlSessionTemplate userSession;
  private static MockMvc userMvc;
  private static HttpServer server;
  private static ExecutorService serverExecutor;
  private static PayTccUserEvidenceService collector;

  /** HTTP请求计数、测试故障和SQL断言传播。 */
  private static final AtomicInteger getRequests = new AtomicInteger(),
      writeRequests = new AtomicInteger();

  private static final AtomicReference<Runnable> afterUserRead = new AtomicReference<>();
  private static final AtomicReference<Throwable> transportAssertion = new AtomicReference<>();
  private static volatile boolean remoteUnavailable;

  /** 真实线上User身份头，检验全局Feign拦截器不会污染持久锚点。 */
  private static final AtomicReference<List<String>> receivedCallerIds = new AtomicReference<>();

  /** 调用方角色不能混入内部User查询。 */
  private static final AtomicReference<String> receivedCallerRole = new AtomicReference<>();

  /** User只读链的五张事实表，不含账户汇总。 */
  private static final List<String> USER_TABLES =
      List.of(
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
    database = "cm16t2_pay_" + UUID.randomUUID().toString().replace("-", "");
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
            PayTccUserEvidenceMySqlIT.class.getClassLoader());
    userSource =
        new JdbcTemplate(
            new DriverManagerDataSource(
                shanghai(url.replace("/cloudmall_pay", "/cloudmall_user")), "root", "root"));
    userFingerprint = fingerprint(userSource, "cloudmall_user");
    userDatabase = "cm16t2_user_" + UUID.randomUUID().toString().replace("-", "");
    userSource.execute(
        "CREATE DATABASE " + userDatabase + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    var userDataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_pay", "/" + userDatabase)), "root", "root");
    userFixture = new JdbcTemplate(userDataSource);
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
            "TccFenceLogMapper")) {
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
      mappers.get(index).getMethod("ensureTable").invoke(instances[index]);
    }
    Class<?> queryType = userClasses.loadClass("com.cloudmall.user.service.impl.UserTccStateQuery");
    var query = queryType.getConstructors()[0].newInstance(instances);
    var proxy = new ProxyFactory(query);
    proxy.setProxyTargetClass(true);
    proxy.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(userTracked),
            new AnnotationTransactionAttributeSource()));
    Class<?> httpType = userClasses.loadClass("com.cloudmall.user.service.impl.UserTccHttpService");
    // GET仅需要query；写资源不注入，任何意外写调用必须失败。
    var httpService =
        httpType.getConstructors()[0].newInstance(
            null, null, proxy.getProxy(userClasses), null, "ops-secret");
    Object controller =
        userClasses
            .loadClass("com.cloudmall.user.controller.UserTccController")
            .getConstructors()[0]
            .newInstance(httpService);
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
              Thread thread = new Thread(task, "cm16t2-owned-user-http");
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
    var config = new UserTccFeignConfiguration();
    ObjectMapper json = new ObjectMapper();
    UserTccClient http =
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
            .errorDecoder(config.userTccErrorDecoder())
            .retryer(config.userTccRetryer())
            .options(config.userTccRequestOptions())
            .target(UserTccClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
    UserTccClient onlyGet =
        (UserTccClient)
            Proxy.newProxyInstance(
                UserTccClient.class.getClassLoader(),
                new Class<?>[] {UserTccClient.class},
                (proxy, method, arguments) -> {
                  assertEquals("state", method.getName());
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
    collector = new PayTccUserEvidenceService(service, reader, onlyGet, true, "ops-secret");
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
    userTracked.checking = false;
    afterUserRead.set(null);
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
      dropOwned(source, database, "cm16t2_pay_");
      dropOwned(userSource, userDatabase, "cm16t2_user_");
    }
    System.out.println(
        "CM16T2 original Pay/User checksums unchanged; owned databases and HTTP listener removed");
  }

  @Test
  void inheritedAuthContextCannotPolluteOriginalFeignIdentityAndIsRestored() {
    var payBefore = facts();
    var userBefore = userFacts();
    enableChecks();
    AuthContext.set(99L, "ADMIN");
    try {
      assertEquals("BLOCKED_REMAINING_EVIDENCE", inspectUser().classification);
      assertEquals(List.of("7"), receivedCallerIds.get());
      assertNull(receivedCallerRole.get());
      assertEquals(99L, AuthContext.userId());
      assertEquals("ADMIN", AuthContext.role());
    } finally {
      AuthContext.clear();
    }
    assertUnchanged(payBefore, userBefore);
  }

  @Test
  void confirmedAndCancelledActualUserGetPreserveBothDatabasesAndNeverAuthorizeFunds() {
    for (String state : List.of("CONFIRMED", "CANCELLED")) {
      seed("CONFIRMED".equals(state) ? "COMMITTED" : "ROLLEDBACK");
      seedUser(state);
      var payBefore = facts();
      var userBefore = userFacts();
      tracked.reset();
      userTracked.reset();
      enableChecks();
      var report = inspectUser();
      assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification);
      assertEquals(state, report.userEvidence.state());
      assertEquals("CONFIRMED".equals(state), report.userEvidence.ledgerApplied());
      assertFalse(report.automaticActionsAllowed);
      assertEquals(2, tracked.connections);
      assertEquals(12, tracked.statements.size());
      assertEquals(1, userTracked.connections);
      assertEquals(5, userTracked.statements.size());
      assertUnchanged(payBefore, userBefore);
    }
    assertEquals(2, getRequests.get());
    assertEquals(0, writeRequests.get());
  }

  @Test
  void unknownMissingReceiveAndMissingAppliedLedgerAreDistinctBlockedResultsWithoutWrites() {
    for (String variant : List.of("unknown", "receive", "ledger")) {
      tracked.checking = false;
      userTracked.checking = false;
      seedUser("CONFIRMED");
      if ("unknown".equals(variant))
        for (String table : USER_TABLES) {
          if (!"user_tcc_receive".equals(table)) userFixture.update("DELETE FROM " + table);
        }
      else
        userFixture.update(
            "DELETE FROM " + ("receive".equals(variant) ? "user_tcc_receive" : "balance_ledger"));
      var payBefore = facts();
      var userBefore = userFacts();
      enableChecks();
      assertEquals(
          "unknown".equals(variant)
              ? "USER_EVIDENCE_INCOMPLETE"
              : "receive".equals(variant) ? "USER_EVIDENCE_UNAVAILABLE" : "USER_EVIDENCE_CONFLICT",
          inspectUser().classification,
          variant);
      assertUnchanged(payBefore, userBefore);
    }
  }

  @Test
  void actualUserAmountOrderBranchAndOppositeTerminalEvidenceAreConflicts() {
    for (String variant : List.of("amount", "order", "branch", "state")) {
      tracked.checking = false;
      userTracked.checking = false;
      seedUser("state".equals(variant) ? "CANCELLED" : "CONFIRMED");
      if ("amount".equals(variant))
        for (String table : USER_TABLES) {
          if (!"tcc_fence_log".equals(table))
            userFixture.update("UPDATE " + table + " SET amount=11");
        }
      if ("order".equals(variant))
        for (String table : USER_TABLES) {
          if (!"tcc_fence_log".equals(table))
            userFixture.update("UPDATE " + table + " SET order_no='other'");
        }
      if ("branch".equals(variant))
        for (String table : USER_TABLES) {
          if (!"balance_ledger".equals(table))
            userFixture.update("UPDATE " + table + " SET branch_id=101");
        }
      var payBefore = facts();
      var userBefore = userFacts();
      enableChecks();
      assertEquals("USER_EVIDENCE_CONFLICT", inspectUser().classification, variant);
      assertUnchanged(payBefore, userBefore);
    }
  }

  @Test
  void unavailableUserStillRecapturesPayAndNeverWritesOrCallsPost() {
    remoteUnavailable = true;
    var payBefore = facts();
    var userBefore = userFacts();
    enableChecks();
    assertEquals("USER_EVIDENCE_UNAVAILABLE", inspectUser().classification);
    assertEquals(2, tracked.connections);
    assertEquals(0, userTracked.connections);
    assertEquals(1, getRequests.get());
    assertUnchanged(payBefore, userBefore);
  }

  @Test
  void payChangeDuringUserRequestDiscardsRemoteConclusionEvenWhenUserFailed() {
    for (boolean failure : List.of(false, true)) {
      tracked.checking = false;
      userTracked.checking = false;
      seed("COMMITTED");
      remoteUnavailable = failure;
      var userBefore = userFacts();
      afterUserRead.set(() -> fixture.update("UPDATE pay_record SET status='SUCCESS'"));
      enableChecks();
      var report = inspectUser();
      assertEquals("LOCAL_EVIDENCE_CHANGED", report.classification);
      assertFalse(report.userEvidence.identityMatched());
      assertNull(report.userEvidence.ledgerApplied());
      assertEquals(userBefore, userFacts());
      assertEquals(
          "SUCCESS", fixture.queryForObject("SELECT status FROM pay_record", String.class));
      assertEquals(0, writeRequests.get());
      assertNull(transportAssertion.get());
    }
  }

  @Test
  void tryingEvidenceIsIncompleteAgainstPayTerminalAndMatchedOnlyWithPendingPay() {
    seedUser("TRYING");
    var payBefore = facts();
    var userBefore = userFacts();
    enableChecks();
    var pending = inspectUser();
    assertEquals("USER_EVIDENCE_INCOMPLETE", pending.classification);
    assertEquals(List.of("USER_TERMINAL_EVIDENCE_PENDING"), pending.reasonCodes);
    assertUnchanged(payBefore, userBefore);
    tracked.checking = false;
    fixture.update("UPDATE pay_tcc_execution SET state='RUNNING',decision='NONE'");
    fixture.update("UPDATE pay_tcc_receipt SET state='TRYING'");
    fixture.update("UPDATE pay_tcc_dispatch SET state='TRY_ACCEPTED'");
    fixture.update("UPDATE tcc_fence_log SET status=1");
    payBefore = facts();
    enableChecks();
    var matched = inspectUser();
    assertEquals("BLOCKED_REMAINING_EVIDENCE", matched.classification);
    assertEquals(Boolean.FALSE, matched.userEvidence.ledgerApplied());
    assertFalse(matched.automaticActionsAllowed);
    assertUnchanged(payBefore, userBefore);
  }

  @Test
  void actualUserAuthenticationRejectsInvalidCallerBeforeAnyDatabaseRead() throws Exception {
    var payBefore = facts();
    var userBefore = userFacts();
    enableChecks();
    userMvc
        .perform(
            get("/api/internal/users/7/balance/tcc-debits/tcc-debit:pay1")
                .header("X-User-Id", "7")
                .header("X-Internal-Service", "cloud-mall-pay")
                .header("X-Internal-Callback-Token", "wrong")
                .param("xid", "tc-private:8091:original"))
        .andExpect(status().isForbidden());
    assertEquals(0, userTracked.connections);
    assertEquals(0, tracked.connections);
    assertUnchanged(payBefore, userBefore);
  }

  @Test
  void untrustedPayAnchorNeverQueriesRealUserAndHttpReportIsNoStoreWithoutRawIds()
      throws Exception {
    fixture.update("UPDATE pay_tcc_execution SET state='UNKNOWN'");
    var payBefore = facts();
    var userBefore = userFacts();
    enableChecks();
    var skipped = inspectUser();
    assertFalse(skipped.userEvidence.queryAttempted());
    assertEquals(0, getRequests.get());
    assertEquals(0, userTracked.connections);
    assertUnchanged(payBefore, userBefore);
    tracked.checking = false;
    seed("COMMITTED");
    enableChecks();
    var mvc =
        MockMvcBuilders.standaloneSetup(new PayTccUserEvidenceController(collector))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String body =
        mvc.perform(
                get("/api/internal/payments/pay1/tcc-reconciliation/user-evidence")
                    .param("xid", "caller-cannot-override")
                    .param("branchId", "999")
                    .param("userId", "8")
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.classification").value("BLOCKED_REMAINING_EVIDENCE"))
            .andExpect(jsonPath("$.data.automaticActionsAllowed").value(false))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String raw :
        List.of(
            "tc-private:8091:original",
            "tcc-debit:pay1",
            "private-error-password",
            "ops-secret",
            "trustedAnchor")) assertFalse(body.contains(raw), raw);
    assertEquals(0, writeRequests.get());
    assertNull(transportAssertion.get());
  }

  /** 请求期间两端Mapper只允许真实只读RR SELECT。 */
  private static void enableChecks() {
    tracked.checking = true;
    userTracked.checking = true;
  }

  /** 真实受控入口，不接受额外资金参数。 */
  private PayTccUserReconciliationVO inspectUser() {
    return collector.inspect("pay1", "cm18-reconciliation", "ops-secret");
  }

  /** 包括租约/重试/时间/金额的完整行事实核验。 */
  private static void assertUnchanged(Object payBefore, Object userBefore) {
    assertEquals(payBefore, facts());
    assertEquals(userBefore, userFacts());
    assertEquals(0, writeRequests.get());
    assertNull(transportAssertion.get());
  }

  /** User五表完整快照。 */
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
            + " user_tcc_receive(id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key,state,created_at,updated_at)"
            + " VALUES(1,'pay1','tc-private:8091:original',202,7,'202610order1',10,'tcc-debit:pay1','pay1','ACCEPTED',?,?)",
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
