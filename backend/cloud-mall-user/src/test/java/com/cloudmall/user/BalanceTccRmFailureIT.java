package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.user.domain.dto.BalanceTccBranchDTO;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.seata.core.context.RootContext;
import io.seata.core.model.BranchType;
import io.seata.core.model.GlobalStatus;
import io.seata.rm.DefaultResourceManager;
import io.seata.tm.api.GlobalTransaction;
import io.seata.tm.api.GlobalTransactionContext;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 15B：独立 RM JVM、真实 TC/MySQL、确定性提交 barrier 和隔离 TCP 断连验收。 */
class BalanceTccRmFailureIT {
  /** 只读原表结构的连接。 */
  private static JdbcTemplate source;

  /** 独立测试库事实查询。 */
  private static JdbcTemplate fixture;

  /** 只充当 TM、未注册资金 RM 的真实应用上下文。 */
  private static ConfigurableApplicationContext coordinator;

  /** 当前批次唯一、受白名单校验的数据库名。 */
  private static String database;

  /** RM 重启仍复用的 JDBC URL。 */
  private static String isolatedUrl;

  /** 本地开发数据库用户名。 */
  private static String username;

  /** 本地开发数据库密码。 */
  private static String password;

  /** 测试 TC 的实际地址，父 TM 直连。 */
  private static String tcAddress;

  /** 只有 RM 子进程经过此代理。 */
  private static RmTcLinkProxy proxy;

  /** 保存日志、JVM 参数、故障快照和恢复元数据的目录。 */
  private static Path artifacts;

  /** 未决时禁止清理数据库或覆盖下一组夹具。 */
  private static boolean preserve;

  /** JSON 控制消息与证据序列化。 */
  private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();

  /** 本批启动的子进程序号，用来保留每次启动日志。 */
  private static int launchNumber;

  /** 同批全部真实事务，用于最终确认 TC 已结束。 */
  private static final List<GlobalTransaction> ALL_TRANSACTIONS = new ArrayList<>();

  /** 所有父进程原业务表 checksum。 */
  private static List<Map<String, Object>> originalChecksums;

  /** 当前用例所属 RM 进程句柄，仅此进程允许被终止。 */
  private Process child;

  /** 当前 RM 启动日志，重启后用于证明原分支确实收到 TC 重试。 */
  private Path childLog;

  /** 当前子进程的 loopback 控制端口。 */
  private int controlPort;

  /** 每次重启独立的控制鉴权键。 */
  private String controlKey;

  /** 当前测试交易。 */
  private GlobalTransaction transaction;

  /** TC 决议 RPC，回调暂停时不阻塞测试控制线程。 */
  private Future<?> decision;

  /** 决议方向，清理时不允许把已提交改成回滚。 */
  private boolean committing;

  /** 测试用唯一支付号。 */
  private String payNo;

  /** 父进程异步发送已选定的全局决议。 */
  private ExecutorService decisions;

  /** 测试控制通道客户端。 */
  private final HttpClient http =
      HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(3)).build();

  @BeforeAll
  static void setupBatch() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?"))
      throw new IllegalStateException("显式 IT 需要 cloudmall_user 的 cloudmall.mysql.test.url");
    username = System.getProperty("cloudmall.mysql.test.username", "root");
    password = System.getProperty("cloudmall.mysql.test.password", "root");
    tcAddress = System.getProperty("cloudmall.seata.test.tc", "127.0.0.1:8091");
    int delimiter = tcAddress.lastIndexOf(':');
    proxy =
        new RmTcLinkProxy(
            tcAddress.substring(0, delimiter),
            Integer.parseInt(tcAddress.substring(delimiter + 1)));
    source = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    originalChecksums = source.queryForList(checksumSql());
    database = "cm15b_rm_it_" + UUID.randomUUID().toString().replace("-", "");
    artifacts = Path.of("target", database).toAbsolutePath();
    Files.createDirectories(artifacts);
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    isolatedUrl = url.replace("/cloudmall_user", "/" + database);
    fixture = new JdbcTemplate(new DriverManagerDataSource(isolatedUrl, username, password));
    for (String table :
        List.of(
            "mall_user",
            "user_address",
            "balance_ledger",
            "balance_debit_attempt",
            "balance_tcc_debit",
            "tcc_fence_log"))
      fixture.execute(
          (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
    Files.writeString(
        artifacts.resolve("batch.json"),
        JSON.writeValueAsString(
            Map.of(
                "database",
                database,
                "appId",
                database + "-rm",
                "tc",
                tcAddress,
                "proxyPort",
                proxy.port())));
    coordinator =
        new SpringApplicationBuilder(UserApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.application.name=" + database + "-tm",
                "--seata.application-id=" + database + "-tm",
                "--spring.datasource.url=" + isolatedUrl,
                "--spring.datasource.username=" + username,
                "--spring.datasource.password=" + password,
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.service-registry.auto-registration.enabled=false",
                "--seata.enabled=true",
                "--seata.enable-auto-data-source-proxy=false",
                "--seata.service.grouplist.default=" + tcAddress,
                "--cloudmall.user.tcc-resource.enabled=false",
                "--cloudmall.user.tcc-debit.enabled=false");
    assertTrue(
        coordinator.getBeansOfType(BalanceTccDebitLocalResource.class).isEmpty(),
        "父 TM 不能代替独立 RM 清算");
    assertTrue(
        DefaultResourceManager.get()
            .getResourceManager(BranchType.TCC)
            .getManagedResources()
            .isEmpty(),
        "请独立运行此测试类，父进程不能残留其他测试注册的 RM");
  }

  @AfterAll
  static void finishBatch() throws Exception {
    if (fixture != null
        && fixture.queryForObject(
                "SELECT COUNT(*) FROM mall_user WHERE frozen_balance<>0", Integer.class)
            > 0) preserve = true;
    if (!preserve && coordinator != null) {
      for (GlobalTransaction completed : ALL_TRANSACTIONS) assertTrue(terminal(completed));
      assertEquals(originalChecksums, source.queryForList(checksumSql()));
    }
    if (coordinator != null) coordinator.close();
    if (proxy != null) proxy.close();
    if (!preserve
        && source != null
        && database != null
        && database.matches("cm15b_rm_it_[0-9a-f]{32}"))
      source.execute("DROP DATABASE IF EXISTS " + database);
    if (preserve)
      throw new IllegalStateException("未决现场保留: " + database + "; evidence=" + artifacts);
    if (artifacts != null) System.out.println("CM15B_BATCH_CLEAN " + artifacts);
  }

  @BeforeEach
  void startCase(TestInfo info) throws Exception {
    Assumptions.assumeFalse(preserve, "未决事务尚未处理，禁止覆盖现场");
    for (String table :
        List.of("tcc_fence_log", "balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
      fixture.update("DELETE FROM " + table);
    fixture.update("DELETE FROM mall_user WHERE id=7");
    fixture.update(
        "INSERT INTO mall_user(id,username,password_hash,role,balance,status,created_at,updated_at)"
            + " VALUES(7,'rm-fault-test','unused','USER',100,1,NOW(3),NOW(3))");
    payNo = "fault-" + info.getTestMethod().orElseThrow().getName();
    assertTrue(payNo.length() <= 58);
    decisions =
        Executors.newSingleThreadExecutor(
            task -> {
              Thread thread = new Thread(task, "cm15b-decision");
              thread.setDaemon(true);
              return thread;
            });
    proxy.restore();
    startChild();
    System.out.println("CM15B_CASE_START " + payNo);
  }

  @AfterEach
  void recoverAndStopCase() throws Exception {
    if (preserve) return;
    try {
      proxy.restore();
      if (transaction != null && !terminal(transaction)) {
        if (child == null || !child.isAlive() || state().path("paused").asBoolean()) {
          killChild();
          startChild();
        }
        if (decision == null) decide(false);
        await(() -> terminal(transaction), 75);
      }
      assertEquals("0.00", scalar("SELECT frozen_balance FROM mall_user WHERE id=7"));
      snapshot("final");
      System.out.println("CM15B_CASE_END " + payNo);
    } catch (Throwable failure) {
      preserve = true;
      try {
        snapshot("unresolved");
      } catch (Exception evidenceFailure) {
        failure.addSuppressed(evidenceFailure);
      }
      throw failure;
    } finally {
      RootContext.unbind();
      killChild();
      if (decisions != null) decisions.shutdownNow();
    }
  }

  @Test
  void tryBeforeCommitCrash() throws Exception {
    begin();
    arm("TRY_BEFORE_COMMIT");
    prepareAsync();
    JsonNode reached = barrier("TRY_BEFORE_COMMIT");
    assertTrue(reached.path("transactionActive").asBoolean());
    assertMoney("100.00", "0.00", 0);
    assertEquals("0", scalar("SELECT COUNT(*) FROM balance_debit_attempt"));
    snapshot("before-kill");
    killChild();
    decide(false);
    startChild();
    settled(false, reached.path("branchId").asText());
    assertEquals("4", scalar("SELECT status FROM tcc_fence_log"));
  }

  @Test
  void tryCommittedCrashThenCommit() throws Exception {
    committedTryCrash(true);
  }

  @Test
  void tryCommittedCrashThenRollback() throws Exception {
    committedTryCrash(false);
  }

  @Test
  void confirmBeforeCommitCrash() throws Exception {
    phaseTwoCrash(true, false);
  }

  @Test
  void cancelBeforeCommitCrash() throws Exception {
    phaseTwoCrash(false, false);
  }

  @Test
  void confirmAfterCommitBeforeAckCrash() throws Exception {
    phaseTwoCrash(true, true);
  }

  @Test
  void cancelAfterCommitBeforeAckCrash() throws Exception {
    phaseTwoCrash(false, true);
  }

  @Test
  void tcLinkLossThenCommit() throws Exception {
    tcLinkLoss(true);
  }

  @Test
  void tcLinkLossThenRollback() throws Exception {
    tcLinkLoss(false);
  }

  /** 在 Try 已持久化而调用者未收到返回时终止 RM，再复用原分支清算。 */
  private void committedTryCrash(boolean commit) throws Exception {
    begin();
    arm("TRY_AFTER_COMMIT");
    prepareAsync();
    JsonNode reached = barrier("TRY_AFTER_COMMIT");
    assertFalse(reached.path("transactionActive").asBoolean());
    assertMoney("90.00", "10.00", 0);
    assertEquals("TRYING", attemptState());
    assertEquals("1", scalar("SELECT status FROM tcc_fence_log"));
    snapshot("before-kill");
    killChild();
    decide(commit);
    startChild();
    settled(commit, reached.path("branchId").asText());
  }

  /** 按 barrier 区分本地提交前和提交后 ACK 前的真实窗口。 */
  private void phaseTwoCrash(boolean commit, boolean afterCommit) throws Exception {
    begin();
    prepare();
    String branchId = scalar("SELECT branch_id FROM balance_debit_attempt");
    String point =
        (commit ? "CONFIRM" : "CANCEL") + (afterCommit ? "_AFTER_COMMIT" : "_BEFORE_COMMIT");
    arm(point);
    decide(commit);
    JsonNode reached = barrier(point);
    assertEquals(branchId, reached.path("branchId").asText());
    assertEquals(!afterCommit, reached.path("transactionActive").asBoolean());
    assertFalse(terminal(transaction), "终止进程前 TC 不能已收到阶段二完成确认");
    if (afterCommit) assertMoney(commit ? "90.00" : "100.00", "0.00", commit ? 1 : 0);
    else {
      assertMoney("90.00", "10.00", 0);
      assertEquals("TRYING", attemptState());
      assertEquals("1", scalar("SELECT status FROM tcc_fence_log"));
    }
    snapshot("before-kill");
    killChild();
    if (!afterCommit) assertMoney("90.00", "10.00", 0);
    startChild();
    settled(commit, branchId);
  }

  /** 只切断 RM 的 TC 链路，父 TM 和共享 TC 容器保持可用。 */
  private void tcLinkLoss(boolean commit) throws Exception {
    begin();
    prepare();
    String branchId = scalar("SELECT branch_id FROM balance_debit_attempt");
    assertTrue(proxy.activeConnections() > 0);
    assertTrue(proxy.forwardedBytes() > 0);
    long acceptedBefore = proxy.acceptedConnections();
    long processId = child.pid();
    proxy.block();
    assertEquals(0, proxy.activeConnections());
    decide(commit);
    await(
        () -> {
          GlobalStatus status = transaction.getStatus();
          return status != GlobalStatus.Begin && !terminal(transaction);
        },
        30);
    assertMoney("90.00", "10.00", 0);
    assertEquals("TRYING", attemptState());
    snapshot("disconnected");
    proxy.restore();
    settled(commit, branchId);
    assertTrue(proxy.acceptedConnections() > acceptedBefore);
    assertEquals(processId, child.pid(), "网络恢复不应依赖重启 RM");
  }

  /** 创建原 XID，后续任何恢复都不再次调用 prepare。 */
  private void begin() throws Exception {
    transaction = GlobalTransactionContext.createNew();
    transaction.begin(120000, "cm15b-rm-failure");
    ALL_TRANSACTIONS.add(transaction);
    snapshot("begin");
  }

  /** 发送唯一一次 Try；barrier 场景不等待故意暂停的响应。 */
  private CompletableFuture<HttpResponse<String>> prepareAsync() throws Exception {
    BalanceTccBranchDTO request = new BalanceTccBranchDTO();
    request.userId = 7L;
    request.payNo = payNo;
    request.orderNo = "order-" + payNo;
    request.amount = new BigDecimal("10.00");
    return http.sendAsync(
        controlRequest("/prepare", Map.of("xid", transaction.getXid(), "request", request)),
        HttpResponse.BodyHandlers.ofString());
  }

  /** 普通 Try 必须明确返回成功。 */
  private void prepare() throws Exception {
    HttpResponse<String> response = prepareAsync().get(20, TimeUnit.SECONDS);
    assertEquals(200, response.statusCode(), response.body());
    assertTrue(JSON.readTree(response.body()).path("prepared").asBoolean());
  }

  /** 异步 RPC 在自身线程绑定原 XID，不阻塞控制线程观察 RM barrier。 */
  private void decide(boolean commit) {
    assertNull(decision, "同一全局事务只能选择一次决议");
    committing = commit;
    RootContext.unbind();
    decision =
        decisions.submit(
            () -> {
              RootContext.bind(transaction.getXid());
              try {
                if (commit) transaction.commit();
                else transaction.rollback();
              } finally {
                RootContext.unbind();
              }
              return null;
            });
  }

  /** 同时核对 TC、分支身份和全部资金终态。 */
  private void settled(boolean commit, String branchId) throws Exception {
    await(
        () -> terminal(transaction) && (commit ? "CONFIRMED" : "CANCELLED").equals(attemptState()),
        75);
    if (decision != null) decision.get(45, TimeUnit.SECONDS);
    assertMoney(commit ? "90.00" : "100.00", "0.00", commit ? 1 : 0);
    assertEquals(transaction.getXid(), scalar("SELECT xid FROM balance_debit_attempt"));
    assertEquals(branchId, scalar("SELECT branch_id FROM balance_debit_attempt"));
    assertEquals("1", scalar("SELECT COUNT(*) FROM balance_debit_attempt"));
    assertEquals("1", scalar("SELECT COUNT(*) FROM tcc_fence_log"));
    assertEquals(transaction.getXid(), scalar("SELECT xid FROM tcc_fence_log"));
    assertEquals(branchId, scalar("SELECT branch_id FROM tcc_fence_log"));
    String debitCount = scalar("SELECT COUNT(*) FROM balance_tcc_debit");
    assertEquals(
        commit ? "2" : ("0".equals(debitCount) ? "4" : "3"),
        scalar("SELECT status FROM tcc_fence_log"));
    if (!"0".equals(debitCount)) {
      assertEquals("1", debitCount);
      assertEquals(
          commit ? "CONFIRMED" : "CANCELLED", scalar("SELECT state FROM balance_tcc_debit"));
      assertEquals(transaction.getXid(), scalar("SELECT xid FROM balance_tcc_debit"));
      assertEquals(branchId, scalar("SELECT branch_id FROM balance_tcc_debit"));
    }
    if (commit) {
      assertEquals("DEBIT", scalar("SELECT operation_type FROM balance_ledger"));
      assertEquals("APPLIED", scalar("SELECT state FROM balance_ledger"));
      assertEquals("10.00", scalar("SELECT amount FROM balance_ledger"));
      assertEquals(payNo, scalar("SELECT operation_key FROM balance_ledger"));
      assertEquals(payNo, scalar("SELECT pay_no FROM balance_ledger"));
    }
    String log = Files.readString(childLog);
    assertTrue(
        log.contains("xid=" + transaction.getXid() + ",branchId=" + branchId),
        "恢复后的 RM 必须实际收到原分支回调");
    snapshot("settled");
  }

  /** 只启动本测试拥有的子进程，使用标准 JVM 参数文件避免 Windows 命令长度限制。 */
  private void startChild() throws Exception {
    assertTrue(child == null || !child.isAlive());
    int generation = ++launchNumber;
    Path readyFile = artifacts.resolve("ready-" + generation + ".txt");
    Path arguments = artifacts.resolve("jvm-" + generation + ".args");
    Path output = artifacts.resolve("rm-" + generation + ".log");
    childLog = output;
    controlKey = UUID.randomUUID().toString();
    String classpath =
        System.getProperty("surefire.test.class.path", System.getProperty("java.class.path"));
    List<String> command =
        List.of(
            "-Xms96m",
            "-Xmx256m",
            "-Dfile.encoding=UTF-8",
            "--add-opens=java.base/java.lang=ALL-UNNAMED",
            "-cp",
            classpath,
            UserRmFaultProcess.class.getName(),
            "--spring.profiles.active=cm15b-rm-child",
            "--spring.application.name=" + database + "-rm",
            "--seata.application-id=" + database + "-rm",
            "--spring.datasource.url=" + isolatedUrl,
            "--spring.datasource.username=" + username,
            "--spring.datasource.password=" + password,
            "--spring.cloud.nacos.discovery.enabled=false",
            "--spring.cloud.service-registry.auto-registration.enabled=false",
            "--seata.enabled=true",
            "--seata.enable-auto-data-source-proxy=false",
            "--seata.service.grouplist.default=127.0.0.1:" + proxy.port(),
            "--cloudmall.user.tcc-resource.enabled=true",
            "--cloudmall.user.tcc-debit.enabled=true",
            "--cloudmall.test.control-key=" + controlKey,
            "--cloudmall.test.ready-file=" + readyFile);
    Files.writeString(
        arguments,
        command.stream()
            .map(value -> "\"" + value.replace('\\', '/').replace("\"", "\\\"") + "\"")
            .collect(java.util.stream.Collectors.joining(System.lineSeparator())));
    String executable =
        System.getProperty("os.name").toLowerCase().contains("win") ? "java.exe" : "java";
    child =
        new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", executable).toString(),
                "@" + arguments)
            .redirectErrorStream(true)
            .redirectOutput(output.toFile())
            .start();
    await(
        () -> {
          if (!child.isAlive()) throw new IllegalStateException("RM 启动失败，见 " + output);
          return Files.exists(readyFile);
        },
        50);
    controlPort = Integer.parseInt(Files.readString(readyFile).trim());
    assertFalse(state().path("paused").asBoolean());
    Files.writeString(
        artifacts.resolve("active-process.json"),
        JSON.writeValueAsString(
            Map.of(
                "pid",
                child.pid(),
                "appId",
                database + "-rm",
                "database",
                database,
                "log",
                output.toString())));
  }

  /** 仅终止本用例通过 ProcessBuilder 创建并持有的 RM，不按名称批量杀进程。 */
  private void killChild() throws Exception {
    if (child != null && child.isAlive()) {
      child.destroyForcibly();
      assertTrue(child.waitFor(12, TimeUnit.SECONDS));
    }
  }

  /** 设置下一次调用的确定性故障点。 */
  private void arm(String point) throws Exception {
    HttpResponse<String> response =
        http.send(
            controlRequest("/arm", Map.of("point", point)), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
  }

  /** 读取 barrier 状态；独立控制线程不会被资金事务锁阻塞。 */
  private JsonNode state() throws Exception {
    HttpResponse<String> response =
        http.send(controlRequest("/state", null), HttpResponse.BodyHandlers.ofString());
    assertEquals(200, response.statusCode(), response.body());
    return JSON.readTree(response.body());
  }

  /** 等待明确阶段、事务状态和原 XID 的证明。 */
  private JsonNode barrier(String point) throws Exception {
    await(() -> state().path("paused").asBoolean(), 35);
    JsonNode reached = state();
    assertEquals(point, reached.path("point").asText());
    assertEquals(transaction.getXid(), reached.path("xid").asText());
    Files.writeString(artifacts.resolve(payNo + "-barrier.json"), reached.toPrettyString());
    return reached;
  }

  /** 控制端口只接受本用例的随机键，Prepare 响应可被故障刻意中断。 */
  private HttpRequest controlRequest(String path, Object body) throws Exception {
    HttpRequest.Builder builder =
        HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + controlPort + path))
            .timeout(Duration.ofSeconds(45))
            .header("X-Test-Key", controlKey);
    return body == null
        ? builder.GET().build()
        : builder
            .header("Content-Type", "application/json")
            .POST(HttpRequest.BodyPublishers.ofString(JSON.writeValueAsString(body)))
            .build();
  }

  /** 留下不可变的资金与协议事实快照，失败时供原 XID 恢复。 */
  private void snapshot(String stage) throws Exception {
    Map<String, Object> facts = new java.util.LinkedHashMap<>();
    facts.put("database", database);
    facts.put("appId", database + "-rm");
    facts.put("payNo", payNo);
    facts.put("xid", transaction == null ? null : transaction.getXid());
    facts.put("commitDecision", decision == null ? null : committing);
    if (transaction != null) {
      try {
        facts.put("tcStatus", transaction.getStatus().name());
      } catch (Exception unavailable) {
        facts.put("tcStatus", "UNAVAILABLE: " + unavailable.getClass().getSimpleName());
      }
    }
    facts.put("rmPid", child == null ? null : child.pid());
    facts.put("rmLog", childLog == null ? null : childLog.toString());
    facts.put("proxyConnections", proxy.activeConnections());
    facts.put("proxyAcceptedConnections", proxy.acceptedConnections());
    for (String table :
        List.of("balance_debit_attempt", "balance_tcc_debit", "balance_ledger", "tcc_fence_log"))
      facts.put(table, fixture.queryForList("SELECT * FROM " + table));
    facts.put(
        "money",
        fixture.queryForList("SELECT id,balance,frozen_balance FROM mall_user WHERE id=7"));
    Files.writeString(
        artifacts.resolve(payNo + "-" + stage + ".json"),
        JSON.writerWithDefaultPrettyPrinter().writeValueAsString(facts));
  }

  /** TC 终态仅用于验收清理，资金结论另以 MySQL 断言为准。 */
  private static boolean terminal(GlobalTransaction transaction) throws Exception {
    return Set.of(
            GlobalStatus.Committed,
            GlobalStatus.Rollbacked,
            GlobalStatus.TimeoutRollbacked,
            GlobalStatus.Finished)
        .contains(transaction.getStatus());
  }

  /** 读取已提交可见的资金，证明故障位于提交边界哪一侧。 */
  private void assertMoney(String available, String frozen, int ledgers) {
    assertEquals(available, scalar("SELECT balance FROM mall_user WHERE id=7"));
    assertEquals(frozen, scalar("SELECT frozen_balance FROM mall_user WHERE id=7"));
    assertEquals(Integer.toString(ledgers), scalar("SELECT COUNT(*) FROM balance_ledger"));
  }

  /** 读取唯一归属状态，尚未提交时返回空字符串。 */
  private String attemptState() {
    List<String> states =
        fixture.queryForList(
            "SELECT state FROM balance_debit_attempt WHERE pay_no=?", String.class, payNo);
    return states.isEmpty() ? "" : states.get(0);
  }

  /** 独立库标量事实读取。 */
  private String scalar(String query) {
    return fixture.queryForObject(query, String.class);
  }

  /** 原业务表只做 checksum，不写入测试资金。 */
  private static String checksumSql() {
    return "CHECKSUM TABLE"
               + " cloudmall_user.mall_user,cloudmall_user.balance_ledger,cloudmall_user.balance_debit_attempt,cloudmall_user.balance_tcc_debit,cloudmall_user.tcc_fence_log,cloudmall_pay.pay_record,cloudmall_pay.pay_debit_intent";
  }

  /** 可抛异常的有界条件观察器。 */
  private interface Condition {
    boolean satisfied() throws Exception;
  }

  /** 等待明确事实，不用固定延时猜事务阶段。 */
  private void await(Condition condition, int seconds) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(seconds).toNanos();
    while (!condition.satisfied()) {
      if (System.nanoTime() >= deadline) fail("故障验收等待超时，保留现场 " + artifacts);
      Thread.sleep(100);
    }
  }
}
