package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import com.baomidou.mybatisplus.core.MybatisConfiguration;
import com.baomidou.mybatisplus.extension.spring.MybatisSqlSessionFactoryBean;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.web.GlobalExceptionHandler;
import com.cloudmall.pay.config.FeignAuthConfiguration;
import com.cloudmall.pay.controller.PayTccStockEvidenceController;
import com.cloudmall.pay.domain.vo.PayTccStockReconciliationVO;
import com.cloudmall.pay.feign.StockEvidenceClient;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.impl.PayTccReconciliationReadService;
import com.cloudmall.pay.service.impl.PayTccReconciliationService;
import com.cloudmall.pay.service.impl.PayTccStockEvidenceService;
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
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.AbstractDataSource;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 两个owned库及真实Stock GET/Feign验证；只监听随机loopback端口，finally精确清理。 */
class PayTccStockEvidenceMySqlIT {
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

  /** 原Stock库只读指纹和owned库。 */
  private static JdbcTemplate stockSource, stockFixture;

  /** 原Stock表清单及校验和。 */
  private static List<Map<String, Object>> stockFingerprint;

  /** 仅当前IT拥有的Order库名。 */
  private static String stockDatabase;

  /** 真实Stock生产类的隔离加载器。 */
  private static URLClassLoader stockClasses;

  /** Stock既有GET的SQL写入/锁监测数据源。 */
  private static TrackingDataSource stockTracked;

  /** 实际Stock Controller/Mapper只读边界。 */
  private static MockMvc stockMvc;

  /** 只监听随机loopback地址的owned HTTP server。 */
  private static HttpServer server;

  /** owned HTTP处理线程，cleanup关闭。 */
  private static ExecutorService serverExecutor;

  /** 真实Pay/Order证据采集编排器。 */
  private static PayTccStockEvidenceService collector;

  /** HTTP请求计数、测试故障和SQL断言传播。 */
  private static final AtomicInteger getRequests = new AtomicInteger(),
      writeRequests = new AtomicInteger();

  /** GET完成后仅测试在owned Pay库执行的受控writer钩子。 */
  private static final AtomicReference<Runnable> afterStockRead = new AtomicReference<>();

  /** 将服务端SQL/上下文断言传回主测试线程。 */
  private static final AtomicReference<Throwable> transportAssertion = new AtomicReference<>();

  /** 线上是否出现本不应传给Stock的调用方凭证/事务头。 */
  private static final java.util.concurrent.atomic.AtomicBoolean unexpectedContextHeader =
      new java.util.concurrent.atomic.AtomicBoolean();

  /** 模拟HTTP503而不调用Stock生产查询。 */
  private static volatile boolean remoteUnavailable;

  /** 真实线上Stock身份头，检验全局Feign拦截器不会污染持久锚点。 */
  private static final AtomicReference<List<String>> receivedCallerIds = new AtomicReference<>();

  /** 调用方角色不能混入内部Stock查询。 */
  private static final AtomicReference<String> receivedCallerRole = new AtomicReference<>();

  /** Stock查询的两张月表，本报告仅使用主行基本字段。 */
  private static final List<String> STOCK_TABLES =
      List.of(
          "stock_flow",
          "stock_reservation_request",
          "seckill_reservation",
          "stock_sku",
          "stock_cache_repair");

  /** 仅当父classpath缺少Stock已有Sentinel依赖时，加载其标准Boot jar中的core。 */
  private static Path sentinelCoreDirectory, sentinelCoreJar;

  /** Stock已有的Redis类型仅供生产类反射加载，不创建客户端或连接。 */
  private static final List<Path> stockDependencyJars = new ArrayList<>();

  @BeforeAll
  static void setup() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_pay URL");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghai(url), "root", "root"));
    sourceFingerprint = originalFingerprint();
    setupStock(url);
    database = "cm16t7_pay_" + UUID.randomUUID().toString().replace("-", "");
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

  /** 真实Stock Controller/get/Mapper；不实例化Boot应用、RPC、库存动作或调度器。 */
  private static void setupStock(String url) throws Exception {
    Path classes = Path.of("../cloud-mall-stock/target/classes").toAbsolutePath().normalize();
    if (!Files.isDirectory(classes)) throw new IllegalStateException("先构建标准Stock classes/Boot jar");
    List<java.net.URL> classpath = new ArrayList<>();
    classpath.add(classes.toUri().toURL());
    try {
      Class.forName(
          "com.alibaba.csp.sentinel.Entry",
          false,
          PayTccStockEvidenceMySqlIT.class.getClassLoader());
    } catch (ClassNotFoundException absentFromPayClasspath) {
      // 使用Stock已经声明的固定版本依赖；无生产依赖或用户专属路径。
      Path artifact = Path.of("../cloud-mall-stock/target/cloud-mall-stock-0.1.0-SNAPSHOT.jar");
      try (JarFile jar = new JarFile(artifact.toFile())) {
        var entries =
            jar.stream()
                .filter(
                    entry ->
                        entry.getName().startsWith("BOOT-INF/lib/sentinel-core-")
                            && entry.getName().endsWith(".jar"))
                .toList();
        if (entries.size() != 1)
          throw new IllegalStateException("Stock Boot jar应包含唯一Sentinel core");
        sentinelCoreDirectory = Files.createTempDirectory("cm16t7_stock_core_");
        sentinelCoreJar = sentinelCoreDirectory.resolve("sentinel-core.jar");
        try (var input = jar.getInputStream(entries.get(0))) {
          Files.copy(input, sentinelCoreJar);
        }
        classpath.add(sentinelCoreJar.toUri().toURL());
      }
    }
    try (JarFile jar =
        new JarFile(
            Path.of("../cloud-mall-stock/target/cloud-mall-stock-0.1.0-SNAPSHOT.jar").toFile())) {
      for (String[] dependency :
          List.of(
              new String[] {
                "org.springframework.data.redis.core.script.RedisScript", "spring-data-redis-"
              },
              new String[] {
                "org.springframework.data.keyvalue.core.KeyValueOperations", "spring-data-keyvalue-"
              },
              new String[] {
                "org.springframework.data.util.CloseableIterator", "spring-data-commons-"
              })) {
        try {
          Class.forName(dependency[0], false, PayTccStockEvidenceMySqlIT.class.getClassLoader());
        } catch (ClassNotFoundException absent) {
          var matches =
              jar.stream()
                  .filter(
                      entry ->
                          entry.getName().startsWith("BOOT-INF/lib/" + dependency[1])
                              && entry.getName().endsWith(".jar"))
                  .toList();
          if (matches.size() != 1)
            throw new IllegalStateException("Stock artifact须包含唯一已声明依赖:" + dependency[1]);
          if (sentinelCoreDirectory == null)
            sentinelCoreDirectory = Files.createTempDirectory("cm16t7_stock_deps_");
          Path dependencyFile = sentinelCoreDirectory.resolve(dependency[1] + "existing.jar");
          try (var input = jar.getInputStream(matches.get(0))) {
            Files.copy(input, dependencyFile);
          }
          stockDependencyJars.add(dependencyFile);
          classpath.add(dependencyFile.toUri().toURL());
        }
      }
    }
    stockClasses =
        new URLClassLoader(
            classpath.toArray(java.net.URL[]::new),
            PayTccStockEvidenceMySqlIT.class.getClassLoader());
    stockSource =
        new JdbcTemplate(
            new DriverManagerDataSource(
                shanghai(url.replace("/cloudmall_pay", "/cloudmall_stock")), "root", "root"));
    stockFingerprint = fingerprint(stockSource, "cloudmall_stock");
    stockDatabase = "cm16t7_stock_" + UUID.randomUUID().toString().replace("-", "");
    stockSource.execute(
        "CREATE DATABASE " + stockDatabase + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    DataSource stockDataSource =
        new DriverManagerDataSource(
            shanghai(url.replace("/cloudmall_pay", "/" + stockDatabase)), "root", "root");
    stockFixture = new JdbcTemplate(stockDataSource);
    stockTracked = new TrackingDataSource(stockDataSource, false);
    ensureStockTables();
    Object sqlMapper =
        stockClasses
            .loadClass("com.cloudmall.stock.mapper.StockSqlMapper")
            .getConstructor(JdbcTemplate.class)
            .newInstance(new JdbcTemplate(stockTracked));
    var sqlType = stockClasses.loadClass("com.cloudmall.stock.mapper.StockSqlMapper");
    var settlementType =
        stockClasses.loadClass("com.cloudmall.stock.service.impl.SeckillQuotaSettlementService");
    var codecType =
        stockClasses.loadClass("com.cloudmall.stock.service.impl.StockReservationSnapshotCodec");
    var readerType =
        stockClasses.loadClass("com.cloudmall.stock.service.impl.StockReservationReader");
    var queryType =
        stockClasses.loadClass("com.cloudmall.stock.service.impl.StockQueryServiceImpl");
    var stockJson = new ObjectMapper();
    Object codec = codecType.getConstructor(ObjectMapper.class).newInstance(stockJson);
    Object stockReader =
        readerType
            .getConstructor(sqlType, ObjectMapper.class, settlementType, codecType)
            .newInstance(sqlMapper, stockJson, null, codec);
    Object query =
        queryType
            .getConstructor(
                stockClasses.loadClass("org.springframework.data.redis.core.StringRedisTemplate"),
                sqlType,
                readerType)
            .newInstance(null, sqlMapper, stockReader);
    Object controller =
        stockClasses
            .loadClass("com.cloudmall.stock.controller.StockController")
            .getConstructor(
                stockClasses.loadClass("com.cloudmall.stock.service.StockQueryService"),
                stockClasses.loadClass("com.cloudmall.stock.service.StockReservationService"),
                stockClasses.loadClass("com.cloudmall.stock.service.SeckillReservationService"))
            .newInstance(query, null, null);
    stockMvc =
        MockMvcBuilders.standaloneSetup(controller)
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
  }

  /** 只开放真实Stock预扣GET，使用全局Feign拦截器与默认不重试语义。 */
  private static void configureTransport() throws Exception {
    server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
    serverExecutor =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "cm16t7-owned-stock-http");
              thread.setDaemon(true);
              return thread;
            });
    server.setExecutor(serverExecutor);
    server.createContext(
        "/",
        exchange -> {
          try (exchange) {
            if (!"GET".equals(exchange.getRequestMethod())
                || !exchange.getRequestURI().getPath().startsWith("/api/stock/reservations/")) {
              writeRequests.incrementAndGet();
              exchange.sendResponseHeaders(405, -1);
              return;
            }
            getRequests.incrementAndGet();
            unexpectedContextHeader.set(
                List.of("X-Internal-Callback-Token", "X-Internal-Service", "TX_XID").stream()
                    .anyMatch(name -> exchange.getRequestHeaders().containsKey(name)));
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
              var result = stockMvc.perform(request).andReturn().getResponse();
              byte[] body = result.getContentAsByteArray();
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
    StockEvidenceClient http =
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
            .target(StockEvidenceClient.class, "http://127.0.0.1:" + server.getAddress().getPort());
    StockEvidenceClient onlyGet =
        (StockEvidenceClient)
            Proxy.newProxyInstance(
                StockEvidenceClient.class.getClassLoader(),
                new Class<?>[] {StockEvidenceClient.class},
                (proxy, method, arguments) -> {
                  assertEquals("reservation", method.getName());
                  assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
                  assertNull(RootContext.getXID());
                  assertNull(AuthContext.userId());
                  try {
                    return method.invoke(http, arguments);
                  } catch (InvocationTargetException failure) {
                    throw failure.getCause();
                  } finally {
                    Runnable hook = afterStockRead.getAndSet(null);
                    if (hook != null) hook.run();
                  }
                });
    collector = new PayTccStockEvidenceService(service, reader, onlyGet, true);
  }

  @BeforeEach
  void reset() {
    tracked.checking = false;
    stockTracked.checking = false;
    remoteUnavailable = false;
    unexpectedContextHeader.set(false);
    afterStockRead.set(null);
    transportAssertion.set(null);
    receivedCallerIds.set(null);
    receivedCallerRole.set(null);
    getRequests.set(0);
    writeRequests.set(0);
    seed("COMMITTED");
    seedStock("CONFIRMED");
    tracked.reset();
    stockTracked.reset();
  }

  @AfterAll
  static void cleanup() throws Exception {
    if (server != null) server.stop(0);
    if (serverExecutor != null) serverExecutor.shutdownNow();
    if (stockClasses != null) stockClasses.close();
    if (sentinelCoreJar != null) Files.deleteIfExists(sentinelCoreJar);
    for (Path dependency : stockDependencyJars) Files.deleteIfExists(dependency);
    if (sentinelCoreDirectory != null) Files.deleteIfExists(sentinelCoreDirectory);
    if (tracked != null) tracked.checking = false;
    if (stockTracked != null) stockTracked.checking = false;
    try {
      if (source != null) assertEquals(sourceFingerprint, originalFingerprint());
      if (stockSource != null)
        assertEquals(stockFingerprint, fingerprint(stockSource, "cloudmall_stock"));
    } finally {
      dropOwned(source, database, "cm16t7_pay_");
      dropOwned(stockSource, stockDatabase, "cm16t7_stock_");
    }
    System.out.println(
        "CM16T7 original Pay/Stock checksums unchanged; owned databases, HTTP and loader files"
            + " removed");
  }

  @Test
  void actualStockThreeStatesAreOnlyObservationsAndPreserveAllOriginalRows() {
    for (String state : List.of("RESERVED", "CONFIRMED", "ROLLED_BACK")) {
      stockTracked.checking = false;
      seedStock(state);
      var payBefore = facts();
      var stockBefore = stockFacts();
      tracked.reset();
      stockTracked.reset();
      enableChecks();
      var report = inspectStock();
      assertEquals("BLOCKED_REMAINING_EVIDENCE", report.classification, state);
      assertEquals(state, report.stockEvidence.state());
      assertEquals(2, report.stockEvidence.observedItemCount());
      assertNotNull(report.stockEvidence.itemVectorSha256());
      assertFalse(report.automaticActionsAllowed);
      assertEquals(2, tracked.connections);
      assertEquals(12, tracked.statements.size());
      assertEquals(3, stockTracked.statements.size());
      assertUnchanged(payBefore, stockBefore);
    }
  }

  @Test
  void actualMissingPartialAndAliasedStockCannotAuthorizeInventoryConclusion() {
    for (String variant : List.of("missing", "partial", "alias", "snapshot")) {
      tracked.checking = false;
      stockTracked.checking = false;
      seedStock("CONFIRMED");
      if ("missing".equals(variant))
        for (String table : List.of("stock_flow", "stock_reservation_request"))
          stockFixture.update("DELETE FROM " + table);
      if ("partial".equals(variant))
        stockFixture.update("DELETE FROM stock_flow WHERE sku_id=8 AND flow_type='CONFIRM'");
      if ("alias".equals(variant))
        stockFixture.update("UPDATE stock_flow SET order_no=UPPER(order_no)");
      if ("snapshot".equals(variant))
        stockFixture.update("UPDATE stock_reservation_request SET items_hash=?", "0".repeat(64));
      var payBefore = facts();
      var stockBefore = stockFacts();
      enableChecks();
      var report = inspectStock();
      assertEquals("STOCK_EVIDENCE_INCOMPLETE", report.classification, variant);
      assertFalse(report.stockEvidence.identityMatched());
      assertNull(report.stockEvidence.itemVectorSha256());
      assertUnchanged(payBefore, stockBefore);
    }
  }

  @Test
  void unavailableStockRecapturesPayWithoutRetryOrAnyWrite() {
    remoteUnavailable = true;
    var payBefore = facts();
    var stockBefore = stockFacts();
    enableChecks();
    assertEquals("STOCK_EVIDENCE_UNAVAILABLE", inspectStock().classification);
    assertEquals(1, getRequests.get());
    assertEquals(2, tracked.connections);
    assertEquals(0, stockTracked.connections);
    assertUnchanged(payBefore, stockBefore);
  }

  @Test
  void callerIdentityAndCredentialHeadersDoNotEnterStockGetAndContextIsRestored() {
    for (boolean failure : List.of(false, true)) {
      remoteUnavailable = failure;
      var payBefore = facts();
      var stockBefore = stockFacts();
      enableChecks();
      AuthContext.set(99L, "ADMIN");
      try {
        assertEquals(
            failure ? "STOCK_EVIDENCE_UNAVAILABLE" : "BLOCKED_REMAINING_EVIDENCE",
            inspectStock().classification);
        assertNull(receivedCallerIds.get());
        assertNull(receivedCallerRole.get());
        assertFalse(unexpectedContextHeader.get());
        assertEquals(99L, AuthContext.userId());
        assertEquals("ADMIN", AuthContext.role());
      } finally {
        AuthContext.clear();
      }
      assertUnchanged(payBefore, stockBefore);
    }
  }

  @Test
  void payChangesDuringEitherSuccessfulOrFailedStockGetDiscardRemoteConclusion() {
    for (boolean failure : List.of(false, true)) {
      tracked.checking = false;
      seed("COMMITTED");
      remoteUnavailable = failure;
      var stockBefore = stockFacts();
      afterStockRead.set(() -> fixture.update("UPDATE pay_record SET status='SUCCESS'"));
      enableChecks();
      var report = inspectStock();
      assertEquals("LOCAL_EVIDENCE_CHANGED", report.classification);
      assertNull(report.stockEvidence.itemVectorSha256());
      assertEquals(stockBefore, stockFacts());
      assertEquals(
          "SUCCESS", fixture.queryForObject("SELECT status FROM pay_record", String.class));
      assertEquals(0, writeRequests.get());
      assertNull(transportAssertion.get());
    }
  }

  @Test
  void noTrustedAnchorSkipsStockAndHttpReportsNeverExposeRawVectorOrCredentials() throws Exception {
    fixture.update("UPDATE pay_tcc_execution SET state='UNKNOWN'");
    var payBefore = facts();
    var stockBefore = stockFacts();
    enableChecks();
    assertFalse(inspectStock().stockEvidence.queryAttempted());
    assertEquals(0, getRequests.get());
    assertUnchanged(payBefore, stockBefore);
    tracked.checking = false;
    seed("COMMITTED");
    enableChecks();
    var mvc =
        MockMvcBuilders.standaloneSetup(new PayTccStockEvidenceController(collector))
            .setControllerAdvice(new GlobalExceptionHandler())
            .build();
    String body =
        mvc.perform(
                get("/api/internal/payments/pay1/tcc-reconciliation/stock-evidence")
                    .header("X-Internal-Service", "cm18-reconciliation")
                    .header("X-Internal-Callback-Token", "ops-secret")
                    .param("orderNo", "other")
                    .param("skuId", "999")
                    .param("xid", "injected"))
            .andExpect(status().isOk())
            .andExpect(header().string("Cache-Control", "no-store"))
            .andExpect(jsonPath("$.data.scope").value("PAY_STOCK_OBSERVED_ONLY"))
            .andExpect(jsonPath("$.data.classification").value("BLOCKED_REMAINING_EVIDENCE"))
            .andReturn()
            .getResponse()
            .getContentAsString();
    for (String raw :
        List.of(
            "tc-private:8091:original",
            "tcc-debit:pay1",
            "ops-secret",
            "\"skuId\"",
            "\"quantity\"",
            "items_snapshot",
            "items_hash",
            "private-error-password")) assertFalse(body.contains(raw), raw);
    assertEquals(0, writeRequests.get());
    assertNull(transportAssertion.get());
  }

  /** owned结构只在测试准备创建，GET期间禁止DDL。 */
  private static void ensureStockTables() {
    for (String table : STOCK_TABLES)
      stockFixture.execute(
          "CREATE TABLE IF NOT EXISTS " + table + " LIKE cloudmall_stock." + table);
  }

  /** 准备原同向普通流水和canonical request，绝不调用Stock写服务。 */
  private static void seedStock(String state) {
    stockTracked.checking = false;
    for (String table : STOCK_TABLES) stockFixture.update("DELETE FROM " + table);
    long flowId = 1;
    for (long skuId : List.of(7L, 8L)) {
      int quantity = skuId == 7 ? 2 : 3;
      stockFixture.update(
          "INSERT INTO stock_flow(id,sku_id,order_no,flow_type,quantity,idempotency_key,created_at)"
              + " VALUES(?,?,'202610order1','RESERVE',?,?,'2026-10-10 04:00:00.123')",
          flowId++,
          skuId,
          quantity,
          "stock-reserve-" + skuId);
      if (!"RESERVED".equals(state))
        stockFixture.update(
            "INSERT INTO"
                + " stock_flow(id,sku_id,order_no,flow_type,quantity,idempotency_key,created_at)"
                + " VALUES(?,?,'202610order1',?,?,?,'2026-10-10 04:00:00.123')",
            flowId++,
            skuId,
            "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK",
            quantity,
            "stock-settle-" + skuId);
    }
    String snapshot = "[{\"skuId\":7,\"quantity\":2},{\"skuId\":8,\"quantity\":3}]";
    try {
      String hash =
          java.util.HexFormat.of()
              .formatHex(
                  java.security.MessageDigest.getInstance("SHA-256")
                      .digest(snapshot.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
      stockFixture.update(
          "INSERT INTO"
              + " stock_reservation_request(id,order_no,scene,items_snapshot,items_hash,status,created_at,updated_at)"
              + " VALUES(1,'202610order1','NORMAL',?,?,?,'2026-10-10 04:00:00.123','2026-10-10"
              + " 04:00:00.123')",
          snapshot,
          hash,
          state);
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new AssertionError(impossible);
    }
  }

  /** 两端GET都只SELECT，Pay还需真正readOnly RR。 */
  private static void enableChecks() {
    tracked.checking = true;
    stockTracked.checking = true;
  }

  /** 固定本地运维凭证仅用于测试内部入口。 */
  private PayTccStockReconciliationVO inspectStock() {
    return collector.inspect("pay1", "cm18-reconciliation", "ops-secret");
  }

  /** 所有资金/库存/请求/时间/租约字段均保持。 */
  private static void assertUnchanged(Object payBefore, Object stockBefore) {
    assertEquals(payBefore, facts());
    assertEquals(stockBefore, stockFacts());
    assertEquals(0, writeRequests.get());
    assertNull(transportAssertion.get());
  }

  /** 完整五表事实包括未被GET触碰的库存/投影表。 */
  private static List<List<Map<String, Object>>> stockFacts() {
    return STOCK_TABLES.stream()
        .map(table -> stockFixture.queryForList("SELECT * FROM " + table))
        .toList();
  }

  /** 只删除名称严格校验后的owned库。 */
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

  /** 原库表清单及校验和。 */
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
