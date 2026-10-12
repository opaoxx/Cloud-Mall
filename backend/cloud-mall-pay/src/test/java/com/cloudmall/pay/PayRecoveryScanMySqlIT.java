package com.cloudmall.pay;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.cloudmall.pay.domain.po.PayDebitIntentPO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import java.sql.Connection;
import java.sql.DriverManager;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;

/** 显式运行的真实 MySQL 扫描回归；夹具仅存在于当前连接的临时表，不修改业务表。 */
class PayRecoveryScanMySqlIT {
  /** 固定业务时间，使到期与租约边界可以精确断言。 */
  private static final LocalDateTime NOW = LocalDateTime.of(2026, 10, 6, 12, 0);

  /** 临时表所属的唯一物理连接，关闭后由 MySQL 自动清除。 */
  private Connection connection;

  /** 只访问当前连接临时表的 SQL 测试夹具。 */
  private JdbcTemplate fixture;

  /** 被验证的生产 Mapper，执行真实扫描和领取 SQL。 */
  private PayDebitIntentMapper mapper;

  @BeforeEach
  void prepareSessionTables() throws Exception {
    String databaseUrl = System.getProperty("cloudmall.mysql.test.url");
    if (databaseUrl == null || databaseUrl.isBlank()) {
      throw new IllegalStateException("显式运行此 IT 时必须提供 cloudmall.mysql.test.url");
    }
    connection =
        DriverManager.getConnection(
            databaseUrl,
            System.getProperty("cloudmall.mysql.test.username", "root"),
            System.getProperty("cloudmall.mysql.test.password", "root"));
    fixture = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
    String paymentTable =
        (String) fixture.queryForMap("SHOW CREATE TABLE pay_record").get("Create Table");
    String intentTable =
        (String) fixture.queryForMap("SHOW CREATE TABLE pay_debit_intent").get("Create Table");
    fixture.execute(paymentTable.replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE"));
    fixture.execute(intentTable.replaceFirst("CREATE TABLE", "CREATE TEMPORARY TABLE"));
    mapper = new PayDebitIntentMapper(fixture);
  }

  @AfterEach
  void closeSession() throws Exception {
    if (connection != null) connection.close();
  }

  @Test
  void unsupportedHeadCannotStarveMultipleBatchesOfLegacyWork() {
    String[][] modes = {
      {"TCC", "TCC"},
      {"LEGACY", "TCC"},
      {"TCC", "LEGACY"},
      {"FUTURE", "LEGACY"},
      {"LEGACY", "FUTURE"},
      {null, "LEGACY"},
      {"legacy", "LEGACY"},
      {"LEGACY", "legacy"},
      {"LEGACY ", "LEGACY"},
      {"LEGACY", ""}
    };
    for (int index = 0; index < 60; index++) {
      String[] pair = modes[index % modes.length];
      insert(index + 1, "blocked-" + index, pair[0], pair[1], "INIT", NOW.minusDays(1), null);
    }
    for (int index = 0; index < 51; index++) {
      insert(101 + index, "ready-" + index, "LEGACY", "LEGACY", "INIT", NOW.minusHours(1), null);
    }
    List<Map<String, Object>> before = blockedSnapshot();

    List<PayDebitIntentPO> firstBatch = mapper.findDue(NOW, 50);

    assertEquals(50, firstBatch.size());
    assertEquals("ready-0", firstBatch.get(0).payNo);
    assertEquals("ready-49", firstBatch.get(49).payNo);
    for (PayDebitIntentPO intent : firstBatch) {
      assertEquals(1, mapper.claim(intent.payNo, "test-worker", NOW.plusSeconds(30), NOW));
    }
    assertEquals(List.of("ready-50"), payNos(mapper.findDue(NOW, 50)));
    for (int index = 0; index < 60; index++) {
      assertEquals(0, mapper.claim("blocked-" + index, "test-worker", NOW.plusSeconds(30), NOW));
    }
    assertEquals(before, blockedSnapshot());
  }

  @Test
  void supportedScanPreservesDueLeaseStatusOrderingAndLimit() {
    insert(1, "future", "LEGACY", "LEGACY", "INIT", NOW.plusSeconds(1), null);
    insert(2, "leased", "LEGACY", "LEGACY", "INIT", NOW.minusDays(1), NOW.plusSeconds(1));
    insert(3, "success", "LEGACY", "LEGACY", "SUCCESS", NOW.minusDays(1), null);
    insert(4, "failed", "LEGACY", "LEGACY", "FAILED", NOW.minusDays(1), null);
    insert(5, "refunded", "LEGACY", "LEGACY", "REFUNDED", NOW.minusDays(1), null);
    insert(6, "expired-lease", "LEGACY", "LEGACY", "DEBITED", NOW.minusSeconds(1), NOW);
    insert(7, "unknown", "LEGACY", "LEGACY", "ORDER_SYNC_UNKNOWN", NOW, null);
    insert(8, "refund", "LEGACY", "LEGACY", "REFUND_PENDING", NOW, null);
    insert(9, "initial", "LEGACY", "LEGACY", "INIT", NOW, null);

    assertEquals(List.of("expired-lease", "unknown"), payNos(mapper.findDue(NOW, 2)));
    assertEquals(List.of("expired-lease"), payNos(mapper.findDue(NOW, 0)));
    assertEquals(
        List.of("expired-lease", "unknown", "refund", "initial"), payNos(mapper.findDue(NOW, 50)));
  }

  @Test
  void recordLosingEligibilityAfterScanCannotAcquireLease() {
    insert(1, "changed", "LEGACY", "LEGACY", "INIT", NOW, null);
    assertEquals(List.of("changed"), payNos(mapper.findDue(NOW, 50)));
    fixture.update("UPDATE pay_record SET debit_mode='TCC' WHERE pay_no='changed'");
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM pay_debit_intent");

    assertEquals(0, mapper.claim("changed", "test-worker", NOW.plusSeconds(30), NOW));

    assertTrue(mapper.findDue(NOW, 50).isEmpty());
    assertEquals(before, fixture.queryForMap("SELECT * FROM pay_debit_intent"));
  }

  /** 新环扫描只轮转主键，负/零/最大值不做加法并保留到期/租约/模式资格。 */
  @Test
  void ringScanKeepsEligibilityAndSignedPrimaryKeyBoundaries() {
    insert(-9, "negative", "LEGACY", "LEGACY", "INIT", NOW, null);
    insert(-3, "future", "LEGACY", "LEGACY", "INIT", NOW.plusSeconds(1), null);
    insert(0, "leased", "LEGACY", "LEGACY", "INIT", NOW, NOW.plusSeconds(1));
    insert(1, "done", "LEGACY", "LEGACY", "SUCCESS", NOW, null);
    insert(2, "ready", "LEGACY", "LEGACY", "INIT", NOW, null);
    insert(3, "expired", "LEGACY", "LEGACY", "REFUND_PENDING", NOW, NOW);
    insert(4, "blocked", "TCC", "LEGACY", "INIT", NOW, null);
    insert(Long.MAX_VALUE, "maximum", "LEGACY", "LEGACY", "DEBITED", NOW, null);
    assertEquals(List.of("negative", "ready"), payNos(mapper.findDueAfter(NOW, null, 2)));
    assertEquals(List.of("negative"), payNos(mapper.findDueAfter(NOW, Long.MIN_VALUE, 0)));
    assertEquals(List.of("ready", "expired"), payNos(mapper.findDueAfter(NOW, 0L, 2)));
    assertEquals(List.of("expired", "maximum"), payNos(mapper.findDueAfter(NOW, 2L, 50)));
    assertTrue(mapper.findDueAfter(NOW, Long.MAX_VALUE, 50).isEmpty());
    fixture.update("UPDATE pay_debit_intent SET lease_until=NULL WHERE id=0");
    assertEquals(List.of("leased", "ready"), payNos(mapper.findDueAfter(NOW, -9L, 2)));
  }

  /** 两份记录使用同一支付键；null 支付模式代表缺少支付单的孤立意图。 */
  private void insert(
      long identifier,
      String payNo,
      String recordMode,
      String intentMode,
      String status,
      LocalDateTime dueAt,
      LocalDateTime leaseUntil) {
    if (recordMode != null) {
      fixture.update(
          "INSERT INTO"
              + " pay_record(id,pay_no,order_no,user_id,amount,status,debit_mode,created_at,updated_at)"
              + " VALUES(?,?,?,7,10,'PENDING',?,?,?)",
          identifier,
          payNo,
          "order-" + payNo,
          recordMode,
          NOW,
          NOW);
    }
    fixture.update(
        "INSERT INTO pay_debit_intent(id,pay_no,order_no,user_id,amount,status,debit_mode,attempts,"
            + "next_retry_at,lease_owner,lease_until,last_error,created_at,updated_at)"
            + " VALUES(?,?,?,7,10,?,?,2,?,'original-owner',?,'original-error',?,?)",
        identifier,
        payNo,
        "order-" + payNo,
        status,
        intentMode,
        dueAt,
        leaseUntil,
        NOW,
        NOW);
  }

  /** 保存所有隔离行的完整持久状态，验证扫描和拒绝没有隐式写入。 */
  private List<Map<String, Object>> blockedSnapshot() {
    return fixture.queryForList(
        "SELECT * FROM pay_debit_intent WHERE pay_no LIKE 'blocked-%' ORDER BY id");
  }

  /** 提取顺序和批量边界，避免只检查数量而漏掉错误记录。 */
  private List<String> payNos(List<PayDebitIntentPO> intents) {
    return intents.stream().map(intent -> intent.payNo).collect(Collectors.toList());
  }
}
