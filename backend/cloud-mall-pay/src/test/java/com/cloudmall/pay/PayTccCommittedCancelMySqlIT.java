package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.service.impl.PayTccCommittedCancelStore;
import com.cloudmall.pay.service.impl.PayTccDeliveryStore;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 真实 MySQL 验证 COMMITTED 未发起交付时的取消意图与 delivery 竞争。 */
class PayTccCommittedCancelMySqlIT {
  /** 原 cloudmall_pay 连接。 */
  private static JdbcTemplate source;

  /** 本测试随机隔离库。 */
  private static JdbcTemplate fixture;

  /** 关闭外部发现、Seata 和 TCC 调度的 Pay 上下文。 */
  private static ConfigurableApplicationContext application;

  /** 仅允许删除本测试创建的数据库。 */
  private static String database;

  /** 真实取消意图存储器。 */
  private static PayTccCommittedCancelStore cancellations;

  /** 真实交付存储器。 */
  private static PayTccDeliveryStore deliveries;

  /** 真实交付 Mapper。 */
  private static PayTccDeliveryMapper deliveryMapper;

  /** 原始业务表校验和。 */
  private static List<Map<String, Object>> checksums;

  /** 稳定测试时间，避开宿主机与容器秒级时钟误差。 */
  private static final LocalDateTime DUE = LocalDateTime.of(2026, 10, 6, 12, 0);

  @BeforeAll
  static void setup() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_pay 测试 URL");
    source = new JdbcTemplate(new DriverManagerDataSource(url, "root", "root"));
    checksums = originalChecksums();
    database = "cm16o_cancel_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolated = url.replace("/cloudmall_pay", "/" + database);
    fixture = new JdbcTemplate(new DriverManagerDataSource(isolated, "root", "root"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_pay", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法表名: " + table);
      fixture.execute(
          (String)
              source.queryForMap("SHOW CREATE TABLE cloudmall_pay." + table).get("Create Table"));
    }
    application =
        new SpringApplicationBuilder(PayApplication.class)
            .web(WebApplicationType.NONE)
            .run(
                "--spring.datasource.url=" + isolated,
                "--spring.cloud.nacos.discovery.enabled=false",
                "--spring.cloud.service-registry.auto-registration.enabled=false",
                "--seata.enabled=false",
                "--cloudmall.pay.tcc-entry.enabled=false",
                "--cloudmall.pay.tcc-coordinator.enabled=false",
                "--cloudmall.pay.tcc-receipt.enabled=false",
                "--cloudmall.pay.tcc-bridge.enabled=false",
                "--cloudmall.pay.tcc-recovery.enabled=false",
                "--cloudmall.pay.tcc-delivery.enabled=false",
                "--cloudmall.pay.tcc-cancel.enabled=false");
    application.getBean(com.cloudmall.pay.mapper.PayTccExecutionMapper.class).ensureTable();
    application.getBean(com.cloudmall.pay.mapper.PayTccDispatchMapper.class).ensureTable();
    application.getBean(com.cloudmall.pay.mapper.PayTccReceiptMapper.class).ensureTable();
    application.getBean(com.cloudmall.pay.mapper.PayTccFenceMapper.class).ensureTable();
    application.getBean(com.cloudmall.pay.mapper.PayTccCancelMapper.class).ensureTable();
    deliveryMapper = application.getBean(PayTccDeliveryMapper.class);
    deliveryMapper.ensureTable();
    cancellations = application.getBean(PayTccCommittedCancelStore.class);
    deliveries = application.getBean(PayTccDeliveryStore.class);
  }

  @AfterAll
  static void cleanup() {
    if (application != null) application.close();
    if (source != null) assertEquals(checksums, originalChecksums());
    if (database != null && database.matches("cm16o_cancel_[0-9a-f]{32}"))
      source.execute("DROP DATABASE " + database);
  }

  @BeforeEach
  void clear() {
    fixture.update("DELETE FROM pay_tcc_delivery");
    fixture.update("DELETE FROM tcc_fence_log");
    fixture.update("DELETE FROM pay_tcc_receipt");
    fixture.update("DELETE FROM pay_tcc_dispatch");
    fixture.update("DELETE FROM pay_tcc_cancel");
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_tcc_execution");
    fixture.update("DELETE FROM pay_record");
  }

  @Test
  void committedPaymentWithoutDeliveryCreatesCancellationIntent() {
    insertFacts("missing-delivery");
    cancellations.request(payment("missing-delivery"));
    Map<String, Object> row =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='missing-delivery'");
    assertEquals("CANCEL_REQUESTED", row.get("state"));
    assertEquals(0, row.get("attempts"));
    assertNull(row.get("lease_owner"));
    assertNull(row.get("lease_until"));
  }

  @Test
  void readyCancellationPreservesMetadataAndOnlyChangesDirection() {
    insertFacts("ready-replay");
    insertDelivery("ready-replay", "READY", 0, null, null, DUE.minusMinutes(1));
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='ready-replay'");
    cancellations.request(payment("ready-replay"));
    Map<String, Object> afterFirst =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='ready-replay'");
    assertEquals("CANCEL_REQUESTED", afterFirst.get("state"));
    assertNotNull(deliveries.claim("ready-replay", "replay-worker"));
    Map<String, Object> afterClaim =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='ready-replay'");
    assertEquals("CANCEL_REQUESTED", afterClaim.get("state"));
    assertEquals(1, afterClaim.get("attempts"));
    assertEquals("replay-worker", afterClaim.get("lease_owner"));
    cancellations.request(payment("ready-replay"));
    Map<String, Object> afterSecond =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='ready-replay'");
    assertEquals(afterClaim, afterSecond);
    assertEquals(before.get("attempts"), afterFirst.get("attempts"));
    assertEquals(before.get("lease_owner"), afterFirst.get("lease_owner"));
    assertEquals(before.get("lease_until"), afterFirst.get("lease_until"));
  }

  @Test
  void activeAttemptedOrLeasedDeliveryCannotBeReversed() {
    insertFacts("attempted");
    LocalDateTime hostNow = hostNow();
    insertDelivery(
        "attempted", "READY", 1, "active-worker", hostNow.plusDays(1), hostNow.minusMinutes(1));
    BizException error =
        assertThrows(BizException.class, () -> cancellations.request(payment("attempted")));
    assertEquals(202, error.getHttpStatus());
    assertEquals("READY", deliveryState("attempted"));
    assertEquals(1, attempts("attempted"));
  }

  @Test
  void expiredReadyDeliveryCanBeRedirectedWithoutChangingLeaseMetadata() {
    insertFacts("expired-ready");
    LocalDateTime hostNow = hostNow();
    insertDelivery(
        "expired-ready",
        "READY",
        3,
        "expired-worker",
        hostNow.minusMinutes(1),
        hostNow.plusDays(1));
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='expired-ready'");
    cancellations.request(payment("expired-ready"));
    Map<String, Object> after =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='expired-ready'");
    assertEquals("CANCEL_REQUESTED", after.get("state"));
    assertEquals(before.get("attempts"), after.get("attempts"));
    assertEquals(before.get("lease_owner"), after.get("lease_owner"));
    assertEquals(before.get("lease_until"), after.get("lease_until"));
    assertEquals(before.get("refund_operation_key"), after.get("refund_operation_key"));
    assertTrue(nextRetry("expired-ready").isBefore(hostNow.plusDays(1).minusSeconds(1)));
  }

  @Test
  void releasedReadyDeliveryCanBeRedirectedAfterClaimFinish() {
    insertFacts("released-ready");
    insertDelivery("released-ready", "READY", 0, null, null, DUE.minusMinutes(1));
    assertNotNull(deliveries.claim("released-ready", "released-worker"));
    deliveries.finish("released-ready", "released-worker", 1, "temporary");
    Map<String, Object> released =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='released-ready'");
    assertEquals("READY", released.get("state"));
    assertEquals(1, released.get("attempts"));
    assertNull(released.get("lease_owner"));
    assertNull(released.get("lease_until"));
    cancellations.request(payment("released-ready"));
    assertEquals("CANCEL_REQUESTED", deliveryState("released-ready"));
    Map<String, Object> after =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='released-ready'");
    assertEquals(released.get("attempts"), after.get("attempts"));
    assertNull(after.get("lease_owner"));
    assertNull(after.get("lease_until"));
    assertTrue(nextRetry("released-ready").isBefore((LocalDateTime) released.get("next_retry_at")));
  }

  @Test
  void orderSyncUnknownDeliveryIsNeverRedirected() {
    insertFacts("unknown-delivery");
    insertDelivery("unknown-delivery", "READY", 0, null, null, DUE.minusMinutes(1));
    assertNotNull(deliveries.claim("unknown-delivery", "unknown-worker"));
    assertTrue(deliveries.stage("unknown-delivery", "unknown-worker", "ORDER_SYNC_UNKNOWN"));
    BizException error =
        assertThrows(BizException.class, () -> cancellations.request(payment("unknown-delivery")));
    assertEquals(202, error.getHttpStatus());
    assertEquals("ORDER_SYNC_UNKNOWN", deliveryState("unknown-delivery"));
  }

  @Test
  void requestCancellationCasUsesExactFixedTimeAndLeaseShape() {
    insertFacts("sql-ready");
    insertDelivery("sql-ready", "READY", 0, null, null, DUE.minusMinutes(1));
    assertEquals(1, deliveryMapper.requestCancellation("sql-ready", DUE));

    insertFacts("sql-active");
    insertDelivery("sql-active", "READY", 0, "active", DUE.plusSeconds(1), DUE.minusMinutes(1));
    assertEquals(0, deliveryMapper.requestCancellation("sql-active", DUE));

    insertFacts("sql-boundary");
    insertDelivery("sql-boundary", "READY", 0, "expired", DUE, DUE.minusMinutes(1));
    assertEquals(1, deliveryMapper.requestCancellation("sql-boundary", DUE));

    insertFacts("sql-partial");
    insertDelivery("sql-partial", "READY", 0, null, DUE.minusMinutes(1), DUE.minusMinutes(1));
    assertEquals(0, deliveryMapper.requestCancellation("sql-partial", DUE));

    insertFacts("sql-partial-owner");
    insertDelivery("sql-partial-owner", "READY", 0, "owner-only", null, DUE.minusMinutes(1));
    assertEquals(0, deliveryMapper.requestCancellation("sql-partial-owner", DUE));

    insertFacts("sql-blank-owner");
    insertDelivery("sql-blank-owner", "READY", 0, " ", DUE.minusMinutes(1), DUE.minusMinutes(1));
    assertEquals(0, deliveryMapper.requestCancellation("sql-blank-owner", DUE));

    insertFacts("sql-negative-attempts");
    insertDelivery("sql-negative-attempts", "READY", -1, null, null, DUE.minusMinutes(1));
    assertEquals(0, deliveryMapper.requestCancellation("sql-negative-attempts", DUE));

    insertFacts("sql-case");
    insertDelivery("sql-case", "ready", 0, null, null, DUE.minusMinutes(1));
    assertEquals(0, deliveryMapper.requestCancellation("sql-case", DUE));

    insertFacts("sql-unknown");
    insertDelivery("sql-unknown", "ORDER_SYNC_UNKNOWN", 0, null, null, DUE.minusMinutes(1));
    assertEquals(0, deliveryMapper.requestCancellation("sql-unknown", DUE));
  }

  @Test
  void existingCancellationAndLegacyGatesRemainProtected() {
    insertFacts("cancel-gate");
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_cancel(id,pay_no,user_id,order_no,amount,state,attempts,next_retry_at,created_at,updated_at)"
            + " VALUES(1,'cancel-gate',7,'order-cancel-gate',10,'REQUESTED',0,NOW(3),NOW(3),NOW(3))");
    assertEquals(
        202,
        assertThrows(BizException.class, () -> cancellations.request(payment("cancel-gate")))
            .getHttpStatus());
    fixture.update("DELETE FROM pay_tcc_cancel");
    fixture.update(
        "INSERT INTO"
            + " pay_debit_intent(id,pay_no,order_no,user_id,amount,debit_mode,status,attempts,next_retry_at,created_at,updated_at)"
            + " VALUES(1,'cancel-gate','order-cancel-gate',7,10,'LEGACY','INIT',0,NOW(3),NOW(3),NOW(3))");
    assertEquals(
        202,
        assertThrows(BizException.class, () -> cancellations.request(payment("cancel-gate")))
            .getHttpStatus());
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_tcc_delivery WHERE pay_no='cancel-gate'", Integer.class));
  }

  @Test
  void requestAndClaimCompetitionHasNoDeadlockAndProtectsWinner() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int index = 0; index < 10; index++) {
        String payNo = "race-" + index;
        String token = "worker-" + index;
        insertFacts(payNo);
        insertDelivery(payNo, "READY", 0, null, null, DUE.minusMinutes(1));
        CountDownLatch start = new CountDownLatch(1);
        Callable<Boolean> requestTask =
            () -> {
              start.await();
              try {
                cancellations.request(payment(payNo));
                return true;
              } catch (BizException expected) {
                assertEquals(202, expected.getHttpStatus());
                return false;
              }
            };
        Callable<PayTccDeliveryPO> claimTask =
            () -> {
              start.await();
              return deliveries.claim(payNo, token);
            };
        Future<Boolean> request = pool.submit(requestTask);
        Future<PayTccDeliveryPO> claim = pool.submit(claimTask);
        start.countDown();
        boolean requestAccepted = request.get(15, TimeUnit.SECONDS);
        PayTccDeliveryPO claimed = claim.get(15, TimeUnit.SECONDS);
        assertNotNull(claimed);
        String state = deliveryState(payNo);
        if (requestAccepted) {
          assertEquals("CANCEL_REQUESTED", state);
          assertEquals("CANCEL_REQUESTED", claimed.state);
        } else {
          assertEquals("READY", state);
          assertEquals("READY", claimed.state);
          assertEquals(1, attempts(payNo));
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void requestAndOldStageCompetitionHasNoDeadlockAndProtectsDirection() throws Exception {
    ExecutorService pool = Executors.newFixedThreadPool(2);
    try {
      for (int index = 0; index < 10; index++) {
        String payNo = "stage-race-" + index;
        String token = "stage-worker-" + index;
        insertFacts(payNo);
        insertDelivery(payNo, "READY", 0, null, null, DUE.minusMinutes(1));
        CyclicBarrier start = new CyclicBarrier(2);
        Callable<Boolean> requestTask =
            () -> {
              start.await(15, TimeUnit.SECONDS);
              try {
                cancellations.request(payment(payNo));
                return true;
              } catch (BizException expected) {
                assertEquals(202, expected.getHttpStatus());
                return false;
              }
            };
        Callable<Boolean> stageTask =
            () -> {
              start.await(15, TimeUnit.SECONDS);
              PayTccDeliveryPO claimed = deliveries.claim(payNo, token);
              if (claimed == null) return false;
              try {
                return deliveries.stage(payNo, token, "ORDER_SYNC_UNKNOWN");
              } catch (BizException expected) {
                return false;
              }
            };
        Future<Boolean> request = pool.submit(requestTask);
        Future<Boolean> stage = pool.submit(stageTask);
        boolean requestAccepted = request.get(15, TimeUnit.SECONDS);
        boolean stageAccepted = stage.get(15, TimeUnit.SECONDS);
        if (requestAccepted) {
          assertFalse(stageAccepted);
          assertEquals("CANCEL_REQUESTED", deliveryState(payNo));
          assertEquals("PENDING", paymentState(payNo));
        } else {
          assertTrue(stageAccepted);
          assertEquals("ORDER_SYNC_UNKNOWN", deliveryState(payNo));
          assertEquals("ORDER_SYNC_UNKNOWN", paymentState(payNo));
        }
      }
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void staleStageCannotPaidAfterCancellationIntentAccepted() {
    insertFacts("stale-stage");
    cancellations.request(payment("stale-stage"));
    assertNotNull(deliveries.claim("stale-stage", "stale-worker"));
    assertThrows(
        BizException.class, () -> deliveries.stage("stale-stage", "stale-worker", "SUCCESS"));
    assertEquals("CANCEL_REQUESTED", deliveryState("stale-stage"));
    assertEquals("PENDING", paymentState("stale-stage"));
  }

  @Test
  void cancellationWorkerCannotPaidButCanRefund() {
    insertFacts("cancel-worker");
    cancellations.request(payment("cancel-worker"));
    PayTccDeliveryPO claimed = deliveries.claim("cancel-worker", "cancel-worker-token");
    assertNotNull(claimed);
    assertThrows(
        BizException.class,
        () -> deliveries.stage("cancel-worker", "cancel-worker-token", "SUCCESS"));
    assertThrows(
        BizException.class,
        () -> deliveries.stage("cancel-worker", "cancel-worker-token", "ORDER_SYNC_UNKNOWN"));
    assertTrue(deliveries.stage("cancel-worker", "cancel-worker-token", "REFUND_PENDING"));
    assertTrue(deliveries.stage("cancel-worker", "cancel-worker-token", "REFUNDED"));
    assertEquals("REFUNDED", deliveryState("cancel-worker"));
    assertEquals("REFUNDED", paymentState("cancel-worker"));
  }

  @Test
  void dueScanAndClaimIncludeCancellationState() {
    insertFacts("due-cancel");
    insertDelivery("due-cancel", "CANCEL_REQUESTED", 0, null, null, DUE.minusMinutes(1));
    assertEquals(
        List.of("due-cancel"),
        deliveryMapper.findDue(DUE, 20).stream().map(row -> row.payNo).toList());
    assertNotNull(deliveries.claim("due-cancel", "due-token"));
    assertEquals(1, attempts("due-cancel"));
  }

  @Test
  void failedInsertRollsBackCancellationIntent() {
    insertFacts("trigger-cancel");
    fixture.execute(
        "CREATE TRIGGER fail_cancel_delivery BEFORE INSERT ON pay_tcc_delivery FOR EACH ROW BEGIN"
            + " IF NEW.state='CANCEL_REQUESTED' THEN SIGNAL SQLSTATE '45000' SET"
            + " MESSAGE_TEXT='fixture failure'; END IF; END");
    try {
      assertThrows(RuntimeException.class, () -> cancellations.request(payment("trigger-cancel")));
    } finally {
      fixture.execute("DROP TRIGGER fail_cancel_delivery");
    }
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_tcc_delivery WHERE pay_no='trigger-cancel'", Integer.class));
  }

  private void insertFacts(String payNo) {
    long id = Math.abs(payNo.hashCode()) + 1L;
    String xid = "xid-" + payNo;
    fixture.update(
        "INSERT INTO"
            + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
            + " VALUES(?,?,?,7,10,'PENDING','TCC',NOW(3),NOW(3))",
        id,
        payNo,
        "order-" + payNo);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_execution(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,decision,created_at,updated_at,next_recovery_at)"
            + " VALUES(?,?,?,?,?,10,?,?, 'COMMITTED','COMMIT',NOW(3),NOW(3),?)",
        id,
        payNo,
        xid,
        7L,
        "order-" + payNo,
        "tcc-debit:" + payNo,
        payNo,
        DUE);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_dispatch(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,state,user_branch_id,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,'CONFIRMED',2002,NOW(3),NOW(3))",
        id,
        payNo,
        xid,
        7L,
        "order-" + payNo,
        BigDecimal.TEN,
        "tcc-debit:" + payNo,
        payNo);
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_receipt(id,pay_no,xid,user_id,order_no,amount,operation_key,ledger_key,branch_id,state,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?, ?,1001,'CONFIRMED',NOW(3),NOW(3))",
        id,
        payNo,
        xid,
        7L,
        "order-" + payNo,
        BigDecimal.TEN,
        "tcc-debit:" + payNo,
        payNo);
    fixture.update(
        "INSERT INTO tcc_fence_log(xid,branch_id,action_name,status,gmt_create,gmt_modified)"
            + " VALUES(?,1001,'cloudmallPayDebitReceipt',2,NOW(3),NOW(3))",
        xid);
  }

  private void insertDelivery(
      String payNo,
      String state,
      int attempts,
      String owner,
      LocalDateTime leaseUntil,
      LocalDateTime nextRetryAt) {
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_delivery(id,pay_no,xid,user_id,order_no,amount,state,refund_operation_key,attempts,next_retry_at,lease_owner,lease_until,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,10,?,?,?,?,?,?,NOW(3),NOW(3))",
        Math.abs((payNo + state).hashCode()) + 1L,
        payNo,
        "xid-" + payNo,
        7L,
        "order-" + payNo,
        state,
        "refund:" + payNo,
        attempts,
        nextRetryAt,
        owner,
        leaseUntil);
  }

  private PayVO payment(String payNo) {
    PayVO payment = new PayVO();
    payment.payNo = payNo;
    payment.orderNo = "order-" + payNo;
    payment.userId = 7L;
    payment.amount = BigDecimal.TEN;
    payment.status = "PENDING";
    return payment;
  }

  private String deliveryState(String payNo) {
    return fixture.queryForObject(
        "SELECT state FROM pay_tcc_delivery WHERE pay_no=?", String.class, payNo);
  }

  private String paymentState(String payNo) {
    return fixture.queryForObject(
        "SELECT status FROM pay_record WHERE pay_no=?", String.class, payNo);
  }

  private LocalDateTime hostNow() {
    return LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"));
  }

  private LocalDateTime nextRetry(String payNo) {
    return fixture.queryForObject(
        "SELECT next_retry_at FROM pay_tcc_delivery WHERE pay_no=?", LocalDateTime.class, payNo);
  }

  private int attempts(String payNo) {
    return fixture.queryForObject(
        "SELECT attempts FROM pay_tcc_delivery WHERE pay_no=?", Integer.class, payNo);
  }

  private static List<Map<String, Object>> originalChecksums() {
    return source.queryForList(
        "CHECKSUM TABLE"
            + " cloudmall_pay.pay_record,cloudmall_pay.pay_debit_intent,cloudmall_pay.pay_callback_log");
  }
}
