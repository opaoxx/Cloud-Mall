package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.SeckillMessageRecoveryMapper;
import com.cloudmall.order.mapper.SeckillOrderDedupMapper;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.Clock;
import java.time.LocalDateTime;
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
import org.springframework.aop.framework.ProxyFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.annotation.AnnotationTransactionAttributeSource;
import org.springframework.transaction.interceptor.TransactionInterceptor;
import org.springframework.transaction.support.TransactionTemplate;

/** 真实 MySQL 验证常规和秒杀订单创建的上海毫秒时间语义。 */
class OrderCreationPrecisionMySqlIT {
  /** 只读核对原订单库的连接。 */
  private static JdbcTemplate source;

  /** 测试前原库表清单和校验和。 */
  private static List<Map<String, Object>> sourceFingerprint;

  /** 当前测试独立创建的数据库名。 */
  private String database;

  /** 隔离库的真实 JDBC 入口。 */
  private JdbcTemplate fixture;

  /** 外层本地 RR 事务，与被测注解事务使用同一数据源。 */
  private TransactionTemplate transaction;

  /** 经注解事务代理的真实订单 Service。 */
  private OrderServiceImpl service;

  /** 隔离库连接地址。 */
  private String isolatedUrl;

  @BeforeAll
  static void setupSource() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("需要 cloudmall_order 测试 URL");
    source =
        new JdbcTemplate(new DriverManagerDataSource(withShanghaiTimezone(url), "root", "root"));
    sourceFingerprint = fingerprint();
  }

  @BeforeEach
  void setupFixture() {
    String sourceUrl = System.getProperty("cloudmall.mysql.test.url");
    database = "cm17f_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    isolatedUrl = withShanghaiTimezone(sourceUrl.replace("/cloudmall_order", "/" + database));
    DataSource dataSource = new DriverManagerDataSource(isolatedUrl, "root", "root");
    fixture = new JdbcTemplate(dataSource);
    cloneFromSource("mall_order_template", "mall_order_template");
    cloneFromSource("mall_order_item_template", "mall_order_item_template");
    cloneFromSource("order_idempotency", "order_idempotency");
    fixture.execute("CREATE TABLE mall_order_202612 LIKE mall_order_template");
    fixture.execute("CREATE TABLE mall_order_202701 LIKE mall_order_template");
    fixture.execute("CREATE TABLE mall_order_item_202612 LIKE mall_order_item_template");
    fixture.execute("CREATE TABLE mall_order_item_202701 LIKE mall_order_item_template");
    OrderReservationIntentMapper intentMapper = new OrderReservationIntentMapper(fixture);
    OrderOutboxMapper outboxMapper = new OrderOutboxMapper(fixture);
    SeckillOrderDedupMapper dedupMapper = new SeckillOrderDedupMapper(fixture);
    SeckillMessageRecoveryMapper recoveryMapper = new SeckillMessageRecoveryMapper(fixture);
    intentMapper.ensureTable();
    outboxMapper.ensureTable();
    dedupMapper.ensureTable();
    recoveryMapper.ensureTable();
    OrderSqlMapper sqlMapper = new OrderSqlMapper(fixture);
    OrderReservationIntentService intentTarget = new OrderReservationIntentService(intentMapper);
    OrderReservationIntentService intentProxy = transactionProxy(intentTarget, dataSource);
    service =
        new OrderServiceImpl(
            mock(StockClient.class),
            mock(ProductClient.class),
            mock(UserClient.class),
            mock(CartClient.class),
            sqlMapper,
            new ObjectMapper());
    ReflectionTestUtils.setField(service, "reservationIntentService", intentProxy);
    ReflectionTestUtils.setField(service, "reservationIntentMapper", intentMapper);
    ReflectionTestUtils.setField(service, "orderOutboxMapper", outboxMapper);
    ReflectionTestUtils.setField(service, "seckillOrderDedupMapper", dedupMapper);
    ReflectionTestUtils.setField(service, "seckillMessageRecoveryMapper", recoveryMapper);
    ReflectionTestUtils.setField(
        service,
        "creationClock",
        Clock.fixed(
            OffsetDateTime.of(2026, 12, 31, 23, 59, 59, 999_500_000, ZoneOffset.ofHours(8))
                .toInstant(),
            ZoneOffset.ofHours(8)));
    configureMocks();
    service = transactionProxy(service, dataSource);
    transaction = new TransactionTemplate(new DataSourceTransactionManager(dataSource));
    transaction.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
    transaction.setReadOnly(false);
  }

  @AfterEach
  void cleanupFixture() {
    AuthContext.clear();
    if (database != null && database.matches("cm17f_[0-9a-f]{32}")) {
      String dropped = database;
      source.execute("DROP DATABASE " + dropped);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              dropped));
      System.out.println("已清理订单创建测试隔离库: " + dropped);
      database = null;
    }
  }

  @AfterAll
  static void cleanupSource() {
    if (source != null) assertEquals(sourceFingerprint, fingerprint());
    System.out.println("CM17F original tables and checksums unchanged");
  }

  @Test
  void normalCreateUsesOneTruncatedShanghaiMillisecondForAllCreatedFields() throws Exception {
    AuthContext.set(7L, "USER");
    ApiResponse<?> response =
        transaction.execute(status -> service.create("idem-normal", normalRequest()));
    assertEquals("0", response.code);
    Map<String, Object> order =
        fixture.queryForMap("SELECT * FROM mall_order_202612 WHERE order_no LIKE '202612%'");
    Timestamp expectedCreated =
        Timestamp.valueOf(LocalDateTime.of(2026, 12, 31, 23, 59, 59, 999_000_000));
    assertEquals(expectedCreated.toLocalDateTime(), local(order.get("created_at")));
    assertEquals(expectedCreated.toLocalDateTime(), local(order.get("updated_at")));
    assertEquals(
        Timestamp.valueOf(LocalDateTime.of(2027, 1, 1, 0, 29, 59, 999_000_000)),
        Timestamp.valueOf(local(order.get("expire_at"))));
    assertEquals(
        expectedCreated,
        fixture.queryForObject(
            "SELECT created_at FROM mall_order_item_202612 WHERE order_no=?",
            Timestamp.class,
            order.get("order_no")));
    assertEquals(
        expectedCreated,
        fixture.queryForObject(
            "SELECT created_at FROM order_reservation_intent WHERE order_no=?",
            Timestamp.class,
            order.get("order_no")));
    Map<String, Object> outbox =
        fixture.queryForMap("SELECT * FROM order_outbox WHERE order_no=?", order.get("order_no"));
    assertEquals(expectedCreated.toLocalDateTime(), local(outbox.get("created_at")));
    assertEquals(expectedCreated.toLocalDateTime(), local(outbox.get("updated_at")));
    assertEquals(expectedCreated.toLocalDateTime(), local(outbox.get("next_retry_at")));
    assertEquals(
        expectedCreated,
        fixture.queryForObject(
            "SELECT created_at FROM order_idempotency WHERE idempotency_key='idem-normal'",
            Timestamp.class));
    assertEquals(
        "COMMITTED",
        fixture.queryForObject(
            "SELECT status FROM order_reservation_intent WHERE order_no=?",
            String.class,
            order.get("order_no")));
    assertPersistedCreation(
        String.valueOf(order.get("order_no")), expectedCreated.toLocalDateTime());
  }

  @Test
  void explicitUtcSeckillInstantUsesJanuaryLocalMillisecondsAndDuplicateIsIdempotent()
      throws Exception {
    ProductClient products = mock(ProductClient.class);
    ProductClient.SkuView sku =
        new ProductClient.SkuView(7L, 9L, "商品", "SKU-7", new BigDecimal("12.30"), Map.of());
    when(products.sku(7L)).thenReturn(ApiResponse.ok(sku));
    ReflectionTestUtils.setField(service, "products", products);
    String address = "{\"receiver\":\"小明\",\"phone\":\"13800138000\"}";
    String requestHash = SeckillRequestFingerprint.hash(11L, 7L, 8L, 1, address);
    Map<String, Object> event =
        Map.of(
            "orderNo",
            "20270101-seckill-utc",
            "eventId",
            "event-utc-1",
            "eventType",
            "SECKILL_ORDER_REQUEST",
            "activityId",
            11L,
            "idempotencyKey",
            "request-utc-1",
            "requestHash",
            requestHash,
            "skuId",
            7L,
            "userId",
            8L,
            "addressSnapshot",
            address,
            "occurredAt",
            "2026-12-31T16:00:00.000900Z");
    transaction.execute(
        status -> {
          service.consumeSeckill(event);
          return null;
        });
    transaction.execute(
        status -> {
          service.consumeSeckill(event);
          return null;
        });
    Map<String, Object> order =
        fixture.queryForMap(
            "SELECT * FROM mall_order_202701 WHERE order_no=?", "20270101-seckill-utc");
    assertEquals(LocalDateTime.of(2027, 1, 1, 0, 0), local(order.get("created_at")));
    assertTrue(String.valueOf(order.get("address_snapshot")).contains("小明"));
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM order_outbox WHERE event_id=?",
            Integer.class,
            "order-timeout:20270101-seckill-utc"));
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM seckill_order_dedup WHERE idempotency_key=?",
            Integer.class,
            "request-utc-1"));
    Map<String, Object> dedup =
        fixture.queryForMap(
            "SELECT * FROM seckill_order_dedup WHERE idempotency_key='request-utc-1'");
    assertEquals(requestHash, dedup.get("request_hash"));
    assertEquals("event-utc-1", dedup.get("event_id"));
    assertEquals("COMMITTED", dedup.get("status"));
    assertPersistedCreation("20270101-seckill-utc", LocalDateTime.of(2027, 1, 1, 0, 0));
  }

  @Test
  void seckillMonthEndDoesNotRoundIntoNextMonthAndExactMillisStayUnchanged() throws Exception {
    for (String[] sample :
        List.of(
            new String[] {
              "202612-end", "2026-12-31T23:59:59.999500+08:00", "2026-12-31T23:59:59.999"
            },
            new String[] {
              "202701-exact", "2027-01-01T00:00:00.123+08:00", "2027-01-01T00:00:00.123"
            })) {
      Map<String, Object> event =
          Map.of(
              "orderNo",
              sample[0],
              "eventId",
              "event:" + sample[0],
              "eventType",
              "SECKILL_ORDER_REQUEST",
              "activityId",
              11L,
              "idempotencyKey",
              "request:" + sample[0],
              "requestHash",
              SeckillRequestFingerprint.hash(11L, 7L, 8L, 1, "{}"),
              "skuId",
              7L,
              "userId",
              8L,
              "addressSnapshot",
              "{}",
              "occurredAt",
              sample[1]);
      service.consumeSeckill(event);
      assertPersistedCreation(sample[0], LocalDateTime.parse(sample[2]));
    }
  }

  @Test
  void invalidOccurredAtFailsWithoutLeavingOrderOrDedupRows() {
    ProductClient products = mock(ProductClient.class);
    when(products.sku(7L))
        .thenReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(7L, 9L, "商品", "SKU-7", BigDecimal.TEN, Map.of())));
    ReflectionTestUtils.setField(service, "products", products);
    Map<String, Object> event =
        Map.of(
            "orderNo", "20261231-seckill-invalid",
            "eventId", "event-invalid",
            "eventType", "SECKILL_ORDER_REQUEST",
            "activityId", 11L,
            "idempotencyKey", "request-invalid",
            "requestHash", SeckillRequestFingerprint.hash(11L, 7L, 8L, 1, "{}"),
            "skuId", 7L,
            "userId", 8L,
            "addressSnapshot", "{}",
            "occurredAt", "not-an-offset-time");
    assertThrows(
        IllegalStateException.class,
        () ->
            transaction.execute(
                status -> {
                  service.consumeSeckill(event);
                  return null;
                }));
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM mall_order_202612", Integer.class));
    assertEquals(
        0, fixture.queryForObject("SELECT COUNT(*) FROM seckill_order_dedup", Integer.class));
    assertEquals(0, fixture.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
  }

  private void assertPersistedCreation(String orderNo, LocalDateTime expected) throws Exception {
    String month = orderNo.substring(0, 6);
    Map<String, Object> order =
        fixture.queryForMap("SELECT * FROM mall_order_" + month + " WHERE order_no=?", orderNo);
    assertEquals(expected, local(order.get("created_at")));
    assertEquals(expected, local(order.get("updated_at")));
    assertEquals(expected.plusMinutes(30), local(order.get("expire_at")));
    assertEquals(
        expected,
        fixture.queryForObject(
            "SELECT created_at FROM mall_order_item_" + month + " WHERE order_no=?",
            LocalDateTime.class,
            orderNo));
    Map<String, Object> outbox =
        fixture.queryForMap("SELECT * FROM order_outbox WHERE order_no=?", orderNo);
    for (String field : List.of("created_at", "updated_at", "next_retry_at"))
      assertEquals(expected, local(outbox.get(field)));
    var payload = new ObjectMapper().readTree(String.valueOf(outbox.get("payload")));
    assertEquals(
        expected.atOffset(ZoneOffset.ofHours(8)),
        OffsetDateTime.parse(payload.path("occurredAt").asText()));
    assertEquals(
        expected.plusMinutes(30).atOffset(ZoneOffset.ofHours(8)),
        OffsetDateTime.parse(payload.path("expireAt").asText()));
    assertEquals("order-timeout:" + orderNo, payload.path("eventId").asText());
  }

  private LocalDateTime local(Object value) {
    return value instanceof Timestamp timestamp
        ? timestamp.toLocalDateTime()
        : (LocalDateTime) value;
  }

  private CreateOrderDTO normalRequest() {
    CreateOrderDTO request = new CreateOrderDTO();
    request.addressId = 9L;
    com.cloudmall.order.domain.dto.OrderItemDTO item =
        new com.cloudmall.order.domain.dto.OrderItemDTO();
    item.skuId = 7L;
    item.quantity = 1;
    request.items = List.of(item);
    return request;
  }

  @Test
  void ambiguousAddressCannotCreateAnOrderUsingTheOldCanonicalHash() {
    Map<String, Object> event = timeEvent("202612-address-integrity");
    event.put("occurredAt", "2026-12-31T23:59:59.999+08:00");
    event.put("addressSnapshot", "{} {}");
    assertThrows(IllegalStateException.class, () -> service.consumeSeckill(event));
    for (String table :
        List.of(
            "mall_order_202612", "mall_order_item_202612", "seckill_order_dedup", "order_outbox")) {
      assertEquals(0, fixture.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class));
    }
    verifyNoInteractions(ReflectionTestUtils.getField(service, "products"));
  }

  @Test
  void firstDeliveryWithoutVerifiableTimeRollsBackBeforeProductLookup() {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    int index = 0;
    for (Object invalid : new Object[] {null, "", "  ", 123L, "invalid-date"}) {
      Map<String, Object> event = timeEvent("202612-invalid-time-" + index++);
      event.put("occurredAt", invalid);
      assertTimeRejected(event, products);
    }
    assertTimeRejected(timeEvent("202612-missing-time"), products);
  }

  @Test
  void explicitWrongMonthCannotCreateOrderInTheOriginalNumberShard() {
    Map<String, Object> event = timeEvent("202612-wrong-month");
    event.put("occurredAt", "2027-01-01T00:00:00+08:00");
    assertTimeRejected(event, (ProductClient) ReflectionTestUtils.getField(service, "products"));
  }

  @Test
  void committedDuplicateStillSucceedsWhenLegacyRedeliveryHasNoTime() {
    Map<String, Object> event = timeEvent("202612-committed-legacy");
    event.put("occurredAt", "2026-12-31T23:59:59.999500+08:00");
    service.consumeSeckill(event);
    var before =
        fixture.queryForList(
            "CHECKSUM TABLE"
                + " mall_order_202612,mall_order_item_202612,order_outbox,seckill_order_dedup");
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    clearInvocations(products);
    event.remove("occurredAt");
    service.consumeSeckill(event);
    verifyNoInteractions(products);
    assertEquals(
        before,
        fixture.queryForList(
            "CHECKSUM TABLE"
                + " mall_order_202612,mall_order_item_202612,order_outbox,seckill_order_dedup"));
    assertEquals(1, fixture.queryForObject("SELECT COUNT(*) FROM order_outbox", Integer.class));
  }

  @Test
  void deadLetterTimeFailuresKeepStoredPayloadAndQuarantineWithoutRpcOrRepublish()
      throws Exception {
    StockClient stock = mock(StockClient.class);
    org.springframework.amqp.rabbit.core.RabbitTemplate rabbit =
        mock(org.springframework.amqp.rabbit.core.RabbitTemplate.class);
    com.rabbitmq.client.Channel channel = mock(com.rabbitmq.client.Channel.class);
    SeckillMessageRecoveryMapper recoveryMapper =
        (SeckillMessageRecoveryMapper)
            ReflectionTestUtils.getField(service, "seckillMessageRecoveryMapper");
    var recovery =
        new com.cloudmall.order.service.impl.SeckillMessageRecovery(
            recoveryMapper, rabbit, new ObjectMapper(), stock);
    doAnswer(
            invocation -> {
              org.springframework.amqp.rabbit.connection.CorrelationData correlation =
                  invocation.getArgument(3);
              correlation
                  .getFuture()
                  .set(
                      new org.springframework.amqp.rabbit.connection.CorrelationData.Confirm(
                          true, null));
              return null;
            })
        .when(rabbit)
        .send(
            anyString(),
            anyString(),
            any(org.springframework.amqp.core.Message.class),
            any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
    for (String kind : List.of("missing", "wrong-month")) {
      String orderNo = "202612-recovery-" + kind;
      Map<String, Object> event = timeEvent(orderNo);
      if (kind.equals("wrong-month")) event.put("occurredAt", "2027-01-01T00:00:00+08:00");
      Map<String, Object> stockFact = new java.util.LinkedHashMap<>(event);
      stockFact.put("status", "ACCEPTED");
      when(stock.seckillReservationFact(orderNo, "cloud-mall-order"))
          .thenReturn(ApiResponse.ok(stockFact));
      var properties = new org.springframework.amqp.core.MessageProperties();
      properties.setDeliveryTag(17L);
      recovery.consumeDeadLetter(
          new org.springframework.amqp.core.Message(
              new ObjectMapper().writeValueAsBytes(event), properties),
          channel);
      String eventId = String.valueOf(event.get("eventId"));
      Map<String, Object> stored =
          fixture.queryForMap("SELECT * FROM seckill_message_recovery WHERE event_id=?", eventId);
      fixture.update(
          "UPDATE seckill_message_recovery SET next_retry_at=DATE_SUB(NOW(3),INTERVAL 1 DAY) WHERE"
              + " event_id=?",
          eventId);
      recovery.replayDue();
      Map<String, Object> after =
          fixture.queryForMap("SELECT * FROM seckill_message_recovery WHERE event_id=?", eventId);
      assertEquals("QUARANTINED", after.get("status"));
      assertTrue(String.valueOf(after.get("last_error")).contains("ORDER_SECKILL_TIME_UNVERIFIED"));
      assertEquals(stored.get("payload"), after.get("payload"));
      assertEquals(stored.get("request_hash"), after.get("request_hash"));
      assertNull(after.get("lease_owner"));
      assertTrue(local(after.get("next_retry_at")).isAfter(LocalDateTime.now().plusMinutes(4)));
    }
    verify(channel, times(2)).basicAck(17L, false);
    verifyNoInteractions(stock);
    verify(rabbit, never())
        .send(
            anyString(),
            anyString(),
            any(org.springframework.amqp.core.Message.class),
            any(org.springframework.amqp.rabbit.connection.CorrelationData.class));
  }

  @Test
  void firstCommittedConsumerResolvesAllMatchingRecoverableStates() throws Exception {
    int sequence = 0;
    for (String state : List.of("REPLAY_PENDING", "REPLAYED", "QUARANTINED", "VERIFYING")) {
      Map<String, Object> event = timeEvent("202612-resolve-first-" + sequence);
      event.put("occurredAt", "2026-12-01T12:00:00+08:00");
      saveRecovery(100 + sequence++, String.valueOf(event.get("eventId")), event, state);
      Map<String, Object> before = recoveryRow(String.valueOf(event.get("eventId")));
      service.consumeSeckill(event);
      Map<String, Object> after = recoveryRow(String.valueOf(event.get("eventId")));
      assertEquals("RESOLVED", after.get("status"), state);
      assertNull(after.get("lease_owner"));
      assertNull(after.get("lease_until"));
      for (String field :
          List.of(
              "payload", "request_hash", "created_at", "attempts", "next_retry_at", "last_error"))
        assertEquals(before.get(field), after.get(field));
    }
  }

  @Test
  void committedDuplicateResolvesHeldRowsWithoutTimeAndAcceptsHexCase() throws Exception {
    int sequence = 0;
    for (String state : List.of("QUARANTINED", "VERIFYING")) {
      Map<String, Object> event = timeEvent("202612-resolve-duplicate-" + sequence);
      event.put("occurredAt", "2026-12-01T12:00:00+08:00");
      service.consumeSeckill(event);
      event.remove("occurredAt");
      event.put(
          "requestHash",
          String.valueOf(event.get("requestHash")).toUpperCase(java.util.Locale.ROOT));
      long recordId = 100 + sequence++;
      String eventId = String.valueOf(event.get("eventId"));
      saveRecovery(recordId, eventId, event, state);
      service.consumeSeckill(event);
      Map<String, Object> resolved = recoveryRow(eventId);
      assertEquals("RESOLVED", resolved.get("status"));
      assertNull(resolved.get("lease_owner"));
      recoveryMapper()
          .markReplayed(recordId, "held-owner", 99, LocalDateTime.now().plusDays(1), "late");
      recoveryMapper()
          .markVerification(
              recordId, "held-owner", "VERIFYING", "late", LocalDateTime.now().plusDays(1));
      assertEquals(resolved, recoveryRow(eventId));
    }
  }

  @Test
  void reusedEventIdCannotResolveAnotherOrderBusinessTuple() throws Exception {
    Map<String, Object> original = timeEvent("202612-original-identity");
    original.put("occurredAt", "2026-12-01T12:00:00+08:00");
    String eventId = String.valueOf(original.get("eventId"));
    saveRecovery(100, eventId, original, "REPLAY_PENDING");
    Map<String, Object> before = recoveryRow(eventId);
    Map<String, Object> incoming = timeEvent("202612-other-identity");
    incoming.put("eventId", eventId);
    incoming.put("occurredAt", "2026-12-01T12:00:00+08:00");
    service.consumeSeckill(incoming);
    assertEquals(
        1,
        fixture.queryForObject(
            "SELECT COUNT(*) FROM mall_order_202612 WHERE order_no=?",
            Integer.class,
            incoming.get("orderNo")));
    assertEquals(before, recoveryRow(eventId));
  }

  @Test
  void exactEventIdentityAndPayloadTypesAreRequiredForResolution() throws Exception {
    int sequence = 0;
    for (String fault :
        List.of(
            "case",
            "trailing",
            "inner-event",
            "user-string",
            "key-number",
            "body-hash",
            "metadata-hash")) {
      Map<String, Object> incoming = timeEvent("202612-resolution-fault-" + sequence);
      incoming.put("occurredAt", "2026-12-01T12:00:00+08:00");
      if (fault.equals("key-number")) incoming.put("idempotencyKey", "123");
      String storedId = String.valueOf(incoming.get("eventId"));
      Map<String, Object> payload = new java.util.LinkedHashMap<>(incoming);
      switch (fault) {
        case "case" -> {
          incoming.put("eventId", storedId.toUpperCase(java.util.Locale.ROOT));
          payload.put("eventId", incoming.get("eventId"));
        }
        case "trailing" -> {
          incoming.put("eventId", storedId + " ");
          payload.put("eventId", incoming.get("eventId"));
        }
        case "inner-event" -> payload.put("eventId", "other-event");
        case "user-string" -> payload.put("userId", "8");
        case "key-number" -> payload.put("idempotencyKey", 123);
        case "body-hash" -> payload.put("requestHash", "b".repeat(64));
        default -> {}
      }
      // 元数据hash与incoming保持一致，body-hash用例单独验证内层hash防线。
      recoveryMapper()
          .saveDlq(
              100 + sequence++,
              storedId,
              String.valueOf(incoming.get("requestHash")),
              new ObjectMapper().writeValueAsString(payload),
              "REPLAY_PENDING",
              LocalDateTime.now());
      if (fault.equals("metadata-hash"))
        fixture.update(
            "UPDATE seckill_message_recovery SET request_hash=? WHERE event_id=?",
            "b".repeat(64),
            storedId);
      Map<String, Object> before = recoveryRow(storedId);
      service.consumeSeckill(incoming);
      assertEquals(before, recoveryRow(storedId), fault);
    }
  }

  @Test
  void conflictResolvedAndUnknownStatesRemainUntouchedByCommittedEvidence() throws Exception {
    int sequence = 0;
    for (String state : List.of("CONFLICT", "RESOLVED", "UNRECOGNIZED")) {
      Map<String, Object> event = timeEvent("202612-resolution-terminal-" + sequence);
      event.put("occurredAt", "2026-12-01T12:00:00+08:00");
      String eventId = String.valueOf(event.get("eventId"));
      saveRecovery(100 + sequence++, eventId, event, state);
      Map<String, Object> before = recoveryRow(eventId);
      service.consumeSeckill(event);
      assertEquals(before, recoveryRow(eventId));
    }
  }

  @Test
  void largeLongUserIdentityUsesExactJsonIntegerRatherThanStringCoercion() throws Exception {
    Map<String, Object> event = timeEvent("202612-resolution-large-user");
    event.put("userId", Long.MAX_VALUE);
    event.put("requestHash", SeckillRequestFingerprint.hash(11, 7, Long.MAX_VALUE, 1, "{}"));
    event.put("occurredAt", "2026-12-01T12:00:00+08:00");
    String eventId = String.valueOf(event.get("eventId"));
    saveRecovery(100, eventId, event, "QUARANTINED");
    assertEquals(
        "UNSIGNED INTEGER",
        fixture.queryForObject(
            "SELECT JSON_TYPE(JSON_EXTRACT(payload,'$.userId')) FROM seckill_message_recovery WHERE"
                + " event_id=?",
            String.class,
            eventId));
    service.consumeSeckill(event);
    assertEquals("RESOLVED", recoveryRow(eventId).get("status"));
    assertEquals(
        Long.MAX_VALUE,
        fixture.queryForObject("SELECT user_id FROM mall_order_202612", Long.class));
  }

  private SeckillMessageRecoveryMapper recoveryMapper() {
    return (SeckillMessageRecoveryMapper)
        ReflectionTestUtils.getField(service, "seckillMessageRecoveryMapper");
  }

  private void saveRecovery(long id, String eventId, Map<String, Object> event, String status)
      throws Exception {
    recoveryMapper()
        .saveDlq(
            id,
            eventId,
            String.valueOf(event.get("requestHash")),
            new ObjectMapper().writeValueAsString(event),
            status,
            LocalDateTime.now());
    fixture.update(
        "UPDATE seckill_message_recovery SET"
            + " lease_owner='held-owner',lease_until=?,last_error='keep original' WHERE event_id=?",
        LocalDateTime.now().plusMinutes(1),
        eventId);
  }

  private Map<String, Object> recoveryRow(String eventId) {
    return fixture.queryForMap("SELECT * FROM seckill_message_recovery WHERE event_id=?", eventId);
  }

  private Map<String, Object> timeEvent(String orderNo) {
    return new java.util.LinkedHashMap<>(
        Map.of(
            "orderNo",
            orderNo,
            "eventId",
            SeckillRequestFingerprint.eventId(orderNo),
            "eventType",
            "SECKILL_ORDER_REQUEST",
            "payloadVersion",
            1,
            "activityId",
            11L,
            "skuId",
            7L,
            "userId",
            8L,
            "idempotencyKey",
            "time:" + orderNo,
            "addressSnapshot",
            "{}",
            "requestHash",
            SeckillRequestFingerprint.hash(11, 7, 8, 1, "{}")));
  }

  private void assertTimeRejected(Map<String, Object> event, ProductClient products) {
    clearInvocations(products);
    IllegalStateException failure =
        assertThrows(IllegalStateException.class, () -> service.consumeSeckill(event));
    boolean stableReason = false;
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (String.valueOf(cause.getMessage()).contains("ORDER_SECKILL_TIME_UNVERIFIED"))
        stableReason = true;
    }
    assertTrue(stableReason);
    verifyNoInteractions(products);
    for (String table :
        List.of(
            "mall_order_202612",
            "mall_order_202701",
            "mall_order_item_202612",
            "mall_order_item_202701",
            "order_outbox",
            "seckill_order_dedup")) {
      assertEquals(
          0, fixture.queryForObject("SELECT COUNT(*) FROM " + table, Integer.class), table);
    }
  }

  private void configureMocks() {
    ProductClient products = (ProductClient) ReflectionTestUtils.getField(service, "products");
    StockClient stock = (StockClient) ReflectionTestUtils.getField(service, "stock");
    UserClient users = (UserClient) ReflectionTestUtils.getField(service, "users");
    CartClient cart = (CartClient) ReflectionTestUtils.getField(service, "cart");
    when(products.sku(7L))
        .thenReturn(
            ApiResponse.ok(
                new ProductClient.SkuView(7L, 9L, "商品", "SKU-7", BigDecimal.TEN, Map.of())));
    when(stock.reserve(any())).thenReturn(ApiResponse.ok(null));
    when(cart.deleteItem(anyLong(), anyLong())).thenReturn(ApiResponse.ok(null));
    when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L, "receiver", "小明"))));
  }

  @SuppressWarnings("unchecked")
  private <T> T transactionProxy(T target, DataSource dataSource) {
    ProxyFactory factory = new ProxyFactory(target);
    factory.setProxyTargetClass(true);
    TransactionInterceptor interceptor =
        new TransactionInterceptor(
            new DataSourceTransactionManager(dataSource),
            new AnnotationTransactionAttributeSource());
    factory.addAdvice(interceptor);
    return (T) factory.getProxy();
  }

  private void cloneFromSource(String sourceTable, String targetTable) {
    String ddl =
        String.valueOf(
            source
                .queryForMap("SHOW CREATE TABLE cloudmall_order." + sourceTable)
                .get("Create Table"));
    ddl = ddl.replace("`" + sourceTable + "`", "`" + targetTable + "`");
    fixture.execute(ddl);
  }

  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> result = new ArrayList<>();
    List<String> tables = source.queryForList("SHOW TABLES FROM cloudmall_order", String.class);
    result.addAll(source.queryForList("SHOW TABLES FROM cloudmall_order"));
    for (String table : tables) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法表名: " + table);
      result.addAll(source.queryForList("CHECKSUM TABLE cloudmall_order." + table));
    }
    return result;
  }

  private static String withShanghaiTimezone(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }
}
