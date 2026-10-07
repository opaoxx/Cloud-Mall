package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 使用真实 MySQL 验证恢复完成写的租约、归属和终态保护。 */
class SeckillRecoveryLeaseMySqlIT {
  /** 原订单库只读事实入口，建删库只针对自有随机名称。 */
  private static JdbcTemplate source;

  /** 原库表清单与数据校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 自有隔离库的 SQL 入口。 */
  private static JdbcTemplate fixture;

  /** 真实恢复台账 Mapper。 */
  private static SeckillMessageRecoveryMapper mapper;

  /** 当前测试套件的独立数据库。 */
  private static String database;

  /** 只有本套件确实建库成功才允许清理。 */
  private static boolean databaseCreated;

  /** 固定 hash，仅测试 Mapper 精确比较，不在 SQL 层重新解释业务字段。 */
  private static final String HASH = "a".repeat(64);

  /** 毫秒对齐的固定初始时间。 */
  private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 7, 12, 0);

  @BeforeAll
  static void prepareDatabase() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("需要cloudmall_order测试URL");
    source = jdbc(url);
    sourceFingerprint = fingerprint();
    database = "cm17j_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    databaseCreated = true;
    fixture = jdbc(url.replace("/cloudmall_order", "/" + database));
    mapper = new SeckillMessageRecoveryMapper(fixture);
    mapper.ensureTable();
    org.springframework.test.util.ReflectionTestUtils.setField(
        mapper,
        "completionClock",
        java.time.Clock.fixed(
            CREATED.toInstant(java.time.ZoneOffset.ofHours(8)), java.time.ZoneOffset.ofHours(8)));
  }

  @BeforeEach
  void clearFixture() {
    fixture.execute("TRUNCATE TABLE seckill_message_recovery");
    clockAt(CREATED);
  }

  @AfterAll
  static void cleanup() {
    try {
      if (sourceFingerprint != null) assertEquals(sourceFingerprint, fingerprint());
    } finally {
      if (databaseCreated && database.matches("cm17j_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
        System.out.println(
            "CM17J fixture cleaned; original Order tables/checksums unchanged: " + database);
      }
    }
  }

  @Test
  void expiredOwnerCannotCompleteOrChangeRetryMetadata() {
    seed("REPLAY_PENDING", CREATED.minusNanos(1_000_000));
    assertBothCompletionsIgnored("Worker-Token");
  }

  @Test
  void exactExpiryNullLeaseAndDifferentOwnerTokensAreRejected() {
    seed("VERIFYING", CREATED);
    assertBothCompletionsIgnored("Worker-Token");
    seed("QUARANTINED", null);
    assertBothCompletionsIgnored("Worker-Token");
    for (String wrong : new String[] {"worker-token", "Worker-Token ", "replacement", null}) {
      seed("REPLAYED", CREATED.plusSeconds(30));
      assertBothCompletionsIgnored(wrong);
    }
  }

  @Test
  void terminalStatesCannotBeOverwrittenEvenWithMatchingOwner() {
    for (String status : List.of("CONFLICT", "RESOLVED")) {
      seed(status, CREATED.plusSeconds(30));
      assertBothCompletionsIgnored("Worker-Token");
    }
  }

  @Test
  void freshClockIsUsedRatherThanStaleRetryTime() {
    seed("REPLAY_PENDING", CREATED.plusSeconds(30));
    clockAt(CREATED.plusMinutes(1));
    assertBothCompletionsIgnored("Worker-Token");
  }

  @Test
  void claimedReplacementOwnsTheLeaseAndOldOwnerCannotReleaseIt() {
    seed("REPLAY_PENDING", CREATED.minusSeconds(1));
    assertEquals(1, mapper.claim(1, "replacement", CREATED.plusSeconds(30), CREATED));
    assertBothCompletionsIgnored("Worker-Token");
    mapper.markReplayed(1, "replacement", 4, CREATED.plusMinutes(2), null);
    assertEquals("REPLAYED", row().get("status"));
    assertNull(row().get("lease_owner"));
    assertNull(row().get("lease_until"));
  }

  @Test
  void realNewDeadLetterConflictWinsOverLateCompletion() {
    seed("REPLAY_PENDING", CREATED.plusSeconds(30));
    assertEquals(
        1, mapper.saveDlq(2, "event", HASH, "{\"changed\":true}", "REPLAY_PENDING", CREATED));
    assertEquals("CONFLICT", row().get("status"));
    assertEquals("Worker-Token", row().get("lease_owner"));
    assertBothCompletionsIgnored("Worker-Token");
  }

  @Test
  void liveOwnerCanReplayOrVerifyEachRecoverableState() {
    for (String sourceState : List.of("REPLAY_PENDING", "REPLAYED", "QUARANTINED", "VERIFYING")) {
      seed(sourceState, CREATED.plusSeconds(30));
      Map<String, Object> before = row();
      mapper.markReplayed(1, "Worker-Token", 4, CREATED.plusMinutes(2), "x".repeat(600));
      Map<String, Object> after = row();
      assertEquals("REPLAYED", after.get("status"));
      assertEquals(4, ((Number) after.get("attempts")).intValue());
      assertEquals("x".repeat(512), after.get("last_error"));
      assertCompleted(before, after, CREATED.plusMinutes(2));
      assertEquals(
          1, mapper.claim(1, "next-owner", CREATED.plusMinutes(3), CREATED.plusMinutes(2)));
      for (String targetState : List.of("QUARANTINED", "VERIFYING", "CONFLICT")) {
        seed(sourceState, CREATED.plusSeconds(30));
        before = row();
        mapper.markVerification(1, "Worker-Token", targetState, "verify", CREATED.plusMinutes(5));
        after = row();
        assertEquals(targetState, after.get("status"));
        assertEquals(3, ((Number) after.get("attempts")).intValue());
        assertEquals("verify", after.get("last_error"));
        assertCompleted(before, after, CREATED.plusMinutes(5));
      }
    }
  }

  @Test
  void unknownVerificationTargetCannotChangeTheRecord() {
    seed("REPLAY_PENDING", CREATED.plusSeconds(30));
    Map<String, Object> before = row();
    for (String target : new String[] {null, "", "RESOLVED", "REPLAY_PENDING", "arbitrary"}) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              mapper.markVerification(
                  1, "Worker-Token", target, "invalid", CREATED.plusMinutes(1)));
      assertEquals(before, row());
    }
  }

  private void assertBothCompletionsIgnored(String owner) {
    Map<String, Object> before = row();
    mapper.markReplayed(1, owner, 99, CREATED.plusDays(1), "late replay");
    assertEquals(before, row(), "markReplayed must not mutate stale or terminal facts");
    mapper.markVerification(1, owner, "QUARANTINED", "late verification", CREATED.plusDays(1));
    assertEquals(before, row(), "markVerification must not mutate stale or terminal facts");
  }

  private void assertCompleted(
      Map<String, Object> before, Map<String, Object> after, LocalDateTime nextRetry) {
    assertNull(after.get("lease_owner"));
    assertNull(after.get("lease_until"));
    assertEquals(
        CREATED,
        fixture.queryForObject(
            "SELECT updated_at FROM seckill_message_recovery WHERE id=1", LocalDateTime.class));
    assertEquals(
        nextRetry,
        fixture.queryForObject(
            "SELECT next_retry_at FROM seckill_message_recovery WHERE id=1", LocalDateTime.class));
    for (String field : List.of("event_id", "request_hash", "payload", "created_at"))
      assertEquals(before.get(field), after.get(field));
  }

  private void seed(String status, LocalDateTime leaseUntil) {
    fixture.execute("TRUNCATE TABLE seckill_message_recovery");
    mapper.saveDlq(1, "event", HASH, "{\"original\":true}", status, CREATED.minusDays(1));
    fixture.update(
        "UPDATE seckill_message_recovery SET"
            + " attempts=3,lease_owner='Worker-Token',lease_until=?,last_error='original' WHERE"
            + " id=1",
        leaseUntil);
  }

  private Map<String, Object> row() {
    return fixture.queryForMap("SELECT * FROM seckill_message_recovery WHERE id=1");
  }

  private void clockAt(LocalDateTime time) {
    org.springframework.test.util.ReflectionTestUtils.setField(
        mapper,
        "completionClock",
        java.time.Clock.fixed(
            time.toInstant(java.time.ZoneOffset.ofHours(8)), java.time.ZoneOffset.ofHours(8)));
  }

  private static JdbcTemplate jdbc(String url) {
    return new JdbcTemplate(
        new DriverManagerDataSource(
            url
                + (url.contains("?") ? "&" : "?")
                + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true&connectTimeout=3000&socketTimeout=5000",
            "root",
            "root"));
  }

  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> result = new ArrayList<>(source.queryForList("SHOW TABLES"));
    for (String table : source.queryForList("SHOW TABLES", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表名");
      result.addAll(source.queryForList("CHECKSUM TABLE " + table));
    }
    return result;
  }
}
