package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillOrderOutboxDispatcher;
import com.cloudmall.stock.service.impl.SeckillRequestClaimService;
import com.cloudmall.stock.service.impl.SeckillReservationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
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
  void claimInsertRejectsLossyColumnValuesInStrictAndNonStrictModes() throws Exception {
    OffsetDateTime created = OffsetDateTime.parse("2026-10-07T12:00:00+08:00");
    try (var connection = fixture.getDataSource().getConnection()) {
      var isolatedJdbc =
          new JdbcTemplate(
              new org.springframework.jdbc.datasource.SingleConnectionDataSource(connection, true));
      var strictMapper = new StockSqlMapper(isolatedJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        isolatedJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (String[] invalid :
            List.of(
                new String[] {"o".repeat(65), "key", "a".repeat(64)},
                new String[] {"order", "k".repeat(129), "a".repeat(64)},
                new String[] {"order", "key", "a".repeat(65)},
                new String[] {null, "key", "a".repeat(64)},
                new String[] {"order", null, "a".repeat(64)},
                new String[] {" ", "key", "a".repeat(64)},
                new String[] {"order", " ", "a".repeat(64)})) {
          isolatedJdbc.execute("DELETE FROM seckill_reservation");
          assertThrows(
              RuntimeException.class,
              () ->
                  strictMapper.insertSeckillPendingIgnore(
                      11, 7, 7, invalid[0], invalid[1], "{}", invalid[2], created));
          assertEquals(
              0,
              isolatedJdbc.queryForObject(
                  "SELECT COUNT(*) FROM seckill_reservation", Integer.class));
        }
        assertThrows(
            RuntimeException.class,
            () ->
                strictMapper.insertSeckillPendingIgnore(
                    11, 7, 7, "order", "key", "{}", "a".repeat(64), null));
        assertEquals(
            0,
            isolatedJdbc.queryForObject("SELECT COUNT(*) FROM seckill_reservation", Integer.class));
      }
    }
  }

  @Test
  void invalidStockKeysFailBeforeClaimOrRedisWhileUnicodeBoundaryRemainsValid() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    HashOperations<String, Object, Object> operations = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(operations);
    when(operations.entries(anyString())).thenReturn(Map.of());
    var stock = StockTestServices.seckillService(redis, mapper, new ObjectMapper());
    ReflectionTestUtils.setField(
        stock,
        "seckillRequestClaimService",
        transactionProxy(new SeckillRequestClaimService(mapper)));
    AuthContext.set(7L, "USER");
    for (String key : List.of("", " ", "k".repeat(129), "😀".repeat(129))) {
      clearInvocations(redis, operations);
      var failure =
          assertThrows(
              com.cloudmall.common.error.BizException.class,
              () ->
                  stock.seckill(
                      request(key), com.cloudmall.common.auth.AuthContext.requireUserId()));
      assertEquals(400, failure.getHttpStatus());
      verifyNoInteractions(redis, operations);
      assertEquals(
          0, fixture.queryForObject("SELECT COUNT(*) FROM seckill_reservation", Integer.class));
    }
    String key = "😀".repeat(128);
    assertEquals(
        503,
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () ->
                    stock.seckill(
                        request(key), com.cloudmall.common.auth.AuthContext.requireUserId()))
            .getHttpStatus());
    assertEquals(
        key,
        fixture.queryForObject("SELECT idempotency_key FROM seckill_reservation", String.class));
  }

  @Test
  void strictClaimInsertKeepsColumnBoundariesAndNullableHistoryContract() {
    OffsetDateTime created = OffsetDateTime.parse("2026-10-07T12:00:00+08:00");
    String orderNo = "o".repeat(64);
    String key = "😀".repeat(128);
    assertEquals(1, mapper.insertSeckillPendingIgnore(11, 7, 7, orderNo, key, null, null, created));
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM seckill_reservation");
    assertEquals(orderNo, before.get("order_no"));
    assertEquals(key, before.get("idempotency_key"));
    assertNull(before.get("request_payload"));
    assertNull(before.get("request_hash"));
    assertEquals(
        0,
        mapper.insertSeckillPendingIgnore(
            11, 7, 7, "candidate", key, "{}", "a".repeat(64), created.plusSeconds(1)));
    assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_reservation"));
    assertThrows(
        org.springframework.dao.DataAccessException.class,
        () ->
            mapper.insertSeckillPendingIgnore(
                11, 7, 8, "bad-json", "bad-json", "{invalid", "a".repeat(64), created));
    assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_reservation"));
  }

  @Test
  void concurrentExactClaimStillReturnsOneOriginalFactAfterStrictInsert() throws Exception {
    var claims = transactionProxy(new SeckillRequestClaimService(mapper));
    var executor = java.util.concurrent.Executors.newFixedThreadPool(2);
    var ready = new java.util.concurrent.CountDownLatch(2);
    var release = new java.util.concurrent.CountDownLatch(1);
    OffsetDateTime created = OffsetDateTime.parse("2026-10-07T12:00:00+08:00");
    String hash = SeckillRequestFingerprint.hash(11, 7, 7, 1, "{}");
    try {
      var first =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return claims.claim(
                    11, 7, 7, "202610-race-first", "strict-race", "{}", hash, created);
              });
      var second =
          executor.submit(
              () -> {
                ready.countDown();
                assertTrue(release.await(5, java.util.concurrent.TimeUnit.SECONDS));
                return claims.claim(
                    11, 7, 7, "202610-race-second", "strict-race", "{}", hash, created);
              });
      assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
      release.countDown();
      assertEquals(
          first.get(15, java.util.concurrent.TimeUnit.SECONDS),
          second.get(15, java.util.concurrent.TimeUnit.SECONDS));
      assertEquals(
          1, fixture.queryForObject("SELECT COUNT(*) FROM seckill_reservation", Integer.class));
    } finally {
      release.countDown();
      executor.shutdownNow();
      assertTrue(executor.awaitTermination(15, java.util.concurrent.TimeUnit.SECONDS));
    }
  }

  @Test
  void claimKeyAliasesCannotReuseOrModifyAnotherExactRequestIdentity() {
    var claims = transactionProxy(new SeckillRequestClaimService(mapper));
    OffsetDateTime created = OffsetDateTime.parse("2026-10-07T12:00:00+08:00");
    String hash = SeckillRequestFingerprint.hash(11, 7, 7, 1, "{}");
    claims.claim(11, 7, 7, "202610-original-key", "Key-Case", "{}", hash, created);
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM seckill_reservation");
    for (String alias : List.of("KEY-CASE", "Kéy-Case", "Key-Case ")) {
      var failure =
          assertThrows(
              com.cloudmall.common.error.BizException.class,
              () ->
                  claims.claim(
                      11, 7, 7, "202610-new-key", alias, "{}", hash, created.plusSeconds(1)));
      assertEquals(409, failure.getHttpStatus());
      assertEquals("SECKILL_IDEMPOTENCY_CONFLICT", failure.getCode());
      assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_reservation"));
    }
  }

  @Test
  void exactClaimKeyReplaysAndDifferentUsersKeepIndependentKeys() {
    var claims = transactionProxy(new SeckillRequestClaimService(mapper));
    OffsetDateTime created = OffsetDateTime.parse("2026-10-07T12:00:00+08:00");
    String hash = SeckillRequestFingerprint.hash(11, 7, 7, 1, "{}");
    Map<String, Object> first =
        claims.claim(11, 7, 7, "202610-exact-first", "same-key", "{}", hash, created);
    assertEquals(
        first,
        claims.claim(
            11, 7, 7, "202610-exact-retry", "same-key", "{}", hash, created.plusSeconds(1)));
    Map<String, Object> other =
        claims.claim(
            11,
            7,
            8,
            "202610-other-user",
            "same-key",
            "{}",
            SeckillRequestFingerprint.hash(11, 7, 8, 1, "{}"),
            created);
    assertEquals("202610-other-user", other.get("order_no"));
    assertEquals(
        2, fixture.queryForObject("SELECT COUNT(*) FROM seckill_reservation", Integer.class));
  }

  @Test
  void publicStockEntryRejectsClaimAliasesBeforeTouchingRedis() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    HashOperations<String, Object, Object> operations = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(operations);
    when(operations.entries(anyString())).thenReturn(Map.of());
    var stock = StockTestServices.seckillService(redis, mapper, new ObjectMapper());
    ReflectionTestUtils.setField(
        stock,
        "seckillRequestClaimService",
        transactionProxy(new SeckillRequestClaimService(mapper)));
    AuthContext.set(7L, "USER");
    SeckillReservationDTO original = request("Key-Case");
    assertEquals(
        503,
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () ->
                    stock.seckill(original, com.cloudmall.common.auth.AuthContext.requireUserId()))
            .getHttpStatus());
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM seckill_reservation");
    for (String alias : List.of("KEY-CASE", "Kéy-Case", "Key-Case ")) {
      clearInvocations(redis, operations);
      var failure =
          assertThrows(
              com.cloudmall.common.error.BizException.class,
              () ->
                  stock.seckill(
                      request(alias), com.cloudmall.common.auth.AuthContext.requireUserId()));
      assertEquals(409, failure.getHttpStatus());
      assertEquals("SECKILL_IDEMPOTENCY_CONFLICT", failure.getCode());
      verifyNoInteractions(redis, operations);
      assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_reservation"));
    }
    assertEquals(
        503,
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () ->
                    stock.seckill(original, com.cloudmall.common.auth.AuthContext.requireUserId()))
            .getHttpStatus());
    assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_reservation"));
  }

  @Test
  void explicitPayloadIdentityConflictsStayUnchangedAndAreNotPublished() throws Exception {
    for (String field : List.of("eventId", "requestHash")) {
      for (Object value : new Object[] {null, "different", 123}) {
        fixture.execute("DELETE FROM seckill_order_outbox");
        fixture.execute("DELETE FROM seckill_reservation");
        String orderNo = "202512-inner-identity";
        seed(orderNo, "2025-12-31T23:59:59.999+08:00");
        var serializer = new ObjectMapper();
        var payload =
            serializer.readValue(
                storedPayload(orderNo),
                new com.fasterxml.jackson.core.type.TypeReference<
                    LinkedHashMap<String, Object>>() {});
        payload.put(field, value);
        fixture.update(
            "UPDATE seckill_order_outbox SET payload=? WHERE order_no=?",
            serializer.writeValueAsString(payload),
            orderNo);
        String original = storedPayload(orderNo);
        String originalHash =
            fixture.queryForObject(
                "SELECT request_hash FROM seckill_order_outbox WHERE order_no=?",
                String.class,
                orderNo);
        due(orderNo);
        List<Map<String, Object>> sent = new ArrayList<>();
        dispatcher(sender(sent, false)).dispatch();
        assertTrue(sent.isEmpty(), field);
        assertEquals("UNKNOWN", state(orderNo));
        assertEquals(original, storedPayload(orderNo));
        assertEquals(
            originalHash,
            fixture.queryForObject(
                "SELECT request_hash FROM seckill_order_outbox WHERE order_no=?",
                String.class,
                orderNo));
        assertTrue(
            fixture
                .queryForObject(
                    "SELECT last_error FROM seckill_order_outbox WHERE order_no=?",
                    String.class,
                    orderNo)
                .startsWith("SECKILL_EVENT_TIME_UNVERIFIED:"));
      }
    }
  }

  @Test
  void matchingPayloadMetadataOrLegacyAbsenceBothPublishWithoutRewritingStorage() throws Exception {
    for (boolean explicit : List.of(false, true)) {
      String orderNo = explicit ? "202512-inner-valid" : "202512-inner-absent";
      seed(orderNo, "2025-12-31T23:59:59.999+08:00");
      if (explicit)
        fixture.update(
            "UPDATE seckill_order_outbox SET payload=JSON_SET(payload,'$.eventId',event_id,'$.requestHash',UPPER(request_hash)) WHERE order_no=?",
            orderNo);
      String original = storedPayload(orderNo);
      due(orderNo);
      List<Map<String, Object>> sent = new ArrayList<>();
      dispatcher(sender(sent, false)).dispatch();
      assertEquals(1, sent.size());
      assertEquals("PUBLISHED", state(orderNo));
      assertEquals(original, storedPayload(orderNo));
      assertEquals(SeckillRequestFingerprint.eventId(orderNo), sent.get(0).get("eventId"));
    }
  }

  @Test
  void preInterruptedDispatcherDoesNotScanMarkersOrOutbox() throws Exception {
    mapper = spy(mapper);
    seedMarkerCandidate(1, "PENDING");
    seed("202512-interrupt-ready", "2025-12-31T23:59:59.999+08:00");
    due("202512-interrupt-ready");
    var before = fixture.queryForList("SELECT * FROM seckill_order_outbox");
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue())
        .thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
    var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    List<Map<String, Object>> sent = new ArrayList<>();
    var dispatcher =
        new SeckillOrderOutboxDispatcher(
            mapper,
            acceptance,
            redis,
            sender(sent, false),
            new ObjectMapper(),
            new MockEnvironment());
    clearInvocations(mapper, redis);
    try {
      Thread.currentThread().interrupt();
      dispatcher.dispatch();
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    verifyNoInteractions(mapper, redis, acceptance);
    assertTrue(sent.isEmpty());
    assertEquals(before, fixture.queryForList("SELECT * FROM seckill_order_outbox"));
  }

  @Test
  void interruptedMarkerReadDoesNotBecomeMissingOrAcceptedEvidence() {
    seedMarkerCandidate(1, "PENDING");
    seedMarkerCandidate(2, "PENDING");
    var before = fixture.queryForList("SELECT * FROM seckill_reservation ORDER BY id");
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    org.springframework.data.redis.core.ValueOperations<String, String> values =
        mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    for (boolean matched : List.of(false, true)) {
      mapper = spy(new StockSqlMapper(fixture));
      clearInvocations(values);
      doAnswer(
              invocation -> {
                Thread.currentThread().interrupt();
                return matched ? "202610-marker-1" : null;
              })
          .when(values)
          .get(markerKey(1));
      var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
      var dispatcher =
          new SeckillOrderOutboxDispatcher(
              mapper,
              acceptance,
              redis,
              mock(RabbitTemplate.class),
              new ObjectMapper(),
              new MockEnvironment());
      try {
        dispatcher.dispatch();
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertEquals(before, fixture.queryForList("SELECT * FROM seckill_reservation ORDER BY id"));
      verifyNoInteractions(acceptance);
      verify(values, never()).get(markerKey(2));
      verify(mapper, never()).findPendingSeckillOutbox(any(), anyInt());
    }
  }

  @Test
  void interruptedOutboxConfirmPreservesClaimAndStopsTheNextRow() throws Exception {
    for (boolean alreadyConfirmed : List.of(false, true)) {
      fixture.execute("DELETE FROM seckill_order_outbox");
      fixture.execute("DELETE FROM seckill_reservation");
      mapper = spy(new StockSqlMapper(fixture));
      seed("202512-interrupt-first", "2025-12-31T23:59:59.999+08:00");
      seed("202512-interrupt-second", "2025-12-31T23:59:59.999+08:00");
      fixture.update(
          "UPDATE seckill_order_outbox SET id=1 WHERE order_no='202512-interrupt-first'");
      fixture.update(
          "UPDATE seckill_order_outbox SET id=2 WHERE order_no='202512-interrupt-second'");
      due("202512-interrupt-first");
      due("202512-interrupt-second");
      Map<String, Object> secondBefore =
          fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=2");
      var firstBeforeSend = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      RabbitTemplate rabbit = mock(RabbitTemplate.class);
      doAnswer(
              invocation -> {
                firstBeforeSend.set(
                    fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=1"));
                CorrelationData correlation = invocation.getArgument(3);
                if (alreadyConfirmed)
                  correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                Thread.currentThread().interrupt();
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
      try {
        dispatcher(rabbit).dispatch();
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertEquals(
          firstBeforeSend.get(),
          fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=1"));
      assertEquals(
          secondBefore, fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=2"));
      verify(rabbit, times(1))
          .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
      verify(mapper, never())
          .recordSeckillOutboxFailure(anyString(), anyString(), anyString(), any(), any());
      verify(mapper, never()).markSeckillOutboxPublished(anyString(), anyString(), any());
    }
  }

  @Test
  void interruptAfterCommittedMarkerWritePreservesItAndStopsTheNextPhase() {
    mapper = spy(mapper);
    seedMarkerCandidate(1, "PENDING");
    seedMarkerCandidate(2, "PENDING");
    Map<String, Object> secondBefore =
        fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=2");
    var committed = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              committed.set(fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1"));
              Thread.currentThread().interrupt();
              return result;
            })
        .when(mapper)
        .markSeckillUnknown(eq(7L), eq("key:202610-marker-1"), anyString(), any());
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue())
        .thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
    var dispatcher =
        new SeckillOrderOutboxDispatcher(
            mapper,
            mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class),
            redis,
            mock(RabbitTemplate.class),
            new ObjectMapper(),
            new MockEnvironment());
    try {
      dispatcher.dispatch();
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertEquals("UNKNOWN", committed.get().get("status"));
    assertEquals(
        committed.get(), fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1"));
    assertEquals(secondBefore, fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=2"));
    verify(mapper, never()).findPendingSeckillOutbox(any(), anyInt());
  }

  @Test
  void interruptionAfterClaimKeepsTheLeaseWithoutReadingOrPublishing() throws Exception {
    mapper = spy(mapper);
    String orderNo = "202512-interrupted-claim";
    seed(orderNo, "2025-12-31T23:59:59.999+08:00");
    due(orderNo);
    var claimed = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
    doAnswer(
            invocation -> {
              Object result = invocation.callRealMethod();
              claimed.set(
                  fixture.queryForMap(
                      "SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
              Thread.currentThread().interrupt();
              return result;
            })
        .when(mapper)
        .claimSeckillOutbox(anyLong(), anyString(), any(), any());
    List<Map<String, Object>> sent = new ArrayList<>();
    try {
      dispatcher(sender(sent, false)).dispatch();
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    assertNotNull(claimed.get().get("lease_owner"));
    assertEquals(
        claimed.get(),
        fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
    assertTrue(sent.isEmpty());
    verify(mapper, never()).findActiveSeckillOutboxLease(anyLong(), anyString(), any());
    verify(mapper, never()).findSeckillByOrderNo(orderNo);
  }

  @Test
  void interruptionWhileLeaseQueryReturnsStopsBeforeFactReadOrPublish() throws Exception {
    for (int stage : List.of(1, 2)) {
      fixture.execute("DELETE FROM seckill_order_outbox");
      fixture.execute("DELETE FROM seckill_reservation");
      mapper = spy(new StockSqlMapper(fixture));
      String orderNo = "202512-interrupted-guard";
      seed(orderNo, "2025-12-31T23:59:59.999+08:00");
      due(orderNo);
      var calls = new java.util.concurrent.atomic.AtomicInteger();
      var before = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      doAnswer(
              invocation -> {
                Object result = invocation.callRealMethod();
                if (calls.incrementAndGet() == stage) {
                  before.set(
                      fixture.queryForMap(
                          "SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
                  Thread.currentThread().interrupt();
                }
                return result;
              })
          .when(mapper)
          .findActiveSeckillOutboxLease(anyLong(), anyString(), any());
      List<Map<String, Object>> sent = new ArrayList<>();
      try {
        dispatcher(sender(sent, false)).dispatch();
        assertTrue(Thread.currentThread().isInterrupted());
      } finally {
        Thread.interrupted();
      }
      assertTrue(sent.isEmpty());
      assertEquals(
          before.get(),
          fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
      verify(mapper, times(stage == 1 ? 0 : 1)).findSeckillByOrderNo(orderNo);
    }
  }

  @Test
  void markerUnknownWriteFailureDoesNotBlockLaterMarkerOrCommittedOutbox() throws Exception {
    seedMarkerCandidate(1, "PENDING");
    seedMarkerCandidate(2, "PENDING");
    seed("202512-after-marker-db", "2025-12-31T23:59:59.999+08:00");
    due("202512-after-marker-db");
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1");
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    org.springframework.data.redis.core.ValueOperations<String, String> values =
        mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(markerKey(2))).thenReturn("202610-marker-2");
    var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    List<Map<String, Object>> sent = new ArrayList<>();
    var dispatcher =
        new SeckillOrderOutboxDispatcher(
            mapper,
            acceptance,
            redis,
            sender(sent, false),
            new ObjectMapper(),
            new MockEnvironment());
    fixture.execute(
        "CREATE TRIGGER fail_marker_unknown BEFORE UPDATE ON seckill_reservation FOR EACH ROW BEGIN IF OLD.id=1 AND NEW.status='UNKNOWN' THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture marker state failure'; END IF; END");
    try {
      assertDoesNotThrow(dispatcher::dispatch);
      assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1"));
      verify(acceptance, never())
          .accept(
              anyLong(),
              anyString(),
              eq("202610-marker-1"),
              anyString(),
              anyString(),
              anyString(),
              any());
      verify(acceptance)
          .accept(
              anyLong(),
              anyString(),
              eq("202610-marker-2"),
              anyString(),
              anyString(),
              anyString(),
              any());
      assertEquals("PUBLISHED", state("202512-after-marker-db"));
      assertEquals(1, sent.size());
    } finally {
      fixture.execute("DROP TRIGGER fail_marker_unknown");
    }
  }

  @Test
  void unknownCommitResponseLossKeepsCommittedFactWithoutRetryingOrGuessing() throws Exception {
    mapper = spy(mapper);
    seedMarkerCandidate(1, "PENDING");
    seed("202512-after-marker-unknown", "2025-12-31T23:59:59.999+08:00");
    due("202512-after-marker-unknown");
    var committed = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
    doAnswer(
            invocation -> {
              invocation.callRealMethod();
              committed.set(fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1"));
              throw new org.springframework.dao.DataAccessResourceFailureException(
                  "fixture response lost after commit");
            })
        .when(mapper)
        .markSeckillUnknown(eq(7L), eq("key:202610-marker-1"), anyString(), any());
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.opsForValue())
        .thenReturn(mock(org.springframework.data.redis.core.ValueOperations.class));
    var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    List<Map<String, Object>> sent = new ArrayList<>();
    var dispatcher =
        new SeckillOrderOutboxDispatcher(
            mapper,
            acceptance,
            redis,
            sender(sent, false),
            new ObjectMapper(),
            new MockEnvironment());
    assertDoesNotThrow(dispatcher::dispatch);
    assertEquals("UNKNOWN", committed.get().get("status"));
    assertEquals(
        committed.get(), fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1"));
    verify(mapper, times(1))
        .markSeckillUnknown(eq(7L), eq("key:202610-marker-1"), anyString(), any());
    verify(mapper, never()).findSeckillByOrderNo("202610-marker-1");
    verifyNoInteractions(acceptance);
    assertEquals("PUBLISHED", state("202512-after-marker-unknown"));
    assertEquals(1, sent.size());
  }

  @Test
  void outboxDatabaseWriteFailuresStayLocalAndDoNotMisclassifyConfirmedPublish() throws Exception {
    for (String stage : List.of("claim", "published", "failure")) {
      fixture.execute("DELETE FROM seckill_order_outbox");
      fixture.execute("DELETE FROM seckill_reservation");
      mapper = spy(new StockSqlMapper(fixture));
      seed("202512-db-first", "2025-12-31T23:59:59.999+08:00");
      seed("202512-db-second", "2025-12-31T23:59:59.999+08:00");
      fixture.update("UPDATE seckill_order_outbox SET id=1 WHERE order_no='202512-db-first'");
      fixture.update("UPDATE seckill_order_outbox SET id=2 WHERE order_no='202512-db-second'");
      due("202512-db-first");
      due("202512-db-second");
      Map<String, Object> before =
          fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=1");
      String condition =
          switch (stage) {
            case "claim" -> "NEW.lease_owner IS NOT NULL AND OLD.lease_owner IS NULL";
            case "published" -> "NEW.status='PUBLISHED'";
            case "failure" -> "NEW.status IN ('UNKNOWN','DEAD')";
            default -> throw new IllegalArgumentException(stage);
          };
      fixture.execute(
          "CREATE TRIGGER fail_first_outbox_update BEFORE UPDATE ON seckill_order_outbox FOR EACH ROW BEGIN IF OLD.id=1 AND ("
              + condition
              + ") THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='fixture outbox update failure'; END IF; END");
      try {
        List<Map<String, Object>> sent = new ArrayList<>();
        assertDoesNotThrow(() -> dispatcher(sender(sent, "failure".equals(stage))).dispatch());
        Map<String, Object> failed =
            fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=1");
        if ("claim".equals(stage)) assertEquals(before, failed);
        else {
          assertEquals("PENDING", failed.get("status"));
          assertNotNull(failed.get("lease_owner"));
          assertNull(failed.get("published_at"));
          assertNull(failed.get("last_error"));
          assertEquals(before.get("next_retry_at"), failed.get("next_retry_at"));
        }
        assertEquals("PUBLISHED", state("202512-db-second"));
        assertEquals("claim".equals(stage) ? 1 : 2, sent.size());
        verify(mapper, times("published".equals(stage) ? 1 : 0))
            .markSeckillOutboxPublished(
                eq(SeckillRequestFingerprint.eventId("202512-db-first")), anyString(), any());
        verify(mapper, times("failure".equals(stage) ? 1 : 0))
            .recordSeckillOutboxFailure(
                eq(SeckillRequestFingerprint.eventId("202512-db-first")),
                anyString(),
                anyString(),
                any(),
                any());
      } finally {
        fixture.execute("DROP TRIGGER fail_first_outbox_update");
      }
    }
  }

  @Test
  void outboxFactReadFailureDoesNotBecomeAPublishFailureOrBlockOtherRows() throws Exception {
    mapper = spy(mapper);
    seed("202512-fact-db-first", "2025-12-31T23:59:59.999+08:00");
    seed("202512-fact-db-second", "2025-12-31T23:59:59.999+08:00");
    fixture.update("UPDATE seckill_order_outbox SET id=1 WHERE order_no='202512-fact-db-first'");
    fixture.update("UPDATE seckill_order_outbox SET id=2 WHERE order_no='202512-fact-db-second'");
    due("202512-fact-db-first");
    due("202512-fact-db-second");
    doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "fixture fact query unavailable"))
        .when(mapper)
        .findSeckillByOrderNo("202512-fact-db-first");
    List<Map<String, Object>> sent = new ArrayList<>();
    assertDoesNotThrow(() -> dispatcher(sender(sent, false)).dispatch());
    Map<String, Object> failed =
        fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=1");
    assertEquals("PENDING", failed.get("status"));
    assertNotNull(failed.get("lease_owner"));
    assertNull(failed.get("last_error"));
    verify(mapper, never())
        .recordSeckillOutboxFailure(
            eq(SeckillRequestFingerprint.eventId("202512-fact-db-first")),
            anyString(),
            anyString(),
            any(),
            any());
    assertEquals("PUBLISHED", state("202512-fact-db-second"));
    assertEquals(1, sent.size());
  }

  @Test
  void scanDatabaseFailureAndUnexpectedClaimErrorsStillPropagate() throws Exception {
    mapper = spy(mapper);
    String orderNo = "202512-scan-boundary";
    seed(orderNo, "2025-12-31T23:59:59.999+08:00");
    due(orderNo);
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo);
    List<Map<String, Object>> sent = new ArrayList<>();
    var dispatcher = dispatcher(sender(sent, false));
    doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "fixture scan unavailable"))
        .when(mapper)
        .findPendingSeckillOutbox(any(), anyInt());
    assertThrows(org.springframework.dao.DataAccessException.class, dispatcher::dispatch);
    doCallRealMethod().when(mapper).findPendingSeckillOutbox(any(), anyInt());
    doThrow(new IllegalStateException("fixture unexpected claim"))
        .when(mapper)
        .claimSeckillOutbox(anyLong(), anyString(), any(), any());
    assertThrows(IllegalStateException.class, dispatcher::dispatch);
    doThrow(new AssertionError("fixture fatal claim"))
        .when(mapper)
        .claimSeckillOutbox(anyLong(), anyString(), any(), any());
    assertThrows(AssertionError.class, dispatcher::dispatch);
    assertTrue(sent.isEmpty());
    assertEquals(
        before,
        fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
  }

  @Test
  void outboxClaimCounterSaturatesWithoutOverflowOrMovingTheIncrement() throws Exception {
    String orderNo = "202512-counter-boundary";
    seed(orderNo, "2025-12-31T23:59:59.999+08:00");
    long outboxId =
        fixture.queryForObject(
            "SELECT id FROM seckill_order_outbox WHERE order_no=?", Long.class, orderNo);
    OffsetDateTime now = OffsetDateTime.parse("2027-01-01T12:00:00+08:00");
    for (int attempts : List.of(0, Integer.MAX_VALUE - 1, Integer.MAX_VALUE)) {
      fixture.update(
          "UPDATE seckill_order_outbox SET attempts=?,status='PENDING',lease_owner=NULL,lease_until=NULL,next_retry_at=? WHERE id=?",
          attempts,
          now,
          outboxId);
      assertEquals(1, mapper.claimSeckillOutbox(outboxId, "first", now.plusSeconds(30), now));
      assertEquals(
          Math.min((long) Integer.MAX_VALUE, (long) attempts + 1),
          fixture.queryForObject(
              "SELECT attempts FROM seckill_order_outbox WHERE id=?", Long.class, outboxId));
      assertEquals(0, mapper.claimSeckillOutbox(outboxId, "concurrent", now.plusSeconds(30), now));
      assertEquals(
          1, mapper.claimSeckillOutbox(outboxId, "next", now.plusSeconds(60), now.plusSeconds(30)));
      assertEquals(
          Math.min((long) Integer.MAX_VALUE, (long) attempts + 2),
          fixture.queryForObject(
              "SELECT attempts FROM seckill_order_outbox WHERE id=?", Long.class, outboxId));
    }
  }

  @Test
  void negativeOutboxCounterDoesNotOccupyScanWindowOrPermitAnyWrites() throws Exception {
    seed("202512-negative-counter", "2025-12-31T23:59:59.999+08:00");
    seed("202512-normal-counter", "2025-12-31T23:59:59.999+08:00");
    OffsetDateTime now = OffsetDateTime.parse("2027-01-01T12:00:00+08:00");
    fixture.update(
        "UPDATE seckill_order_outbox SET id=1,attempts=-1,next_retry_at=? WHERE order_no='202512-negative-counter'",
        now);
    fixture.update(
        "UPDATE seckill_order_outbox SET id=2,next_retry_at=? WHERE order_no='202512-normal-counter'",
        now);
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=1");
    assertEquals(0, mapper.claimSeckillOutbox(1, "negative", now.plusSeconds(30), now));
    assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=1"));
    assertEquals(
        2L, ((Number) mapper.findPendingSeckillOutbox(now, 1).get(0).get("id")).longValue());
    fixture.update(
        "UPDATE seckill_order_outbox SET lease_owner='negative',lease_until=? WHERE id=1",
        now.plusSeconds(30));
    before = fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=1");
    assertNull(mapper.findActiveSeckillOutboxLease(1, "negative", now));
    String eventId = SeckillRequestFingerprint.eventId("202512-negative-counter");
    assertEquals(0, mapper.markSeckillOutboxPublished(eventId, "negative", now));
    assertEquals(
        0,
        mapper.recordSeckillOutboxFailure(eventId, "negative", "error", now.plusSeconds(10), now));
    assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE id=1"));
  }

  @Test
  void saturatedFirstOutboxRetriesWithoutBlockingTheFollowingPublish() throws Exception {
    seed("202512-saturated", "2025-12-31T23:59:59.999+08:00");
    seed("202512-after-saturated", "2025-12-31T23:59:59.999+08:00");
    fixture.update(
        "UPDATE seckill_order_outbox SET id=1,attempts=?,status='DEAD' WHERE order_no='202512-saturated'",
        Integer.MAX_VALUE);
    fixture.update("UPDATE seckill_order_outbox SET id=2 WHERE order_no='202512-after-saturated'");
    due("202512-saturated");
    due("202512-after-saturated");
    List<Map<String, Object>> sent = new ArrayList<>();
    assertDoesNotThrow(() -> dispatcher(sender(sent, true)).dispatch());
    assertEquals(2, sent.size());
    assertEquals("DEAD", state("202512-saturated"));
    assertEquals(
        Integer.MAX_VALUE,
        fixture.queryForObject(
            "SELECT attempts FROM seckill_order_outbox WHERE id=1", Integer.class));
    assertEquals("PUBLISHED", state("202512-after-saturated"));
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT attempts FROM seckill_order_outbox WHERE id=2", Integer.class));
  }

  @Test
  void slowFirstOutboxDoesNotGiveTheNextRowAnAlreadyExpiredLease() throws Exception {
    mapper = spy(mapper);
    seed("202512-slow-first", "2025-12-31T23:59:59.999+08:00");
    seed("202512-slow-second", "2025-12-31T23:59:59.999+08:00");
    fixture.update("UPDATE seckill_order_outbox SET id=1 WHERE order_no='202512-slow-first'");
    fixture.update("UPDATE seckill_order_outbox SET id=2 WHERE order_no='202512-slow-second'");
    due("202512-slow-first");
    due("202512-slow-second");
    var instant =
        new java.util.concurrent.atomic.AtomicReference<>(
            java.time.Instant.parse("2027-01-01T04:00:00Z"));
    doAnswer(
            invocation -> {
              Object fact = invocation.callRealMethod();
              instant.updateAndGet(value -> value.plusSeconds(40));
              return fact;
            })
        .when(mapper)
        .findSeckillByOrderNo("202512-slow-first");
    doAnswer(
            invocation -> {
              assertEquals(
                  java.time.LocalDateTime.parse("2027-01-01T12:01:10"),
                  fixture.queryForObject(
                      "SELECT lease_until FROM seckill_order_outbox WHERE id=2",
                      java.time.LocalDateTime.class));
              return invocation.callRealMethod();
            })
        .when(mapper)
        .findSeckillByOrderNo("202512-slow-second");
    List<Map<String, Object>> sent = new ArrayList<>();
    var dispatcher = dispatcher(sender(sent, false));
    setOutboxClock(dispatcher, instant);
    dispatcher.dispatch();
    assertEquals(1, sent.size());
    assertEquals("202512-slow-second", sent.get(0).get("orderNo"));
    assertEquals("PENDING", state("202512-slow-first"));
    assertEquals("PUBLISHED", state("202512-slow-second"));
  }

  @Test
  void guardQueryErrorsOrReturningAfterDeadlineNeverPublishOrWriteFailure() throws Exception {
    for (int stage : List.of(1, 2)) {
      for (boolean queryFailure : List.of(true, false)) {
        fixture.execute("DELETE FROM seckill_order_outbox");
        fixture.execute("DELETE FROM seckill_reservation");
        mapper = spy(new StockSqlMapper(fixture));
        String orderNo = "202512-slow-guard";
        seed(orderNo, "2025-12-31T23:59:59.999+08:00");
        due(orderNo);
        var instant =
            new java.util.concurrent.atomic.AtomicReference<>(
                java.time.Instant.parse("2027-01-01T04:00:00Z"));
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var snapshot = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
        doAnswer(
                invocation -> {
                  if (calls.incrementAndGet() == stage) {
                    snapshot.set(
                        fixture.queryForMap(
                            "SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
                    if (queryFailure)
                      throw new org.springframework.dao.DataAccessResourceFailureException(
                          "fixture lease read failed");
                    Object deadline = invocation.callRealMethod();
                    instant.updateAndGet(value -> value.plusSeconds(31));
                    return deadline;
                  }
                  return invocation.callRealMethod();
                })
            .when(mapper)
            .findActiveSeckillOutboxLease(anyLong(), anyString(), any());
        List<Map<String, Object>> sent = new ArrayList<>();
        var dispatcher = dispatcher(sender(sent, false));
        setOutboxClock(dispatcher, instant);
        dispatcher.dispatch();
        assertTrue(sent.isEmpty());
        assertEquals(
            snapshot.get(),
            fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
        verify(mapper, times(stage == 1 ? 0 : 1)).findSeckillByOrderNo(orderNo);
        verify(mapper, never())
            .recordSeckillOutboxFailure(anyString(), anyString(), anyString(), any(), any());
      }
    }
  }

  @Test
  void expirationDuringAnOutboxSendCannotUndoItButRejectsCompletion() throws Exception {
    String orderNo = "202512-inflight-lease";
    seed(orderNo, "2025-12-31T23:59:59.999+08:00");
    due(orderNo);
    var instant =
        new java.util.concurrent.atomic.AtomicReference<>(
            java.time.Instant.parse("2027-01-01T04:00:00Z"));
    var beforeSend = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    doAnswer(
            invocation -> {
              beforeSend.set(
                  fixture.queryForMap(
                      "SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
              instant.updateAndGet(value -> value.plusSeconds(31));
              CorrelationData correlation = invocation.getArgument(3);
              correlation.getFuture().set(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    var dispatcher = dispatcher(rabbit);
    setOutboxClock(dispatcher, instant);
    dispatcher.dispatch();
    verify(rabbit, times(1))
        .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
    assertEquals(
        beforeSend.get(),
        fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
  }

  @Test
  void exactlyExpiredOutboxLeaseCanBeScannedAndReclaimed() throws Exception {
    String orderNo = "202512-exact-lease";
    seed(orderNo, "2025-12-31T23:59:59.999+08:00");
    OffsetDateTime now = OffsetDateTime.parse("2027-01-01T12:00:00+08:00");
    fixture.update(
        "UPDATE seckill_order_outbox SET lease_owner='old',lease_until=?,next_retry_at=? WHERE order_no=?",
        now,
        now.minusSeconds(1),
        orderNo);
    List<Map<String, Object>> dueRows = mapper.findPendingSeckillOutbox(now, 10);
    assertEquals(1, dueRows.size());
    long outboxId = ((Number) dueRows.get(0).get("id")).longValue();
    assertNull(mapper.findActiveSeckillOutboxLease(outboxId, "old", now));
    assertEquals(1, mapper.claimSeckillOutbox(outboxId, "new", now.plusSeconds(30), now));
    assertEquals(
        now.plusSeconds(30).toLocalDateTime(),
        mapper.findActiveSeckillOutboxLease(outboxId, "new", now));
    assertNull(mapper.findActiveSeckillOutboxLease(outboxId, "NEW", now));
    assertNull(mapper.findActiveSeckillOutboxLease(outboxId, "new ", now));
  }

  /** 用可推进的Clock替身验证数据库查询/发送耗时，不真实等待租约到期。 */
  private void setOutboxClock(
      SeckillOrderOutboxDispatcher dispatcher,
      java.util.concurrent.atomic.AtomicReference<java.time.Instant> instant) {
    Clock clock = mock(Clock.class);
    when(clock.instant()).thenAnswer(invocation -> instant.get());
    ReflectionTestUtils.setField(dispatcher, "outboxClock", clock);
  }

  @Test
  void lostOutboxLeaseImmediatelyAfterClaimStopsBeforeFactLookupAndPublish() throws Exception {
    for (String fault : List.of("expired", "replacement", "published")) {
      fixture.execute("DELETE FROM seckill_order_outbox");
      fixture.execute("DELETE FROM seckill_reservation");
      mapper = spy(new StockSqlMapper(fixture));
      String orderNo = "202512-guard-before";
      seed(orderNo, "2025-12-31T23:59:59.999+08:00");
      due(orderNo);
      var snapshot = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      doAnswer(
              invocation -> {
                Object result = invocation.callRealMethod();
                invalidateOutbox(orderNo, fault);
                snapshot.set(
                    fixture.queryForMap(
                        "SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
                return result;
              })
          .when(mapper)
          .claimSeckillOutbox(anyLong(), anyString(), any(), any());
      List<Map<String, Object>> sent = new ArrayList<>();
      dispatcher(sender(sent, false)).dispatch();
      assertTrue(sent.isEmpty(), fault);
      verify(mapper, never()).findSeckillByOrderNo(orderNo);
      assertEquals(
          snapshot.get(),
          fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
    }
  }

  @Test
  void lostOutboxLeaseDuringFactLookupStopsBeforePublish() throws Exception {
    for (String fault : List.of("expired", "replacement", "published")) {
      fixture.execute("DELETE FROM seckill_order_outbox");
      fixture.execute("DELETE FROM seckill_reservation");
      mapper = spy(new StockSqlMapper(fixture));
      String orderNo = "202512-guard-after";
      seed(orderNo, "2025-12-31T23:59:59.999+08:00");
      due(orderNo);
      var snapshot = new java.util.concurrent.atomic.AtomicReference<Map<String, Object>>();
      doAnswer(
              invocation -> {
                Object fact = invocation.callRealMethod();
                invalidateOutbox(orderNo, fault);
                snapshot.set(
                    fixture.queryForMap(
                        "SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
                return fact;
              })
          .when(mapper)
          .findSeckillByOrderNo(orderNo);
      List<Map<String, Object>> sent = new ArrayList<>();
      dispatcher(sender(sent, false)).dispatch();
      assertTrue(sent.isEmpty(), fault);
      assertEquals(
          snapshot.get(),
          fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
    }
  }

  private void invalidateOutbox(String orderNo, String fault) {
    switch (fault) {
      case "expired" ->
          fixture.update(
              "UPDATE seckill_order_outbox SET lease_until=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE order_no=?",
              orderNo);
      case "replacement" ->
          fixture.update(
              "UPDATE seckill_order_outbox SET lease_owner='replacement' WHERE order_no=?",
              orderNo);
      case "published" ->
          fixture.update(
              "UPDATE seckill_order_outbox SET status='PUBLISHED' WHERE order_no=?", orderNo);
      default -> throw new IllegalArgumentException(fault);
    }
  }

  @Test
  void outboxCompletionRequiresExactIdentityActiveLeaseAndRetryableState() throws Exception {
    String orderNo = "202512-completion-guard";
    String eventId = SeckillRequestFingerprint.eventId(orderNo);
    OffsetDateTime completion = OffsetDateTime.parse("2026-10-07T12:00:00+08:00");
    seed(orderNo, "2025-12-31T23:59:59.999+08:00");
    for (boolean published : List.of(true, false)) {
      for (String fault :
          List.of(
              "expired",
              "equal",
              "null-lease",
              "owner-case",
              "owner-space",
              "replacement",
              "event-case",
              "event-space",
              "published",
              "other-state",
              "blank-owner",
              "null-owner",
              "null-event")) {
        fixture.update(
            "UPDATE seckill_order_outbox SET status='PENDING',attempts=2,lease_owner='Worker-A',lease_until=?,last_error='original-error',published_at=NULL WHERE order_no=?",
            completion.plusSeconds(30),
            orderNo);
        String submittedEvent = eventId;
        String submittedOwner = "Worker-A";
        switch (fault) {
          case "expired" ->
              fixture.update(
                  "UPDATE seckill_order_outbox SET lease_until=? WHERE order_no=?",
                  completion.minusNanos(1_000_000),
                  orderNo);
          case "equal" ->
              fixture.update(
                  "UPDATE seckill_order_outbox SET lease_until=? WHERE order_no=?",
                  completion,
                  orderNo);
          case "null-lease" ->
              fixture.update(
                  "UPDATE seckill_order_outbox SET lease_until=NULL WHERE order_no=?", orderNo);
          case "owner-case" -> submittedOwner = "WORKER-A";
          case "owner-space" -> submittedOwner += " ";
          case "replacement" ->
              fixture.update(
                  "UPDATE seckill_order_outbox SET lease_owner='new-worker' WHERE order_no=?",
                  orderNo);
          case "event-case" -> submittedEvent = eventId.toUpperCase(java.util.Locale.ROOT);
          case "event-space" -> submittedEvent += " ";
          case "published" ->
              fixture.update(
                  "UPDATE seckill_order_outbox SET status='PUBLISHED' WHERE order_no=?", orderNo);
          case "other-state" ->
              fixture.update(
                  "UPDATE seckill_order_outbox SET status='CANCELLED' WHERE order_no=?", orderNo);
          case "blank-owner" -> {
            submittedOwner = " ";
            fixture.update(
                "UPDATE seckill_order_outbox SET lease_owner=' ' WHERE order_no=?", orderNo);
          }
          case "null-owner" -> submittedOwner = null;
          case "null-event" -> submittedEvent = null;
          default -> throw new IllegalArgumentException(fault);
        }
        Map<String, Object> before =
            fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo);
        int changed =
            published
                ? mapper.markSeckillOutboxPublished(submittedEvent, submittedOwner, completion)
                : mapper.recordSeckillOutboxFailure(
                    submittedEvent,
                    submittedOwner,
                    "late error",
                    completion.plusSeconds(10),
                    completion);
        assertEquals(0, changed, fault);
        assertEquals(
            before,
            fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo),
            fault);
      }
    }
  }

  @Test
  void validOutboxCompletionNormalizesTimeAndLateFailureCannotUndoPublished() throws Exception {
    String orderNo = "202512-completion-valid";
    String eventId = SeckillRequestFingerprint.eventId(orderNo);
    OffsetDateTime completion = OffsetDateTime.parse("2026-10-07T04:00:00.123999999Z");
    java.time.LocalDateTime expected = java.time.LocalDateTime.parse("2026-10-07T12:00:00.123");
    seed(orderNo, "2025-12-31T23:59:59.999+08:00");
    for (String status : List.of("PENDING", "UNKNOWN", "DEAD")) {
      for (boolean published : List.of(true, false)) {
        fixture.update(
            "UPDATE seckill_order_outbox SET status=?,attempts=2,lease_owner='owner',lease_until=?,last_error=NULL,published_at=NULL WHERE order_no=?",
            status,
            expected.plusNanos(1_000_000),
            orderNo);
        Map<String, Object> before =
            fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo);
        int changed =
            published
                ? mapper.markSeckillOutboxPublished(eventId, "owner", completion)
                : mapper.recordSeckillOutboxFailure(
                    eventId, "owner", "failure", completion.plusSeconds(10), completion);
        assertEquals(1, changed);
        Map<String, Object> after =
            fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo);
        assertEquals(published ? "PUBLISHED" : "UNKNOWN", after.get("status"));
        assertEquals(expected, after.get("updated_at"));
        if (published) assertEquals(expected, after.get("published_at"));
        assertNull(after.get("lease_owner"));
        assertNull(after.get("lease_until"));
        for (String field :
            List.of("payload", "request_hash", "event_id", "created_at", "attempts"))
          assertEquals(before.get(field), after.get(field));
        if (published) {
          assertEquals(
              0,
              mapper.recordSeckillOutboxFailure(
                  eventId, "owner", "late", completion.plusSeconds(10), completion));
          assertEquals(
              after,
              fixture.queryForMap("SELECT * FROM seckill_order_outbox WHERE order_no=?", orderNo));
        }
      }
    }
  }

  @Test
  void redisMarkerFailuresDoNotBlockOtherMarkersOrCommittedOutbox() throws Exception {
    seedMarkerCandidate(1, "PENDING");
    seedMarkerCandidate(2, "PENDING");
    seed("202512-ready-redis", "2025-12-31T23:59:59.999+08:00");
    Map<String, Object> failedBefore =
        fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1");
    List<Map<String, Object>> sent = new ArrayList<>();
    RabbitTemplate rabbit = sender(sent, false);
    var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    org.springframework.data.redis.core.ValueOperations<String, String> values =
        mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    for (RuntimeException fault :
        List.of(
            new org.springframework.data.redis.RedisConnectionFailureException(
                "fixture redis unavailable"),
            new com.cloudmall.common.error.BizException(
                "REDIS_SERVICE_UNAVAILABLE", "fixture guarded Redis", 503))) {
      sent.clear();
      clearInvocations(acceptance);
      doThrow(fault).when(values).get(markerKey(1));
      when(values.get(markerKey(2))).thenReturn("202610-marker-2");
      fixture.update(
          "UPDATE seckill_order_outbox SET status='PENDING' WHERE order_no='202512-ready-redis'");
      due("202512-ready-redis");
      var dispatcher =
          new SeckillOrderOutboxDispatcher(
              mapper, acceptance, redis, rabbit, new ObjectMapper(), new MockEnvironment());
      assertDoesNotThrow(dispatcher::dispatch);
      assertEquals(
          failedBefore, fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1"));
      verify(acceptance, never())
          .accept(
              anyLong(),
              anyString(),
              eq("202610-marker-1"),
              anyString(),
              anyString(),
              anyString(),
              any());
      verify(acceptance)
          .accept(
              eq(7L),
              anyString(),
              eq("202610-marker-2"),
              anyString(),
              anyString(),
              anyString(),
              any());
      assertEquals("PUBLISHED", state("202512-ready-redis"));
      assertEquals(1, sent.size());
    }
  }

  @Test
  void totalMarkerReadOutageStillAllowsAlreadyCommittedOutboxDelivery() throws Exception {
    seedMarkerCandidate(1, "PENDING");
    seed("202512-ready-outage", "2025-12-31T23:59:59.999+08:00");
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1");
    List<Map<String, Object>> sent = new ArrayList<>();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    org.springframework.data.redis.core.ValueOperations<String, String> values =
        mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString()))
        .thenThrow(
            new org.springframework.data.redis.RedisConnectionFailureException(
                "fixture total marker outage"));
    var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    var dispatcher =
        new SeckillOrderOutboxDispatcher(
            mapper,
            acceptance,
            redis,
            sender(sent, false),
            new ObjectMapper(),
            new MockEnvironment());
    due("202512-ready-outage");
    assertDoesNotThrow(dispatcher::dispatch);
    assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1"));
    verifyNoInteractions(acceptance);
    assertEquals("PUBLISHED", state("202512-ready-outage"));
    assertEquals(1, sent.size());
  }

  @Test
  void unknownPrefixCannotStarveLaterPendingAndUnknownMarkersAreRevisited() {
    seedMarkerCandidate(1, "UNKNOWN");
    seedMarkerCandidate(2, "UNKNOWN");
    seedMarkerCandidate(3, "PENDING");
    seedMarkerCandidate(4, "UNKNOWN");
    Map<String, Object> firstBefore =
        fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1");
    List<String> visited = new ArrayList<>();
    Map<String, String> markers = new java.util.HashMap<>();
    markers.put(markerKey(3), "202610-marker-3");
    var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    var dispatcher = markerDispatcher(visited, markers, acceptance, 2);
    dispatcher.dispatch();
    assertEquals(List.of(markerKey(1), markerKey(2)), visited);
    visited.clear();
    dispatcher.dispatch();
    assertEquals(List.of(markerKey(3), markerKey(4)), visited);
    verify(acceptance)
        .accept(
            eq(7L),
            eq("key:202610-marker-3"),
            eq("202610-marker-3"),
            anyString(),
            anyString(),
            anyString(),
            any());
    assertEquals(firstBefore, fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=1"));
    markers.put(markerKey(1), "202610-marker-1");
    visited.clear();
    dispatcher.dispatch();
    assertEquals(List.of(markerKey(1), markerKey(2)), visited);
    verify(acceptance)
        .accept(
            eq(7L),
            eq("key:202610-marker-1"),
            eq("202610-marker-1"),
            anyString(),
            anyString(),
            anyString(),
            any());
  }

  @Test
  void partialPagesWrapAfterDeletedCursorAndIncludeNewLowerIdsWithoutExtraCandidates() {
    seedMarkerCandidate(10, "UNKNOWN");
    seedMarkerCandidate(20, "UNKNOWN");
    seedMarkerCandidate(30, "UNKNOWN");
    List<String> visited = new ArrayList<>();
    var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    var dispatcher = markerDispatcher(visited, new java.util.HashMap<>(), acceptance, 2);
    dispatcher.dispatch();
    assertEquals(List.of(markerKey(10), markerKey(20)), visited);
    visited.clear();
    dispatcher.dispatch();
    assertEquals(List.of(markerKey(30)), visited);
    fixture.update("DELETE FROM seckill_reservation WHERE id=30");
    seedMarkerCandidate(5, "UNKNOWN");
    visited.clear();
    dispatcher.dispatch();
    assertEquals(List.of(markerKey(5), markerKey(10)), visited);
    verifyNoInteractions(acceptance);
  }

  /** 在独立测试库固定候选主键，避免随机ID掩盖扫描顺序。 */
  private void seedMarkerCandidate(long candidateId, String status) {
    String orderNo = "202610-marker-" + candidateId;
    mapper.insertSeckillPendingIgnore(
        11,
        7,
        7,
        orderNo,
        "key:" + orderNo,
        "{}",
        "a".repeat(64),
        OffsetDateTime.parse("2026-10-01T12:00:00+08:00"));
    fixture.update(
        "UPDATE seckill_reservation SET id=?,status=? WHERE order_no=?",
        candidateId,
        status,
        orderNo);
  }

  private String markerKey(long candidateId) {
    return "seckill:accepted:11:7:7:key:202610-marker-" + candidateId;
  }

  private SeckillOrderOutboxDispatcher markerDispatcher(
      List<String> visited,
      Map<String, String> markers,
      com.cloudmall.stock.service.impl.SeckillAcceptanceService acceptance,
      int batchLimit) {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    org.springframework.data.redis.core.ValueOperations<String, String> values =
        mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString()))
        .thenAnswer(
            invocation -> {
              String key = invocation.getArgument(0);
              visited.add(key);
              return markers.get(key);
            });
    return new SeckillOrderOutboxDispatcher(
        mapper,
        acceptance,
        redis,
        mock(RabbitTemplate.class),
        new ObjectMapper(),
        new MockEnvironment()
            .withProperty("cloudmall.stock.seckill.outbox.batch-size", String.valueOf(batchLimit)));
  }

  @Test
  void failedQueryKeepsItsPlaceAndFailedAcceptanceDoesNotPinTheNextScan() {
    mapper = spy(mapper);
    seedMarkerCandidate(10, "UNKNOWN");
    seedMarkerCandidate(20, "UNKNOWN");
    seedMarkerCandidate(30, "PENDING");
    List<String> visited = new ArrayList<>();
    var markers = Map.of(markerKey(30), "202610-marker-30");
    var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    doThrow(new IllegalStateException("fixture acceptance unavailable"))
        .when(acceptance)
        .accept(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    Map<String, Object> before =
        fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=30");
    var dispatcher = markerDispatcher(visited, markers, acceptance, 2);
    dispatcher.dispatch();
    assertEquals(List.of(markerKey(10), markerKey(20)), visited);
    visited.clear();
    doThrow(
            new org.springframework.dao.DataAccessResourceFailureException(
                "fixture scan unavailable"))
        .when(mapper)
        .findPendingSeckillReservationsAfter(20, 2);
    assertThrows(org.springframework.dao.DataAccessException.class, dispatcher::dispatch);
    assertTrue(visited.isEmpty());
    doCallRealMethod().when(mapper).findPendingSeckillReservationsAfter(20, 2);
    dispatcher.dispatch();
    assertEquals(List.of(markerKey(30)), visited);
    verify(acceptance, times(1))
        .accept(
            anyLong(),
            anyString(),
            eq("202610-marker-30"),
            anyString(),
            anyString(),
            anyString(),
            any());
    assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_reservation WHERE id=30"));
    visited.clear();
    dispatcher.dispatch();
    assertEquals(List.of(markerKey(10), markerKey(20)), visited);
  }

  @Test
  void missingMarkerForOldLocalDateTimePendingFactMovesToUnknown() {
    OffsetDateTime oldTime = OffsetDateTime.parse("2026-10-07T12:00:00+08:00");
    mapper.insertSeckillPendingIgnore(
        11, 7, 7, "202610-old-marker", "old-marker-key", "{}", "a".repeat(64), oldTime);
    Map<String, Object> before = fixture.queryForMap("SELECT * FROM seckill_reservation");
    assertTrue(before.get("updated_at") instanceof java.time.LocalDateTime);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    var values = mock(org.springframework.data.redis.core.ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    var acceptance = mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    var dispatcher =
        new SeckillOrderOutboxDispatcher(
            mapper, acceptance, redis, rabbit, new ObjectMapper(), new MockEnvironment());
    ReflectionTestUtils.setField(
        dispatcher,
        "markerClock",
        Clock.fixed(oldTime.toInstant().plusSeconds(30), ZoneOffset.UTC));
    dispatcher.dispatch();
    assertEquals(before, fixture.queryForMap("SELECT * FROM seckill_reservation"));
    ReflectionTestUtils.setField(
        dispatcher,
        "markerClock",
        Clock.fixed(oldTime.toInstant().plusSeconds(30).plusMillis(1), ZoneOffset.UTC));
    dispatcher.dispatch();
    Map<String, Object> after = fixture.queryForMap("SELECT * FROM seckill_reservation");
    assertEquals("UNKNOWN", after.get("status"));
    assertEquals("REDIS_MARKER_MISSING_AFTER_GRACE_PERIOD", after.get("last_error"));
    for (String field : List.of("order_no", "request_payload", "request_hash", "created_at"))
      assertEquals(before.get(field), after.get(field));
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM seckill_order_outbox", Integer.class));
    verifyNoInteractions(acceptance);
    verify(rabbit, never())
        .convertAndSend(anyString(), anyString(), any(Object.class), any(CorrelationData.class));
  }

  @Test
  void ambiguousAddressIsRejectedBeforeClaimRedisOrOutboxWrites() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    SeckillReservationServiceImpl stock =
        StockTestServices.seckillService(redis, mapper, new ObjectMapper());
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
            "{\"contact\":{\"name\":1,\"name\":2}}",
            "{\"name\":\"\\uD800\"}",
            "{\"\\uDC00\":1}")) {
      SeckillReservationDTO request = request("invalid-address");
      request.addressSnapshot = address;
      var failure =
          assertThrows(
              com.cloudmall.common.error.BizException.class,
              () -> stock.seckill(request, com.cloudmall.common.auth.AuthContext.requireUserId()));
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
    SeckillReservationServiceImpl stock =
        StockTestServices.seckillService(redis, mapper, new ObjectMapper());
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
        assertThrows(
                com.cloudmall.common.error.BizException.class,
                () -> stock.seckill(request, com.cloudmall.common.auth.AuthContext.requireUserId()))
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
    assertThrows(
        com.cloudmall.common.error.BizException.class,
        () -> stock.seckill(request, com.cloudmall.common.auth.AuthContext.requireUserId()));
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
