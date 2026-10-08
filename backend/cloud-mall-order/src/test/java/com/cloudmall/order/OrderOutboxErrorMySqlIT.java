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

/** 超时outbox真实SQL错误记录，broker异常为替身，每例只写随机数据库。 */
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
