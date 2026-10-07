package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillOrderOutboxDispatcher;
import com.cloudmall.stock.service.impl.SeckillRequestClaimService;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 MySQL 验证 Stock 秒杀受理时间与 outbox envelope 时间稳定性。 */
class SeckillEventTimeMySqlIT {
  /** 原Stock库只读核对连接。 */
  private static JdbcTemplate source;

  /** 原库表清单和校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 每例独立数据库名。 */
  private String database;

  /** 隔离库的真实SQL入口。 */
  private JdbcTemplate fixture;

  /** 隔离库连接地址。 */
  private String isolatedUrl;

  /** 真实受理和outbox持久层。 */
  private StockSqlMapper mapper;

  /** 用于构造原子受理事实的本地事务。 */
  private TransactionTemplate transaction;

  /** 入口固定跨年采样，用于证明新claim单次时间与重试不变。 */
  private static final OffsetDateTime CLAIM_DECEMBER =
      OffsetDateTime.of(2026, 12, 31, 23, 59, 59, 999_500_000, ZoneOffset.ofHours(8));

  @BeforeAll
  static void setupSource() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_stock(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_stock 测试 URL");
    source =
        new JdbcTemplate(new DriverManagerDataSource(withShanghaiTimezone(url), "root", "root"));
    sourceFingerprint = fingerprint();
  }

  @BeforeEach
  void setupFixture() {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    database = "cm17g_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    isolatedUrl = withShanghaiTimezone(sourceUrl.replace("/cloudmall_stock", "/" + database));
    DataSource dataSource = new DriverManagerDataSource(isolatedUrl, "root", "root");
    fixture = new JdbcTemplate(dataSource);
    mapper = new StockSqlMapper(fixture);
    mapper.ensureSeckillLedgerTable();
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
  }

  @AfterEach
  void cleanupFixture() {
    AuthContext.clear();
    if (database != null && database.matches("cm17g_[0-9a-f]{32}")) {
      String dropped = database;
      source.execute("DROP DATABASE " + dropped);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              dropped));
      System.out.println("已清理秒杀事件时间测试隔离库: " + dropped);
      database = null;
    }
  }

  @AfterAll
  static void cleanupSource() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17G original Stock tables and checksums unchanged");
  }

  @Test
  void ambiguousAddressIsRejectedBeforeClaimRedisOrOutboxWrites() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    StockServiceImpl stock = new StockServiceImpl(redis, mapper, rabbit, new ObjectMapper());
    ReflectionTestUtils.setField(
        stock,
        "seckillRequestClaimService",
        transactionProxy(new SeckillRequestClaimService(mapper)));
    AuthContext.set(7L, "USER");
    for (String address :
        List.of(
            "{} {}",
            "{} null",
            "{} garbage",
            "{\"city\":1,\"city\":2}",
            "{\"contact\":{\"name\":1,\"name\":2}}")) {
      SeckillReservationDTO request = request("invalid-address");
      request.addressSnapshot = address;
      var failure =
          assertThrows(com.cloudmall.common.error.BizException.class, () -> stock.seckill(request));
      assertEquals(400, failure.getHttpStatus());
      assertEquals(
          0, fixture.queryForObject("SELECT COUNT(*) FROM seckill_reservation", Integer.class));
      assertEquals(
          0, fixture.queryForObject("SELECT COUNT(*) FROM seckill_order_outbox", Integer.class));
      verifyNoInteractions(redis, rabbit);
    }
  }

  @Test
  void claimPersistsOneMillisecondAndReusesItAfterYearChanges() throws Exception {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    HashOperations<String, Object, Object> operations = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(operations);
    when(operations.entries(anyString())).thenReturn(Map.of());
    StockServiceImpl stock =
        new StockServiceImpl(redis, mapper, mock(RabbitTemplate.class), new ObjectMapper());
    ReflectionTestUtils.setField(
        stock,
        "seckillRequestClaimService",
        transactionProxy(new SeckillRequestClaimService(mapper)));
    ReflectionTestUtils.setField(
        stock, "seckillClaimClock", Clock.fixed(CLAIM_DECEMBER.toInstant(), ZoneOffset.UTC));
    AuthContext.set(7L, "USER");
    SeckillReservationDTO request = request("same-key");
    assertEquals(
        503,
        assertThrows(com.cloudmall.common.error.BizException.class, () -> stock.seckill(request))
            .getHttpStatus());
    Map<String, Object> first =
        fixture.queryForMap(
            "SELECT * FROM seckill_reservation WHERE user_id=7 AND idempotency_key='same-key'");
    assertTrue(String.valueOf(first.get("order_no")).startsWith("202612"));
    var payload = new ObjectMapper().readTree(String.valueOf(first.get("request_payload")));
    assertEquals("2026-12-31T23:59:59.999+08:00", payload.path("occurredAt").asText());
    assertEquals(
        java.time.LocalDateTime.parse("2026-12-31T23:59:59.999"),
        fixture.queryForObject(
            "SELECT created_at FROM seckill_reservation", java.time.LocalDateTime.class));
    ReflectionTestUtils.setField(
        stock,
        "seckillClaimClock",
        Clock.fixed(OffsetDateTime.parse("2027-01-01T00:00:00+08:00").toInstant(), ZoneOffset.UTC));
    assertThrows(com.cloudmall.common.error.BizException.class, () -> stock.seckill(request));
    Map<String, Object> second =
        fixture.queryForMap(
            "SELECT * FROM seckill_reservation WHERE user_id=7 AND idempotency_key='same-key'");
    assertEquals(first, second);
  }

  @Test
  void explicitEventTimeAndIdentitySurviveNackThenRetry() throws Exception {
    String orderNo = "202512-explicit";
    seed(orderNo, "2025-12-31T15:59:59.999Z");
    String original = storedPayload(orderNo);
    List<Map<String, Object>> sent = new ArrayList<>();
    RabbitTemplate rabbit = sender(sent, true);
    SeckillOrderOutboxDispatcher dispatcher = dispatcher(rabbit);
    due(orderNo);
    dispatcher.dispatch();
    assertEquals("UNKNOWN", state(orderNo));
    due(orderNo);
    dispatcher.dispatch();
    assertEquals(2, sent.size());
    assertEquals(sent.get(0), sent.get(1));
    assertEquals("2025-12-31T23:59:59.999+08:00", sent.get(0).get("occurredAt"));
    assertEquals(SeckillRequestFingerprint.eventId(orderNo), sent.get(0).get("eventId"));
    assertEquals(original, storedPayload(orderNo));
    assertEquals("PUBLISHED", state(orderNo));
  }

  @Test
  void legacyPayloadUsesVerifiedClaimTimeWithoutRewritingJson() throws Exception {
    String orderNo = "202512-legacy";
    seed(orderNo, null);
    String original = storedPayload(orderNo);
    List<Map<String, Object>> sent = new ArrayList<>();
    due(orderNo);
    dispatcher(sender(sent, false)).dispatch();
    assertEquals(1, sent.size());
    assertEquals("2025-12-31T23:59:59.999+08:00", sent.get(0).get("occurredAt"));
    assertEquals(original, storedPayload(orderNo));
    assertFalse(new ObjectMapper().readTree(original).has("occurredAt"));
    assertEquals("PUBLISHED", state(orderNo));
  }

  @Test
  void insufficientOrConflictingFactsAreRetriedWithoutPublishing() throws Exception {
    List<Map<String, Object>> sent = new ArrayList<>();
    SeckillOrderOutboxDispatcher dispatcher = dispatcher(sender(sent, false));
    for (String fault :
        List.of("missing", "month", "explicit", "hash", "state", "key", "address")) {
      String orderNo = "202512-" + fault;
      seed(orderNo, fault.equals("explicit") ? "2025-12-31T23:59:58+08:00" : null);
      switch (fault) {
        case "missing" ->
            fixture.update("DELETE FROM seckill_reservation WHERE order_no=?", orderNo);
        case "month" ->
            fixture.update(
                "UPDATE seckill_reservation SET created_at='2027-01-01 00:00:00' WHERE order_no=?",
                orderNo);
        case "hash" ->
            fixture.update(
                "UPDATE seckill_reservation SET request_hash='conflicting-hash' WHERE order_no=?",
                orderNo);
        case "state" ->
            fixture.update(
                "UPDATE seckill_reservation SET status='REJECTED' WHERE order_no=?", orderNo);
        case "key" ->
            fixture.update(
                "UPDATE seckill_order_outbox SET idempotency_key='other-key' WHERE order_no=?",
                orderNo);
        case "address" ->
            fixture.update(
                "UPDATE seckill_order_outbox SET payload=JSON_REMOVE(payload,'$.addressSnapshot')"
                    + " WHERE order_no=?",
                orderNo);
        default -> {}
      }
      due(orderNo);
      dispatcher.dispatch();
      assertTrue(sent.isEmpty(), fault);
      Map<String, Object> row =
          fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo);
      assertEquals("UNKNOWN", row.get("status"), fault);
      assertTrue(
          String.valueOf(row.get("last_error")).startsWith("SECKILL_EVENT_TIME_UNVERIFIED:"),
          fault);
      assertNull(row.get("lease_owner"));
      assertEquals(SeckillRequestFingerprint.eventId(orderNo), row.get("event_id"));
    }
  }

  private void seed(String orderNo, String occurredAt) throws Exception {
    String key = "key:" + orderNo;
    String hash = SeckillRequestFingerprint.hash(11, 7, 7, 1, "{}");
    Map<String, Object> payload =
        new java.util.LinkedHashMap<>(
            Map.of(
                "orderNo",
                orderNo,
                "activityId",
                11L,
                "skuId",
                7L,
                "userId",
                7L,
                "quantity",
                1,
                "idempotencyKey",
                key,
                "addressSnapshot",
                "{}"));
    if (occurredAt != null) payload.put("occurredAt", occurredAt);
    String json = new ObjectMapper().writeValueAsString(payload);
    transaction.execute(
        status -> {
          assertEquals(
              1,
              mapper.insertSeckillPendingIgnore(
                  11,
                  7,
                  7,
                  orderNo,
                  key,
                  json,
                  hash,
                  OffsetDateTime.parse("2025-12-31T23:59:59.999+08:00")));
          assertEquals(
              1,
              mapper.acceptSeckillAndInsertOutbox(
                  7,
                  key,
                  orderNo,
                  SeckillRequestFingerprint.eventId(orderNo),
                  json,
                  hash,
                  OffsetDateTime.now(ZoneOffset.ofHours(8))));
          return null;
        });
  }

  private RabbitTemplate sender(List<Map<String, Object>> sent, boolean nackFirst) {
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    doAnswer(
            invocation -> {
              sent.add(
                  new java.util.LinkedHashMap<>((Map<String, Object>) invocation.getArgument(2)));
              CorrelationData correlation = invocation.getArgument(3);
              correlation
                  .getFuture()
                  .set(new CorrelationData.Confirm(!nackFirst || sent.size() > 1, "fixture"));
              return null;
            })
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    return rabbit;
  }

  private SeckillOrderOutboxDispatcher dispatcher(RabbitTemplate rabbit) {
    return new SeckillOrderOutboxDispatcher(
        mapper,
        mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class),
        mock(StringRedisTemplate.class),
        rabbit,
        new ObjectMapper(),
        new MockEnvironment());
  }

  private void due(String orderNo) {
    fixture.update(
        "UPDATE seckill_order_outbox SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
            + " order_no=?",
        orderNo);
  }

  private String storedPayload(String orderNo) {
    return fixture.queryForObject(
        "SELECT payload FROM seckill_order_outbox WHERE order_no=?", String.class, orderNo);
  }

  private String state(String orderNo) {
    return fixture.queryForObject(
        "SELECT status FROM seckill_order_outbox WHERE order_no=?", String.class, orderNo);
  }

  private SeckillReservationDTO request(String key) {
    SeckillReservationDTO request = new SeckillReservationDTO();
    request.activityId = 11L;
    request.skuId = 7L;
    request.userId = 7L;
    request.idempotencyKey = key;
    request.addressSnapshot = "{\"receiver\":\"小明\"}";
    return request;
  }

  @SuppressWarnings("unchecked")
  private <T> T transactionProxy(T target) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    factory.addAdvice(
        new TransactionInterceptor(
            new DataSourceTransactionManager(fixture.getDataSource()),
            new AnnotationTransactionAttributeSource()));
    return (T) factory.getProxy();
  }

  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> result = new ArrayList<>();
    List<String> tables = source.queryForList("SHOW TABLES FROM cloudmall_stock", String.class);
    result.addAll(source.queryForList("SHOW TABLES FROM cloudmall_stock"));
    for (String table : tables) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法表名: " + table);
      result.addAll(source.queryForList("CHECKSUM TABLE cloudmall_stock." + table));
    }
    return result;
  }

  private static String withShanghaiTimezone(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }
}
