package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.config.CommonWebConfiguration;
import com.cloudmall.common.web.AuthInterceptor;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.config.FeignAuthConfiguration;
import com.cloudmall.pay.controller.PayTccOrderEvidenceController;
import com.cloudmall.pay.domain.vo.PayTccOrderReconciliationVO;
import com.cloudmall.pay.feign.OrderBasicEvidenceClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.PayTccOrderEvidenceService;
import com.cloudmall.pay.service.impl.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.PayTccReconciliationService;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpServer;
import feign.Feign;
import io.seata.core.context.RootContext;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLClassLoader;
import java.nio.file.Files;
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
import java.util.jar.JarFile;
import javax.sql.DataSource;
import org.apache.ibatis.session.LocalCacheScope;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mybatis.spring.SqlSessionTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.cloud.openfeign.support.SpringMvcContract;
import org.springframework.http.converter.json.Jackson2ObjectMapperBuilder;
import org.springframework.http.converter.json.MappingJackson2HttpMessageConverter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 两个owned库及真实Order GET/Feign验证；只监听随机loopback端口，finally精确清理。 */
class PayTccOrderEvidenceMySqlIT {
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

  /** 原Order库只读指纹和owned库。 */
  private static JdbcTemplate orderSource, orderFixture;

  /** 原Order表清单及校验和。 */
  private static List<Map<String, Object>> orderFingerprint;

  /** 仅当前IT拥有的Order库名。 */
  private static String orderDatabase;

  /** 真实Order生产类的隔离加载器。 */
  private static URLClassLoader orderClasses;

  /** Order既有GET的SQL写入/锁监测数据源。 */
  private static TrackingDataSource orderTracked;

  /** 实际Controller/归属校验和AuthInterceptor边界。 */
  private static MockMvc orderMvc;

  /** 只监听随机loopback地址的owned HTTP server。 */
  private static HttpServer server;

  /** owned HTTP处理线程，cleanup关闭。 */
  private static ExecutorService serverExecutor;

  /** 真实Pay/Order证据采集编排器。 */
  private static PayTccOrderEvidenceService collector;

  /** HTTP请求计数、测试故障和SQL断言传播。 */
  private static final AtomicInteger getRequests = new AtomicInteger(),
      writeRequests = new AtomicInteger();

  /** GET完成后仅测试在owned Pay库执行的受控writer钩子。 */
  private static final AtomicReference<Runnable> afterOrderRead = new AtomicReference<>();

  /** 将服务端SQL/上下文断言传回主测试线程。 */
  private static final AtomicReference<Throwable> transportAssertion = new AtomicReference<>();

  /** 模拟HTTP503而不调用Order生产查询。 */
  private static volatile boolean remoteUnavailable;

  /** 仅测试wire上的原生token变异，不改变Order原表或Controller。 */
  private static volatile String responseMutation;

  /** 真实线上Order身份头，检验全局Feign拦截器不会污染持久锚点。 */
  private static final AtomicReference<List<String>> receivedCallerIds = new AtomicReference<>();

  /** 调用方角色不能混入内部Order查询。 */
  private static final AtomicReference<String> receivedCallerRole = new AtomicReference<>();

  /** Order查询的两张月表，本报告仅使用主行基本字段。 */
  private static final List<String> ORDER_TABLES =
      List.of("mall_order_202610", "mall_order_item_202610");

  /** 仅当父classpath缺少Order已有Sentinel依赖时，加载其标准Boot jar中的core。 */
  private static Path sentinelCoreDirectory, sentinelCoreJar;

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_pay URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    sourceFingerprint = originalFingerprint();
    setupOrder(url);
    database = "cm16t3_pay_" + UUID.randomUUID().toString().replace("-", "");
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

  /** 真实Order Controller/get/Mapper；不实例化Boot应用、RPC、库存动作或调度器。 */
  private static void setupOrder(String url) throws Exception {
    Path classes = Path.of("../cloud-mall-order/target/classes").toAbsolutePath().normalize();
    if (!Files.isDirectory(classes)) throw new IllegalStateException("先构建标准Order classes/Boot jar");
    List<java.net.URL> classpath = new ArrayList<>();
    classpath.add(classes.toUri().toURL());
    try {
      Class.forName(
          "com.alibaba.csp.sentinel.Entry",
          false,
          PayTccOrderEvidenceMySqlIT.class.getClassLoader());
    } catch (ClassNotFoundException absentFromPayClasspath) {
      // 使用Order已经声明的固定版本依赖；无生产依赖或用户专属路径。
      Path artifact = Path.of("../cloud-mall-order/target/cloud-mall-order-0.1.0-SNAPSHOT.jar");
      try (JarFile jar = new JarFile(artifact.toFile())) {
        var entries =
            jar.stream()
                .filter(
                    entry ->
                        entry.getName().startsWith("BOOT-INF/lib/sentinel-core-")
                            && entry.getName().endsWith(".jar"))
                .toList();
        if (entries.size() != 1)
          throw new IllegalStateException("Order Boot jar应包含唯一Sentinel core");
        sentinelCoreDirectory = Files.createTempDirectory("cm16t3_order_core_");
        sentinelCoreJar = sentinelCoreDirectory.resolve("sentinel-core.jar");
        try (var input = jar.getInputStream(entries.get(0))) {
          Files.copy(input, sentinelCoreJar);
        }
        classpath.add(sentinelCoreJar.toUri().toURL());
      }
    }
    orderClasses =
        new URLClassLoader(
            classpath.toArray(java.net.URL[]::new),
            PayTccOrderEvidenceMySqlIT.class.getClassLoader());
    orderSource =
        new JdbcTemplate(
            new DriverManagerDataSource(
                shanghai(url.replace("/cloudmall_pay", "/cloudmall_order")), "root", "root"));
    orderFingerprint = fingerprint(orderSource, "cloudmall_order");
    orderDatabase = "cm16t3_order_" + UUID.randomUUID().toString().replace("-", "");
    orderSource.execute(
        "CREATE DATABASE " + orderDatabase + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    DataSource orderDataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_pay", "/" + orderDatabase)), "root", "root");
    orderFixture = new JdbcTemplate(orderDataSource);
    orderTracked = new TrackingDataSource(orderDataSource, false);
    ensureOrderTables();
    Object sqlMapper =
        orderClasses
            .loadClass("com.cloudmall.order.mapper.OrderSqlMapper")
            .getConstructor(JdbcTemplate.class)
            .newInstance(new JdbcTemplate(orderTracked));
    Class<?> queryContract =
        orderClasses.loadClass("com.cloudmall.order.service.OrderDetailQueryService");
    Object queryService =
        orderClasses
            .loadClass("com.cloudmall.order.service.impl.OrderDetailQueryServiceImpl")
            .getConstructor(
                orderClasses.loadClass("com.cloudmall.order.mapper.OrderSqlMapper"),
                ObjectMapper.class,
                orderClasses.loadClass("com.cloudmall.order.mapper.OrderStockActionMapper"),
                orderClasses.loadClass("com.cloudmall.order.feign.StockClient"))
            .newInstance(sqlMapper, new ObjectMapper(), null, null);
    Object controller =
        orderClasses
            .loadClass("com.cloudmall.order.controller.OrderDetailQueryController")
            .getConstructor(queryContract)
            .newInstance(queryService);
    Jackson2ObjectMapperBuilder moneyBuilder = new Jackson2ObjectMapperBuilder();
    new CommonWebConfiguration().moneyAsString().customize(moneyBuilder);
    orderMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setMessageConverters(new MappingJackson2HttpMessageConverter(moneyBuilder.build()))
            .addInterceptors(new AuthInterceptor())
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  /** 只开放真实订单详情GET，使用全局Feign拦截器与默认不重试语义。 */
  private static void configureTransport() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "cm16t3-owned-order-http");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(serverExecutor);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())
                || !exchange.getRequestURI().getPath().startsWith("/api/orders/")) {
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
              var result = orderMvc.perform(request).andReturn().getResponse();
              byte[] body = result.getContentAsByteArray();
              if (result.getStatus() == 200 && responseMutation != null) {
                ObjectMapper mutationMapper = new ObjectMapper();
                var envelope = mutationMapper.readTree(body);
                var observed =
                    (com.fasterxml.jackson.databind.node.ObjectNode) envelope.get("data");
                switch (responseMutation) {
                  case "textId" -> observed.put("userId", "7");
                  case "floatId" -> observed.put("userId", 7.9);
                  case "numberMoney" ->
                      observed.put("payAmount", new java.math.BigDecimal("10.00"));
                  case "moneyScale" -> observed.put("payAmount", "10.001");
                  case "statusType" -> observed.put("status", 1);
                  case "validTail" -> observed.put("payAmount", "10.0000");
                  default -> throw new AssertionError(responseMutation);
                }
                body = mutationMapper.writeValueAsBytes(envelope);
              }
              exchange.getResponseHeaders().add("Content-Type", "application/json");
              exchange.sendResponseHeaders(result.getStatus(), body.length);
              exchange.getResponseBody().write(body);
              assertNull(AuthContext.userId());
              assertNull(RootContext.getXID());
            } catch (Throwable failure) {
              transportAssertion.set(failure);
              exchange.sendResponseHeaders(500, -1);
            }
          }
        });
    server.start();
    ObjectMapper json =
        new ObjectMapper()
            .disable(
                com.fasterxml.jackson.databind.DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES);
    OrderBasicEvidenceClient http =
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
            .retryer(feign.Retryer.NEVER_RETRY)
            .options(new feign.Request.Options(3000, 5000))
            .target(
                OrderBasicEvidenceClient.class,
                "http://127.0.0.1:" + server.getAddress().getPort());
    OrderBasicEvidenceClient onlyGet =
        (OrderBasicEvidenceClient)
            Proxy.newProxyInstance(
                OrderBasicEvidenceClient.class.getClassLoader(),
                new Class<?>[] {OrderBasicEvidenceClient.class},
                (proxy, method, arguments) -> {
                  assertEquals("get", method.getName());
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                  assertNull(RootContext.getXID());
                  assertNull(AuthContext.userId());
                  try {
                    return method.invoke(http, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  } finally {
                    Runnable hook = afterOrderRead.getAndSet(null);
                    if (hook != null) hook.run();
                  }
                });
    collector = new PayTccOrderEvidenceService(service, reader, onlyGet, true);
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
    orderTracked.checking = false;
    remoteUnavailable = false;
    responseMutation = null;
    afterOrderRead.set(null);
    transportAssertion.set(null);
    receivedCallerIds.set(null);
    receivedCallerRole.set(null);
    getRequests.set(0);
    writeRequests.set(0);
    seed("COMMITTED");
    seedOrder("PAID");
    tracked.reset();
    orderTracked.reset();
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (server != null) server.stop(0);
    if (serverExecutor != null) serverExecutor.shutdownNow();
    if (orderClasses != null) orderClasses.close();
    if (sentinelCoreJar != null) Files.deleteIfExists(sentinelCoreJar);
    if (sentinelCoreDirectory != null) Files.deleteIfExists(sentinelCoreDirectory);
    if (tracked != null) tracked.checking = false;
    if (orderTracked != null) orderTracked.checking = false;
    try {
      if (source != null) assertEquals(sourceFingerprint, originalFingerprint());
      if (orderSource != null)
        assertEquals(orderFingerprint, fingerprint(orderSource, "cloudmall_order"));
    } finally {
      dropOwned(source, database, "cm16t3_pay_");
      dropOwned(orderSource, orderDatabase, "cm16t3_order_");
    }
    System.out.println(
        "CM16T3 original Pay/Order checksums unchanged; owned databases, core temp file and HTTP"
            + " removed");
  }

  @Test
  void actualFeignNativeProofRejectsCoercibleWireTokensWithoutChangingSources() {
    for (String mutation :
        List.of("textId", "floatId", "numberMoney", "moneyScale", "statusType", "validTail")) {
      responseMutation = mutation;
      var payBefore = facts();
      var orderBefore = orderFacts();
      tracked.reset();
      orderTracked.reset();
      getRequests.set(0);
      enableChecks();
      var report = inspectOrder();
      assertEquals(
          "validTail".equals(mutation) ? "BLOCKED_REMAINING_EVIDENCE" : "ORDER_EVIDENCE_INCOMPLETE",
          report.classification,
          mutation);
      assertEquals("validTail".equals(mutation), report.orderEvidence.identityMatched());
      if (List.of("textId", "floatId").contains(mutation))
        assertNull(report.orderEvidence.userIdSha256());
      if (List.of("numberMoney", "moneyScale").contains(mutation))
        assertNull(report.orderEvidence.payAmountSha256());
      assertEquals(1, getRequests.get());
      assertEquals(12, tracked.statements.size());
      assertEquals(2, tracked.connections);
      assertEquals(2, orderTracked.statements.size());
      assertEquals(2, orderTracked.connections);
      assertFalse(report.automaticActionsAllowed);
      assertUnchanged(payBefore, orderBefore);
    }
  }

  @Test
  void fourOrderStatesWithCommittedPayOnlyMatchBasicIdentityAndPreserveEveryRow() {
    for (String state : List.of("PENDING_PAYMENT", "PAID", "COMPLETED", "CANCELLED")) {
      orderTracked.checking = false;
      seedOrder(state);
      var payBefore = facts();
      var orderBefore = orderFacts();
      tracked.reset();
      orderTracked.reset();
      enableChecks();
      var report = inspectOrder();
      assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification, state);
      assertEquals(state, report.orderEvidence.state());
      assertTrue(report.orderEvidence.identityMatched());
      assertEquals(List.of("USER", "STOCK", "TC", "MQ"), report.externalSystemsNotQueried);
      assertFalse(report.automaticActionsAllowed);
      assertEquals(2, tracked.connections);
      assertEquals(12, tracked.statements.size());
      assertEquals(2, orderTracked.connections);
      assertEquals(2, orderTracked.statements.size());
      assertUnchanged(payBefore, orderBefore);
    }
  }

  @Test
  void unknownStateAndWrongAmountAreIncompleteAndConflictWithoutWrites() {
    for (String variant : List.of("state", "amount")) {
      orderTracked.checking = false;
      seedOrder("PAID");
      if ("state".equals(variant))
        orderFixture.update("UPDATE mall_order_202610 SET status='UNRECOGNIZED'");
      else orderFixture.update("UPDATE mall_order_202610 SET pay_amount=11");
      var payBefore = facts();
      var orderBefore = orderFacts();
      enableChecks();
      assertEquals(
          "state".equals(variant) ? "ORDER_EVIDENCE_INCOMPLETE" : "ORDER_EVIDENCE_CONFLICT",
          inspectOrder().classification);
      assertUnchanged(payBefore, orderBefore);
    }
  }

  @Test
  void missingOrderWrongOwnerAndCollationAliasCannotBeUsedAsEvidence() {
    for (String variant : List.of("missing", "owner", "alias")) {
      orderTracked.checking = false;
      seedOrder("PAID");
      if ("missing".equals(variant)) orderFixture.update("DELETE FROM mall_order_202610");
      if ("owner".equals(variant)) orderFixture.update("UPDATE mall_order_202610 SET user_id=8");
      if ("alias".equals(variant))
        orderFixture.update("UPDATE mall_order_202610 SET order_no='202610ORDER1'");
      var payBefore = facts();
      var orderBefore = orderFacts();
      enableChecks();
      assertEquals("ORDER_EVIDENCE_UNAVAILABLE", inspectOrder().classification, variant);
      assertUnchanged(payBefore, orderBefore);
    }
  }

  @Test
  void missingMonthTableAndRemote503StayUnavailableAndNeverRepairSchema() {
    var payBefore = facts();
    var orderBefore = orderFacts();
    remoteUnavailable = true;
    enableChecks();
    assertEquals("ORDER_EVIDENCE_UNAVAILABLE", inspectOrder().classification);
    assertEquals(1, getRequests.get());
    assertEquals(0, orderTracked.connections);
    assertUnchanged(payBefore, orderBefore);
    remoteUnavailable = false;
    orderTracked.checking = false;
    orderFixture.execute("DROP TABLE mall_order_202610");
    enableChecks();
    assertEquals("ORDER_EVIDENCE_UNAVAILABLE", inspectOrder().classification);
    assertEquals(
        0,
        orderFixture.queryForObject(
            "SELECT COUNT(*) FROM information_schema.tables WHERE table_schema=? AND"
                + " table_name='mall_order_202610'",
            Integer.class,
            orderDatabase));
    assertEquals(payBefore, facts());
    assertEquals(0, writeRequests.get());
    assertNull(transportAssertion.get());
  }

  @Test
  void callerIdentityIsIsolatedOnTheWireAndRestoredOnSuccessAndFailure() {
    for (boolean failure : List.of(false, true)) {
      remoteUnavailable = failure;
      var payBefore = facts();
      var orderBefore = orderFacts();
      enableChecks();
      AuthContext.set(99L, "ADMIN");
      try {
        assertEquals(
            failure ? "ORDER_EVIDENCE_UNAVAILABLE" : "BLOCKED_REMAINING_EVIDENCE",
            inspectOrder().classification);
        assertEquals(List.of("7"), receivedCallerIds.get());
        assertNull(receivedCallerRole.get());
        assertEquals(99L, AuthContext.userId());
        assertEquals("ADMIN", AuthContext.role());
      } finally {
        AuthContext.clear();
      }
      assertUnchanged(payBefore, orderBefore);
    }
  }

  @Test
  void payChangeDuringOrderRequestDiscardsBothSuccessfulAndUnavailableEvidence() {
    for (boolean failure : List.of(false, true)) {
      tracked.checking = false;
      seed("COMMITTED");
      remoteUnavailable = failure;
      var orderBefore = orderFacts();
      afterOrderRead.set(() -> fixture.update("UPDATE pay_record SET status='SUCCESS'"));
      enableChecks();
      assertEquals("LOCAL_EVIDENCE_CHANGED", inspectOrder().classification);
      assertEquals(orderBefore, orderFacts());
      assertEquals(
          "SUCCESS", fixture.queryForObject("SELECT status FROM pay_record", String.class));
      assertEquals(0, writeRequests.get());
      assertNull(transportAssertion.get());
    }
  }

  @Test
  void untrustedPaySkipsOrderAndActualHttpReportIsNoStoreWithOnlyBasicSummary() throws Exception {
    fixture.update("UPDATE pay_tcc_execution SET state='UNKNOWN'");
    var payBefore = facts();
    var orderBefore = orderFacts();
    enableChecks();
    assertFalse(inspectOrder().orderEvidence.queryAttempted());
    assertEquals(0, getRequests.get());
    assertUnchanged(payBefore, orderBefore);
    tracked.checking = false;
    seed("COMMITTED");
    enableChecks();
    var mvc =
        MockMvcBuilders.standaloneSetup(new PayTccOrderEvidenceController(collector))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String body =
        mvc.perform(
                get("/api/internal/payments/pay1/tcc-reconciliation/order-evidence")
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret")
                    .param("orderNo", "other")
                    .param("userId", "8")
                    .param("xid", "injected"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.scope").value("PAY_ORDER_BASIC_ONLY"))
            .andExpect(jsonPath("$.data.classification").value("BLOCKED_REMAINING_EVIDENCE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String raw :
        List.of(
            "tc-private:8091:original",
            "tcc-debit:pay1",
            "ops-secret",
            "stockActionStatus",
            "userEvidence",
            "addressSnapshot",
            "items",
            "private-error-password")) assertFalse(body.contains(raw), raw);
    assertEquals(0, writeRequests.get());
    assertNull(transportAssertion.get());
  }

  @Test
  void actualOrderAuthInterceptorRejectsInvalidOrderHeaderBeforeDatabaseRead() throws Exception {
    enableChecks();
    orderMvc
        .perform(get("/api/orders/202610order1").header("X-User-Id", "bad"))
        .andExpect(status().isUnauthorized());
    assertEquals(0, orderTracked.connections);
    orderMvc
        .perform(get("/api/orders/202610order1").header("X-User-Id", "8"))
        .andExpect(status().isForbidden());
  }

  /** 仅创建本测试owned月表，查询期间不调用。 */
  private static void ensureOrderTables() {
    orderFixture.execute(
        "CREATE TABLE IF NOT EXISTS mall_order_202610 LIKE cloudmall_order.mall_order_template");
    orderFixture.execute(
        "CREATE TABLE IF NOT EXISTS mall_order_item_202610 LIKE"
            + " cloudmall_order.mall_order_item_template");
  }

  /** owned订单与明细是同一原载荷；不创建库存动作或付款状态。 */
  private static void seedOrder(String state) {
    orderTracked.checking = false;
    ensureOrderTables();
    for (String table : ORDER_TABLES) orderFixture.update("DELETE FROM " + table);
    orderFixture.update(
        "INSERT INTO"
            + " mall_order_202610(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
            + " VALUES(1,'202610order1',7,?,10,10,'{}','2026-10-10 13:00:00.123','2026-10-10"
            + " 12:00:00.123','2026-10-10 12:00:00.123')",
        state);
  }

  /** 真实内部采集请求，仅使用原payNo。 */
  private PayTccOrderReconciliationVO inspectOrder() {
    return collector.inspect("pay1", "cm18-reconciliation", "ops-secret");
  }

  /** 两端只允许无锁SELECT；Pay还必须真实只读RR。 */
  private static void enableChecks() {
    tracked.checking = true;
    orderTracked.checking = true;
  }

  /** 完整订单主行/明细事实，不只是报告中的四个字段。 */
  private static List<List<Map<String, Object>>> orderFacts() {
    return ORDER_TABLES.stream()
        .map(table -> orderFixture.queryForList("SELECT * FROM " + table))
        .toList();
  }

  /** 核验全部持久行和线上无写请求。 */
  private static void assertUnchanged(Object payBefore, Object orderBefore) {
    assertEquals(payBefore, facts());
    assertEquals(orderBefore, orderFacts());
    assertEquals(0, writeRequests.get());
    assertNull(transportAssertion.get());
  }

  /** 名称校验后只删除owned库。 */
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

  /** 原库表清单及checksum只读核对。 */
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

  /** 用原字段准备本地完整终态；不注册任何TC分支或真实Order操作。 */
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

  /** Pay SELECT验证真实只读RR；Order既有GET只验证无写/无锁，不声称RR。 */
  private static final class TrackingDataSource extends AbstractDataSource {
    /** owned库基础连接来源。 */
    final DataSource delegate;

    /** Pay验证真实RR，Order现有公开GET仅断言无锁SELECT。 */
    final boolean requireReadOnlyRr;

    /** 仅被测请求阶段启用写入/事务断言。 */
    boolean checking;

    /** 被测请求取得的实际连接数。 */
    int connections;

    /** 本次请求实际准备的SQL。 */
    final List<String> statements = new ArrayList<>();

    TrackingDataSource(DataSource delegate) {
      this(delegate, true);
    }

    TrackingDataSource(DataSource delegate, boolean requireReadOnlyRr) {
      this.delegate = delegate;
      this.requireReadOnlyRr = requireReadOnlyRr;
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
                  if (requireReadOnlyRr) {
                    assertTrue(connection.isReadOnly());
                    assertEquals(
                        Connection.TRANSACTION_REPEATABLE_READ,
                        connection.getTransactionIsolation());
                  }
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
