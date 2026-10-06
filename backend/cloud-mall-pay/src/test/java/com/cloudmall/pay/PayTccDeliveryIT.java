package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccDeliveryVO;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccDeliveryService;
import com.cloudmall.pay.service.impl.PayTccDeliveryScheduler;
import com.cloudmall.pay.service.impl.PayTccDeliveryStore;
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
import org.mockito.AdditionalAnswers;
import org.mockito.Mockito;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

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
    application =
        new SpringApplicationBuilder(PayApplication.class)
            .web(WebApplicationType.NONE)
            .run(payArguments(false));
    coordinator = application.getBean(PayTccCoordinatorService.class);
    delivery = application.getBean(PayTccDeliveryService.class);
    store = application.getBean(PayTccDeliveryStore.class);
    parentRunning =
        (AtomicBoolean)
            ReflectionTestUtils.getField(
                application.getBean(PayTccDeliveryScheduler.class), "running");
  }

  @BeforeEach
  void seed() throws Exception {
    Assumptions.assumeFalse(preserve);
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
    if (request == null || preserve) return;
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
      entry.getValue().waitFor(15, TimeUnit.SECONDS);
      naming.deregisterInstance(
          "cloud-mall-" + entry.getKey(), batch, "127.0.0.1", proxies.get(entry.getKey()).port());
      proxies.get(entry.getKey()).close();
    }
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
    commitFunds();
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
