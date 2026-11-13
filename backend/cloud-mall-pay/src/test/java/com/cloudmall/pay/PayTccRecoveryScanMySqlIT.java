package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccExecutionRecoveryMapper;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.impl.execution.PayTccExecutionRecoveryStore;
import com.cloudmall.pay.service.impl.execution.PayTccRecoveryScheduler;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.*;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 真实 MySQL/MyBatis/事务验证扫描与 token CAS，不向任何 TC 注册分支。 */
class PayTccRecoveryScanMySqlIT {
  /** 固定时刻用于精确检查到期和过期边界。 */
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 12, 0);

  /** 仅管理本测试随机库的原库连接。 */
  private static JdbcTemplate source;

  /** 隔离库的事实核验连接。 */
  private static JdbcTemplate fixture;

  /** 实际 Pay 上下文，Seata 与全部 TCC 执行/调度开关关闭。 */
  private static ConfigurableApplicationContext application;

  /** 明确限定删除的数据库名。 */
  private static String database;

  /** 恢复短事务 Spring 代理。 */
  private static PayTccExecutionRecoveryStore store;

  /** 真实扫描 SQL 与幂等 DDL。 */
  private static PayTccExecutionRecoveryMapper mapper;

  /** 原表校验和。 */
  private static List<Map<String, Object>> checksums;

  @BeforeAll
  static void setup() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_pay 测试 URL");
    source = new JdbcTemplate(new DriverManagerDataSource(url, "root", "root"));
    checksums = originalChecksums();
    database = "cm16b3_scan_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolated = url.replace("/cloudmall_pay", "/" + database);
    fixture = new JdbcTemplate(new DriverManagerDataSource(isolated, "root", "root"));
    for (String table : List.of("pay_record", "pay_debit_intent", "pay_callback_log"))
      fixture.execute(
          (String) source.queryForMap("SHOW CREATE TABLE " + table).get("Create Table"));
    application =
        new SpringApplicationBuilder(PayApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + isolated,
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.service-registry.auto-registration.enabled=false",
                "--seata.enabled=false",
                "--cloudmall.pay.tcc-coordinator.enabled=false",
                "--cloudmall.pay.tcc-recovery.enabled=false");
    application.getBean(PayTccExecutionMapper.class).ensureTable();
    application.getBean(com.cloudmall.pay.mapper.PayTccReceiptMapper.class).ensureTable();
    mapper = application.getBean(PayTccExecutionRecoveryMapper.class);
    mapper.ensureSchema();
    store = application.getBean(PayTccExecutionRecoveryStore.class);
  }

  @AfterAll
  static void cleanup() {
    if (application != null) application.close();
    if (source != null) assertEquals(checksums, originalChecksums());
    if (database != null && database.matches("cm16b3_scan_[0-9a-f]{32}"))
      source.execute("DROP DATABASE " + database);
  }

  @BeforeEach
  void clear() {
    fixture.update("DELETE FROM pay_tcc_cancel");
    fixture.update("DELETE FROM pay_tcc_dispatch");
    fixture.update("DELETE FROM pay_tcc_receipt");
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_tcc_execution");
    fixture.update("DELETE FROM pay_record");
  }

  @Test
  void excludedHeadCannotStarveMultipleBatches() {
    for (int index = 0; index < 60; index++) {
      String payNo = "excluded-" + index;
      insert(index + 1, payNo, "COMMIT_REQUESTED", "COMMIT");
      switch (index % 6) {
        case 0 -> fixture.update("UPDATE pay_record SET debit_mode='LEGACY' WHERE pay_no=?", payNo);
        case 1 ->
            fixture.update(
                "UPDATE pay_tcc_execution SET state='BEGIN_UNKNOWN',xid=NULL WHERE pay_no=?",
                payNo);
        case 2 ->
            fixture.update("UPDATE pay_tcc_execution SET state='COMMITTED' WHERE pay_no=?", payNo);
        case 3 ->
            fixture.update("UPDATE pay_record SET order_no=UPPER(order_no) WHERE pay_no=?", payNo);
        case 4 ->
            fixture.update(
                "UPDATE pay_tcc_execution SET decision='ROLLBACK' WHERE pay_no=?", payNo);
        default ->
            fixture.update("UPDATE pay_tcc_execution SET state='CONFLICT' WHERE pay_no=?", payNo);
      }
    }
    insert(61, "blank-xid", "COMMIT_REQUESTED", "COMMIT");
    fixture.update("UPDATE pay_tcc_execution SET xid=' ' WHERE pay_no='blank-xid'");
    List<Map<String, Object>> before =
        fixture.queryForList("SELECT * FROM pay_tcc_execution ORDER BY id");
    for (int index = 0; index < 21; index++)
      insert(101 + index, "ready-" + index, "COMMIT_REQUESTED", "COMMIT");
    List<PayTccExecutionPO> first = store.findDue(NOW, 20);
    assertEquals(20, first.size());
    assertEquals("ready-0", first.get(0).payNo);
    assertEquals("ready-19", first.get(19).payNo);
    for (PayTccExecutionPO row : first)
      assertNotNull(
          store.claimRecovery(row.payNo, UUID.randomUUID().toString(), NOW.plusSeconds(60), NOW));
    assertEquals(List.of("ready-20"), names(store.findDue(NOW, 20)));
    assertEquals(
        before, fixture.queryForList("SELECT * FROM pay_tcc_execution WHERE id<=61 ORDER BY id"));
  }

  @Test
  void claimOnlyTouchesRequestedPayNoAndRechecksEligibility() {
    insert(1, "first", "COMMIT_REQUESTED", "COMMIT");
    insert(2, "second", "COMMIT_REQUESTED", "COMMIT");
    assertNotNull(store.claimRecovery("first", "token-first", NOW.plusSeconds(60), NOW));
    assertNull(
        fixture.queryForObject(
            "SELECT recovery_owner FROM pay_tcc_execution WHERE pay_no='second'", String.class));
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT recovery_attempts FROM pay_tcc_execution WHERE pay_no='second'",
            Integer.class));
    assertEquals(List.of("second"), names(store.findDue(NOW, 20)));
    fixture.update("UPDATE pay_record SET debit_mode='TCC ' WHERE pay_no='second'");
    assertNull(store.claimRecovery("second", "token-second", NOW.plusSeconds(60), NOW));
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT recovery_attempts FROM pay_tcc_execution WHERE pay_no='second'",
            Integer.class));
  }

  @Test
  void dueAndLeaseBoundariesAreExact() {
    insert(1, "future", "UNKNOWN", "NONE");
    insert(2, "leased", "UNKNOWN", "NONE");
    insert(3, "boundary", "UNKNOWN", "NONE");
    fixture.update(
        "UPDATE pay_tcc_execution SET next_recovery_at=? WHERE pay_no='future'",
        NOW.plusSeconds(1));
    fixture.update(
        "UPDATE pay_tcc_execution SET recovery_owner='other',recovery_lease_until=? WHERE"
            + " pay_no='leased'",
        NOW.plusSeconds(1));
    fixture.update(
        "UPDATE pay_tcc_execution SET"
            + " next_recovery_at=?,recovery_owner='expired',recovery_lease_until=? WHERE"
            + " pay_no='boundary'",
        NOW,
        NOW);
    assertEquals(List.of("boundary"), names(store.findDue(NOW, 20)));
    assertNotNull(store.claimRecovery("boundary", "new", NOW.plusSeconds(60), NOW));
  }

  @Test
  void expiredOwnerCannotReleaseNewLeaseOrChangeBusinessFacts() {
    insert(1, "handoff", "COMMIT_REQUESTED", "COMMIT");
    Map<String, Object> before = business("handoff");
    LocalDateTime current = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    assertNotNull(
        store.claimRecovery(
            "handoff", "old-token", current.minusSeconds(1), current.minusSeconds(2)));
    assertNotNull(store.claimRecovery("handoff", "new-token", current.plusSeconds(60), current));
    assertFalse(store.finishRecovery("handoff", "old-token", current.plusDays(1), "late"));
    assertEquals(
        "new-token",
        fixture.queryForObject("SELECT recovery_owner FROM pay_tcc_execution", String.class));
    assertTrue(store.finishRecovery("handoff", "new-token", current.plusSeconds(12), "pending"));
    assertEquals(before, business("handoff"));
    assertEquals(
        2,
        fixture.queryForObject("SELECT recovery_attempts FROM pay_tcc_execution", Integer.class));
  }

  @Test
  void retryCounterSaturatesButNeverPermanentlyStopsRecovery() {
    insert(1, "old-unknown", "UNKNOWN", "NONE");
    fixture.update("UPDATE pay_tcc_execution SET recovery_attempts=31");
    LocalDateTime current =
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
            .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    assertEquals(1, store.findDue(current, 20).size());
    PayTccExecutionPO claimed =
        store.claimRecovery("old-unknown", "token", current.plusSeconds(60), current);
    assertNotNull(claimed);
    assertEquals(31, claimed.recoveryAttempts);
    assertTrue(
        store.finishRecovery("old-unknown", "token", current.plusSeconds(300), "still unknown"));
    assertTrue(store.findDue(current.plusSeconds(299), 20).isEmpty());
    assertEquals(1, store.findDue(current.plusSeconds(300), 20).size());
  }

  /** 尚未被新owner接手时，过期worker也不能清租约或延后恢复。 */
  @Test
  void expiredRecoveryOwnerCannotDeferUnclaimedWork() {
    insert(1, "expired-finish", "UNKNOWN", "NONE");
    LocalDateTime current = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    assertNotNull(
        store.claimRecovery(
            "expired-finish", "expired-owner", current.minusSeconds(1), current.minusSeconds(2)));
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE pay_no='expired-finish'");
    assertFalse(
        store.finishRecovery("expired-finish", "expired-owner", current.plusMinutes(5), "late"));
    assertEquals(
        before,
        fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE pay_no='expired-finish'"));
    assertNotNull(
        store.claimRecovery("expired-finish", "next-owner", current.plusMinutes(1), current));
    assertEquals(
        "next-owner",
        fixture.queryForObject(
            "SELECT recovery_owner FROM pay_tcc_execution WHERE pay_no='expired-finish'",
            String.class));
  }

  /** finish必须精确匹配支付号和token，不能沿MySQL大小写不敏感比较释放租约。 */
  @Test
  void recoveryFinishRequiresExactTokenAndPayNo() {
    insert(1, "exact-finish", "COMMIT_REQUESTED", "COMMIT");
    LocalDateTime current = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    assertNotNull(
        store.claimRecovery("exact-finish", "exact-owner", current.plusMinutes(1), current));
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE pay_no='exact-finish'");
    assertFalse(
        store.finishRecovery("exact-finish", "EXACT-OWNER", current.plusMinutes(5), "wrong token"));
    assertFalse(
        store.finishRecovery("EXACT-FINISH", "exact-owner", current.plusMinutes(5), "wrong payNo"));
    assertEquals(
        before, fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE pay_no='exact-finish'"));
    assertTrue(
        store.finishRecovery("exact-finish", "exact-owner", current.plusSeconds(5), "pending"));
    assertEquals(
        "COMMIT_REQUESTED",
        fixture.queryForObject(
            "SELECT state FROM pay_tcc_execution WHERE pay_no='exact-finish'", String.class));
  }

  /** 显式固定now核验严格到期边界；非有效lease完全不改变行，资金状态不参与改写。 */
  @Test
  void finishSqlRequiresStrictlyFutureLeaseAndPreservesBusinessFacts() {
    int identifier = 1;
    for (String boundary : List.of("expired", "equal", "missing", "future")) {
      String payNo = "finish-" + boundary;
      insert(identifier++, payNo, "ROLLBACK_REQUESTED", "ROLLBACK");
      LocalDateTime leaseUntil =
          switch (boundary) {
            case "expired" -> NOW.minusSeconds(1);
            case "equal" -> NOW;
            case "future" -> NOW.plusNanos(1_000_000);
            default -> null;
          };
      fixture.update(
          "UPDATE pay_tcc_execution SET recovery_owner='owner',recovery_lease_until=? WHERE"
              + " pay_no=?",
          leaseUntil,
          payNo);
      Map<String, Object> before =
          fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE pay_no=?", payNo);
      Map<String, Object> funds = business(payNo);
      int updated = mapper.finishRecovery(payNo, "owner", NOW.plusMinutes(1), "pending", NOW);
      assertEquals("future".equals(boundary) ? 1 : 0, updated, boundary);
      assertEquals(funds, business(payNo));
      if (updated == 0)
        assertEquals(
            before, fixture.queryForMap("SELECT * FROM pay_tcc_execution WHERE pay_no=?", payNo));
      else {
        assertNull(
            fixture.queryForObject(
                "SELECT recovery_owner FROM pay_tcc_execution WHERE pay_no=?",
                String.class,
                payNo));
        assertEquals(
            NOW.plusMinutes(1),
            fixture.queryForObject(
                "SELECT next_recovery_at FROM pay_tcc_execution WHERE pay_no=?",
                LocalDateTime.class,
                payNo));
      }
    }
  }

  @Test
  void twoSchedulersCannotCallRecoverTwiceDuringOneValidLease() throws Exception {
    insert(1, "contended", "COMMIT_REQUESTED", "COMMIT");
    PayTccCoordinatorService coordinator = mock(PayTccCoordinatorService.class);
    CountDownLatch entered = new CountDownLatch(1), release = new CountDownLatch(1);
    when(coordinator.recover("contended"))
        .thenAnswer(
            invocation -> {
              assertFalse(TransactionSynchronizationManager.isActualTransactionActive());
              entered.countDown();
              assertTrue(release.await(10, TimeUnit.SECONDS));
              PayTccExecutionVO view = new PayTccExecutionVO();
              view.state = "COMMIT_REQUESTED";
              return view;
            });
    PayTccRecoveryScheduler first =
        new PayTccRecoveryScheduler(
            store,
            coordinator,
            mock(com.cloudmall.pay.service.impl.PayTccRollbackProjectionService.class),
            20,
            60,
            10000);
    PayTccRecoveryScheduler second =
        new PayTccRecoveryScheduler(
            store,
            coordinator,
            mock(com.cloudmall.pay.service.impl.PayTccRollbackProjectionService.class),
            20,
            60,
            10000);
    ExecutorService pool = Executors.newSingleThreadExecutor();
    try {
      Future<?> active = pool.submit(first::recoverDue);
      assertTrue(entered.await(10, TimeUnit.SECONDS));
      second.recoverDue();
      release.countDown();
      active.get(10, TimeUnit.SECONDS);
      verify(coordinator, times(1)).recover("contended");
      verify(coordinator, never()).execute(any());
      assertEquals(
          1,
          fixture.queryForObject("SELECT recovery_attempts FROM pay_tcc_execution", Integer.class));
      assertNull(
          fixture.queryForObject("SELECT recovery_owner FROM pay_tcc_execution", String.class));
    } finally {
      release.countDown();
      pool.shutdownNow();
    }
  }

  @Test
  void failingHeadBacksOffSoNextBatchMakesProgressWithoutChangingDecisions() {
    for (int index = 0; index < 21; index++)
      insert(index + 1, "retry-" + index, "COMMIT_REQUESTED", "COMMIT");
    PayTccCoordinatorService coordinator = mock(PayTccCoordinatorService.class);
    AtomicInteger calls = new AtomicInteger();
    when(coordinator.recover(anyString()))
        .thenAnswer(
            invocation -> {
              calls.incrementAndGet();
              throw new BizException("PAY_TCC_EXECUTION_CONFLICT", "fixture", 409);
            });
    PayTccRecoveryScheduler worker =
        new PayTccRecoveryScheduler(
            store,
            coordinator,
            mock(com.cloudmall.pay.service.impl.PayTccRollbackProjectionService.class),
            20,
            60,
            10000);
    worker.recoverDue();
    assertEquals(20, calls.get());
    worker.recoverDue();
    assertEquals(21, calls.get());
    assertEquals(
        21,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_tcc_execution WHERE state='COMMIT_REQUESTED' AND"
                + " decision='COMMIT' AND recovery_attempts=1",
            Integer.class));
    assertEquals(
        21,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_tcc_execution WHERE"
                + " recovery_last_error='PAY_TCC_EXECUTION_CONFLICT'",
            Integer.class));
    verify(coordinator, never()).execute(any());
  }

  @Test
  void migrationCanRunTwiceWithoutChangingExistingBusinessFields() {
    insert(1, "migration", "UNKNOWN", "NONE");
    Map<String, Object> before = business("migration");
    // 仅在本测试独立库模拟带存量数据的旧 execution 表。
    assertTrue(database.matches("cm16b3_scan_[0-9a-f]{32}"));
    fixture.execute(
        "ALTER TABLE pay_tcc_execution DROP COLUMN recovery_attempts, DROP COLUMN next_recovery_at,"
            + " DROP COLUMN recovery_owner, DROP COLUMN recovery_lease_until, DROP COLUMN"
            + " recovery_last_error");
    mapper.ensureSchema();
    Object firstSchedule =
        fixture.queryForObject(
            "SELECT next_recovery_at FROM pay_tcc_execution", java.time.LocalDateTime.class);
    mapper.ensureSchema();
    assertEquals(before, business("migration"));
    assertEquals(
        firstSchedule,
        fixture.queryForObject(
            "SELECT next_recovery_at FROM pay_tcc_execution", java.time.LocalDateTime.class));
    assertEquals(
        0,
        fixture.queryForObject("SELECT recovery_attempts FROM pay_tcc_execution", Integer.class));
  }

  @Test
  void rollbackProjectionFiltersBeforeLimitAndDrainsMultipleBatches() {
    for (int index = 0; index < 24; index++) {
      String key = "excluded-rollback-" + index;
      insert(100 + index, key, "ROLLEDBACK", "ROLLBACK");
      branches(key);
      switch (index % 6) {
        case 0 -> fixture.update("UPDATE pay_record SET debit_mode='LEGACY' WHERE pay_no=?", key);
        case 1 -> fixture.update("DELETE FROM pay_tcc_dispatch WHERE pay_no=?", key);
        case 2 -> fixture.update("UPDATE pay_tcc_receipt SET amount=11 WHERE pay_no=?", key);
        case 3 ->
            fixture.update(
                "INSERT INTO"
                    + " pay_tcc_cancel(id,pay_no,user_id,order_no,amount,state,next_retry_at,created_at,updated_at)"
                    + " SELECT"
                    + " id,pay_no,user_id,order_no,amount,'CONFLICT',created_at,created_at,updated_at"
                    + " FROM pay_tcc_execution WHERE pay_no=?",
                key);
        case 4 ->
            fixture.update(
                "INSERT INTO"
                    + " pay_debit_intent(id,pay_no,user_id,order_no,amount,status,next_retry_at,created_at,updated_at)"
                    + " SELECT"
                    + " id,pay_no,user_id,order_no,amount,'FAILED',created_at,created_at,updated_at"
                    + " FROM pay_tcc_execution WHERE pay_no=?",
                key);
        default -> fixture.update("UPDATE pay_record SET status='SUCCESS' WHERE pay_no=?", key);
      }
      assertNull(store.claimRollbackProjection(key, "invalid-owner", NOW.plusSeconds(60), NOW));
    }
    for (int index = 0; index < 21; index++) {
      String key = "valid-rollback-" + index;
      insert(
          200 + index, key, "ROLLEDBACK", new String[] {"NONE", "COMMIT", "ROLLBACK"}[index % 3]);
      branches(key);
    }
    var first = store.findDueRollbackProjection(NOW, 20);
    assertEquals(20, first.size());
    assertEquals("valid-rollback-0", first.get(0).payNo);
    for (var row : first)
      assertNotNull(
          store.claimRollbackProjection(row.payNo, "claim-" + row.payNo, NOW.plusSeconds(60), NOW));
    assertEquals(List.of("valid-rollback-20"), names(store.findDueRollbackProjection(NOW, 20)));
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_tcc_execution WHERE id<200 AND recovery_attempts<>0",
            Integer.class));
  }

  @Test
  void oldRecoveryTokenCannotProjectAfterRollbackLeaseTakeover() {
    LocalDateTime current = LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
    insert(1, "rollback-lease", "ROLLBACK_REQUESTED", "ROLLBACK");
    assertNotNull(
        store.claimRecovery("rollback-lease", "old-recovery", current.plusSeconds(60), current));
    branches("rollback-lease");
    fixture.update(
        "UPDATE pay_tcc_execution SET state='ROLLEDBACK',recovery_lease_until=?",
        current.minusSeconds(1));
    assertNotNull(
        store.claimRollbackProjection(
            "rollback-lease", "new-projection", current.plusSeconds(60), current));
    var payment = new com.cloudmall.pay.domain.vo.PayVO();
    payment.payNo = "rollback-lease";
    payment.userId = 7L;
    payment.orderNo = "order-rollback-lease";
    payment.amount = java.math.BigDecimal.TEN;
    var projectionStore =
        application.getBean(com.cloudmall.pay.service.impl.PayTccPublicStateStore.class);
    assertThrows(
        BizException.class,
        () ->
            projectionStore.projectFailedClaimed(
                payment, "fixture-rollback-lease", "old-recovery"));
    assertFalse(
        store.finishRecovery("rollback-lease", "old-recovery", current.plusDays(1), "late"));
    assertEquals("PENDING", fixture.queryForObject("SELECT status FROM pay_record", String.class));
    assertEquals(
        "new-projection",
        fixture.queryForObject("SELECT recovery_owner FROM pay_tcc_execution", String.class));
    fixture.update("UPDATE pay_tcc_execution SET recovery_lease_until=?", current.minusSeconds(1));
    assertThrows(
        BizException.class,
        () ->
            projectionStore.projectFailedClaimed(
                payment, "fixture-rollback-lease", "new-projection"));
    assertEquals("PENDING", fixture.queryForObject("SELECT status FROM pay_record", String.class));
  }

  @Test
  void abortCasPreservesRecoveryLeaseAndNeverOverridesChosenCommit() {
    insert(1, "abort-cas", "RUNNING", "NONE");
    fixture.update(
        "UPDATE pay_tcc_execution SET"
            + " recovery_owner='existing-owner',recovery_lease_until=?,recovery_attempts=7,next_recovery_at=?",
        NOW.plusSeconds(60),
        NOW.plusMinutes(1));
    var executions = application.getBean(PayTccExecutionMapper.class);
    assertEquals(1, executions.requestRollback("abort-cas", "fixture-abort-cas", NOW));
    var chosen = executions.selectByPayNo("abort-cas");
    assertEquals("ROLLBACK", chosen.decision);
    assertEquals("ROLLBACK_REQUESTED", chosen.state);
    assertEquals("existing-owner", chosen.recoveryOwner);
    assertEquals(NOW.plusSeconds(60), chosen.recoveryLeaseUntil);
    assertEquals(7, chosen.recoveryAttempts);
    assertEquals(NOW, chosen.nextRecoveryAt);
    assertEquals(
        0,
        executions.setDecision(
            "abort-cas", "fixture-abort-cas", "COMMIT", "COMMIT_REQUESTED", NOW));
    assertEquals(0, executions.requestRollback("abort-cas", "fixture-abort-cas", NOW));
    insert(2, "commit-first", "COMMIT_REQUESTED", "COMMIT");
    assertEquals(0, executions.requestRollback("commit-first", "fixture-commit-first", NOW));
    insert(3, "invalid-case", "RUNNING", "none");
    assertEquals(0, executions.requestRollback("invalid-case", "fixture-invalid-case", NOW));
  }

  /** 仅供隔离SQL测试的原载荷；不注册任何真实TC分支或写User资金。 */
  private void branches(String payNo) {
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_dispatch(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,user_branch_id,created_at,updated_at)"
            + " SELECT"
            + " id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,'CANCELLED',id*2+1,created_at,updated_at"
            + " FROM pay_tcc_execution WHERE pay_no=?",
        payNo);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_receipt(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,branch_id,created_at,updated_at)"
            + " SELECT"
            + " id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,'CANCELLED',id*2+2,created_at,updated_at"
            + " FROM pay_tcc_execution WHERE pay_no=?",
        payNo);
  }

  /** 所有模拟 execution 仅存在于 Seata 关闭的独立测试库。 */
  private void insert(long id, String payNo, String state, String decision) {
    fixture.update(
        "INSERT INTO"
            + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
            + " VALUES(?,?,?,7,10,'PENDING','TCC',?,?)",
        id,
        payNo,
        "order-" + payNo,
        NOW,
        NOW);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_execution(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,decision,created_at,updated_at,next_recovery_at)"
            + " VALUES(?,?,?,7,?,10,?,?,?,?,?,?,?)",
        id,
        payNo,
        "fixture-" + payNo,
        "order-" + payNo,
        "tcc-debit:" + payNo,
        payNo,
        state,
        decision,
        NOW,
        NOW,
        NOW.minusMinutes(1));
  }

  private Map<String, Object> business(String payNo) {
    return fixture.queryForMap(
        "SELECT"
            + " id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,decision,last_error,created_at,updated_at"
            + " FROM pay_tcc_execution WHERE pay_no=?",
        payNo);
  }

  private List<String> names(List<PayTccExecutionPO> rows) {
    return rows.stream().map(row -> row.payNo).toList();
  }

  private static List<Map<String, Object>> originalChecksums() {
    return source.queryForList(
        "CHECKSUM TABLE"
            + " cloudmall_pay.pay_record,cloudmall_pay.pay_debit_intent,cloudmall_pay.pay_callback_log");
  }
}
