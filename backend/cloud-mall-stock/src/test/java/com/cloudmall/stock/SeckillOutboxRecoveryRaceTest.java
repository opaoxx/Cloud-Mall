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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.mock.env.MockEnvironment;

class SeckillOutboxRecoveryRaceTest {
  @Test
  void interruptedAcceptanceFailureDoesNotStartVerificationQueries() {
    pendingRow(Instant.now());
    when(redis.opsForValue()).thenReturn(values);
    when(values.get(anyString())).thenReturn(ORDER_NO);
    doAnswer(
            invocation -> {
              Thread.currentThread().interrupt();
              throw new IllegalStateException("fixture acceptance interrupted");
            })
        .when(acceptance)
        .accept(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    try {
      dispatcher.dispatch();
      assertTrue(Thread.currentThread().isInterrupted());
    } finally {
      Thread.interrupted();
    }
    verify(acceptance, times(1))
        .accept(anyLong(), anyString(), anyString(), anyString(), anyString(), anyString(), any());
    verify(mapper, never()).findSeckillByOrderNo(anyString());
    verify(mapper, never()).findSeckillOutboxByOrderNo(anyString());
    verify(mapper, never()).findPendingSeckillOutbox(any(), anyInt());
  }

  @Test
  void unexpectedUnknownTransitionErrorsStillPropagate() {
    pendingRow(Instant.now().minusSeconds(60));
    when(redis.opsForValue()).thenReturn(values);
    for (Throwable fault :
        List.of(
            new IllegalStateException("fixture transition bug"),
            new AssertionError("fixture fatal transition"))) {
      clearInvocations(mapper, acceptance);
      doThrow(fault).when(mapper).markSeckillUnknown(anyLong(), anyString(), anyString(), any());
      org.junit.jupiter.api.Assertions.assertSame(
          fault,
          org.junit.jupiter.api.Assertions.assertThrows(fault.getClass(), dispatcher::dispatch));
      verify(mapper, times(1)).markSeckillUnknown(anyLong(), anyString(), anyString(), any());
      verify(mapper, never()).findPendingSeckillOutbox(any(), anyInt());
      verifyNoInteractions(acceptance);
    }
  }

  @Test
  void unexpectedMarkerReadFailuresAreNotTreatedAsRedisUnavailability() {
    pendingRow(Instant.now());
    when(redis.opsForValue()).thenReturn(values);
    for (Throwable fault :
        List.of(
            new com.cloudmall.common.error.BizException("OTHER_FAILURE", "fixture", 503),
            new com.cloudmall.common.error.BizException(
                "REDIS_SERVICE_UNAVAILABLE", "fixture", 400),
            new IllegalStateException("fixture programming failure"),
            new AssertionError("fixture fatal failure"))) {
      clearInvocations(mapper, acceptance);
      doThrow(fault).when(values).get(anyString());
      org.junit.jupiter.api.Assertions.assertSame(
          fault,
          org.junit.jupiter.api.Assertions.assertThrows(fault.getClass(), dispatcher::dispatch));
      verify(mapper, never()).markSeckillUnknown(anyLong(), anyString(), anyString(), any());
      verify(mapper, never()).findPendingSeckillOutbox(any(), anyInt());
      verifyNoInteractions(acceptance);
    }
  }

  @Test
  void databaseTimeTypesUseShanghaiIndependentOfTheJvmDefaultZone() {
    java.util.TimeZone originalZone = java.util.TimeZone.getDefault();
    java.time.LocalDateTime updated = java.time.LocalDateTime.parse("2026-10-07T11:59:00");
    org.springframework.test.util.ReflectionTestUtils.setField(
        dispatcher,
        "markerClock",
        java.time.Clock.fixed(Instant.parse("2026-10-07T04:00:00Z"), java.time.ZoneOffset.UTC));
    when(redis.opsForValue()).thenReturn(values);
    when(mapper.markSeckillUnknown(anyLong(), anyString(), anyString(), any())).thenReturn(1);
    try {
      for (String zone : List.of("UTC", "GMT+14:00")) {
        java.util.TimeZone.setDefault(java.util.TimeZone.getTimeZone(zone));
        for (Object timestamp :
            List.of(
                updated,
                Timestamp.valueOf(updated),
                updated
                    .atOffset(java.time.ZoneOffset.ofHours(8))
                    .withOffsetSameInstant(java.time.ZoneOffset.UTC))) {
          clearInvocations(mapper, acceptance);
          pendingRowValue(timestamp);
          dispatcher.dispatch();
          verify(mapper).markSeckillUnknown(eq(3L), eq("key"), anyString(), any());
          verifyNoInteractions(acceptance);
        }
      }
    } finally {
      java.util.TimeZone.setDefault(originalZone);
    }
  }

  @Test
  void graceBoundaryAndUnknownTimesDoNotGuessExpiration() {
    java.time.LocalDateTime now = java.time.LocalDateTime.parse("2026-10-07T12:00:00");
    org.springframework.test.util.ReflectionTestUtils.setField(
        dispatcher,
        "markerClock",
        java.time.Clock.fixed(
            now.toInstant(java.time.ZoneOffset.ofHours(8)), java.time.ZoneOffset.UTC));
    when(redis.opsForValue()).thenReturn(values);
    when(mapper.markSeckillUnknown(anyLong(), anyString(), anyString(), any())).thenReturn(1);
    for (Object timestamp :
        new Object[] {
          null,
          "2026-10-07T00:00:00",
          now.plusDays(1),
          now.minusSeconds(29),
          now.minusSeconds(30),
          now.minusSeconds(30).minusNanos(1_000_000)
        }) {
      clearInvocations(mapper);
      pendingRowValue(timestamp);
      dispatcher.dispatch();
      verify(mapper, times(now.minusSeconds(30).minusNanos(1_000_000).equals(timestamp) ? 1 : 0))
          .markSeckillUnknown(anyLong(), anyString(), anyString(), any());
    }
  }

  private static final String ORDER_NO = "202610recovery-race";
  private static final String EVENT_ID = SeckillRequestFingerprint.eventId(ORDER_NO);
  private final StockSqlMapper mapper = mock(StockSqlMapper.class);
  private final SeckillAcceptanceService acceptance = mock(SeckillAcceptanceService.class);
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final ValueOperations<String, String> values = mock(ValueOperations.class);
  private final SeckillOrderOutboxDispatcher dispatcher =
      new SeckillOrderOutboxDispatcher(
          mapper,
          acceptance,
          redis,
          mock(RabbitTemplate.class),
          new ObjectMapper(),
          new MockEnvironment());
  private final Logger logger =
      (Logger) LoggerFactory.getLogger(SeckillOrderOutboxDispatcher.class);
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
        .when(acceptance)
        .accept(anyLong(), any(), eq(ORDER_NO), eq(EVENT_ID), any(), eq("hash"), any());
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
        .when(acceptance)
        .accept(anyLong(), any(), eq(ORDER_NO), eq(EVENT_ID), any(), eq("hash"), any());
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
        .when(acceptance)
        .accept(anyLong(), any(), eq(ORDER_NO), eq(EVENT_ID), any(), eq("hash"), any());
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

    assertTrue(
        logs.list.stream()
            .anyMatch(
                event ->
                    event.getLevel() == Level.ERROR
                        && event.getFormattedMessage().contains("已转 UNKNOWN")));
  }

  private void pendingRow(Instant updatedAt) {
    pendingRowValue(Timestamp.from(updatedAt));
  }

  private void pendingRowValue(Object updatedAt) {
    Map<String, Object> row =
        new java.util.LinkedHashMap<>(
            Map.of(
                "activity_id",
                1L,
                "sku_id",
                2L,
                "user_id",
                3L,
                "order_no",
                ORDER_NO,
                "idempotency_key",
                "key",
                "request_payload",
                "{}",
                "request_hash",
                "hash"));
    row.put("updated_at", updatedAt);
    row.put("id", 1L);
    when(mapper.findPendingSeckillReservations(anyInt())).thenReturn(List.of(row));
    when(mapper.findPendingSeckillOutbox(any(), anyInt())).thenReturn(List.of());
  }

  private void completedFact() {
    completedFact("PUBLISHED", EVENT_ID);
  }

  private void completedFact(String outboxStatus, String outboxEventId) {
    when(mapper.findSeckillByOrderNo(ORDER_NO))
        .thenReturn(
            Map.of(
                "order_no",
                ORDER_NO,
                "user_id",
                3L,
                "idempotency_key",
                "key",
                "request_hash",
                "hash",
                "status",
                "ACCEPTED"));
    when(mapper.findSeckillOutboxByOrderNo(ORDER_NO))
        .thenReturn(
            Map.of("event_id", outboxEventId, "request_hash", "hash", "status", outboxStatus));
  }

  private void captureLogs() {
    logs.start();
    logger.addAppender(logs);
  }

  private boolean hasLogAtLeast(Level level) {
    return logs.list.stream().anyMatch(event -> event.getLevel().isGreaterOrEqual(level));
  }
}
