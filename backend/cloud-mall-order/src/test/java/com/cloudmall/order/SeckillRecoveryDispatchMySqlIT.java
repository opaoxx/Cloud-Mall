package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.service.impl.SeckillMessageRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;

/** 真实 MySQL 与可变时钟验证逐条认领、发送前租约检查和重试时间。 */
class SeckillRecoveryDispatchMySqlIT {
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

  /** 毫秒对齐的固定初始时间。 */
  private static final LocalDateTime CREATED = LocalDateTime.of(2026, 10, 7, 12, 0);

  @BeforeAll
  static void prepareDatabase() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("需要cloudmall_order测试URL");
    source = jdbc(url);
    sourceFingerprint = fingerprint();
    database = "cm17k_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    databaseCreated = true;
    fixture = jdbc(url.replace("/cloudmall_order", "/" + database));
    mapper = new SeckillMessageRecoveryMapper(fixture);
    mapper.ensureTable();
  }

  @BeforeEach
  void clearFixture() {
    fixture.execute("TRUNCATE TABLE seckill_message_recovery");
    clock = new MutableClock(CREATED);
    mapper = spy(new SeckillMessageRecoveryMapper(fixture));
    ReflectionTestUtils.setField(mapper, "completionClock", clock);
    stock = mock(StockClient.class);
    rabbit = mock(RabbitTemplate.class);
    sent = new ArrayList<>();
    recovery = new SeckillMessageRecovery(mapper, rabbit, new ObjectMapper(), stock);
    ReflectionTestUtils.setField(recovery, "recoveryClock", clock);
    when(stock.seckillReservationFact(anyString(), eq("cloud-mall-order")))
        .thenAnswer(invocation -> ApiResponse.ok(fact(invocation.getArgument(0))));
    sendWithDelay(0, true);
  }

  @AfterAll
  static void cleanup() {
    try {
      if (sourceFingerprint != null) assertEquals(sourceFingerprint, fingerprint());
    } finally {
      if (databaseCreated && database.matches("cm17k_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
        System.out.println(
            "CM17K fixture cleaned; original Order tables/checksums unchanged: " + database);
      }
    }
  }

  /** Service 与 Mapper 共用的可控时钟。 */
  private MutableClock clock;

  /** 外部库存验真替身。 */
  private StockClient stock;

  /** 外部消息发送替身。 */
  private RabbitTemplate rabbit;

  /** 实际恢复调度器。 */
  private SeckillMessageRecovery recovery;

  /** 已调用发送的消息，不能把发送后失效说成未发送。 */
  private List<Message> sent;

  @Test
  void unknownStockStatesRemainRetryableEvenWithoutIdentityFields() throws Exception {
    for (String status : List.of("UNKNOWN", "PROCESSING", "", "missing", "null")) {
      fixture.execute("TRUNCATE TABLE seckill_message_recovery");
      clock.reset(CREATED);
      sent.clear();
      seed(1, "unknown");
      Map<String, Object> before = row(1);
      Map<String, Object> response = new java.util.LinkedHashMap<>();
      if (!"missing".equals(status)) response.put("status", "null".equals(status) ? null : status);
      when(stock.seckillReservationFact(anyString(), anyString()))
          .thenReturn(ApiResponse.ok(response));
      recovery.replayDue();
      assertEquals("VERIFYING", row(1).get("status"), status);
      assertEquals(CREATED.plusSeconds(30), row(1).get("next_retry_at"));
      assertEquals(before.get("payload"), row(1).get("payload"));
      assertEquals(before.get("attempts"), row(1).get("attempts"));
      assertNull(row(1).get("lease_owner"));
      assertTrue(sent.isEmpty());
      clock.advance(30);
      when(stock.seckillReservationFact(anyString(), anyString()))
          .thenReturn(ApiResponse.ok(fact("202610-unknown")));
      recovery.replayDue();
      assertEquals("REPLAYED", row(1).get("status"));
      assertEquals(1, sent.size());
    }
  }

  @Test
  void incompleteAcceptedEvidenceCanRecoverWhenCompleteFactArrives() throws Exception {
    for (String field :
        List.of("orderNo", "activityId", "skuId", "userId", "idempotencyKey", "requestHash")) {
      fixture.execute("TRUNCATE TABLE seckill_message_recovery");
      clock.reset(CREATED);
      sent.clear();
      seed(1, "incomplete");
      Map<String, Object> full = fact("202610-incomplete");
      full.put(
          "status",
          "activityId".equals(field)
              ? "PUBLISHED"
              : "userId".equals(field) ? "ORDER_CREATED" : "ACCEPTED");
      Map<String, Object> partial = new java.util.LinkedHashMap<>(full);
      partial.remove(field);
      when(stock.seckillReservationFact(anyString(), anyString()))
          .thenReturn(ApiResponse.ok(partial));
      recovery.replayDue();
      assertEquals("VERIFYING", row(1).get("status"), field);
      assertTrue(sent.isEmpty());
      clock.advance(30);
      when(stock.seckillReservationFact(anyString(), anyString())).thenReturn(ApiResponse.ok(full));
      recovery.replayDue();
      assertEquals("REPLAYED", row(1).get("status"), field);
      assertEquals(1, sent.size());
    }
  }

  @Test
  void blankAcceptedEvidenceIsRetryableButCompleteMismatchStillConflicts() throws Exception {
    for (String field :
        List.of("orderNo", "activityId", "skuId", "userId", "idempotencyKey", "requestHash")) {
      fixture.execute("TRUNCATE TABLE seckill_message_recovery");
      seed(1, "blank");
      Map<String, Object> partial = fact("202610-blank");
      partial.put(field, " ");
      when(stock.seckillReservationFact(anyString(), anyString()))
          .thenReturn(ApiResponse.ok(partial));
      recovery.replayDue();
      assertEquals("VERIFYING", row(1).get("status"), field);
      assertTrue(sent.isEmpty());
    }
    fixture.execute("TRUNCATE TABLE seckill_message_recovery");
    seed(1, "mismatch");
    Map<String, Object> mismatch = fact("202610-mismatch");
    mismatch.put("userId", 999);
    when(stock.seckillReservationFact(anyString(), anyString()))
        .thenReturn(ApiResponse.ok(mismatch));
    recovery.replayDue();
    assertEquals("CONFLICT", row(1).get("status"));
    assertTrue(sent.isEmpty());
    assertTrue(mapper.findDue(50, CREATED.plusDays(1)).isEmpty());
  }

  @Test
  void negativeStockEvidenceStaysQuarantinedAndInvalidEventDoesNotWaitForStock() throws Exception {
    for (String status : List.of("NOT_FOUND", "REJECTED")) {
      fixture.execute("TRUNCATE TABLE seckill_message_recovery");
      seed(1, "negative");
      when(stock.seckillReservationFact(anyString(), anyString()))
          .thenReturn(ApiResponse.ok(Map.of("status", status)));
      recovery.replayDue();
      assertEquals("QUARANTINED", row(1).get("status"));
      assertEquals(CREATED.plusMinutes(5), row(1).get("next_retry_at"));
      assertTrue(sent.isEmpty());
    }
    fixture.execute("TRUNCATE TABLE seckill_message_recovery");
    seed(1, "invalid");
    fixture.update(
        "UPDATE seckill_message_recovery SET payload=JSON_REMOVE(payload,'$.requestHash') WHERE id=1");
    Map<String, Object> before = row(1);
    clearInvocations(stock);
    recovery.replayDue();
    assertEquals("CONFLICT", row(1).get("status"));
    assertEquals(before.get("payload"), row(1).get("payload"));
    verifyNoInteractions(stock);
    assertTrue(sent.isEmpty());
  }

  @Test
  void slowFirstVerificationDoesNotGiveTheSecondRecordAnExpiredLease() throws Exception {
    seed(1, "first");
    seed(2, "second");
    when(stock.seckillReservationFact(anyString(), eq("cloud-mall-order")))
        .thenAnswer(
            invocation -> {
              String orderNo = invocation.getArgument(0);
              if (orderNo.endsWith("first")) clock.advance(40);
              else
                assertEquals(
                    CREATED.plusSeconds(70),
                    fixture.queryForObject(
                        "SELECT lease_until FROM seckill_message_recovery WHERE id=2",
                        LocalDateTime.class));
              return ApiResponse.ok(fact(orderNo));
            });
    recovery.replayDue();
    assertEquals(1, sent.size());
    assertEquals(eventId("second"), sent.get(0).getMessageProperties().getMessageId());
    assertEquals("REPLAY_PENDING", row(1).get("status"));
    assertEquals("REPLAYED", row(2).get("status"));
    assertEquals(
        CREATED.plusSeconds(42),
        fixture.queryForObject(
            "SELECT next_retry_at FROM seckill_message_recovery WHERE id=2", LocalDateTime.class));
  }

  @Test
  void lostLeaseImmediatelyAfterClaimStopsBeforeStockLookup() throws Exception {
    for (String fault : List.of("expired", "replacement", "conflict", "resolved")) {
      fixture.execute("TRUNCATE TABLE seckill_message_recovery");
      seed(1, fault);
      var snapshot = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      doAnswer(
              invocation -> {
                int claimed = (Integer) invocation.callRealMethod();
                if (claimed == 1) {
                  invalidate(fault);
                  snapshot.set(row(1));
                }
                return claimed;
              })
          .when(mapper)
          .claim(anyLong(), anyString(), any(LocalDateTime.class), any(LocalDateTime.class));
      recovery.replayDue();
      verifyNoInteractions(stock);
      assertTrue(sent.isEmpty());
      assertEquals(snapshot.get(), row(1), fault);
    }
  }

  @Test
  void leaseOrTerminalChangeDuringStockRpcStopsBeforePublish() throws Exception {
    for (String fault : List.of("expired", "replacement", "conflict", "resolved")) {
      fixture.execute("TRUNCATE TABLE seckill_message_recovery");
      clock.reset(CREATED);
      seed(1, fault);
      var snapshot = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      when(stock.seckillReservationFact(anyString(), eq("cloud-mall-order")))
          .thenAnswer(
              invocation -> {
                Map<String, Object> fact = fact(invocation.getArgument(0));
                invalidate(fault);
                snapshot.set(row(1));
                return ApiResponse.ok(fact);
              });
      recovery.replayDue();
      assertTrue(sent.isEmpty(), fault);
      assertEquals(snapshot.get(), row(1), fault);
    }
  }

  @Test
  void retrySchedulesStartAtActualVerificationOrConfirmCompletion() throws Exception {
    for (String scenario : List.of("ack", "nack", "missing-stock")) {
      fixture.execute("TRUNCATE TABLE seckill_message_recovery");
      clock.reset(CREATED);
      sent.clear();
      seed(1, scenario);
      when(stock.seckillReservationFact(anyString(), eq("cloud-mall-order")))
          .thenAnswer(
              invocation -> {
                Map<String, Object> fact = fact(invocation.getArgument(0));
                if (scenario.equals("missing-stock")) {
                  clock.advance(5);
                  fact.put("status", "NOT_FOUND");
                }
                return ApiResponse.ok(fact);
              });
      sendWithDelay(5, scenario.equals("ack"));
      String originalPayload = String.valueOf(row(1).get("payload"));
      recovery.replayDue();
      boolean missing = scenario.equals("missing-stock");
      assertEquals(missing ? 0 : 1, sent.size());
      assertEquals(missing ? "QUARANTINED" : "REPLAYED", row(1).get("status"));
      assertEquals(
          CREATED.plusSeconds(missing ? 305 : 7),
          fixture.queryForObject(
              "SELECT next_retry_at FROM seckill_message_recovery WHERE id=1",
              LocalDateTime.class));
      assertEquals(
          CREATED.plusSeconds(5),
          fixture.queryForObject(
              "SELECT updated_at FROM seckill_message_recovery WHERE id=1", LocalDateTime.class));
      assertNull(row(1).get("lease_owner"));
      assertEquals(originalPayload, row(1).get("payload"));
      if (!missing)
        assertEquals(
            originalPayload,
            new String(sent.get(0).getBody(), java.nio.charset.StandardCharsets.UTF_8));
    }
  }

  @Test
  void expirationDuringAnAlreadyStartedSendCannotBeRecalledButCannotComplete() throws Exception {
    seed(1, "in-flight");
    sendWithDelay(31, true);
    recovery.replayDue();
    assertEquals(1, sent.size());
    assertEquals("REPLAY_PENDING", row(1).get("status"));
    assertEquals(0, ((Number) row(1).get("attempts")).intValue());
    assertNotNull(row(1).get("lease_owner"));
  }

  @Test
  void exactlyExpiredLeaseIsDueAndCanBeReclaimed() throws Exception {
    seed(1, "boundary");
    assertEquals(1, mapper.claim(1, "previous", CREATED, CREATED.minusSeconds(1)));
    assertEquals(1, mapper.findDue(50, CREATED).size());
    assertEquals(1, mapper.claim(1, "replacement", CREATED.plusSeconds(30), CREATED));
    assertEquals("replacement", row(1).get("lease_owner"));
  }

  @Test
  void leaseReadFailureBeforeOrAfterStockLeavesClaimUntouchedAndRetryable() throws Exception {
    for (int failedRead : new int[] {1, 2}) {
      fixture.execute("TRUNCATE TABLE seckill_message_recovery");
      clock.reset(CREATED);
      clearInvocations(stock);
      sent.clear();
      seed(1, "read-error");
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      var snapshot = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      org.mockito.stubbing.Answer<Object> failedQuery =
          invocation -> {
            if (reads.incrementAndGet() == failedRead) {
              snapshot.set(row(1));
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "fixture lease read unavailable");
            }
            return invocation.callRealMethod();
          };
      doAnswer(failedQuery).when(mapper).findClaimed(anyLong(), anyString());
      doAnswer(failedQuery).when(mapper).hasActiveLease(anyLong(), anyString());
      recovery.replayDue();
      assertTrue(sent.isEmpty());
      assertEquals(snapshot.get(), row(1));
      verify(stock, times(failedRead - 1))
          .seckillReservationFact(anyString(), eq("cloud-mall-order"));
      clock.advance(31);
      assertEquals(
          1, mapper.claim(1, "next-owner", CREATED.plusSeconds(61), CREATED.plusSeconds(31)));
    }
  }

  @Test
  void leaseReadThatReturnsAfterDeadlineCannotAuthorizeStockOrPublish() throws Exception {
    for (int delayedRead : new int[] {1, 2}) {
      fixture.execute("TRUNCATE TABLE seckill_message_recovery");
      clock.reset(CREATED);
      clearInvocations(stock);
      sent.clear();
      JdbcTemplate delayed = spy(fixture);
      mapper = new SeckillMessageRecoveryMapper(delayed);
      ReflectionTestUtils.setField(mapper, "completionClock", clock);
      recovery = new SeckillMessageRecovery(mapper, rabbit, new ObjectMapper(), stock);
      ReflectionTestUtils.setField(recovery, "recoveryClock", clock);
      var reads = new java.util.concurrent.atomic.AtomicInteger();
      var snapshot = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      org.mockito.stubbing.Answer<Object> delayedQuery =
          invocation -> {
            Object result = invocation.callRealMethod();
            if (reads.incrementAndGet() == delayedRead) {
              snapshot.set(row(1));
              clock.advance(31);
            }
            return result;
          };
      doAnswer(delayedQuery)
          .when(delayed)
          .queryForList(anyString(), anyLong(), anyString(), any(LocalDateTime.class));
      doAnswer(delayedQuery)
          .when(delayed)
          .queryForList(
              anyString(),
              eq(LocalDateTime.class),
              anyLong(),
              anyString(),
              any(LocalDateTime.class));
      seed(1, "slow-read");
      recovery.replayDue();
      assertTrue(sent.isEmpty());
      assertEquals(snapshot.get(), row(1));
      verify(stock, times(delayedRead - 1))
          .seckillReservationFact(anyString(), eq("cloud-mall-order"));
    }
  }

  @Test
  void staleScanCannotLowerAttemptsCompletedByAnotherWorkerBeforeClaim() throws Exception {
    seed(1, "stale-attempts");
    fixture.update("UPDATE seckill_message_recovery SET attempts=2,status='REPLAYED' WHERE id=1");
    doAnswer(
            invocation -> {
              Object scanned = invocation.callRealMethod();
              assertEquals(
                  1, mapper.claim(1, "interleaved-owner", CREATED.plusSeconds(30), CREATED));
              mapper.markReplayed(1, "interleaved-owner", 3, CREATED.plusSeconds(8), null);
              clock.advance(10);
              return scanned;
            })
        .when(mapper)
        .findDue(anyInt(), any(LocalDateTime.class));
    recovery.replayDue();
    assertEquals(1, sent.size());
    assertEquals(4, ((Number) row(1).get("attempts")).intValue());
    assertEquals(
        CREATED.plusSeconds(26),
        fixture.queryForObject(
            "SELECT next_retry_at FROM seckill_message_recovery WHERE id=1", LocalDateTime.class));
  }

  @Test
  void maximumAttemptsSaturatesWithoutNegativeCounterOrBackoffOverflow() throws Exception {
    seed(1, "maximum-attempts");
    fixture.update("UPDATE seckill_message_recovery SET attempts=? WHERE id=1", Integer.MAX_VALUE);
    recovery.replayDue();
    assertEquals(1, sent.size());
    assertEquals(Integer.MAX_VALUE, ((Number) row(1).get("attempts")).intValue());
    assertEquals(
        CREATED.plusSeconds(256),
        fixture.queryForObject(
            "SELECT next_retry_at FROM seckill_message_recovery WHERE id=1", LocalDateTime.class));
    assertNull(row(1).get("lease_owner"));
  }

  @Test
  void negativeStoredAttemptsCannotBeResetOrPublished() throws Exception {
    seed(1, "negative-attempts");
    fixture.update("UPDATE seckill_message_recovery SET attempts=-1 WHERE id=1");
    Map<String, Object> before = row(1);
    recovery.replayDue();
    verifyNoInteractions(stock);
    assertTrue(sent.isEmpty());
    assertEquals(-1, ((Number) row(1).get("attempts")).intValue());
    assertEquals(before.get("payload"), row(1).get("payload"));
    assertEquals(before.get("status"), row(1).get("status"));
    assertNotNull(row(1).get("lease_owner"));
  }

  @Test
  void directCompletionCannotDecreaseCurrentCountOrRepairNegativeFacts() throws Exception {
    seed(1, "monotonic-completion");
    fixture.update("UPDATE seckill_message_recovery SET attempts=6 WHERE id=1");
    assertEquals(1, mapper.claim(1, "owner", CREATED.plusSeconds(30), CREATED));
    Map<String, Object> before = row(1);
    for (int staleCount : new int[] {3, -1}) {
      mapper.markReplayed(1, "owner", staleCount, CREATED.plusDays(1), "stale");
      assertEquals(before, row(1));
    }
    fixture.update("UPDATE seckill_message_recovery SET attempts=-1 WHERE id=1");
    before = row(1);
    mapper.markReplayed(1, "owner", 0, CREATED.plusDays(1), "do not guess zero");
    assertEquals(before, row(1));
  }

  private void seed(long id, String suffix) throws Exception {
    String orderNo = "202610-" + suffix;
    String hash = SeckillRequestFingerprint.hash(1, 2, 3, 1, "{}");
    Map<String, Object> event =
        new java.util.LinkedHashMap<>(
            Map.of(
                "eventId",
                eventId(suffix),
                "orderNo",
                orderNo,
                "activityId",
                1,
                "skuId",
                2,
                "userId",
                3,
                "idempotencyKey",
                "key:" + orderNo,
                "addressSnapshot",
                "{}",
                "requestHash",
                hash,
                "occurredAt",
                "2026-10-01T12:00:00+08:00"));
    mapper.saveDlq(
        id,
        eventId(suffix),
        hash,
        new ObjectMapper().writeValueAsString(event),
        "REPLAY_PENDING",
        CREATED.minusMinutes(1));
  }

  private Map<String, Object> fact(String orderNo) throws Exception {
    String payload =
        fixture.queryForObject(
            "SELECT payload FROM seckill_message_recovery WHERE event_id=?",
            String.class,
            SeckillRequestFingerprint.eventId(orderNo));
    Map<String, Object> fact =
        new ObjectMapper()
            .readValue(
                payload,
                new com.fasterxml.jackson.core.type.TypeReference<
                    java.util.LinkedHashMap<String, Object>>() {});
    fact.put("status", "ACCEPTED");
    return fact;
  }

  private String eventId(String suffix) {
    return SeckillRequestFingerprint.eventId("202610-" + suffix);
  }

  private Map<String, Object> row(long id) {
    return fixture.queryForMap("SELECT * FROM seckill_message_recovery WHERE id=?", id);
  }

  private void invalidate(String fault) {
    switch (fault) {
      case "expired" -> clock.advance(31);
      case "replacement" ->
          fixture.update(
              "UPDATE seckill_message_recovery SET lease_owner='replacement' WHERE id=1");
      case "conflict" ->
          mapper.saveDlq(
              99,
              String.valueOf(row(1).get("event_id")),
              String.valueOf(row(1).get("request_hash")),
              "{\"different\":true}",
              "REPLAY_PENDING",
              CREATED);
      case "resolved" ->
          mapper.markResolved(
              String.valueOf(row(1).get("event_id")),
              "202610-resolved",
              3L,
              "key:202610-resolved",
              String.valueOf(row(1).get("request_hash")),
              CREATED);
      default -> throw new IllegalArgumentException("unknown fixture fault");
    }
  }

  private void sendWithDelay(int seconds, boolean ack) {
    doAnswer(
            invocation -> {
              sent.add(invocation.getArgument(2));
              clock.advance(seconds);
              CorrelationData correlation = invocation.getArgument(3);
              correlation.getFuture().set(new CorrelationData.Confirm(ack, "fixture"));
              return null;
            })
        .when(rabbit)
        .send(anyString(), anyString(), any(Message.class), any(CorrelationData.class));
  }

  /** 两个业务组件共享的时钟，模拟 RPC/发送期间耗时而不真实等待。 */
  private static final class MutableClock extends java.time.Clock {
    /** 当前模拟瞬时。 */
    private final java.util.concurrent.atomic.AtomicReference<java.time.Instant> current;

    /** Clock 接口携带的显示时区。 */
    private final java.time.ZoneId zone;

    private MutableClock(LocalDateTime initial) {
      this(
          new java.util.concurrent.atomic.AtomicReference<>(
              initial.toInstant(java.time.ZoneOffset.ofHours(8))),
          java.time.ZoneOffset.ofHours(8));
    }

    private MutableClock(
        java.util.concurrent.atomic.AtomicReference<java.time.Instant> current,
        java.time.ZoneId zone) {
      this.current = current;
      this.zone = zone;
    }

    private void advance(long seconds) {
      current.updateAndGet(value -> value.plusSeconds(seconds));
    }

    private void reset(LocalDateTime value) {
      current.set(value.toInstant(java.time.ZoneOffset.ofHours(8)));
    }

    /** 返回当前模拟瞬时。 */
    @Override
    public java.time.Instant instant() {
      return current.get();
    }

    /** 返回测试时区。 */
    @Override
    public java.time.ZoneId getZone() {
      return zone;
    }

    /** 切换时区但继续共享同一个模拟瞬时。 */
    @Override
    public java.time.Clock withZone(java.time.ZoneId zone) {
      return new MutableClock(current, zone);
    }
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
