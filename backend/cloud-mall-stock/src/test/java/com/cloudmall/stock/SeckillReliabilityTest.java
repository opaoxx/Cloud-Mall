package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillOrderOutboxDispatcher;
import com.cloudmall.stock.service.impl.SeckillRequestClaimService;
import com.cloudmall.stock.service.impl.SeckillReservationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.lang.reflect.Method;
import java.time.OffsetDateTime;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.connection.CorrelationData;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.core.env.Environment;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 秒杀受理和订单 outbox 的可恢复行为回归测试。 */
class SeckillReliabilityTest {
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void legacyAcceptedWithoutOutboxMustRemainRecoveryPending() {
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    SeckillRequestClaimService claimService = mock(SeckillRequestClaimService.class);
    SeckillReservationServiceImpl service =
        StockTestServices.seckillService(redis, mapper, new ObjectMapper());
    ReflectionTestUtils.setField(service, "seckillRequestClaimService", claimService);

    SeckillReservationDTO request = request(11L, 22L, 33L, "legacy-key");
    String hash = SeckillRequestFingerprint.hash(11L, 22L, 33L, 1, request.addressSnapshot);
    String orderNo = "202609legacy-order";
    AuthContext.set(33L, "USER");
    Map<String, Object> acceptedRow = new LinkedHashMap<>();
    acceptedRow.put("order_no", orderNo);
    acceptedRow.put("status", "ACCEPTED");
    acceptedRow.put("request_hash", hash);
    acceptedRow.put("request_payload", null);
    acceptedRow.put("idempotency_key", "legacy-key");
    when(claimService.claim(
            eq(11L), eq(22L), eq(33L), any(), eq("legacy-key"), any(), eq(hash), any()))
        .thenReturn(acceptedRow);
    when(mapper.findSeckillOutboxByOrderNo(orderNo)).thenReturn(null);

    Map<?, ?> data =
        (Map<?, ?>)
            service.seckill(request, com.cloudmall.common.auth.AuthContext.requireUserId()).data;

    assertEquals(Boolean.FALSE, data.get("accepted"));
    assertEquals("RECOVERY_REQUIRED", data.get("reservationStatus"));
    assertEquals("ORDER_RECOVERY_PENDING", data.get("status"));
  }

  @Test
  void publishedEnvelopeContainsStableOrderNoAndNackLeavesOutboxRetryable() throws Exception {
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    Environment environment = mock(Environment.class);
    when(environment.getProperty(
            eq("cloudmall.stock.seckill.outbox.batch-size"), eq(Integer.class), eq(50)))
        .thenReturn(1);
    when(environment.getProperty(
            eq("cloudmall.stock.seckill.outbox.confirm-timeout-ms"), eq(Long.class), eq(5000L)))
        .thenReturn(1000L);
    SeckillOrderOutboxDispatcher dispatcher =
        new SeckillOrderOutboxDispatcher(
            mapper,
            mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class),
            mock(StringRedisTemplate.class),
            rabbit,
            new ObjectMapper(),
            environment);
    String orderNo = "202609stable-order";
    String eventId = SeckillRequestFingerprint.eventId(orderNo);
    String hash = SeckillRequestFingerprint.hash(1, 2, 3, 1, "{}");
    String payload =
        new ObjectMapper()
            .writeValueAsString(
                Map.of(
                    "activityId",
                    1,
                    "skuId",
                    2,
                    "userId",
                    3,
                    "quantity",
                    1,
                    "orderNo",
                    orderNo,
                    "idempotencyKey",
                    "k",
                    "addressSnapshot",
                    "{}"));
    when(mapper.findSeckillByOrderNo(orderNo))
        .thenReturn(
            Map.of(
                "activity_id",
                1L,
                "sku_id",
                2L,
                "user_id",
                3L,
                "order_no",
                orderNo,
                "idempotency_key",
                "k",
                "request_hash",
                hash,
                "status",
                "ACCEPTED",
                "created_at",
                java.time.LocalDateTime.of(2026, 9, 30, 23, 59, 59, 999_000_000)));
    Map<String, Object> row =
        Map.of(
            "id", 1L,
            "event_id", eventId,
            "order_no", orderNo,
            "request_hash", hash,
            "idempotency_key", "k",
            "payload", payload);
    Map<String, Object> sent = new LinkedHashMap<>();
    org.mockito.Mockito.doAnswer(
            invocation -> {
              sent.putAll(invocation.getArgument(2));
              CorrelationData correlation = invocation.getArgument(3);
              correlation.getFuture().set(new CorrelationData.Confirm(false, "nack"));
              return null;
            })
        .when(rabbit)
        .convertAndSend(
            any(String.class), any(String.class), any(Object.class), any(CorrelationData.class));

    when(mapper.findActiveSeckillOutboxLease(eq(1L), eq("owner-1"), any(OffsetDateTime.class)))
        .thenAnswer(
            invocation ->
                ((OffsetDateTime) invocation.getArgument(2)).toLocalDateTime().plusSeconds(30));
    ReflectionTestUtils.invokeMethod(dispatcher, "dispatchOne", row, "owner-1");

    assertEquals(orderNo, sent.get("orderNo"));
    assertEquals(eventId, sent.get("eventId"));
    verify(mapper)
        .recordSeckillOutboxFailure(
            eq(eventId),
            eq("owner-1"),
            any(String.class),
            any(OffsetDateTime.class),
            any(OffsetDateTime.class));
  }

  @Test
  void concurrentAcceptedCommitMustNeverBeRolledBackWhenSecondAcceptanceFails() throws Exception {
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    RabbitTemplate rabbit = mock(RabbitTemplate.class);
    com.cloudmall.stock.service.impl.SeckillAcceptanceService acceptance =
        mock(com.cloudmall.stock.service.impl.SeckillAcceptanceService.class);
    SeckillReservationServiceImpl service =
        StockTestServices.seckillService(redis, mapper, new ObjectMapper());
    ReflectionTestUtils.setField(service, "seckillAcceptanceService", acceptance);

    String hash = "race-hash";
    String orderNo = "202609race-order";
    String eventId = SeckillRequestFingerprint.eventId(orderNo);
    org.mockito.Mockito.doThrow(new IllegalStateException("response lost"))
        .when(acceptance)
        .accept(
            any(Long.class),
            any(String.class),
            eq(orderNo),
            eq(eventId),
            any(String.class),
            eq(hash),
            any());
    when(mapper.findSeckillByOrderNo(orderNo))
        .thenReturn(
            Map.of(
                "status",
                "ACCEPTED",
                "order_no",
                orderNo,
                "activity_id",
                10L,
                "user_id",
                8L,
                "sku_id",
                20L,
                "idempotency_key",
                "private-idem"));
    when(mapper.findSeckillOutboxByOrderNo(orderNo))
        .thenReturn(Map.of("event_id", eventId, "request_hash", hash));

    Method acceptAfterLua =
        SeckillReservationServiceImpl.class.getDeclaredMethod(
            "acceptAfterLua", long.class, String.class, String.class, String.class, String.class);
    acceptAfterLua.setAccessible(true);
    com.cloudmall.common.api.ApiResponse<?> response =
        (com.cloudmall.common.api.ApiResponse<?>)
            acceptAfterLua.invoke(service, 34L, "race-key", orderNo, "{}", hash);
    Map<?, ?> data = (Map<?, ?>) response.data;

    assertEquals(Boolean.TRUE, data.get("accepted"));
    verify(redis, times(0)).execute(any(), any(), any(Object[].class));
  }

  private static SeckillReservationDTO request(
      long activityId, long skuId, long userId, String idempotencyKey) {
    SeckillReservationDTO request = new SeckillReservationDTO();
    request.activityId = activityId;
    request.skuId = skuId;
    request.userId = userId;
    request.idempotencyKey = idempotencyKey;
    request.addressSnapshot = "{}";
    return request;
  }
}
