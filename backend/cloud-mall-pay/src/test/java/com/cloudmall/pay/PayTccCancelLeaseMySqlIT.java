package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.service.impl.cancellation.PayTccCancelStore;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 真实 MySQL 主动取消租约与本地状态 CAS 验证，不注册 Seata 分支或写入真实资金。 */
class PayTccCancelLeaseMySqlIT {
  /** 原 cloudmall_pay 连接。 */
  private static JdbcTemplate source;

  /** 隔离测试库连接。 */
  private static JdbcTemplate fixture;

  /** 关闭发现、Seata、协调器和恢复调度的 Pay 上下文。 */
  private static ConfigurableApplicationContext application;

  /** 本测试随机数据库名。 */
  private static String database;

  /** 真实取消 Mapper。 */
  private static PayTccCancelMapper cancels;

  /** 真实取消 Store 事务代理。 */
  private static PayTccCancelStore store;

  /** 原业务表校验和。 */
  private static List<Map<String, Object>> checksums;

  @BeforeAll
  static void setup() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_pay 测试 URL");
    source = new JdbcTemplate(new DriverManagerDataSource(url, "root", "root"));
    checksums = originalChecksums();
    database = "cm16m_cancel_" + UUID.randomUUID().toString().replace("-", "");
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
                "--cloudmall.pay.tcc-coordinator.enabled=false",
                "--cloudmall.pay.tcc-recovery.enabled=false",
                "--cloudmall.pay.tcc-delivery.enabled=false");
    application.getBean(PayTccExecutionMapper.class).ensureTable();
    application.getBean(PayTccCancelMapper.class).ensureTable();
    cancels = application.getBean(PayTccCancelMapper.class);
    store = application.getBean(PayTccCancelStore.class);
  }

  @AfterAll
  static void cleanup() {
    if (application != null) application.close();
    if (source != null) assertEquals(checksums, originalChecksums());
    if (database != null && database.matches("cm16m_cancel_[0-9a-f]{32}"))
      source.execute("DROP DATABASE " + database);
  }

  @BeforeEach
  void clear() {
    fixture.update("DELETE FROM pay_tcc_cancel");
    fixture.update("DELETE FROM pay_tcc_execution");
    fixture.update("DELETE FROM pay_tcc_dispatch");
    fixture.update("DELETE FROM pay_tcc_receipt");
    fixture.update("DELETE FROM pay_debit_intent");
    fixture.update("DELETE FROM pay_record");
  }

  @Test
  void mapperStateCasRequiresExactCurrentOwnerAndFutureLease() {
    LocalDateTime sampledNow = hostNow();
    LocalDateTime now = sampledNow.withNano((sampledNow.getNano() / 1_000_000) * 1_000_000);
    for (String boundary : List.of("expired", "equal", "null", "future")) {
      insertCancel("state-" + boundary, "REQUESTED", "owner", lease(boundary, now));
      List<Map<String, Object>> before = snapshot("state-" + boundary);
      assertEquals(0, cancels.updateState("state-" + boundary, "Owner", "COMPLETED", null, now));
      assertEquals(before, snapshot("state-" + boundary));
      int changed = cancels.updateState("state-" + boundary, "owner", "COMPLETED", null, now);
      assertEquals("future".equals(boundary) ? 1 : 0, changed, boundary);
      fixture.update("DELETE FROM pay_tcc_cancel WHERE pay_no=?", "state-" + boundary);
    }
    insertCancel("state-old", "REQUESTED", "new-owner", now.plusMinutes(1));
    List<Map<String, Object>> before = snapshot("state-old");
    assertEquals(0, cancels.updateState("state-old", "old-owner", "COMPLETED", null, now));
    assertEquals(before, snapshot("state-old"));
  }

  @Test
  void mapperFinishCasRequiresFutureLeaseAndDoesNotPolluteNewOwner() {
    LocalDateTime sampledNow = hostNow();
    LocalDateTime now = sampledNow.withNano((sampledNow.getNano() / 1_000_000) * 1_000_000);
    for (String boundary : List.of("expired", "equal", "null", "future")) {
      insertCancel("finish-" + boundary, "REQUESTED", "owner", lease(boundary, now));
      List<Map<String, Object>> before = snapshot("finish-" + boundary);
      assertEquals(
          0, cancels.finish("finish-" + boundary, "Owner", now.plusMinutes(1), "late", now));
      assertEquals(before, snapshot("finish-" + boundary));
      int changed = cancels.finish("finish-" + boundary, "owner", now.plusMinutes(1), null, now);
      assertEquals("future".equals(boundary) ? 1 : 0, changed, boundary);
      fixture.update("DELETE FROM pay_tcc_cancel WHERE pay_no=?", "finish-" + boundary);
    }
    insertCancel("finish-handoff", "REQUESTED", "new-owner", now.plusMinutes(1));
    List<Map<String, Object>> before = snapshot("finish-handoff");
    assertEquals(0, cancels.finish("finish-handoff", "old-owner", now.plusMinutes(2), "late", now));
    assertEquals(before, snapshot("finish-handoff"));
  }

  @Test
  void storeFinishExpiredLeaseLeavesRowAndNewOwnerCanClaim() {
    insertPay("finish-store");
    insertCancel("finish-store", "REQUESTED", "expired-owner", hostNow().minusDays(1));
    List<Map<String, Object>> expired = snapshot("finish-store");
    store.finish("finish-store", "expired-owner", 1, "late");
    assertEquals(expired, snapshot("finish-store"));
    assertNotNull(store.claimRecovery("finish-store", "new-owner"));
    assertEquals("new-owner", owner("finish-store"));
  }

  @Test
  void storeStageRejectsExpiredNullAndBlankLeaseInputsWithoutPayWrite() {
    insertPay("stage-inputs");
    insertCancel("stage-inputs", "REQUESTED", "owner", hostNow().minusDays(1));
    assertFalse(store.stage("stage-inputs", "owner", "COMPLETED", null));
    fixture.update("UPDATE pay_tcc_cancel SET lease_until=NULL WHERE pay_no='stage-inputs'");
    assertFalse(store.stage("stage-inputs", "owner", "COMPLETED", null));
    fixture.update(
        "UPDATE pay_tcc_cancel SET lease_until=DATE_ADD(NOW(3),INTERVAL 1 MINUTE) WHERE"
            + " pay_no='stage-inputs'");
    for (String invalidToken : java.util.Arrays.asList(null, "", " ")) {
      fixture.update(
          "UPDATE pay_tcc_cancel SET lease_owner=? WHERE pay_no='stage-inputs'", invalidToken);
      List<Map<String, Object>> before = snapshot("stage-inputs");
      assertFalse(store.stage("stage-inputs", invalidToken, "COMPLETED", null));
      assertEquals(before, snapshot("stage-inputs"));
    }
    assertEquals("PENDING", paymentState("stage-inputs"));
    assertEquals("REQUESTED", cancelState("stage-inputs"));
  }

  @Test
  void validCompletedStageChangesPayAndCancelAtomically() {
    insertPay("stage-valid");
    insertCancel("stage-valid", "REQUESTED", "owner", hostNow().plusMinutes(1));
    assertTrue(store.stage("stage-valid", "owner", "COMPLETED", null));
    assertEquals("FAILED", paymentState("stage-valid"));
    assertEquals("COMPLETED", cancelState("stage-valid"));
  }

  @Test
  void cancelWriteFailureRollsBackPayFailureProjection() {
    insertPay("stage-trigger");
    insertCancel("stage-trigger", "REQUESTED", "owner", hostNow().plusMinutes(1));
    fixture.execute(
        "CREATE TRIGGER fail_cancel_completed BEFORE UPDATE ON pay_tcc_cancel FOR EACH ROW BEGIN IF"
            + " NEW.state='COMPLETED' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='cancel fixture"
            + " failure'; END IF; END");
    try {
      assertThrows(
          RuntimeException.class, () -> store.stage("stage-trigger", "owner", "COMPLETED", null));
    } finally {
      fixture.execute("DROP TRIGGER fail_cancel_completed");
    }
    assertEquals("PENDING", paymentState("stage-trigger"));
    assertEquals("REQUESTED", cancelState("stage-trigger"));
  }

  @Test
  void terminalCancelStatesCannotUpdateBusinessState() {
    insertCancel("terminal-completed", "COMPLETED", "owner", hostNow().plusMinutes(1));
    insertCancel("terminal-conflict", "CONFLICT", "owner", hostNow().plusMinutes(1));
    assertEquals(
        0, cancels.updateState("terminal-completed", "owner", "REQUESTED", null, hostNow()));
    assertEquals(
        0, cancels.updateState("terminal-conflict", "owner", "REQUESTED", null, hostNow()));
    assertEquals("COMPLETED", cancelState("terminal-completed"));
    assertEquals("CONFLICT", cancelState("terminal-conflict"));
  }

  private void insertPay(String payNo) {
    fixture.update(
        "INSERT INTO"
            + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
            + " VALUES(1,?,?,7,10,'PENDING','TCC',NOW(3),NOW(3))",
        payNo,
        "order-" + payNo);
  }

  private void insertCancel(String payNo, String state, String owner, LocalDateTime leaseUntil) {
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_cancel(id,pay_no,user_id,order_no,amount,state,attempts,next_retry_at,lease_owner,lease_until,created_at,updated_at)"
            + " VALUES(?,?,?,?,10,?,0,DATE_SUB(NOW(3),INTERVAL 1 DAY),?,?,NOW(3),NOW(3))",
        Math.abs(payNo.hashCode()) + 1L,
        payNo,
        7L,
        "order-" + payNo,
        state,
        owner,
        leaseUntil);
  }

  private LocalDateTime lease(String boundary, LocalDateTime now) {
    return switch (boundary) {
      case "expired" -> now.minusDays(1);
      case "equal" -> now;
      case "future" -> now.plusNanos(1_000_000);
      default -> null;
    };
  }

  private List<Map<String, Object>> snapshot(String payNo) {
    return fixture.queryForList("SELECT * FROM pay_tcc_cancel WHERE pay_no=?", payNo);
  }

  private String owner(String payNo) {
    return fixture.queryForObject(
        "SELECT lease_owner FROM pay_tcc_cancel WHERE pay_no=?", String.class, payNo);
  }

  private String paymentState(String payNo) {
    return fixture.queryForObject(
        "SELECT status FROM pay_record WHERE pay_no=?", String.class, payNo);
  }

  private String cancelState(String payNo) {
    return fixture.queryForObject(
        "SELECT state FROM pay_tcc_cancel WHERE pay_no=?", String.class, payNo);
  }

  private static LocalDateTime hostNow() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
  }

  private static List<Map<String, Object>> originalChecksums() {
    return source.queryForList(
        "CHECKSUM TABLE"
            + " cloudmall_pay.pay_record,cloudmall_pay.pay_debit_intent,cloudmall_pay.pay_callback_log");
  }
}
