package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillAcceptanceService;
import com.cloudmall.stock.service.impl.SeckillOrderOutboxDispatcher;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.AfterEach;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.env.MockEnvironment;

class SeckillOutboxRecoveryRaceTest {
  private static final String ORDER_NO = "202610recovery-race";
  private static final String EVENT_ID = SeckillRequestFingerprint.eventId(ORDER_NO);
  private final StockSqlMapper mapper = mock(StockSqlMapper.class);
  private final SeckillAcceptanceService acceptance = mock(SeckillAcceptanceService.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> values = mock(ValueOperations.class);
  private final SeckillOrderOutboxDispatcher dispatcher = new SeckillOrderOutboxDispatcher(
      mapper, acceptance, redis, mock(RabbitTemplate.class),
      new ObjectMapper(), new MockEnvironment());
  private final Logger logger = (Logger) LoggerFactory.getLogger(SeckillOrderOutboxDispatcher.class);
  private final ListAppender<ILoggingEvent> logs = new ListAppender<>();

  @AfterEach
  void releaseLogCapture() {
    logger.detachAppender(logs);
    logs.stop();
  }

  @Test
  void concurrentRequestCommitIsRecognizedAfterRecoveryCasLoses() {
    captureLogs();
    pendingRow(Instant.now());
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(any())).thenReturn(ORDER_NO);
    doThrow(new IllegalStateException("秒杀受理记录状态已变化"))
        .when(acceptance).accept(anyLong(), any(), eq(ORDER_NO), eq(EVENT_ID),
            any(), eq("hash"), any());
    completedFact();

    dispatcher.dispatch();

    verify(mapper).findSeckillByOrderNo(ORDER_NO);
    verify(mapper).findSeckillOutboxByOrderNo(ORDER_NO);
    assertFalse(hasLogAtLeast(Level.WARN));
  }

  @Test
  void zeroRowUnknownTransitionChecksWhetherRequestAlreadyCommitted() {
    captureLogs();
    pendingRow(Instant.now().minusSeconds(60));
    when(redis.opsForValue()).thenReturn(values);
    when(mapper.markSeckillUnknown(eq(3L), eq("key"), any(), any())).thenReturn(0);
    completedFact();

    dispatcher.dispatch();

    verify(mapper).findSeckillByOrderNo(ORDER_NO);
    verify(mapper).findSeckillOutboxByOrderNo(ORDER_NO);
    assertFalse(hasLogAtLeast(Level.WARN));
  }

  @Test
  void pendingOutboxIsAlreadyACommittedAcceptance() {
    captureLogs();
    pendingRow(Instant.now());
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(any())).thenReturn(ORDER_NO);
    doThrow(new IllegalStateException("秒杀受理记录状态已变化"))
        .when(acceptance).accept(anyLong(), any(), eq(ORDER_NO), eq(EVENT_ID),
            any(), eq("hash"), any());
    completedFact("PENDING", EVENT_ID);

    dispatcher.dispatch();

    assertFalse(hasLogAtLeast(Level.WARN));
    verify(mapper).findSeckillOutboxByOrderNo(ORDER_NO);
  }

  @Test
  void conflictingEventIdStillRaisesRecoveryWarning() {
    captureLogs();
    pendingRow(Instant.now());
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(any())).thenReturn(ORDER_NO);
    doThrow(new IllegalStateException("秒杀受理记录状态已变化"))
        .when(acceptance).accept(anyLong(), any(), eq(ORDER_NO), eq(EVENT_ID),
            any(), eq("hash"), any());
    completedFact("PUBLISHED", "wrong-event");

    dispatcher.dispatch();

    assertTrue(hasLogAtLeast(Level.WARN));
  }

  @Test
  void successfulUnknownTransitionStillRecordsErrorEvidence() {
    captureLogs();
    pendingRow(Instant.now().minusSeconds(60));
    when(redis.opsForValue()).thenReturn(values);
    when(mapper.markSeckillUnknown(eq(3L), eq("key"), any(), any())).thenReturn(1);

    dispatcher.dispatch();

    assertTrue(logs.list.stream().anyMatch(event -> event.getLevel() == Level.ERROR
        && event.getFormattedMessage().contains("已转 UNKNOWN")));
  }

  private void pendingRow(Instant updatedAt) {
    Map<String, Object> row = Map.of(
        "activity_id", 1L, "sku_id", 2L, "user_id", 3L,
        "order_no", ORDER_NO, "idempotency_key", "key",
        "request_payload", "{}", "request_hash", "hash",
        "updated_at", Timestamp.from(updatedAt));
    when(mapper.findPendingSeckillReservations(anyInt())).thenReturn(List.of(row));
    when(mapper.findPendingSeckillOutbox(any(), anyInt())).thenReturn(List.of());
  }

  private void completedFact() {
    completedFact("PUBLISHED", EVENT_ID);
  }

  private void completedFact(String outboxStatus, String outboxEventId) {
    when(mapper.findSeckillByOrderNo(ORDER_NO)).thenReturn(Map.of(
        "order_no", ORDER_NO, "user_id", 3L, "idempotency_key", "key",
        "request_hash", "hash", "status", "ACCEPTED"));
    when(mapper.findSeckillOutboxByOrderNo(ORDER_NO)).thenReturn(Map.of(
        "event_id", outboxEventId, "request_hash", "hash", "status", outboxStatus));
  }

  private void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  private boolean hasLogAtLeast(Level level) {
    return logs.list.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(level));
  }
}
