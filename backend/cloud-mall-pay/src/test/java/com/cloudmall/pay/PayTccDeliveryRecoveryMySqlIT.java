package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.*;

import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.service.impl.delivery.PayTccDeliveryStore;
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

/** 真实 MySQL 交付恢复扫描与租约 CAS 验证，不注册 Seata 分支或写入真实资金。 */
class PayTccDeliveryRecoveryMySqlIT {
  /** 隔离库原库连接。 */
  private static JdbcTemplate source;

  /** 测试隔离库连接。 */
  private static JdbcTemplate fixture;

  /** 关闭全部外部发现、Seata 和恢复调度的 Pay 上下文。 */
  private static ConfigurableApplicationContext application;

  /** 仅允许删除本测试创建的数据库。 */
  private static String database;

  /** 真实交付恢复 Mapper。 */
  private static PayTccDeliveryMapper deliveries;

  /** 真实 Spring 事务代理交付存储器。 */
  private static PayTccDeliveryStore store;

  /** 原业务表校验和。 */
  private static List<Map<String, Object>> checksums;

  /** 宿主机当前 Asia/Shanghai 时钟，用于制造明显过期的租约。 */
  private static LocalDateTime hostNow() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
  }

  @BeforeAll
  static void setup() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_pay(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_pay 测试 URL");
    source = new JdbcTemplate(new DriverManagerDataSource(url, "root", "root"));
    checksums = originalChecksums();
    database = "cm16l_delivery_" + UUID.randomUUID().toString().replace("-", "");
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
    application.getBean(PayTccDeliveryMapper.class).ensureTable();
    deliveries = application.getBean(PayTccDeliveryMapper.class);
    store = application.getBean(PayTccDeliveryStore.class);
  }

  @AfterAll
  static void cleanup() {
    if (application != null) application.close();
    if (source != null) assertEquals(checksums, originalChecksums());
    if (database != null && database.matches("cm16l_delivery_[0-9a-f]{32}"))
      source.execute("DROP DATABASE " + database);
  }

  @BeforeEach
  void clear() {
    fixture.update("DELETE FROM pay_tcc_delivery");
    fixture.update("DELETE FROM pay_tcc_execution");
    fixture.update("DELETE FROM pay_record");
  }

  @Test
  void invalidXidHeadCannotStarveTwentyOneValidExecutions() {
    for (int index = 0; index < 21; index++) insertCommitted(index + 1, "bad-" + index, null);
    List<Map<String, Object>> badBefore =
        fixture.queryForList(
            "SELECT * FROM pay_tcc_execution WHERE pay_no LIKE 'bad-%' ORDER BY id");
    for (int index = 0; index < 21; index++)
      insertCommitted(index + 101, "valid-" + index, "xid-valid-" + index);

    List<PayTccExecutionPO> first = deliveries.findMissing(20);
    assertEquals(20, first.size());
    assertEquals("valid-0", first.get(0).payNo);
    for (PayTccExecutionPO execution : first) assertNotNull(store.ensure(execution.payNo));

    List<PayTccExecutionPO> remaining = deliveries.findMissing(20);
    assertEquals(List.of("valid-20"), remaining.stream().map(row -> row.payNo).toList());
    assertEquals(
        20,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_tcc_delivery WHERE pay_no LIKE 'valid-%'", Integer.class));
    assertEquals(
        0,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM pay_tcc_delivery WHERE pay_no LIKE 'bad-%'", Integer.class));
    assertEquals(
        badBefore,
        fixture.queryForList(
            "SELECT * FROM pay_tcc_execution WHERE pay_no LIKE 'bad-%' ORDER BY id"));
  }

  @Test
  void blankAndWhitespaceXidRowsAreExcluded() {
    insertCommitted(1, "blank-xid", null);
    fixture.update("UPDATE pay_tcc_execution SET xid='' WHERE pay_no='blank-xid'");
    assertTrue(deliveries.findMissing(20).isEmpty());
    fixture.update("UPDATE pay_tcc_execution SET xid=' ' WHERE pay_no='blank-xid'");
    assertTrue(deliveries.findMissing(20).isEmpty());
  }

  @Test
  void mapperStateCasAcceptsOnlyStrictlyFutureLease() {
    LocalDateTime sampledNow = hostNow();
    LocalDateTime now = sampledNow.withNano((sampledNow.getNano() / 1_000_000) * 1_000_000);
    for (String boundary : List.of("expired", "equal", "null", "future")) {
      insertDelivery("lease-state-" + boundary, "READY", "owner", lease(boundary, now));
      assertEquals(
          0,
          deliveries.updateState(
              "lease-state-" + boundary,
              "Owner",
              "READY",
              "ORDER_SYNC_UNKNOWN",
              "wrong-token",
              now));
      int changed =
          deliveries.updateState(
              "lease-state-" + boundary, "owner", "READY", "ORDER_SYNC_UNKNOWN", null, now);
      assertEquals("future".equals(boundary) ? 1 : 0, changed, boundary);
      fixture.update("DELETE FROM pay_tcc_delivery WHERE pay_no=?", "lease-state-" + boundary);
    }
  }

  @Test
  void mapperFinishCasRejectsExpiredAndOldOwnerCannotTouchNewLease() {
    LocalDateTime sampledNow = hostNow();
    LocalDateTime now = sampledNow.withNano((sampledNow.getNano() / 1_000_000) * 1_000_000);
    for (String boundary : List.of("expired", "equal", "null", "future")) {
      insertDelivery("lease-finish-" + boundary, "READY", "owner", lease(boundary, now));
      assertEquals(
          0,
          deliveries.finishAttempt(
              "lease-finish-" + boundary, "Owner", now.plusMinutes(1), "case-variant", now));
      int changed =
          deliveries.finishAttempt(
              "lease-finish-" + boundary, "owner", now.plusMinutes(1), null, now);
      assertEquals("future".equals(boundary) ? 1 : 0, changed, boundary);
      fixture.update("DELETE FROM pay_tcc_delivery WHERE pay_no=?", "lease-finish-" + boundary);
    }
    insertDelivery("lease-handoff", "READY", "new-owner", now.plusMinutes(1));
    Map<String, Object> claimed =
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='lease-handoff'");
    assertEquals(
        0, deliveries.updateState("lease-handoff", "old-owner", "READY", "SUCCESS", "late", now));
    assertEquals(
        0, deliveries.finishAttempt("lease-handoff", "old-owner", now.plusMinutes(2), "late", now));
    assertEquals(
        claimed,
        fixture.queryForMap("SELECT * FROM pay_tcc_delivery WHERE pay_no='lease-handoff'"));
  }

  @Test
  void expiredStoreStageLeavesPayAndDeliveryUnchanged() {
    insertCommitted(1, "expired-stage", "xid-expired-stage");
    assertNotNull(store.ensure("expired-stage"));
    assertNotNull(store.claim("expired-stage", "expired-owner"));
    fixture.update(
        "UPDATE pay_tcc_delivery SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE pay_no=?",
        "expired-stage");
    assertFalse(store.stage("expired-stage", "expired-owner", "SUCCESS"));
    assertEquals("PENDING", paymentState("expired-stage"));
    assertEquals("READY", deliveryState("expired-stage"));
  }

  @Test
  void validStageAdvancesPayAndDeliveryTogether() {
    insertCommitted(1, "valid-stage", "xid-valid-stage");
    assertNotNull(store.ensure("valid-stage"));
    assertNotNull(store.claim("valid-stage", "valid-owner"));
    assertTrue(store.stage("valid-stage", "valid-owner", "SUCCESS"));
    assertEquals("SUCCESS", paymentState("valid-stage"));
    assertEquals("SUCCESS", deliveryState("valid-stage"));
  }

  @Test
  void deliveryStateFailureRollsBackPaymentStatus() {
    insertCommitted(1, "trigger-stage", "xid-trigger-stage");
    assertNotNull(store.ensure("trigger-stage"));
    assertNotNull(store.claim("trigger-stage", "trigger-owner"));
    fixture.execute(
        "CREATE TRIGGER fail_delivery_success BEFORE UPDATE ON pay_tcc_delivery FOR EACH ROW BEGIN"
            + " IF NEW.state='SUCCESS' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='delivery"
            + " fixture failure'; END IF; END");
    try {
      assertThrows(
          RuntimeException.class, () -> store.stage("trigger-stage", "trigger-owner", "SUCCESS"));
    } finally {
      fixture.execute("DROP TRIGGER fail_delivery_success");
    }
    assertEquals("PENDING", paymentState("trigger-stage"));
    assertEquals("READY", deliveryState("trigger-stage"));
  }

  private void insertCommitted(long id, String payNo, String xid) {
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
            + " VALUES(?,?,?,?,?,10,?,?, 'COMMITTED','COMMIT',NOW(3),NOW(3),NOW(3))",
        id,
        payNo,
        xid,
        7L,
        "order-" + payNo,
        "tcc-debit:" + payNo,
        payNo);
  }

  private void insertDelivery(String payNo, String state, String owner, LocalDateTime leaseUntil) {
    fixture.update(
        "INSERT INTO"
            + " pay_tcc_delivery(id,pay_no,xid,user_id,order_no,amount,state,refund_operation_key,attempts,next_retry_at,lease_owner,lease_until,created_at,updated_at)"
            + " VALUES(?, ?, ?, 7, ?, 10, ?, ?, 0, NOW(3), ?, ?, NOW(3), NOW(3))",
        Math.abs(payNo.hashCode()) + 1L,
        payNo,
        "xid-" + payNo,
        "order-" + payNo,
        state,
        "refund:" + payNo,
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

  private String paymentState(String payNo) {
    return fixture.queryForObject(
        "SELECT status FROM pay_record WHERE pay_no=?", String.class, payNo);
  }

  private String deliveryState(String payNo) {
    return fixture.queryForObject(
        "SELECT state FROM pay_tcc_delivery WHERE pay_no=?", String.class, payNo);
  }

  private static List<Map<String, Object>> originalChecksums() {
    return source.queryForList(
        "CHECKSUM TABLE"
            + " cloudmall_pay.pay_record,cloudmall_pay.pay_debit_intent,cloudmall_pay.pay_callback_log");
  }
}
