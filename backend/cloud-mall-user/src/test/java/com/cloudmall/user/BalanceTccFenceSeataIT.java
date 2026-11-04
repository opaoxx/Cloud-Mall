package com.cloudmall.user;

import static org.junit.jupiter.api.Assertions.*;

import com.alibaba.fastjson.JSON;
import com.cloudmall.user.domain.dto.BalanceTccBranchDTO;
import com.cloudmall.user.mapper.BalanceDebitAttemptMapper;
import com.cloudmall.user.mapper.BalanceTccResourceEvidenceMapper;
import com.cloudmall.user.mapper.TccFenceLogMapper;
import com.cloudmall.user.service.BalanceTccDebitLocalResource;
import com.cloudmall.user.service.impl.tcc.BalanceTccDebitBusinessCallback;
import com.cloudmall.user.service.impl.tcc.BalanceTccDebitLocalResourceImpl;
import io.seata.common.Constants;
import io.seata.core.context.RootContext;
import io.seata.core.model.BranchType;
import io.seata.core.model.GlobalStatus;
import io.seata.rm.DefaultResourceManager;
import io.seata.rm.tcc.api.BusinessActionContext;
import io.seata.tm.api.GlobalTransaction;
import io.seata.tm.api.GlobalTransactionContext;
import java.math.BigDecimal;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.BooleanSupplier;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.PlatformTransactionManager;

/** 显式运行：真实 TC 经网络调用实际 User RM，资金仅在随机测试库内变动。 */
class BalanceTccFenceSeataIT {
  /** 原库连接只读取表结构并管理隔离库。 */
  private static JdbcTemplate source;

  /** 独立数据库的实际 SQL 验证入口。 */
  private static JdbcTemplate fixture;

  /** 可精确删除的随机测试数据库名。 */
  private static String database;

  /** 实际 User Spring Boot 进程上下文，Seata RM/TM 均连接真实 TC。 */
  private static ConfigurableApplicationContext application;

  /** 经 Seata 代理注册分支的实际资源。 */
  private static BalanceTccDebitLocalResource resource;

  /** 仅用于模拟已注册请求迟到的适配器，跳过再次登记分支。 */
  private static BalanceTccDebitLocalResource raw;

  /** 若任一验收事务未收敛，后续测试不能删除其数据库事实。 */
  private static boolean preserveFixture;

  /** 尚需结束的真实全局事务，失败测试也必须回滚。 */
  private final List<GlobalTransaction> pending = new ArrayList<>();

  /** 当前测试全部真实事务，用于确认 TC 已结束而不是仍在后台重试。 */
  private final List<GlobalTransaction> transactions = new ArrayList<>();

  @BeforeAll
  static void startUserRm() throws Exception {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_user(\\?.*)?"))
      throw new IllegalStateException("显式 IT 需要 cloudmall_user 的 cloudmall.mysql.test.url");
    String username = System.getProperty("cloudmall.mysql.test.username", "root");
    String password = System.getProperty("cloudmall.mysql.test.password", "root");
    source = new JdbcTemplate(new DriverManagerDataSource(url, username, password));
    database = "cm15_tcc_it_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolatedUrl = url.replace("/cloudmall_user", "/" + database);
    fixture = new JdbcTemplate(new DriverManagerDataSource(isolatedUrl, username, password));
    try {
      for (String table :
          List.of(
              "mall_user",
              "user_address",
              "balance_ledger",
              "balance_debit_attempt",
              "balance_tcc_debit"))
        fixture.execute(
            (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
      application =
          new SpringApplicationBuilder(UserApplication.class)
              .web(WebApplicationType.NONE)
              .run(
                  "--spring.datasource.url=" + isolatedUrl,
                  "--spring.datasource.username=" + username,
                  "--spring.datasource.password=" + password,
                  "--spring.application.name=" + database,
                  "--spring.cloud.nacos.discovery.enabled=false",
                  "--spring.cloud.service-registry.auto-registration.enabled=false",
                  "--seata.enabled=true",
                  "--seata.enable-auto-data-source-proxy=false",
                  "--seata.service.grouplist.default="
                      + System.getProperty("cloudmall.seata.test.tc", "127.0.0.1:18091"),
                  "--cloudmall.user.tcc-resource.enabled=true",
                  "--cloudmall.user.tcc-debit.enabled=true");
      resource = application.getBean(BalanceTccDebitLocalResource.class);
      raw =
          new BalanceTccDebitLocalResourceImpl(
              application.getBean(PlatformTransactionManager.class),
              application.getBean(BalanceTccDebitBusinessCallback.class),
              application.getBean(TccFenceLogMapper.class),
              application.getBean(BalanceDebitAttemptMapper.class),
              application.getBean(BalanceTccResourceEvidenceMapper.class));
    } catch (Throwable failure) {
      stopUserRm();
      throw failure;
    }
  }

  @AfterAll
  static void stopUserRm() {
    boolean unresolved =
        preserveFixture
            || (fixture != null
                && fixture.queryForObject(
                        "SELECT COUNT(*) FROM mall_user WHERE frozen_balance<>0", Integer.class)
                    > 0);
    if (application != null) application.close();
    if (!unresolved
        && source != null
        && database != null
        && database.matches("cm15_tcc_it_[0-9a-f]{32}"))
      source.execute("DROP DATABASE IF EXISTS " + database);
    if (unresolved) throw new IllegalStateException("验收冻结尚未收敛，保留测试库供恢复: " + database);
  }

  @BeforeEach
  void fixtures() {
    Assumptions.assumeFalse(preserveFixture, "保留上一失败事务，禁止覆盖验收事实");
    fixture.execute("DROP TRIGGER IF EXISTS reject_cancel");
    for (String table :
        List.of("tcc_fence_log", "balance_tcc_debit", "balance_debit_attempt", "balance_ledger"))
      fixture.update("DELETE FROM " + table);
    fixture.update("DELETE FROM mall_user WHERE id=7");
    fixture.update(
        "INSERT INTO mall_user(id,username,password_hash,role,balance,status,created_at,updated_at)"
            + " VALUES(7,'rm-test','unused','USER',100,1,NOW(3),NOW(3))");
  }

  @AfterEach
  void finishPendingTransactions() throws Exception {
    if (preserveFixture) return;
    try {
      fixture.execute("DROP TRIGGER IF EXISTS reject_cancel");
      for (GlobalTransaction transaction : pending) transaction.rollback();
      await(() -> scalar("SELECT frozen_balance FROM mall_user WHERE id=7").equals("0.00"));
      for (GlobalTransaction transaction : transactions) {
        await(
            () -> {
              try {
                return java.util.Set.of(
                        GlobalStatus.Committed,
                        GlobalStatus.Rollbacked,
                        GlobalStatus.TimeoutRollbacked,
                        GlobalStatus.Finished)
                    .contains(transaction.getStatus());
              } catch (Exception failure) {
                return false;
              }
            });
      }
    } catch (Exception | AssertionError failure) {
      preserveFixture = true;
      throw failure;
    } finally {
      RootContext.unbind();
    }
  }

  @Test
  void realTcCommitProducesOneLedgerAndReplaysSurviveFenceCleanup() throws Exception {
    BalanceTccBranchDTO request = request("tc-commit", "10.00");
    GlobalTransaction transaction = begin();
    assertTrue(resource.prepare(null, request));
    BusinessActionContext context = storedContext(request);
    assertFunds("90.00", "10.00", 0);
    transaction.commit();
    pending.remove(transaction);
    await(() -> "CONFIRMED".equals(state(request)));
    assertFunds("90.00", "0.00", 1);
    assertEquals("2", scalar("SELECT status FROM tcc_fence_log"));
    assertTrue(resource.commit(context));
    assertFalse(resource.rollback(context));
    fixture.update("DELETE FROM tcc_fence_log");
    assertTrue(resource.commit(context));
    assertFalse(resource.rollback(context));
    assertFunds("90.00", "0.00", 1);
  }

  @Test
  void realTcRollbackReleasesFreezeOnce() throws Exception {
    BalanceTccBranchDTO request = request("tc-cancel", "10.00");
    GlobalTransaction transaction = begin();
    assertTrue(resource.prepare(null, request));
    BusinessActionContext context = storedContext(request);
    transaction.rollback();
    pending.remove(transaction);
    await(() -> "CANCELLED".equals(state(request)));
    assertEquals("3", scalar("SELECT status FROM tcc_fence_log"));
    assertTrue(resource.rollback(context));
    assertFalse(resource.commit(context));
    fixture.update("DELETE FROM tcc_fence_log");
    assertTrue(resource.rollback(context));
    assertFunds("100.00", "0.00", 0);
  }

  @Test
  void realTcEmptyRollbackWritesPermanentTombstoneAndBlocksDelayedPrepare() throws Exception {
    BalanceTccBranchDTO request = request("tc-empty", "10.00");
    GlobalTransaction transaction = begin();
    BusinessActionContext context = registerWithoutTry(transaction, request);
    transaction.rollback();
    pending.remove(transaction);
    await(() -> "CANCELLED".equals(state(request)));
    assertEquals("4", scalar("SELECT status FROM tcc_fence_log"));
    assertEquals("0", scalar("SELECT COUNT(*) FROM balance_tcc_debit"));
    assertFunds("100.00", "0.00", 0);
    fixture.update("DELETE FROM tcc_fence_log");
    RootContext.bind(context.getXid());
    try {
      assertThrows(RuntimeException.class, () -> raw.prepare(context, request));
    } finally {
      RootContext.unbind();
    }
    assertEquals("0", scalar("SELECT COUNT(*) FROM tcc_fence_log"));
    assertEquals("CANCELLED", state(request));
    assertFunds("100.00", "0.00", 0);
  }

  @Test
  void emptyCancelFailureRollsBackFenceAndTcRetryFinishesAfterRepair() throws Exception {
    BalanceTccBranchDTO request = request("tc-atomic", "10.00");
    GlobalTransaction transaction = begin();
    registerWithoutTry(transaction, request);
    fixture.execute(
        "CREATE TRIGGER reject_cancel BEFORE INSERT ON balance_debit_attempt FOR EACH ROW SIGNAL"
            + " SQLSTATE '45000' SET MESSAGE_TEXT='controlled cancellation failure'");
    transaction.rollback();
    assertEquals("0", scalar("SELECT COUNT(*) FROM tcc_fence_log"));
    assertEquals("0", scalar("SELECT COUNT(*) FROM balance_debit_attempt"));
    assertFunds("100.00", "0.00", 0);
    fixture.execute("DROP TRIGGER reject_cancel");
    await(() -> "CANCELLED".equals(state(request)));
    pending.remove(transaction);
    assertEquals("4", scalar("SELECT status FROM tcc_fence_log"));
    assertFunds("100.00", "0.00", 0);
  }

  @Test
  void repeatedTryCannotCreateASecondFundingBranchAndBothRollbacksConverge() throws Exception {
    BalanceTccBranchDTO request = request("tc-repeat", "10.00");
    GlobalTransaction transaction = begin();
    assertTrue(resource.prepare(null, request));
    assertThrows(RuntimeException.class, () -> resource.prepare(null, request));
    assertFunds("90.00", "10.00", 0);
    transaction.rollback();
    pending.remove(transaction);
    await(
        () ->
            "CANCELLED".equals(state(request))
                && "2".equals(scalar("SELECT COUNT(*) FROM tcc_fence_log")));
    assertEquals("1", scalar("SELECT COUNT(*) FROM balance_debit_attempt"));
    assertFunds("100.00", "0.00", 0);
  }

  @Test
  void insufficientFundsRollBackTryFenceAndRealTcCancellationLeavesNoFreeze() throws Exception {
    BalanceTccBranchDTO request = request("tc-insufficient", "101.00");
    GlobalTransaction transaction = begin();
    assertThrows(RuntimeException.class, () -> resource.prepare(null, request));
    assertEquals("0", scalar("SELECT COUNT(*) FROM tcc_fence_log"));
    assertEquals("0", scalar("SELECT COUNT(*) FROM balance_debit_attempt"));
    transaction.rollback();
    pending.remove(transaction);
    await(() -> "CANCELLED".equals(state(request)));
    assertFunds("100.00", "0.00", 0);
  }

  /** 从真实 TC 开启唯一事务，失败时交 AfterEach 清算。 */
  private GlobalTransaction begin() throws Exception {
    GlobalTransaction transaction = GlobalTransactionContext.createNew();
    transaction.begin(30000, "cm15-user-rm");
    pending.add(transaction);
    transactions.add(transaction);
    return transaction;
  }

  /** 真正在 TC 注册分支，但模拟 User Try 尚未到达，用来验证空回滚。 */
  private BusinessActionContext registerWithoutTry(
      GlobalTransaction transaction, BalanceTccBranchDTO request) throws Exception {
    Map<String, Object> action = Map.of("request", request, Constants.USE_TCC_FENCE, false);
    String data = JSON.toJSONString(Map.of(Constants.TCC_ACTION_CONTEXT, action));
    long branchId =
        DefaultResourceManager.get()
            .branchRegister(
                BranchType.TCC,
                BalanceTccDebitLocalResource.ACTION_NAME,
                null,
                transaction.getXid(),
                data,
                null);
    return context(transaction.getXid(), branchId, request);
  }

  /** 从真实归属记录构造原分支重放，不另造新 XID。 */
  private BusinessActionContext storedContext(BalanceTccBranchDTO request) {
    Map<String, Object> stored =
        fixture.queryForMap(
            "SELECT xid,branch_id FROM balance_debit_attempt WHERE pay_no=?", request.payNo);
    return context(
        (String) stored.get("xid"), ((Number) stored.get("branch_id")).longValue(), request);
  }

  /** 模拟原 callback 的稳定参数快照。 */
  private BusinessActionContext context(String xid, long branchId, BalanceTccBranchDTO request) {
    BusinessActionContext context =
        new BusinessActionContext(xid, Long.toString(branchId), Map.of("request", request));
    context.setActionName(BalanceTccDebitLocalResource.ACTION_NAME);
    return context;
  }

  /** 构造不含 XID/branchId 的业务参数。 */
  private BalanceTccBranchDTO request(String payNo, String amount) {
    BalanceTccBranchDTO request = new BalanceTccBranchDTO();
    request.userId = 7L;
    request.payNo = payNo;
    request.orderNo = "order-" + payNo;
    request.amount = new BigDecimal(amount);
    return request;
  }

  /** 核对所有资金断言，而非仅判断 TC 返回状态。 */
  private void assertFunds(String available, String frozen, int ledgers) {
    assertEquals(available, scalar("SELECT balance FROM mall_user WHERE id=7"));
    assertEquals(frozen, scalar("SELECT frozen_balance FROM mall_user WHERE id=7"));
    assertEquals(Integer.toString(ledgers), scalar("SELECT COUNT(*) FROM balance_ledger"));
  }

  /** 读取永久归属状态，尚未回调时返回空串。 */
  private String state(BalanceTccBranchDTO request) {
    List<String> states =
        fixture.queryForList(
            "SELECT state FROM balance_debit_attempt WHERE pay_no=?", String.class, request.payNo);
    return states.isEmpty() ? "" : states.get(0);
  }

  /** 读取独立库标量。 */
  private String scalar(String query) {
    return fixture.queryForObject(query, String.class);
  }

  /** 有界等待 TC 异步回调，失败必须保留验收失败结果。 */
  private void await(BooleanSupplier condition) throws Exception {
    long deadline = System.nanoTime() + Duration.ofSeconds(25).toNanos();
    while (!condition.getAsBoolean() && System.nanoTime() < deadline) Thread.sleep(100);
    assertTrue(condition.getAsBoolean(), "真实 TC 回调未在限定时间收敛");
  }
}
