package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryVO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.PayTccTransactionClient;
import com.cloudmall.pay.service.impl.delivery.PayTccDeliveryScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import io.seata.core.model.GlobalStatus;
import io.seata.tm.TransactionManagerHolder;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 真实四服务交付：独立库、发现组、Rabbit vhost 和独立临时 Redis。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PayTccCancelIT {
  /** 原begin之前记录本场景TC原生日志的最小只读窗口。 */
  private PayTccTcBranchEvidence.Window tcEvidenceWindowStartedAt;

  /** 首次begin前同源时钟锚点的不可覆盖文件，供TC包绑定来源。 */
  private Path tcEvidenceAnchorArtifact;

  /** 所有夹具的独立命名前缀。 */
  private static String batch, month, payUrl, redisContainer;

  private static String gatewayUrl;
  private static Process gatewayProcess;
  private static Process replacementPay;
  private static java.util.concurrent.atomic.AtomicBoolean cancelRunning;
  private static com.cloudmall.pay.service.impl.PayTccCancelStore cancelStore;
  private static final String TOKEN = "cm16e-cancel-token";

  /** 原库只读及各隔离服务数据库。 */
  private static JdbcTemplate source, payDb, userDb, orderDb, stockDb;

  /** 各服务隔离库 JDBC 地址。 */
  private static final Map<String, String> urls = new LinkedHashMap<>();

  /** 证据目录，不随清理数据库丢失。 */
  private static Path artifacts;

  /** 主 Pay 真正的 Spring/Seata 进程。 */
  private static ConfigurableApplicationContext application;

  /** 清理前核验已提交支付的最终交付。 */
  private static PayTccDeliveryService delivery;

  /** 测试只暂停本机 Pay 的自动调度，另有专门自动用例。 */
  private static AtomicBoolean parentRunning;

  /** 隔离发现组的管理客户端。 */
  private static NamingService naming;

  /** 三个独立 User/Order/Stock JVM 和其 HTTP 代理。 */
  private static final Map<String, Process> children = new LinkedHashMap<>();

  private static final Map<String, Integer> ports = new LinkedHashMap<>();
  private static final Map<String, TccResponseProxy> proxies = new LinkedHashMap<>();

  /** 仅自有 User JVM 使用的 TC 通道；父 Pay TM/RM 仍直连原 TC。 */
  private static UserRmTcProxy userRmTcProxy;

  /** 原 Order8 桥接代表的固定场景，其他旧用例不继承本声明。 */
  private static final String RM_LINK_SCENARIO = "USER_RM_TC_LINK_LOSS_ORIGINAL_ROLLBACK";

  /** 本测试临时 Redis 的随机宿主机端口。 */
  private static int redisPort;

  /** HTTP 控制与业务请求，不替代 Pay 的真实 Feign 客户端。 */
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  /** 业务载荷与持久证据 JSON。 */
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /** 所有实际全局事务，清理前核对终态。 */
  private static final Set<String> xids = new HashSet<>();

  /** 原业务数据库的校验和。 */
  private static List<Map<String, Object>> originalChecksums;

  /** 发生未决资金时保留现场。 */
  private static boolean preserve;

  /** 当前用例的原支付与 SKU。 */
  private PayTccDispatchDTO request;

  private long skuId;

  /** 当前用例是否持有本机 scheduler 的测试暂停权。 */
  private boolean pauseOwned;

  /** 只有原 prepared-undecided 代表启用四阶段取证，其他方法沿用原清算链。 */
  private boolean phaseEvidenceRepresentative;

  /** 当前代表第一次实际 Try 后核定的原 execution/XID/两 branch，只在内存比较。 */
  private OriginalTcIdentity phaseEvidenceIdentity;

  /** 原回滚终态 TC 包只供两个回滚阶段使用，不能倒填自然 TRYING。 */
  private Path rollbackTcEvidence;

  /** 失败审计仅记录最后请求的阶段名称，不写异常正文或原凭证。 */
  private String lastEvidencePhase = "NOT_STARTED";

  /** 当前取证代表的固定场景；begin 响应丢失不得复用原资金回滚的声明。 */
  private String evidenceScenarioId = "CANCEL_ORIGINAL_ROLLBACK";

  /** 同一原事务的不可变内部身份，不序列化该记录中的原 XID/branch。 */
  private record OriginalTcIdentity(
      long executionId, String xid, long payBranch, long userBranch) {}

  @BeforeAll
  static void setup() throws Exception {
    userRmTcProxy = UserRmTcProxy.preflight();
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_user 测试 URL");
    source = jdbc(sourceUrl);
    originalChecksums = checksums();
    batch = "cm16c_" + UUID.randomUUID().toString().replace("-", "");
    month = YearMonth.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMM"));
    artifacts = Path.of("target", "cm16e-cancel", batch).toAbsolutePath();
    Files.createDirectories(artifacts);
    for (String service : List.of("user", "pay", "order", "stock")) {
      String database = batch + "_" + service;
      source.execute(
          "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
      String url = sourceUrl.replace("/cloudmall_user", "/" + database);
      urls.put(service, url);
      JdbcTemplate target = jdbc(url);
      for (String table :
          source.queryForList("SHOW TABLES FROM cloudmall_" + service, String.class)) {
        if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法测试表名");
        target.execute(
            (String)
                source
                    .queryForMap("SHOW CREATE TABLE cloudmall_" + service + "." + table)
                    .get("Create Table"));
      }
    }
    userDb = jdbc(urls.get("user"));
    payDb = jdbc(urls.get("pay"));
    orderDb = jdbc(urls.get("order"));
    stockDb = jdbc(urls.get("stock"));
    payUrl = urls.get("pay");
    redisContainer = batch + "-redis";
    docker(
        "run",
        "-d",
        "--name",
        redisContainer,
        "--label",
        "cloudmall.test=" + batch,
        "-p",
        "127.0.0.1::6379",
        "redis:7.0",
        "redis-server",
        "--requirepass",
        "root");
    redisPort = Integer.parseInt(docker("port", redisContainer, "6379").trim().split(":")[1]);
    rabbit("PUT", "/api/vhosts/" + batch, Map.of());
    rabbit(
        "PUT",
        "/api/permissions/" + batch + "/root",
        Map.of("configure", ".*", "write", ".*", "read", ".*"));
    Properties properties = new Properties();
    properties.put("serverAddr", "127.0.0.1:18848");
    properties.put("username", "root");
    properties.put("password", "root");
    naming = NacosFactory.createNamingService(properties);
    Files.writeString(
        artifacts.resolve("fixture.json"),
        JSON.writeValueAsString(
            Map.of(
                "group",
                batch,
                "databases",
                urls.keySet().stream().map(name -> batch + "_" + name).toList(),
                "redis",
                redisContainer,
                "rabbitVhost",
                batch)));
    userRmTcProxy.start();
    Files.writeString(
        artifacts.resolve("user-rm-tc-proxy-source.json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(userRmTcProxy.identity),
        StandardOpenOption.CREATE_NEW);
    startService("user");
    startService("stock");
    startService("order");
    application =
        new SpringApplicationBuilder(PayApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(payArguments());
    // 真实 Pay 启动后只读核验测试配置隔离，不能仅凭注解推定未被程序式启动扫描。
    List<String> m2Profiles =
        List.of("cm18-m2-routing", "cm18-m2-fork-admission", "cm18-m2-fork-migration");
    List<String> activeProfiles = Arrays.asList(application.getEnvironment().getActiveProfiles());
    for (String profile : m2Profiles)
      assertFalse(activeProfiles.contains(profile), "M2_TEST_PROFILE_MUST_REMAIN_INACTIVE");
    List<Class<?>> m2Configurations =
        List.of(
            PayDebitModeM2MySqlIT.RoutingConfiguration.class,
            PayDebitModeM2ForkProcess.AdmissionConfiguration.class,
            PayDebitModeM2ForkProcess.MigrationConfiguration.class);
    for (Class<?> configuration : m2Configurations)
      assertTrue(
          application.getBeansOfType(configuration).isEmpty(),
          "M2_TEST_CONFIGURATION_MUST_NOT_ENTER_PAY_CONTEXT");
    Files.writeString(
        artifacts.resolve("m2-test-configuration-isolation.json"),
        JSON.writerWithDefaultPrettyPrinter()
            .writeValueAsString(
                Map.of(
                    "runId",
                    batch,
                    "scope",
                    "ACTUAL_OWNED_PAY_STARTUP_ONLY",
                    "actualActiveProfiles",
                    activeProfiles,
                    "checkedM2Profiles",
                    m2Profiles,
                    "allThreeM2ProfilesInactive",
                    true,
                    "checkedM2ConfigurationTypes",
                    m2Configurations.stream().map(Class::getName).toList(),
                    "allThreeM2ConfigurationBeansAbsent",
                    true,
                    "payContextActive",
                    application.isActive(),
                    "automaticActionsAllowed",
                    false)),
        StandardOpenOption.CREATE_NEW);
    cancelStore = application.getBean(com.cloudmall.pay.service.impl.PayTccCancelStore.class);
    cancelRunning =
        (AtomicBoolean)
            ReflectionTestUtils.getField(
                application.getBean(com.cloudmall.pay.service.impl.PayTccCancelScheduler.class),
                "running");
    startGateway();
    delivery = application.getBean(PayTccDeliveryService.class);
    parentRunning =
        (AtomicBoolean)
            ReflectionTestUtils.getField(
                application.getBean(PayTccDeliveryScheduler.class), "running");
  }

  @BeforeEach
  void seed() throws Exception {
    Assumptions.assumeFalse(preserve);
    userRmTcProxy.restore();
    tcEvidenceWindowStartedAt = PayTccTcBranchEvidence.markWindowStart();
    tcEvidenceAnchorArtifact = artifacts.resolve("tc-window-anchor-" + UUID.randomUUID() + ".json");
    Files.writeString(
        tcEvidenceAnchorArtifact,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tcEvidenceWindowStartedAt),
        java.nio.file.StandardOpenOption.CREATE_NEW);
    if (!"OK".equals(tcEvidenceWindowStartedAt.status())) {
      preserve = true;
      fail("TC同源开始窗口不可用，禁止begin并保留来源证据");
    }
    await(() -> cancelRunning.compareAndSet(false, true), 10);
    await(() -> parentRunning.compareAndSet(false, true), 10);
    pauseOwned = true;
    for (TccResponseProxy proxy : proxies.values()) {
      proxy.clearFault();
      proxy.resetCounters();
    }
    stockDb.execute("DROP TRIGGER IF EXISTS block_settlement");
    for (String table :
        List.of(
            "pay_tcc_delivery",
            "pay_tcc_execution",
            "pay_tcc_dispatch",
            "pay_tcc_receipt",
            "tcc_fence_log",
            "pay_tcc_cancel",
            "pay_callback_log",
            "pay_debit_intent",
            "pay_record")) payDb.update("DELETE FROM " + table);
    for (String table :
        List.of(
            "user_tcc_receive",
            "tcc_fence_log",
            "balance_debit_attempt",
            "balance_tcc_debit",
            "balance_ledger",
            "mall_user")) userDb.update("DELETE FROM " + table);
    userDb.update(
        "INSERT INTO mall_user(id,username,password_hash,role,balance,status,created_at,updated_at)"
            + " VALUES(7,'delivery','unused','USER',100,1,NOW(3),NOW(3))");
    request = new PayTccDispatchDTO();
    request.payNo = "delivery-" + UUID.randomUUID();
    request.orderNo = month + UUID.randomUUID().toString();
    request.userId = 7L;
    request.amount = new BigDecimal("10.00");
    skuId = System.nanoTime();
    orderDb.update(
        "INSERT INTO mall_order_"
            + month
            + "(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
            + " VALUES(?,?,7,'PENDING_PAYMENT',10,10,'{}',DATE_ADD(NOW(3),INTERVAL 1"
            + " HOUR),NOW(3),NOW(3))",
        skuId,
        request.orderNo);
    orderDb.update(
        "INSERT INTO mall_order_item_"
            + month
            + "(id,order_id,order_no,product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount,created_at)"
            + " VALUES(?,?,?,1,?,'fixture','{}',10,1,10,NOW(3))",
        skuId,
        skuId,
        request.orderNo,
        skuId);
    stockDb.update(
        "INSERT INTO"
            + " stock_sku(sku_id,product_id,total_quantity,available_quantity,reserved_quantity,sold_quantity,version,updated_at)"
            + " VALUES(?,1,10,10,0,0,0,NOW(3))",
        skuId);
    HttpResponse<String> reserve =
        call(
            "stock",
            "/api/stock/reservations",
            Map.of(
                "orderNo",
                request.orderNo,
                "items",
                List.of(Map.of("skuId", skuId, "quantity", 1)),
                "scene",
                "NORMAL"));
    assertEquals(200, reserve.statusCode(), reserve.body());
  }

  @AfterEach
  void settle() throws Exception {
    if (request == null || preserve) return;
    try {
      stopReplacement();
      if (!pauseOwned) {
        await(() -> parentRunning.compareAndSet(false, true), 10);
        pauseOwned = true;
      }
      stockDb.execute("DROP TRIGGER IF EXISTS block_settlement");
      orderDb.update(
          "UPDATE order_stock_action SET next_retry_at=NOW(3) WHERE order_no=?", request.orderNo);
      String state =
          orderDb.queryForObject(
              "SELECT status FROM mall_order_" + month + " WHERE order_no=?",
              String.class,
              request.orderNo);
      if ("PENDING_PAYMENT".equals(state))
        call("order", "/api/orders/" + request.orderNo + "/cancel", Map.of());
      await(
          () ->
              "SUCCEEDED"
                  .equals(
                      orderDb
                          .queryForList(
                              "SELECT status FROM order_stock_action WHERE order_no=?",
                              String.class,
                              request.orderNo)
                          .stream()
                          .findFirst()
                          .orElse("")),
          45);
      if (cancelStore.read(request.payNo) != null
          && !"CONFLICT".equals(cancelStore.read(request.payNo).state)) {
        await(
            () -> {
              payDb.update(
                  "UPDATE pay_tcc_cancel SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
                      + " pay_no=?",
                  request.payNo);
              return "COMPLETED"
                  .equals(
                      application
                          .getBean(com.cloudmall.pay.service.impl.PayTccCancelService.class)
                          .reconcile(request.payNo));
            },
            70);
        assertEquals("FAILED", paymentState());
      }
      List<String> executionXids =
          payDb.queryForList(
              "SELECT xid FROM pay_tcc_execution WHERE pay_no=?", String.class, request.payNo);
      if (!executionXids.isEmpty() && executionXids.get(0) != null) xids.add(executionXids.get(0));
      for (String xid : xids) await(() -> terminal(xid), 45);
      if ("COMMITTED"
          .equals(
              payDb
                  .queryForList(
                      "SELECT state FROM pay_tcc_execution WHERE pay_no=?",
                      String.class,
                      request.payNo)
                  .stream()
                  .findFirst()
                  .orElse(""))) {
        forceDue();
        delivery.deliver(request.payNo);
        await(
            () -> {
              forceDue();
              PayTccDeliveryVO value = delivery.deliver(request.payNo);
              return value != null && Set.of("SUCCESS", "REFUNDED").contains(value.state);
            },
            30);
      }
      assertEquals(
          "0.00",
          userDb.queryForObject("SELECT frozen_balance FROM mall_user WHERE id=7", String.class));
      assertEquals(
          0,
          stockDb.queryForObject(
              "SELECT reserved_quantity FROM stock_sku WHERE sku_id=?", Integer.class, skuId));
      if (phaseEvidenceRepresentative) {
        assertEquals("CANCELLED", orderState());
        assertEquals(
            "ROLLED_BACK",
            stockDb.queryForObject(
                "SELECT status FROM stock_reservation_request WHERE order_no=?",
                String.class,
                request.orderNo));
        assertEquals(
            "ROLLBACK",
            orderDb.queryForObject(
                "SELECT action_type FROM order_stock_action WHERE order_no=?",
                String.class,
                request.orderNo));
        assertFunds("100.00", 0, 0);
        if (RM_LINK_SCENARIO.equals(evidenceScenarioId))
          capturePhase(
              "ROLLBACK_PRE_CLEANUP",
              rollbackTcEvidence,
              null,
              evidenceScenarioId,
              "ORIGINAL_ROLLBACK_NATIVE_TERMINAL");
        else capturePhase("ROLLBACK_PRE_CLEANUP", rollbackTcEvidence, null);
      }
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("execution", payDb.queryForList("SELECT * FROM pay_tcc_execution"));
      snapshot.put("delivery", payDb.queryForList("SELECT * FROM pay_tcc_delivery"));
      snapshot.put("cancellation", payDb.queryForList("SELECT * FROM pay_tcc_cancel"));
      snapshot.put("callbacks", payDb.queryForList("SELECT * FROM pay_callback_log"));
      snapshot.put("payment", payDb.queryForList("SELECT * FROM pay_record"));
      snapshot.put("userAttempt", userDb.queryForList("SELECT * FROM balance_debit_attempt"));
      snapshot.put("userFence", userDb.queryForList("SELECT * FROM tcc_fence_log"));
      snapshot.put("dispatch", payDb.queryForList("SELECT * FROM pay_tcc_dispatch"));
      snapshot.put("payReceipt", payDb.queryForList("SELECT * FROM pay_tcc_receipt"));
      snapshot.put("ledger", userDb.queryForList("SELECT * FROM balance_ledger"));
      snapshot.put(
          "funds", userDb.queryForList("SELECT balance,frozen_balance,status FROM mall_user"));
      snapshot.put(
          "order",
          orderDb.queryForList(
              "SELECT * FROM mall_order_" + month + " WHERE order_no=?", request.orderNo));
      snapshot.put("stock", stockDb.queryForList("SELECT * FROM stock_sku WHERE sku_id=?", skuId));
      snapshot.put(
          "action",
          orderDb.queryForList(
              "SELECT * FROM order_stock_action WHERE order_no=?", request.orderNo));
      Files.writeString(
          artifacts.resolve(request.payNo + ".json"), JSON.writeValueAsString(snapshot));
    } catch (Throwable failure) {
      preserve = true;
      if (phaseEvidenceRepresentative
          || "BEGIN_RESPONSE_LOST_NO_SUBJECT_XID".equals(evidenceScenarioId)
          || "ROLLEDBACK_PAY_201_ORDER_10".equals(evidenceScenarioId))
        recordEvidenceFailure(failure);
      throw failure;
    } finally {
      parentRunning.set(false);
      cancelRunning.set(false);
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    boolean proxyClosed = false;
    Throwable primary = null;
    try {
      stopReplacement();
      if (gatewayProcess != null) {
        gatewayProcess.destroyForcibly();
        gatewayProcess.waitFor(10, TimeUnit.SECONDS);
      }
      for (String xid : xids) if (!terminal(xid)) preserve = true;
      if (application != null) application.close();
      for (var entry : children.entrySet()) {
        entry.getValue().destroyForcibly();
        entry.getValue().waitFor(15, TimeUnit.SECONDS);
        naming.deregisterInstance(
            "cloud-mall-" + entry.getKey(), batch, "127.0.0.1", proxies.get(entry.getKey()).port());
        proxies.get(entry.getKey()).close();
      }
      if (naming != null) naming.shutDown();
      closeUserRmTcProxy();
      proxyClosed = true;
      if (source != null) assertEquals(originalChecksums, checksums());
      if (!preserve && batch != null && batch.matches("cm16c_[0-9a-f]{32}")) {
        for (String service : urls.keySet())
          source.execute("DROP DATABASE IF EXISTS " + batch + "_" + service);
        rabbit("DELETE", "/api/vhosts/" + batch, null);
        if (batch.equals(
            JSON.readTree(docker("inspect", redisContainer))
                .get(0)
                .path("Config")
                .path("Labels")
                .path("cloudmall.test")
                .asText())) docker("rm", "-f", redisContainer);
      }
      assertFalse(preserve, "保留未决交付现场: " + artifacts);
    } catch (Throwable failure) {
      primary = failure;
      throw failure;
    } finally {
      if (!proxyClosed) {
        try {
          closeUserRmTcProxy();
        } catch (Throwable failure) {
          preserve = true;
          if (primary != null) primary.addSuppressed(failure);
          else if (failure instanceof Exception exception) throw exception;
          else throw (Error) failure;
        }
      }
    }
  }

  /** proxy 和 loader 独立关闭；不声称原 helper 具有线程 join 证据。 */
  private static void closeUserRmTcProxy() throws Exception {
    if (userRmTcProxy == null) return;
    try {
      userRmTcProxy.close();
      if (artifacts != null)
        Files.writeString(
            artifacts.resolve("user-rm-tc-proxy-closed-" + UUID.randomUUID() + ".json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(userRmTcProxy.identity),
            StandardOpenOption.CREATE_NEW);
    } catch (Exception | AssertionError failure) {
      preserve = true;
      throw failure;
    }
  }

  @Test
  @Order(1)
  void lostCancelResponseWaitsForStockAndCompletesCallback() throws Exception {
    createPublic();
    stockDb.execute(
        "CREATE TRIGGER block_settlement BEFORE UPDATE ON stock_reservation_request FOR EACH ROW"
            + " BEGIN IF NEW.status='ROLLED_BACK' THEN SIGNAL SQLSTATE '45000' SET"
            + " MESSAGE_TEXT='test cancellation stock unavailable'; END IF; END");
    proxies.get("order").dropNextPostBodyForPath("/cancel");
    try {
      Response result =
          post(
              "/api/payments/callback", callback(false, "cancel-response-lost"), callbackHeaders());
      assertEquals(202, result.status(), result.body().toString());
      assertEquals("PENDING", paymentState());
      assertEquals("ORDER_CANCELLED", cancelStore.read(request.payNo).state);
      assertEquals("CANCELLED", orderState());
      assertEquals(
          202,
          post("/api/payments/" + request.payNo + "/mock-success", Map.of(), Map.of()).status());
      io.seata.core.context.RootContext.bind("cancel-gate-no-transaction");
      try {
        assertThrows(
            com.cloudmall.common.error.BizException.class,
            () ->
                application
                    .getBean(com.cloudmall.pay.service.PayTccBridgeService.class)
                    .prepare(request));
      } finally {
        io.seata.core.context.RootContext.unbind();
      }
      assertEquals(
          0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_execution", Integer.class));
      assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_dispatch", Integer.class));
      assertFunds("100.00", 0, 0);
    } finally {
      stockDb.execute("DROP TRIGGER IF EXISTS block_settlement");
    }
    orderDb.update("UPDATE order_stock_action SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY)");
    completeCancellation();
    Response replay =
        post("/api/payments/callback", callback(false, "cancel-response-lost"), callbackHeaders());
    assertEquals(200, replay.status(), replay.body().toString());
    assertEquals("FAILED", replay.body().path("data").path("status").asText());
    assertEquals(
        1,
        payDb.queryForObject(
            "SELECT COUNT(*) FROM pay_callback_log WHERE callback_status='FAILED' AND processed_at"
                + " IS NOT NULL",
            Integer.class));
    assertStock(10, 0, 0);
  }

  @Test
  @Order(2)
  void firstSuccessAndCancelHaveOnlyOnePersistentWinner() throws Exception {
    createPublic();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch start = new CountDownLatch(1);
    try {
      Future<Response> success =
          pool.submit(
              () -> {
                start.await();
                return post("/api/payments/" + request.payNo + "/mock-success", Map.of(), Map.of());
              });
      Future<Response> cancel =
          pool.submit(
              () -> {
                start.await();
                return post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of());
              });
      start.countDown();
      assertTrue(Set.of(200, 202, 409).contains(success.get(30, TimeUnit.SECONDS).status()));
      assertTrue(Set.of(200, 202, 409).contains(cancel.get(30, TimeUnit.SECONDS).status()));
    } finally {
      pool.shutdownNow();
    }
    int executions = payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_execution", Integer.class);
    int cancels = payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_cancel", Integer.class);
    assertEquals(1, executions + cancels);
    if (cancels == 1) {
      completeCancellation();
      assertFunds("100.00", 0, 0);
    } else {
      await(
          () -> {
            try {
              post("/api/payments/" + request.payNo + "/mock-success", Map.of(), Map.of());
              return Set.of("SUCCESS", "FAILED", "REFUNDED").contains(paymentState());
            } catch (Exception failure) {
              throw new RuntimeException(failure);
            }
          },
          30);
      if ("FAILED".equals(paymentState())) assertFunds("100.00", 0, 0);
      else if ("REFUNDED".equals(paymentState())) assertFunds("100.00", 1, 1);
      else assertFunds("90.00", 1, 0);
    }
  }

  @Test
  @Order(3)
  void committedDeliveryWithPaidPermissionPersistsCancellationForReconciliation() throws Exception {
    createPublic();
    assertEquals(
        "COMMITTED",
        application
            .getBean(com.cloudmall.pay.service.PayTccCoordinatorService.class)
            .execute(request)
            .state);
    var deliveryStore =
        application.getBean(com.cloudmall.pay.service.impl.delivery.PayTccDeliveryStore.class);
    deliveryStore.ensure(request.payNo);
    assertNotNull(deliveryStore.claim(request.payNo, "prior-delivery"));
    assertTrue(deliveryStore.stage(request.payNo, "prior-delivery", "ORDER_SYNC_UNKNOWN"));
    try {
      assertEquals(
          202, post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of()).status());
      assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_cancel", Integer.class));
      assertEquals("COMMIT", decision());
      assertEquals("PENDING_PAYMENT", orderState());
      assertEquals("CANCEL_RECONCILING", deliveryStore.read(request.payNo).state);
      assertEquals("ORDER_SYNC_UNKNOWN", paymentState());
      assertFunds("90.00", 1, 0);
    } finally {
      deliveryStore.finish(request.payNo, "prior-delivery", 1, null);
    }
    parentRunning.set(false);
    pauseOwned = false;
    await(() -> "REFUNDED".equals(paymentState()), 40);
    assertFunds("100.00", 1, 1);
    assertStock(10, 0, 0);
    assertEquals(0, proxies.get("order").paidPostCount());
  }

  @Test
  @Order(4)
  void freshPayProcessRecoversCommittedCancelIntent() throws Exception {
    createPublic();
    cancelStore.claimOrRead(paymentView());
    assertEquals("PENDING_PAYMENT", orderState());
    List<String> arguments =
        new ArrayList<>(
            List.of(
                javaExecutable(),
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Xmx256m",
                "-jar",
                Path.of("target", "cloud-mall-pay-0.1.0-SNAPSHOT.jar")
                    .toAbsolutePath()
                    .toString()));
    arguments.addAll(List.of(payArguments()));
    arguments.removeIf(
        argument ->
            argument.startsWith("--spring.cloud.nacos.discovery.register-enabled=")
                || argument.startsWith("--seata.application-id="));
    arguments.add("--spring.main.web-application-type=none");
    arguments.add("--spring.cloud.nacos.discovery.register-enabled=false");
    arguments.add("--seata.application-id=" + batch + "-replacement");
    replacementPay =
        new ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .redirectOutput(artifacts.resolve("cancel-replacement.log").toFile())
            .start();
    await(
        () -> {
          if (!replacementPay.isAlive())
            throw new AssertionError("replacement Pay退出: " + artifacts);
          return "FAILED".equals(paymentState());
        },
        90);
    assertEquals("COMPLETED", cancelStore.read(request.payNo).state);
    assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_execution", Integer.class));
    assertFunds("100.00", 0, 0);
    assertStock(10, 0, 0);
    stopReplacement();
  }

  @Test
  @Order(5)
  void staleLeaseCannotWriteAndFailedLedgerWriteRollsBackPayment() throws Exception {
    createPublic();
    cancelStore.claimOrRead(paymentView());
    assertNotNull(cancelStore.claimRecovery(request.payNo, "old-token"));
    payDb.update(
        "UPDATE pay_tcc_cancel SET lease_until=DATE_SUB(NOW(3),INTERVAL 1"
            + " DAY),next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY)");
    // 尚未被新实例接手时，过期owner也不能清除租约或延后恢复。
    var expiredCancel =
        payDb.queryForMap("SELECT * FROM pay_tcc_cancel WHERE pay_no=?", request.payNo);
    cancelStore.finish(request.payNo, "old-token", 1, "expired worker");
    assertEquals(
        expiredCancel,
        payDb.queryForMap("SELECT * FROM pay_tcc_cancel WHERE pay_no=?", request.payNo));
    assertNotNull(cancelStore.claimRecovery(request.payNo, "new-token"));
    assertFalse(cancelStore.stage(request.payNo, "old-token", "COMPLETED", null));
    cancelStore.finish(request.payNo, "old-token", 1, null);
    assertEquals("new-token", cancelStore.read(request.payNo).leaseOwner);
    assertEquals("PENDING", paymentState());
    call("order", "/api/orders/" + request.orderNo + "/cancel", Map.of());
    await(
        () ->
            "SUCCEEDED"
                .equals(
                    orderDb.queryForObject(
                        "SELECT status FROM order_stock_action WHERE order_no=?",
                        String.class,
                        request.orderNo)),
        30);
    payDb.execute(
        "CREATE TRIGGER fail_cancel_completion BEFORE UPDATE ON pay_tcc_cancel FOR EACH ROW BEGIN"
            + " IF NEW.state='COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test cancel"
            + " completion write failure'; END IF; END");
    try {
      assertThrows(
          RuntimeException.class,
          () -> cancelStore.stage(request.payNo, "new-token", "COMPLETED", null));
      assertEquals("PENDING", paymentState());
      assertEquals("REQUESTED", cancelStore.read(request.payNo).state);
    } finally {
      payDb.execute("DROP TRIGGER IF EXISTS fail_cancel_completion");
      cancelStore.finish(request.payNo, "new-token", 2, null);
    }
    completeCancellation();
  }

  @Test
  @Order(6)
  void invalidModeRowsDoNotOccupyRecoveryBatch() throws Exception {
    createPublic();
    cancelStore.claimOrRead(paymentView());
    try {
      for (int index = 0; index < 21; index++) {
        String key = "scan-fixture-" + index;
        payDb.update(
            "INSERT INTO"
                + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
                + " VALUES(?,?,?,7,10,'PENDING','LEGACY',NOW(3),NOW(3))",
            100L + index,
            key,
            key);
        payDb.update(
            "INSERT INTO"
                + " pay_tcc_cancel(id,pay_no,order_no,user_id,amount,state,next_retry_at,created_at,updated_at)"
                + " VALUES(?,?,?,7,10,'REQUESTED',DATE_SUB(NOW(3),INTERVAL 1 DAY),NOW(3),NOW(3))",
            100L + index,
            key,
            key);
      }
      var due =
          application
              .getBean(com.cloudmall.pay.mapper.PayTccCancelMapper.class)
              .findDue(LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(1), 20);
      assertEquals(1, due.size());
      assertEquals(request.payNo, due.get(0).payNo);
    } finally {
      payDb.update("DELETE FROM pay_tcc_cancel WHERE pay_no LIKE 'scan-fixture-%'");
      payDb.update("DELETE FROM pay_record WHERE pay_no LIKE 'scan-fixture-%'");
    }
    completeCancellation();
  }

  @Test
  @Order(7)
  void rolledBackFailureDoesNotCreateCancelIntentWhenCancelIsEnabled() throws Exception {
    userDb.update("UPDATE mall_user SET balance=1 WHERE id=7");
    createPublic();
    assertEquals(
        "ROLLEDBACK",
        application
            .getBean(com.cloudmall.pay.service.PayTccCoordinatorService.class)
            .execute(request)
            .state);
    assertEquals("PENDING", paymentState());
    Response result = post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of());
    assertEquals(200, result.status(), result.body().toString());
    assertEquals("FAILED", result.body().path("data").path("status").asText());
    assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_cancel", Integer.class));
    assertEquals("PENDING_PAYMENT", orderState());
    assertFunds("1.00", 0, 0);
  }

  @Test
  @Order(8)
  void preparedUndecidedPaymentCanDurablyChooseRollback() throws Exception {
    phaseEvidenceRepresentative = true;
    evidenceScenarioId = RM_LINK_SCENARIO;
    try {
      createPublic();
      capturePhase("CREATED_BEFORE_EXECUTE", null, null, RM_LINK_SCENARIO, "NO_EXECUTION_YET");
      try (CommitPause pause = new CommitPause(false, 45, 60)) {
        PayTccTransactionClient originalTransactions =
            (PayTccTransactionClient)
                ReflectionTestUtils.getField(pause.coordinator, "transactions");
        CountedTransactions counted = new CountedTransactions(originalTransactions);
        ExecutorService recoveryCaller = Executors.newSingleThreadExecutor();
        Future<PayTccExecutionVO> recovery = null;
        Throwable primary = null;
        Map<String, Object> channel = new LinkedHashMap<>();
        Process user = children.get("user");
        long originalPid = user.pid();
        Instant originalStart = user.info().startInstant().orElseThrow();
        ReflectionTestUtils.setField(pause.coordinator, "transactions", counted);
        try {
          // 1. 原公开 HTTP 与真实 SDK 唯一 begin/双 Try；只暂停当前 COMMIT 决议点。
          pause.start();
          assertEquals("NONE", decision());
          assertEquals(
              "TRYING", payDb.queryForObject("SELECT state FROM pay_tcc_receipt", String.class));
          assertEquals(
              "TRY_ACCEPTED",
              payDb.queryForObject("SELECT state FROM pay_tcc_dispatch", String.class));
          assertBridgeIdentity();
          assertPreparedWindow(pause);
          counted.assertSingleBegin(originalTcIdentity().xid);
          Path preparedTc = captureOriginalTc("cancel-prepared-undecided");
          var preparedFacts = JSON.readTree(Files.readAllBytes(preparedTc));
          assertEquals("UNOBSERVED", preparedFacts.path("globalOutcome").textValue());
          assertFalse(preparedFacts.path("directionProvenByTc").asBoolean());
          assertEquals(2, preparedFacts.path("events").size());
          for (var event : preparedFacts.path("events"))
            assertEquals("REGISTER", event.path("type").textValue());
          for (String participant : List.of("pay", "user"))
            assertTrue(preparedFacts.path(participant).path("registrationObserved").asBoolean());
          capturePhase(
              "PREPARED_UNDECIDED",
              preparedTc,
              pause,
              RM_LINK_SCENARIO,
              "ORIGINAL_ACTIVE_REGISTRATIONS_ONLY");

          // 2. 只断自有 User TC 通道；User HTTP 与父 TM 保留真实可达性。
          assertTrue(userRmTcProxy.active() > 0);
          assertTrue(userRmTcProxy.bytes() > 0);
          channel.put("beforeBlock", userRmTcProxy.observation());
          userRmTcProxy.block();
          assertEquals(0, userRmTcProxy.active());
          assertUserTryingOverHttp();
          Response failure =
              post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of());
          assertEquals(202, failure.status(), failure.body().toString());
          assertEquals("ROLLBACK", decision());
          assertEquals(
              "ROLLBACK_REQUESTED",
              payDb.queryForObject("SELECT state FROM pay_tcc_execution", String.class));
          assertEquals("PENDING", paymentState());
          pause.release.countDown();
          Response lateCommit = pause.response.get(pause.responseTimeoutSeconds, TimeUnit.SECONDS);
          assertEquals(409, lateCommit.status());
          assertEquals("PAY_TCC_EXECUTION_CONFLICT", lateCommit.body().path("code").asText());
          assertEquals(
              202,
              post(
                      "/api/payments/callback",
                      callback(false, "abort-before-decision"),
                      callbackHeaders())
                  .status());
          assertEquals(
              1,
              payDb.queryForObject(
                  "SELECT COUNT(*) FROM pay_callback_log WHERE callback_status='RECEIVED_FAILURE'"
                      + " AND processed_at IS NULL",
                  Integer.class));

          // 3. 仅一次真实 recover 发送原 rollback；返回后只读等待，绝不轮询写观察。
          recovery =
              recoveryCaller.submit(
                  () -> application.getBean(PayTccCoordinatorService.class).recover(request.payNo));
          PayTccExecutionVO pending = recovery.get(45, TimeUnit.SECONDS);
          assertEquals("ROLLBACK_REQUESTED", pending.state);
          assertEquals("ROLLBACK", pending.decision);
          Map<String, Object> stable = awaitDisconnectedStable(pause);
          channel.put("disconnected", stable);
          channel.put("tcStatusBeforeNativeRead", disconnectedTcStatus().name());
          channel.put("explicitRecoverReturned", true);
          Path partialTc = captureOriginalTc("cancel-user-tc-disconnected");
          var partial = JSON.readTree(Files.readAllBytes(partialTc));
          assertEquals("UNOBSERVED", partial.path("globalOutcome").textValue());
          assertFalse(partial.path("directionProvenByTc").booleanValue());
          String userBranchHash =
              sha256(
                  Long.toString(originalTcIdentity().userBranch)
                      .getBytes(java.nio.charset.StandardCharsets.UTF_8));
          for (var event : partial.path("events")) {
            assertFalse("GLOBAL_ROLLBACK".equals(event.path("type").textValue()));
            if ("PHASE_TWO_ROLLBACK".equals(event.path("type").textValue()))
              assertFalse(userBranchHash.equals(event.path("branchSha256").textValue()));
          }
          assertEquals(stable, disconnectedObservation(pause));
          capturePhase(
              "ROLLBACK_REQUESTED_USER_TC_DISCONNECTED",
              partialTc,
              null,
              RM_LINK_SCENARIO,
              "ORIGINAL_ROLLBACK_REQUESTED_PARTIAL_NOT_TERMINAL");
          assertEquals(stable, disconnectedObservation(pause));
          channel.put("tcStatusAfterPartialPhase", disconnectedTcStatus().name());

          // 4. 原进程重连并继续已选回滚；accepted 必须与新转发、同 PID/启动时间合取。
          long acceptedBeforeRestore = userRmTcProxy.accepted();
          long bytesBeforeRestore = userRmTcProxy.bytes();
          channel.put("beforeRestore", userRmTcProxy.observation());
          userRmTcProxy.restore();
          await(
              () -> {
                try {
                  return userRmTcProxy.active() > 0
                      && userRmTcProxy.accepted() > acceptedBeforeRestore
                      && userRmTcProxy.bytes() > bytesBeforeRestore;
                } catch (Exception unavailable) {
                  throw new IllegalStateException("OWNED_PROXY_OBSERVATION_FAILED", unavailable);
                }
              },
              30);
          assertTrue(user.isAlive());
          assertEquals(originalPid, user.pid());
          assertEquals(originalStart, user.info().startInstant().orElseThrow());
          channel.put("restored", userRmTcProxy.observation());
          channel.put("sameUserPidAndStart", true);
          channel.put("userPid", originalPid);
          channel.put("userStartedAt", originalStart.toString());
          pause.resumeRecovery();
          await(() -> "FAILED".equals(paymentState()), 30);
          assertEquals(
              200,
              post(
                      "/api/payments/callback",
                      callback(false, "abort-before-decision"),
                      callbackHeaders())
                  .status());
          assertEquals("PENDING_PAYMENT", orderState());
          assertEquals(
              0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_cancel", Integer.class));
          assertEquals(
              1, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_execution", Integer.class));
          assertEquals(1, proxies.get("user").postCount());
          assertEquals(0, proxies.get("order").postCount());
          assertFunds("100.00", 0, 0);
          assertStock(9, 1, 0);
          assertEquals(
              "ROLLEDBACK",
              payDb.queryForObject("SELECT state FROM pay_tcc_execution", String.class));
          assertEquals(
              "CANCELLED",
              userDb.queryForObject(
                  "SELECT state FROM balance_debit_attempt WHERE pay_no=?",
                  String.class,
                  request.payNo));
          assertEquals(
              0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_delivery", Integer.class));
          assertBridgeIdentity();
          counted.assertSingleBegin(originalTcIdentity().xid);
          assertTrue(counted.rollbacks.get() >= 1);
          channel.put("sdkCalls", counted.observation());
          channel.put("userTryPosts", proxies.get("user").postCount());
          channel.put("orderPostsBeforeSettlement", proxies.get("order").postCount());
          channel.put("originalIdentityPreserved", true);
        } catch (Throwable failure) {
          primary = failure;
          throw failure;
        } finally {
          finishRmBridgeCaller(
              pause, recoveryCaller, recovery, originalTransactions, counted, primary, channel);
        }
      }
      Path evidence = captureOriginalTc("cancel-original-rollback");
      var facts = JSON.readTree(Files.readString(evidence));
      assertEquals("SERVER_GLOBAL_ROLLBACK_OBSERVED", facts.path("globalOutcome").asText());
      assertTrue(facts.path("directionProvenByTc").asBoolean());
      assertTrue(facts.path("branchDetailsObserved").asBoolean());
      for (String participant : List.of("pay", "user")) {
        assertTrue(facts.path(participant).path("registrationObserved").asBoolean());
        assertEquals(
            "SERVER_PHASE_TWO_ROLLBACK_OBSERVED",
            facts.path(participant).path("phaseTwo").asText());
      }
      rollbackTcEvidence = evidence;
      assertEquals("PENDING_PAYMENT", orderState());
      assertEquals(5, facts.path("events").size());
      capturePhase(
          "ORIGINAL_ROLLBACK_ORDER_PENDING",
          evidence,
          null,
          RM_LINK_SCENARIO,
          "ORIGINAL_ROLLBACK_NATIVE_TERMINAL");
    } catch (Throwable failure) {
      preserve = true;
      recordEvidenceFailure(failure);
      throw failure;
    }
  }

  /** 真实 begin 已成功但响应丢失时，业务身份始终未绑定；阶段包先于测试探针清理。 */
  @Test
  @Order(19)
  void lostBeginResponseKeepsOriginalExecutionUnbound() throws Exception {
    evidenceScenarioId = "BEGIN_RESPONSE_LOST_NO_SUBJECT_XID";
    lastEvidencePhase = "BEGIN_RESPONSE_LOST";
    PayTccCoordinatorService coordinator = application.getBean(PayTccCoordinatorService.class);
    Object target = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(coordinator);
    PayTccTransactionClient original =
        (PayTccTransactionClient) ReflectionTestUtils.getField(target, "transactions");
    AtomicBoolean recoveryRunning =
        (AtomicBoolean)
            ReflectionTestUtils.getField(
                application.getBean(com.cloudmall.pay.service.impl.execution.PayTccRecoveryScheduler.class),
                "running");
    LostBeginTransactions probe = new LostBeginTransactions(original);
    boolean ownsRecovery = false;
    Throwable primaryFailure = null;
    try {
      // 1. 只在持有原恢复调度门时替换窄接口，仍由真实公开入口创建本笔 TCC Pay。
      await(() -> recoveryRunning.compareAndSet(false, true), 10);
      ownsRecovery = true;
      assertTrue(pauseOwned && parentRunning.get() && cancelRunning.get());
      createPublic();
      ReflectionTestUtils.setField(target, "transactions", probe);
      assertBeginUnknownView(coordinator.execute(request));
      Map<String, Object> identity = assertBeginUnknownFacts(null);
      assertBeginUnknownView(coordinator.execute(request));
      assertBeginUnknownFacts(identity);
      assertBeginUnknownView(coordinator.recover(request.payNo));
      assertBeginUnknownFacts(identity);
      probe.assertBusinessCalls();

      // 2. 未把探针 XID 写入业务或采集器；完整只读阶段必须先于任何测试 TC 清理。
      Path phase =
          capturePhase(
              "BEGIN_RESPONSE_LOST",
              null,
              null,
              evidenceScenarioId,
              "ORIGINAL_EXECUTION_XID_UNPROVEN");
      var facts = JSON.readTree(Files.readAllBytes(phase));
      assertEquals(
          ((Number) identity.get("id")).longValue(), facts.at("/subject/executionId").longValue());
      for (String field : List.of("xidSha256", "payBranchSha256", "userBranchSha256"))
        assertTrue(facts.path("subject").path(field).isNull(), "SUBJECT_XID_MUST_REMAIN_UNPROVEN");
      assertEquals("NOT_OBSERVED", facts.at("/sources/tc/status").textValue());
      assertBeginUnknownFacts(identity);
      probe.assertBusinessCalls();

      // 3. 恢复原接口后才清理测试唯一知道的空分支 TC；该动作不证明业务资金终态。
      ReflectionTestUtils.setField(target, "transactions", original);
      assertSame(original, ReflectionTestUtils.getField(target, "transactions"));
      assertTrue(probe.probeXid != null && !probe.probeXid.isBlank(), "TEST_PROBE_XID_UNAVAILABLE");
      GlobalStatus beforeCleanup = TransactionManagerHolder.get().getStatus(probe.probeXid);
      boolean terminalBeforeCleanup =
          Set.of(
                  GlobalStatus.Committed,
                  GlobalStatus.Rollbacked,
                  GlobalStatus.TimeoutRollbacked,
                  GlobalStatus.Finished)
              .contains(beforeCleanup);
      int cleanupRollbackCalls = 0;
      if (!terminalBeforeCleanup) {
        assertEquals(GlobalStatus.Begin, beforeCleanup, "TEST_PROBE_TC_STATE_UNEXPECTED");
        original.rollback(probe.probeXid);
        cleanupRollbackCalls++;
      }
      await(() -> terminal(probe.probeXid), 45);
      assertBeginUnknownFacts(identity);
      probe.assertBusinessCalls();
      Map<String, Object> cleanup = new LinkedHashMap<>();
      cleanup.put("runId", batch);
      cleanup.put("scenarioId", evidenceScenarioId);
      cleanup.put("phaseArtifact", artifactReference(phase));
      cleanup.put("declaration", "TEST_FIXTURE_CLEANUP_ONLY");
      cleanup.put("businessFundsEvidence", false);
      cleanup.put("performedAfterPhase", true);
      cleanup.put("cleanupRollbackCalls", cleanupRollbackCalls);
      cleanup.put("terminalBeforeCleanup", terminalBeforeCleanup);
      cleanup.put("terminalAfterCleanup", true);
      cleanup.put("businessBeginCalls", probe.begins.get());
      cleanup.put("lostBeginResponses", probe.lostResponses.get());
      cleanup.put("businessCommitCalls", probe.commits.get());
      cleanup.put("businessRollbackCalls", probe.rollbacks.get());
      cleanup.put("userTccPostCalls", proxies.get("user").postCount());
      cleanup.put("businessExecutionState", "BEGIN_UNKNOWN");
      cleanup.put("businessDecision", "NONE");
      cleanup.put("businessXidStillNull", true);
      cleanup.put("automaticActionsAllowed", false);
      cleanup.put("retention", "NO_AUTOMATIC_EXPIRY");
      Files.writeString(
          artifacts.resolve("begin-response-lost-cleanup-" + UUID.randomUUID() + ".json"),
          JSON.writerWithDefaultPrettyPrinter().writeValueAsString(cleanup),
          StandardOpenOption.CREATE_NEW);
    } catch (Throwable failure) {
      primaryFailure = failure;
      preserve = true;
      recordEvidenceFailure(failure);
      throw failure;
    } finally {
      try {
        RootContext.unbind();
        ReflectionTestUtils.setField(target, "transactions", original);
        assertSame(original, ReflectionTestUtils.getField(target, "transactions"));
        if (ownsRecovery) recoveryRunning.set(false);
      } catch (Throwable restorationFailure) {
        preserve = true;
        if (primaryFailure != null) primaryFailure.addSuppressed(restorationFailure);
        else {
          recordEvidenceFailure(restorationFailure);
          throw restorationFailure;
        }
      }
    }
  }

  /** 当前 fixture 的 Pay/Order 金额不一致时保留真实回滚事实，不制造交付或退款。 */
  @Test
  @Order(20)
  void crossSourceAmountConflictPreservesOriginalRollbackFacts() throws Exception {
    evidenceScenarioId = "ROLLEDBACK_PAY_201_ORDER_10";
    lastEvidencePhase = "PAY_ORDER_AMOUNT_MISMATCH";
    try {
      // 1. 公开创建原十元 Pay，仅精确匹配本 owned 原行后准备金额不一致的故障。
      createPublic();
      BigDecimal conflictingAmount = new BigDecimal("201.00");
      assertEquals(
          1,
          payDb.update(
              "UPDATE pay_record SET amount=? WHERE BINARY pay_no=BINARY ?"
                  + " AND BINARY order_no=BINARY ? AND user_id=? AND BINARY debit_mode='TCC'"
                  + " AND BINARY status='PENDING' AND amount=?",
              conflictingAmount,
              request.payNo,
              request.orderNo,
              request.userId,
              new BigDecimal("10.00")));
      request.amount = conflictingAmount;

      // 2. 原协调器真实执行并回滚；User 资金、Order 和 Stock 保留各自原事实。
      PayTccExecutionVO outcome =
          application.getBean(PayTccCoordinatorService.class).execute(request);
      assertEquals("ROLLEDBACK", outcome.state);
      assertEquals("ROLLBACK", outcome.decision);
      assertTrue(outcome.xid != null && !outcome.xid.isBlank(), "ORIGINAL_EXECUTION_XID_MISSING");
      xids.add(outcome.xid);
      assertTrue(RootContext.getXID() == null, "CALLER_CONTEXT_MUST_BE_EMPTY");
      String executionSql =
          "SELECT id,pay_no,user_id,order_no,amount,operation_key,ledger_key,xid,state,decision"
              + " FROM pay_tcc_execution WHERE pay_no=?";
      Map<String, Object> execution = payDb.queryForMap(executionSql, request.payNo);
      assertTrue(((Number) execution.get("id")).longValue() > 0, "EXECUTION_ID_MUST_EXIST");
      assertTrue(outcome.xid.equals(execution.get("xid")), "ORIGINAL_EXECUTION_XID_CHANGED");
      assertTrue(request.payNo.equals(execution.get("pay_no")), "EXECUTION_PAY_TUPLE_CHANGED");
      assertTrue(
          request.orderNo.equals(execution.get("order_no")), "EXECUTION_ORDER_TUPLE_CHANGED");
      assertEquals(request.userId.longValue(), ((Number) execution.get("user_id")).longValue());
      assertEquals(0, conflictingAmount.compareTo((BigDecimal) execution.get("amount")));
      assertTrue(
          ("tcc-debit:" + request.payNo).equals(execution.get("operation_key")),
          "EXECUTION_OPERATION_KEY_CHANGED");
      assertTrue(request.payNo.equals(execution.get("ledger_key")), "EXECUTION_LEDGER_KEY_CHANGED");
      assertEquals("ROLLEDBACK", execution.get("state"));
      assertEquals("ROLLBACK", execution.get("decision"));
      assertEquals(
          "TCC",
          payDb.queryForObject(
              "SELECT debit_mode FROM pay_record WHERE pay_no=?", String.class, request.payNo));
      assertEquals("PENDING", paymentState());
      assertNull(delivery.deliver(request.payNo));
      assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_delivery", Integer.class));
      assertFunds("100.00", 0, 0);
      assertEquals("PENDING_PAYMENT", orderState());
      Map<String, Object> orderAmounts =
          orderDb.queryForMap(
              "SELECT pay_amount,total_amount FROM mall_order_" + month + " WHERE order_no=?",
              request.orderNo);
      for (String field : List.of("pay_amount", "total_amount"))
        assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) orderAmounts.get(field)));
      assertEquals(
          "RESERVED",
          stockDb.queryForObject(
              "SELECT status FROM stock_reservation_request WHERE order_no=?",
              String.class,
              request.orderNo));
      assertStock(9, 1, 0);
      assertEquals(0, proxies.get("order").paidPostCount());
      assertEquals(0, proxies.get("user").refundPostCount());

      // 3. 保留同笔金额冲突的原阶段，原 XID 可读但不冒充已采 native branch packet。
      Path phase =
          capturePhase(
              "PAY_ORDER_AMOUNT_MISMATCH",
              null,
              null,
              evidenceScenarioId,
              "ORIGINAL_XID_PRESENT_NO_NATIVE_BRANCH_PACKET");
      var facts = JSON.readTree(Files.readAllBytes(phase));
      assertEquals(
          ((Number) execution.get("id")).longValue(), facts.at("/subject/executionId").longValue());
      assertEquals(
          sha256(outcome.xid.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          facts.at("/subject/xidSha256").textValue());
      assertTrue(
          execution.equals(payDb.queryForMap(executionSql, request.payNo)),
          "ORIGINAL_ROLLBACK_EXECUTION_CHANGED");
      assertFunds("100.00", 0, 0);
    } catch (Throwable failure) {
      preserve = true;
      recordEvidenceFailure(failure);
      throw failure;
    }
  }

  @Test
  @Order(9)
  void commitDecisionWinnerCannotBeReversedByFailure() throws Exception {
    createPublic();
    try (CommitPause pause = new CommitPause(true)) {
      pause.start();
      assertEquals("COMMIT", decision());
      int reads = proxies.get("user").getCount();
      Response failure = post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of());
      assertEquals(202, failure.status());
      assertEquals("COMMIT", decision());
      assertEquals(reads, proxies.get("user").getCount());
      pause.release.countDown();
      assertTrue(Set.of(200, 202).contains(pause.response.get(20, TimeUnit.SECONDS).status()));
      await(
          () -> {
            forceDue();
            try {
              return post("/api/payments/" + request.payNo + "/mock-success", Map.of(), Map.of())
                      .status()
                  == 200;
            } catch (Exception failureResult) {
              throw new RuntimeException(failureResult);
            }
          },
          30);
      assertFunds("90.00", 1, 0);
      assertEquals("PAID", orderState());
    }
  }

  @Test
  @Order(10)
  void unavailableUserProofCannotChooseRollback() throws Exception {
    createPublic();
    try (CommitPause pause = new CommitPause(false)) {
      pause.start();
      proxies.get("user").setTccReadsUnavailable(true);
      try {
        assertEquals(
            202,
            post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of()).status());
        assertEquals("NONE", decision());
        assertEquals("PENDING", paymentState());
      } finally {
        proxies.get("user").setTccReadsUnavailable(false);
      }
      pause.release.countDown();
      assertTrue(Set.of(200, 202).contains(pause.response.get(20, TimeUnit.SECONDS).status()));
      assertEquals("COMMIT", decision());
    }
  }

  @Test
  @Order(11)
  void failureCallbackCanAbortPreparedExecutionAfterCallerExited() throws Exception {
    createPublic();
    Object coordinator =
        org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
            application.getBean(com.cloudmall.pay.service.PayTccCoordinatorService.class));
    var original =
        (com.cloudmall.pay.service.impl.execution.PayTccExecutionStore)
            ReflectionTestUtils.getField(coordinator, "executions");
    var intercepted =
        org.mockito.Mockito.mock(
            com.cloudmall.pay.service.impl.execution.PayTccExecutionStore.class,
            org.mockito.AdditionalAnswers.delegatesTo(original));
    AtomicInteger decisionCalls = new AtomicInteger();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              decisionCalls.incrementAndGet();
              throw new IllegalStateException("fixture caller exited before decision");
            })
        .when(intercepted)
        .decideOwned(
            org.mockito.ArgumentMatchers.argThat(
                owned -> owned != null && request.payNo.equals(owned.payNo)),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("COMMIT"));
    ReflectionTestUtils.setField(coordinator, "executions", intercepted);
    try {
      assertEquals(
          202,
          post("/api/payments/" + request.payNo + "/mock-success", Map.of(), Map.of()).status());
    } finally {
      ReflectionTestUtils.setField(coordinator, "executions", original);
    }
    assertEquals(1, decisionCalls.get(), "caller-exit故障必须命中真实owned决议入口");
    assertEquals("NONE", decision());
    Response cancelled =
        post("/api/payments/callback", callback(false, "orphan-abort"), callbackHeaders());
    assertEquals(202, cancelled.status(), cancelled.body().toString());
    assertEquals("ROLLBACK", decision());
    await(() -> "FAILED".equals(paymentState()), 30);
    assertEquals(
        200,
        post("/api/payments/callback", callback(false, "orphan-abort"), callbackHeaders())
            .status());
    assertEquals("PENDING_PAYMENT", orderState());
    assertFunds("100.00", 0, 0);
  }

  /** 原资金已提交但交付从未认领时，显式失败请求选择取消并由后台唯一退款。 */
  @Test
  @Order(12)
  void committedUnclaimedDeliveryCanBeCancelledAndRefunded() throws Exception {
    createPublic();
    var coordinator = application.getBean(com.cloudmall.pay.service.PayTccCoordinatorService.class);
    assertEquals("COMMITTED", coordinator.execute(request).state);
    assertFunds("90.00", 1, 0);
    Response accepted = post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of());
    assertEquals(202, accepted.status(), accepted.body().toString());
    assertEquals(
        List.of("CANCEL_REQUESTED"),
        payDb.queryForList(
            "SELECT state FROM pay_tcc_delivery WHERE pay_no=?", String.class, request.payNo));
    assertEquals("PENDING", paymentState());
    assertEquals("PENDING_PAYMENT", orderState());
    assertEquals("COMMIT", decision());
    assertEquals(0, proxies.get("order").paidPostCount());
    assertEquals(0, proxies.get("user").refundPostCount());
    assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_cancel", Integer.class));
    parentRunning.set(false);
    pauseOwned = false;
    await(() -> "REFUNDED".equals(paymentState()), 40);
    assertEquals("CANCELLED", orderState());
    assertStock(10, 0, 0);
    assertFunds("100.00", 1, 1);
    assertEquals(0, proxies.get("order").paidPostCount());
    assertEquals("COMMIT", decision());
  }

  /** 已选取消在开关关闭的新Pay进程仍能恢复，库存未知及退款响应丢失均不改向。 */
  @Test
  @Order(13)
  void committedCancellationCallbackSurvivesLostResponsesAndRestartWithFlagOff() throws Exception {
    assertCancellationRestartWithFlagOff(false);
  }

  /** 已取得paid许可的取消待核验状态同样可在关闭入口开关的新进程恢复。 */
  @Test
  @Order(18)
  void uncertainCancellationSurvivesLostResponsesAndRestartWithFlagOff() throws Exception {
    assertCancellationRestartWithFlagOff(true);
  }

  private void assertCancellationRestartWithFlagOff(boolean reconcileCancellation)
      throws Exception {
    createPublic();
    assertEquals(
        "COMMITTED",
        application
            .getBean(com.cloudmall.pay.service.PayTccCoordinatorService.class)
            .execute(request)
            .state);
    var deliveryStore =
        application.getBean(com.cloudmall.pay.service.impl.delivery.PayTccDeliveryStore.class);
    deliveryStore.ensure(request.payNo);
    if (reconcileCancellation) {
      assertNotNull(deliveryStore.claim(request.payNo, "lost-before-paid"));
      assertTrue(deliveryStore.stage(request.payNo, "lost-before-paid", "ORDER_SYNC_UNKNOWN"));
      deliveryStore.finish(request.payNo, "lost-before-paid", 1, "fixture caller stopped");
    }
    Map<String, Object> failureCallback = callback(false, "committed-cancel");
    assertEquals(202, post("/api/payments/callback", failureCallback, callbackHeaders()).status());
    var accepted =
        payDb.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no=?", request.payNo);
    assertEquals(202, post("/api/payments/callback", failureCallback, callbackHeaders()).status());
    assertEquals(
        accepted,
        payDb.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no=?", request.payNo));
    stockDb.execute(
        "CREATE TRIGGER block_settlement BEFORE UPDATE ON stock_reservation_request FOR EACH ROW"
            + " BEGIN IF NEW.status='ROLLED_BACK' THEN SIGNAL SQLSTATE '45000' SET"
            + " MESSAGE_TEXT='test committed cancellation stock unavailable'; END IF; END");
    proxies.get("order").dropNextPostBodyForPath("/cancel");
    try {
      assertEquals(
          reconcileCancellation ? "CANCEL_RECONCILING" : "CANCEL_REQUESTED",
          delivery.deliver(request.payNo).state);
      assertEquals("CANCELLED", orderState());
      assertEquals(reconcileCancellation ? "ORDER_SYNC_UNKNOWN" : "PENDING", paymentState());
      assertFunds("90.00", 1, 0);
      assertEquals(0, proxies.get("order").paidPostCount());
    } finally {
      stockDb.execute("DROP TRIGGER IF EXISTS block_settlement");
    }
    orderDb.update(
        "UPDATE order_stock_action SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
            + " order_no=?",
        request.orderNo);
    await(
        () ->
            "SUCCEEDED"
                .equals(
                    orderDb.queryForObject(
                        "SELECT status FROM order_stock_action WHERE order_no=?",
                        String.class,
                        request.orderNo)),
        30);
    proxies.get("user").dropNextPostBodyForPath("/refunds");
    forceDue();
    List<String> arguments =
        new ArrayList<>(
            List.of(
                javaExecutable(),
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Xmx256m",
                "-jar",
                Path.of("target", "cloud-mall-pay-0.1.0-SNAPSHOT.jar")
                    .toAbsolutePath()
                    .toString()));
    arguments.addAll(List.of(payArguments()));
    arguments.removeIf(
        argument ->
            argument.startsWith("--cloudmall.pay.tcc-cancel.enabled=")
                || argument.startsWith("--spring.cloud.nacos.discovery.register-enabled=")
                || argument.startsWith("--seata.application-id="));
    arguments.addAll(
        List.of(
            "--cloudmall.pay.tcc-cancel.enabled=false",
            "--spring.main.web-application-type=none",
            "--spring.cloud.nacos.discovery.register-enabled=false",
            "--seata.application-id=" + batch + "-committed-replacement"));
    replacementPay =
        new ProcessBuilder(arguments)
            .redirectErrorStream(true)
            .redirectOutput(
                artifacts
                    .resolve(
                        reconcileCancellation
                            ? "uncertain-cancel-replacement.log"
                            : "committed-cancel-replacement.log")
                    .toFile())
            .start();
    await(
        () -> {
          if (!replacementPay.isAlive())
            throw new AssertionError("replacement Pay exited: " + artifacts);
          // 退款意图先于 User 入账可见；等待原丢响应尝试入账并释放租约，不能仅凭意图断言资金。
          return "REFUND_PENDING".equals(paymentState())
              && userDb.queryForObject(
                      "SELECT COUNT(*) FROM balance_ledger WHERE BINARY pay_no=BINARY ?"
                          + " AND operation_type='REFUND'",
                      Integer.class,
                      request.payNo)
                  == 1
              && userDb.queryForObject(
                      "SELECT COUNT(*) FROM balance_ledger WHERE BINARY pay_no=BINARY ?"
                          + " AND BINARY order_no=BINARY ? AND user_id=? AND amount=?"
                          + " AND BINARY operation_key=BINARY ?"
                          + " AND BINARY original_operation_key=BINARY ?"
                          + " AND operation_type='REFUND' AND state='APPLIED'",
                      Integer.class,
                      request.payNo,
                      request.orderNo,
                      request.userId,
                      request.amount,
                      "refund:" + request.payNo,
                      request.payNo)
                  == 1
              && userDb.queryForObject(
                      "SELECT COUNT(*) FROM mall_user WHERE id=? AND balance=? AND"
                          + " frozen_balance=?",
                      Integer.class,
                      request.userId,
                      new BigDecimal("100.00"),
                      BigDecimal.ZERO)
                  == 1
              && payDb.queryForObject(
                      "SELECT COUNT(*) FROM pay_tcc_delivery WHERE BINARY pay_no=BINARY ? AND"
                          + " state='REFUND_PENDING' AND lease_owner IS NULL AND lease_until IS"
                          + " NULL AND BINARY refund_operation_key=BINARY ?",
                      Integer.class,
                      request.payNo,
                      "refund:" + request.payNo)
                  == 1
              && proxies.get("user").refundPostCount() >= 1
              && ((java.util.concurrent.atomic.AtomicReference<?>)
                          ReflectionTestUtils.getField(proxies.get("user"), "dropPostSuffix"))
                      .get()
                  == null;
        },
        90);
    assertFunds("100.00", 1, 1);
    await(() -> "REFUNDED".equals(paymentState()), 40);
    assertFunds("100.00", 1, 1);
    assertStock(10, 0, 0);
    assertTrue(proxies.get("user").refundPostCount() >= 2);
    assertEquals(0, proxies.get("order").paidPostCount());
    assertEquals(409, post("/api/payments/callback", failureCallback, callbackHeaders()).status());
    assertEquals(
        "RECEIVED_FAILURE",
        payDb.queryForObject(
            "SELECT callback_status FROM pay_callback_log WHERE pay_no=?",
            String.class,
            request.payNo));
    assertEquals("COMMIT", decision());
    stopReplacement();
  }

  /** 旧worker尚未取得paid许可时，过期READY可改向，迟到stage不能再发送paid。 */
  @Test
  @Order(14)
  void expiredReadyCancellationBlocksLatePaidWorkerAndRefunds() throws Exception {
    assertPausedReadyCancellation(true);
  }

  /** 取消只改变READY方向，保留有效租约，原持有者迟到stage不得取得paid许可。 */
  @Test
  @Order(15)
  void activeReadyCancellationBlocksLatePaidWorkerWithoutStealingLease() throws Exception {
    assertPausedReadyCancellation(false);
  }

  /** 在paid许可提交前暂停真实worker，分别验证有效与过期lease下的同向清算。 */
  private void assertPausedReadyCancellation(boolean expireLease) throws Exception {
    createPublic();
    assertEquals(
        "COMMITTED",
        application
            .getBean(com.cloudmall.pay.service.PayTccCoordinatorService.class)
            .execute(request)
            .state);
    Object worker = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(delivery);
    var original =
        (com.cloudmall.pay.service.impl.delivery.PayTccDeliveryStore)
            ReflectionTestUtils.getField(worker, "store");
    var paused =
        org.mockito.Mockito.mock(
            com.cloudmall.pay.service.impl.delivery.PayTccDeliveryStore.class,
            org.mockito.AdditionalAnswers.delegatesTo(original));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    org.mockito.Mockito.doAnswer(
            invocation -> {
              entered.countDown();
              if (!release.await(30, TimeUnit.SECONDS))
                throw new IllegalStateException("fixture stage timeout");
              return original.stage(
                  invocation.getArgument(0), invocation.getArgument(1), invocation.getArgument(2));
            })
        .when(paused)
        .stage(
            org.mockito.ArgumentMatchers.eq(request.payNo),
            org.mockito.ArgumentMatchers.anyString(),
            org.mockito.ArgumentMatchers.eq("ORDER_SYNC_UNKNOWN"));
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<PayTccDeliveryVO> oldWorker = null;
    ReflectionTestUtils.setField(worker, "store", paused);
    try {
      oldWorker = pool.submit(() -> delivery.deliver(request.payNo));
      assertTrue(entered.await(15, TimeUnit.SECONDS));
      assertEquals("READY", original.read(request.payNo).state);
      if (expireLease) {
        payDb.update(
            "UPDATE pay_tcc_delivery SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
                + " pay_no=?",
            request.payNo);
      }
      var claimed = original.read(request.payNo);
      Response result = post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of());
      assertEquals(202, result.status(), result.body().toString());
      var cancellation = original.read(request.payNo);
      assertEquals("CANCEL_REQUESTED", cancellation.state);
      assertEquals(claimed.attempts, cancellation.attempts);
      assertEquals(claimed.leaseOwner, cancellation.leaseOwner);
      assertEquals(claimed.leaseUntil, cancellation.leaseUntil);
      if (!expireLease) {
        assertTrue(cancellation.leaseUntil.isAfter(LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
        assertNull(original.claim(request.payNo, "premature-recovery"));
        assertEquals(claimed.leaseOwner, original.read(request.payNo).leaseOwner);
      }
      release.countDown();
      assertEquals("CANCEL_REQUESTED", oldWorker.get(20, TimeUnit.SECONDS).state);
      assertEquals(0, proxies.get("order").paidPostCount());
      assertEquals("PENDING", paymentState());
    } finally {
      release.countDown();
      try {
        if (oldWorker != null) oldWorker.get(20, TimeUnit.SECONDS);
      } finally {
        ReflectionTestUtils.setField(worker, "store", original);
        pool.shutdownNow();
      }
    }
    parentRunning.set(false);
    pauseOwned = false;
    await(() -> "REFUNDED".equals(paymentState()), 40);
    assertEquals("CANCELLED", orderState());
    assertEquals(0, proxies.get("order").paidPostCount());
    assertFunds("100.00", 1, 1);
    assertStock(10, 0, 0);
    assertEquals("COMMIT", decision());
  }

  /** 旧paid已授权但尚未抵达Order时，取消先赢，迟到paid必须被Order拒绝。 */
  @Test
  @Order(16)
  void uncertainCancellationWinsAgainstLatePaidRequest() throws Exception {
    assertUncertainCancellationWinner(false);
  }

  /** paid实际已成功但回包丢失时，取消待核验收敛SUCCESS，不能退款。 */
  @Test
  @Order(17)
  void uncertainCancellationLosesToPaidWithLostResponse() throws Exception {
    assertUncertainCancellationWinner(true);
  }

  /** 在真实Order paid写入之前或之后暂停旧调用，验证两种服务端CAS胜者。 */
  private void assertUncertainCancellationWinner(boolean paidWins) throws Exception {
    createPublic();
    assertEquals(
        "COMMITTED",
        application
            .getBean(com.cloudmall.pay.service.PayTccCoordinatorService.class)
            .execute(request)
            .state);
    Object worker = org.springframework.test.util.AopTestUtils.getUltimateTargetObject(delivery);
    var original =
        (com.cloudmall.pay.feign.OrderClient) ReflectionTestUtils.getField(worker, "orders");
    var paused =
        org.mockito.Mockito.mock(
            com.cloudmall.pay.feign.OrderClient.class,
            org.mockito.AdditionalAnswers.delegatesTo(original));
    CountDownLatch entered = new CountDownLatch(1);
    CountDownLatch release = new CountDownLatch(1);
    if (paidWins) proxies.get("order").dropNextPostBodyForPath("/paid");
    org.mockito.Mockito.doAnswer(
            invocation -> {
              com.cloudmall.common.api.ApiResponse<com.cloudmall.pay.feign.OrderClient.OrderView>
                  result = null;
              RuntimeException lostResponse = null;
              if (paidWins) {
                try {
                  result = original.paid(request.orderNo, request.userId);
                } catch (RuntimeException failure) {
                  lostResponse = failure;
                }
              }
              entered.countDown();
              if (!release.await(30, TimeUnit.SECONDS))
                throw new IllegalStateException("fixture paid timeout");
              if (lostResponse != null) throw lostResponse;
              return paidWins ? result : original.paid(request.orderNo, request.userId);
            })
        .when(paused)
        .paid(request.orderNo, request.userId);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    Future<PayTccDeliveryVO> oldWorker = null;
    ReflectionTestUtils.setField(worker, "orders", paused);
    try {
      oldWorker = pool.submit(() -> delivery.deliver(request.payNo));
      assertTrue(entered.await(15, TimeUnit.SECONDS));
      assertEquals("ORDER_SYNC_UNKNOWN", paymentState());
      Response accepted = post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of());
      assertEquals(202, accepted.status(), accepted.body().toString());
      assertEquals(
          "CANCEL_RECONCILING",
          payDb.queryForObject(
              "SELECT state FROM pay_tcc_delivery WHERE pay_no=?", String.class, request.payNo));
      payDb.update(
          "UPDATE pay_tcc_delivery SET lease_until=DATE_SUB(NOW(3),INTERVAL 1"
              + " DAY),next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE pay_no=?",
          request.payNo);
      if (paidWins) {
        assertEquals("PAID", orderState());
        await(
            () ->
                "SUCCEEDED"
                    .equals(
                        orderDb.queryForObject(
                            "SELECT status FROM order_stock_action WHERE order_no=?",
                            String.class,
                            request.orderNo)),
            20);
      }
      delivery.deliver(request.payNo);
      assertEquals(paidWins ? "PAID" : "CANCELLED", orderState());
      release.countDown();
      oldWorker.get(20, TimeUnit.SECONDS);
    } finally {
      release.countDown();
      try {
        if (oldWorker != null) oldWorker.get(20, TimeUnit.SECONDS);
      } finally {
        ReflectionTestUtils.setField(worker, "orders", original);
        pool.shutdownNow();
      }
    }
    String terminalState = paidWins ? "SUCCESS" : "REFUNDED";
    await(
        () -> {
          forceDue();
          return terminalState.equals(delivery.deliver(request.payNo).state);
        },
        30);
    assertEquals(terminalState, paymentState());
    assertEquals(1, proxies.get("order").paidPostCount());
    assertEquals(paidWins ? 0 : 1, proxies.get("order").cancelPostCount());
    assertFunds(paidWins ? "90.00" : "100.00", 1, paidWins ? 0 : 1);
    assertStock(paidWins ? 9 : 10, 0, paidWins ? 1 : 0);
    assertEquals("COMMIT", decision());
    assertEquals(
        409, post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of()).status());
  }

  /** 只读核对当前原身份；首次 Try 时固定，后续原回滚和清理前必须保持同一组身份。 */
  private OriginalTcIdentity originalTcIdentity() {
    Map<String, Object> execution =
        payDb.queryForMap("SELECT id,xid FROM pay_tcc_execution WHERE pay_no=?", request.payNo);
    Map<String, Object> receipt =
        payDb.queryForMap(
            "SELECT xid,branch_id FROM pay_tcc_receipt WHERE pay_no=?", request.payNo);
    Map<String, Object> receive =
        userDb.queryForMap(
            "SELECT xid,branch_id FROM user_tcc_receive WHERE pay_no=?", request.payNo);
    String xid = (String) execution.get("xid");
    assertNotNull(xid);
    assertEquals(xid, receipt.get("xid"));
    assertEquals(xid, receive.get("xid"));
    long payBranch = ((Number) receipt.get("branch_id")).longValue();
    long userBranch = ((Number) receive.get("branch_id")).longValue();
    assertTrue(
        payBranch > 0 && userBranch > 0 && payBranch != userBranch,
        "ORIGINAL_BRANCHES_MUST_BE_DISTINCT");
    OriginalTcIdentity observed =
        new OriginalTcIdentity(
            ((Number) execution.get("id")).longValue(), xid, payBranch, userBranch);
    if (phaseEvidenceIdentity == null) phaseEvidenceIdentity = observed;
    else assertTrue(phaseEvidenceIdentity.equals(observed), "ORIGINAL_TC_IDENTITY_CHANGED");
    xids.add(xid);
    return observed;
  }

  /** 每次使用当前原身份及同一 begin 前的 daemon anchor；TRYING 与终态生成各自独立 TC 包。 */
  private Path captureOriginalTc(String phase) throws Exception {
    OriginalTcIdentity identity = originalTcIdentity();
    return PayTccTcBranchEvidence.capture(
        artifacts,
        phase,
        identity.xid,
        identity.payBranch,
        identity.userBranch,
        tcEvidenceWindowStartedAt,
        tcEvidenceAnchorArtifact);
  }

  /** 固定六张本笔来源的 tuple/原键；不把另一次重读的业务身份当成相同执行。 */
  private void assertBridgeIdentity() {
    originalTcIdentity();
    for (String table :
        List.of(
            "pay_tcc_execution",
            "pay_tcc_receipt",
            "pay_tcc_dispatch",
            "user_tcc_receive",
            "balance_debit_attempt",
            "balance_tcc_debit")) {
      JdbcTemplate database = table.startsWith("pay_") ? payDb : userDb;
      boolean hasLedgerKey = !table.equals("balance_tcc_debit");
      Map<String, Object> row =
          database.queryForMap(
              "SELECT pay_no,order_no,user_id,amount,operation_key"
                  + (hasLedgerKey ? ",ledger_key" : "")
                  + " FROM "
                  + table
                  + " WHERE pay_no=?",
              request.payNo);
      assertTrue(request.payNo.equals(row.get("pay_no")), "ORIGINAL_PAY_CHANGED");
      assertTrue(request.orderNo.equals(row.get("order_no")), "ORIGINAL_ORDER_CHANGED");
      assertEquals(7L, ((Number) row.get("user_id")).longValue());
      assertEquals(0, request.amount.compareTo((BigDecimal) row.get("amount")));
      assertTrue(
          ("tcc-debit:" + request.payNo).equals(row.get("operation_key")),
          "ORIGINAL_OPERATION_KEY_CHANGED");
      if (hasLedgerKey)
        assertTrue(request.payNo.equals(row.get("ledger_key")), "ORIGINAL_LEDGER_KEY_CHANGED");
    }
    assertEquals(
        "TCC",
        payDb.queryForObject(
            "SELECT debit_mode FROM pay_record WHERE pay_no=?", String.class, request.payNo));
    assertEquals(
        "TCC",
        userDb.queryForObject(
            "SELECT mode FROM balance_debit_attempt WHERE pay_no=?", String.class, request.payNo));
  }

  /** 断的是 TC 通道而不是 User HTTP；仍通过原 Feign/Nacos 客户端读取本笔 TRYING。 */
  private void assertUserTryingOverHttp() {
    OriginalTcIdentity identity = originalTcIdentity();
    var response =
        application
            .getBean(com.cloudmall.pay.feign.UserTccClient.class)
            .state(
                7L,
                "tcc-debit:" + request.payNo,
                7L,
                "cloud-mall-pay",
                application
                    .getEnvironment()
                    .getProperty("cloudmall.internal.callback-token", "cloudmall-local-callback"),
                identity.xid);
    assertNotNull(response);
    assertEquals("0", response.code);
    assertNotNull(response.data);
    assertEquals("TRYING", response.data.state);
    assertTrue(identity.xid.equals(response.data.xid), "HTTP_ORIGINAL_XID_CHANGED");
    assertNotNull(response.data.branchId);
    assertTrue(
        identity.userBranch == response.data.branchId.longValue(), "HTTP_ORIGINAL_BRANCH_CHANGED");
    assertFalse(response.data.ledgerApplied);
    assertTrue(response.data.ledgerAppliedPresent);
  }

  /** 只读观察真实局部 vector；逆序清算可能尚未到达 Pay，不要求两个分支同步。 */
  private Map<String, Object> disconnectedObservation(CommitPause pause) throws Exception {
    assertTrue(pauseOwned && parentRunning.get() && cancelRunning.get());
    assertTrue(pause.ownsRecovery && pause.recoveryRunning.get());
    assertTrue(pause.response.isDone(), "ORIGINAL_SUCCESS_OWNER_NOT_FINISHED");
    assertEquals(0, userRmTcProxy.active());
    assertTrue(children.get("user").isAlive());
    assertBridgeIdentity();
    assertEquals("ROLLBACK", decision());
    assertEquals(
        "ROLLBACK_REQUESTED",
        payDb.queryForObject("SELECT state FROM pay_tcc_execution", String.class));
    assertEquals("PENDING", paymentState());
    assertEquals(
        "TRYING",
        userDb.queryForObject(
            "SELECT state FROM balance_debit_attempt WHERE pay_no=?", String.class, request.payNo));
    assertEquals(
        "TRYING",
        userDb.queryForObject(
            "SELECT state FROM balance_tcc_debit WHERE pay_no=?", String.class, request.payNo));
    assertEquals(
        "90.00", userDb.queryForObject("SELECT balance FROM mall_user WHERE id=7", String.class));
    assertEquals(
        "10.00",
        userDb.queryForObject("SELECT frozen_balance FROM mall_user WHERE id=7", String.class));
    assertEquals(0, userDb.queryForObject("SELECT COUNT(*) FROM balance_ledger", Integer.class));
    assertEquals("PENDING_PAYMENT", orderState());
    assertStock(9, 1, 0);
    assertEquals(
        "RESERVED",
        stockDb.queryForObject(
            "SELECT status FROM stock_reservation_request WHERE order_no=?",
            String.class,
            request.orderNo));
    assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_delivery", Integer.class));
    OriginalTcIdentity identity = originalTcIdentity();
    disconnectedTcStatus();
    String receipt =
        payDb.queryForObject(
            "SELECT state FROM pay_tcc_receipt WHERE pay_no=?", String.class, request.payNo);
    int payFence =
        payDb.queryForObject(
            "SELECT status FROM tcc_fence_log WHERE xid=? AND branch_id=?",
            Integer.class,
            identity.xid,
            identity.payBranch);
    assertTrue(
        "TRYING".equals(receipt) && payFence == 1
            || "CANCELLED".equals(receipt) && Set.of(3, 4).contains(payFence),
        "PAY_LOCAL_VECTOR_UNPROVEN");
    assertEquals(
        1,
        userDb.queryForObject(
            "SELECT status FROM tcc_fence_log WHERE xid=? AND branch_id=?",
            Integer.class,
            identity.xid,
            identity.userBranch));
    return Map.of(
        "payReceiptState",
        receipt,
        "payFenceStatus",
        payFence,
        "userState",
        "TRYING",
        "userBalance",
        "90.00",
        "userFrozen",
        "10.00",
        "userLedgerCount",
        0,
        "proxyActiveConnections",
        0,
        "ownedChecksums",
        ownedEvidenceChecksums());
  }

  /** TC 重试枚举可异步变化；独立核非终态，不把枚举瞬态纳入数据库静止比较。 */
  private GlobalStatus disconnectedTcStatus() throws Exception {
    GlobalStatus status = TransactionManagerHolder.get().getStatus(originalTcIdentity().xid);
    assertNotNull(status);
    assertNotEquals(GlobalStatus.Begin, status);
    assertNotEquals(GlobalStatus.UnKnown, status);
    assertFalse(
        Set.of(
                GlobalStatus.Committed,
                GlobalStatus.Rollbacked,
                GlobalStatus.TimeoutRollbacked,
                GlobalStatus.Finished)
            .contains(status),
        "DISCONNECTED_TC_MUST_REMAIN_UNSETTLED");
    return status;
  }

  /** 两次连续真实观察一致才采集；不是采集失败后的重试或排表。 */
  private Map<String, Object> awaitDisconnectedStable(CommitPause pause) throws Exception {
    java.util.concurrent.atomic.AtomicReference<Map<String, Object>> prior =
        new java.util.concurrent.atomic.AtomicReference<>();
    await(
        () -> {
          try {
            Map<String, Object> current = disconnectedObservation(pause);
            return current.equals(prior.getAndSet(current));
          } catch (Exception failure) {
            throw new IllegalStateException("DISCONNECTED_OBSERVATION_FAILED", failure);
          }
        },
        30);
    assertUserTryingOverHttp();
    return prior.get();
  }

  /** 失败也先解除本测试通道，再等待自有调用线程；不为清理另发决议。 */
  private void finishRmBridgeCaller(
      CommitPause pause,
      ExecutorService caller,
      Future<PayTccExecutionVO> recovery,
      PayTccTransactionClient original,
      CountedTransactions counted,
      Throwable primary,
      Map<String, Object> channel)
      throws Exception {
    Throwable cleanupFailure = null;
    try {
      userRmTcProxy.restore();
    } catch (Throwable failure) {
      cleanupFailure = failure;
    }
    pause.release.countDown();
    try {
      caller.shutdownNow();
      assertTrue(
          caller.awaitTermination(15, TimeUnit.SECONDS), "ORIGINAL_RECOVER_WORKER_UNRESOLVED");
      if (recovery != null) recovery.get(0, TimeUnit.SECONDS);
      channel.put("recoverWorkerStoppedAndResultKnown", true);
    } catch (Throwable failure) {
      if (cleanupFailure == null) cleanupFailure = failure;
      else cleanupFailure.addSuppressed(failure);
      channel.put("recoverWorkerStoppedAndResultKnown", false);
    } finally {
      try {
        ReflectionTestUtils.setField(pause.coordinator, "transactions", original);
        channel.put("originalTransactionsRestored", true);
      } catch (Throwable failure) {
        if (cleanupFailure == null) cleanupFailure = failure;
        else cleanupFailure.addSuppressed(failure);
        channel.put("originalTransactionsRestored", false);
      }
      channel.put("sdkCalls", counted.observation());
      channel.put("runId", batch);
      channel.put("scenarioId", RM_LINK_SCENARIO);
      channel.put("automaticActionsAllowed", false);
      channel.put("retention", "NO_AUTOMATIC_EXPIRY");
      try {
        Files.writeString(
            artifacts.resolve("user-rm-tc-link-observation-" + UUID.randomUUID() + ".json"),
            JSON.writerWithDefaultPrettyPrinter().writeValueAsString(channel),
            StandardOpenOption.CREATE_NEW);
      } catch (Throwable failure) {
        if (cleanupFailure == null) cleanupFailure = failure;
        else cleanupFailure.addSuppressed(failure);
      }
    }
    if (cleanupFailure != null) {
      preserve = true;
      if (primary != null) primary.addSuppressed(cleanupFailure);
      else if (cleanupFailure instanceof Exception exception) throw exception;
      else throw (Error) cleanupFailure;
    }
  }

  /** 只从固定 Maven 测试产物加载原纯 JDK proxy，不导入 User 测试或业务 Bean。 */
  private static final class UserRmTcProxy implements AutoCloseable {
    /** 与 Pay 测试应用隔离，仅允许平台类作为父 loader。 */
    private final URLClassLoader loader;

    /** 原 public 类，所有网络实现仍来自冻结 User 字节码。 */
    private final Class<?> type;

    /** 安全来源及关闭审计，不存原业务身份。 */
    private final Map<String, Object> identity;

    /** 原实例在唯一 User 启动之前才创建；preflight 不打开 socket。 */
    private Object proxy;

    /** 原 owned 监听端口，在 close 后仍保留供安全审计。 */
    private int port;

    /** 只表示本适配器已调用关闭，不代表原 helper 已 join 所有线程。 */
    private boolean closed;

    private UserRmTcProxy(URLClassLoader loader, Class<?> type, Map<String, Object> identity) {
      this.loader = loader;
      this.type = type;
      this.identity = identity;
    }

    /** 任何 fixture 创建前核定源文件/两 class，失败不换其他实现兜底。 */
    private static UserRmTcProxy preflight() throws Exception {
      Path root = Path.of("..", "cloud-mall-user").toAbsolutePath().normalize();
      Path source = root.resolve("src/test/java/com/cloudmall/user/RmTcLinkProxy.java");
      Path classes = root.resolve("target/test-classes").toRealPath();
      Map<String, String> pins =
          Map.of(
              "com.cloudmall.user.RmTcLinkProxy",
                  "68056AD73570ED5783431B74D06FCB79B37A4937A92DDFEDADAC9A9F473C9D3D",
              "com.cloudmall.user.RmTcLinkProxy$ProxyConnection",
                  "267552FAB251D93B416AA7CD24320E6F82BD7BEFE80A45CD0A75A4F5C3C2C718");
      assertTrue(Files.isRegularFile(source), "USER_PROXY_SOURCE_PRECONDITION_MISSING");
      String sourceSha = sha256(Files.readAllBytes(source));
      assertEquals("7b30862e008c36381adc50834d1970b4efc80a099571b61c05643abc0444df64", sourceSha);
      for (var entry : pins.entrySet()) {
        Path file = classes.resolve(entry.getKey().replace('.', '/') + ".class");
        assertTrue(Files.isRegularFile(file), "USER_PROXY_CLASS_PRECONDITION_MISSING");
        assertEquals(entry.getValue().toLowerCase(Locale.ROOT), sha256(Files.readAllBytes(file)));
      }
      URLClassLoader loader =
          new URLClassLoader(
              new URL[] {classes.toUri().toURL()}, ClassLoader.getPlatformClassLoader());
      try {
        Map<String, Object> loaded = new LinkedHashMap<>();
        for (var entry : pins.entrySet()) {
          Class<?> loadedType = Class.forName(entry.getKey(), true, loader);
          assertSame(loader, loadedType.getClassLoader());
          Path actual =
              Path.of(loadedType.getProtectionDomain().getCodeSource().getLocation().toURI())
                  .toRealPath();
          assertEquals(classes, actual, "USER_PROXY_CODE_SOURCE_CHANGED");
          try (var resource =
              loadedType.getResourceAsStream("/" + entry.getKey().replace('.', '/') + ".class")) {
            assertNotNull(resource);
            String digest = sha256(resource.readAllBytes());
            assertEquals(entry.getValue().toLowerCase(Locale.ROOT), digest);
            loaded.put(
                entry.getKey(),
                Map.of(
                    "classSha256", digest, "codeSource", "../cloud-mall-user/target/test-classes"));
          }
        }
        Map<String, Object> identity = new LinkedHashMap<>();
        identity.put("sourceSha256", sourceSha);
        identity.put("loadedClasses", loaded);
        identity.put("parentLoader", "PLATFORM");
        identity.put("target", "127.0.0.1:18091");
        identity.put("automaticActionsAllowed", false);
        identity.put("retention", "NO_AUTOMATIC_EXPIRY");
        return new UserRmTcProxy(
            loader, Class.forName("com.cloudmall.user.RmTcLinkProxy", false, loader), identity);
      } catch (Exception | AssertionError failure) {
        loader.close();
        throw failure;
      }
    }

    private void start() throws Exception {
      try {
        proxy = type.getConstructor(String.class, int.class).newInstance("127.0.0.1", 18091);
      } catch (java.lang.reflect.InvocationTargetException failure) {
        throwOriginal(failure.getCause());
      }
      port = ((Number) invoke("port")).intValue();
      identity.put("port", port);
    }

    private Object invoke(String method) throws Exception {
      assertNotNull(proxy, "OWNED_PROXY_NOT_STARTED");
      try {
        return type.getMethod(method).invoke(proxy);
      } catch (java.lang.reflect.InvocationTargetException failure) {
        return throwOriginal(failure.getCause());
      }
    }

    private static Object throwOriginal(Throwable failure) throws Exception {
      if (failure instanceof Exception exception) throw exception;
      if (failure instanceof Error error) throw error;
      throw new IllegalStateException("OWNED_PROXY_INVOCATION_FAILED", failure);
    }

    private int port() {
      return port;
    }

    private int active() throws Exception {
      return ((Number) invoke("activeConnections")).intValue();
    }

    private long accepted() throws Exception {
      return ((Number) invoke("acceptedConnections")).longValue();
    }

    private long bytes() throws Exception {
      return ((Number) invoke("forwardedBytes")).longValue();
    }

    private void block() throws Exception {
      invoke("block");
    }

    private void restore() throws Exception {
      invoke("restore");
    }

    private Map<String, Object> observation() throws Exception {
      return Map.of(
          "activeConnections",
          active(),
          "acceptedConnections",
          accepted(),
          "forwardedBytes",
          bytes());
    }

    /** 原 proxy.close 后关闭 loader，独立保留 socket 观察与线程未 join 限度。 */
    @Override
    public void close() throws Exception {
      if (closed) return;
      try {
        identity.put("proxyCloseInvoked", proxy != null);
        if (proxy != null) {
          invoke("close");
          assertEquals(0, active());
          identity.put("activeConnectionsAfterClose", 0);
        }
      } finally {
        loader.close();
        closed = true;
        identity.put("classLoaderClosed", true);
        identity.put("allProxyThreadsJoinedProven", false);
      }
    }
  }

  /** 无故障的原官方 SDK 透明计数；只比较内存原 XID，不把计数当作资金终态。 */
  private static final class CountedTransactions implements PayTccTransactionClient {
    /** 原官方接口，所有调用原样委派。 */
    private final PayTccTransactionClient delegate;

    /** 实际委派次数，不以 execution 行数替代 begin 证据。 */
    private final AtomicInteger begins = new AtomicInteger(),
        commits = new AtomicInteger(),
        rollbacks = new AtomicInteger();

    /** begin 正常返回的原身份，只留内存供精确比较。 */
    private String startedXid;

    private CountedTransactions(PayTccTransactionClient delegate) {
      this.delegate = Objects.requireNonNull(delegate);
    }

    /** 原 SDK begin 后保存实际返回身份，异常不改写成成功。 */
    @Override
    public String begin() throws Exception {
      begins.incrementAndGet();
      startedXid = delegate.begin();
      return startedXid;
    }

    /** 原 SDK commit 仅透明计数。 */
    @Override
    public void commit(String xid) throws Exception {
      commits.incrementAndGet();
      delegate.commit(xid);
    }

    /** 原 SDK rollback 仅透明计数，不更换 XID。 */
    @Override
    public void rollback(String xid) throws Exception {
      rollbacks.incrementAndGet();
      delegate.rollback(xid);
    }

    private void assertSingleBegin(String expected) {
      assertEquals(1, begins.get());
      assertEquals(0, commits.get());
      assertTrue(expected.equals(startedXid), "ORIGINAL_SDK_BEGIN_IDENTITY_CHANGED");
    }

    private Map<String, Object> observation() {
      return Map.of(
          "beginCalls",
          begins.get(),
          "beginReturned",
          startedXid != null,
          "commitCalls",
          commits.get(),
          "rollbackCalls",
          rollbacks.get(),
          "originalSdkDelegate",
          true);
    }
  }

  /** 必须仍命中原决议前屏障与三个 scheduler 暂停权，不能用后来终态代替活跃事实。 */
  private void assertPreparedWindow(CommitPause pause) throws Exception {
    assertTrue(pauseOwned && parentRunning.get() && cancelRunning.get());
    assertTrue(pause.ownsRecovery && pause.recoveryRunning.get());
    assertEquals(1, pause.decisionCalls.get());
    assertEquals(1L, pause.release.getCount());
    assertNotNull(pause.response);
    assertFalse(pause.response.isDone(), "ORIGINAL_SUCCESS_OWNER_ALREADY_FINISHED");
    assertEquals("NONE", decision());
    assertEquals(
        "RUNNING",
        payDb.queryForObject(
            "SELECT state FROM pay_tcc_execution WHERE pay_no=?", String.class, request.payNo));
    assertEquals("PENDING", paymentState());
    assertEquals(
        GlobalStatus.Begin, TransactionManagerHolder.get().getStatus(originalTcIdentity().xid));
  }

  /** 只采安全四源包；校验完整 owned checksum 与当前原身份，侧文件明确标记对应 TC 原阶段。 */
  private Path capturePhase(String phaseRole, Path tcArtifact, CommitPause pause) throws Exception {
    return capturePhase(
        phaseRole,
        tcArtifact,
        pause,
        "CANCEL_ORIGINAL_ROLLBACK",
        tcArtifact == null
            ? "NO_EXECUTION_YET"
            : pause != null
                ? "ORIGINAL_ACTIVE_REGISTRATIONS_ONLY"
                : "ORIGINAL_ROLLBACK_NATIVE_TERMINAL");
  }

  /** 仅显式传入代表场景和 TC 适用性，采集与强 checksum 门继续使用同一实现。 */
  private Path capturePhase(
      String phaseRole,
      Path tcArtifact,
      CommitPause pause,
      String scenarioId,
      String tcApplicability)
      throws Exception {
    evidenceScenarioId = scenarioId;
    lastEvidencePhase = phaseRole;
    if (pause != null) assertPreparedWindow(pause);
    Map<String, Object> before = ownedEvidenceChecksums();
    long started = System.nanoTime();
    Path phase =
        PayTccStageEvidence.capture(
            artifacts,
            batch,
            phaseRole,
            request.payNo,
            request.orderNo,
            7,
            skuId,
            month,
            Map.of("pay", payDb, "user", userDb, "order", orderDb, "stock", stockDb),
            Map.of("scenarioId", scenarioId),
            Map.of("scenarioId", scenarioId, "observedOutcome", phaseRole));
    if (pause != null) assertPreparedWindow(pause);
    Map<String, Object> after = ownedEvidenceChecksums();
    assertEquals(before, after, "PHASE_WINDOW_OWNED_DATABASES_CHANGED");
    var facts = JSON.readTree(Files.readAllBytes(phase));
    assertFalse(facts.at("/sources/pay/sourceChanged").asBoolean(true));
    if (phaseEvidenceIdentity != null) {
      OriginalTcIdentity identity = originalTcIdentity();
      assertEquals(identity.executionId, facts.at("/subject/executionId").longValue());
      assertEquals(
          sha256(identity.xid.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          facts.at("/subject/xidSha256").textValue());
      assertEquals(
          sha256(
              Long.toString(identity.payBranch).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          facts.at("/subject/payBranchSha256").textValue());
      assertEquals(
          sha256(
              Long.toString(identity.userBranch).getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          facts.at("/subject/userBranchSha256").textValue());
    }
    if (pause != null)
      assertEquals("Begin", facts.at("/sources/tc/observedGlobalStatus").textValue());
    Map<String, Object> link = new LinkedHashMap<>();
    link.put("runId", batch);
    link.put("scenarioId", scenarioId);
    link.put("phase", phaseRole);
    link.put("payNo", request.payNo);
    link.put("orderNo", request.orderNo);
    link.put("phaseArtifact", artifactReference(phase));
    link.put("tcArtifact", tcArtifact == null ? null : artifactReference(tcArtifact));
    link.put("tcApplicability", tcApplicability);
    link.put("collectorElapsedNanos", System.nanoTime() - started);
    link.put("ownerStillPausedBeforeAndAfter", pause != null);
    link.put("ownedChecksumsBefore", before);
    link.put("ownedChecksumsAfter", after);
    link.put("ownedChecksumsUnchanged", true);
    link.put("automaticActionsAllowed", false);
    link.put("retention", "NO_AUTOMATIC_EXPIRY");
    Files.writeString(
        artifacts.resolve("cancel-phase-link-" + phaseRole + "-" + UUID.randomUUID() + ".json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(link),
        StandardOpenOption.CREATE_NEW);
    return phase;
  }

  /** 固定四个 owned schema 的全表 checksum，不输出业务行或原 XID。 */
  private Map<String, Object> ownedEvidenceChecksums() {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var entry :
        Map.of("pay", payDb, "user", userDb, "order", orderDb, "stock", stockDb).entrySet()) {
      List<String> tables = entry.getValue().queryForList("SHOW TABLES", String.class);
      for (String table : tables)
        if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("INVALID_OWNED_TABLE");
      result.put(
          entry.getKey(),
          entry.getValue().queryForList("CHECKSUM TABLE " + String.join(",", tables)));
    }
    return result;
  }

  private static Map<String, Object> artifactReference(Path path) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    return Map.of(
        "relativePath",
        artifacts.relativize(path).toString().replace('\\', '/'),
        "bytes",
        bytes.length,
        "sha256",
        sha256(bytes));
  }

  private static String sha256(byte[] bytes) throws Exception {
    return HexFormat.of()
        .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
  }

  /** 失败材料不可覆盖且无自动到期；记录失败类别，不写 message/stack/原身份。 */
  private void recordEvidenceFailure(Throwable failure) {
    try {
      Files.writeString(
          artifacts.resolve("cancel-phase-failure-" + UUID.randomUUID() + ".json"),
          JSON.writeValueAsString(
              Map.of(
                  "runId",
                  batch,
                  "scenarioId",
                  evidenceScenarioId,
                  "lastRequestedPhase",
                  lastEvidencePhase,
                  "category",
                  failure instanceof AssertionError
                      ? "EVIDENCE_OR_SCENARIO_ASSERTION_FAILED"
                      : "EVIDENCE_OR_SCENARIO_OPERATION_FAILED",
                  "preserve",
                  true,
                  "retention",
                  "NO_AUTOMATIC_EXPIRY")),
          StandardOpenOption.CREATE_NEW);
    } catch (Exception unavailable) {
      failure.addSuppressed(new IllegalStateException("SAFE_FAILURE_ARTIFACT_UNAVAILABLE"));
    }
  }

  /** 每次真实入口返回后都核对未绑定身份，并避免在失败信息中打印探针 XID。 */
  private void assertBeginUnknownView(PayTccExecutionVO view) {
    assertEquals("BEGIN_UNKNOWN", view.state);
    assertEquals("NONE", view.decision);
    assertTrue(view.xid == null, "BUSINESS_XID_MUST_REMAIN_NULL");
    assertTrue(RootContext.getXID() == null, "CALLER_CONTEXT_MUST_BE_EMPTY");
  }

  /** 核对原执行身份与两侧空分支事实；返回值仅用于内存比较，不序列化原操作键。 */
  private Map<String, Object> assertBeginUnknownFacts(Map<String, Object> expectedIdentity) {
    Map<String, Object> execution =
        payDb.queryForMap(
            "SELECT id,pay_no,user_id,order_no,amount,operation_key,ledger_key,state,decision,xid"
                + " FROM pay_tcc_execution WHERE pay_no=?",
            request.payNo);
    assertEquals("BEGIN_UNKNOWN", execution.get("state"));
    assertEquals("NONE", execution.get("decision"));
    assertTrue(execution.get("xid") == null, "BUSINESS_XID_MUST_REMAIN_SQL_NULL");
    Map<String, Object> identity = new LinkedHashMap<>(execution);
    for (String field : List.of("state", "decision", "xid")) identity.remove(field);
    if (expectedIdentity != null)
      assertTrue(expectedIdentity.equals(identity), "BEGIN_UNKNOWN_EXECUTION_IDENTITY_CHANGED");
    assertTrue(((Number) identity.get("id")).longValue() > 0, "EXECUTION_ID_MUST_EXIST");
    assertTrue(request.payNo.equals(identity.get("pay_no")), "EXECUTION_PAY_TUPLE_CHANGED");
    assertEquals(request.userId.longValue(), ((Number) identity.get("user_id")).longValue());
    assertTrue(request.orderNo.equals(identity.get("order_no")), "EXECUTION_ORDER_TUPLE_CHANGED");
    assertEquals(0, request.amount.compareTo((BigDecimal) identity.get("amount")));
    assertTrue(
        ("tcc-debit:" + request.payNo).equals(identity.get("operation_key")),
        "EXECUTION_OPERATION_KEY_CHANGED");
    assertTrue(request.payNo.equals(identity.get("ledger_key")), "EXECUTION_LEDGER_KEY_CHANGED");
    assertEquals(
        "TCC",
        payDb.queryForObject(
            "SELECT debit_mode FROM pay_record WHERE pay_no=?", String.class, request.payNo));
    assertEquals(1, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_execution", Integer.class));
    for (String table :
        List.of(
            "pay_tcc_receipt",
            "pay_tcc_dispatch",
            "tcc_fence_log",
            "pay_tcc_delivery",
            "pay_tcc_cancel"))
      assertEquals(
          0,
          payDb.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class),
          "UNBOUND_EXECUTION_MUST_HAVE_NO_PAY_BRANCH_OR_DELIVERY");
    for (String table :
        List.of(
            "user_tcc_receive",
            "balance_debit_attempt",
            "balance_tcc_debit",
            "tcc_fence_log",
            "balance_ledger"))
      assertEquals(
          0,
          userDb.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class),
          "UNBOUND_EXECUTION_MUST_HAVE_NO_USER_BRANCH_OR_LEDGER");
    assertEquals(0, proxies.get("user").postCount());
    assertEquals("PENDING", paymentState());
    assertFunds("100.00", 0, 0);
    assertEquals("PENDING_PAYMENT", orderState());
    assertEquals(
        "RESERVED",
        stockDb.queryForObject(
            "SELECT status FROM stock_reservation_request WHERE order_no=?",
            String.class,
            request.orderNo));
    assertStock(9, 1, 0);
    assertTrue(RootContext.getXID() == null, "CALLER_CONTEXT_MUST_BE_EMPTY");
    return identity;
  }

  private String decision() {
    return payDb.queryForObject(
        "SELECT decision FROM pay_tcc_execution WHERE pay_no=?", String.class, request.payNo);
  }

  /** 只暂停成功线程的决议点；mock-fail不占成功/callback的同实例同步锁。 */
  private final class CommitPause implements AutoCloseable {
    /** 原协调器及独立事务Store代理。 */
    private final Object coordinator;

    private final com.cloudmall.pay.service.impl.execution.PayTccExecutionStore original;

    /** 测试持有恢复调度权时不让定时器提前清算。 */
    private final AtomicBoolean recoveryRunning;

    private boolean ownsRecovery = true;

    /** 决议点与成功线程继续执行的屏障。 */
    private final CountDownLatch ready = new CountDownLatch(1);

    private final CountDownLatch release = new CountDownLatch(1);

    /** 当前原支付COMMIT决议的实际拦截次数。 */
    private final AtomicInteger decisionCalls = new AtomicInteger();

    /** 真实HTTP成功请求线程。 */
    private final ExecutorService caller = Executors.newSingleThreadExecutor();

    private Future<Response> response;

    /** 当前调用的屏障上限，只有四阶段代表选择 45 秒；其他仍为 15 秒。 */
    private final int barrierTimeoutSeconds;

    /** 与本调用 HTTP 一致的 Future 有界等待；默认 20 秒，代表最多 60 秒。 */
    private final int responseTimeoutSeconds;

    private CommitPause(boolean commitFirst) throws Exception {
      this(commitFirst, 15, 20);
    }

    private CommitPause(boolean commitFirst, int barrierTimeoutSeconds, int responseTimeoutSeconds)
        throws Exception {
      if (barrierTimeoutSeconds <= 0
          || barrierTimeoutSeconds > 45
          || responseTimeoutSeconds < barrierTimeoutSeconds
          || responseTimeoutSeconds > 60)
        throw new IllegalArgumentException("invalid bounded decision pause");
      this.barrierTimeoutSeconds = barrierTimeoutSeconds;
      this.responseTimeoutSeconds = responseTimeoutSeconds;
      coordinator =
          org.springframework.test.util.AopTestUtils.getUltimateTargetObject(
              application.getBean(com.cloudmall.pay.service.PayTccCoordinatorService.class));
      original =
          (com.cloudmall.pay.service.impl.execution.PayTccExecutionStore)
              ReflectionTestUtils.getField(coordinator, "executions");
      recoveryRunning =
          (AtomicBoolean)
              ReflectionTestUtils.getField(
                  application.getBean(com.cloudmall.pay.service.impl.execution.PayTccRecoveryScheduler.class),
                  "running");
      await(() -> recoveryRunning.compareAndSet(false, true), 10);
      var intercepted =
          org.mockito.Mockito.mock(
              com.cloudmall.pay.service.impl.execution.PayTccExecutionStore.class,
              org.mockito.AdditionalAnswers.delegatesTo(original));
      org.mockito.Mockito.doAnswer(
              invocation -> {
                decisionCalls.incrementAndGet();
                com.cloudmall.pay.domain.po.PayTccExecutionPO chosen =
                    commitFirst
                        ? original.decideOwned(
                            invocation.getArgument(0),
                            invocation.getArgument(1),
                            invocation.getArgument(2))
                        : null;
                ready.countDown();
                if (!release.await(barrierTimeoutSeconds, TimeUnit.SECONDS))
                  throw new IllegalStateException("decision barrier timed out");
                return commitFirst
                    ? chosen
                    : original.decideOwned(
                        invocation.getArgument(0),
                        invocation.getArgument(1),
                        invocation.getArgument(2));
              })
          .when(intercepted)
          .decideOwned(
              org.mockito.ArgumentMatchers.argThat(
                  owned -> owned != null && request.payNo.equals(owned.payNo)),
              org.mockito.ArgumentMatchers.anyString(),
              org.mockito.ArgumentMatchers.eq("COMMIT"));
      ReflectionTestUtils.setField(coordinator, "executions", intercepted);
    }

    private void start() throws Exception {
      response =
          caller.submit(
              () ->
                  post(
                      "/api/payments/" + request.payNo + "/mock-success",
                      Map.of(),
                      Map.of(),
                      responseTimeoutSeconds));
      assertTrue(ready.await(10, TimeUnit.SECONDS));
      assertEquals(1, decisionCalls.get(), "屏障必须命中原支付owned决议入口一次");
    }

    private void resumeRecovery() {
      if (ownsRecovery) {
        ownsRecovery = false;
        recoveryRunning.set(false);
      }
    }

    /** 恢复原Store/屏障；响应或线程结果不明时保留现场，不以caller退出代替清算证据。 */
    @Override
    public void close() throws Exception {
      release.countDown();
      Exception responseFailure = null;
      try {
        if (response != null) response.get(responseTimeoutSeconds, TimeUnit.SECONDS);
      } catch (Exception failure) {
        // 客户端线程退出不能证明服务端请求结束；无响应时保留原现场。
        preserve = true;
        responseFailure = failure;
        throw failure;
      } finally {
        try {
          ReflectionTestUtils.setField(coordinator, "executions", original);
          resumeRecovery();
          caller.shutdownNow();
          assertTrue(caller.awaitTermination(15, TimeUnit.SECONDS), "取消屏障caller未退出，保留现场");
        } catch (Exception | AssertionError cleanupFailure) {
          preserve = true;
          if (responseFailure != null) responseFailure.addSuppressed(cleanupFailure);
          else throw cleanupFailure;
        }
      }
    }
  }

  /** 仅模拟一次真实 begin 响应丢失；探针身份只留内存及原 fixture 清理集合。 */
  private static final class LostBeginTransactions implements PayTccTransactionClient {
    /** 协调器原有的官方 TC 窄接口，业务 RPC 始终委托该真实实现。 */
    private final PayTccTransactionClient delegate;

    /** 分别统计实际业务入口触发的 begin、commit、rollback，排除后续测试清理。 */
    private final AtomicInteger begins = new AtomicInteger(),
        commits = new AtomicInteger(),
        rollbacks = new AtomicInteger();

    /** 仅第一次成功的真实 begin 丢失响应。 */
    private final AtomicBoolean loseNextBeginResponse = new AtomicBoolean(true);

    /** 实际注入的响应丢失次数。 */
    private final AtomicInteger lostResponses = new AtomicInteger();

    /** 仅测试知道的已启动 TC 身份；禁止序列化或补入业务 subject。 */
    private String probeXid;

    private LostBeginTransactions(PayTccTransactionClient delegate) {
      this.delegate = Objects.requireNonNull(delegate);
    }

    /** 调用真实 begin 并保留测试清理身份，随后仅一次抛固定 IOException。 */
    @Override
    public String begin() throws Exception {
      begins.incrementAndGet();
      String started = delegate.begin();
      probeXid = started;
      if (started != null && !started.isBlank()) xids.add(started);
      if (loseNextBeginResponse.compareAndSet(true, false)) {
        lostResponses.incrementAndGet();
        throw new java.io.IOException("test begin response lost");
      }
      return started;
    }

    /** 统计业务 commit 后调用原官方接口，不模拟提交结果。 */
    @Override
    public void commit(String xid) throws Exception {
      commits.incrementAndGet();
      delegate.commit(xid);
    }

    /** 统计业务 rollback 后调用原官方接口，不模拟回滚结果。 */
    @Override
    public void rollback(String xid) throws Exception {
      rollbacks.incrementAndGet();
      delegate.rollback(xid);
    }

    /** 重放和恢复不得取得新的 begin、Try 或业务决议权限。 */
    private void assertBusinessCalls() {
      assertEquals(1, begins.get());
      assertEquals(1, lostResponses.get());
      assertEquals(0, commits.get());
      assertEquals(0, rollbacks.get());
    }
  }

  private com.cloudmall.pay.domain.vo.PayVO paymentView() {
    var payment = new com.cloudmall.pay.domain.vo.PayVO();
    payment.payNo = request.payNo;
    payment.orderNo = request.orderNo;
    payment.userId = request.userId;
    payment.amount = request.amount;
    payment.status = "PENDING";
    return payment;
  }

  private void completeCancellation() throws Exception {
    await(
        () -> {
          payDb.update(
              "UPDATE pay_tcc_cancel SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
                  + " pay_no=?",
              request.payNo);
          try {
            Response result =
                post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of());
            assertTrue(Set.of(200, 202).contains(result.status()), result.body().toString());
            if (result.status() == 200)
              assertEquals("FAILED", result.body().path("data").path("status").asText());
            return result.status() == 200;
          } catch (Exception failure) {
            throw new RuntimeException(failure);
          }
        },
        45);
    assertEquals("COMPLETED", cancelStore.read(request.payNo).state);
  }

  private static void stopReplacement() throws Exception {
    if (replacementPay != null) {
      replacementPay.destroyForcibly();
      replacementPay.waitFor(15, TimeUnit.SECONDS);
      replacementPay = null;
    }
  }

  private void createPublic() throws Exception {
    Response response =
        post(
            "/api/payments",
            Map.of("orderNo", request.orderNo, "payAmount", "10.00"),
            Map.of("Idempotency-Key", request.orderNo, "X-User-Id", "8"));
    assertEquals(200, response.status(), response.body().toString());
    request.payNo = response.body().path("data").path("payNo").asText();
    assertFalse(request.payNo.isBlank());
  }

  private Map<String, Object> callback(boolean success, String id) {
    return Map.of(
        "payNo",
        request.payNo,
        "userId",
        7,
        "amount",
        "10.00",
        "success",
        success,
        "callbackId",
        id);
  }

  private Map<String, String> callbackHeaders() {
    return Map.of("X-Internal-Callback-Token", "cloudmall-local-callback");
  }

  private static Response post(String path, Object body, Map<String, String> headers)
      throws Exception {
    return post(path, body, headers, 20);
  }

  /** 只为原代表的暂停调用提供一致有界 HTTP 预算，普通调用仍由上面的 20 秒入口执行。 */
  private static Response post(
      String path, Object body, Map<String, String> headers, int timeoutSeconds) throws Exception {
    if (timeoutSeconds <= 0 || timeoutSeconds > 60)
      throw new IllegalArgumentException("invalid HTTP timeout");
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(gatewayUrl + path))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("Authorization", "Bearer " + TOKEN)
            .header("Content-Type", "application/json");
    headers.forEach(request::setHeader);
    HttpResponse<String> response =
        HTTP.send(
            request
                .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
                .build(),
            HttpResponse.BodyHandlers.ofString());
    return new Response(response.statusCode(), JSON.readTree(response.body()));
  }

  private record Response(int status, com.fasterxml.jackson.databind.JsonNode body) {}

  private static void startGateway() throws Exception {
    docker(
        "exec",
        redisContainer,
        "redis-cli",
        "-a",
        "root",
        "SET",
        "auth:token:" + TOKEN,
        "7:USER",
        "EX",
        "7200");
    int port = availablePort();
    gatewayUrl = "http://127.0.0.1:" + port;
    gatewayProcess =
        new ProcessBuilder(
                javaExecutable(),
                "-Xmx256m",
                "-jar",
                Path.of(
                        "..",
                        "cloud-mall-gateway",
                        "target",
                        "cloud-mall-gateway-0.1.0-SNAPSHOT.jar")
                    .toAbsolutePath()
                    .toString(),
                "--server.port=" + port,
                "--spring.cloud.nacos.discovery.group=" + batch,
                "--spring.cloud.nacos.discovery.register-enabled=false",
                "--spring.cloud.loadbalancer.cache.enabled=false",
                "--spring.redis.port=" + redisPort,
                "--spring.redis.password=root",
                "--spring.cloud.sentinel.eager=false",
                "--cloudmall.sentinel.nacos.group=" + batch)
            .redirectErrorStream(true)
            .redirectOutput(artifacts.resolve("gateway.log").toFile())
            .start();
    await(
        () -> {
          try {
            return HTTP.send(
                        HttpRequest.newBuilder(URI.create(gatewayUrl + "/actuator/health"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.discarding())
                    .statusCode()
                == 200;
          } catch (Exception unavailable) {
            return false;
          }
        },
        90);
  }

  private void forceDue() {
    payDb.update(
        "UPDATE pay_tcc_delivery SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
            + " pay_no=?",
        request.payNo);
  }

  private String paymentState() {
    return payDb.queryForObject(
        "SELECT status FROM pay_record WHERE pay_no=?", String.class, request.payNo);
  }

  private String orderState() {
    return orderDb.queryForObject(
        "SELECT status FROM mall_order_" + month + " WHERE order_no=?",
        String.class,
        request.orderNo);
  }

  private void assertStock(int available, int reserved, int sold) {
    Map<String, Object> row =
        stockDb.queryForMap(
            "SELECT available_quantity,reserved_quantity,sold_quantity FROM stock_sku WHERE"
                + " sku_id=?",
            skuId);
    assertEquals(available, ((Number) row.get("available_quantity")).intValue());
    assertEquals(reserved, ((Number) row.get("reserved_quantity")).intValue());
    assertEquals(sold, ((Number) row.get("sold_quantity")).intValue());
  }

  private void assertFunds(String balance, int debits, int refunds) {
    assertEquals(
        balance, userDb.queryForObject("SELECT balance FROM mall_user WHERE id=7", String.class));
    assertEquals(
        "0.00",
        userDb.queryForObject("SELECT frozen_balance FROM mall_user WHERE id=7", String.class));
    assertEquals(
        debits,
        userDb.queryForObject(
            "SELECT COUNT(*) FROM balance_ledger WHERE operation_type='DEBIT'", Integer.class));
    assertEquals(
        refunds,
        userDb.queryForObject(
            "SELECT COUNT(*) FROM balance_ledger WHERE operation_type='REFUND'", Integer.class));
  }

  private static String[] payArguments() {
    return new String[] {
      "--server.port=0",
      "--cloudmall.pay.debit-mode=TCC",
      "--cloudmall.pay.tcc-cancel.enabled=true",
      "--cloudmall.pay.tcc-cancel.interval-ms=200",
      "--cloudmall.pay.tcc-entry.enabled=true",
      "--cloudmall.pay.tcc-recovery.enabled=true",
      "--spring.datasource.url=" + payUrl,
      "--spring.cloud.nacos.discovery.group=" + batch,
      "--spring.cloud.nacos.discovery.register-enabled=true",
      "--spring.cloud.nacos.discovery.ip=127.0.0.1",
      "--spring.cloud.loadbalancer.cache.enabled=false",
      "--seata.enabled=true",
      "--seata.application-id=" + batch + "-pay",
      "--seata.enable-auto-data-source-proxy=false",
      "--cloudmall.pay.tcc-bridge.enabled=true",
      "--cloudmall.pay.tcc-receipt.enabled=true",
      "--cloudmall.pay.tcc-coordinator.enabled=true",
      "--cloudmall.pay.tcc-coordinator.timeout-ms=120000",
      "--cloudmall.pay.tcc-delivery.enabled=true",
      "--cloudmall.pay.tcc-delivery.interval-ms=200"
    };
  }

  private static void startService(String service) throws Exception {
    ports.put(service, availablePort());
    TccResponseProxy proxy = new TccResponseProxy(ports.get(service));
    proxies.put(service, proxy);
    List<String> command =
        new ArrayList<>(
            List.of(
                javaExecutable(),
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Xmx256m",
                "-jar",
                Path.of(
                        "..",
                        "cloud-mall-" + service,
                        "target",
                        "cloud-mall-" + service + "-0.1.0-SNAPSHOT.jar")
                    .toAbsolutePath()
                    .toString(),
                "--server.port=" + ports.get(service),
                "--spring.datasource.url=" + urls.get(service),
                "--spring.cloud.nacos.discovery.group=" + batch,
                "--spring.cloud.nacos.discovery.ip=127.0.0.1",
                "--spring.cloud.nacos.discovery.port=" + proxy.port(),
                "--spring.rabbitmq.virtual-host=" + batch,
                "--spring.redis.port=" + redisPort,
                "--spring.cloud.sentinel.eager=false",
                "--seata.enable-auto-data-source-proxy=false"));
    if (service.equals("user"))
      command.addAll(
          List.of(
              "--seata.enabled=true",
              "--seata.application-id=" + batch + "-user",
              "--seata.service.grouplist.default=127.0.0.1:" + userRmTcProxy.port(),
              "--cloudmall.user.tcc-debit.enabled=true",
              "--cloudmall.user.tcc-resource.enabled=true",
              "--cloudmall.user.tcc-http.enabled=true"));
    else command.add("--seata.enabled=false");
    if (service.equals("order"))
      command.addAll(
          List.of(
              "--cloudmall.order.stock-action-recovery-interval-ms=200",
              "--cloudmall.order.stock-degrade.rule-group=" + batch));
    if (service.equals("stock"))
      command.addAll(
          List.of(
              "--cloudmall.stock.seckill.sentinel.rule-group=" + batch,
              "--cloudmall.stock.dependency-degrade.rule-group=" + batch,
              "--cloudmall.stock.cache-repair-interval-ms=86400000",
              "--cloudmall.stock.consistency-interval-ms=86400000"));
    Process child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(artifacts.resolve(service + ".log").toFile())
            .start();
    children.put(service, child);
    if (service.equals("user"))
      Files.writeString(
          artifacts.resolve("user-rm-tc-startup.json"),
          JSON.writerWithDefaultPrettyPrinter()
              .writeValueAsString(
                  Map.of(
                      "pid",
                      child.pid(),
                      "startedAt",
                      child.info().startInstant().orElseThrow().toString(),
                      "appIdSha256",
                      sha256((batch + "-user").getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                      "privateTcAddress",
                      "127.0.0.1:" + userRmTcProxy.port(),
                      "parentTmAddressUnchanged",
                      true)),
          StandardOpenOption.CREATE_NEW);
    await(
        () -> {
          if (!child.isAlive()) throw new AssertionError(service + " JVM 退出，日志: " + artifacts);
          try {
            return HTTP.send(
                        HttpRequest.newBuilder(
                                URI.create(
                                    "http://127.0.0.1:" + ports.get(service) + "/actuator/health"))
                            .timeout(Duration.ofSeconds(3))
                            .GET()
                            .build(),
                        HttpResponse.BodyHandlers.discarding())
                    .statusCode()
                == 200;
          } catch (Exception failure) {
            return false;
          }
        },
        90);
    await(
        () -> {
          try {
            return !naming.selectInstances("cloud-mall-" + service, batch, true).isEmpty();
          } catch (Exception failure) {
            return false;
          }
        },
        30);
    if (service.equals("stock")) confirmEmptyStockMaintenance();
  }

  /** 首次空库审计的既有完成标记必须在 seed 前出现；只读观察失败时保留本 fixture。 */
  private static void confirmEmptyStockMaintenance() throws Exception {
    try {
      await(
          () ->
              stockDb.queryForObject(
                      "SELECT COUNT(*) FROM stock_consistency_audit_state"
                          + " WHERE state_key='NORMAL_STOCK' AND last_completed_at IS NOT NULL"
                          + " AND cursor_sku_id=0 AND last_batch_count=0 AND last_error IS NULL",
                      Integer.class)
                  == 1,
          10);
      Map<String, Object> audit =
          stockDb.queryForMap(
              "SELECT last_completed_at FROM stock_consistency_audit_state"
                  + " WHERE state_key='NORMAL_STOCK'");
      long stockRows = stockDb.queryForObject("SELECT COUNT(*) FROM stock_sku", Long.class);
      long repairRows =
          stockDb.queryForObject("SELECT COUNT(*) FROM stock_cache_repair", Long.class);
      assertEquals(0L, stockRows, "STOCK_STARTUP_MUST_PRECEDE_SEED");
      assertEquals(0L, repairRows, "STOCK_STARTUP_REPAIR_MUST_BE_EMPTY");
      Files.writeString(
          artifacts.resolve("stock-maintenance-startup.json"),
          JSON.writerWithDefaultPrettyPrinter()
              .writeValueAsString(
                  Map.of(
                      "runId",
                      batch,
                      "privateParameters",
                      Map.of(
                          "cloudmall.stock.cache-repair-interval-ms", 86400000,
                          "cloudmall.stock.consistency-interval-ms", 86400000),
                      "observedAt",
                      OffsetDateTime.now(ZoneOffset.ofHours(8)).toString(),
                      "normalStockAuditLastCompletedAt",
                      audit.get("last_completed_at").toString(),
                      "normalStockAuditCursor",
                      0,
                      "normalStockAuditBatchCount",
                      0,
                      "normalStockAuditErrorAbsent",
                      true,
                      "stockSkuRows",
                      stockRows,
                      "cacheRepairRows",
                      repairRows)),
          StandardOpenOption.CREATE_NEW);
    } catch (Exception | AssertionError failure) {
      preserve = true;
      throw failure;
    }
  }

  private static HttpResponse<String> call(String service, String path, Object body)
      throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports.get(service) + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-User-Id", "7")
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private static void rabbit(String method, String path, Object body) throws Exception {
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:15674" + path))
            .version(HttpClient.Version.HTTP_1_1)
            .timeout(Duration.ofSeconds(10))
            .header(
                "Authorization",
                "Basic "
                    + Base64.getEncoder()
                        .encodeToString(
                            "root:root".getBytes(java.nio.charset.StandardCharsets.UTF_8)))
            .header("Content-Type", "application/json");
    request.method(
        method,
        body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    var response = HTTP.send(request.build(), HttpResponse.BodyHandlers.ofString());
    assertTrue(
        response.statusCode() < 300 || ("DELETE".equals(method) && response.statusCode() == 404),
        response.body());
  }

  private static String docker(String... arguments) throws Exception {
    List<String> command = new ArrayList<>();
    command.add("docker");
    command.addAll(List.of(arguments));
    Process process = new ProcessBuilder(command).redirectErrorStream(true).start();
    String output =
        new String(
            process.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
    assertEquals(0, process.waitFor(), output);
    return output;
  }

  private static String javaExecutable() {
    return Path.of(System.getProperty("java.home"), "bin", "java.exe").toString();
  }

  /** 本机出站临时端口段为 1024..15000，服务端口在段外探测，避免启动期间被连接复用。 */
  private static int availablePort() throws Exception {
    for (int attempt = 0; attempt < 128; attempt++) {
      int candidate = ThreadLocalRandom.current().nextInt(22000, 42000);
      try (ServerSocket socket = new ServerSocket(candidate)) {
        return socket.getLocalPort();
      } catch (java.io.IOException unavailable) {
        /* 尝试下一个独立测试端口。 */
      }
    }
    throw new IllegalStateException("没有可用隔离测试端口");
  }

  private static JdbcTemplate jdbc(String url) {
    String bounded =
        url.replaceAll("([?&])connectTimeout=[^&]*", "$1connectTimeout=3000")
            .replaceAll("([?&])socketTimeout=[^&]*", "$1socketTimeout=5000");
    if (!bounded.matches(".*[?&]connectTimeout=.*"))
      bounded += (bounded.contains("?") ? "&" : "?") + "connectTimeout=3000";
    if (!bounded.matches(".*[?&]socketTimeout=.*")) bounded += "&socketTimeout=5000";
    JdbcTemplate database = new JdbcTemplate(new DriverManagerDataSource(bounded, "root", "root"));
    database.setQueryTimeout(5);
    return database;
  }

  private static List<Map<String, Object>> checksums() {
    return source.queryForList(
        "CHECKSUM TABLE"
            + " cloudmall_user.mall_user,cloudmall_user.balance_ledger,cloudmall_user.balance_debit_attempt,cloudmall_user.balance_tcc_debit,cloudmall_pay.pay_record,cloudmall_pay.pay_debit_intent,cloudmall_pay.pay_callback_log,cloudmall_stock.stock_sku,cloudmall_stock.stock_flow,cloudmall_order.order_stock_action");
  }

  private static boolean terminal(String xid) {
    try {
      return Set.of(
              GlobalStatus.Committed,
              GlobalStatus.Rollbacked,
              GlobalStatus.TimeoutRollbacked,
              GlobalStatus.Finished)
          .contains(TransactionManagerHolder.get().getStatus(xid));
    } catch (Exception failure) {
      return false;
    }
  }

  private static void await(BooleanSupplier condition, int seconds) throws Exception {
    long end = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    while (System.nanoTime() < end) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(150);
    }
    fail("等待条件超时: " + artifacts);
  }
}
