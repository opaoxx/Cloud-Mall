package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.service.impl.SeckillMessageRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.rabbitmq.client.Channel;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.*;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;

/** 使用真实 MySQL JSON 类型验证死信重复保存；不修改原业务库。 */
class SeckillRecoveryJsonMySqlIT {
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
    database = "cm17i_" + UUID.randomUUID().toString().replace("-", "");
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
  }

  @AfterAll
  static void cleanup() {
    try {
      if (sourceFingerprint != null) assertEquals(sourceFingerprint, fingerprint());
    } finally {
      if (databaseCreated && database.matches("cm17i_[0-9a-f]{32}")) {
        source.execute("DROP DATABASE " + database);
        assertEquals(
            0,
            source.queryForObject(
                "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
                Integer.class,
                database));
        System.out.println(
            "CM17I fixture cleaned; original Order tables/checksums unchanged: " + database);
      }
    }
  }

  @Test
  void identicalCompactPayloadPreservesEveryExistingColumnAndTerminalState() {
    long recordId = 1;
    for (String state : List.of("VERIFYING", "RESOLVED", "CONFLICT")) {
      String eventId = "same-" + state;
      String payload = "{\"z\":1,\"a\":{\"b\":2}}";
      assertEquals(
          1, mapper.saveDlq(recordId++, eventId, HASH, payload, "REPLAY_PENDING", CREATED));
      fixture.update(
          "UPDATE seckill_message_recovery SET"
              + " status=?,attempts=7,lease_owner='owner',lease_until=?,next_retry_at=?,last_error='keep"
              + " me' WHERE event_id=?",
          state,
          CREATED.plusYears(2),
          CREATED.plusYears(3),
          eventId);
      Map<String, Object> before = row(eventId);
      assertEquals(
          0,
          mapper.saveDlq(recordId++, eventId, HASH, payload, "QUARANTINED", CREATED.plusHours(1)));
      assertEquals(before, row(eventId));
    }
  }

  @Test
  void keyOrderWhitespaceAndNumericRepresentationFollowMySqlJsonEquality() {
    assertEquivalent("{\"b\":2,\"a\":1}", "{ \"a\": 1, \"b\": 2 }");
    assertEquivalent("{\"v\":1}", "{\"v\":1.0}");
    assertEquivalent("{\"v\":\"中\"}", "{\"v\":\"\\u4e2d\"}");
  }

  @Test
  void structuralDifferencesStillConflictAndKeepFirstPayload() {
    int sequence = 0;
    for (String[] pair :
        List.of(
            new String[] {"{\"v\":1}", "{\"v\":\"1\"}"},
            new String[] {"{\"v\":[1,2]}", "{\"v\":[2,1]}"},
            new String[] {"{\"v\":null}", "{}"},
            new String[] {"{\"occurredAt\":\"2026-10-01\"}", "{\"occurredAt\":\"2026-10-02\"}"})) {
      String eventId = "different-" + sequence++;
      mapper.saveDlq(sequence * 2L, eventId, HASH, pair[0], "REPLAY_PENDING", CREATED);
      Map<String, Object> before = row(eventId);
      assertEquals(
          1,
          mapper.saveDlq(
              sequence * 2L + 1, eventId, HASH, pair[1], "REPLAY_PENDING", CREATED.plusHours(1)));
      Map<String, Object> after = row(eventId);
      assertEquals("CONFLICT", after.get("status"));
      assertEquals(before.get("payload"), after.get("payload"));
      assertEquals(before.get("request_hash"), after.get("request_hash"));
    }
  }

  @Test
  void hashDifferenceIncludingCaseStillConflictsForEquivalentJson() {
    mapper.saveDlq(1, "hash-case", HASH, "{\"a\":1}", "REPLAY_PENDING", CREATED);
    Map<String, Object> before = row("hash-case");
    assertEquals(
        1,
        mapper.saveDlq(
            2,
            "hash-case",
            HASH.toUpperCase(java.util.Locale.ROOT),
            "{ \"a\": 1 }",
            "REPLAY_PENDING",
            CREATED.plusHours(1)));
    assertEquals("CONFLICT", row("hash-case").get("status"));
    assertEquals(before.get("payload"), row("hash-case").get("payload"));
    assertEquals(HASH, row("hash-case").get("request_hash"));
  }

  @Test
  void malformedJsonFailsRatherThanBecomingAnIdempotentSuccess() {
    mapper.saveDlq(1, "bad-json", HASH, "{\"a\":1}", "REPLAY_PENDING", CREATED);
    Map<String, Object> before = row("bad-json");
    assertThrows(
        DataAccessException.class,
        () -> mapper.saveDlq(2, "bad-json", HASH, "{bad", "REPLAY_PENDING", CREATED.plusHours(1)));
    assertEquals(before, row("bad-json"));
  }

  @Test
  void simultaneousIdenticalEventOnlyCreatesOneNonConflictingRecord() throws Exception {
    var executor = Executors.newFixedThreadPool(2);
    CountDownLatch ready = new CountDownLatch(2);
    CountDownLatch release = new CountDownLatch(1);
    try {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return mapper.saveDlq(
                    1, "race", HASH, "{\"b\":2,\"a\":1}", "REPLAY_PENDING", CREATED);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(release.await(5, TimeUnit.SECONDS));
                return mapper.saveDlq(
                    2, "race", HASH, "{ \"a\":1, \"b\":2 }", "REPLAY_PENDING", CREATED);
              });
      assertTrue(ready.await(5, TimeUnit.SECONDS));
      release.countDown();
      assertEquals(
          List.of(0, 1),
          java.util.stream.Stream.of(
                  first.get(10, TimeUnit.SECONDS), second.get(10, TimeUnit.SECONDS))
              .sorted()
              .toList());
      assertEquals(
          1,
          fixture.queryForObject("SELECT COUNT(*) FROM seckill_message_recovery", Integer.class));
      assertEquals("REPLAY_PENDING", row("race").get("status"));
      assertNull(row("race").get("last_error"));
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(10, TimeUnit.SECONDS));
    }
  }

  @Test
  void repeatedActualDeadLetterHandlingAcknowledgesWithoutFalseConflict() throws Exception {
    String orderNo = "202610-duplicate";
    String eventId = SeckillRequestFingerprint.eventId(orderNo);
    Map<String, Object> event =
        Map.of(
            "orderNo",
            orderNo,
            "eventId",
            eventId,
            "eventType",
            "SECKILL_ORDER_REQUEST",
            "activityId",
            1,
            "skuId",
            2,
            "userId",
            3,
            "idempotencyKey",
            "key",
            "addressSnapshot",
            "{}",
            "requestHash",
            SeckillRequestFingerprint.hash(1, 2, 3, 1, "{}"),
            "occurredAt",
            "2026-10-01T12:00:00+08:00");
    byte[] bytes = new ObjectMapper().writeValueAsBytes(event);
    Channel channel = mock(Channel.class);
    var recovery =
        new SeckillMessageRecovery(
            mapper, mock(RabbitTemplate.class), new ObjectMapper(), mock(StockClient.class));
    doAnswer(
            invocation -> {
              assertEquals(
                  1,
                  fixture.queryForObject(
                      "SELECT COUNT(*) FROM seckill_message_recovery WHERE event_id=?",
                      Integer.class,
                      eventId));
              return null;
            })
        .when(channel)
        .basicAck(anyLong(), eq(false));
    MessageProperties firstProperties = new MessageProperties();
    firstProperties.setDeliveryTag(23);
    recovery.consumeDeadLetter(new Message(bytes, firstProperties), channel);
    Map<String, Object> before = row(eventId);
    MessageProperties secondProperties = new MessageProperties();
    secondProperties.setDeliveryTag(24);
    recovery.consumeDeadLetter(new Message(bytes, secondProperties), channel);
    verify(channel).basicAck(23, false);
    verify(channel).basicAck(24, false);
    verify(channel, never()).basicNack(anyLong(), anyBoolean(), anyBoolean());
    assertEquals("REPLAY_PENDING", row(eventId).get("status"));
    assertEquals(before, row(eventId));
  }

  private void assertEquivalent(String first, String second) {
    String eventId = UUID.randomUUID().toString();
    long recordId = Math.abs(UUID.randomUUID().getMostSignificantBits());
    assertEquals(1, mapper.saveDlq(recordId, eventId, HASH, first, "REPLAY_PENDING", CREATED));
    Map<String, Object> before = row(eventId);
    assertEquals(
        0,
        mapper.saveDlq(
            recordId + 1, eventId, HASH, second, "REPLAY_PENDING", CREATED.plusHours(1)));
    assertEquals(before, row(eventId));
  }

  private Map<String, Object> row(String eventId) {
    return fixture.queryForMap("SELECT * FROM seckill_message_recovery WHERE event_id=?", eventId);
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
