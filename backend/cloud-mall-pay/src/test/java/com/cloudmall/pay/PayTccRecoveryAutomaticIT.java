package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.nacos.api.NacosFactory;
import com.alibaba.nacos.api.naming.NamingService;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.service.PayTccBridgeService;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.PayTccReceiptService;
import com.cloudmall.pay.service.PayTccTransactionClient;
import com.cloudmall.pay.service.impl.PayTccCoordinatorServiceImpl;
import com.cloudmall.pay.service.impl.PayTccExecutionStore;
import com.cloudmall.pay.service.impl.PayTccRecoveryScheduler;
import com.cloudmall.pay.service.impl.receipt.PayTccExecutionEvidence;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import io.seata.core.model.GlobalStatus;
import io.seata.tm.TransactionManagerHolder;
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

/** 显式验收：真实 Pay Feign/Nacos、两个独立 User JVM、真实 MySQL 与 TC。 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PayTccRecoveryAutomaticIT {
  /** 原库只读连接以及随机隔离库连接。 */
  private static JdbcTemplate source, userDb, payDb;

  /** 此次验收唯一的数据库/发现组前缀。 */
  private static String batch, userDatabase, payDatabase, userUrl;

  /** 保留独立 JVM 日志和验收事实。 */
  private static Path artifacts;

  /** 实际 Pay 应用及其事务桥。 */
  private static ConfigurableApplicationContext application;

  /** 原一次性 User 桥，用于真实 Feign 调用。 */
  private static PayTccBridgeService bridge;

  /** 唯一分支认领入口及官方 Fence 资源。 */
  private static PayTccReceiptService receipts;

  /** 与实际 Feign 相同 Nacos 组的管理客户端。 */
  private static NamingService naming;

  /** 只管理本验收启动的两个子进程。 */
  private static final Process[] users = new Process[2];

  /** User 实际端口和 Nacos 对外公布的代理端口。 */
  private static final int[] userPorts = new int[2];

  /** 各 User 实例前的响应故障代理。 */
  private static final TccResponseProxy[] proxies = new TccResponseProxy[2];

  /** 原库校验和，证明无业务数据变更。 */
  private static List<Map<String, Object>> checksums;

  /** 未决事实必须保留，不允许删除测试库。 */
  private static boolean preserve;

  /** 测试控制客户端，不参与 Pay Feign 调用。 */
  private static final HttpClient HTTP =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  /** 请求正文和证据序列化。 */
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /** 独立提交的原 execution 事实。 */
  private static PayTccExecutionStore executions;

  /** 真实 Pay receipt/Fence 证据查询。 */
  private static PayTccExecutionEvidence evidence;

  /** 连接真实 TC 的官方客户端。 */
  private static PayTccTransactionClient official;

  /** 子 Pay JVM 复用的隔离库 JDBC 地址。 */
  private static String payUrl;

  /** 测试掌握的原 XID，仅供终态核验和清理。 */
  private static final Set<String> startedXids = new HashSet<>();

  /** 当前用例被测生产协调器。 */
  private PayTccCoordinatorService coordinator;

  /** 真实 SDK 边界的计数与响应故障注入。 */
  private ProbeTransactions probe;

  /** 仅本测试拥有并可终止的 Pay 子进程。 */
  private Process payChild;

  /** 子 Pay 回环测试控制端口。 */
  private int controlPort;

  /** 当前子进程独立控制凭证，不进入日志。 */
  private String controlKey;

  /** 当前 Pay JVM 的独立日志文件。 */
  private Path childLog;

  /** 当前测试的原支付完整载荷。 */
  private PayTccDispatchDTO request;

  @BeforeAll
  static void setup() throws Exception {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    if (sourceUrl == null || !sourceUrl.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?"))
      throw new IllegalStateException("显式 IT 需要 cloudmall.mysql.test.url 指向本地 cloudmall_user");
    source = jdbc(sourceUrl);
    checksums = originalChecksums();
    batch = "cm16b3_auto_" + UUID.randomUUID().toString().replace("-", "");
    userDatabase = batch + "_user";
    payDatabase = batch + "_pay";
    artifacts = Path.of("target", "cm16b3-automatic", batch).toAbsolutePath();
    Files.createDirectories(artifacts);
    source.execute(
        "CREATE DATABASE " + userDatabase + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    source.execute(
        "CREATE DATABASE " + payDatabase + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    userUrl = sourceUrl.replace("/cloudmall_user", "/" + userDatabase);
    payUrl = sourceUrl.replace("/cloudmall_user", "/" + payDatabase);
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
    properties.put("serverAddr", "127.0.0.1:18848");
    properties.put("username", "root");
    properties.put("password", "root");
    naming = NacosFactory.createNamingService(properties);
    for (int index = 0; index < 2; index++) {
      userPorts[index] = availablePort();
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
    application =
        new SpringApplicationBuilder(PayApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + payUrl,
                "--spring.cloud.nacos.discovery.group=" + batch,
                "--spring.cloud.nacos.discovery.register-enabled=false",
                "--spring.cloud.loadbalancer.cache.enabled=false",
                "--seata.enabled=true",
                "--seata.application-id=" + batch + "-pay",
                "--seata.enable-auto-data-source-proxy=false",
                "--cloudmall.pay.tcc-bridge.enabled=true",
                "--cloudmall.pay.tcc-receipt.enabled=true",
                "--cloudmall.pay.tcc-coordinator.enabled=true",
                "--cloudmall.pay.tcc-coordinator.timeout-ms=120000",
                "--cloudmall.pay.tcc-recovery.enabled=true",
                "--cloudmall.pay.tcc-recovery.interval-ms=200");
    bridge = application.getBean(PayTccBridgeService.class);
    receipts = application.getBean(PayTccReceiptService.class);
    executions = application.getBean(PayTccExecutionStore.class);
    evidence = application.getBean(PayTccExecutionEvidence.class);
    official = application.getBean(PayTccTransactionClient.class);
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
    payDb.update("DELETE FROM pay_tcc_execution");
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
    probe = new ProbeTransactions();
    coordinator = coordinator(bridge, probe);
  }

  @AfterEach
  void settle() throws Exception {
    try {
      RootContext.unbind();
      if (payChild != null && payChild.isAlive()) killPay();
      List<String> observedXids =
          payDb.queryForList(
              "SELECT xid FROM pay_tcc_execution WHERE pay_no=?", String.class, request.payNo);
      String xid = observedXids.isEmpty() ? null : observedXids.get(0);
      if (xid != null) startedXids.add(xid);
      for (String original : startedXids) {
        if (!terminalXid(original)) {
          String decision =
              payDb
                  .queryForList(
                      "SELECT decision FROM pay_tcc_execution WHERE xid=?", String.class, original)
                  .stream()
                  .findFirst()
                  .orElse("NONE");
          if ("COMMIT".equals(decision)) official.commit(original);
          else official.rollback(original);
        }
        await(() -> terminalXid(original), 45);
      }
      await(
          () ->
              userDb.queryForObject(
                      "SELECT COUNT(*) FROM mall_user WHERE frozen_balance<>0", Integer.class)
                  == 0,
          45);
      assertEquals(
          0,
          payDb.queryForObject(
              "SELECT COUNT(*) FROM pay_tcc_receipt WHERE state='TRYING'", Integer.class));
      Map<String, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("testOnlyKnownXids", new ArrayList<>(startedXids));
      for (String table :
          List.of("pay_tcc_execution", "pay_tcc_dispatch", "pay_tcc_receipt", "tcc_fence_log"))
        snapshot.put(table, payDb.queryForList("SELECT * FROM " + table));
      for (String table :
          List.of(
              "user_tcc_receive",
              "balance_debit_attempt",
              "balance_tcc_debit",
              "balance_ledger",
              "tcc_fence_log"))
        snapshot.put("user_" + table, userDb.queryForList("SELECT * FROM " + table));
      snapshot.put("funds", userDb.queryForList("SELECT balance,frozen_balance FROM mall_user"));
      Files.writeString(
          artifacts.resolve(request.payNo + ".json"), JSON.writeValueAsString(snapshot));
    } catch (Throwable failure) {
      preserve = true;
      throw failure;
    }
  }

  @AfterAll
  static void cleanup() throws Exception {
    for (String xid : startedXids) if (!terminalXid(xid)) preserve = true;
    if (application != null) application.close();
    for (int index = 0; index < 2; index++) {
      stopUser(index);
      if (proxies[index] != null) proxies[index].close();
    }
    if (naming != null) naming.shutDown();
    if (source != null) assertEquals(checksums, originalChecksums());
    if (!preserve && batch != null && batch.matches("cm16b3_auto_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE IF EXISTS " + userDatabase);
      source.execute("DROP DATABASE IF EXISTS " + payDatabase);
    }
    assertFalse(preserve, "保留未决真实事务与数据库: " + batch);
  }

  @Test
  @Order(1)
  void scheduledCommitUsesOriginalDecisionWithoutManualRecover() throws Exception {
    probe.dropCommitBefore.set(true);
    assertEquals("COMMIT_REQUESTED", coordinator.execute(request).state);
    assertTrue(
        executions
            .read(request.payNo)
            .nextRecoveryAt
            .isAfter(java.time.LocalDateTime.now().plusSeconds(15)));
    String xid = executions.read(request.payNo).xid;
    due(request.payNo);
    await(() -> finished(request.payNo, "COMMITTED"), 30);
    assertEquals(xid, executions.read(request.payNo).xid);
    assertEquals(1, executions.read(request.payNo).recoveryAttempts);
    assertEquals(1, probe.begins.get());
    assertEquals(1, posts());
    assertTwoBranches();
    assertFunds("90.00", "0.00", 1);
  }

  @Test
  @Order(2)
  void scheduledRollbackSettlesInsufficientFundsWithoutNewTry() throws Exception {
    request.amount = new BigDecimal("201.00");
    payDb.update("UPDATE pay_record SET amount=201");
    probe.dropRollbackBefore.set(true);
    assertEquals("ROLLBACK_REQUESTED", coordinator.execute(request).state);
    due(request.payNo);
    await(() -> finished(request.payNo, "ROLLEDBACK"), 30);
    assertExecution("ROLLEDBACK", "ROLLBACK");
    assertEquals(1, posts());
    assertTwoBranches();
    assertFunds("100.00", "0.00", 0);
  }

  @Test
  @Order(3)
  void decisionNoneIsObservedWithoutAutomaticDecision() throws Exception {
    PayTccBridgeService interrupted =
        new PayTccBridgeService() {
          public PayTccDispatchVO prepare(PayTccDispatchDTO value) {
            return bridge.prepare(value);
          }

          public PayTccDispatchVO recover(String payNo) {
            return bridge.recover(payNo);
          }

          public PayTccDispatchVO dispatch(PayTccDispatchDTO value) {
            bridge.dispatch(value);
            throw new AssertionError("stop before decision");
          }
        };
    assertThrows(AssertionError.class, () -> coordinator(interrupted, probe).execute(request));
    String xid = executions.read(request.payNo).xid;
    due(request.payNo);
    await(
        () ->
            executions.read(request.payNo).recoveryAttempts >= 1
                && executions.read(request.payNo).recoveryOwner == null,
        15);
    assertExecution("UNKNOWN", "NONE");
    assertFunds("90.00", "10.00", 0);
    assertEquals(1, posts());
    // 测试明确请求原 TC 取消，扫描器只核对随后到达的永久事实。
    official.rollback(xid);
    due(request.payNo);
    await(() -> finished(request.payNo, "ROLLEDBACK"), 30);
    assertExecution("ROLLEDBACK", "NONE");
    assertFunds("100.00", "0.00", 0);
  }

  @Test
  @Order(4)
  void unknownBeginDoesNotOccupyAutomaticRecoveryWindow() throws Exception {
    probe.dropBeginAfter.set(true);
    assertEquals("BEGIN_UNKNOWN", coordinator.execute(request).state);
    String unknownPay = request.payNo;
    PayTccDispatchDTO ready = new PayTccDispatchDTO();
    ready.payNo = "canary-" + UUID.randomUUID();
    ready.orderNo = "order-" + ready.payNo;
    ready.userId = 7L;
    ready.amount = new BigDecimal("10.00");
    payDb.update(
        "INSERT INTO"
            + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
            + " VALUES(2,?,?,7,10,'PENDING','TCC',NOW(3),NOW(3))",
        ready.payNo,
        ready.orderNo);
    probe.dropCommitBefore.set(true);
    assertEquals("COMMIT_REQUESTED", coordinator.execute(ready).state);
    due(unknownPay);
    due(ready.payNo);
    await(() -> finished(ready.payNo, "COMMITTED"), 30);
    assertEquals("BEGIN_UNKNOWN", executions.read(unknownPay).state);
    assertNull(executions.read(unknownPay).xid);
    assertEquals(0, executions.read(unknownPay).recoveryAttempts);
    assertEquals(2, probe.begins.get());
    assertEquals(1, posts());
    assertFunds("90.00", "0.00", 1);
  }

  @Test
  @Order(5)
  void restartedPayAutomaticallyFindsDurableCommitIntent() throws Exception {
    AtomicBoolean parentRunning =
        (AtomicBoolean)
            ReflectionTestUtils.getField(
                application.getBean(PayTccRecoveryScheduler.class), "running");
    await(() -> parentRunning.compareAndSet(false, true), 10);
    try {
      startPay("BEFORE_COMMIT", false);
      CompletableFuture<HttpResponse<String>> inflight = executeChild();
      await(() -> childState().path("barrier").asBoolean(), 30);
      String xid = executions.read(request.payNo).xid;
      startedXids.add(xid);
      assertExecution("COMMIT_REQUESTED", "COMMIT");
      assertFunds("90.00", "10.00", 0);
      killPay();
      inflight.handle((response, failure) -> null).get(10, TimeUnit.SECONDS);
      due(request.payNo);
      startPay("NONE", true);
      // 无 /recover 控制调用，只有新 JVM 的 @Scheduled 自动发现原记录。
      await(() -> finished(request.payNo, "COMMITTED"), 30);
      assertEquals(xid, executions.read(request.payNo).xid);
      assertEquals(1, posts());
      assertFunds("90.00", "0.00", 1);
      assertTrue(Files.readString(childLog).contains("TCC resource commit result : true"));
      assertTrue(executions.read(request.payNo).recoveryAttempts >= 1);
    } finally {
      parentRunning.set(false);
    }
  }

  /** 只调整独立夹具的调度时间，不变更任何业务/XID/决议字段。 */
  private void due(String payNo) {
    payDb.update(
        "UPDATE pay_tcc_execution SET next_recovery_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
            + " pay_no=?",
        payNo);
  }

  private boolean finished(String payNo, String state) {
    var row = executions.read(payNo);
    return state.equals(row.state) && row.recoveryOwner == null;
  }

  private PayTccCoordinatorService coordinator(
      PayTccBridgeService user, PayTccTransactionClient transactionClient) {
    return new PayTccCoordinatorServiceImpl(
        executions, receipts, user, evidence, transactionClient);
  }

  private void assertExecution(String state, String decision) {
    assertEquals(state, executions.read(request.payNo).state);
    assertEquals(decision, executions.read(request.payNo).decision);
    assertEquals(1, payDb.queryForObject("SELECT COUNT(*) FROM pay_tcc_execution", Integer.class));
  }

  private void assertTwoBranches() {
    String xid = executions.read(request.payNo).xid;
    assertEquals(xid, payDb.queryForObject("SELECT xid FROM pay_tcc_receipt", String.class));
    assertEquals(xid, userDb.queryForObject("SELECT xid FROM balance_debit_attempt", String.class));
    assertNotEquals(
        payDb.queryForObject("SELECT branch_id FROM pay_tcc_receipt", Long.class),
        userDb.queryForObject("SELECT branch_id FROM balance_debit_attempt", Long.class));
    assertEquals(1, payDb.queryForObject("SELECT COUNT(*) FROM tcc_fence_log", Integer.class));
    assertEquals(1, userDb.queryForObject("SELECT COUNT(*) FROM tcc_fence_log", Integer.class));
  }

  private static boolean terminalXid(String xid) {
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

  /** 所有网络故障都发生在真实 SDK 调用边界；计数器仅用于测试证据。 */
  private static final class ProbeTransactions implements PayTccTransactionClient {
    /** 真实 begin/决议 RPC 的调用次数。 */
    final AtomicInteger begins = new AtomicInteger(),
        commits = new AtomicInteger(),
        rollbacks = new AtomicInteger();

    /** 仅一次生效的请求/响应丢失开关。 */
    final AtomicBoolean dropBeginAfter = new AtomicBoolean(),
        dropCommitBefore = new AtomicBoolean(),
        dropCommitAfter = new AtomicBoolean(),
        dropRollbackBefore = new AtomicBoolean();

    /** 短超时验证 NONE 决议仅等待 TC 自行取消。 */
    boolean shortTimeout;

    /** 原 begin 响应暂停与继续的确定性屏障。 */
    CountDownLatch beginWaiting, releaseBegin;

    public String begin() throws Exception {
      begins.incrementAndGet();
      String xid;
      if (shortTimeout) {
        GlobalTransaction globalTransaction = GlobalTransactionContext.createNew();
        globalTransaction.begin(2500, "coordinator-timeout");
        xid = globalTransaction.getXid();
      } else xid = official.begin();
      startedXids.add(xid);
      if (beginWaiting != null) {
        beginWaiting.countDown();
        releaseBegin.await();
      }
      if (dropBeginAfter.compareAndSet(true, false))
        throw new java.io.IOException("begin response lost");
      return xid;
    }

    public void commit(String xid) throws Exception {
      commits.incrementAndGet();
      if (dropCommitBefore.compareAndSet(true, false))
        throw new java.io.IOException("commit request lost");
      official.commit(xid);
      if (dropCommitAfter.compareAndSet(true, false))
        throw new java.io.IOException("commit response lost");
    }

    public void rollback(String xid) throws Exception {
      rollbacks.incrementAndGet();
      if (dropRollbackBefore.compareAndSet(true, false))
        throw new java.io.IOException("rollback request lost");
      official.rollback(xid);
    }
  }

  /** 测试子 JVM 只注册自己的稳定 RM 身份和回环控制端口。 */
  private void startPay(String fault, boolean recoveryEnabled) throws Exception {
    controlPort = availablePort();
    controlKey = UUID.randomUUID().toString();
    String generation = Long.toString(System.nanoTime());
    childLog = artifacts.resolve("pay-" + generation + ".log");
    Path arguments = artifacts.resolve("pay-" + generation + ".args");
    List<String> options =
        List.of(
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-Xmx256m",
            "-Dfile.encoding=UTF-8",
            "-cp",
            System.getProperty("surefire.test.class.path", System.getProperty("java.class.path")),
            PayCoordinatorFaultProcess.class.getName(),
            "--spring.main.web-application-type=none",
            "--spring.datasource.url=" + payUrl,
            "--spring.cloud.nacos.discovery.group=" + batch,
            "--spring.cloud.nacos.discovery.register-enabled=false",
            "--spring.cloud.loadbalancer.cache.enabled=false",
            "--seata.application-id=" + batch + "-fault-pay",
            "--seata.enabled=true",
            "--seata.enable-auto-data-source-proxy=false",
            "--cloudmall.pay.tcc-bridge.enabled=true",
            "--cloudmall.pay.tcc-receipt.enabled=true",
            "--cloudmall.pay.tcc-coordinator.enabled=true",
            "--cloudmall.pay.tcc-coordinator.timeout-ms=120000",
            "--cloudmall.pay.tcc-recovery.enabled=" + recoveryEnabled,
            "--cloudmall.pay.tcc-recovery.interval-ms=200",
            "--control-port=" + controlPort,
            "--control-key=" + controlKey,
            "--fault=" + fault);
    Files.writeString(
        arguments,
        options.stream()
            .map(value -> "\"" + value.replace("\\", "/").replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
    payChild =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java.exe").toString(),
                "@" + arguments)
            .redirectErrorStream(true)
            .redirectOutput(childLog.toFile())
            .start();
    await(
        () -> {
          if (!payChild.isAlive()) throw new AssertionError("Pay child exited: " + childLog);
          return childState().path("ready").asBoolean();
        },
        90);
  }

  private com.fasterxml.jackson.databind.JsonNode childState() {
    try {
      return JSON.readTree(control("/state", null).body());
    } catch (Exception failure) {
      return JSON.createObjectNode();
    }
  }

  private HttpResponse<String> control(String path, Object body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + controlPort + path))
            .timeout(Duration.ofSeconds(10))
            .header("X-Control-Key", controlKey);
    if (body == null) builder.GET();
    else
      builder
          .header("Content-Type", "application/json")
          .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)));
    return HTTP.send(builder.build(), HttpResponse.BodyHandlers.ofString());
  }

  private CompletableFuture<HttpResponse<String>> executeChild() throws Exception {
    return HTTP.sendAsync(
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + controlPort + "/execute"))
            .timeout(Duration.ofSeconds(90))
            .header("X-Control-Key", controlKey)
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(request)))
            .build(),
        HttpResponse.BodyHandlers.ofString());
  }

  private void killPay() throws Exception {
    if (payChild != null && payChild.isAlive()) {
      payChild.destroyForcibly();
      assertTrue(payChild.waitFor(15, TimeUnit.SECONDS));
    }
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
    users[index] =
        new ProcessBuilder(command)
            .redirectErrorStream(true)
            .redirectOutput(
                artifacts.resolve("user-" + index + "-" + System.nanoTime() + ".log").toFile())
            .start();
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
      users[index].waitFor(15, TimeUnit.SECONDS);
    }
    if (naming != null && proxies[index] != null)
      naming.deregisterInstance("cloud-mall-user", batch, "127.0.0.1", proxies[index].port());
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

  private static JdbcTemplate jdbc(String url) {
    return new JdbcTemplate(new DriverManagerDataSource(url, "root", "root"));
  }

  private static List<Map<String, Object>> originalChecksums() {
    return source.queryForList(
        "CHECKSUM TABLE"
            + " cloudmall_user.mall_user,cloudmall_user.balance_ledger,cloudmall_user.balance_debit_attempt,cloudmall_user.balance_tcc_debit,cloudmall_pay.pay_record,cloudmall_pay.pay_debit_intent,cloudmall_pay.pay_callback_log");
  }

  /** 与其他真实IT一致，避开本机1024..15000出站临时端口段。 */
  private static int availablePort() throws Exception {
    for (int attempt = 0; attempt < 128; attempt++) {
      int candidate = ThreadLocalRandom.current().nextInt(22000, 42000);
      try (ServerSocket socket = new ServerSocket(candidate)) {
        return socket.getLocalPort();
      } catch (java.io.IOException unavailable) {
        // 尝试下一独立测试端口。
      }
    }
    throw new IllegalStateException("没有可用隔离测试端口");
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
