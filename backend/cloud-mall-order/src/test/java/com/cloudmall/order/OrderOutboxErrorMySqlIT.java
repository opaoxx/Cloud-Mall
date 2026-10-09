package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.domain.po.OrderOutboxPO;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.service.impl.OrderOutboxDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.springframework.mock.env.MockEnvironment;

/** 超时outbox真实SQL回归；多数broker为替身，已到期路由另用隔离真实vhost，每例独立随机数据库。 */
class OrderOutboxErrorMySqlIT {
  /** 原订单库只读核验和隔离库管理入口。 */
  private static JdbcTemplate source;

  /** 原订单库表清单和校验和。 */
  private static List<Map<String, Object>> originalFingerprint;

  /** 本例独立创建、可精确清理的数据库名。 */
  private String database;

  /** 隔离数据库中的实际事实查询入口。 */
  private JdbcTemplate fixture;

  /** 实际outbox持久层。 */
  private OrderOutboxMapper mapper;

  @BeforeAll
  static void readOriginalDatabase() {
    String url = System.getProperty("cloudmall.mysql.test.url");
    if (url == null || !url.matches("jdbc:mysql://[^/]+/cloudmall_order(\\?.*)?"))
      throw new IllegalStateException("显式IT需要cloudmall_order的cloudmall.mysql.test.url");
    source = new JdbcTemplate(new DriverManagerDataSource(shanghaiUrl(url), "root", "root"));
    originalFingerprint = fingerprint();
  }

  @BeforeEach
  void createFixture() {
    database = "cm17au_" + UUID.randomUUID().toString().replace("-", "");
    source.execute(
        "CREATE DATABASE " + database + " CHARACTER SET utf8mb4 COLLATE utf8mb4_unicode_ci");
    String isolatedUrl =
        System.getProperty("cloudmall.mysql.test.url").replace("/cloudmall_order", "/" + database);
    fixture =
        new JdbcTemplate(new DriverManagerDataSource(shanghaiUrl(isolatedUrl), "root", "root"));
    fixture.execute(
        String.valueOf(source.queryForMap("SHOW CREATE TABLE order_outbox").get("Create Table")));
    mapper = new OrderOutboxMapper(fixture);
  }

  @AfterEach
  void removeFixture() {
    if (database != null && database.matches("cm17au_[0-9a-f]{32}")) {
      source.execute("DROP DATABASE " + database);
      assertEquals(
          0,
          source.queryForObject(
              "SELECT COUNT(*) FROM information_schema.schemata WHERE schema_name=?",
              Integer.class,
              database));
      System.out.println("CM17AU fixture dropped: " + database);
      database = null;
    }
  }

  @AfterAll
  static void verifyOriginalDatabase() {
    if (source != null) assertEquals(originalFingerprint, fingerprint());
    System.out.println("CM17AU original Order tables and checksums unchanged");
  }

  @Test
  void retryTimeOverflowCannotAbortFollowingEventsOrReachTheFailureWriter() {
    long outsideDatabaseYear =
        java.time.Duration.between(
                    LocalDateTime.now(ZoneOffset.ofHours(8)), LocalDateTime.of(10000, 1, 1, 0, 0))
                .getSeconds()
            + 86400;
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (long retrySeconds : List.of(Long.MAX_VALUE, outsideDatabaseYear)) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO first = pendingOutbox();
      OrderOutboxPO second = pendingOutbox();
      second.id = 2L;
      second.eventId = "after-invalid-retry-time";
      second.orderNo = "202610-after-invalid-retry-time";
      second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
      assertEquals(1, mapper.insert(first));
      assertEquals(1, mapper.insert(second));
      var before = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
      OrderOutboxMapper observedMapper = spy(mapper);
      RabbitTemplate rabbit = mock(RabbitTemplate.class);
      List<String> sentIds = new ArrayList<>();
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                sentIds.add(correlation.getId());
                if (first.eventId.equals(correlation.getId()))
                  throw new IllegalStateException("controlled send failure");
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger)
              org.slf4j.LoggerFactory.getLogger(OrderOutboxDispatcher.class);
      ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
          new ch.qos.logback.core.read.ListAppender<>();
      warnings.start();
      logger.addAppender(warnings);
      RuntimeException rejected = null;
      try {
        new OrderOutboxDispatcher(
                observedMapper,
                rabbit,
                new ObjectMapper(),
                new MockEnvironment()
                    .withProperty(
                        "cloudmall.order.outbox.retry-delay-seconds", String.valueOf(retrySeconds)))
            .dispatchPending();
      } catch (RuntimeException failure) {
        rejected = failure;
      } finally {
        logger.detachAppender(warnings);
        warnings.stop();
      }
      RuntimeException captured = rejected;
      var after = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
      String secondStatus =
          fixture.queryForObject("SELECT status FROM order_outbox WHERE id=2", String.class);
      List<ch.qos.logback.classic.spi.ILoggingEvent> events =
          warnings.list.stream()
              .filter(
                  event ->
                      event.getLevel() == ch.qos.logback.classic.Level.WARN
                          && event.getFormattedMessage().contains(first.eventId))
              .toList();
      checks.add(() -> assertNull(captured));
      checks.add(() -> assertEquals(before, after));
      checks.add(() -> assertEquals(List.of(first.eventId, second.eventId), sentIds));
      checks.add(() -> assertEquals("PUBLISHED", secondStatus));
      checks.add(
          () ->
              verify(observedMapper, never())
                  .recordOutboxFailure(anyString(), anyString(), any(LocalDateTime.class)));
      checks.add(() -> assertEquals(1, events.size()));
      if (!events.isEmpty())
        checks.add(
            () ->
                assertTrue(
                    events
                        .get(0)
                        .getThrowableProxy()
                        .getClassName()
                        .endsWith("InvalidDataAccessApiUsageException")));
    }
    assertAll(checks);
  }

  @Test
  void zeroFailureUpdateCannotClaimARetryWasRecordedAndKeepsCurrentFacts() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String state : List.of("PUBLISHED", "MISSING", "TYPE", "NEGATIVE")) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO first = pendingOutbox();
      OrderOutboxPO second = pendingOutbox();
      second.id = 2L;
      second.eventId = "after-unrecorded-failure";
      second.orderNo = "202610-after-unrecorded-failure";
      second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
      assertEquals(1, mapper.insert(first));
      assertEquals(1, mapper.insert(second));
      java.util.concurrent.atomic.AtomicReference<List<Map<String, Object>>> established =
          new java.util.concurrent.atomic.AtomicReference<>();
      RabbitTemplate rabbit = mock(RabbitTemplate.class);
      List<String> sentIds = new ArrayList<>();
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                sentIds.add(correlation.getId());
                if (first.eventId.equals(correlation.getId())) {
                  if ("PUBLISHED".equals(state))
                    assertEquals(
                        1,
                        mapper.markOutboxPublished(
                            first.eventId, LocalDateTime.now(ZoneOffset.ofHours(8))));
                  else if ("MISSING".equals(state))
                    fixture.update("DELETE FROM order_outbox WHERE id=1");
                  else if ("TYPE".equals(state))
                    fixture.update("UPDATE order_outbox SET event_type='PAY_NOTIFY' WHERE id=1");
                  else fixture.update("UPDATE order_outbox SET attempts=-1 WHERE id=1");
                  established.set(fixture.queryForList("SELECT * FROM order_outbox WHERE id=1"));
                  throw new IllegalStateException("controlled send failure");
                }
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger)
              org.slf4j.LoggerFactory.getLogger(OrderOutboxDispatcher.class);
      ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
          new ch.qos.logback.core.read.ListAppender<>();
      warnings.start();
      logger.addAppender(warnings);
      try {
        dispatcher(rabbit).dispatchPending();
      } finally {
        logger.detachAppender(warnings);
        warnings.stop();
      }
      List<String> messages =
          warnings.list.stream()
              .filter(
                  event ->
                      event.getLevel() == ch.qos.logback.classic.Level.WARN
                          && event.getFormattedMessage().contains(first.eventId))
              .map(event -> event.getFormattedMessage())
              .toList();
      var after = fixture.queryForList("SELECT * FROM order_outbox WHERE id=1");
      String secondStatus =
          fixture.queryForObject("SELECT status FROM order_outbox WHERE id=2", String.class);
      checks.add(() -> assertEquals(established.get(), after, state));
      checks.add(() -> assertEquals(List.of(first.eventId, second.eventId), sentIds, state));
      checks.add(() -> assertEquals("PUBLISHED", secondStatus, state));
      checks.add(() -> assertEquals(1, messages.size(), state));
      checks.add(
          () -> assertTrue(messages.stream().noneMatch(message -> message.contains("将重试")), state));
      checks.add(
          () ->
              assertTrue(
                  messages.stream()
                      .allMatch(message -> message.contains("更新0行") && message.contains("未确认记录")),
                  state));
    }
    assertAll(checks);
  }

  @Test
  void alreadyExpiredRecoveryCannotWaitBehindAnUnexpiredTtlHead() throws Exception {
    OrderOutboxPO future = pendingOutbox();
    future.eventId = "future-timeout-head";
    OrderOutboxPO expired = pendingOutbox();
    expired.id = 2L;
    expired.eventId = "already-due-timeout-tail";
    expired.orderNo = "202610-recovered-timeout";
    expired.nextRetryAt = future.nextRetryAt.plusSeconds(1);
    expired.payload =
        "{\"expireAt\":\""
            + LocalDateTime.now(ZoneOffset.ofHours(8))
                .minusMinutes(1)
                .atOffset(ZoneOffset.ofHours(8))
            + "\"}";
    assertEquals(1, mapper.insert(future));
    assertEquals(1, mapper.insert(expired));
    try (OrderTimeoutRabbitFixture broker = new OrderTimeoutRabbitFixture()) {
      dispatcher(broker.rabbit()).dispatchPending();
      org.springframework.amqp.core.Message message = broker.awaitDueMessage();
      assertNotNull(message, "已到期事件不应等待未到期的30分钟TTL队头");
      assertEquals(
          expired.eventId, new ObjectMapper().readTree(message.getBody()).path("eventId").asText());
      assertNull(message.getMessageProperties().getExpiration(), "关单队列消息不能继续携带1ms TTL");
      assertEquals(1, broker.pendingTimeoutMessages(), "未到期消息仍保留原TTL链路");
      assertEquals(
          2,
          fixture.queryForObject(
              "SELECT COUNT(*) FROM order_outbox WHERE status='PUBLISHED'", Integer.class));
    }
  }

  @Test
  void missingDueRouteBindingCannotBeMistakenForSuccessfulPublication() throws Exception {
    OrderOutboxPO expired = pendingOutbox();
    expired.payload =
        "{\"expireAt\":\""
            + LocalDateTime.now(ZoneOffset.ofHours(8))
                .minusMinutes(1)
                .atOffset(ZoneOffset.ofHours(8))
            + "\"}";
    assertEquals(1, mapper.insert(expired));
    try (OrderTimeoutRabbitFixture broker = new OrderTimeoutRabbitFixture()) {
      broker.removeDeadLetterBinding();
      dispatcher(broker.rabbit()).dispatchPending();
      var after = fixture.queryForMap("SELECT * FROM order_outbox");
      assertEquals("PENDING", after.get("status"));
      assertEquals(1, ((Number) after.get("attempts")).intValue());
      assertTrue(String.valueOf(after.get("last_error")).contains("returned"));
      assertNull(after.get("published_at"));
    }
  }

  @Test
  void conflictingPayloadIdentitiesCannotBePublishedAndUseTheOriginalRetryPath() throws Exception {
    ObjectMapper json = new ObjectMapper();
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String field : List.of("eventId", "eventType", "orderNo")) {
      OrderOutboxPO identity = pendingOutbox();
      String expectedValue =
          Map.of(
                  "eventId",
                  identity.eventId,
                  "eventType",
                  identity.eventType,
                  "orderNo",
                  identity.orderNo)
              .get(field);
      String caseAlias = expectedValue.toUpperCase(java.util.Locale.ROOT);
      if (caseAlias.equals(expectedValue))
        caseAlias = expectedValue.toLowerCase(java.util.Locale.ROOT);
      List<Object> wrongValues =
          new ArrayList<>(
              Arrays.asList(
                  null,
                  42,
                  false,
                  List.of("wrong"),
                  Map.of("identity", "wrong"),
                  "other",
                  "trailing "));
      wrongValues.addAll(
          List.of(caseAlias, expectedValue + " ", expectedValue.replaceFirst("[a-zA-Z]", "é")));
      for (Object wrongValue : wrongValues) {
        fixture.update("DELETE FROM order_outbox");
        OrderOutboxPO invalid = pendingOutbox();
        Map<String, Object> payload =
            json.readValue(
                invalid.payload,
                new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
        payload.put("eventId", invalid.eventId);
        payload.put("eventType", invalid.eventType);
        payload.put("orderNo", invalid.orderNo);
        payload.put(field, wrongValue);
        invalid.payload = json.writeValueAsString(payload);
        OrderOutboxPO healthy = pendingOutbox();
        healthy.id = 2L;
        healthy.eventId = "timeout-after-payload-conflict";
        healthy.orderNo = "202610-after-payload-conflict";
        healthy.nextRetryAt = invalid.nextRetryAt.plusSeconds(1);
        assertEquals(1, mapper.insert(invalid));
        assertEquals(1, mapper.insert(healthy));
        var before = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        List<String> sentIds = new ArrayList<>();
        doAnswer(
                invocation -> {
                  CorrelationData correlation = invocation.getArgument(4);
                  sentIds.add(correlation.getId());
                  correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                  return null;
                })
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        dispatcher(rabbit).dispatchPending();
        var after = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
        LocalDateTime retryAt =
            fixture.queryForObject(
                "SELECT next_retry_at FROM order_outbox WHERE id=1", LocalDateTime.class);
        String healthyStatus =
            fixture.queryForObject("SELECT status FROM order_outbox WHERE id=2", String.class);
        String label = field + ":" + String.valueOf(wrongValue);
        checks.add(() -> assertEquals("PENDING", after.get("status"), label));
        checks.add(() -> assertEquals(1, ((Number) after.get("attempts")).intValue(), label));
        checks.add(
            () -> assertTrue(String.valueOf(after.get("last_error")).contains("载荷身份不一致"), label));
        checks.add(() -> assertTrue(retryAt.isAfter(invalid.nextRetryAt), label));
        checks.add(() -> assertUnchangedEvent(before, after));
        checks.add(() -> assertEquals(List.of(healthy.eventId), sentIds, label));
        checks.add(() -> assertEquals("PUBLISHED", healthyStatus, label));
      }
    }
    assertAll(checks);
  }

  @Test
  void matchingFullAndLegacyPayloadsKeepTheirStoredIdentityAndPublish() throws Exception {
    ObjectMapper json = new ObjectMapper();
    for (boolean fullIdentity : List.of(false, true)) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO outbox = pendingOutbox();
      Map<String, Object> payload =
          json.readValue(
              outbox.payload,
              new com.fasterxml.jackson.core.type.TypeReference<Map<String, Object>>() {});
      if (fullIdentity) {
        payload.put("eventId", outbox.eventId);
        payload.put("eventType", outbox.eventType);
        payload.put("orderNo", outbox.orderNo);
      }
      payload.put("traceId", "original-trace");
      outbox.payload = json.writeValueAsString(payload);
      assertEquals(1, mapper.insert(outbox));
      var before = fixture.queryForMap("SELECT * FROM order_outbox");
      RabbitTemplate rabbit = mock(RabbitTemplate.class);
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                Map<String, Object> envelope = invocation.getArgument(2);
                assertEquals(outbox.eventId, correlation.getId());
                assertEquals(payload, envelope.get("payload"));
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      dispatcher(rabbit).dispatchPending();
      var after = fixture.queryForMap("SELECT * FROM order_outbox");
      assertEquals("PUBLISHED", after.get("status"));
      assertEquals(0, ((Number) after.get("attempts")).intValue());
      for (String column : List.of("event_id", "event_type", "order_no", "payload", "created_at"))
        assertEquals(before.get(column), after.get(column), column);
      verify(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void blankIdentityRowsCannotFillTheScanWindowAndStarveFollowingEvents() {
    LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(2).withNano(0);
    List<String> blanks = List.of("", " ", "\t\r\n", "\u2003");
    for (int rowIndex = 1; rowIndex <= 50; rowIndex++) {
      OrderOutboxPO invalid = pendingOutbox();
      invalid.id = (long) rowIndex;
      invalid.eventId = "blank-identity-head-" + rowIndex;
      invalid.orderNo = blanks.get(rowIndex % blanks.size());
      invalid.lastError = "preserve blank backlog";
      invalid.nextRetryAt = dueAt;
      assertEquals(1, mapper.insert(invalid));
    }
    var before = fixture.queryForList("SELECT * FROM order_outbox ORDER BY id");
    OrderOutboxPO healthy = pendingOutbox();
    healthy.id = 100L;
    healthy.eventId = "timeout-after-blank-window";
    healthy.orderNo = "202610-after-blank-window";
    healthy.attempts = Integer.MAX_VALUE;
    healthy.nextRetryAt = dueAt.plusSeconds(1);
    assertEquals(1, mapper.insert(healthy));
    List<String> selected = mapper.findPendingOutbox(50).stream().map(row -> row.eventId).toList();
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    List<String> sentIds = new ArrayList<>();
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(4);
              sentIds.add(correlation.getId());
              correlation.getFuture().set(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    dispatcher(rabbit).dispatchPending();
    assertAll(
        () -> assertEquals(List.of(healthy.eventId), selected),
        () -> assertEquals(List.of(healthy.eventId), sentIds),
        () ->
            assertEquals(
                before,
                fixture.queryForList("SELECT * FROM order_outbox WHERE id<100 ORDER BY id")),
        () ->
            assertEquals(
                "PUBLISHED",
                fixture.queryForObject(
                    "SELECT status FROM order_outbox WHERE id=100", String.class)));
  }

  @Test
  void identityScanMatchesJavaBlankAcrossWhitespaceBoundariesAndSqlModes() throws Exception {
    java.util.Set<String> samples = new java.util.LinkedHashSet<>();
    samples.add("");
    for (int codePoint = 0; codePoint <= Character.MAX_CODE_POINT; codePoint++) {
      if (Character.isWhitespace(codePoint)) {
        samples.add(new String(Character.toChars(codePoint - 1)));
        samples.add(new String(Character.toChars(codePoint)));
        samples.add(new String(Character.toChars(codePoint + 1)));
      }
    }
    samples.addAll(
        List.of(
            "\u0000",
            "\u0085",
            "\u00A0",
            "\u2007",
            "\u202F",
            "\u200B",
            "\uFEFF",
            "😀",
            "\t\u2003\n",
            "\t事件😀\n",
            " id "));
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderOutboxMapper(modeJdbc);
      for (String sqlMode : List.of("STRICT_TRANS_TABLES", "NO_BACKSLASH_ESCAPES")) {
        modeJdbc.execute("SET SESSION sql_mode='" + sqlMode + "'");
        for (String field : List.of("eventId", "orderNo")) {
          for (String sample : samples) {
            modeJdbc.update("DELETE FROM order_outbox");
            OrderOutboxPO outbox = pendingOutbox();
            if ("eventId".equals(field)) outbox.eventId = sample;
            else outbox.orderNo = sample;
            // 夹具由默认连接写入，特殊模式连接只验证实际扫描，防写入转义改变样本。
            assertEquals(1, mapper.insert(outbox));
            var before = modeJdbc.queryForMap("SELECT * FROM order_outbox");
            assertEquals(outbox.eventId, before.get("event_id"), "fixture eventId");
            assertEquals(outbox.orderNo, before.get("order_no"), "fixture orderNo");
            List<OrderOutboxPO> selected = modeMapper.findPendingOutbox(50);
            var after = modeJdbc.queryForMap("SELECT * FROM order_outbox");
            String label = sqlMode + ":" + field + ":" + sample.codePoints().boxed().toList();
            checks.add(() -> assertEquals(sample.isBlank() ? 0 : 1, selected.size(), label));
            checks.add(() -> assertEquals(before, after, label));
            if (!sample.isBlank() && !selected.isEmpty()) {
              checks.add(() -> assertEquals(outbox.eventId, selected.get(0).eventId, label));
              checks.add(() -> assertEquals(outbox.orderNo, selected.get(0).orderNo, label));
            }
          }
        }
      }
    }
    System.out.println(
        "CM17DB identity samples: " + samples.size() + ", comparisons: " + samples.size() * 4);
    assertAll(checks);
  }

  @Test
  void blankRequiredIdentitiesCannotBePublishedOrWrittenWhileHealthyEventsContinue() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String column : List.of("eventId", "orderNo")) {
      for (String blank : List.of("", " ", "\t\r\n", "\u2003")) {
        fixture.update("DELETE FROM order_outbox");
        OrderOutboxPO invalid = pendingOutbox();
        if ("eventId".equals(column)) invalid.eventId = blank;
        else invalid.orderNo = blank;
        invalid.lastError = "preserve blank identity evidence";
        OrderOutboxPO healthy = pendingOutbox();
        healthy.id = 2L;
        healthy.eventId = "timeout-after-blank-identity";
        healthy.orderNo = "202610-valid-identity";
        healthy.nextRetryAt = invalid.nextRetryAt.plusSeconds(1);
        assertEquals(1, mapper.insert(invalid));
        assertEquals(1, mapper.insert(healthy));
        var before = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        List<String> sentIds = new ArrayList<>();
        doAnswer(
                invocation -> {
                  CorrelationData correlation = invocation.getArgument(4);
                  sentIds.add(correlation.getId());
                  correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                  return null;
                })
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        dispatcher(rabbit).dispatchPending();
        var after = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
        String healthyStatus =
            fixture.queryForObject("SELECT status FROM order_outbox WHERE id=2", String.class);
        String label = column + ":" + blank.codePoints().boxed().toList();
        checks.add(() -> assertEquals(before, after, label));
        checks.add(() -> assertEquals(List.of(healthy.eventId), sentIds, label));
        checks.add(() -> assertEquals("PUBLISHED", healthyStatus, label));
      }
    }
    assertAll(checks);
  }

  @Test
  void ineligibleTypesAndNegativeCountersCannotBeMutatedByEitherUpdate() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (Map.Entry<String, Integer> invalid :
        List.of(
            Map.entry("PAY_NOTIFY", 0),
            Map.entry("order_timeout", 0),
            Map.entry("ORDER_TIMEOUT ", 0),
            Map.entry("ORDER_TÍMEOUT", 0),
            Map.entry("", 0),
            Map.entry("ORDER_TIMEOUT", -1),
            Map.entry("ORDER_TIMEOUT", Integer.MIN_VALUE))) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO original = pendingOutbox();
      original.eventType = invalid.getKey();
      original.attempts = invalid.getValue();
      original.lastError = "preserve eligibility evidence";
      assertEquals(1, mapper.insert(original));
      var before = fixture.queryForMap("SELECT * FROM order_outbox");
      int failed =
          mapper.recordOutboxFailure(
              original.eventId, "new error", original.nextRetryAt.plusMinutes(1));
      var afterFailure = fixture.queryForMap("SELECT * FROM order_outbox");
      int completed =
          mapper.markOutboxPublished(original.eventId, original.createdAt.plusMinutes(1));
      var afterCompletion = fixture.queryForMap("SELECT * FROM order_outbox");
      String label = invalid.toString();
      checks.add(() -> assertEquals(0, failed, label));
      checks.add(() -> assertEquals(before, afterFailure, label));
      checks.add(() -> assertEquals(0, completed, label));
      checks.add(() -> assertEquals(before, afterCompletion, label));
    }
    for (int attempts : List.of(0, Integer.MAX_VALUE)) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO original = pendingOutbox();
      original.attempts = attempts;
      assertEquals(1, mapper.insert(original));
      assertEquals(
          1,
          mapper.recordOutboxFailure(
              original.eventId, "legal failure😀", original.nextRetryAt.plusMinutes(1)));
      assertEquals(
          1, mapper.markOutboxPublished(original.eventId, original.createdAt.plusMinutes(1)));
      var after = fixture.queryForMap("SELECT * FROM order_outbox");
      checks.add(() -> assertEquals("ORDER_TIMEOUT", after.get("event_type")));
      checks.add(() -> assertEquals("PUBLISHED", after.get("status")));
      checks.add(
          () ->
              assertEquals(
                  Math.min((long) attempts + 1, Integer.MAX_VALUE),
                  ((Number) after.get("attempts")).longValue()));
      checks.add(() -> assertNull(after.get("last_error")));
    }
    assertAll(checks);
  }

  @Test
  void eligibilityChangesDuringSendCannotBeCompletedOrRetriedByTheTimeoutHandler() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (Map.Entry<String, Integer> changed :
        List.of(
            Map.entry("PAY_NOTIFY", 0),
            Map.entry("order_timeout", 0),
            Map.entry("ORDER_TIMEOUT ", 0),
            Map.entry("ORDER_TÍMEOUT", 0),
            Map.entry("ORDER_TIMEOUT", -1))) {
      for (boolean acknowledged : List.of(true, false)) {
        fixture.update("DELETE FROM order_outbox");
        OrderOutboxPO first = pendingOutbox();
        first.lastError = "preserve changed eligibility";
        OrderOutboxPO second = pendingOutbox();
        second.id = 2L;
        second.eventId = "after-changed-event-eligibility";
        second.orderNo = "202610-after-changed-eligibility";
        second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
        assertEquals(1, mapper.insert(first));
        assertEquals(1, mapper.insert(second));
        java.util.concurrent.atomic.AtomicReference<Map<String, Object>> established =
            new java.util.concurrent.atomic.AtomicReference<>();
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        List<String> sentIds = new ArrayList<>();
        doAnswer(
                invocation -> {
                  CorrelationData correlation = invocation.getArgument(4);
                  sentIds.add(correlation.getId());
                  if (first.eventId.equals(correlation.getId())) {
                    assertEquals(
                        1,
                        fixture.update(
                            "UPDATE order_outbox SET event_type=?,attempts=? WHERE id=1",
                            changed.getKey(),
                            changed.getValue()));
                    established.set(fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1"));
                    if (!acknowledged) throw new IllegalStateException("controlled send failure");
                  }
                  correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                  return null;
                })
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(OrderOutboxDispatcher.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
            new ch.qos.logback.core.read.ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);
        try {
          dispatcher(rabbit).dispatchPending();
        } finally {
          logger.detachAppender(warnings);
          warnings.stop();
        }
        long completionFailures =
            warnings.list.stream()
                .filter(
                    event ->
                        event.getLevel() == ch.qos.logback.classic.Level.WARN
                            && event.getThrowableProxy() != null
                            && event
                                .getThrowableProxy()
                                .getClassName()
                                .endsWith("OptimisticLockingFailureException"))
                .count();
        var after = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
        String secondStatus =
            fixture.queryForObject("SELECT status FROM order_outbox WHERE id=2", String.class);
        String label = changed + ":" + acknowledged;
        checks.add(() -> assertEquals(established.get(), after, label));
        checks.add(() -> assertEquals(List.of(first.eventId, second.eventId), sentIds, label));
        checks.add(() -> assertEquals(acknowledged ? 1 : 0, completionFailures, label));
        checks.add(() -> assertEquals("PUBLISHED", secondStatus, label));
      }
    }
    assertAll(checks);
  }

  @Test
  void eventIdAliasesCannotMutateTheOriginalEventThroughEitherUpdate() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String alias :
        Arrays.asList("timeout-event", "Timeout-Évent", "Timeout-Event ", "missing", null)) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO original = pendingOutbox();
      original.eventId = "Timeout-Event";
      original.lastError = "preserve exact identity";
      assertEquals(1, mapper.insert(original));
      var before = fixture.queryForMap("SELECT * FROM order_outbox");
      int failed =
          mapper.recordOutboxFailure(alias, "alias failure", original.nextRetryAt.plusMinutes(1));
      var afterFailure = fixture.queryForMap("SELECT * FROM order_outbox");
      int completed = mapper.markOutboxPublished(alias, original.createdAt.plusMinutes(1));
      var afterCompletion = fixture.queryForMap("SELECT * FROM order_outbox");
      checks.add(() -> assertEquals(0, failed, String.valueOf(alias)));
      checks.add(() -> assertEquals(before, afterFailure, String.valueOf(alias)));
      checks.add(() -> assertEquals(0, completed, String.valueOf(alias)));
      checks.add(() -> assertEquals(before, afterCompletion, String.valueOf(alias)));
    }
    for (String exactId : List.of("Timeout-Event", "事件😀A", " event ")) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO original = pendingOutbox();
      original.eventId = exactId;
      assertEquals(1, mapper.insert(original));
      assertEquals(
          1,
          mapper.recordOutboxFailure(
              exactId, "exact failure😀", original.nextRetryAt.plusMinutes(1)));
      assertEquals(1, mapper.markOutboxPublished(exactId, original.createdAt.plusMinutes(1)));
      var after = fixture.queryForMap("SELECT * FROM order_outbox");
      checks.add(() -> assertEquals(exactId, after.get("event_id")));
      checks.add(() -> assertEquals("PUBLISHED", after.get("status")));
      checks.add(() -> assertEquals(1, ((Number) after.get("attempts")).intValue()));
      checks.add(() -> assertNull(after.get("last_error")));
    }
    assertAll(checks);
  }

  @Test
  void identityChangesDuringSendCannotBeCompletedOrRetriedAsTheOriginalEvent() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String alias :
        List.of("TIMEOUT-ERROR-EVENT", "timeout-errór-event", "timeout-error-event ")) {
      for (boolean acknowledged : List.of(true, false)) {
        fixture.update("DELETE FROM order_outbox");
        OrderOutboxPO first = pendingOutbox();
        first.lastError = "preserve changed identity";
        OrderOutboxPO second = pendingOutbox();
        second.id = 2L;
        second.eventId = "after-changed-event-identity";
        second.orderNo = "202610-after-changed-identity";
        second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
        assertEquals(1, mapper.insert(first));
        assertEquals(1, mapper.insert(second));
        java.util.concurrent.atomic.AtomicReference<Map<String, Object>> established =
            new java.util.concurrent.atomic.AtomicReference<>();
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        List<String> sentIds = new ArrayList<>();
        doAnswer(
                invocation -> {
                  CorrelationData correlation = invocation.getArgument(4);
                  sentIds.add(correlation.getId());
                  if (first.eventId.equals(correlation.getId())) {
                    assertEquals(
                        1, fixture.update("UPDATE order_outbox SET event_id=? WHERE id=1", alias));
                    established.set(fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1"));
                    if (!acknowledged) throw new IllegalStateException("controlled send failure");
                  }
                  correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                  return null;
                })
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        ch.qos.logback.classic.Logger logger =
            (ch.qos.logback.classic.Logger)
                org.slf4j.LoggerFactory.getLogger(OrderOutboxDispatcher.class);
        ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
            new ch.qos.logback.core.read.ListAppender<>();
        warnings.start();
        logger.addAppender(warnings);
        try {
          dispatcher(rabbit).dispatchPending();
        } finally {
          logger.detachAppender(warnings);
          warnings.stop();
        }
        long completionFailures =
            warnings.list.stream()
                .filter(
                    event ->
                        event.getLevel() == ch.qos.logback.classic.Level.WARN
                            && event.getThrowableProxy() != null
                            && event
                                .getThrowableProxy()
                                .getClassName()
                                .endsWith("OptimisticLockingFailureException"))
                .count();
        var after = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
        String secondStatus =
            fixture.queryForObject("SELECT status FROM order_outbox WHERE id=2", String.class);
        String label = alias + ":" + acknowledged;
        checks.add(() -> assertEquals(established.get(), after, label));
        checks.add(() -> assertEquals(List.of(first.eventId, second.eventId), sentIds, label));
        checks.add(() -> assertEquals(acknowledged ? 1 : 0, completionFailures, label));
        checks.add(() -> assertEquals("PUBLISHED", secondStatus, label));
      }
    }
    assertAll(checks);
  }

  @Test
  void statusAliasesCannotBePublishedAsPendingEvents() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String status : List.of("pending", "PENDING ", "PÉNDING", "", "OTHER", "PUBLISHED")) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO invalid = pendingOutbox();
      invalid.status = status;
      invalid.lastError = "keep status evidence";
      OrderOutboxPO healthy = pendingOutbox();
      healthy.id = 2L;
      healthy.eventId = "timeout-after-other-status";
      healthy.orderNo = "202610-valid-timeout";
      healthy.nextRetryAt = invalid.nextRetryAt.plusSeconds(1);
      assertEquals(1, mapper.insert(invalid));
      assertEquals(1, mapper.insert(healthy));
      var before = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
      RabbitTemplate rabbit = mock(RabbitTemplate.class);
      List<String> sent = new ArrayList<>();
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                sent.add(correlation.getId());
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      dispatcher(rabbit).dispatchPending();
      var after = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
      checks.add(() -> assertEquals(before, after, status));
      checks.add(() -> assertEquals(List.of(healthy.eventId), sent, status));
      assertEquals(
          "PUBLISHED",
          fixture.queryForObject("SELECT status FROM order_outbox WHERE id=2", String.class));
    }
    assertAll(checks);
  }

  @Test
  void statusAliasesCannotOccupyThePendingScanWindow() {
    LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(2).withNano(0);
    for (int rowIndex = 1; rowIndex <= 50; rowIndex++) {
      OrderOutboxPO invalid = pendingOutbox();
      invalid.id = (long) rowIndex;
      invalid.eventId = "wrong-status-head-" + rowIndex;
      invalid.orderNo = "202610-wrong-status-" + rowIndex;
      invalid.status = rowIndex % 2 == 0 ? "pending" : "PENDING ";
      invalid.nextRetryAt = dueAt;
      assertEquals(1, mapper.insert(invalid));
    }
    var before = fixture.queryForList("SELECT * FROM order_outbox ORDER BY id");
    OrderOutboxPO valid = pendingOutbox();
    valid.id = 100L;
    valid.eventId = "timeout-after-status-window";
    valid.orderNo = "202610-after-status-window";
    valid.nextRetryAt = dueAt.plusSeconds(1);
    valid.attempts = Integer.MAX_VALUE;
    assertEquals(1, mapper.insert(valid));
    List<OrderOutboxPO> selected = mapper.findPendingOutbox(50);
    assertAll(
        () ->
            assertEquals(
                List.of(valid.eventId), selected.stream().map(row -> row.eventId).toList()),
        () ->
            assertEquals(
                before,
                fixture.queryForList("SELECT * FROM order_outbox WHERE id<100 ORDER BY id")));
  }

  @Test
  void pendingStatusAliasesCannotBeMutatedByCompletionOrFailureWrites() {
    for (String status : List.of("pending", "PENDING ", "PÉNDING", "", "OTHER", "PUBLISHED")) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO outbox = pendingOutbox();
      outbox.status = status;
      outbox.lastError = "preserve status evidence";
      assertEquals(1, mapper.insert(outbox));
      var before = fixture.queryForMap("SELECT * FROM order_outbox");
      int failed =
          mapper.recordOutboxFailure(
              outbox.eventId, "new error", outbox.nextRetryAt.plusMinutes(1));
      var afterFailure = fixture.queryForMap("SELECT * FROM order_outbox");
      int completed = mapper.markOutboxPublished(outbox.eventId, outbox.createdAt.plusMinutes(1));
      var afterCompletion = fixture.queryForMap("SELECT * FROM order_outbox");
      assertAll(
          () -> assertEquals(0, completed, status),
          () -> assertEquals(before, afterCompletion, status),
          () -> assertEquals(0, failed, status),
          () -> assertEquals(before, afterFailure, status));
    }
  }

  @Test
  void nonTimeoutEventTypesCannotBePublishedAsTimeouts() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (String type : List.of("PAY_NOTIFY", "order_timeout", "ORDER_TIMEOUT ", "")) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO invalid = pendingOutbox();
      invalid.eventType = type;
      invalid.lastError = "keep other event";
      OrderOutboxPO healthy = pendingOutbox();
      healthy.id = 2L;
      healthy.eventId = "timeout-after-other-type";
      healthy.orderNo = "202610-valid-timeout";
      healthy.nextRetryAt = invalid.nextRetryAt.plusSeconds(1);
      assertEquals(1, mapper.insert(invalid));
      assertEquals(1, mapper.insert(healthy));
      var before = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
      RabbitTemplate rabbit = mock(RabbitTemplate.class);
      List<String> sent = new ArrayList<>();
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                sent.add(correlation.getId());
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      dispatcher(rabbit).dispatchPending();
      var after = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
      checks.add(() -> assertEquals(before, after, type));
      checks.add(() -> assertEquals(List.of(healthy.eventId), sent, type));
      assertEquals(
          "PUBLISHED",
          fixture.queryForObject("SELECT status FROM order_outbox WHERE id=2", String.class));
    }
    assertAll(checks);
  }

  @Test
  void typeAliasesCannotOccupyTheTimeoutScanWindow() {
    LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(2).withNano(0);
    for (int rowIndex = 1; rowIndex <= 50; rowIndex++) {
      OrderOutboxPO invalid = pendingOutbox();
      invalid.id = (long) rowIndex;
      invalid.eventId = "wrong-type-head-" + rowIndex;
      invalid.orderNo = "202610-wrong-type-" + rowIndex;
      invalid.eventType = rowIndex % 2 == 0 ? "order_timeout" : "ORDER_TIMEOUT ";
      invalid.nextRetryAt = dueAt;
      assertEquals(1, mapper.insert(invalid));
    }
    var before = fixture.queryForList("SELECT * FROM order_outbox ORDER BY id");
    OrderOutboxPO valid = pendingOutbox();
    valid.id = 100L;
    valid.eventId = "timeout-after-type-window";
    valid.orderNo = "202610-after-type-window";
    valid.nextRetryAt = dueAt.plusSeconds(1);
    valid.attempts = Integer.MAX_VALUE;
    assertEquals(1, mapper.insert(valid));
    List<OrderOutboxPO> selected = mapper.findPendingOutbox(50);
    assertAll(
        () ->
            assertEquals(
                List.of(valid.eventId), selected.stream().map(row -> row.eventId).toList()),
        () ->
            assertEquals(
                before,
                fixture.queryForList("SELECT * FROM order_outbox WHERE id<100 ORDER BY id")));
  }

  @Test
  void zeroCompletionProofMustRemainBoundToOrderTypeAndNonnegativeCounter() {
    for (String state :
        List.of(
            "type-other",
            "type-case",
            "type-accent",
            "type-trailing",
            "order-other",
            "order-case",
            "order-trailing",
            "negative",
            "peer-one",
            "peer-max")) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO first = pendingOutbox();
      first.lastError = "keep publication fact";
      OrderOutboxPO second = pendingOutbox();
      second.id = 2L;
      second.eventId = "after-zero-publication";
      second.orderNo = "202610-after-zero";
      second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
      assertEquals(1, mapper.insert(first));
      assertEquals(1, mapper.insert(second));
      java.util.concurrent.atomic.AtomicReference<List<Map<String, Object>>> established =
          new java.util.concurrent.atomic.AtomicReference<>();
      RabbitTemplate rabbit = mock(RabbitTemplate.class);
      List<String> sentIds = new ArrayList<>();
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                sentIds.add(correlation.getId());
                if (first.eventId.equals(correlation.getId())) {
                  assertEquals(
                      1,
                      mapper.markOutboxPublished(
                          first.eventId, LocalDateTime.now(ZoneOffset.ofHours(8))));
                  if (state.startsWith("type-")) {
                    String type =
                        switch (state) {
                          case "type-other" -> "PAY_NOTIFY";
                          case "type-case" -> "order_timeout";
                          case "type-accent" -> "ORDER_TÍMEOUT";
                          default -> "ORDER_TIMEOUT ";
                        };
                    fixture.update("UPDATE order_outbox SET event_type=? WHERE id=1", type);
                  } else if (state.startsWith("order-")) {
                    String orderNo =
                        switch (state) {
                          case "order-other" -> "202610-different-order";
                          case "order-case" -> first.orderNo.toUpperCase(java.util.Locale.ROOT);
                          default -> first.orderNo + " ";
                        };
                    fixture.update("UPDATE order_outbox SET order_no=? WHERE id=1", orderNo);
                  } else {
                    int attempts =
                        "negative".equals(state)
                            ? -1
                            : "peer-one".equals(state) ? 1 : Integer.MAX_VALUE;
                    fixture.update("UPDATE order_outbox SET attempts=? WHERE id=1", attempts);
                  }
                  established.set(fixture.queryForList("SELECT * FROM order_outbox WHERE id=1"));
                }
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger)
              org.slf4j.LoggerFactory.getLogger(OrderOutboxDispatcher.class);
      ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
          new ch.qos.logback.core.read.ListAppender<>();
      warnings.start();
      logger.addAppender(warnings);
      try {
        dispatcher(rabbit).dispatchPending();
      } finally {
        logger.detachAppender(warnings);
        warnings.stop();
      }
      long completionFailures =
          warnings.list.stream()
              .filter(
                  event ->
                      event.getLevel() == ch.qos.logback.classic.Level.WARN
                          && event.getThrowableProxy() != null
                          && event
                              .getThrowableProxy()
                              .getClassName()
                              .endsWith("OptimisticLockingFailureException"))
              .count();
      assertAll(
          () -> assertEquals(state.startsWith("peer-") ? 0 : 1, completionFailures, state),
          () ->
              assertEquals(
                  established.get(),
                  fixture.queryForList("SELECT * FROM order_outbox WHERE id=1"),
                  state),
          () -> assertEquals(List.of(first.eventId, second.eventId), sentIds, state),
          () ->
              assertEquals(
                  "PUBLISHED",
                  fixture.queryForObject(
                      "SELECT status FROM order_outbox WHERE id=2", String.class),
                  state));
    }
  }

  @Test
  void zeroPublicationUpdateNeedsExactPublishedEvidenceInsteadOfSilentCompletion() {
    for (String state : List.of("OTHER", "PUBLISHED", "MISSING", "ALIAS", "pending", "PENDING ")) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO first = pendingOutbox();
      first.lastError = "keep publication fact";
      OrderOutboxPO second = pendingOutbox();
      second.id = 2L;
      second.eventId = "after-zero-publication";
      second.orderNo = "202610-after-zero";
      second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
      assertEquals(1, mapper.insert(first));
      assertEquals(1, mapper.insert(second));
      java.util.concurrent.atomic.AtomicReference<List<Map<String, Object>>> established =
          new java.util.concurrent.atomic.AtomicReference<>();
      RabbitTemplate rabbit = mock(RabbitTemplate.class);
      List<String> sentIds = new ArrayList<>();
      doAnswer(
              invocation -> {
                CorrelationData correlation = invocation.getArgument(4);
                sentIds.add(correlation.getId());
                if (first.eventId.equals(correlation.getId())) {
                  if ("PUBLISHED".equals(state) || "ALIAS".equals(state)) {
                    assertEquals(
                        1,
                        mapper.markOutboxPublished(
                            first.eventId, LocalDateTime.now(ZoneOffset.ofHours(8))));
                    if ("ALIAS".equals(state))
                      fixture.update(
                          "UPDATE order_outbox SET event_id=? WHERE id=1",
                          first.eventId.toUpperCase(java.util.Locale.ROOT));
                  } else if ("MISSING".equals(state))
                    fixture.update("DELETE FROM order_outbox WHERE id=1");
                  else fixture.update("UPDATE order_outbox SET status=? WHERE id=1", state);
                  established.set(fixture.queryForList("SELECT * FROM order_outbox WHERE id=1"));
                }
                correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                return null;
              })
          .when(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
      ch.qos.logback.classic.Logger logger =
          (ch.qos.logback.classic.Logger)
              org.slf4j.LoggerFactory.getLogger(OrderOutboxDispatcher.class);
      ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent> warnings =
          new ch.qos.logback.core.read.ListAppender<>();
      warnings.start();
      logger.addAppender(warnings);
      try {
        dispatcher(rabbit).dispatchPending();
      } finally {
        logger.detachAppender(warnings);
        warnings.stop();
      }
      long completionFailures =
          warnings.list.stream()
              .filter(
                  event ->
                      event.getLevel() == ch.qos.logback.classic.Level.WARN
                          && event.getThrowableProxy() != null
                          && event
                              .getThrowableProxy()
                              .getClassName()
                              .endsWith("OptimisticLockingFailureException"))
              .count();
      assertAll(
          () -> assertEquals("PUBLISHED".equals(state) ? 0 : 1, completionFailures, state),
          () ->
              assertEquals(
                  established.get(),
                  fixture.queryForList("SELECT * FROM order_outbox WHERE id=1"),
                  state),
          () -> assertEquals(List.of(first.eventId, second.eventId), sentIds, state),
          () ->
              assertEquals(
                  "PUBLISHED",
                  fixture.queryForObject(
                      "SELECT status FROM order_outbox WHERE id=2", String.class),
                  state));
    }
  }

  @Test
  void interruptingAConfirmWaitPreservesAllRowsAndStopsTheFollowingEvent() throws Exception {
    OrderOutboxPO first = pendingOutbox();
    OrderOutboxPO second = pendingOutbox();
    second.id = 2L;
    second.eventId = "after-interrupted-confirm";
    second.orderNo = "202610-after-interrupt";
    second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
    assertEquals(1, mapper.insert(first));
    assertEquals(1, mapper.insert(second));
    var before = fixture.queryForList("SELECT * FROM order_outbox ORDER BY id");
    java.util.concurrent.CountDownLatch firstSent = new java.util.concurrent.CountDownLatch(1);
    java.util.concurrent.atomic.AtomicBoolean signalAtExit =
        new java.util.concurrent.atomic.AtomicBoolean();
    java.util.concurrent.atomic.AtomicReference<Throwable> unexpected =
        new java.util.concurrent.atomic.AtomicReference<>();
    List<String> sentIds = new java.util.concurrent.CopyOnWriteArrayList<>();
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(4);
              sentIds.add(correlation.getId());
              if (first.eventId.equals(correlation.getId())) firstSent.countDown();
              else correlation.getFuture().set(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    OrderOutboxDispatcher dispatcher =
        new OrderOutboxDispatcher(
            mapper,
            rabbit,
            new ObjectMapper(),
            new MockEnvironment()
                .withProperty("cloudmall.order.outbox.confirm-timeout-ms", "30000"));
    Thread worker =
        new Thread(
            () -> {
              try {
                dispatcher.dispatchPending();
              } catch (Throwable failure) {
                unexpected.set(failure);
              } finally {
                signalAtExit.set(Thread.currentThread().isInterrupted());
              }
            },
            "outbox-interrupted-confirm-test");
    try {
      worker.start();
      assertTrue(firstSent.await(5, java.util.concurrent.TimeUnit.SECONDS));
      worker.interrupt();
      worker.join(10000);
      assertFalse(worker.isAlive());
    } finally {
      worker.interrupt();
      worker.join(5000);
      assertFalse(worker.isAlive());
    }
    var after = fixture.queryForList("SELECT * FROM order_outbox ORDER BY id");
    assertAll(
        () -> assertNull(unexpected.get()),
        () -> assertTrue(signalAtExit.get()),
        () -> assertEquals(List.of(first.eventId), sentIds),
        () -> assertEquals(before, after));
  }

  @Test
  void negativeRowsCannotFillTheScanLimitAndStarveEligibleEvents() {
    LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(2).withNano(0);
    for (int rowIndex = 1; rowIndex <= 50; rowIndex++) {
      OrderOutboxPO invalid = pendingOutbox();
      invalid.id = (long) rowIndex;
      invalid.eventId = "invalid-head-" + rowIndex;
      invalid.orderNo = "202610-invalid-" + rowIndex;
      invalid.attempts = rowIndex % 2 == 0 ? -1 : Integer.MIN_VALUE;
      invalid.nextRetryAt = dueAt;
      invalid.lastError = "keep invalid head";
      assertEquals(1, mapper.insert(invalid));
    }
    var invalidBefore =
        fixture.queryForList("SELECT * FROM order_outbox WHERE attempts<0 ORDER BY id");
    OrderOutboxPO first = pendingOutbox();
    first.id = 101L;
    first.eventId = "eligible-zero";
    first.orderNo = "202610-eligible-zero";
    first.nextRetryAt = dueAt.plusSeconds(1);
    OrderOutboxPO second = pendingOutbox();
    second.id = 102L;
    second.eventId = "eligible-maximum";
    second.orderNo = "202610-eligible-max";
    second.attempts = Integer.MAX_VALUE;
    second.nextRetryAt = dueAt.plusSeconds(1);
    assertEquals(1, mapper.insert(first));
    assertEquals(1, mapper.insert(second));
    List<String> expectedIds = List.of(first.eventId, second.eventId);
    List<String> scannedIds =
        mapper.findPendingOutbox(50).stream().map(row -> row.eventId).toList();
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    List<String> sentIds = new ArrayList<>();
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(4);
              sentIds.add(correlation.getId());
              correlation.getFuture().set(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    dispatcher(rabbit).dispatchPending();
    var invalidAfter =
        fixture.queryForList("SELECT * FROM order_outbox WHERE attempts<0 ORDER BY id");
    List<Map<String, Object>> healthy =
        fixture.queryForList(
            "SELECT status,attempts FROM order_outbox WHERE id IN (101,102) ORDER BY id");
    List<OrderOutboxPO> remaining = mapper.findPendingOutbox(50);
    assertAll(
        () -> assertEquals(expectedIds, scannedIds),
        () -> assertEquals(expectedIds, sentIds),
        () -> assertEquals(invalidBefore, invalidAfter),
        () ->
            assertEquals(
                List.of("PUBLISHED", "PUBLISHED"),
                healthy.stream().map(row -> row.get("status")).toList()),
        () -> assertEquals(0, ((Number) healthy.get(0).get("attempts")).intValue()),
        () -> assertEquals(Integer.MAX_VALUE, ((Number) healthy.get(1).get("attempts")).intValue()),
        () -> assertTrue(remaining.isEmpty()));
  }

  @Test
  void eligibleScanRetainsDueStatusOrderingAndLimitSemantics() {
    LocalDateTime dueAt = LocalDateTime.now(ZoneOffset.ofHours(8)).minusMinutes(3).withNano(0);
    List<Long> ids = List.of(1L, 2L, 3L, 4L, 15L, 12L, 11L);
    for (int rowIndex = 0; rowIndex < ids.size(); rowIndex++) {
      OrderOutboxPO outbox = pendingOutbox();
      outbox.id = ids.get(rowIndex);
      outbox.eventId = "scan-order-" + outbox.id;
      outbox.orderNo = "202610-scan-" + outbox.id;
      outbox.nextRetryAt = dueAt;
      if (rowIndex == 0) outbox.attempts = -1;
      else if (rowIndex == 1) {
        outbox.status = "PUBLISHED";
        outbox.publishedAt = outbox.createdAt;
      } else if (rowIndex == 2) outbox.status = "OTHER";
      else if (rowIndex == 3)
        outbox.nextRetryAt = LocalDateTime.now(ZoneOffset.ofHours(8)).plusHours(1);
      else if (rowIndex == 4) {
        outbox.attempts = Integer.MAX_VALUE;
        outbox.nextRetryAt = dueAt.plusSeconds(1);
      } else if (rowIndex == 6) outbox.attempts = Integer.MAX_VALUE - 1;
      assertEquals(1, mapper.insert(outbox));
    }
    var before = fixture.queryForList("SELECT * FROM order_outbox ORDER BY id");
    List<OrderOutboxPO> allEligible = mapper.findPendingOutbox(50);
    assertEquals(List.of(11L, 12L, 15L), allEligible.stream().map(row -> row.id).toList());
    assertEquals(
        List.of(Integer.MAX_VALUE - 1, 0, Integer.MAX_VALUE),
        allEligible.stream().map(row -> row.attempts).toList());
    assertEquals(
        List.of(11L, 12L), mapper.findPendingOutbox(2).stream().map(row -> row.id).toList());
    for (int limit : List.of(0, -1, Integer.MIN_VALUE, 1))
      assertEquals(
          List.of(11L), mapper.findPendingOutbox(limit).stream().map(row -> row.id).toList());
    assertEquals(before, fixture.queryForList("SELECT * FROM order_outbox ORDER BY id"));
    fixture.execute("DROP TABLE order_outbox");
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    OrderOutboxDispatcher dispatcher = dispatcher(rabbit);
    clearInvocations(rabbit);
    assertThrows(org.springframework.dao.DataAccessException.class, dispatcher::dispatchPending);
    verifyNoInteractions(rabbit);
  }

  @Test
  void aFailureRecordSqlErrorMustNotPreventTheFollowingCandidate() {
    OrderOutboxPO first = pendingOutbox();
    first.lastError = "keep first fact";
    OrderOutboxPO second = pendingOutbox();
    second.id = 2L;
    second.eventId = "after-failure-write";
    second.orderNo = "202610-after-failure";
    second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
    assertEquals(1, mapper.insert(first));
    assertEquals(1, mapper.insert(second));
    var firstBefore = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
    fixture.execute(
        "CREATE TRIGGER cs_failure_record BEFORE UPDATE ON order_outbox FOR EACH ROW BEGIN IF"
            + " OLD.id=1 THEN SIGNAL SQLSTATE '45000' SET MESSAGE_TEXT='first failure write"
            + " rejected'; END IF; END");
    RabbitTemplate rabbit = failingRabbit("broker unavailable");
    RuntimeException failure = null;
    try {
      dispatcher(rabbit).dispatchPending();
    } catch (RuntimeException rejected) {
      failure = rejected;
    }
    var firstAfter = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
    var secondAfter = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=2");
    RuntimeException captured = failure;
    assertAll(
        () -> assertNull(captured),
        () -> assertEquals(firstBefore, firstAfter),
        () -> assertEquals(1, ((Number) secondAfter.get("attempts")).intValue()),
        () -> assertEquals("broker unavailable", secondAfter.get("last_error")),
        () -> assertEquals("PENDING", secondAfter.get("status")),
        () ->
            verify(rabbit, times(2))
                .convertAndSend(
                    anyString(), anyString(), any(), any(), any(CorrelationData.class)));
  }

  @Test
  void aConfirmedPublishSqlErrorCannotBeRewrittenAsASendFailure() {
    OrderOutboxPO first = pendingOutbox();
    first.lastError = "keep confirmed event fact";
    OrderOutboxPO second = pendingOutbox();
    second.id = 2L;
    second.eventId = "after-publish-write";
    second.orderNo = "202610-after-publish";
    second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
    assertEquals(1, mapper.insert(first));
    assertEquals(1, mapper.insert(second));
    var firstBefore = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
    fixture.execute(
        "CREATE TRIGGER cs_published_record BEFORE UPDATE ON order_outbox FOR EACH ROW BEGIN IF"
            + " OLD.id=1 AND NEW.status='PUBLISHED' THEN SIGNAL SQLSTATE '45000' SET"
            + " MESSAGE_TEXT='first published write rejected'; END IF; END");
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    doAnswer(
            invocation -> {
              CorrelationData correlation = invocation.getArgument(4);
              correlation.getFuture().set(new CorrelationData.Confirm(true, null));
              return null;
            })
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    dispatcher(rabbit).dispatchPending();
    var firstAfter = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
    var secondAfter = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=2");
    assertAll(
        () -> assertEquals(firstBefore, firstAfter),
        () -> assertEquals("PUBLISHED", secondAfter.get("status")),
        () -> assertEquals(0, ((Number) secondAfter.get("attempts")).intValue()),
        () ->
            verify(rabbit, times(2))
                .convertAndSend(
                    anyString(), anyString(), any(), any(), any(CorrelationData.class)));
  }

  @Test
  void invalidCounterSnapshotsCannotPublishButFollowingHealthyEventsStillCan() {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    for (int invalidCount : List.of(-1, Integer.MIN_VALUE)) {
      for (int healthyCount : List.of(0, Integer.MAX_VALUE)) {
        fixture.update("DELETE FROM order_outbox");
        OrderOutboxPO invalid = pendingOutbox();
        invalid.attempts = invalidCount;
        invalid.lastError = "keep invalid counter fact";
        OrderOutboxPO healthy = pendingOutbox();
        healthy.id = 2L;
        healthy.eventId = "healthy-after-invalid";
        healthy.orderNo = "202610-healthy";
        healthy.attempts = healthyCount;
        healthy.nextRetryAt = invalid.nextRetryAt.plusSeconds(1);
        assertEquals(1, mapper.insert(invalid));
        assertEquals(1, mapper.insert(healthy));
        var invalidBefore = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
        RabbitTemplate rabbit = mock(RabbitTemplate.class);
        List<String> published = new ArrayList<>();
        doAnswer(
                invocation -> {
                  CorrelationData correlation = invocation.getArgument(4);
                  published.add(correlation.getId());
                  correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                  return null;
                })
            .when(rabbit)
            .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
        dispatcher(rabbit).dispatchPending();
        var invalidAfter = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=1");
        var healthyAfter = fixture.queryForMap("SELECT * FROM order_outbox WHERE id=2");
        String label = invalidCount + ":" + healthyCount;
        checks.add(() -> assertEquals(invalidBefore, invalidAfter, label));
        checks.add(() -> assertEquals(List.of(healthy.eventId), published, label));
        checks.add(() -> assertEquals("PUBLISHED", healthyAfter.get("status"), label));
        checks.add(
            () ->
                assertEquals(
                    healthyCount, ((Number) healthyAfter.get("attempts")).intValue(), label));
      }
    }
    assertAll(checks);
  }

  @Test
  void calendarCutoverDatesAreReadAsUnmodifiedLocalDateTimes() throws Exception {
    System.out.println("CM17DN scan JVM zone: " + java.time.ZoneId.systemDefault());
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderOutboxMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (String field : List.of("next_retry_at", "created_at", "updated_at", "published_at")) {
          for (String date :
              List.of("1582-10-04", "1582-10-05", "1582-10-10", "1582-10-14", "1582-10-15")) {
            modeJdbc.update("DELETE FROM order_outbox");
            OrderOutboxPO original = pendingOutbox();
            assertEquals(1, modeMapper.insert(original));
            String storedValue = date + " 12:34:56.789";
            // 白名单字段和明确文本保存合法ISO日期，取证全程不用Timestamp中转。
            modeJdbc.update("UPDATE order_outbox SET " + field + "=? WHERE id=1", storedValue);
            var before = outboxTextFacts(modeJdbc, 1L);
            assertEquals(storedValue, before.get(field));
            LocalDateTime expectedTime = LocalDateTime.parse(date + "T12:34:56.789");
            LocalDateTime expectedRetry =
                field.equals("next_retry_at") ? expectedTime : original.nextRetryAt;
            LocalDateTime expectedCreated =
                field.equals("created_at") ? expectedTime : original.createdAt;
            LocalDateTime expectedUpdated =
                field.equals("updated_at") ? expectedTime : original.updatedAt;
            LocalDateTime expectedPublished = field.equals("published_at") ? expectedTime : null;
            List<OrderOutboxPO> selected = List.of();
            RuntimeException scanFailure = null;
            try {
              selected = modeMapper.findPendingOutbox(1);
            } catch (RuntimeException rejected) {
              scanFailure = rejected;
            }
            var warnings = modeJdbc.queryForList("SHOW WARNINGS");
            var after = outboxTextFacts(modeJdbc, 1L);
            RuntimeException captured = scanFailure;
            List<OrderOutboxPO> candidates = selected;
            String label = mode + ":" + field + ":" + date;
            checks.add(() -> assertNull(captured, label));
            checks.add(() -> assertEquals(1, candidates.size(), label));
            checks.add(() -> assertEquals(before, after, label));
            checks.add(
                () ->
                    assertTrue(
                        warnings.stream()
                            .allMatch(warning -> ((Number) warning.get("Code")).intValue() == 1287),
                        label + ":" + warnings));
            if (candidates.size() == 1) {
              OrderOutboxPO candidate = candidates.get(0);
              checks.add(() -> assertEquals(original.eventId, candidate.eventId, label));
              checks.add(() -> assertEquals(expectedRetry, candidate.nextRetryAt, label));
              checks.add(() -> assertEquals(expectedCreated, candidate.createdAt, label));
              checks.add(() -> assertEquals(expectedUpdated, candidate.updatedAt, label));
              checks.add(() -> assertEquals(expectedPublished, candidate.publishedAt, label));
            }
          }
        }
      }
    }
    assertAll(checks);
  }

  @Test
  void impossibleCalendarDatesCannotAbortTheScanOrOccupyItsLimit() throws Exception {
    assertInvalidDateCandidatesAreExcluded(
        List.of(
            "2026-02-29 00:00:00.000",
            "2026-02-30 00:00:00.000",
            "2026-04-31 00:00:00.000",
            "2026-09-31 00:00:00.000",
            "1900-02-29 00:00:00.000",
            "1000-02-29 00:00:00.000"),
        "ALLOW_INVALID_DATES");
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderOutboxMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (String validDate :
            List.of(
                "2000-02-29",
                "2024-02-29",
                "1900-02-28",
                "1000-02-28",
                "2026-04-30",
                "2026-01-31")) {
          modeJdbc.update("DELETE FROM order_outbox");
          OrderOutboxPO valid = pendingOutbox();
          LocalDateTime timestamp = LocalDateTime.parse(validDate + "T12:00:00.123");
          valid.nextRetryAt = timestamp;
          valid.createdAt = timestamp;
          valid.updatedAt = timestamp;
          valid.publishedAt = timestamp;
          assertEquals(1, modeMapper.insert(valid));
          var original = outboxTextFacts(modeJdbc, 1L);
          List<OrderOutboxPO> selected = modeMapper.findPendingOutbox(1);
          assertEquals(1, selected.size(), mode + ":" + validDate);
          OrderOutboxPO candidate = selected.get(0);
          assertEquals(valid.eventId, candidate.eventId);
          assertEquals(timestamp, candidate.nextRetryAt);
          assertEquals(timestamp, candidate.createdAt);
          assertEquals(timestamp, candidate.updatedAt);
          assertEquals(timestamp, candidate.publishedAt);
          assertEquals(original, outboxTextFacts(modeJdbc, 1L));
        }
      }
    }
  }

  @Test
  void partialZeroDatesCannotAbortTheScanOrOccupyItsLimit() throws Exception {
    assertInvalidDateCandidatesAreExcluded(
        List.of("2026-00-15 00:00:00.000", "2026-02-00 00:00:00.000", "2026-00-00 00:00:00.000"));
  }

  @Test
  void zeroDateCandidatesCannotAbortTheScanOrOccupyItsLimit() throws Exception {
    assertInvalidDateCandidatesAreExcluded(List.of("0000-00-00 00:00:00.000"));
  }

  /** 仅隔离库构造指定零日期，逐字段/模式断言窗口、真实SQL投递和不可变坏事实；broker为确认替身。 */
  private void assertInvalidDateCandidatesAreExcluded(List<String> invalidDates) throws Exception {
    assertInvalidDateCandidatesAreExcluded(invalidDates, "");
  }

  /** 指定造数模式仅限隔离连接；读取仍覆盖strict/empty，非法时间以文本核验，不修改原库。 */
  private void assertInvalidDateCandidatesAreExcluded(
      List<String> invalidDates, String fixtureWriteMode) throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = spy(new OrderOutboxMapper(modeJdbc));
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        for (String zeroField :
            List.of("next_retry_at", "created_at", "updated_at", "published_at")) {
          for (String invalidDate : invalidDates) {
            modeJdbc.execute("SET SESSION sql_mode='" + fixtureWriteMode + "'");
            modeJdbc.update("DELETE FROM order_outbox");
            OrderOutboxPO invalid = pendingOutbox();
            OrderOutboxPO healthy = pendingOutbox();
            healthy.id = 2L;
            healthy.eventId = "after-zero-date";
            healthy.orderNo = "202610-after-zero-date";
            healthy.nextRetryAt = invalid.nextRetryAt.plusSeconds(1);
            OrderOutboxPO later = pendingOutbox();
            later.id = 3L;
            later.eventId = "after-healthy-event";
            later.orderNo = "202610-after-healthy-event";
            later.nextRetryAt = invalid.nextRetryAt.plusSeconds(2);
            assertEquals(1, modeMapper.insert(invalid));
            assertEquals(1, modeMapper.insert(healthy));
            assertEquals(1, modeMapper.insert(later));
            // 字段名仅来自上述固定白名单；异常数据只在本例随机隔离库构造。
            modeJdbc.update("UPDATE order_outbox SET " + zeroField + "=? WHERE id=1", invalidDate);
            assertEquals(invalidDate, outboxTextFacts(modeJdbc, 1L).get(zeroField));
            var originalInvalid = outboxTextFacts(modeJdbc, 1L);
            modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
            List<OrderOutboxPO> selected = List.of();
            RuntimeException scanFailure = null;
            try {
              selected = modeMapper.findPendingOutbox(1);
            } catch (RuntimeException rejected) {
              scanFailure = rejected;
            }
            var warnings = modeJdbc.queryForList("SHOW WARNINGS");
            List<OrderOutboxPO> candidates = selected;
            RuntimeException capturedScan = scanFailure;
            String label = mode + ":" + zeroField + ":" + invalidDate;
            checks.add(() -> assertNull(capturedScan, label));
            // 仅保留原event_type BINARY弃用warning，新增日期门不得引入转换告警。
            checks.add(
                () ->
                    assertTrue(
                        warnings.stream()
                            .allMatch(warning -> ((Number) warning.get("Code")).intValue() == 1287),
                        label + ":" + warnings));
            checks.add(
                () ->
                    assertEquals(
                        List.of(healthy.eventId),
                        candidates.stream().map(candidate -> candidate.eventId).toList(),
                        label));
            var invalidAfterScan = outboxTextFacts(modeJdbc, 1L);
            checks.add(() -> assertEquals(originalInvalid, invalidAfterScan, label));
            List<String> sentEvents = new ArrayList<>();
            RabbitTemplate rabbit = mock(RabbitTemplate.class);
            doAnswer(
                    invocation -> {
                      Map<?, ?> envelope = invocation.getArgument(2);
                      sentEvents.add((String) envelope.get("eventId"));
                      CorrelationData correlation = invocation.getArgument(4);
                      correlation.getFuture().set(new CorrelationData.Confirm(true, null));
                      return null;
                    })
                .when(rabbit)
                .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
            RuntimeException dispatchFailure = null;
            try {
              new OrderOutboxDispatcher(
                      modeMapper,
                      rabbit,
                      new ObjectMapper(),
                      new MockEnvironment().withProperty("cloudmall.order.outbox.batch-size", "1"))
                  .dispatchPending();
            } catch (RuntimeException rejected) {
              dispatchFailure = rejected;
            }
            RuntimeException capturedDispatch = dispatchFailure;
            var invalidAfterDispatch = outboxTextFacts(modeJdbc, 1L);
            String healthyStatus =
                modeJdbc.queryForObject("SELECT status FROM order_outbox WHERE id=2", String.class);
            String laterStatus =
                modeJdbc.queryForObject("SELECT status FROM order_outbox WHERE id=3", String.class);
            checks.add(() -> assertNull(capturedDispatch, label));
            checks.add(() -> assertEquals(List.of(healthy.eventId), sentEvents, label));
            checks.add(() -> assertEquals(originalInvalid, invalidAfterDispatch, label));
            checks.add(() -> assertEquals("PUBLISHED", healthyStatus, label));
            checks.add(() -> assertEquals("PENDING", laterStatus, label));
            verify(modeMapper, never()).markOutboxPublished(eq(invalid.eventId), any());
            verify(modeMapper, never()).recordOutboxFailure(eq(invalid.eventId), any(), any());
          }
        }
        // 合法最小/最大非空发布时间与NULL都仍可读，不以PENDING猜测发布时间必须为空。
        for (LocalDateTime publishedAt :
            Arrays.asList(
                null, LocalDateTime.of(1000, 1, 1, 0, 0), LocalDateTime.of(9999, 12, 31, 23, 59))) {
          modeJdbc.update("DELETE FROM order_outbox");
          OrderOutboxPO valid = pendingOutbox();
          valid.publishedAt = publishedAt;
          assertEquals(1, modeMapper.insert(valid));
          List<OrderOutboxPO> selected = modeMapper.findPendingOutbox(1);
          assertEquals(1, selected.size());
          assertEquals(valid.eventId, selected.get(0).eventId);
          assertEquals(publishedAt, selected.get(0).publishedAt);
        }
      }
      assertAll(checks);
    }
  }

  /** 文本读取四个时间，避免取证被零日期的JDBC时间转换中断；保留完整事件事实。 */
  private static Map<String, Object> outboxTextFacts(JdbcTemplate jdbcTemplate, long outboxId) {
    return jdbcTemplate.queryForMap(
        "SELECT"
            + " id,event_id,event_type,order_no,payload,status,attempts,last_error,CAST(next_retry_at"
            + " AS CHAR) AS next_retry_at,CAST(published_at AS CHAR) AS"
            + " published_at,CAST(created_at AS CHAR) AS created_at,CAST(updated_at AS CHAR) AS"
            + " updated_at FROM order_outbox WHERE id=?",
        outboxId);
  }

  @Test
  void publicationTimesAreFlooredBeforeSqlInBothModes() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    List<LocalDateTime> publicationTimes =
        List.of(
            LocalDateTime.parse("2026-10-31T23:59:59.999499"),
            LocalDateTime.parse("2026-10-31T23:59:59.999500"),
            LocalDateTime.parse("2026-10-31T23:59:59.999999"),
            LocalDateTime.parse("2026-12-31T23:59:59.999999"),
            LocalDateTime.parse("1000-01-01T00:00:00.000999"),
            LocalDateTime.parse("9999-12-31T23:59:59.999999"),
            LocalDateTime.parse("2026-10-09T12:00:00.456"));
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderOutboxMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (LocalDateTime publishedAt : publicationTimes) {
          modeJdbc.update("DELETE FROM order_outbox");
          OrderOutboxPO outbox = pendingOutbox();
          outbox.attempts = 19;
          outbox.lastError = "previous failure😀";
          assertEquals(1, modeMapper.insert(outbox));
          var before = modeJdbc.queryForMap("SELECT * FROM order_outbox");
          RuntimeException failure = null;
          int affected = -2;
          try {
            affected = modeMapper.markOutboxPublished(outbox.eventId, publishedAt);
          } catch (RuntimeException rejected) {
            failure = rejected;
          }
          var warnings = modeJdbc.queryForList("SHOW WARNINGS");
          // 两个完成时间以文本读取，保留旧non-strict实现的零日期事实。
          var after =
              modeJdbc.queryForMap(
                  "SELECT id,event_id,event_type,order_no,payload,status,attempts,next_retry_at,"
                      + "last_error,CAST(published_at AS CHAR) AS published_at,created_at,"
                      + "CAST(updated_at AS CHAR) AS updated_at FROM order_outbox");
          RuntimeException captured = failure;
          int result = affected;
          String label = mode + ":" + publishedAt;
          String expectedTime =
              publishedAt
                  .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                  .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd HH:mm:ss.SSS"));
          checks.add(() -> assertNull(captured, label));
          checks.add(() -> assertTrue(warnings.isEmpty(), label));
          checks.add(() -> assertEquals(1, result, label));
          checks.add(() -> assertEquals("PUBLISHED", after.get("status"), label));
          checks.add(() -> assertNull(after.get("last_error"), label));
          checks.add(() -> assertEquals(expectedTime, after.get("published_at"), label));
          checks.add(() -> assertEquals(expectedTime, after.get("updated_at"), label));
          for (String field :
              List.of(
                  "id",
                  "event_id",
                  "event_type",
                  "order_no",
                  "payload",
                  "attempts",
                  "next_retry_at",
                  "created_at")) {
            checks.add(
                () -> assertEquals(before.get(field), after.get(field), label + ":" + field));
          }
        }
      }
    }
    assertAll(checks);
  }

  @Test
  void failureRetryTimesAreFlooredBeforeSqlInBothModes() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    List<LocalDateTime> retryTimes =
        List.of(
            LocalDateTime.parse("2026-10-31T23:59:59.999499"),
            LocalDateTime.parse("2026-10-31T23:59:59.999500"),
            LocalDateTime.parse("2026-10-31T23:59:59.999999"),
            LocalDateTime.parse("2026-12-31T23:59:59.999999"),
            LocalDateTime.parse("1000-01-01T00:00:00.000999"),
            LocalDateTime.parse("9999-12-31T23:59:59.999999"),
            LocalDateTime.parse("2026-10-09T12:00:00.456"));
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderOutboxMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (LocalDateTime retryAt : retryTimes) {
          modeJdbc.update("DELETE FROM order_outbox");
          OrderOutboxPO outbox = pendingOutbox();
          assertEquals(1, modeMapper.insert(outbox));
          var before = modeJdbc.queryForMap("SELECT * FROM order_outbox");
          RuntimeException failure = null;
          int affected = -2;
          try {
            affected = modeMapper.recordOutboxFailure(outbox.eventId, "time failure😀", retryAt);
          } catch (RuntimeException rejected) {
            failure = rejected;
          }
          var warnings = modeJdbc.queryForList("SHOW WARNINGS");
          // 以文本读取retry，保留旧实现non-strict越界写零日期的证据而不触发JDBC读错误。
          var after =
              modeJdbc.queryForMap(
                  "SELECT id,event_id,event_type,order_no,payload,status,attempts,"
                      + "CAST(next_retry_at AS CHAR) AS next_retry_at,last_error,published_at,"
                      + "created_at,updated_at FROM order_outbox");
          String storedRetry = (String) after.get("next_retry_at");
          RuntimeException captured = failure;
          int result = affected;
          String label = mode + ":" + retryAt;
          checks.add(() -> assertNull(captured, label));
          checks.add(() -> assertTrue(warnings.isEmpty(), label));
          checks.add(() -> assertEquals(1, result, label));
          checks.add(
              () ->
                  assertEquals(
                      retryAt
                          .truncatedTo(java.time.temporal.ChronoUnit.MILLIS)
                          .format(
                              java.time.format.DateTimeFormatter.ofPattern(
                                  "uuuu-MM-dd HH:mm:ss.SSS")),
                      storedRetry,
                      label));
          checks.add(() -> assertEquals("PENDING", after.get("status"), label));
          checks.add(() -> assertEquals(1, ((Number) after.get("attempts")).intValue(), label));
          checks.add(() -> assertEquals("time failure😀", after.get("last_error"), label));
          checks.add(() -> assertUnchangedEvent(before, after));
        }
      }
    }
    assertAll(checks);
  }

  @Test
  void failureCountersSaturateWithoutSqlWarningsAndDoNotRepairNegativeFacts() throws Exception {
    List<org.junit.jupiter.api.function.Executable> checks = new ArrayList<>();
    LocalDateTime retryAt = LocalDateTime.of(2026, 10, 9, 12, 0, 0, 456_000_000);
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderOutboxMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (int attempts : List.of(0, 19, Integer.MAX_VALUE - 1, Integer.MAX_VALUE, -1)) {
          modeJdbc.update("DELETE FROM order_outbox");
          OrderOutboxPO outbox = pendingOutbox();
          outbox.attempts = attempts;
          assertEquals(1, modeMapper.insert(outbox));
          var before = modeJdbc.queryForMap("SELECT * FROM order_outbox");
          RuntimeException failure = null;
          int affected = -2;
          try {
            affected = modeMapper.recordOutboxFailure(outbox.eventId, "counter failure😀", retryAt);
          } catch (RuntimeException rejected) {
            failure = rejected;
          }
          var warnings = modeJdbc.queryForList("SHOW WARNINGS");
          var after = modeJdbc.queryForMap("SELECT * FROM order_outbox");
          RuntimeException captured = failure;
          int result = affected;
          String label = mode + ":" + attempts;
          checks.add(() -> assertNull(captured, label));
          checks.add(() -> assertTrue(warnings.isEmpty(), label));
          checks.add(() -> assertEquals(attempts < 0 ? 0 : 1, result, label));
          if (attempts < 0) checks.add(() -> assertEquals(before, after, label));
          else {
            checks.add(
                () ->
                    assertEquals(
                        Math.min((long) attempts + 1L, Integer.MAX_VALUE),
                        ((Number) after.get("attempts")).longValue(),
                        label));
            checks.add(() -> assertEquals("counter failure😀", after.get("last_error"), label));
            checks.add(() -> assertUnchangedEvent(before, after));
            checks.add(() -> assertEquals("PENDING", after.get("status"), label));
            LocalDateTime storedRetry =
                modeJdbc.queryForObject(
                    "SELECT next_retry_at FROM order_outbox", LocalDateTime.class);
            checks.add(() -> assertEquals(retryAt, storedRetry, label));
          }
        }
      }
    }
    assertAll(checks);
  }

  @Test
  void aSaturatedFailureCounterCannotStopTheFollowingPendingEvent() throws Exception {
    OrderOutboxPO first = pendingOutbox();
    first.attempts = Integer.MAX_VALUE;
    OrderOutboxPO second = pendingOutbox();
    second.id = 2L;
    second.eventId = "timeout-following-event";
    second.orderNo = "202610-following";
    second.nextRetryAt = first.nextRetryAt.plusSeconds(1);
    assertEquals(1, mapper.insert(first));
    assertEquals(1, mapper.insert(second));
    var before = fixture.queryForList("SELECT * FROM order_outbox ORDER BY id");
    RabbitTemplate rabbit = failingRabbit("broker unavailable😀");
    RuntimeException failure = null;
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      modeJdbc.execute("SET SESSION sql_mode='STRICT_TRANS_TABLES'");
      var realDispatcher =
          new OrderOutboxDispatcher(
              new OrderOutboxMapper(modeJdbc), rabbit, new ObjectMapper(), new MockEnvironment());
      try {
        realDispatcher.dispatchPending();
      } catch (RuntimeException rejected) {
        failure = rejected;
      }
    }
    var after = fixture.queryForList("SELECT * FROM order_outbox ORDER BY id");
    RuntimeException captured = failure;
    assertAll(
        () -> assertNull(captured),
        () -> assertEquals(Integer.MAX_VALUE, ((Number) after.get(0).get("attempts")).intValue()),
        () -> assertEquals(1, ((Number) after.get(1).get("attempts")).intValue()),
        () -> assertEquals("broker unavailable😀", after.get(0).get("last_error")),
        () -> assertEquals("broker unavailable😀", after.get(1).get("last_error")),
        () ->
            verify(rabbit, times(2))
                .convertAndSend(
                    anyString(), anyString(), any(), any(), any(CorrelationData.class)));
    for (int rowIndex = 0; rowIndex < after.size(); rowIndex++) {
      assertUnchangedEvent(before.get(rowIndex), after.get(rowIndex));
      assertEquals("PENDING", after.get(rowIndex).get("status"));
      assertTrue(
          fixture
              .queryForObject(
                  "SELECT next_retry_at FROM order_outbox WHERE id=?",
                  LocalDateTime.class,
                  after.get(rowIndex).get("id"))
              .isAfter(LocalDateTime.now(ZoneOffset.ofHours(8))));
    }
  }

  @Test
  void concurrentFailureWritesAndRepeatedMaximumPreserveTheEvent() throws Exception {
    for (int attempts : List.of(0, Integer.MAX_VALUE - 1)) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO outbox = pendingOutbox();
      outbox.attempts = attempts;
      assertEquals(1, mapper.insert(outbox));
      var before = fixture.queryForMap("SELECT * FROM order_outbox");
      LocalDateTime retryAt = LocalDateTime.now(ZoneOffset.ofHours(8)).plusMinutes(1).withNano(0);
      java.util.concurrent.CountDownLatch ready = new java.util.concurrent.CountDownLatch(2);
      java.util.concurrent.CountDownLatch start = new java.util.concurrent.CountDownLatch(1);
      java.util.concurrent.ExecutorService executor =
          java.util.concurrent.Executors.newFixedThreadPool(2);
      try {
        var first =
            executor.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                  return mapper.recordOutboxFailure(outbox.eventId, "first failure😀", retryAt);
                });
        var second =
            executor.submit(
                () -> {
                  ready.countDown();
                  assertTrue(start.await(5, java.util.concurrent.TimeUnit.SECONDS));
                  return mapper.recordOutboxFailure(
                      outbox.eventId, "second failure😀", retryAt.plusSeconds(1));
                });
        assertTrue(ready.await(5, java.util.concurrent.TimeUnit.SECONDS));
        start.countDown();
        assertEquals(1, first.get(10, java.util.concurrent.TimeUnit.SECONDS));
        assertEquals(1, second.get(10, java.util.concurrent.TimeUnit.SECONDS));
      } finally {
        start.countDown();
        executor.shutdownNow();
        assertTrue(executor.awaitTermination(5, java.util.concurrent.TimeUnit.SECONDS));
      }
      var after = fixture.queryForMap("SELECT * FROM order_outbox");
      assertUnchangedEvent(before, after);
      assertEquals("PENDING", after.get("status"));
      assertEquals(
          Math.min((long) attempts + 2L, Integer.MAX_VALUE),
          ((Number) after.get("attempts")).longValue());
      String error = String.valueOf(after.get("last_error"));
      assertTrue(List.of("first failure😀", "second failure😀").contains(error));
      assertEquals(
          "first failure😀".equals(error) ? retryAt : retryAt.plusSeconds(1),
          fixture.queryForObject("SELECT next_retry_at FROM order_outbox", LocalDateTime.class));
      if (attempts == Integer.MAX_VALUE - 1) {
        for (int repeatIndex = 0; repeatIndex < 2; repeatIndex++) {
          assertEquals(1, mapper.recordOutboxFailure(outbox.eventId, null, retryAt.plusMinutes(1)));
          assertEquals(
              Integer.MAX_VALUE,
              fixture.queryForObject("SELECT attempts FROM order_outbox", Integer.class));
          assertNull(fixture.queryForMap("SELECT * FROM order_outbox").get("last_error"));
          assertUnchangedEvent(before, fixture.queryForMap("SELECT * FROM order_outbox"));
        }
      }
    }
  }

  @Test
  void mapperPreservesCompleteCharactersAndNullsInBothSqlModes() throws Exception {
    List<String[]> samples =
        List.of(
            new String[] {null, null},
            new String[] {"", ""},
            new String[] {" \t", " \t"},
            new String[] {"短消息😀", "短消息😀"},
            new String[] {"a".repeat(511) + "😀", "a".repeat(511) + "😀"},
            new String[] {"😀".repeat(512), "😀".repeat(512)},
            new String[] {"😀".repeat(513), "😀".repeat(512)},
            new String[] {"a".repeat(510) + "😀中尾", "a".repeat(510) + "😀中"},
            new String[] {"a".repeat(512), "a".repeat(512)},
            new String[] {"a".repeat(513), "a".repeat(512)},
            new String[] {"中".repeat(512), "中".repeat(512)},
            new String[] {"中".repeat(513), "中".repeat(512)});
    LocalDateTime retryAt = LocalDateTime.of(2026, 10, 9, 12, 0, 0, 456_000_000);
    try (var connection = fixture.getDataSource().getConnection()) {
      var modeJdbc = new JdbcTemplate(new SingleConnectionDataSource(connection, true));
      var modeMapper = new OrderOutboxMapper(modeJdbc);
      for (String mode : List.of("STRICT_TRANS_TABLES", "")) {
        modeJdbc.execute("SET SESSION sql_mode='" + mode + "'");
        for (String[] sample : samples) {
          modeJdbc.update("DELETE FROM order_outbox");
          OrderOutboxPO outbox = pendingOutbox();
          assertEquals(1, modeMapper.insert(outbox));
          var before = modeJdbc.queryForMap("SELECT * FROM order_outbox");
          assertEquals(1, modeMapper.recordOutboxFailure(outbox.eventId, sample[0], retryAt));
          var after = modeJdbc.queryForMap("SELECT * FROM order_outbox");
          assertEquals(sample[1], after.get("last_error"));
          assertUnchangedEvent(before, after);
          assertEquals("PENDING", after.get("status"));
          assertEquals(1, ((Number) after.get("attempts")).intValue());
          assertEquals(
              retryAt,
              modeJdbc.queryForObject(
                  "SELECT next_retry_at FROM order_outbox", LocalDateTime.class));
        }
      }
    }
  }

  @Test
  void dispatcherDoesNotSplitUtf8OrLoseMessagesThatFitTheColumn() {
    for (String[] sample :
        List.of(
            new String[] {"中".repeat(333) + "😀", "中".repeat(333) + "😀"},
            new String[] {"😀".repeat(251), "😀".repeat(251)},
            new String[] {"😀".repeat(512), "😀".repeat(512)},
            new String[] {"😀".repeat(513), "😀".repeat(512)})) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO outbox = pendingOutbox();
      assertEquals(1, mapper.insert(outbox));
      var before = fixture.queryForMap("SELECT * FROM order_outbox");
      RabbitTemplate rabbit = failingRabbit(sample[0]);
      var dispatcher = dispatcher(rabbit);
      assertDoesNotThrow(dispatcher::dispatchPending);
      var after = fixture.queryForMap("SELECT * FROM order_outbox");
      assertEquals(sample[1], after.get("last_error"));
      assertUnchangedEvent(before, after);
      assertEquals("PENDING", after.get("status"));
      assertEquals(1, ((Number) after.get("attempts")).intValue());
      assertTrue(
          fixture
              .queryForObject("SELECT next_retry_at FROM order_outbox", LocalDateTime.class)
              .isAfter(LocalDateTime.now(ZoneOffset.ofHours(8))));
      verify(rabbit)
          .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    }
  }

  @Test
  void nullOrBlankExceptionMessagesStillUseTheOriginalClassName() {
    for (String message : Arrays.asList(null, "", " \t")) {
      fixture.update("DELETE FROM order_outbox");
      assertEquals(1, mapper.insert(pendingOutbox()));
      dispatcher(failingRabbit(message)).dispatchPending();
      var after = fixture.queryForMap("SELECT * FROM order_outbox");
      assertEquals("IllegalStateException", after.get("last_error"));
      assertEquals("PENDING", after.get("status"));
      assertEquals(1, ((Number) after.get("attempts")).intValue());
      assertNull(after.get("published_at"));
    }
  }

  @Test
  void nonPendingRowsRemainCompletelyUntouched() {
    for (String status : List.of("PUBLISHED", "OTHER")) {
      fixture.update("DELETE FROM order_outbox");
      OrderOutboxPO outbox = pendingOutbox();
      outbox.status = status;
      if ("PUBLISHED".equals(status)) outbox.publishedAt = outbox.createdAt;
      assertEquals(1, mapper.insert(outbox));
      var before = fixture.queryForMap("SELECT * FROM order_outbox");
      assertEquals(
          0,
          mapper.recordOutboxFailure(
              outbox.eventId, "😀".repeat(513), outbox.nextRetryAt.plusMinutes(1)));
      assertEquals(
          0, mapper.recordOutboxFailure("missing-event", "😀".repeat(513), outbox.nextRetryAt));
      assertEquals(before, fixture.queryForMap("SELECT * FROM order_outbox"));
    }
  }

  @Test
  void failureRecordSqlErrorIsIsolatedWithoutRepeatingThePublish() {
    assertEquals(1, mapper.insert(pendingOutbox()));
    var before = fixture.queryForMap("SELECT * FROM order_outbox");
    fixture.execute(
        "CREATE TRIGGER cm17au_write_failure BEFORE UPDATE ON order_outbox FOR EACH ROW SIGNAL"
            + " SQLSTATE '45000' SET MESSAGE_TEXT='cm17au failure record rejected'");
    RabbitTemplate rabbit = failingRabbit("中".repeat(333) + "😀");
    var dispatcher = dispatcher(rabbit);
    assertDoesNotThrow(dispatcher::dispatchPending);
    assertEquals(before, fixture.queryForMap("SELECT * FROM order_outbox"));
    verify(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
  }

  /** 仅模拟broker抛错，实际Dispatcher和Mapper沿原路径保存本地失败事实。 */
  private static RabbitTemplate failingRabbit(String message) {
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    doThrow(new IllegalStateException(message))
        .when(rabbit)
        .convertAndSend(anyString(), anyString(), any(), any(), any(CorrelationData.class));
    return rabbit;
  }

  /** 使用原默认退避，避免本轮改变消息投递配置。 */
  private OrderOutboxDispatcher dispatcher(RabbitTemplate rabbit) {
    return new OrderOutboxDispatcher(mapper, rabbit, new ObjectMapper(), new MockEnvironment());
  }

  /** 原事件身份、载荷、创建与发布事实不能因记录错误而变化。 */
  private static void assertUnchangedEvent(Map<String, Object> before, Map<String, Object> after) {
    for (String column :
        List.of(
            "id", "event_id", "event_type", "order_no", "payload", "created_at", "published_at"))
      assertEquals(before.get(column), after.get(column), column);
  }

  /** 构造真实到期PENDING事件，JSON时间保持当前上海语义。 */
  private static OrderOutboxPO pendingOutbox() {
    LocalDateTime now = LocalDateTime.now(ZoneOffset.ofHours(8));
    OrderOutboxPO outbox = new OrderOutboxPO();
    outbox.id = 1L;
    outbox.eventId = "timeout-error-event";
    outbox.eventType = "ORDER_TIMEOUT";
    outbox.orderNo = "202610error1";
    outbox.createdAt = now.minusMinutes(5).withNano(0);
    outbox.updatedAt = outbox.createdAt;
    outbox.payload =
        "{\"expireAt\":\""
            + now.plusMinutes(30).atOffset(ZoneOffset.ofHours(8))
            + "\",\"occurredAt\":\""
            + outbox.createdAt.atOffset(ZoneOffset.ofHours(8))
            + "\"}";
    outbox.status = "PENDING";
    outbox.attempts = 0;
    outbox.nextRetryAt = outbox.createdAt;
    return outbox;
  }

  /** 原库核验只读，夹具只能在自行创建的随机库内变动。 */
  private static List<Map<String, Object>> fingerprint() {
    List<Map<String, Object>> result =
        new ArrayList<>(source.queryForList("SHOW TABLES FROM cloudmall_order"));
    for (String table : source.queryForList("SHOW TABLES FROM cloudmall_order", String.class)) {
      if (!table.matches("[A-Za-z0-9_]+")) throw new IllegalStateException("非法源表名");
      result.addAll(source.queryForList("CHECKSUM TABLE cloudmall_order." + table));
    }
    return result;
  }

  /** JDBC会话显式使用上海DATETIME语义。 */
  private static String shanghaiUrl(String url) {
    return url
        + (url.contains("?") ? "&" : "?")
        + "serverTimezone=Asia%2FShanghai&forceConnectionTimeZoneToSession=true";
  }
}
