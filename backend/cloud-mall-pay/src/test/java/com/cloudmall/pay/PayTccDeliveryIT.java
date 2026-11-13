package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.pay.config.PayDebitModeConfiguration;
import com.cloudmall.pay.controller.PayCreationController;
import com.cloudmall.pay.domain.dto.PayCreateDTO;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.service.PayCreationService;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.impl.PayTccDeliveryScheduler;
import com.cloudmall.pay.service.impl.PayTccDeliveryStore;
import com.cloudmall.pay.service.impl.PayTccRecoveryScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import io.seata.core.context.RootContext;
import io.seata.core.model.GlobalStatus;
import io.seata.tm.TransactionManagerHolder;
import java.math.BigDecimal;
import java.net.*;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.amqp.core.Binding;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.connection.CachingConnectionFactory;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitAdmin;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实四服务交付：独立库、发现组、Rabbit vhost 和独立临时 Redis。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PayTccDeliveryIT {
  /** 所有夹具的独立命名前缀。 */
  private static String batch, month, payUrl, redisContainer;

  /** 原库只读及各隔离服务数据库。 */
  private static JdbcTemplate source, payDb, userDb, orderDb, stockDb;

  /** 各服务隔离库 JDBC 地址。 */
  private static final Map<String, String> urls = new LinkedHashMap<>();

  /** 证据目录，不随清理数据库丢失。 */
  private static Path artifacts;

  /** 主 Pay 真正的 Spring/Seata 进程。 */
  private static ConfigurableApplicationContext application;

  /** 实际全局扣款和交付入口。 */
  private static PayTccCoordinatorService coordinator;

  private static PayTccDeliveryService delivery;
  private static PayTccDeliveryStore store;

  /** 测试只暂停本机 Pay 的自动调度，另有专门自动用例。 */
  private static AtomicBoolean parentRunning;

  /** 隔离发现组的管理客户端。 */
  private static NamingService naming;

  /** 三个独立 User/Order/Stock JVM 和其 HTTP 代理。 */
  private static final Map<String, Process> children = new LinkedHashMap<>();

  private static final Map<String, Integer> ports = new LinkedHashMap<>();
  private static final Map<String, TccResponseProxy> proxies = new LinkedHashMap<>();

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

  /** 用于退款交付重启场景的独立 Pay JVM。 */
  private Process restartedPay;

  /** 当前用例是否持有本机 scheduler 的测试暂停权。 */
  private boolean pauseOwned;

  /** 协议回退只允许自有新 JVM 恢复，父进程不得在收尾时兜底交付。 */
  private boolean protocolRound;

  /** 旧 TCC 子进程、真实 HTTP 端口和回环控制凭据。 */
  private Process originalPay;

  private int protocolPort, protocolControlPort;
  private String protocolControlKey;

  /** 只含白名单进程事实的阶段审计，不保存控制凭据。 */
  private final Map<String, Object> protocolProcesses = new LinkedHashMap<>();

  /** MQ场景只由真实consumer取消订单，普通收尾不得额外cancel制造通过。 */
  private boolean mqRound;

  /** 原TC注册之前记录的本场景只读证据窗口。 */
  private PayTccTcBranchEvidence.Window tcWindowStartedAt;

  /** 首次begin之前保存同源时钟锚点；逐事件包引用其不可覆盖的文件。 */
  private Path tcWindowAnchorArtifact;

  /** 自有Order测试进程的回环控制地址与凭据，不写入证据。 */
  private int mqControlPort;

  private String mqControlKey;

  /** MQ阶段不可变包、被动观察与明确故障驱动操作。 */
  private final List<Path> mqArtifacts = new ArrayList<>();

  private final Map<String, Object> mqObservations = new LinkedHashMap<>();
  private final List<Map<String, Object>> mqDriverActions = new ArrayList<>();

  /** 父进程恢复调度的全 class 暂停权；原 M4 仍只能由替代 JVM 恢复。 */
  private static AtomicBoolean parentRecoveryRunning;

  /** 仅原第一个方法走真实创建，其他十一方法保留既有种子。 */
  private boolean creationRound, creationSettled;

  /** 两次真实创建的原身份，仅在内存匹配与请求，不写控制凭据。 */
  private String creationKeyA, creationKeyB, creationControlKey, reservationOrderB;

  /** B 独立库存 SKU 与自有 helper 的控制端口。 */
  private long reservationSkuB;

  /** 新 Order helper 的唯一自有回环控制端口。 */
  private int creationControlPort;

  /** 只读 Product/Cart metadata double，不替代 User、Order 或 Stock。 */
  private MetadataFixture creationMetadata;

  /** 即使原创建失败也由 class 收尾精确关闭的本机 metadata 资源。 */
  private static MetadataFixture ownedCreationMetadata;

  /** 不可覆盖的两阶段与 TC/窗口索引。 */
  private final List<Map<String, Object>> creationPhaseLinks = new ArrayList<>();

  /** A 原 TC 已证明 COMMIT；单支 commit ACK 仍为 UNOBSERVED。 */
  private Path creationTcArtifact;

  @BeforeAll
  static void setup() throws Exception {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_user 测试 URL");
    source = jdbc(sourceUrl);
    originalChecksums = checksums();
    batch = "cm16c_" + UUID.randomUUID().toString().replace("-", "");
    month = YearMonth.now(ZoneId.of("Asia/Shanghai")).format(DateTimeFormatter.ofPattern("yyyyMM"));
    artifacts = Path.of("target", "cm16c-delivery", batch).toAbsolutePath();
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
    startService("user");
    startService("stock");
    startService("order");
    List<String> parentArguments = new ArrayList<>(List.of(payArguments(false)));
    parentArguments.addAll(
        List.of(
            "--cloudmall.pay.debit-mode=TCC", "--cloudmall.pay.tcc-entry.enabled=true",
            "--cloudmall.pay.tcc-recovery.enabled=true",
                "--cloudmall.pay.tcc-recovery.interval-ms=86400000"));
    application =
        new SpringApplicationBuilder(PayApplication.class)
            .web(WebApplicationType.NONE)
            .run(parentArguments.toArray(String[]::new));
    coordinator = application.getBean(PayTccCoordinatorService.class);
    delivery = application.getBean(PayTccDeliveryService.class);
    store = application.getBean(PayTccDeliveryStore.class);
    parentRunning =
        (AtomicBoolean)
            ReflectionTestUtils.getField(
                application.getBean(PayTccDeliveryScheduler.class), "running");
    parentRecoveryRunning =
        (AtomicBoolean)
            ReflectionTestUtils.getField(
                application.getBean(PayTccRecoveryScheduler.class), "running");
    await(() -> parentRecoveryRunning.compareAndSet(false, true), 10);
    Map<String, Object> effective = new LinkedHashMap<>();
    for (String feature :
        List.of("entry", "coordinator", "receipt", "bridge", "recovery", "delivery")) {
      String property = "cloudmall.pay.tcc-" + feature + ".enabled";
      assertTrue(application.getEnvironment().getProperty(property, Boolean.class, false));
      effective.put(property, true);
    }
    assertTrue(application.getEnvironment().getProperty("seata.enabled", Boolean.class, false));
    assertFalse(
        application
            .getEnvironment()
            .getProperty("seata.enable-auto-data-source-proxy", Boolean.class, true));
    assertEquals("TCC", application.getBean(PayDebitModeConfiguration.class).newPaymentMode());
    effective.put("newPaymentMode", "TCC");
    effective.put(
        "recoveryIntervalMs",
        application
            .getEnvironment()
            .getProperty("cloudmall.pay.tcc-recovery.interval-ms", Long.class));
    effective.put("recoveryInitialDelayEqualsInterval", true);
    effective.put("recoveryPauseOwnedForWholeClass", parentRecoveryRunning.get());
    effective.put("seataEnabled", true);
    effective.put("atAutoProxy", false);
    Files.writeString(
        artifacts.resolve("parent-effective-configuration.json"),
        JSON.writeValueAsString(effective),
        StandardOpenOption.CREATE_NEW);
  }

  @BeforeEach
  void seed(TestInfo testInfo) throws Exception {
    Assumptions.assumeFalse(preserve);
    tcWindowStartedAt = PayTccTcBranchEvidence.markWindowStart();
    tcWindowAnchorArtifact = artifacts.resolve("tc-window-anchor-" + UUID.randomUUID() + ".json");
    Files.writeString(
        tcWindowAnchorArtifact,
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(tcWindowStartedAt),
        StandardOpenOption.CREATE_NEW);
    if (!"OK".equals(tcWindowStartedAt.status())) {
      preserve = true;
      fail("TC同源开始窗口不可用，禁止begin并保留来源证据");
    }
    await(() -> parentRunning.compareAndSet(false, true), 10);
    pauseOwned = true;
    for (TccResponseProxy proxy : proxies.values()) {
      proxy.clearFault();
      proxy.resetCounters();
    }
    stockDb.execute("DROP TRIGGER IF EXISTS block_settlement");
    for (String table :
        List.of(
            "pay_debit_intent",
            "pay_callback_log",
            "pay_tcc_cancel",
            "pay_tcc_delivery",
            "pay_tcc_execution",
            "pay_tcc_dispatch",
            "pay_tcc_receipt",
            "tcc_fence_log",
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
    creationRound =
        testInfo
            .getTestMethod()
            .orElseThrow()
            .getName()
            .equals("scheduledDeliveryWaitsForRealOrderAndStockConfirmation");
    if (creationRound) {
      skuId = System.nanoTime();
      reservationSkuB = skuId + 1;
      for (long creationSku : List.of(skuId, reservationSkuB))
        stockDb.update(
            "INSERT INTO"
                + " stock_sku(sku_id,product_id,total_quantity,available_quantity,reserved_quantity,sold_quantity,version,updated_at)"
                + " VALUES(?,1,10,10,0,0,0,NOW(3))",
            creationSku);
      return;
    }
    request = new PayTccDispatchDTO();
    request.payNo = "delivery-" + UUID.randomUUID();
    request.orderNo = month + UUID.randomUUID().toString();
    request.userId = 7L;
    request.amount = new BigDecimal("10.00");
    skuId = System.nanoTime();
    payDb.update(
        "INSERT INTO"
            + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
            + " VALUES(1,?,?,7,10,'PENDING','TCC',NOW(3),NOW(3))",
        request.payNo,
        request.orderNo);
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
    if (creationRound) {
      if (!creationSettled) preserve = true;
      if (!preserve) {
        try {
          assertCreationSettled();
          stopCreationOrderAndRestore();
          parentRunning.set(false);
        } catch (Throwable failure) {
          preserve = true;
          throw failure;
        }
      }
      return;
    }
    if (request == null || preserve) return;
    if (mqRound) {
      assertEquals("REFUNDED", paymentState());
      assertFunds("100.00", 1, 1);
      parentRunning.set(false);
      return;
    }
    if (protocolRound) {
      assertFalse(originalPay != null && originalPay.isAlive());
      assertFalse(restartedPay != null && restartedPay.isAlive());
      parentRunning.set(false);
      return;
    }
    try {
      if (restartedPay != null && restartedPay.isAlive()) {
        restartedPay.destroyForcibly();
        restartedPay.waitFor(15, TimeUnit.SECONDS);
      }
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
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("execution", payDb.queryForList("SELECT * FROM pay_tcc_execution"));
      snapshot.put("delivery", payDb.queryForList("SELECT * FROM pay_tcc_delivery"));
      snapshot.put("payment", payDb.queryForList("SELECT * FROM pay_record"));
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
      throw failure;
    } finally {
      parentRunning.set(false);
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    for (String xid : xids) if (!terminal(xid)) preserve = true;
    if (application != null) application.close();
    for (var entry : children.entrySet()) {
      entry.getValue().destroyForcibly();
      if (!entry.getValue().waitFor(15, TimeUnit.SECONDS)) {
        preserve = true;
        throw new AssertionError("OWNED_SERVICE_EXIT_UNRESOLVED");
      }
      naming.deregisterInstance(
          "cloud-mall-" + entry.getKey(), batch, "127.0.0.1", proxies.get(entry.getKey()).port());
      proxies.get(entry.getKey()).close();
    }
    if (ownedCreationMetadata != null) ownedCreationMetadata.close();
    if (naming != null) naming.shutDown();
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
  }

  @Test
  @Order(1)
  void scheduledDeliveryWaitsForRealOrderAndStockConfirmation() throws Exception {
    verifyRealOrderCreationAndReservationRecovery();
  }

  @Test
  @Order(2)
  void cancelledOrderCannotRefundUntilRealStockRollbackSucceeds() throws Exception {
    blockStock("ROLLED_BACK");
    cancelOrder();
    commitFunds();
    PayTccDeliveryVO pending = delivery.deliver(request.payNo);
    assertEquals("ORDER_SYNC_UNKNOWN", pending.state);
    assertFunds("90.00", 1, 0);
    assertEquals(0, proxies.get("user").refundPostCount());
    stockDb.execute("DROP TRIGGER block_settlement");
    orderDb.update(
        "UPDATE order_stock_action SET next_retry_at=NOW(3) WHERE order_no=?", request.orderNo);
    awaitStockAction();
    completeDelivery("REFUNDED");
    assertStock(10, 0, 0);
    assertFunds("100.00", 1, 1);
  }

  @Test
  @Order(3)
  void disabledConfirmedUserRefundSurvivesLostResponseAndPayRestart() throws Exception {
    commitFunds();
    cancelOrder();
    awaitStockAction();
    userDb.update("UPDATE mall_user SET status=0 WHERE id=7");
    proxies.get("user").dropNextPostBodyForPath("/refunds");
    assertEquals("REFUND_PENDING", delivery.deliver(request.payNo).state);
    assertFunds("100.00", 1, 1);
    assertEquals("REFUND_PENDING", paymentState());
    forceDue();
    startReplacementPay();
    await(() -> "REFUNDED".equals(paymentState()), 40);
    assertFunds("100.00", 1, 1);
    assertTrue(proxies.get("user").refundPostCount() >= 2);
    assertEquals(0, proxies.get("order").paidPostCount());
  }

  @Test
  @Order(4)
  void paidResponseLossStillUsesReadOnlyStockEvidence() throws Exception {
    commitFunds();
    proxies.get("order").dropNextPostBodyForPath("/paid");
    delivery.deliver(request.payNo);
    awaitStockAction();
    completeDelivery("SUCCESS");
    assertEquals(1, proxies.get("order").paidPostCount());
    assertStock(9, 0, 1);
    assertFunds("90.00", 1, 0);
  }

  @Test
  @Order(5)
  void rolledBackExecutionNeverCreatesDeliveryOrRefund() throws Exception {
    request.amount = new BigDecimal("201.00");
    payDb.update("UPDATE pay_record SET amount=201");
    assertEquals("ROLLEDBACK", coordinator.execute(request).state);
    assertNull(delivery.deliver(request.payNo));
    assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_delivery", Integer.class));
    assertEquals("PENDING_PAYMENT", orderState());
    assertEquals(0, proxies.get("order").paidPostCount());
    assertEquals(0, proxies.get("user").refundPostCount());
    assertFunds("100.00", 0, 0);
  }

  @Test
  @Order(6)
  void concurrentDeliveryAndStaleLeaseCannotOverwriteTerminal() throws Exception {
    commitFunds();
    cancelOrder();
    awaitStockAction();
    store.ensure(request.payNo);
    var first = store.claim(request.payNo, "old-token");
    assertNotNull(first);
    payDb.update("UPDATE pay_tcc_delivery SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 DAY)");
    // 过期但尚未被接手时同样失去本地写权限，不能写退款意图或延后恢复。
    assertFalse(store.stage(request.payNo, "old-token", "REFUND_PENDING"));
    assertEquals("PENDING", paymentState());
    var expired = payDb.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no=?", request.payNo);
    store.finish(request.payNo, "old-token", 1, "expired worker");
    assertEquals(
        expired, payDb.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no=?", request.payNo));
    var second = store.claim(request.payNo, "new-token");
    assertNotNull(second);
    assertFalse(store.stage(request.payNo, "old-token", "REFUND_PENDING"));
    assertEquals("PENDING", paymentState());
    store.finish(request.payNo, "new-token", 1, null);
    forceDue();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      Future<?> left = pool.submit(() -> delivery.deliver(request.payNo));
      Future<?> right = pool.submit(() -> delivery.deliver(request.payNo));
      left.get(20, TimeUnit.SECONDS);
      right.get(20, TimeUnit.SECONDS);
    } finally {
      pool.shutdownNow();
    }
    completeDelivery("REFUNDED");
    assertFunds("100.00", 1, 1);
  }

  @Test
  @Order(7)
  void deliveryWriteFailureRollsBackPaymentStatusInSameTransaction() throws Exception {
    commitFunds();
    payDb.execute(
        "CREATE TRIGGER fail_delivery_success BEFORE UPDATE ON pay_tcc_delivery FOR EACH ROW BEGIN"
            + " IF NEW.state='SUCCESS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test delivery"
            + " write failure'; END IF; END");
    try {
      delivery.deliver(request.payNo);
      awaitStockAction();
      forceDue();
      delivery.deliver(request.payNo);
      assertEquals("ORDER_SYNC_UNKNOWN", paymentState());
      assertEquals("ORDER_SYNC_UNKNOWN", store.read(request.payNo).state);
      assertFunds("90.00", 1, 0);
      assertStock(9, 0, 1);
    } finally {
      payDb.execute("DROP TRIGGER fail_delivery_success");
    }
    completeDelivery("SUCCESS");
  }

  /** 种任务查询不可用时，已持久化的交付任务仍通过真实订单和库存链路完成。 */
  @Test
  @Order(8)
  void missingScanFailureDoesNotStarveExistingRealDelivery() throws Exception {
    commitFunds();
    store.ensure(request.payNo);
    PayTccDeliveryScheduler scheduler = application.getBean(PayTccDeliveryScheduler.class);
    PayTccDeliveryMapper original = application.getBean(PayTccDeliveryMapper.class);
    PayTccDeliveryMapper scanFailure =
        Mockito.mock(PayTccDeliveryMapper.class, AdditionalAnswers.delegatesTo(original));
    Mockito.doThrow(new IllegalStateException("fixture missing scan unavailable"))
        .when(scanFailure)
        .findMissing(20);
    ReflectionTestUtils.setField(scheduler, "mapper", scanFailure);
    ReflectionTestUtils.setField(scheduler, "seedFirst", true);
    try {
      parentRunning.set(false);
      pauseOwned = false;
      await(() -> "SUCCESS".equals(paymentState()), 40);
      assertEquals("PAID", orderState());
      assertStock(9, 0, 1);
      assertFunds("90.00", 1, 0);
      Mockito.verify(scanFailure, Mockito.atLeastOnce()).findMissing(20);
    } finally {
      try {
        await(() -> parentRunning.compareAndSet(false, true), 10);
        pauseOwned = true;
      } finally {
        ReflectionTestUtils.setField(scheduler, "mapper", original);
      }
    }
  }

  private void commitFunds() {
    assertEquals("COMMITTED", coordinator.execute(request).state);
  }

  /** 同一既有四服务 fixture 内，只以真实 HTTP create 和生产事务代理建立 A/B。 */
  private void verifyRealOrderCreationAndReservationRecovery() throws Exception {
    creationKeyA = "normal-create-a-" + UUID.randomUUID();
    creationKeyB = "normal-create-b-" + UUID.randomUUID();
    ExecutorService caller = Executors.newSingleThreadExecutor();
    Future<HttpResponse<String>> originalCreate = null;
    Throwable primary = null;
    try {
      // 1. 真实地址与窄 metadata double 只供本次隔离发现组使用。
      creationMetadata = new MetadataFixture(skuId, reservationSkuB);
      ownedCreationMetadata = creationMetadata;
      creationMetadata.start();
      startCreationOrder();
      var addressResponse =
          call(
              "user",
              "/api/users/me/addresses",
              Map.of(
                  "receiver",
                  "Fixture Receiver",
                  "phone",
                  "13800000000",
                  "detailAddress",
                  "Fixture Shanghai Address",
                  "isDefault",
                  true));
      assertEquals(200, addressResponse.statusCode());
      var address = JSON.readTree(addressResponse.body());
      assertEquals("0", address.path("code").asText());
      long addressId = address.at("/data/id").asLong();
      assertTrue(addressId > 0);
      String addressSha = sha256(JSON.writeValueAsBytes(address.path("data")));
      originalCreate = caller.submit(() -> createOrder(creationKeyA, skuId, addressId, 60));
      await(() -> creationState().path("beforeCommitReady").asBoolean(), 30);
      Map<String, Object> intentA = creationIntent(creationKeyA);
      String orderA = (String) intentA.get("order_no");
      assertEquals("RESERVED", intentA.get("status"));
      assertEquals(hashText(orderA), requiredCreationState().path("aOrderNoSha256").asText());
      assertCreationRows(orderA, 0);
      assertReservation(orderA, skuId, "RESERVED", 9, 1, 0, 1, 0, 0);
      assertOriginalItems(intentA, skuId);
      assertNoPaymentForOrder(orderA);
      var beforeCommit = requiredCreationState();
      assertTrue(beforeCommit.path("transactionActiveAtRegistration").asBoolean());
      assertTrue(beforeCommit.path("synchronizationActiveAtRegistration").asBoolean());
      assertFalse(beforeCommit.path("beforeCommitReadOnly").asBoolean());
      assertTrue(beforeCommit.path("completionStatus").isNull());
      Map<String, Object> atomicity =
          rawCreationEvidence("ORDER_CREATION_ATOMICITY_ONLY", orderA, intentA, skuId);
      atomicity.put("outsideTransactionRowsBeforeCommit", creationRowCounts(orderA));
      atomicity.put("outsideIntentBeforeCommit", "RESERVED");
      atomicity.put("stockBeforeCommit", reservationFacts(orderA, skuId));
      atomicity.put("beforeCommitObserver", beforeCommit);
      atomicity.put("addressResponseSha256", addressSha);
      assertEquals(200, creationControl("POST", "/release").statusCode());
      HttpResponse<String> created = originalCreate.get(60, TimeUnit.SECONDS);
      assertEquals(200, created.statusCode());
      var orderResponse = JSON.readTree(created.body());
      assertEquals("0", orderResponse.path("code").asText());
      assertEquals(orderA, orderResponse.at("/data/orderNo").asText());
      assertCreationRows(orderA, 1);
      assertEquals("COMMITTED", creationIntent(creationKeyA).get("status"));
      var completed = requiredCreationState();
      assertEquals(0, completed.path("completionStatus").asInt(-1));
      assertEquals(1, completed.path("releaseCalls").asInt());
      assertCommittedOrder(orderA, addressId, skuId);
      atomicity.put("outsideTransactionRowsAfterCommit", creationRowCounts(orderA));
      atomicity.put("outsideIntentAfterCommit", "COMMITTED");
      atomicity.put("afterCompletionObserver", completed);
      atomicity.put("orderFacts", safeCreatedOrder(orderA));
      writeCreationArtifact("order-create-atomicity.json", atomicity);

      // 2. 必须由生产 PayCreationService 的 Spring 事务代理和原 Order Feign 创建支付。
      PayCreationService payments = application.getBean(PayCreationService.class);
      assertTrue(org.springframework.aop.support.AopUtils.isAopProxy(payments));
      assertCleanCaller();
      PayCreateDTO createPayment = new PayCreateDTO();
      createPayment.orderNo = orderA;
      createPayment.payAmount = new BigDecimal("10.00");
      PayVO payment;
      try {
        AuthContext.set(7L, "USER");
        var response =
            new PayCreationController(payments)
                .create("normal-payment-" + UUID.randomUUID(), createPayment);
        assertEquals("0", response.code);
        payment = JSON.convertValue(response.data, PayVO.class);
      } finally {
        AuthContext.clear();
      }
      assertCleanCaller();
      assertEquals(orderA, payment.orderNo);
      assertEquals(7L, payment.userId);
      assertEquals(0, new BigDecimal("10.00").compareTo(payment.amount));
      assertEquals("PENDING", payment.status);
      request = new PayTccDispatchDTO();
      request.payNo = payment.payNo;
      request.orderNo = payment.orderNo;
      request.userId = payment.userId;
      request.amount = payment.amount;
      assertEquals(
          1,
          payDb.queryForObject(
              "SELECT COUNT(*) FROM pay_record WHERE order_no=? AND pay_no=? AND debit_mode='TCC'"
                  + " AND status='PENDING'",
              Integer.class,
              orderA,
              request.payNo));
      commitFunds();
      assertCleanCaller();
      await(
          () ->
              "90.00"
                      .equals(
                          userDb.queryForObject(
                              "SELECT balance FROM mall_user WHERE id=7", String.class))
                  && "0.00"
                      .equals(
                          userDb.queryForObject(
                              "SELECT frozen_balance FROM mall_user WHERE id=7", String.class)),
          15);
      assertFunds("90.00", 1, 0);
      assertEquals("PENDING_PAYMENT", orderState());
      assertStock(9, 1, 0);
      creationTcArtifact = captureCreationTc();
      await(this::originalTimeoutPublicationFinished, 20);
      assertOriginalTimeoutPublication();
      captureCreationPhase("NORMAL_CREATE_COMMITTED_BEFORE_DELIVERY");
      parentRunning.set(false);
      pauseOwned = false;
      await(() -> "SUCCESS".equals(paymentState()), 40);
      await(() -> parentRunning.compareAndSet(false, true), 10);
      pauseOwned = true;
      assertEquals("PAID", orderState());
      assertStock(9, 0, 1);
      assertFunds("90.00", 1, 0);
      assertEquals(1, proxies.get("user").postCount());
      assertEquals(0, proxies.get("user").refundPostCount());
      assertReservation(orderA, skuId, "CONFIRMED", 9, 0, 1, 1, 1, 0);
      assertEquals(
          1,
          orderDb.queryForObject(
              "SELECT COUNT(*) FROM order_stock_action WHERE order_no=? AND action_type='CONFIRM'"
                  + " AND status='SUCCEEDED'",
              Integer.class,
              orderA));
      captureCreationPhase("NORMAL_CREATE_DELIVERED");

      // 3. B 首次 reserve 已真实提交但原响应体丢失，只等原年龄门和自动 Recovery。
      Map<String, Object> userBeforeB = originalUserFacts();
      Map<String, Object> payBeforeB = originalPayFacts();
      proxies.get("stock").dropNextPostBodyForPath("/api/stock/reservations");
      HttpResponse<String> unknown = createOrder(creationKeyB, reservationSkuB, addressId, 10);
      assertEquals(202, unknown.statusCode());
      assertEquals(
          "ORDER_RESERVATION_UNKNOWN", JSON.readTree(unknown.body()).path("code").asText());
      Object fault = ReflectionTestUtils.getField(proxies.get("stock"), "dropPostSuffix");
      assertNull(
          ((java.util.concurrent.atomic.AtomicReference<?>) fault).get(),
          "B_RESERVE_FAULT_NOT_CONSUMED");
      Map<String, Object> intentB = creationIntent(creationKeyB);
      reservationOrderB = (String) intentB.get("order_no");
      assertNotEquals(orderA, reservationOrderB);
      assertEquals("RESERVE_UNKNOWN", intentB.get("status"));
      assertOriginalItems(intentB, reservationSkuB);
      assertCreationRows(reservationOrderB, 0);
      assertNoPaymentForOrder(reservationOrderB);
      assertReservation(reservationOrderB, reservationSkuB, "RESERVED", 9, 1, 0, 1, 0, 0);
      LocalDateTime createdB = localTime(intentB.get("created_at"));
      LocalDateTime retryB = localTime(intentB.get("next_retry_at"));
      LocalDateTime updatedB = localTime(intentB.get("updated_at"));
      long originalBackoffMillis = Duration.between(updatedB, retryB).toMillis();
      assertTrue(originalBackoffMillis >= 29000 && originalBackoffMillis <= 30000);
      assertTrue(
          Duration.between(createdB, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))).toSeconds()
              < 120);
      assertEquals(userBeforeB, originalUserFacts());
      assertEquals(payBeforeB, originalPayFacts());
      assertEquals(1, requiredCreationState().path("expectedBReserveFailures").asInt());
      Map<String, Object> bInitial =
          rawCreationEvidence(
              "ORDER_RESERVATION_ONLY_NO_PAY", reservationOrderB, intentB, reservationSkuB);
      bInitial.put("rows", creationRowCounts(reservationOrderB));
      bInitial.put("stock", reservationFacts(reservationOrderB, reservationSkuB));
      bInitial.put("originalBackoffMillis", originalBackoffMillis);
      bInitial.put("userFactsSha256", hashObject(userBeforeB));
      bInitial.put("payFactsSha256", hashObject(payBeforeB));
      bInitial.put("observer", requiredCreationState());
      bInitial.put("actualReserveResponseBodyDropped", true);
      writeCreationArtifact("order-reservation-unknown.json", bInitial);
      long waitStarted = System.nanoTime();
      await(() -> "RELEASED".equals(creationIntent(creationKeyB).get("status")), 180);
      await(() -> creationState().path("activeRecovery").asInt(-1) == 0, 5);
      Map<String, Object> released = creationIntent(creationKeyB);
      assertEquals(intentB.get("id"), released.get("id"));
      assertEquals(intentB.get("order_no"), released.get("order_no"));
      assertEquals(intentB.get("user_id"), released.get("user_id"));
      assertEquals(intentB.get("idempotency_key"), released.get("idempotency_key"));
      assertEquals(intentB.get("items_snapshot"), released.get("items_snapshot"));
      assertEquals(intentB.get("created_at"), released.get("created_at"));
      assertTrue(
          Duration.between(createdB, localTime(released.get("updated_at"))).toMillis() > 120000);
      assertNull(released.get("next_retry_at"));
      assertNull(released.get("last_error"));
      assertCreationRows(reservationOrderB, 0);
      assertNoPaymentForOrder(reservationOrderB);
      assertReservation(reservationOrderB, reservationSkuB, "ROLLED_BACK", 10, 0, 0, 1, 0, 1);
      assertEquals(userBeforeB, originalUserFacts());
      assertEquals(payBeforeB, originalPayFacts());
      assertRecoveryTrace(requiredCreationState());
      Map<String, Object> bFinal =
          rawCreationEvidence(
              "ORDER_RESERVATION_ONLY_NO_PAY", reservationOrderB, released, reservationSkuB);
      bFinal.put("rows", creationRowCounts(reservationOrderB));
      bFinal.put("stock", reservationFacts(reservationOrderB, reservationSkuB));
      bFinal.put("realWaitMillis", TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - waitStarted));
      bFinal.put("defaultRecoveryIntervalMillis", 30000);
      bFinal.put("minimumAgeMillis", 120000);
      bFinal.put(
          "ageAtReleaseMillis",
          Duration.between(createdB, localTime(released.get("updated_at"))).toMillis());
      bFinal.put("userFactsUnchanged", true);
      bFinal.put("payFactsUnchanged", true);
      bFinal.put("observer", requiredCreationState());
      writeCreationArtifact("order-reservation-released.json", bFinal);
      assertCreationSettled();
      creationSettled = true;
      writeCreationArtifact(
          "normal-create-phase-links.json",
          Map.of(
              "runId",
              batch,
              "scenarioId",
              "NORMAL_ORDER_CREATE_WITH_REAL_RESERVE",
              "phases",
              creationPhaseLinks,
              "acceptedPaymentCandidates",
              1,
              "rawOrderReservationWithoutPayment",
              1,
              "automaticActionsAllowed",
              false));
    } catch (Exception | AssertionError failure) {
      primary = failure;
      preserve = true;
      throw failure;
    } finally {
      Throwable cleanupProblem = null;
      try {
        if (originalCreate != null) {
          if (!originalCreate.isDone()) {
            var state = creationState();
            if (state.path("beforeCommitReady").asBoolean()
                && state.path("releaseCalls").asInt() == 0)
              assertEquals(200, creationControl("POST", "/release").statusCode());
          }
          originalCreate.get(60, TimeUnit.SECONDS);
        }
      } catch (Exception | AssertionError cleanupFailure) {
        cleanupProblem = cleanupFailure;
      }
      caller.shutdown();
      try {
        if (!caller.awaitTermination(15, TimeUnit.SECONDS)) {
          throw new AssertionError("ORIGINAL_CREATE_WORKER_UNRESOLVED");
        }
      } catch (Exception | AssertionError cleanupFailure) {
        if (cleanupProblem == null) cleanupProblem = cleanupFailure;
        else cleanupProblem.addSuppressed(cleanupFailure);
      } finally {
        caller.shutdownNow();
        AuthContext.clear();
      }
      if (cleanupProblem != null) {
        preserve = true;
        if (primary != null) primary.addSuppressed(cleanupProblem);
        else if (cleanupProblem instanceof Exception failure) throw failure;
        else throw (AssertionError) cleanupProblem;
      }
    }
  }

  /** A/B 均通过原 HTTP 事务入口；只有 A 的有界等待预算扩大到屏障所需。 */
  private HttpResponse<String> createOrder(
      String key, long creationSku, long addressId, int timeoutSeconds) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + ports.get("order") + "/api/orders"))
            .timeout(Duration.ofSeconds(timeoutSeconds))
            .header("X-User-Id", "7")
            .header("Idempotency-Key", key)
            .header("Content-Type", "application/json")
            .POST(
                HttpRequest.BodyPublishers.ofString(
                    JSON.writeValueAsString(
                        Map.of(
                            "items",
                            List.of(Map.of("skuId", creationSku, "quantity", 1)),
                            "addressId",
                            addressId))))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** 只读独立 DriverManager 连接，不继承 helper 的事务或快照。 */
  private Map<String, Object> creationIntent(String key) {
    return orderDb.queryForMap(
        "SELECT * FROM order_reservation_intent WHERE user_id=7 AND idempotency_key=?", key);
  }

  private Map<String, Object> creationRowCounts(String orderNo) {
    Map<String, Object> rows = new LinkedHashMap<>();
    for (String table :
        List.of(
            "mall_order_" + month, "mall_order_item_" + month, "order_idempotency", "order_outbox"))
      rows.put(
          table,
          orderDb.queryForObject(
              "SELECT COUNT(*) FROM " + table + " WHERE order_no=?", Integer.class, orderNo));
    return rows;
  }

  private void assertCreationRows(String orderNo, int expected) {
    creationRowCounts(orderNo).values().forEach(count -> assertEquals(expected, count));
  }

  private void assertOriginalItems(Map<String, Object> intent, long expectedSku) throws Exception {
    assertEquals(7L, ((Number) intent.get("user_id")).longValue());
    var items = JSON.readTree((String) intent.get("items_snapshot"));
    assertEquals(1, items.size());
    assertEquals(expectedSku, items.get(0).path("skuId").asLong());
    assertEquals(1, items.get(0).path("quantity").asInt());
    var reservation =
        stockDb.queryForMap(
            "SELECT items_snapshot FROM stock_reservation_request WHERE order_no=?",
            intent.get("order_no"));
    assertEquals(items, JSON.readTree((String) reservation.get("items_snapshot")));
  }

  /** 空支付范围按原订单过滤，A 既有 DEBIT 不得算为 B 的资金动作。 */
  private void assertNoPaymentForOrder(String orderNo) {
    for (String table :
        List.of(
            "pay_record",
            "pay_debit_intent",
            "pay_tcc_execution",
            "pay_tcc_dispatch",
            "pay_tcc_receipt",
            "pay_tcc_delivery",
            "pay_tcc_cancel"))
      assertEquals(
          0,
          payDb.queryForObject(
              "SELECT COUNT(*) FROM " + table + " WHERE order_no=?", Integer.class, orderNo));
    for (String table :
        List.of("balance_ledger", "balance_debit_attempt", "balance_tcc_debit", "user_tcc_receive"))
      assertEquals(
          0,
          userDb.queryForObject(
              "SELECT COUNT(*) FROM " + table + " WHERE order_no=?", Integer.class, orderNo));
  }

  private void assertCommittedOrder(String orderNo, long addressId, long expectedSku)
      throws Exception {
    Map<String, Object> order =
        orderDb.queryForMap("SELECT * FROM mall_order_" + month + " WHERE order_no=?", orderNo);
    Map<String, Object> item =
        orderDb.queryForMap(
            "SELECT * FROM mall_order_item_" + month + " WHERE order_no=?", orderNo);
    Map<String, Object> idempotency =
        orderDb.queryForMap("SELECT * FROM order_idempotency WHERE order_no=?", orderNo);
    Map<String, Object> outbox =
        orderDb.queryForMap("SELECT * FROM order_outbox WHERE order_no=?", orderNo);
    assertEquals(7L, ((Number) order.get("user_id")).longValue());
    assertEquals(7L, ((Number) idempotency.get("user_id")).longValue());
    assertEquals(creationKeyA, idempotency.get("idempotency_key"));
    assertEquals(order.get("id"), item.get("order_id"));
    assertEquals(expectedSku, ((Number) item.get("sku_id")).longValue());
    assertEquals(1, ((Number) item.get("quantity")).intValue());
    assertEquals(1L, ((Number) item.get("product_id")).longValue());
    assertEquals("Creation fixture " + expectedSku, item.get("product_name_snapshot"));
    assertEquals(
        JSON.valueToTree(Map.of("fixture", "normal-create", "skuId", Long.toString(expectedSku))),
        JSON.readTree((String) item.get("sku_snapshot")));
    for (Object value :
        List.of(
            order.get("total_amount"),
            order.get("pay_amount"),
            item.get("unit_price"),
            item.get("line_amount")))
      assertEquals(0, new BigDecimal("10.00").compareTo((BigDecimal) value));
    assertEquals(
        addressId, JSON.readTree((String) order.get("address_snapshot")).path("id").asLong());
    LocalDateTime created = localTime(order.get("created_at"));
    LocalDateTime expires = localTime(order.get("expire_at"));
    assertEquals(0, created.getNano() % 1_000_000);
    assertEquals(created.plusMinutes(30), expires);
    assertEquals(created, localTime(item.get("created_at")));
    assertEquals(created, localTime(idempotency.get("created_at")));
    assertEquals(created, localTime(outbox.get("created_at")));
    assertEquals("order-timeout:" + orderNo, outbox.get("event_id"));
    assertEquals("ORDER_TIMEOUT", outbox.get("event_type"));
    var payload = JSON.readTree((String) outbox.get("payload"));
    assertEquals("order-timeout:" + orderNo, payload.path("eventId").asText());
    assertEquals(orderNo, payload.path("orderNo").asText());
    assertEquals("ORDER_TIMEOUT", payload.path("eventType").asText());
    assertEquals(
        created, OffsetDateTime.parse(payload.path("occurredAt").asText()).toLocalDateTime());
    assertEquals(
        expires, OffsetDateTime.parse(payload.path("expireAt").asText()).toLocalDateTime());
    assertEquals(
        ZoneOffset.ofHours(8),
        OffsetDateTime.parse(payload.path("occurredAt").asText()).getOffset());
    assertEquals(
        ZoneOffset.ofHours(8), OffsetDateTime.parse(payload.path("expireAt").asText()).getOffset());
    assertTrue(payload.has("traceId"));
  }

  private Map<String, Object> safeCreatedOrder(String orderNo) {
    Map<String, Object> row =
        new LinkedHashMap<>(
            orderDb.queryForMap(
                "SELECT * FROM mall_order_" + month + " WHERE order_no=?", orderNo));
    row.put("address_snapshot_sha256", hashText((String) row.remove("address_snapshot")));
    row.remove("order_no");
    row.put("orderNoSha256", hashText(orderNo));
    return row;
  }

  private Map<String, Object> reservationFacts(String orderNo, long expectedSku) {
    Map<String, Object> facts = new LinkedHashMap<>();
    facts.put(
        "stock",
        stockDb.queryForMap(
            "SELECT total_quantity,available_quantity,reserved_quantity,sold_quantity FROM"
                + " stock_sku WHERE sku_id=?",
            expectedSku));
    facts.put(
        "reservationStatus",
        stockDb.queryForObject(
            "SELECT status FROM stock_reservation_request WHERE order_no=?",
            String.class,
            orderNo));
    facts.put(
        "itemsSnapshotSha256",
        hashText(
            stockDb.queryForObject(
                "SELECT items_snapshot FROM stock_reservation_request WHERE order_no=?",
                String.class,
                orderNo)));
    facts.put(
        "flows",
        stockDb
            .queryForList(
                "SELECT flow_type,quantity,idempotency_key FROM stock_flow WHERE order_no=? AND"
                    + " sku_id=? ORDER BY flow_type,id",
                orderNo,
                expectedSku)
            .stream()
            .map(
                row -> {
                  Map<String, Object> safe = new LinkedHashMap<>(row);
                  safe.put(
                      "idempotencyKeySha256", hashText((String) safe.remove("idempotency_key")));
                  return safe;
                })
            .toList());
    return facts;
  }

  private void assertReservation(
      String orderNo,
      long expectedSku,
      String status,
      int available,
      int reserved,
      int sold,
      int reserveFlows,
      int confirmFlows,
      int rollbackFlows) {
    assertEquals(
        status,
        stockDb.queryForObject(
            "SELECT status FROM stock_reservation_request WHERE order_no=?",
            String.class,
            orderNo));
    var stock =
        stockDb.queryForMap(
            "SELECT total_quantity,available_quantity,reserved_quantity,sold_quantity FROM"
                + " stock_sku WHERE sku_id=?",
            expectedSku);
    assertEquals(10, ((Number) stock.get("total_quantity")).intValue());
    assertEquals(available, ((Number) stock.get("available_quantity")).intValue());
    assertEquals(reserved, ((Number) stock.get("reserved_quantity")).intValue());
    assertEquals(sold, ((Number) stock.get("sold_quantity")).intValue());
    for (var expectation :
        Map.of("RESERVE", reserveFlows, "CONFIRM", confirmFlows, "ROLLBACK", rollbackFlows)
            .entrySet())
      assertEquals(
          expectation.getValue(),
          stockDb.queryForObject(
              "SELECT COUNT(*) FROM stock_flow WHERE order_no=? AND sku_id=? AND flow_type=? AND"
                  + " quantity=1",
              Integer.class,
              orderNo,
              expectedSku,
              expectation.getKey()));
    assertEquals(
        reserveFlows + confirmFlows + rollbackFlows,
        stockDb.queryForObject(
            "SELECT COUNT(*) FROM stock_flow WHERE order_no=? AND sku_id=?",
            Integer.class,
            orderNo,
            expectedSku));
  }

  private Map<String, Object> rawCreationEvidence(
      String scope, String orderNo, Map<String, Object> intent, long expectedSku) {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put("runId", batch);
    result.put("scope", scope);
    result.put("observedAt", OffsetDateTime.now(ZoneOffset.ofHours(8)).toString());
    result.put("orderNoSha256", hashText(orderNo));
    result.put("userId", 7);
    result.put("skuId", expectedSku);
    result.put("intentId", intent.get("id"));
    result.put("idempotencyKeySha256", hashText((String) intent.get("idempotency_key")));
    result.put("itemsSnapshotSha256", hashText((String) intent.get("items_snapshot")));
    result.put("intentStatus", intent.get("status"));
    result.put("intentCreatedAt", intent.get("created_at"));
    result.put("intentUpdatedAt", intent.get("updated_at"));
    result.put("nextRetryAt", intent.get("next_retry_at"));
    for (String unknown :
        List.of(
            "payNo",
            "payAmount",
            "debitMode",
            "executionId",
            "xidSha256",
            "payBranchSha256",
            "userBranchSha256",
            "payOperationKey")) result.put(unknown, null);
    result.put("acceptedM1Phase", false);
    result.put("ownerRole", "ORDER");
    result.put("nextEvidence", "RETAIN_ORIGINAL_ORDER_RESERVATION_FACTS_WITHOUT_PAY_IDENTITY");
    result.put("retention", "NO_AUTOMATIC_EXPIRY");
    result.put("automaticActionsAllowed", false);
    return result;
  }

  private Map<String, Object> originalUserFacts() {
    Map<String, Object> result = new LinkedHashMap<>();
    result.put(
        "funds",
        userDb.queryForList("SELECT id,balance,frozen_balance,status FROM mall_user WHERE id=7"));
    for (String table :
        List.of(
            "balance_ledger",
            "balance_debit_attempt",
            "balance_tcc_debit",
            "user_tcc_receive",
            "tcc_fence_log")) result.put(table, userDb.queryForList("SELECT * FROM " + table));
    return result;
  }

  private Map<String, Object> originalPayFacts() {
    Map<String, Object> result = new LinkedHashMap<>();
    for (String table :
        List.of(
            "pay_record",
            "pay_debit_intent",
            "pay_tcc_execution",
            "pay_tcc_dispatch",
            "pay_tcc_receipt",
            "pay_tcc_delivery",
            "pay_tcc_cancel")) result.put(table, payDb.queryForList("SELECT * FROM " + table));
    return result;
  }

  private static LocalDateTime localTime(Object value) {
    if (value instanceof java.sql.Timestamp timestamp) return timestamp.toLocalDateTime();
    if (value instanceof LocalDateTime time) return time;
    throw new IllegalStateException("ORIGINAL_DATABASE_TIME_TYPE_REQUIRED");
  }

  private static String hashText(String value) {
    return sha256(value.getBytes(StandardCharsets.UTF_8));
  }

  private static String hashObject(Object value) throws Exception {
    return sha256(JSON.writeValueAsBytes(value));
  }

  private static void assertCleanCaller() {
    assertNull(RootContext.getXID());
    assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
    assertFalse(TransactionSynchronizationManager.isSynchronizationActive());
    assertNull(AuthContext.userId());
    assertNull(AuthContext.role());
  }

  private Path captureCreationTc() throws Exception {
    var execution =
        payDb.queryForMap("SELECT id,xid FROM pay_tcc_execution WHERE pay_no=?", request.payNo);
    var receipt =
        payDb.queryForMap(
            "SELECT xid,branch_id FROM pay_tcc_receipt WHERE pay_no=?", request.payNo);
    var receive =
        userDb.queryForMap(
            "SELECT xid,branch_id FROM user_tcc_receive WHERE pay_no=?", request.payNo);
    String xid = (String) execution.get("xid");
    assertNotNull(xid);
    assertEquals(xid, receipt.get("xid"));
    assertEquals(xid, receive.get("xid"));
    long payBranch = ((Number) receipt.get("branch_id")).longValue();
    long userBranch = ((Number) receive.get("branch_id")).longValue();
    assertTrue(payBranch > 0 && userBranch > 0 && payBranch != userBranch);
    xids.add(xid);
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
      var row =
          database.queryForMap(
              "SELECT pay_no,order_no,user_id,amount,operation_key,xid"
                  + (hasLedgerKey ? ",ledger_key" : "")
                  + " FROM "
                  + table
                  + " WHERE pay_no=?",
              request.payNo);
      assertTrue(request.payNo.equals(row.get("pay_no")), "ORIGINAL_PAYMENT_CHANGED");
      assertTrue(request.orderNo.equals(row.get("order_no")), "ORIGINAL_ORDER_CHANGED");
      assertEquals(7L, ((Number) row.get("user_id")).longValue());
      assertEquals(0, request.amount.compareTo((BigDecimal) row.get("amount")));
      assertTrue(xid.equals(row.get("xid")), "ORIGINAL_XID_CHANGED");
      assertTrue(
          ("tcc-debit:" + request.payNo).equals(row.get("operation_key")),
          "ORIGINAL_OPERATION_CHANGED");
      if (hasLedgerKey)
        assertTrue(request.payNo.equals(row.get("ledger_key")), "ORIGINAL_LEDGER_KEY_CHANGED");
    }
    for (var participant : Map.of("pay", payBranch, "user", userBranch).entrySet())
      assertEquals(
          1,
          (participant.getKey().equals("pay") ? payDb : userDb)
              .queryForObject(
                  "SELECT COUNT(*) FROM tcc_fence_log WHERE xid=? AND branch_id=? AND status=2",
                  Integer.class,
                  xid,
                  participant.getValue()));
    assertEquals(
        1,
        userDb.queryForObject(
            "SELECT COUNT(*) FROM balance_ledger WHERE pay_no=? AND order_no=? AND user_id=7 AND"
                + " operation_type='DEBIT' AND amount=10.00 AND state='APPLIED'",
            Integer.class,
            request.payNo,
            request.orderNo));
    assertEquals(
        "COMMIT",
        payDb.queryForObject(
            "SELECT decision FROM pay_tcc_execution WHERE pay_no=?", String.class, request.payNo));
    assertEquals(
        "TCC",
        userDb.queryForObject(
            "SELECT mode FROM balance_debit_attempt WHERE pay_no=?", String.class, request.payNo));
    Path evidence =
        PayTccTcBranchEvidence.capture(
            artifacts,
            "NORMAL_CREATE_ORIGINAL_COMMIT",
            xid,
            payBranch,
            userBranch,
            tcWindowStartedAt,
            tcWindowAnchorArtifact);
    assertTcCommitEvidence(evidence);
    return evidence;
  }

  /** 只读全 53 表采集；两个阶段始终绑定同一个原 native COMMIT 包。 */
  private void captureCreationPhase(String phase) throws Exception {
    assertTrue(parentRunning.get());
    assertTrue(parentRecoveryRunning.get());
    assertTrue(originalTimeoutPublicationFinished());
    Map<String, Object> before = ownedChecksums();
    assertEquals(53, before.values().stream().mapToInt(value -> ((List<?>) value).size()).sum());
    Path artifact =
        PayTccStageEvidence.capture(
            artifacts,
            batch,
            phase,
            request.payNo,
            request.orderNo,
            7,
            skuId,
            month,
            Map.of("pay", payDb, "user", userDb, "order", orderDb, "stock", stockDb),
            Map.of("scenarioId", "NORMAL_ORDER_CREATE_WITH_REAL_RESERVE"),
            Map.of(
                "scenarioId", "NORMAL_ORDER_CREATE_WITH_REAL_RESERVE", "observedOutcome", phase));
    Map<String, Object> after = ownedChecksums();
    assertEquals(before, after, "NORMAL_CREATE_PHASE_DATABASES_CHANGED");
    Map<String, Object> link = new LinkedHashMap<>();
    link.put("phase", phase);
    link.put("scenarioId", "NORMAL_ORDER_CREATE_WITH_REAL_RESERVE");
    link.put("phaseArtifact", creationReference(artifact));
    link.put("tcArtifact", creationReference(creationTcArtifact));
    link.put("tcApplicability", "ORIGINAL_COMMIT_NATIVE_TERMINAL");
    link.put("ownedChecksumsBefore", before);
    link.put("ownedChecksumsAfter", after);
    link.put("ownedChecksumsUnchanged", true);
    link.put("automaticActionsAllowed", false);
    link.put("retention", "NO_AUTOMATIC_EXPIRY");
    creationPhaseLinks.add(link);
    writeCreationArtifact("normal-create-phase-link-" + phase + ".json", link);
  }

  private Map<String, Object> creationReference(Path path) throws Exception {
    byte[] bytes = Files.readAllBytes(path);
    return Map.of(
        "relativePath",
        artifacts.relativize(path).toString().replace('\\', '/'),
        "sha256",
        sha256(bytes),
        "bytes",
        bytes.length);
  }

  private void writeCreationArtifact(String name, Object value) throws Exception {
    Files.writeString(
        artifacts.resolve(name),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(value),
        StandardOpenOption.CREATE_NEW);
  }

  /** 停止旧 owned Order 后才启动外置测试 main，不改变生产扫描和 Boot jar 内容。 */
  private void startCreationOrder() throws Exception {
    assertEquals(
        0, orderDb.queryForObject("SELECT COUNT(*) FROM order_reservation_intent", Integer.class));
    assertEquals(0, orderDb.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
    stopCurrentOrder();
    creationControlPort = availablePort();
    creationControlKey = UUID.randomUUID().toString();
    startService(
        "order",
        List.of(
            "--control-port=" + creationControlPort,
            "--control-key=" + creationControlKey,
            "--creation-key-a=" + creationKeyA,
            "--creation-key-b=" + creationKeyB),
        "com.cloudmall.order.OrderCreationFaultProcess");
    await(
        () -> {
          var state = creationState();
          return state.path("ready").asBoolean()
              && state.path("firstRecoveryEmptyReturned").asBoolean()
              && state.path("firstDispatcherEmptyReturned").asBoolean();
        },
        35);
    var ready = requiredCreationState();
    assertEquals(
        0, orderDb.queryForObject("SELECT COUNT(*) FROM order_reservation_intent", Integer.class));
    assertEquals(0, orderDb.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
    Map<String, Object> startup = new LinkedHashMap<>();
    startup.put("runId", batch);
    startup.put("process", ready);
    startup.put(
        "orderJarSha256",
        sha256(
            Files.readAllBytes(
                Path.of(
                    "..", "cloud-mall-order", "target", "cloud-mall-order-0.1.0-SNAPSHOT.jar"))));
    startup.put(
        "helperSourceSha256",
        sha256(
            Files.readAllBytes(
                Path.of(
                    "..",
                    "cloud-mall-order",
                    "src",
                    "test",
                    "java",
                    "com",
                    "cloudmall",
                    "order",
                    "OrderCreationFaultProcess.java"))));
    startup.put(
        "helperClassSha256",
        sha256(
            Files.readAllBytes(
                Path.of(
                    "..",
                    "cloud-mall-order",
                    "target",
                    "test-classes",
                    "com",
                    "cloudmall",
                    "order",
                    "OrderCreationFaultProcess.class"))));
    startup.put("loaderPath", "../cloud-mall-order/target/test-classes");
    startup.put("configurationSource", "ORIGINAL_ORDER_APPLICATION");
    startup.put("reservationRecoveryIntervalMillis", 30000);
    startup.put("outboxDispatchIntervalMillis", 1000);
    startup.put("initialDelayConfigured", false);
    startup.put("emptyIntentRowsBeforeAndAfter", true);
    startup.put("emptyOutboxRowsBeforeAndAfter", true);
    startup.put("metadata", creationMetadata.snapshot());
    writeCreationArtifact("order-create-process-startup.json", startup);
  }

  private void stopCurrentOrder() throws Exception {
    Process process = children.get("order");
    if (process != null) {
      stopProtocolProcess(process);
      assertFalse(process.isAlive());
      naming.deregisterInstance(
          "cloud-mall-order", batch, "127.0.0.1", proxies.get("order").port());
      proxies.get("order").close();
    }
  }

  private void stopCreationOrderAndRestore() throws Exception {
    var state = requiredCreationState();
    assertEquals(0, state.path("activeRecovery").asInt(-1));
    assertEquals(0, state.path("activeDispatcher").asInt(-1));
    assertEquals(0, state.path("activeCallbacks").asInt(-1));
    writeCreationArtifact(
        "order-create-final-state.json",
        Map.of("observer", state, "metadata", creationMetadata.snapshot()));
    Process helper = children.get("order");
    long helperPid = helper.pid();
    stopCurrentOrder();
    creationMetadata.close();
    assertTrue(creationMetadata.closed);
    startService("order");
    writeCreationArtifact(
        "order-create-restored.json",
        Map.of(
            "helperPid",
            helperPid,
            "helperExited",
            !helper.isAlive(),
            "restoredOrderPid",
            children.get("order").pid(),
            "restoredOriginalBootJar",
            true,
            "metadataClosed",
            true,
            "allHelperExecutorThreadsJoinedProven",
            false,
            "helperWorkerTerminationScope",
            "OWNED_JVM_EXIT_CONFIRMED"));
  }

  private com.fasterxml.jackson.databind.JsonNode creationState() {
    try {
      HttpResponse<String> response = creationControl("GET", "/state");
      if (response.statusCode() != 200) return JSON.createObjectNode();
      var state = JSON.readTree(response.body());
      if (!state.path("ready").asBoolean()) return JSON.createObjectNode();
      assertEquals(children.get("order").pid(), state.path("pid").asLong());
      assertEquals(
          children.get("order").info().startInstant().orElseThrow().toString(),
          state.path("startedAt").asText());
      assertTrue(state.path("failures").isArray());
      assertTrue(state.path("failures").isEmpty(), "ORIGINAL_ORDER_OBSERVER_FAILURE");
      assertFalse(state.path("boundExceeded").asBoolean(true));
      return state;
    } catch (java.io.IOException | InterruptedException unavailable) {
      if (unavailable instanceof InterruptedException) Thread.currentThread().interrupt();
      return JSON.createObjectNode();
    }
  }

  private com.fasterxml.jackson.databind.JsonNode requiredCreationState() {
    var state = creationState();
    assertTrue(state.path("ready").asBoolean(), "ORDER_CREATION_OBSERVER_UNAVAILABLE");
    return state;
  }

  private HttpResponse<String> creationControl(String method, String path)
      throws java.io.IOException, InterruptedException {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + creationControlPort + path))
            .timeout(Duration.ofSeconds(3))
            .header("X-Control-Key", creationControlKey)
            .method(method, HttpRequest.BodyPublishers.noBody())
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private boolean originalTimeoutPublicationFinished() {
    var state = creationState();
    return state.path("ready").asBoolean()
        && state.path("activeDispatcher").asInt(-1) == 0
        && state.path("activeCallbacks").asInt(-1) == 0
        && state.path("published").size() == 1
        && state.path("published").get(0).path("confirmObserved").asBoolean()
        && "PUBLISHED"
            .equals(
                orderDb.queryForObject(
                    "SELECT status FROM order_outbox WHERE order_no=?",
                    String.class,
                    request.orderNo));
  }

  private void assertOriginalTimeoutPublication() throws Exception {
    var state = requiredCreationState();
    assertTrue(originalTimeoutPublicationFinished());
    var publication = state.path("published").get(0);
    assertEquals("cloudmall.order.timeout.exchange", publication.path("exchange").asText());
    assertEquals("", publication.path("routingKey").asText());
    assertTrue(publication.path("mandatory").asBoolean());
    assertTrue(publication.path("confirmAck").asBoolean());
    assertFalse(publication.path("returned").asBoolean(true));
    assertEquals(
        hashText("order-timeout:" + request.orderNo),
        publication.path("correlationSha256").asText());
    assertEquals(
        hashText("order-timeout:" + request.orderNo), publication.path("eventIdSha256").asText());
    assertEquals(hashText(request.orderNo), publication.path("orderNoSha256").asText());
    var originalPayload =
        JSON.readTree(
            orderDb.queryForObject(
                "SELECT payload FROM order_outbox WHERE order_no=?",
                String.class,
                request.orderNo));
    SortedMap<String, com.fasterxml.jackson.databind.JsonNode> orderedPayload = new TreeMap<>();
    originalPayload
        .fields()
        .forEachRemaining(entry -> orderedPayload.put(entry.getKey(), entry.getValue()));
    assertEquals(
        sha256(JSON.writeValueAsBytes(orderedPayload)), publication.path("payloadSha256").asText());
    assertEquals(
        originalPayload.path("occurredAt").asText(), publication.path("occurredAt").asText());
    assertEquals(originalPayload.path("expireAt").asText(), publication.path("expireAt").asText());
    long expiration = Long.parseLong(publication.path("expiration").asText());
    assertTrue(expiration > 0 && expiration <= 1800000);
    LocalDateTime expires =
        localTime(
            orderDb.queryForObject(
                "SELECT expire_at FROM mall_order_" + month + " WHERE order_no=?",
                Object.class,
                request.orderNo));
    long remainingAtSend =
        Duration.between(
                Instant.parse(publication.path("sentAt").asText()),
                expires.atOffset(ZoneOffset.ofHours(8)).toInstant())
            .toMillis();
    assertTrue(expiration >= remainingAtSend && expiration - remainingAtSend < 5000);
    writeCreationArtifact(
        "order-timeout-original-publication.json",
        Map.of(
            "publication",
            publication,
            "expireAt",
            expires.atOffset(ZoneOffset.ofHours(8)).toString(),
            "remainingTtlAtObservationMillis",
            remainingAtSend,
            "outboxStatus",
            "PUBLISHED",
            "scope",
            "ORIGINAL_FUTURE_TTL_PUBLISHER_ONLY",
            "targetConsumptionObserved",
            false,
            "mqTargetEvidenceRequired",
            true,
            "automaticActionsAllowed",
            false));
  }

  private void assertRecoveryTrace(com.fasterxml.jackson.databind.JsonNode state) {
    assertEquals(1, state.path("expectedBReserveFailures").asInt());
    List<String> types = new ArrayList<>();
    for (var event : state.path("bEvents")) {
      assertEquals(hashText(reservationOrderB), event.path("orderNoSha256").asText());
      types.add(event.path("type").asText());
      if (event.path("type").asText().equals("STOCK_ROLLBACK")) {
        assertEquals("0", event.path("responseCode").asText());
        assertEquals(
            hashText(reservationOrderB + ":ROLLBACK"), event.path("operationKeySha256").asText());
        assertEquals(
            "ORIGINAL_DEFAULT_METHOD_CONTRACT_AND_PERSISTED_FLOW",
            event.path("operationKeySource").asText());
      }
    }
    assertEquals(
        List.of(
            "STOCK_RESERVE_RESPONSE_FAILURE",
            "CLAIM_RECOVERY",
            "STOCK_RESERVATION",
            "STOCK_ROLLBACK",
            "STOCK_RESERVATION",
            "MARK_RELEASED"),
        types);
    assertTrue(state.path("recoveryReturns").asInt() >= 2);
    assertEquals(
        1,
        stockDb.queryForObject(
            "SELECT COUNT(*) FROM stock_flow WHERE order_no=? AND sku_id=? AND flow_type='ROLLBACK'"
                + " AND idempotency_key=?",
            Integer.class,
            reservationOrderB,
            reservationSkuB,
            reservationOrderB + ":ROLLBACK:" + reservationSkuB));
  }

  private void assertCreationSettled() throws Exception {
    assertTrue(request != null && reservationOrderB != null && creationTcArtifact != null);
    assertEquals("SUCCESS", paymentState());
    assertEquals("PAID", orderState());
    assertFunds("90.00", 1, 0);
    assertReservation(request.orderNo, skuId, "CONFIRMED", 9, 0, 1, 1, 1, 0);
    assertEquals("RELEASED", creationIntent(creationKeyB).get("status"));
    assertCreationRows(reservationOrderB, 0);
    assertNoPaymentForOrder(reservationOrderB);
    assertReservation(reservationOrderB, reservationSkuB, "ROLLED_BACK", 10, 0, 0, 1, 0, 1);
    assertTcCommitEvidence(creationTcArtifact);
    assertTrue(xids.stream().allMatch(PayTccDeliveryIT::terminal));
    assertRecoveryTrace(requiredCreationState());
    assertEquals(0, creationMetadata.rejectedRequests);
    assertEquals(2, creationMetadata.productReads);
    assertEquals(2, creationMetadata.cartReads);
    assertEquals(0, creationMetadata.writeRequests);
  }

  /** 本 run 的元数据仅经原 Feign/Nacos 读取；不承担 Product/Cart 业务验收。 */
  private static final class MetadataFixture implements AutoCloseable {
    /** 两个固定白名单 SKU 与精确 owned server/executor。 */
    private final Set<Long> skus;

    /** 元数据 HTTP 请求专用的两个线程，close 必须有限等待终止。 */
    private final ExecutorService executor = Executors.newFixedThreadPool(2);

    /** 仅绑定 127.0.0.1 的自有 HTTP server。 */
    private HttpServer server;

    /** 有界请求计数；任何未授权身份、路径或写方法都使代表失败。 */
    private volatile int productReads, cartReads, writeRequests, rejectedRequests;

    /** 两个 Nacos 注册共享的本机自有端口，关闭后状态。 */
    private int port;

    /** 只有注销、server 关闭、executor 退出均确认后才标记已关闭。 */
    private boolean closed;

    private MetadataFixture(long firstSku, long secondSku) {
      skus = Set.of(firstSku, secondSku);
    }

    private void start() throws Exception {
      port = availablePort();
      server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 10);
      server.createContext("/", this::handle);
      server.setExecutor(executor);
      server.start();
      for (String service : List.of("product", "cart"))
        naming.registerInstance("cloud-mall-" + service, batch, "127.0.0.1", port);
    }

    private synchronized void handle(HttpExchange exchange) throws java.io.IOException {
      Object response = Map.of("code", "FIXTURE_REJECTED");
      int status = 404;
      try {
        String path = exchange.getRequestURI().getPath();
        boolean get = "GET".equals(exchange.getRequestMethod());
        boolean permitted = false;
        if (!get) writeRequests++;
        if (get
            && "7".equals(exchange.getRequestHeaders().getFirst("X-User-Id"))
            && exchange.getRequestURI().getRawQuery() == null
            && exchange.getRequestBody().readNBytes(1025).length == 0) {
          for (long allowedSku : skus) {
            if (path.equals("/api/products/skus/" + allowedSku)) {
              productReads++;
              response =
                  Map.of(
                      "code",
                      "0",
                      "data",
                      Map.of(
                          "skuId",
                          allowedSku,
                          "productId",
                          1,
                          "productName",
                          "Creation fixture " + allowedSku,
                          "skuCode",
                          "fixture-" + allowedSku,
                          "unitPrice",
                          "10.00",
                          "skuSnapshot",
                          Map.of("fixture", "normal-create", "skuId", Long.toString(allowedSku))));
              permitted = true;
            } else if (path.equals("/api/cart/items/" + allowedSku + "/cleanup-snapshot")) {
              cartReads++;
              Map<String, Object> emptyCart = new LinkedHashMap<>();
              emptyCart.put("code", "0");
              emptyCart.put("data", null);
              response = emptyCart;
              permitted = true;
            }
          }
        }
        if (permitted) status = 200;
        else rejectedRequests++;
        if (productReads + cartReads + rejectedRequests > 100) {
          status = 503;
          response = Map.of("code", "FIXTURE_REQUEST_BOUND_EXCEEDED");
          rejectedRequests++;
        }
        byte[] bytes = JSON.writeValueAsBytes(response);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
          output.write(bytes);
        }
      } finally {
        exchange.close();
      }
    }

    private Map<String, Object> snapshot() {
      return Map.of(
          "scope",
          "READ_ONLY_PRODUCT_CART_METADATA_DOUBLE",
          "port",
          port,
          "productReads",
          productReads,
          "cartReads",
          cartReads,
          "writeRequests",
          writeRequests,
          "rejectedRequests",
          rejectedRequests,
          "closed",
          closed,
          "realProductCartIntegrationProven",
          false);
    }

    /** 只注销本 group 两个确切端口，关闭自有回环 server 并收齐 executor。 */
    @Override
    public void close() throws Exception {
      if (closed) return;
      Throwable primary = null;
      for (String service : List.of("product", "cart")) {
        try {
          naming.deregisterInstance("cloud-mall-" + service, batch, "127.0.0.1", port);
        } catch (Exception failure) {
          if (primary == null) primary = failure;
          else primary.addSuppressed(failure);
        }
      }
      if (server != null) server.stop(0);
      executor.shutdownNow();
      if (!executor.awaitTermination(15, TimeUnit.SECONDS)) {
        preserve = true;
        AssertionError failure = new AssertionError("METADATA_WORKER_UNRESOLVED");
        if (primary == null) primary = failure;
        else primary.addSuppressed(failure);
      }
      closed = primary == null;
      if (primary instanceof Exception failure) throw failure;
      if (primary instanceof AssertionError failure) throw failure;
    }
  }

  /** 真实mandatory return不得结束outbox，原事件重试消费后才释放库存和原款项。 */
  @Test
  @Order(11)
  void returnedTimeoutEventRetriesOriginalEnvelopeAndRefundsAcrossFourServices() throws Exception {
    verifyTimeoutEventFunds(false);
  }

  /** 原事务提交但未ACK时硬停Order，真实broker重投与同事件重复均只产生一次资金效果。 */
  @Test
  @Order(12)
  void committedTimeoutBeforeAckRedeliversAndDuplicateCannotRepeatRefund() throws Exception {
    verifyTimeoutEventFunds(true);
  }

  /** 两条真实消息故障共享原TCC和原退款门；所有consumer证据均来自原listener外层观察。 */
  private void verifyTimeoutEventFunds(boolean crashBeforeAck) throws Exception {
    mqRound = true;
    mqArtifacts.add(tcWindowAnchorArtifact);
    protocolProcesses.put("scenarioId", crashBeforeAck ? "MQ-R2" : "MQ-R1");
    String eventId = "order-timeout:" + request.orderNo;
    ch.qos.logback.classic.Logger sdkLogger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger("io.seata.tm.api.DefaultGlobalTransaction");
    var begins =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    begins.start();
    sdkLogger.addAppender(begins);
    CachingConnectionFactory broker = new CachingConnectionFactory("127.0.0.1", 15673);
    broker.setUsername("root");
    broker.setPassword("root");
    broker.setVirtualHost(batch);
    broker.setPublisherConfirmType(CachingConnectionFactory.ConfirmType.CORRELATED);
    broker.setPublisherReturns(true);
    Throwable primaryFailure = null;
    try {
      commitFunds();
      assertFunds("90.00", 1, 0);
      assertStock(9, 1, 0);
      assertEquals("PENDING_PAYMENT", orderState());
      Map<String, Object> identity = originalIdentity();
      String xid = (String) ((Map<?, ?>) identity.get("execution")).get("xid");
      xids.add(xid);
      mqArtifacts.add(
          PayTccTcBranchEvidence.capture(
              artifacts,
              "mq-commit",
              xid,
              ((Number) ((Map<?, ?>) identity.get("receipt")).get("branch_id")).longValue(),
              ((Number) ((Map<?, ?>) identity.get("receive")).get("branch_id")).longValue(),
              tcWindowStartedAt,
              tcWindowAnchorArtifact));
      assertTcCommitEvidence(mqArtifacts.get(mqArtifacts.size() - 1));
      restartObservedOrder(crashBeforeAck ? "AFTER_TIMEOUT_COMMIT" : "NONE");
      mqArtifacts.add(
          stage(
              "MQ_COMMITTED_BEFORE_EVENT",
              Map.of("observedOutcome", "TCC_COMMITTED_ORDER_PENDING")));
      RabbitAdmin admin = new RabbitAdmin(broker);
      Binding binding = timeoutDeadLetterBinding();
      if (!crashBeforeAck) {
        admin.removeBinding(binding);
        mqDriverActions.add(
            Map.of("kind", "REMOVE_OWN_DLX_BINDING", "at", OffsetDateTime.now().toString()));
      }
      Map<String, Object> originalOutbox = seedTimeoutEvent(eventId);
      com.fasterxml.jackson.databind.JsonNode oldState;
      if (crashBeforeAck) {
        await(() -> observedOrderState().path("blocked").asBoolean(), 30);
        oldState = requiredObservedOrderState();
        assertEquals(1, oldState.path("deliveries").size());
        var receipt = oldState.path("deliveries").get(0);
        assertReceipt(receipt, eventId, null, false);
        assertTrue(receipt.path("transactionReturned").asBoolean());
        assertFalse(receipt.path("transactionActiveAfter").asBoolean());
        assertFalse(receipt.path("synchronizationActiveAfter").asBoolean());
        assertEquals(1, oldState.path("barrierCount").asInt());
        assertTrue(receipt.path("blocked").asBoolean());
        assertFalse(receipt.path("ackObserved").asBoolean());
        assertEquals("CANCELLED", orderState());
        Map<String, Object> actionIdentity = orderActionIdentity();
        await(() -> "PUBLISHED".equals(outboxState(eventId)), 15);
        oldState = requiredObservedOrderState();
        assertSuccessfulPublication(
            oldState.path("published"), eventId, receipt.path("bodySha256").asText());
        mqObservations.put("oldOrder", oldState);
        mqArtifacts.add(
            stage(
                "TX_COMMITTED_BEFORE_AUTO_ACK",
                Map.of(
                    "observedOutcome", "ORDER_CANCELLED_BEFORE_ACK", "transactionInactive", true)));
        long oldPid = children.get("order").pid();
        // 故障屏障不释放；精确硬停此Process，broker只能按真实未ACK消息重投。
        stopProtocolProcess(children.get("order"));
        mqDriverActions.add(
            Map.of(
                "kind",
                "HARD_STOP_AT_PRE_ACK_BARRIER",
                "pid",
                oldPid,
                "at",
                OffsetDateTime.now().toString()));
        restartObservedOrder("NONE");
        assertNotEquals(oldPid, children.get("order").pid());
        await(() -> observedOrderState().path("deliveries").size() >= 1, 30);
        var redelivered = requiredObservedOrderState().path("deliveries").get(0);
        assertReceipt(redelivered, eventId, receipt.path("bodySha256").asText(), true);
        await(
            () ->
                observedOrderState()
                    .path("deliveries")
                    .get(0)
                    .path("transactionReturned")
                    .asBoolean(),
            15);
        assertEquals(actionIdentity, orderActionIdentity());
        assertEquals(originalOutbox, immutableOutbox(eventId));
        var redeliveryState = requiredObservedOrderState();
        assertEquals(0, redeliveryState.path("published").size());
        assertEquals("PUBLISHED", outboxState(eventId));
        mqObservations.put("brokerRedelivery", redeliveryState);
        mqArtifacts.add(
            stage(
                "BROKER_REDELIVERED_ORIGINAL_EVENT",
                Map.of(
                    "observedOutcome", "ORIGINAL_EVENT_REDELIVERED", "brokerRedelivered", true)));
        RabbitTemplate duplicate = new RabbitTemplate(broker);
        duplicate.setMandatory(true);
        byte[] body = originalEnvelope(eventId);
        assertEquals(receipt.path("bodySha256").asText(), sha256(body));
        MessageProperties properties = new MessageProperties();
        properties.setContentType(MessageProperties.CONTENT_TYPE_JSON);
        CorrelationData correlation = new CorrelationData(eventId);
        duplicate.send(
            "cloudmall.order.timeout.dlx.exchange", "", new Message(body, properties), correlation);
        assertTrue(correlation.getFuture().get(5, TimeUnit.SECONDS).isAck());
        assertNull(correlation.getReturnedMessage());
        mqDriverActions.add(
            Map.of(
                "kind",
                "EXPLICIT_DUPLICATE_PUBLISH",
                "eventIdSha256",
                sha256(eventId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                "bodySha256",
                sha256(body),
                "confirmAck",
                true,
                "at",
                OffsetDateTime.now().toString()));
        await(() -> observedOrderState().path("deliveries").size() >= 2, 20);
        await(
            () ->
                observedOrderState()
                    .path("deliveries")
                    .get(1)
                    .path("transactionReturned")
                    .asBoolean(),
            15);
        var duplicateState = requiredObservedOrderState();
        assertEquals(2, duplicateState.path("deliveries").size());
        assertEquals(0, duplicateState.path("published").size());
        assertReceipt(duplicateState.path("deliveries").get(1), eventId, sha256(body), false);
        mqObservations.put("explicitDuplicate", duplicateState);
        assertEquals(actionIdentity, orderActionIdentity());
        mqArtifacts.add(
            stage(
                "EXPLICIT_DUPLICATE_CONSUMED",
                Map.of("observedOutcome", "DUPLICATE_NO_SECOND_ACTION")));
      } else {
        await(
            () ->
                observedOrderState().path("published").size() >= 1
                    && observedOrderState().path("published").get(0).path("returned").asBoolean(),
            20);
        var returnedState = requiredObservedOrderState();
        var returned = returnedState.path("published").get(0);
        assertPublicationIdentity(returned, eventId);
        assertTrue(returned.path("confirmObserved").asBoolean());
        assertTrue(returned.path("confirmAck").asBoolean());
        assertEquals(
            "cloudmall.order.timeout.dlx.exchange", returned.path("returnedExchange").asText());
        assertEquals("", returned.path("returnedRoutingKey").asText());
        assertEquals(312, returned.path("returnedReplyCode").asInt());
        assertEquals(returned.path("bodySha256"), returned.path("returnedBodySha256"));
        // confirm回调先于dispatcher写退避，等待原失败持久化后再核资金不变。
        await(
            () ->
                Integer.valueOf(1)
                    .equals(
                        orderDb.queryForObject(
                            "SELECT attempts FROM order_outbox WHERE event_id=?",
                            Integer.class,
                            eventId)),
            10);
        assertEquals("PENDING", outboxState(eventId));
        var outbox =
            orderDb.queryForMap(
                "SELECT attempts,published_at FROM order_outbox WHERE event_id=?", eventId);
        assertEquals(1, ((Number) outbox.get("attempts")).intValue());
        assertNull(outbox.get("published_at"));
        assertEquals(originalOutbox, immutableOutbox(eventId));
        assertEquals(0, observedOrderState().path("deliveries").size());
        assertEquals("PENDING_PAYMENT", orderState());
        assertEquals(
            0,
            orderDb.queryForObject(
                "SELECT COUNT(*) FROM order_stock_action WHERE order_no=?",
                Integer.class,
                request.orderNo));
        assertFunds("90.00", 1, 0);
        assertStock(9, 1, 0);
        mqObservations.put("returnedEvent", returnedState);
        mqArtifacts.add(
            stage(
                "RETURNED_EVENT_REMAINS_PENDING",
                Map.of("observedOutcome", "RETURNED_WITHOUT_CONSUMPTION")));
        admin.declareBinding(binding);
        mqDriverActions.add(
            Map.of("kind", "RESTORE_OWN_DLX_BINDING", "at", OffsetDateTime.now().toString()));
        orderDb.update(
            "UPDATE order_outbox SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
                + " event_id=?",
            eventId);
        await(() -> "PUBLISHED".equals(outboxState(eventId)), 20);
        await(
            () ->
                observedOrderState().path("deliveries").size() == 1
                    && observedOrderState()
                        .path("deliveries")
                        .get(0)
                        .path("transactionReturned")
                        .asBoolean(),
            30);
        var retryState = requiredObservedOrderState();
        var delivered = retryState.path("deliveries").get(0);
        assertReceipt(delivered, eventId, returned.path("bodySha256").asText(), false);
        assertSuccessfulPublication(
            retryState.path("published"), eventId, returned.path("bodySha256").asText());
        assertEquals(2, retryState.path("published").size());
        mqObservations.put("originalRetry", retryState);
        assertEquals(originalOutbox, immutableOutbox(eventId));
        mqArtifacts.add(
            stage(
                "ORIGINAL_EVENT_RETRY_CONSUMED",
                Map.of("observedOutcome", "RETRY_CANCELLED_ORIGINAL_ORDER")));
      }
      awaitStockAction();
      assertEquals("CANCELLED", orderState());
      assertFunds("90.00", 1, 0);
      assertOrderAndStock(request.orderNo, skuId, true);
      // 消息已取消原订单且原库存回滚成功后，才允许既有delivery执行原键退款。
      completeDelivery("REFUNDED");
      assertFunds("100.00", 1, 1);
      assertOriginalLedger(request.payNo, request.orderNo, true);
      assertEquals(identity, originalIdentity());
      assertEquals(
          "COMMITTED",
          payDb.queryForObject(
              "SELECT state FROM pay_tcc_execution WHERE pay_no=?", String.class, request.payNo));
      assertEquals(
          "COMMIT",
          payDb.queryForObject(
              "SELECT decision FROM pay_tcc_execution WHERE pay_no=?",
              String.class,
              request.payNo));
      List<Map<String, Object>> finalLedger =
          userDb.queryForList(
              "SELECT * FROM balance_ledger WHERE pay_no=? ORDER BY id", request.payNo);
      completeDelivery("REFUNDED");
      assertEquals(
          finalLedger,
          userDb.queryForList(
              "SELECT * FROM balance_ledger WHERE pay_no=? ORDER BY id", request.payNo));
      assertEquals(1, proxies.get("user").postCount());
      assertEquals(0, proxies.get("order").cancelPostCount());
      assertEquals(0, proxies.get("order").paidPostCount());
      assertEquals(
          1L,
          begins.list.stream()
              .filter(
                  event -> event.getFormattedMessage().contains("Begin new global transaction ["))
              .count());
      assertTrue(
          begins.list.stream()
              .anyMatch(
                  event ->
                      event
                          .getFormattedMessage()
                          .contains("Begin new global transaction [" + xid + "]")));
      await(
          () -> {
            var state = observedOrderState();
            int expected = crashBeforeAck ? 2 : 1;
            return state.path("deliveries").size() == expected
                && state.path("deliveries").get(expected - 1).path("ackObserved").asBoolean();
          },
          15);
      var finalState = requiredObservedOrderState();
      assertEquals(crashBeforeAck ? 2 : 1, finalState.path("deliveries").size());
      assertEquals(crashBeforeAck ? 0 : 2, finalState.path("published").size());
      assertEquals("PUBLISHED", outboxState(eventId));
      mqObservations.put("finalOrder", finalState);
      mqArtifacts.add(
          stage(
              "REFUND_CONFIRMED_AFTER_MQ_STOCK",
              Map.of(
                  "observedOutcome",
                  "REFUNDED",
                  "originalTryPosts",
                  1,
                  "originalBeginCount",
                  1,
                  "refundLedgerCount",
                  1)));
      stopProtocolProcess(children.get("order"));
      mqArtifacts.add(
          stage("MQ_PRE_CLEANUP", Map.of("observedOutcome", "MQ_EVENT_AND_FUNDS_VERIFIED")));
      writeMqEvidence(eventId, true);
    } catch (Throwable failure) {
      primaryFailure = failure;
      preserve = true;
      try {
        mqObservations.put("failureOrder", observedOrderState());
        writeMqEvidence(eventId, false);
      } catch (Throwable evidenceFailure) {
        failure.addSuppressed(evidenceFailure);
      }
      throw failure;
    } finally {
      sdkLogger.detachAppender(begins);
      begins.stop();
      try {
        broker.destroy();
        stopProtocolProcess(children.get("order"));
      } catch (Throwable cleanupFailure) {
        preserve = true;
        if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
        else throw cleanupFailure;
      }
    }
  }

  /** 原outbox种子与到期加速仅属于本场景，不证明订单创建时的原子outbox写入。 */
  private Map<String, Object> seedTimeoutEvent(String eventId) throws Exception {
    LocalDateTime expired =
        LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusMinutes(1).withNano(0);
    orderDb.update(
        "UPDATE mall_order_" + month + " SET expire_at=? WHERE order_no=?",
        expired,
        request.orderNo);
    Map<String, Object> payload = new LinkedHashMap<>();
    payload.put("eventId", eventId);
    payload.put("eventType", "ORDER_TIMEOUT");
    payload.put("orderNo", request.orderNo);
    payload.put("expireAt", expired.atOffset(ZoneOffset.ofHours(8)).toString());
    payload.put("occurredAt", OffsetDateTime.now().toString());
    payload.put("traceId", "cm18-mq-" + UUID.randomUUID());
    orderDb.update(
        "INSERT INTO"
            + " order_outbox(id,event_id,order_no,event_type,payload,status,attempts,next_retry_at,created_at,updated_at)"
            + " VALUES(?,?,?,'ORDER_TIMEOUT',?,'PENDING',0,DATE_SUB(NOW(3),INTERVAL 1"
            + " DAY),NOW(3),NOW(3))",
        System.nanoTime(),
        eventId,
        request.orderNo,
        JSON.writeValueAsString(payload));
    mqDriverActions.add(
        Map.of(
            "kind",
            "OWNED_EXPIRED_ORDER_AND_OUTBOX_SEED",
            "eventIdSha256",
            sha256(eventId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
            "at",
            OffsetDateTime.now().toString()));
    return immutableOutbox(eventId);
  }

  /** 仅比较原事件的不可变身份和原JSON正文，不含允许推进的重试字段。 */
  private Map<String, Object> immutableOutbox(String eventId) {
    return orderDb.queryForMap(
        "SELECT id,event_id,order_no,event_type,payload FROM order_outbox WHERE event_id=?",
        eventId);
  }

  private String outboxState(String eventId) {
    return orderDb.queryForObject(
        "SELECT status FROM order_outbox WHERE event_id=?", String.class, eventId);
  }

  /** 明确duplicate故障驱动复用生产envelope顺序与原payload，发送前核原body摘要。 */
  private byte[] originalEnvelope(String eventId) throws Exception {
    Map<String, Object> row = immutableOutbox(eventId);
    @SuppressWarnings("unchecked")
    Map<String, Object> payload = JSON.readValue((String) row.get("payload"), LinkedHashMap.class);
    Map<String, Object> envelope = new LinkedHashMap<>();
    envelope.put("eventId", eventId);
    envelope.put("eventType", "ORDER_TIMEOUT");
    envelope.put("orderNo", request.orderNo);
    envelope.put("expireAt", payload.get("expireAt"));
    envelope.put("occurredAt", payload.get("occurredAt"));
    envelope.put("traceId", payload.get("traceId"));
    envelope.put("payload", payload);
    return JSON.writeValueAsBytes(envelope);
  }

  private Map<String, Object> orderActionIdentity() {
    return orderDb.queryForMap(
        "SELECT id,order_no,action_type,operation_key FROM order_stock_action WHERE order_no=?",
        request.orderNo);
  }

  private static Binding timeoutDeadLetterBinding() {
    return new Binding(
        "cloudmall.order.timeout.dlx",
        Binding.DestinationType.QUEUE,
        "cloudmall.order.timeout.dlx.exchange",
        "",
        null);
  }

  private void assertReceipt(
      com.fasterxml.jackson.databind.JsonNode receipt,
      String eventId,
      String bodySha,
      boolean redelivered) {
    assertNotNull(receipt);
    assertEquals("cloudmall.order.timeout.dlx", receipt.path("queue").asText());
    assertEquals("cloudmall.order.timeout.dlx.exchange", receipt.path("exchange").asText());
    assertEquals("", receipt.path("routingKey").asText());
    assertEquals(
        sha256(eventId.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        receipt.path("eventIdSha256").asText());
    assertEquals(
        sha256(request.orderNo.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
        receipt.path("orderNoSha256").asText());
    assertTrue(receipt.path("redelivered").isBoolean());
    assertEquals(redelivered, receipt.path("redelivered").asBoolean());
    assertTrue(receipt.path("bodySha256").asText().matches("[a-f0-9]{64}"));
    if (bodySha != null) assertEquals(bodySha, receipt.path("bodySha256").asText());
  }

  private void assertSuccessfulPublication(
      com.fasterxml.jackson.databind.JsonNode publications, String eventId, String bodySha) {
    var successful =
        java.util.stream.StreamSupport.stream(publications.spliterator(), false)
            .filter(
                row ->
                    row.path("confirmObserved").asBoolean()
                        && row.path("confirmAck").asBoolean()
                        && !row.path("returned").asBoolean()
                        && bodySha.equals(row.path("bodySha256").asText()))
            .toList();
    assertEquals(1, successful.size(), "缺唯一原事件confirm且无return的真实发布记录");
    assertPublicationIdentity(successful.get(0), eventId);
  }

  private void assertPublicationIdentity(
      com.fasterxml.jackson.databind.JsonNode publication, String eventId) {
    String eventSha = sha256(eventId.getBytes(java.nio.charset.StandardCharsets.UTF_8));
    assertEquals(eventSha, publication.path("eventIdSha256").asText());
    assertEquals(eventSha, publication.path("correlationSha256").asText());
    assertTrue(publication.path("mandatory").asBoolean());
    assertEquals("cloudmall.order.timeout.dlx.exchange", publication.path("exchange").asText());
    assertEquals("", publication.path("routingKey").asText());
  }

  /** TC日志辅助器保持独立来源；COMMIT不伪造逐branch ACK。 */
  private void assertTcCommitEvidence(Path path) throws Exception {
    var evidence = JSON.readTree(Files.readString(path));
    assertEquals("SERVER_GLOBAL_COMMIT_OBSERVED", evidence.path("globalOutcome").asText());
    assertTrue(evidence.path("directionProvenByTc").asBoolean());
    assertFalse(evidence.path("branchDetailsObserved").asBoolean());
    // helper的完整可信字段在最终包中保留，两个branch注册由helper明确报告。
    assertTrue(evidence.path("pay").path("registrationObserved").asBoolean());
    assertTrue(evidence.path("user").path("registrationObserved").asBoolean());
    for (String participant : List.of("pay", "user")) {
      assertEquals("ACK_UNOBSERVED", evidence.path(participant).path("commitAck").asText());
      assertEquals("ACK_UNOBSERVED", evidence.path(participant).path("phaseTwo").asText());
    }
  }

  /** 事件证明单列真实driver写动作与被动trace，普通阶段包仍保持聚合MQ来源的保守范围。 */
  private void writeMqEvidence(String eventId, boolean passed) throws Exception {
    List<Map<String, Object>> references = new ArrayList<>();
    for (Path file : mqArtifacts)
      references.add(
          Map.of(
              "relativePath",
              artifacts.relativize(file).toString(),
              "bytes",
              Files.size(file),
              "sha256",
              sha256(Files.readAllBytes(file))));
    Map<String, Object> evidence = new LinkedHashMap<>();
    evidence.put("schemaVersion", "CM18_MQ_EVENT_EVIDENCE_V1");
    evidence.put("scope", "ISOLATED_TEST_EVENT_CAUSALITY");
    evidence.put("automaticActionsAllowed", false);
    evidence.put("runId", batch);
    evidence.put("scenarioId", protocolProcesses.get("scenarioId"));
    evidence.put("phase", passed ? "VERIFIED" : "FAILURE_OBSERVED");
    evidence.put("capturedAt", OffsetDateTime.now().toString());
    evidence.put(
        "subject",
        Map.of(
            "payNo",
            request.payNo,
            "orderNo",
            request.orderNo,
            "eventIdSha256",
            sha256(eventId.getBytes(java.nio.charset.StandardCharsets.UTF_8))));
    evidence.put("virtualHost", batch);
    evidence.put("queue", "cloudmall.order.timeout.dlx");
    evidence.put("observations", mqObservations);
    evidence.put("driverActions", mqDriverActions);
    evidence.put("eventCausalityProven", passed);
    evidence.put("causalityScope", "ONLY_THIS_EVENT_PUBLISH_CONSUME_AND_OWNED_BUSINESS_EFFECTS");
    evidence.put("brokerAckHistoryProven", false);
    evidence.put("artifactManifest", references);
    Files.writeString(
        artifacts.resolve("mq-event-evidence-" + request.payNo + "-" + UUID.randomUUID() + ".json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(evidence),
        StandardOpenOption.CREATE_NEW);
  }

  private static String sha256(byte[] bytes) {
    try {
      return java.util.HexFormat.of()
          .formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));
    } catch (java.security.NoSuchAlgorithmException impossible) {
      throw new IllegalStateException(impossible);
    }
  }

  /** 更换的仅是同manifest自有Order进程，保留原handler/事务/AUTO ACK与retry。 */
  private void restartObservedOrder(String fault) throws Exception {
    Process previous = children.get("order");
    if (previous != null) {
      stopProtocolProcess(previous);
      naming.deregisterInstance(
          "cloud-mall-order", batch, "127.0.0.1", proxies.get("order").port());
      proxies.get("order").close();
    }
    mqControlPort = availablePort();
    mqControlKey = UUID.randomUUID().toString();
    startService(
        "order",
        List.of(
            "--control-port=" + mqControlPort,
            "--control-key=" + mqControlKey,
            "--fault=" + fault,
            "--target-order-no=" + request.orderNo,
            "--spring.rabbitmq.listener.simple.auto-startup=false",
            "--spring.rabbitmq.listener.simple.concurrency=1",
            "--spring.rabbitmq.listener.simple.max-concurrency=1",
            "--spring.rabbitmq.listener.simple.prefetch=1",
            "--spring.rabbitmq.listener.simple.batch-size=1",
            "--cloudmall.order.outbox.dispatch-interval-ms=200",
            "--cloudmall.order.outbox.retry-delay-seconds=3600"));
    await(() -> observedOrderState().path("ready").asBoolean(), 15);
    mqDriverActions.add(
        Map.of(
            "kind",
            "START_OWN_ORDER_OBSERVER",
            "pid",
            children.get("order").pid(),
            "fault",
            fault,
            "at",
            OffsetDateTime.now().toString(),
            "startedAt",
            children.get("order").info().startInstant().orElseThrow().toString(),
            "jarSha256",
            sha256(
                Files.readAllBytes(
                    Path.of(
                        "..",
                        "cloud-mall-order",
                        "target",
                        "cloud-mall-order-0.1.0-SNAPSHOT.jar"))),
            "helperSourceSha256",
            sha256(
                Files.readAllBytes(
                    Path.of(
                        "..",
                        "cloud-mall-order",
                        "src",
                        "test",
                        "java",
                        "com",
                        "cloudmall",
                        "order",
                        "OrderTimeoutFaultProcess.java")))));
  }

  private com.fasterxml.jackson.databind.JsonNode observedOrderState() {
    try {
      var response =
          HTTP.send(
              HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + mqControlPort + "/state"))
                  .timeout(Duration.ofSeconds(3))
                  .header("X-Control-Key", mqControlKey)
                  .GET()
                  .build(),
              HttpResponse.BodyHandlers.ofString());
      if (response.statusCode() != 200) return JSON.createObjectNode();
      var state = JSON.readTree(response.body());
      if (!state.path("ready").asBoolean()) return JSON.createObjectNode();
      assertEquals("CM18_ORDER_TIMEOUT_PROCESS_TRACE_V1", state.path("schemaVersion").asText());
      assertEquals(children.get("order").pid(), state.path("processPid").asLong());
      assertEquals(
          sha256(
              ("order-timeout:" + request.orderNo)
                  .getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          state.path("targetEventIdSha256").asText());
      assertEquals(
          sha256(request.orderNo.getBytes(java.nio.charset.StandardCharsets.UTF_8)),
          state.path("targetOrderNoSha256").asText());
      assertTrue(state.path("boundExceeded").isBoolean());
      assertFalse(state.path("boundExceeded").asBoolean());
      assertTrue(state.path("droppedEvents").isIntegralNumber());
      assertEquals(0, state.path("droppedEvents").asInt());
      assertEquals("AUTO", state.path("ackMode").asText());
      assertTrue(state.path("publisherObserverInstalled").asBoolean());
      assertTrue(state.path("retryPreserved").asBoolean());
      assertEquals(3, state.path("retryMaxAttempts").asInt());
      assertFalse(state.path("defaultRequeueRejected").asBoolean());
      assertEquals(1, state.path("prefetch").asInt());
      assertEquals(1, state.path("concurrency").asInt());
      for (var receipt : state.path("deliveries")) {
        assertFalse(receipt.has("listenerFailureClass"));
        assertFalse(receipt.has("ackFailureClass"));
      }
      for (var publication : state.path("published"))
        assertFalse(publication.has("confirmFailureClass"));
      return state;
    } catch (Exception unavailable) {
      return JSON.createObjectNode();
    }
  }

  /** 轮询可以等待暂不可达；作为成功证据的快照必须通过完整身份和有界性校验。 */
  private com.fasterxml.jackson.databind.JsonNode requiredObservedOrderState() {
    var state = observedOrderState();
    assertTrue(state.path("ready").asBoolean(), "Order观察快照不可用，禁止接受缺证成功");
    return state;
  }

  /** 默认 LEGACY 的新 JVM 自动接续旧 COMMIT，并公开完成另一笔 Legacy 支付。 */
  @Test
  @Order(9)
  void freshDefaultLegacyJvmRecoversOriginalCommitAndAcceptsNewLegacyPayment() throws Exception {
    verifyProtocolRollback(false);
  }

  /** 显式 LEGACY 的新 JVM 只重放原退款键，原资金返还不会发生第二次。 */
  @Test
  @Order(10)
  void freshExplicitLegacyJvmRecoversOriginalRefundAndAcceptsNewLegacyPayment() throws Exception {
    verifyProtocolRollback(true);
  }

  /** 在真实事务已经提交的调用边界断开旧进程，新进程恢复期间父实例只读。 */
  private void verifyProtocolRollback(boolean refund) throws Exception {
    protocolRound = true;
    protocolProcesses.put("scenarioId", refund ? "M4-R2" : "M4-R1");
    CompletableFuture<HttpResponse<String>> inflight = null;
    Throwable primaryFailure = null;
    try {
      startProtocolPay(false, "TCC", refund ? "NONE" : "BEFORE_COMMIT");
      payDb.update("DELETE FROM pay_record WHERE pay_no=?", request.payNo);
      HttpResponse<String> created =
          protocolPost("/api/payments", Map.of("orderNo", request.orderNo, "payAmount", "10.00"));
      assertEquals(200, created.statusCode(), created.body());
      request.payNo = JSON.readTree(created.body()).path("data").path("payNo").asText();
      assertFalse(request.payNo.isBlank());
      assertEquals(
          "TCC",
          payDb.queryForObject(
              "SELECT debit_mode FROM pay_record WHERE pay_no=?", String.class, request.payNo));
      stage("created", Map.of("observedOutcome", "TCC_CREATED"));
      HttpRequest execute =
          HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + protocolControlPort + "/execute"))
              .timeout(Duration.ofSeconds(90))
              .header("X-Control-Key", protocolControlKey)
              .header("Content-Type", "application/json")
              .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)))
              .build();
      inflight = HTTP.sendAsync(execute, HttpResponse.BodyHandlers.ofString());
      if (refund) {
        HttpResponse<String> committed = inflight.get(30, TimeUnit.SECONDS);
        assertEquals(200, committed.statusCode(), committed.body());
        assertEquals("COMMITTED", JSON.readTree(committed.body()).path("state").asText());
        cancelOrder();
        awaitStockAction();
        proxies.get("user").dropNextPostBodyForPath("/refunds");
        HttpResponse<String> pending = protocolControl("/deliver", Map.of("payNo", request.payNo));
        assertEquals(200, pending.statusCode(), pending.body());
        assertEquals("REFUND_PENDING", JSON.readTree(pending.body()).path("state").asText());
        assertEquals("REFUND_PENDING", paymentState());
        assertFunds("100.00", 1, 1);
        assertEquals(1, proxies.get("user").refundPostCount());
      } else {
        await(
            () -> {
              try {
                return JSON.readTree(protocolControl("/state", null).body())
                    .path("barrier")
                    .asBoolean();
              } catch (Exception unavailable) {
                return false;
              }
            },
            30);
        assertEquals(
            "COMMIT_REQUESTED",
            payDb.queryForObject(
                "SELECT state FROM pay_tcc_execution WHERE pay_no=?", String.class, request.payNo));
        assertEquals(
            "90.00",
            userDb.queryForObject("SELECT balance FROM mall_user WHERE id=7", String.class));
        assertEquals(
            "10.00",
            userDb.queryForObject("SELECT frozen_balance FROM mall_user WHERE id=7", String.class));
        assertEquals(
            0,
            userDb.queryForObject(
                "SELECT COUNT(*) FROM balance_ledger WHERE pay_no=?",
                Integer.class,
                request.payNo));
      }
      Map<String, Object> identity = originalIdentity();
      String xid = (String) ((Map<?, ?>) identity.get("execution")).get("xid");
      xids.add(xid);
      if (!refund)
        assertEquals(
            xid, JSON.readTree(protocolControl("/state", null).body()).path("xid").asText());
      List<Map<String, Object>> refundLedger =
          userDb.queryForList(
              "SELECT * FROM balance_ledger WHERE pay_no=? ORDER BY id", request.payNo);
      assertEquals(1, proxies.get("user").postCount());
      stage(
          "durable-fault",
          Map.of(
              "observedOutcome",
              refund ? "REFUND_PENDING" : "COMMIT_REQUESTED",
              "originalTryPosts",
              1));
      stopProtocolProcess(originalPay);
      protocolProcesses.put("oldExitedAt", OffsetDateTime.now().toString());
      inflight.handle((response, failure) -> null).get(15, TimeUnit.SECONDS);
      stage("old-exited", Map.of("observedOutcome", "OLD_JVM_EXIT_CONFIRMED"));
      payDb.update(
          "UPDATE pay_tcc_execution SET next_recovery_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
              + " pay_no=?",
          request.payNo);
      forceDue();
      protocolProcesses.put(
          "fixtureDueAdjustment", "only next_recovery_at/next_retry_at made due after old exit");
      startProtocolPay(true, refund ? "LEGACY" : null, "NONE");
      assertNotEquals(originalPay.pid(), restartedPay.pid());
      stage("new-pay-started", Map.of("observedOutcome", "NEW_LEGACY_JVM_READY"));
      await(() -> (refund ? "REFUNDED" : "SUCCESS").equals(paymentState()), 45);
      assertEquals(identity, originalIdentity());
      assertEquals(
          "COMMITTED",
          payDb.queryForObject(
              "SELECT state FROM pay_tcc_execution WHERE pay_no=?", String.class, request.payNo));
      assertEquals(
          "COMMIT",
          payDb.queryForObject(
              "SELECT decision FROM pay_tcc_execution WHERE pay_no=?",
              String.class,
              request.payNo));
      assertEquals(
          refund ? "REFUNDED" : "SUCCESS",
          payDb.queryForObject(
              "SELECT state FROM pay_tcc_delivery WHERE pay_no=?", String.class, request.payNo));
      assertOriginalLedger(request.payNo, request.orderNo, refund);
      if (refund) {
        assertEquals(
            refundLedger,
            userDb.queryForList(
                "SELECT * FROM balance_ledger WHERE pay_no=? ORDER BY id", request.payNo));
        assertTrue(proxies.get("user").refundPostCount() >= 2);
        assertEquals(0, proxies.get("order").paidPostCount());
        assertStock(10, 0, 0);
      } else {
        assertTrue(
            payDb.queryForObject(
                    "SELECT recovery_attempts FROM pay_tcc_execution WHERE pay_no=?",
                    Integer.class,
                    request.payNo)
                >= 1);
        assertStock(9, 0, 1);
      }
      assertOrderAndStock(request.orderNo, skuId, refund);
      stage(
          "original-recovered",
          Map.of(
              "observedOutcome",
              refund ? "REFUNDED" : "SUCCESS",
              "originalIdentityUnchanged",
              true));
      verifyNewLegacyPayment(refund);
      assertEquals(identity, originalIdentity());
      assertOriginalLedger(request.payNo, request.orderNo, refund);
      assertEquals(1, proxies.get("user").postCount());
      assertFunds(refund ? "90.00" : "80.00", 2, refund ? 1 : 0);
      stage(
          "legacy-completed",
          Map.of(
              "observedOutcome",
              "OLD_TCC_RECOVERED_AND_NEW_LEGACY_SUCCEEDED",
              "originalIdentityUnchanged",
              true,
              "originalTryPosts",
              1,
              "newLegacyReplayed",
              true));
      stopProtocolProcess(restartedPay);
      protocolProcesses.put("newExitedAt", OffsetDateTime.now().toString());
      String originalLog =
          Files.readString(Path.of((String) ((Map<?, ?>) protocolProcesses.get("old")).get("log")));
      String replacementLog =
          Files.readString(Path.of((String) ((Map<?, ?>) protocolProcesses.get("new")).get("log")));
      assertEquals(
          1L,
          originalLog
              .lines()
              .filter(line -> line.contains("Begin new global transaction ["))
              .count());
      assertTrue(originalLog.contains("Begin new global transaction [" + xid + "]"));
      assertEquals(
          0L,
          replacementLog
              .lines()
              .filter(line -> line.contains("Begin new global transaction ["))
              .count());
      protocolProcesses.put("verifiedBeginCounts", Map.of("old", 1, "new", 0));
      Map<String, Object> beforeCollection = ownedChecksums();
      Path stableBundle =
          stage(
              "before-cleanup",
              Map.of(
                  "observedOutcome", "ISOLATED_ROLLBACK_VERIFIED", "bothPayProcessesExited", true));
      Path repeatedBundle =
          stage(
              "repeat-readonly",
              Map.of(
                  "observedOutcome", "ISOLATED_ROLLBACK_VERIFIED", "bothPayProcessesExited", true));
      assertEquals(beforeCollection, ownedChecksums(), "阶段采集不得改变隔离库任何表事实");
      var stableFacts = JSON.readTree(Files.readString(stableBundle));
      var repeatedFacts = JSON.readTree(Files.readString(repeatedBundle));
      assertEquals("EVIDENCE_INCOMPLETE", stableFacts.path("closureClassification").asText());
      assertFalse(stableFacts.path("factsDigest").asText().isBlank());
      assertEquals(stableFacts.path("factsDigest"), repeatedFacts.path("factsDigest"));
    } catch (Throwable failure) {
      primaryFailure = failure;
      preserve = true;
      Files.writeString(
          artifacts.resolve("protocol-unresolved-" + request.payNo + ".txt"),
          failure.getClass().getName());
      try {
        stage(
            "failure-observed",
            Map.of(
                "observedOutcome",
                "FAILURE_OBSERVED",
                "failureType",
                failure.getClass().getSimpleName()));
      } catch (Throwable captureFailure) {
        failure.addSuppressed(captureFailure);
      }
      throw failure;
    } finally {
      try {
        stopProtocolProcess(originalPay);
        stopProtocolProcess(restartedPay);
      } catch (Throwable cleanupFailure) {
        preserve = true;
        if (primaryFailure != null) primaryFailure.addSuppressed(cleanupFailure);
        else throw cleanupFailure;
      }
    }
  }

  /** 原执行和两端分支只比较不可变身份，恢复允许推进终态与重试诊断。 */
  private Map<String, Object> originalIdentity() {
    return Map.of(
        "payment",
        payDb.queryForMap(
            "SELECT id,pay_no,user_id,order_no,amount,debit_mode FROM pay_record WHERE pay_no=?",
            request.payNo),
        "execution",
        payDb.queryForMap(
            "SELECT id,pay_no,user_id,order_no,amount,xid,operation_key,ledger_key FROM"
                + " pay_tcc_execution WHERE pay_no=?",
            request.payNo),
        "receipt",
        payDb.queryForMap(
            "SELECT id,pay_no,xid,branch_id,user_id,order_no,amount,operation_key,ledger_key"
                + " FROM pay_tcc_receipt WHERE pay_no=?",
            request.payNo),
        "dispatch",
        payDb.queryForMap(
            "SELECT id,pay_no,xid,user_branch_id,operation_key,ledger_key FROM pay_tcc_dispatch"
                + " WHERE pay_no=?",
            request.payNo),
        "receive",
        userDb.queryForMap(
            "SELECT id,pay_no,xid,branch_id,operation_key,ledger_key FROM user_tcc_receive"
                + " WHERE pay_no=?",
            request.payNo),
        "attempt",
        userDb.queryForMap(
            "SELECT id,pay_no,mode,xid,branch_id,operation_key,ledger_key FROM"
                + " balance_debit_attempt WHERE pay_no=?",
            request.payNo),
        "tcc",
        userDb.queryForMap(
            "SELECT id,pay_no,xid,branch_id,operation_key FROM balance_tcc_debit WHERE"
                + " pay_no=?",
            request.payNo));
  }

  /** 新 Legacy 经真实 HTTP 创建、完成和重放，数据库模式及资金归属必须同时成立。 */
  private void verifyNewLegacyPayment(boolean refund) throws Exception {
    String legacyOrder = month + UUID.randomUUID();
    long legacySku = System.nanoTime();
    orderDb.update(
        "INSERT INTO mall_order_"
            + month
            + "(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
            + " VALUES(?,?,7,'PENDING_PAYMENT',10,10,'{}',DATE_ADD(NOW(3),INTERVAL 1"
            + " HOUR),NOW(3),NOW(3))",
        legacySku,
        legacyOrder);
    orderDb.update(
        "INSERT INTO mall_order_item_"
            + month
            + "(id,order_id,order_no,product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount,created_at)"
            + " VALUES(?,?,?,1,?,'legacy rollback fixture','{}',10,1,10,NOW(3))",
        legacySku,
        legacySku,
        legacyOrder,
        legacySku);
    stockDb.update(
        "INSERT INTO"
            + " stock_sku(sku_id,product_id,total_quantity,available_quantity,reserved_quantity,sold_quantity,version,updated_at)"
            + " VALUES(?,1,10,10,0,0,0,NOW(3))",
        legacySku);
    HttpResponse<String> reserved =
        call(
            "stock",
            "/api/stock/reservations",
            Map.of(
                "orderNo",
                legacyOrder,
                "items",
                List.of(Map.of("skuId", legacySku, "quantity", 1)),
                "scene",
                "NORMAL"));
    assertEquals(200, reserved.statusCode(), reserved.body());
    HttpResponse<String> created =
        protocolPost("/api/payments", Map.of("orderNo", legacyOrder, "payAmount", "10.00"));
    assertEquals(200, created.statusCode(), created.body());
    String payNo = JSON.readTree(created.body()).path("data").path("payNo").asText();
    assertFalse(payNo.isBlank());
    await(
        () -> {
          try {
            HttpResponse<String> response =
                protocolPost("/api/payments/" + payNo + "/mock-success", Map.of());
            assertTrue(Set.of(200, 202).contains(response.statusCode()), response.body());
            return response.statusCode() == 200;
          } catch (Exception failure) {
            throw new IllegalStateException(failure);
          }
        },
        45);
    assertEquals(
        200, protocolPost("/api/payments/" + payNo + "/mock-success", Map.of()).statusCode());
    assertEquals(
        "LEGACY",
        payDb.queryForObject(
            "SELECT debit_mode FROM pay_record WHERE pay_no=?", String.class, payNo));
    assertEquals(
        "SUCCESS",
        payDb.queryForObject("SELECT status FROM pay_record WHERE pay_no=?", String.class, payNo));
    assertEquals(
        "LEGACY",
        payDb.queryForObject(
            "SELECT debit_mode FROM pay_debit_intent WHERE pay_no=?", String.class, payNo));
    assertEquals(
        "LEGACY",
        userDb.queryForObject(
            "SELECT mode FROM balance_debit_attempt WHERE pay_no=?", String.class, payNo));
    for (String table : List.of("pay_tcc_execution", "pay_tcc_dispatch", "pay_tcc_receipt"))
      assertEquals(
          0,
          payDb.queryForObject(
              "SELECT COUNT(*) FROM " + table + " WHERE pay_no=?", Integer.class, payNo));
    for (String table : List.of("user_tcc_receive", "balance_tcc_debit"))
      assertEquals(
          0,
          userDb.queryForObject(
              "SELECT COUNT(*) FROM " + table + " WHERE pay_no=?", Integer.class, payNo));
    assertOriginalLedger(payNo, legacyOrder, false);
    assertOrderAndStock(legacyOrder, legacySku, false);
    assertFunds(refund ? "90.00" : "80.00", 2, refund ? 1 : 0);
    PayTccStageEvidence.capture(
        artifacts,
        batch,
        "NEW_LEGACY_PAID",
        payNo,
        legacyOrder,
        7,
        legacySku,
        month,
        Map.of("pay", payDb, "user", userDb, "order", orderDb, "stock", stockDb),
        protocolProcesses,
        Map.of(
            "scenarioId",
            protocolProcesses.get("scenarioId"),
            "observedOutcome",
            "LEGACY_SUCCESS",
            "persistentMode",
            "LEGACY",
            "noTccRows",
            true,
            "successReplay",
            true));
  }

  /** 每条资金效果按支付号、原操作键、原金额和订单逐一归属。 */
  private void assertOriginalLedger(String payNo, String orderNo, boolean refunded) {
    List<Map<String, Object>> rows =
        userDb.queryForList(
            "SELECT * FROM balance_ledger WHERE pay_no=? ORDER BY operation_type", payNo);
    assertEquals(refunded ? 2 : 1, rows.size());
    for (Map<String, Object> row : rows) {
      boolean isRefund = "REFUND".equals(row.get("operation_type"));
      assertEquals(isRefund ? "refund:" + payNo : payNo, row.get("operation_key"));
      assertEquals(isRefund ? payNo : null, row.get("original_operation_key"));
      assertEquals(orderNo, row.get("order_no"));
      assertEquals(7L, ((Number) row.get("user_id")).longValue());
      assertEquals(new BigDecimal("10.00"), row.get("amount"));
      assertEquals("APPLIED", row.get("state"));
    }
  }

  /** 验证原订单动作、预留明细、SKU和数量，避免用总库存掩盖错单。 */
  private void assertOrderAndStock(String orderNo, long expectedSku, boolean cancelled)
      throws Exception {
    assertEquals(
        cancelled ? "CANCELLED" : "PAID",
        orderDb.queryForObject(
            "SELECT status FROM mall_order_" + month + " WHERE order_no=?", String.class, orderNo));
    Map<String, Object> action =
        orderDb.queryForMap(
            "SELECT action_type,operation_key,status FROM order_stock_action WHERE order_no=?",
            orderNo);
    assertEquals("SUCCEEDED", action.get("status"));
    assertEquals(cancelled ? "ROLLBACK" : "CONFIRM", action.get("action_type"));
    assertEquals(orderNo + (cancelled ? ":ROLLBACK" : ":CONFIRM"), action.get("operation_key"));
    assertEquals(
        expectedSku,
        orderDb.queryForObject(
            "SELECT sku_id FROM mall_order_item_" + month + " WHERE order_no=?",
            Long.class,
            orderNo));
    assertEquals(
        1,
        orderDb.queryForObject(
            "SELECT quantity FROM mall_order_item_" + month + " WHERE order_no=?",
            Integer.class,
            orderNo));
    assertEquals(
        cancelled ? "ROLLED_BACK" : "CONFIRMED",
        stockDb.queryForObject(
            "SELECT status FROM stock_reservation_request WHERE order_no=?",
            String.class,
            orderNo));
    var items =
        JSON.readTree(
            stockDb.queryForObject(
                "SELECT items_snapshot FROM stock_reservation_request WHERE order_no=?",
                String.class,
                orderNo));
    assertEquals(1, items.size());
    assertEquals(expectedSku, items.get(0).path("skuId").asLong());
    assertEquals(1, items.get(0).path("quantity").asInt());
    List<Map<String, Object>> flows =
        stockDb.queryForList(
            "SELECT sku_id,flow_type,quantity,idempotency_key FROM stock_flow WHERE order_no=?"
                + " ORDER BY flow_type",
            orderNo);
    assertEquals(2, flows.size());
    assertEquals(
        Set.of("RESERVE", cancelled ? "ROLLBACK" : "CONFIRM"),
        flows.stream()
            .map(row -> row.get("flow_type"))
            .collect(java.util.stream.Collectors.toSet()));
    for (Map<String, Object> flow : flows) {
      assertEquals(expectedSku, ((Number) flow.get("sku_id")).longValue());
      assertEquals(1, ((Number) flow.get("quantity")).intValue());
      assertEquals(
          orderNo + ":" + flow.get("flow_type") + ":" + expectedSku, flow.get("idempotency_key"));
    }
    Map<String, Object> stock =
        stockDb.queryForMap(
            "SELECT available_quantity,reserved_quantity,sold_quantity FROM stock_sku WHERE"
                + " sku_id=?",
            expectedSku);
    assertEquals(cancelled ? 10 : 9, ((Number) stock.get("available_quantity")).intValue());
    assertEquals(0, ((Number) stock.get("reserved_quantity")).intValue());
    assertEquals(cancelled ? 0 : 1, ((Number) stock.get("sold_quantity")).intValue());
  }

  /** 阶段包只读取隔离事实；总包缺TC/MQ逐事件证据时仍显式标为不完整。 */
  private Path stage(String phase, Map<String, Object> assertions) throws Exception {
    Map<String, Object> labelled = new LinkedHashMap<>(assertions);
    labelled.put("scenarioId", protocolProcesses.get("scenarioId"));
    String phaseRole =
        switch (phase) {
          case "created" -> "BEFORE_FAULT";
          case "durable-fault" -> "FAULT_PERSISTED";
          case "old-exited" -> "OLD_PAY_EXITED";
          case "new-pay-started" -> "NEW_PAY_STARTED";
          case "original-recovered" -> "ORIGINAL_TCC_OBSERVED";
          case "before-cleanup" -> "PRE_CLEANUP";
          case "failure-observed" -> "FAILURE_OBSERVED";
          default -> phase;
        };
    return PayTccStageEvidence.capture(
        artifacts,
        batch,
        phaseRole,
        request.payNo,
        request.orderNo,
        7,
        skuId,
        month,
        Map.of("pay", payDb, "user", userDb, "order", orderDb, "stock", stockDb),
        protocolProcesses,
        labelled);
  }

  /** 稳定终态采集前后覆盖隔离四库全部表，证明collector没有写入副作用。 */
  private Map<String, Object> ownedChecksums() {
    Map<String, Object> result = new LinkedHashMap<>();
    for (var database :
        Map.of("pay", payDb, "user", userDb, "order", orderDb, "stock", stockDb).entrySet()) {
      List<String> tables = database.getValue().queryForList("SHOW TABLES", String.class);
      for (String table : tables)
        if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法隔离表名");
      result.put(
          database.getKey(),
          database.getValue().queryForList("CHECKSUM TABLE " + String.join(",", tables)));
    }
    return result;
  }

  /** 同一稳定RM身份的两个自有Pay进程；替代进程使用本轮标准Boot jar。 */
  private void startProtocolPay(boolean replacement, String mode, String fault) throws Exception {
    protocolPort = availablePort();
    List<String> options =
        new ArrayList<>(
            List.of(
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Xmx256m",
                "-Duser.timezone=Asia/Shanghai",
                "-Dfile.encoding=UTF-8"));
    Path jar = Path.of("target", "cloud-mall-pay-0.1.0-SNAPSHOT.jar").toAbsolutePath();
    if (replacement) options.addAll(List.of("-jar", jar.toString()));
    else {
      protocolControlPort = availablePort();
      protocolControlKey = UUID.randomUUID().toString();
      options.addAll(
          List.of(
              "-cp",
              System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
              PayCoordinatorFaultProcess.class.getName()));
    }
    List<String> arguments = new ArrayList<>(List.of(payArguments(false)));
    arguments.removeIf(
        argument ->
            argument.startsWith("--spring.main.web-application-type=")
                || argument.startsWith("--seata.application-id=")
                || argument.startsWith("--cloudmall.pay.tcc-delivery.interval-ms="));
    arguments.addAll(
        List.of(
            "--spring.main.web-application-type=servlet",
            "--server.address=127.0.0.1",
            "--server.port=" + protocolPort,
            "--spring.rabbitmq.virtual-host=" + batch,
            "--seata.application-id=" + batch + "-protocol-pay",
            "--cloudmall.pay.tcc-entry.enabled=true",
            "--cloudmall.pay.tcc-recovery.enabled=true",
            "--cloudmall.pay.tcc-recovery.interval-ms=" + (replacement ? 200 : 86400000),
            "--cloudmall.pay.tcc-delivery.interval-ms=" + (replacement ? 200 : 86400000)));
    if (mode != null) arguments.add("--cloudmall.pay.debit-mode=" + mode);
    if (!replacement)
      arguments.addAll(
          List.of(
              "--control-port=" + protocolControlPort,
              "--control-key=" + protocolControlKey,
              "--fault=" + fault));
    options.addAll(arguments);
    String generation = replacement ? "legacy-" + request.payNo : "tcc-" + request.payNo;
    Path argFile = artifacts.resolve(generation + ".args");
    Files.writeString(
        argFile,
        options.stream()
            .map(value -> "\"" + value.replace("\\", "/").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
    Process process =
        new ProcessBuilder(javaExecutable(), "@" + argFile)
            .redirectErrorStream(true)
            .redirectOutput(artifacts.resolve(generation + ".log").toFile())
            .start();
    if (replacement) restartedPay = process;
    else originalPay = process;
    protocolProcesses.put(
        replacement ? "new" : "old",
        Map.of(
            "pid",
            process.pid(),
            "startedAt",
            process.info().startInstant().orElseThrow().toString(),
            "modeArgument",
            mode == null ? "OMITTED_DEFAULT" : mode,
            "appId",
            batch + "-protocol-pay",
            "artifact",
            replacement ? jar.toString() : PayCoordinatorFaultProcess.class.getName(),
            "log",
            artifacts.resolve(generation + ".log").toString(),
            "jarSha256",
            java.util.HexFormat.of()
                .formatHex(
                    java.security.MessageDigest.getInstance("SHA-256")
                        .digest(Files.readAllBytes(jar)))));
    await(
        () -> {
          if (!process.isAlive())
            throw new AssertionError("Pay child exited: " + artifacts.resolve(generation + ".log"));
          try {
            return HTTP.send(
                        HttpRequest.newBuilder(
                                URI.create("http://127.0.0.1:" + protocolPort + "/actuator/health"))
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
    if (!replacement)
      await(
          () -> {
            try {
              return JSON.readTree(protocolControl("/state", null).body())
                  .path("ready")
                  .asBoolean();
            } catch (Exception unavailable) {
              return false;
            }
          },
          10);
  }

  /** 仅向自有Pay公开端口发正常业务请求，创建重放键按订单稳定。 */
  private HttpResponse<String> protocolPost(String path, Object body) throws Exception {
    return HTTP.send(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + protocolPort + path))
            .timeout(Duration.ofSeconds(15))
            .header("X-User-Id", "7")
            .header(
                "Idempotency-Key",
                "protocol-"
                    + (body instanceof Map<?, ?> map
                        ? map.getOrDefault("orderNo", null)
                        : request.orderNo))
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  /** 测试控制只存在src/test辅助进程，不能由生产Jar暴露。 */
  private HttpResponse<String> protocolControl(String path, Object body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + protocolControlPort + path))
            .timeout(Duration.ofSeconds(15))
            .header("X-Control-Key", protocolControlKey);
    if (body == null) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  /** 仅持有本测试创建的Process句柄；退出不能确认时保留全部数据现场。 */
  private static void stopProtocolProcess(Process process) throws Exception {
    if (process == null || !process.isAlive()) return;
    process.destroyForcibly();
    if (!process.waitFor(15, TimeUnit.SECONDS)) {
      preserve = true;
      throw new AssertionError("自有Pay进程退出未确认: " + process.pid());
    }
  }

  private void cancelOrder() throws Exception {
    HttpResponse<String> response =
        call("order", "/api/orders/" + request.orderNo + "/cancel", Map.of());
    assertEquals(200, response.statusCode(), response.body());
  }

  private void awaitStockAction() throws Exception {
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
        40);
  }

  /** 夹具明确置为过去，避开宿主机与容器毫秒时钟差。 */
  private void forceDue() {
    payDb.update(
        "UPDATE pay_tcc_delivery SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
            + " pay_no=?",
        request.payNo);
  }

  private void completeDelivery(String expected) throws Exception {
    await(
        () -> {
          forceDue();
          PayTccDeliveryVO result = delivery.deliver(request.payNo);
          return result != null && expected.equals(result.state);
        },
        30);
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

  private void blockStock(String target) {
    stockDb.execute(
        "CREATE TRIGGER block_settlement BEFORE UPDATE ON stock_reservation_request FOR EACH ROW"
            + " BEGIN IF NEW.status='"
            + target
            + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='test stock settlement unavailable';"
            + " END IF; END");
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

  private static String[] payArguments(boolean replacement) {
    return new String[] {
      "--spring.main.web-application-type=none",
      "--spring.datasource.url=" + payUrl,
      "--spring.cloud.nacos.discovery.group=" + batch,
      "--spring.cloud.nacos.discovery.register-enabled=false",
      "--spring.cloud.loadbalancer.cache.enabled=false",
      "--cloudmall.pay.recovery-interval-ms=86400000",
      "--seata.enabled=true",
      "--seata.application-id=" + batch + (replacement ? "-replacement" : "-pay"),
      "--seata.enable-auto-data-source-proxy=false",
      "--cloudmall.pay.tcc-bridge.enabled=true",
      "--cloudmall.pay.tcc-receipt.enabled=true",
      "--cloudmall.pay.tcc-coordinator.enabled=true",
      "--cloudmall.pay.tcc-coordinator.timeout-ms=120000",
      "--cloudmall.pay.tcc-delivery.enabled=true",
      "--cloudmall.pay.tcc-delivery.interval-ms=200"
    };
  }

  private void startReplacementPay() throws Exception {
    List<String> command =
        new ArrayList<>(
            List.of(
                javaExecutable(),
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Xmx256m",
                "-jar",
                Path.of("target", "cloud-mall-pay-0.1.0-SNAPSHOT.jar")
                    .toAbsolutePath()
                    .toString()));
    command.addAll(List.of(payArguments(true)));
    restartedPay =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(artifacts.resolve("pay-replacement.log").toFile())
            .start();
  }

  private static void startService(String service) throws Exception {
    startService(service, List.of());
  }

  /** 只为明确自有Order测试进程附加外置src/test主类，标准Boot jar保持不含测试控制。 */
  private static void startService(String service, List<String> observerArguments)
      throws Exception {
    startService(service, observerArguments, "com.cloudmall.order.OrderTimeoutFaultProcess");
  }

  /** 创建桥接只切换这个自有启动 main；旧 MQ helper 仍沿原双参数入口。 */
  private static void startService(
      String service, List<String> observerArguments, String helperMain) throws Exception {
    if (!Set.of(
            "com.cloudmall.order.OrderTimeoutFaultProcess",
            "com.cloudmall.order.OrderCreationFaultProcess")
        .contains(helperMain)) throw new IllegalArgumentException("OWNED_ORDER_HELPER_NOT_ALLOWED");
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
    if (!observerArguments.isEmpty()) {
      if (!"order".equals(service)) throw new IllegalArgumentException("观察器只允许Order fixture");
      int launchIndex = command.indexOf("-jar");
      String jarPath = command.remove(launchIndex + 1);
      command.remove(launchIndex);
      command.addAll(
          launchIndex,
          List.of(
              "-Dloader.main=" + helperMain,
              "-Dloader.path="
                  + Path.of("..", "cloud-mall-order", "target", "test-classes").toAbsolutePath(),
              "-cp",
              jarPath,
              "org.springframework.boot.loader.PropertiesLauncher"));
      command.addAll(observerArguments);
    }
    if (service.equals("user"))
      command.addAll(
          List.of(
              "--seata.enabled=true",
              "--seata.application-id=" + batch + "-user",
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
            .redirectOutput(
                artifacts
                    .resolve(
                        observerArguments.isEmpty()
                            ? service + ".log"
                            : service + "-observer-" + System.nanoTime() + ".log")
                    .toFile())
            .start();
    children.put(service, child);
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

  /** 原首次空库维护必须真实完成后再 seed，不排除任何 checksum 表。 */
  private static void confirmEmptyStockMaintenance() throws Exception {
    await(
        () ->
            stockDb.queryForObject(
                    "SELECT COUNT(*) FROM stock_consistency_audit_state WHERE"
                        + " state_key='NORMAL_STOCK' AND last_completed_at IS NOT NULL AND"
                        + " cursor_sku_id=0 AND last_batch_count=0 AND last_error IS NULL",
                    Integer.class)
                == 1,
        10);
    assertEquals(0, stockDb.queryForObject("SELECT COUNT(*) FROM stock_sku", Integer.class));
    assertEquals(
        0, stockDb.queryForObject("SELECT COUNT(*) FROM stock_cache_repair", Integer.class));
    Files.writeString(
        artifacts.resolve("stock-maintenance-startup.json"),
        JSON.writeValueAsString(
            Map.of(
                "runId",
                batch,
                "privateParameters",
                Map.of(
                    "cloudmall.stock.cache-repair-interval-ms",
                    86400000,
                    "cloudmall.stock.consistency-interval-ms",
                    86400000),
                "normalStockAudit",
                stockDb.queryForMap(
                    "SELECT cursor_sku_id,last_batch_count,last_completed_at,last_error FROM"
                        + " stock_consistency_audit_state WHERE state_key='NORMAL_STOCK'"),
                "stockSkuRows",
                0,
                "cacheRepairRows",
                0)),
        StandardOpenOption.CREATE_NEW);
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
