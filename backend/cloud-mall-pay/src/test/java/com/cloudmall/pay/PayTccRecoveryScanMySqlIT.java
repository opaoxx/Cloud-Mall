package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccExecutionRecoveryMapper;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import com.cloudmall.pay.service.impl.PayTccExecutionRecoveryStore;
import com.cloudmall.pay.service.impl.PayTccRecoveryScheduler;
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
    assertNotNull(store.claimRecovery("handoff", "old-token", NOW.plusSeconds(1), NOW));
    assertNotNull(
        store.claimRecovery("handoff", "new-token", NOW.plusSeconds(62), NOW.plusSeconds(2)));
    assertFalse(store.finishRecovery("handoff", "old-token", NOW.plusDays(1), "late"));
    assertEquals(
        "new-token",
        fixture.queryForObject("SELECT recovery_owner FROM pay_tcc_execution", String.class));
    assertTrue(store.finishRecovery("handoff", "new-token", NOW.plusSeconds(12), "pending"));
    assertEquals(before, business("handoff"));
    assertEquals(
        2,
        fixture.queryForObject("SELECT recovery_attempts FROM pay_tcc_execution", Integer.class));
  }

  @Test
  void retryCounterSaturatesButNeverPermanentlyStopsRecovery() {
    insert(1, "old-unknown", "UNKNOWN", "NONE");
    fixture.update("UPDATE pay_tcc_execution SET recovery_attempts=31");
    assertEquals(1, store.findDue(NOW, 20).size());
    PayTccExecutionPO claimed =
        store.claimRecovery("old-unknown", "token", NOW.plusSeconds(60), NOW);
    assertNotNull(claimed);
    assertEquals(31, claimed.recoveryAttempts);
    assertTrue(store.finishRecovery("old-unknown", "token", NOW.plusSeconds(300), "still unknown"));
    assertTrue(store.findDue(NOW.plusSeconds(299), 20).isEmpty());
    assertEquals(1, store.findDue(NOW.plusSeconds(300), 20).size());
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
    PayTccRecoveryScheduler first = new PayTccRecoveryScheduler(store, coordinator, 20, 60, 10000);
    PayTccRecoveryScheduler second = new PayTccRecoveryScheduler(store, coordinator, 20, 60, 10000);
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
    PayTccRecoveryScheduler worker = new PayTccRecoveryScheduler(store, coordinator, 20, 60, 10000);
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
