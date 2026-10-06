package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryVO;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.impl.PayTccDeliveryScheduler;
import com.fasterxml.jackson.databind.ObjectMapper;
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

  @BeforeAll
  static void setup() throws Exception {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_user 测试 URL");
    source = jdbc(sourceUrl);
    originalChecksums = checksums();
    batch = "cm16e_" + UUID.randomUUID().toString().replace("-", "");
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
    startService("user");
    startService("stock");
    startService("order");
    application =
        new SpringApplicationBuilder(PayApplication.class)
            .web(WebApplicationType.SERVLET)
            .run(payArguments());
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
      throw failure;
    } finally {
      parentRunning.set(false);
      cancelRunning.set(false);
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
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
    if (source != null) assertEquals(originalChecksums, checksums());
    if (!preserve && batch != null && batch.matches("cm16e_[0-9a-f]{32}")) {
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
              return post("/api/payments/" + request.payNo + "/mock-success", Map.of(), Map.of())
                      .status()
                  == 200;
            } catch (Exception failure) {
              throw new RuntimeException(failure);
            }
          },
          30);
      assertFunds("90.00", 1, 0);
    }
  }

  @Test
  @Order(3)
  void existingCommittedExecutionRejectsCancellationWithoutChangingDecision() throws Exception {
    createPublic();
    assertEquals(
        "COMMITTED",
        application
            .getBean(com.cloudmall.pay.service.PayTccCoordinatorService.class)
            .execute(request)
            .state);
    assertEquals(
        202, post("/api/payments/" + request.payNo + "/mock-fail", Map.of(), Map.of()).status());
    assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_cancel", Integer.class));
    assertEquals(
        "COMMIT", payDb.queryForObject("SELECT decision FROM pay_tcc_execution", String.class));
    assertEquals("PENDING_PAYMENT", orderState());
    assertFunds("90.00", 1, 0);
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
    HttpRequest.Builder request =
        HttpRequest.newBuilder(URI.create(gatewayUrl + path))
            .timeout(Duration.ofSeconds(20))
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
              "--cloudmall.stock.dependency-degrade.rule-group=" + batch));
    Process child =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(artifacts.resolve(service + ".log").toFile())
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
    return new JdbcTemplate(new DriverManagerDataSource(url, "root", "root"));
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
