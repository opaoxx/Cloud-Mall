package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import com.cloudmall.pay.service.PayTccReceiptService;
import com.cloudmall.pay.service.impl.receipt.PayTccReceiptStore;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.common.Constants;
import io.seata.core.context.RootContext;
import io.seata.core.model.BranchType;
import io.seata.core.model.GlobalStatus;
import io.seata.rm.DefaultResourceManager;
import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.tm.api.GlobalTransaction;
import io.seata.tm.api.GlobalTransactionContext;
import java.math.BigDecimal;
import java.net.ServerSocket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.AopTestUtils;

/** 显式验收：真实TC统一清算Pay receipt与User余额，两个User JVM经Nacos/Feign派发。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PayReceiptTccIT {
  /** 建库前校验，供父应用、发现客户端和两个子JVM共用的隔离环境。 */
  private static PayUserTccBridgeEnvironment environment;

  /** 原库只读连接以及随机隔离库连接。 */
  private static JdbcTemplate source, userDb, payDb;

  /** 此次验收唯一的数据库/发现组前缀。 */
  private static String batch, userDatabase, payDatabase, userUrl;

  /** 保留独立 JVM 日志和验收事实。 */
  private static Path artifacts;

  /** 实际 Pay 应用及其事务桥。 */
  private static ConfigurableApplicationContext application;

  private static PayTccBridgeService bridge;

  /** 唯一分支认领入口及官方 Fence 资源。 */
  private static PayTccReceiptService receipts;

  private static PayTccReceiptLocalResource resource;
  private static PayTccReceiptLocalResource rawResource;
  private static PayTccReceiptStore store;

  /** 与实际 Feign 相同 Nacos 组的管理客户端。 */
  private static NamingService naming;

  /** 只管理本验收启动的两个子进程。 */
  private static final Process[] users = new Process[2];

  /** User 实际端口和 Nacos 对外公布的代理端口。 */
  private static final int[] userPorts = new int[2];

  private static final TccResponseProxy[] proxies = new TccResponseProxy[2];

  /** 所有真实全局事务，清理前逐一核对终态。 */
  private static final List<GlobalTransaction> transactions = new ArrayList<>();

  /** 原库校验和，证明无业务数据变更。 */
  private static List<Map<String, Object>> checksums;

  /** 未决事实必须保留，不允许删除测试库。 */
  private static boolean preserve;

  /** 测试控制客户端，不参与 Pay Feign 调用。 */
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  /** 请求正文和证据序列化。 */
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /** 当前测试唯一交易和派发载荷。 */
  private GlobalTransaction transaction;

  private PayTccDispatchDTO request;

  @BeforeAll
  static void setup() throws Exception {
    environment = PayUserTccBridgeEnvironment.read(System.getProperties());
    String sourceUrl = environment.mysqlUrl();
    source = jdbc(sourceUrl);
    checksums = originalChecksums();
    batch = "cm16b1_" + UUID.randomUUID().toString().replace("-", "");
    userDatabase = batch + "_user";
    payDatabase = batch + "_pay";
    artifacts = Path.of("target", "cm16b1-receipt", batch).toAbsolutePath();
    Files.createDirectories(artifacts);
    source.execute(
        "CREATE DATABASE " + userDatabase + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    source.execute(
        "CREATE DATABASE " + payDatabase + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    userUrl = sourceUrl.replace("/cloudmall_user", "/" + userDatabase);
    String payUrl = sourceUrl.replace("/cloudmall_user", "/" + payDatabase);
    userDb = jdbc(userUrl);
    payDb = jdbc(payUrl);
    for (String table :
        List.of(
            "mall_user",
            "user_address",
            "balance_ledger",
            "balance_debit_attempt",
            "balance_tcc_debit"))
      userDb.execute(
          (String)
              source.queryForMap("SHOW CREATE TABLE cloudmall_user." + table).get("Create Table"));
    for (String table : List.of("pay_record", "pay_debit_intent", "pay_callback_log"))
      payDb.execute(
          (String)
              source.queryForMap("SHOW CREATE TABLE cloudmall_pay." + table).get("Create Table"));
    Files.writeString(
        artifacts.resolve("fixture.json"),
        JSON.writeValueAsString(
            Map.of("group", batch, "userDatabase", userDatabase, "payDatabase", payDatabase)));
    Properties properties = new Properties();
    properties.put("serverAddr", environment.nacosAddress());
    properties.put("username", "root");
    properties.put("password", "root");
    naming = NacosFactory.createNamingService(properties);
    for (int index = 0; index < 2; index++) {
      try (ServerSocket socket = new ServerSocket(0)) {
        userPorts[index] = socket.getLocalPort();
      }
      proxies[index] = new TccResponseProxy(userPorts[index]);
      startUser(index);
    }
    await(
        () -> {
          try {
            return naming.selectInstances("cloud-mall-user", batch, true).size() == 2;
          } catch (Exception failure) {
            return false;
          }
        },
        90);
    List<String> payArguments = new ArrayList<>(environment.applicationArguments());
    payArguments.addAll(
        List.of(
            "--spring.datasource.url=" + payUrl,
            "--spring.cloud.nacos.discovery.group=" + batch,
            "--spring.cloud.nacos.discovery.register-enabled=false",
            "--spring.cloud.loadbalancer.cache.enabled=false",
            "--seata.enabled=true",
            "--seata.application-id=" + batch + "-pay",
            "--seata.enable-auto-data-source-proxy=false",
            "--cloudmall.pay.tcc-bridge.enabled=true",
            "--cloudmall.pay.tcc-receipt.enabled=true"));
    application =
        new SpringApplicationBuilder(PayApplication.class)
            .web(WebApplicationType.NONE)
            .run(payArguments.toArray(String[]::new));
    bridge = application.getBean(PayTccBridgeService.class);
    receipts = application.getBean(PayTccReceiptService.class);
    resource = application.getBean(PayTccReceiptLocalResource.class);
    rawResource = AopTestUtils.getUltimateTargetObject(resource);
    store = application.getBean(PayTccReceiptStore.class);
  }

  @BeforeEach
  void seed() throws Exception {
    Assumptions.assumeFalse(preserve, "上一事务未决，禁止覆盖现场");
    for (TccResponseProxy proxy : proxies) {
      proxy.clearFault();
      proxy.resetCounters();
    }
    for (String table :
        List.of(
            "tcc_fence_log",
            "user_tcc_receive",
            "balance_tcc_debit",
            "balance_debit_attempt",
            "balance_ledger",
            "mall_user")) userDb.update("DELETE FROM " + table);
    payDb.execute("DROP TRIGGER IF EXISTS receipt_failure");
    payDb.update("DELETE FROM tcc_fence_log");
    payDb.update("DELETE FROM pay_tcc_receipt");
    payDb.update("DELETE FROM pay_tcc_dispatch");
    payDb.update("DELETE FROM pay_record");
    userDb.update(
        "INSERT INTO mall_user(id,username,password_hash,role,balance,status,created_at,updated_at)"
            + " VALUES(7,'bridge-test','unused','USER',100,1,NOW(3),NOW(3))");
    request = new PayTccDispatchDTO();
    request.payNo = "bridge-" + UUID.randomUUID();
    request.userId = 7L;
    request.orderNo = "order-" + request.payNo;
    request.amount = new BigDecimal("10.00");
    payDb.update(
        "INSERT INTO"
            + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
            + " VALUES(1,?,?,7,?,'PENDING','TCC',NOW(3),NOW(3))",
        request.payNo,
        request.orderNo,
        request.amount);
    transaction = GlobalTransactionContext.createNew();
    transaction.begin(120000, "cm16b1-receipt");
    transactions.add(transaction);
  }

  @AfterEach
  void settle() throws Exception {
    try {
      payDb.execute("DROP TRIGGER IF EXISTS receipt_failure");
      if (transaction != null && transaction.getStatus() == GlobalStatus.Begin)
        transaction.rollback();
      await(
          () ->
              userDb.queryForObject(
                      "SELECT COUNT(*) FROM mall_user WHERE frozen_balance<>0", Integer.class)
                  == 0,
          45);
      if (transaction != null) await(() -> terminal(transaction), 45);
      assertEquals(
          0,
          payDb.queryForObject(
              "SELECT COUNT(*) FROM pay_tcc_receipt WHERE state='TRYING'", Integer.class));
      Files.writeString(
          artifacts.resolve(request.payNo + ".json"),
          JSON.writeValueAsString(
              Map.of(
                  "xid",
                  transaction.getXid(),
                  "receipt",
                  payDb.queryForList("SELECT * FROM pay_tcc_receipt"),
                  "payFence",
                  payDb.queryForList("SELECT * FROM tcc_fence_log"),
                  "dispatch",
                  payDb.queryForList("SELECT * FROM pay_tcc_dispatch"),
                  "receive",
                  userDb.queryForList("SELECT * FROM user_tcc_receive"),
                  "attempt",
                  userDb.queryForList("SELECT * FROM balance_debit_attempt"),
                  "funds",
                  userDb.queryForList("SELECT balance,frozen_balance FROM mall_user"),
                  "ledger",
                  userDb.queryForList("SELECT * FROM balance_ledger"),
                  "fence",
                  userDb.queryForList("SELECT * FROM tcc_fence_log"))));
    } catch (Throwable failure) {
      preserve = true;
      throw failure;
    } finally {
      RootContext.unbind();
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    // TC 状态必须在关闭 Pay 客户端之前核对，关闭后的 RPC 失败不是事务未决。
    for (GlobalTransaction global : transactions) if (!terminal(global)) preserve = true;
    if (application != null) application.close();
    for (int index = 0; index < 2; index++) {
      stopUser(index);
      if (proxies[index] != null) proxies[index].close();
    }
    if (naming != null) naming.shutDown();
    if (source != null) assertEquals(checksums, originalChecksums(), "原业务库不得被测试修改");
    if (!preserve && batch != null && batch.matches("cm16b1_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE IF EXISTS " + userDatabase);
      source.execute("DROP DATABASE IF EXISTS " + payDatabase);
    }
    assertFalse(preserve, "未决事务保留隔离库: " + batch);
  }

  @Test
  @Order(1)
  void bothResourcesCommitAndTerminalReplaySurvivesFenceCleanup() throws Exception {
    prepareBoth();
    BusinessActionContext context = payContext();
    transaction.commit();
    await(() -> "CONFIRMED".equals(receipts.read(request.payNo).state), 30);
    assertEquals("CONFIRMED", bridge.recover(request.payNo).state);
    assertPay("CONFIRMED", 2);
    assertFunds("90.00", "0.00", 1);
    assertBranch("2");
    assertTrue(rawResource.commit(context));
    assertFalse(rawResource.rollback(context));
    payDb.update("DELETE FROM tcc_fence_log");
    assertTrue(rawResource.commit(context));
    assertFalse(rawResource.rollback(context));
    assertEquals("PENDING", payDb.queryForObject("SELECT status FROM pay_record", String.class));
    assertEquals(1, posts());
  }

  @Test
  @Order(2)
  void bothResourcesCancelAndPrepareCannotResurrect() throws Exception {
    prepareBoth();
    BusinessActionContext context = payContext();
    transaction.rollback();
    await(() -> "CANCELLED".equals(receipts.read(request.payNo).state), 30);
    assertEquals("CANCELLED", bridge.recover(request.payNo).state);
    assertPay("CANCELLED", 3);
    assertFunds("100.00", "0.00", 0);
    assertBranch("3");
    payDb.update("DELETE FROM tcc_fence_log");
    assertTrue(rawResource.rollback(context));
    assertFalse(rawResource.commit(context));
    RootContext.bind(transaction.getXid());
    assertThrows(RuntimeException.class, () -> rawResource.prepare(context, request));
    assertEquals("CANCELLED", receipts.prepare(request).state);
    assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM tcc_fence_log", Integer.class));
    assertEquals(1, posts());
  }

  @Test
  @Order(3)
  void insufficientUserTryCancelsOriginalPayReceipt() throws Exception {
    request.amount = new BigDecimal("201.00");
    payDb.update("UPDATE pay_record SET amount=201");
    assertEquals("TRYING", receipts.prepare(request).state);
    assertEquals("TRY_UNKNOWN", bridge.dispatch(request).state);
    assertEquals("TRYING", receipts.read(request.payNo).state);
    transaction.rollback();
    await(() -> "CANCELLED".equals(receipts.read(request.payNo).state), 30);
    assertEquals("CANCELLED", bridge.recover(request.payNo).state);
    assertPay("CANCELLED", 3);
    assertFunds("100.00", "0.00", 0);
    assertBranch("4");
  }

  @Test
  @Order(4)
  void emptyCancelLeavesPermanentReceiptTombstone() throws Exception {
    assertTrue(store.claim(bridge.prepare(request)));
    Map<String, Object> action = Map.of("request", request, Constants.USE_TCC_FENCE, false);
    long branch =
        DefaultResourceManager.get()
            .branchRegister(
                BranchType.TCC,
                PayTccReceiptLocalResource.ACTION_NAME,
                null,
                transaction.getXid(),
                com.alibaba.fastjson.JSON.toJSONString(
                    Map.of(Constants.TCC_ACTION_CONTEXT, action)),
                null);
    BusinessActionContext context = context(branch);
    transaction.rollback();
    await(() -> "CANCELLED".equals(receipts.read(request.payNo).state), 30);
    assertPay("CANCELLED", 4);
    assertEquals(0, posts());
    assertFunds("100.00", "0.00", 0);
    payDb.update("DELETE FROM tcc_fence_log");
    assertTrue(rawResource.rollback(context));
    RootContext.bind(transaction.getXid());
    assertThrows(RuntimeException.class, () -> rawResource.prepare(context, request));
    assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM tcc_fence_log", Integer.class));
  }

  @Test
  @Order(5)
  void failedPayTryRollsBackFenceAndTcEmptyCancelStillSettlesClaim() throws Exception {
    failUpdatesTo("TRYING");
    assertThrows(RuntimeException.class, () -> receipts.prepare(request));
    assertEquals("CLAIMED", receipts.read(request.payNo).state);
    assertNull(receipts.read(request.payNo).branchId);
    assertEquals(0, payDb.queryForObject("SELECT COUNT(*) FROM tcc_fence_log", Integer.class));
    payDb.execute("DROP TRIGGER receipt_failure");
    transaction.rollback();
    await(() -> "CANCELLED".equals(receipts.read(request.payNo).state), 30);
    assertPay("CANCELLED", 4);
    assertEquals(0, posts());
    assertFunds("100.00", "0.00", 0);
  }

  @Test
  @Order(6)
  void failedConfirmDoesNotCommitFenceAheadOfReceipt() throws Exception {
    prepareBoth();
    failUpdatesTo("CONFIRMED");
    transaction.commit();
    assertPay("TRYING", 1);
    payDb.execute("DROP TRIGGER receipt_failure");
    await(() -> "CONFIRMED".equals(receipts.read(request.payNo).state), 45);
    await(() -> "CONFIRMED".equals(bridge.recover(request.payNo).state), 30);
    assertPay("CONFIRMED", 2);
    assertFunds("90.00", "0.00", 1);
    assertBranch("2");
  }

  @Test
  @Order(7)
  void failedCancelDoesNotCommitFenceAheadOfReceipt() throws Exception {
    prepareBoth();
    failUpdatesTo("CANCELLED");
    transaction.rollback();
    assertPay("TRYING", 1);
    payDb.execute("DROP TRIGGER receipt_failure");
    await(() -> "CANCELLED".equals(receipts.read(request.payNo).state), 45);
    await(() -> "CANCELLED".equals(bridge.recover(request.payNo).state), 30);
    assertPay("CANCELLED", 3);
    assertFunds("100.00", "0.00", 0);
    assertBranch("3");
  }

  @Test
  @Order(8)
  void concurrentReceiptPrepareRegistersOnlyOnePayBranch() throws Exception {
    String xid = transaction.getXid();
    ExecutorService pool = Executors.newFixedThreadPool(2);
    CountDownLatch gate = new CountDownLatch(1);
    try {
      List<Future<?>> results = new ArrayList<>();
      for (int index = 0; index < 2; index++)
        results.add(
            pool.submit(
                () -> {
                  try {
                    gate.await();
                    RootContext.bind(xid);
                    return receipts.prepare(request);
                  } finally {
                    RootContext.unbind();
                  }
                }));
      gate.countDown();
      for (Future<?> result : results) assertNotNull(result.get(20, TimeUnit.SECONDS));
      assertPay("TRYING", 1);
      assertEquals("TRYING", receipts.prepare(request).state);
      assertEquals(0, posts());
      assertEquals("TRY_ACCEPTED", bridge.dispatch(request).state);
      transaction.commit();
      await(() -> "CONFIRMED".equals(receipts.read(request.payNo).state), 30);
      assertPay("CONFIRMED", 2);
      assertEquals(1, posts());
      assertFunds("90.00", "0.00", 1);
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  @Order(9)
  void lostUserResponseDoesNotOverwritePayResourceOrRegisterAgain() throws Exception {
    assertEquals("TRYING", receipts.prepare(request).state);
    for (TccResponseProxy proxy : proxies) proxy.dropNextPostBody();
    assertEquals("TRY_UNKNOWN", bridge.dispatch(request).state);
    assertEquals("TRYING", receipts.prepare(request).state);
    assertEquals("TRY_ACCEPTED", bridge.recover(request.payNo).state);
    assertPay("TRYING", 1);
    assertEquals(1, posts());
    transaction.rollback();
    await(() -> "CANCELLED".equals(receipts.read(request.payNo).state), 30);
    assertEquals("CANCELLED", bridge.recover(request.payNo).state);
    assertPay("CANCELLED", 3);
    assertFunds("100.00", "0.00", 0);
    assertBranch("3");
  }

  @Test
  @Order(10)
  void conflictingPayloadAndXidCannotRebindReceipt() throws Exception {
    assertEquals("TRYING", receipts.prepare(request).state);
    BusinessActionContext context = payContext();
    request.amount = new BigDecimal("11.00");
    assertThrows(BizException.class, () -> receipts.prepare(request));
    assertFalse(rawResource.commit(context));
    request.amount = new BigDecimal("10.00");
    RootContext.bind("other-xid");
    assertThrows(BizException.class, () -> receipts.prepare(request));
    RootContext.bind(transaction.getXid());
    assertPay("TRYING", 1);
    assertEquals(0, posts());
    transaction.rollback();
    await(() -> "CANCELLED".equals(receipts.read(request.payNo).state), 30);
    assertPay("CANCELLED", 3);
    assertFunds("100.00", "0.00", 0);
  }

  @Test
  @Order(11)
  void rejectedSecondBranchCanCancelWithoutTouchingItsPermanentOwner() throws Exception {
    assertEquals("TRYING", receipts.prepare(request).state);
    long originalBranch = receipts.read(request.payNo).branchId;
    assertThrows(RuntimeException.class, () -> resource.prepare(null, request));
    assertEquals(originalBranch, receipts.read(request.payNo).branchId);
    assertEquals("TRYING", receipts.read(request.payNo).state);
    transaction.rollback();
    await(() -> "CANCELLED".equals(receipts.read(request.payNo).state), 30);
    assertEquals(1, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_receipt", Integer.class));
    assertEquals(2, payDb.queryForObject("SELECT COUNT(*) FROM tcc_fence_log", Integer.class));
    assertEquals(
        1,
        payDb.queryForObject("SELECT COUNT(*) FROM tcc_fence_log WHERE status=4", Integer.class));
    assertEquals(
        3,
        payDb.queryForObject(
            "SELECT status FROM tcc_fence_log WHERE branch_id=?", Integer.class, originalBranch));
    assertEquals(0, posts());
    assertFunds("100.00", "0.00", 0);
  }

  /** Pay 资源先受理，再由调用者显式派发 User，一份 XID 对应两个不同分支。 */
  private void prepareBoth() {
    assertEquals("TRYING", receipts.prepare(request).state);
    assertEquals(
        "PREPARED", payDb.queryForObject("SELECT state FROM pay_tcc_dispatch", String.class));
    assertEquals(0, posts());
    assertEquals("TRY_ACCEPTED", bridge.dispatch(request).state);
    assertPay("TRYING", 1);
    assertBranch("1");
    assertFunds("90.00", "10.00", 0);
    assertNotEquals(
        receipts.read(request.payNo).branchId,
        userDb.queryForObject("SELECT branch_id FROM balance_debit_attempt", Long.class));
  }

  /** 回调失败注入只修改随机测试库中的触发器。 */
  private void failUpdatesTo(String state) {
    assertTrue(Set.of("TRYING", "CONFIRMED", "CANCELLED").contains(state));
    payDb.execute(
        "CREATE TRIGGER receipt_failure BEFORE UPDATE ON pay_tcc_receipt FOR EACH ROW BEGIN IF"
            + " NEW.state='"
            + state
            + "' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='receipt acceptance failure'; END"
            + " IF; END");
  }

  /** 核验 receipt 和官方 Fence 在同一分支上共同推进。 */
  private void assertPay(String state, int fence) {
    assertEquals(1, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_receipt", Integer.class));
    assertEquals(state, receipts.read(request.payNo).state);
    assertEquals(transaction.getXid(), receipts.read(request.payNo).xid);
    assertEquals(1, payDb.queryForObject("SELECT COUNT(*) FROM tcc_fence_log", Integer.class));
    assertEquals(fence, payDb.queryForObject("SELECT status FROM tcc_fence_log", Integer.class));
    assertEquals(
        receipts.read(request.payNo).branchId,
        payDb.queryForObject("SELECT branch_id FROM tcc_fence_log", Long.class));
    assertEquals(
        request.amount,
        payDb.queryForObject("SELECT amount FROM pay_tcc_receipt", BigDecimal.class));
  }

  private BusinessActionContext payContext() {
    return context(receipts.read(request.payNo).branchId);
  }

  private BusinessActionContext context(long branch) {
    BusinessActionContext context =
        new BusinessActionContext(
            transaction.getXid(), Long.toString(branch), Map.of("request", request));
    context.setActionName(PayTccReceiptLocalResource.ACTION_NAME);
    return context;
  }

  /** 使用真正的启动包，每个 JVM 独立持有 RM 连接；实例共享稳定 application-id。 */
  private static void startUser(int index) throws Exception {
    Path jar =
        Path.of("..", "cloud-mall-user", "target", "cloud-mall-user-0.1.0-SNAPSHOT.jar")
            .toAbsolutePath();
    assertTrue(Files.isRegularFile(jar), "先 mvn package 构建 User 启动包");
    List<String> command =
        new ArrayList<>(
            List.of(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "--add-opens=java.base/java.lang=ALL-UNNAMED",
                "-Duser.timezone=Asia/Shanghai",
                "-Xmx256m",
                "-jar",
                jar.toString(),
                "--server.port=" + userPorts[index],
                "--spring.datasource.url=" + userUrl,
                "--spring.cloud.nacos.discovery.group=" + batch,
                "--spring.cloud.nacos.discovery.ip=127.0.0.1",
                "--spring.cloud.nacos.discovery.port=" + proxies[index].port(),
                "--seata.enabled=true",
                "--seata.application-id=" + batch + "-user",
                "--cloudmall.user.tcc-debit.enabled=true",
                "--cloudmall.user.tcc-resource.enabled=true",
                "--cloudmall.user.tcc-http.enabled=true"));
    command.addAll(environment.applicationArguments());
    users[index] =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(
                artifacts.resolve("user-" + index + "-" + System.nanoTime() + ".log").toFile())
            .start();
    Files.writeString(
        artifacts.resolve("user-start-" + users[index].pid() + ".json"),
        JSON.writeValueAsString(
            Map.of(
                "pid",
                users[index].pid(),
                "index",
                index,
                "batch",
                batch,
                "userPort",
                userPorts[index],
                "proxyPort",
                proxies[index].port(),
                "nacos",
                environment.nacosAddress(),
                "tc",
                environment.tcAddress(),
                "redisPort",
                environment.redisPort())));
    await(
        () -> {
          if (!users[index].isAlive()) throw new AssertionError("User JVM 退出，检查 " + artifacts);
          try {
            return HTTP.send(
                        HttpRequest.newBuilder(
                                URI.create(
                                    "http://127.0.0.1:" + userPorts[index] + "/actuator/health"))
                            .timeout(Duration.ofSeconds(2))
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
  }

  /** 只停止本测试创建的句柄，并注销对应隔离 Nacos 实例。 */
  private static void stopUser(int index) throws Exception {
    if (users[index] != null && users[index].isAlive()) {
      users[index].destroyForcibly();
      assertTrue(users[index].waitFor(15, TimeUnit.SECONDS), "自有User进程未退出，不允许清理数据库");
    }
    if (users[index] != null) {
      Files.writeString(
          artifacts.resolve("user-stop-" + users[index].pid() + ".json"),
          JSON.writeValueAsString(
              Map.of(
                  "pid",
                  users[index].pid(),
                  "alive",
                  users[index].isAlive(),
                  "exitCode",
                  users[index].exitValue())));
    }
    if (naming != null && proxies[index] != null)
      naming.deregisterInstance("cloud-mall-user", batch, "127.0.0.1", proxies[index].port());
  }

  /** 逐笔检查唯一真实分支与 TC Fence 决议。 */
  private void assertBranch(String fenceStatus) {
    assertEquals(
        1, userDb.queryForObject("SELECT COUNT(*) FROM balance_debit_attempt", Integer.class));
    assertEquals(1, userDb.queryForObject("SELECT COUNT(*) FROM tcc_fence_log", Integer.class));
    assertEquals(
        fenceStatus, userDb.queryForObject("SELECT status FROM tcc_fence_log", String.class));
    assertEquals(
        transaction.getXid(),
        userDb.queryForObject("SELECT xid FROM balance_debit_attempt", String.class));
    assertEquals(
        userDb.queryForObject("SELECT branch_id FROM balance_debit_attempt", Long.class),
        userDb.queryForObject("SELECT branch_id FROM tcc_fence_log", Long.class));
  }

  /** 冻结不是扣款成功，最终账本必须精确一次。 */
  private void assertFunds(String balance, String frozen, int ledgers) {
    assertEquals(balance, userDb.queryForObject("SELECT balance FROM mall_user", String.class));
    assertEquals(
        frozen, userDb.queryForObject("SELECT frozen_balance FROM mall_user", String.class));
    assertEquals(
        ledgers,
        userDb.queryForObject(
            "SELECT COUNT(*) FROM balance_ledger WHERE state='APPLIED'", Integer.class));
  }

  private static int posts() {
    return proxies[0].postCount() + proxies[1].postCount();
  }

  private static int gets() {
    return proxies[0].getCount() + proxies[1].getCount();
  }

  private static JdbcTemplate jdbc(String url) {
    return new JdbcTemplate(new DriverManagerDataSource(url, "root", "root"));
  }

  private static boolean terminal(GlobalTransaction global) {
    try {
      return Set.of(
              GlobalStatus.Committed,
              GlobalStatus.Rollbacked,
              GlobalStatus.TimeoutRollbacked,
              GlobalStatus.Finished)
          .contains(global.getStatus());
    } catch (Exception failure) {
      return false;
    }
  }

  private static List<Map<String, Object>> originalChecksums() {
    return source.queryForList(
        "CHECKSUM TABLE"
            + " cloudmall_user.mall_user,cloudmall_user.balance_ledger,cloudmall_user.balance_debit_attempt,cloudmall_user.balance_tcc_debit,cloudmall_pay.pay_record,cloudmall_pay.pay_debit_intent,cloudmall_pay.pay_callback_log");
  }

  private static void await(BooleanSupplier condition, int seconds) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    while (System.nanoTime() < deadline) {
      if (condition.getAsBoolean()) return;
      Thread.sleep(150);
    }
    fail("验收条件超时，证据: " + artifacts);
  }
}
