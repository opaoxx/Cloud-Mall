package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.*;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.messaging.SeckillRequestFingerprint;
import com.cloudmall.stock.domain.dto.SeckillReservationDTO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillAcceptanceService;
import com.cloudmall.stock.service.impl.SeckillRequestClaimService;
import com.cloudmall.stock.service.impl.SeckillReservationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.Instant;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.test.util.ReflectionTestUtils;

class RedisOutageSeckillTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final StockSqlMapper mapper = mock(StockSqlMapper.class);
  private final SeckillRequestClaimService claims = mock(SeckillRequestClaimService.class);
  private final SeckillAcceptanceService acceptance = mock(SeckillAcceptanceService.class);
  private final SeckillReservationServiceImpl service =
      StockTestServices.seckillService(redis, mapper, new ObjectMapper());

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void redisFailureAfterClaimReturns503WithoutAccepting() {
    prepareClaim("202609original");
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hashes);
    when(hashes.entries(any())).thenThrow(new RedisConnectionFailureException("redis down"));

    BizException failure =
        assertThrows(
            BizException.class,
            () ->
                service.seckill(request(), com.cloudmall.common.auth.AuthContext.requireUserId()));

    assertEquals(503, failure.getHttpStatus());
    assertEquals("REDIS_SERVICE_UNAVAILABLE", failure.getCode());
    verifyNoInteractions(acceptance);
  }

  @Test
  void missingActivityMetadataRemainsRetryable() {
    prepareClaim("202609original");
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hashes);
    when(hashes.entries(any())).thenReturn(Map.of());

    BizException failure =
        assertThrows(
            BizException.class,
            () ->
                service.seckill(request(), com.cloudmall.common.auth.AuthContext.requireUserId()));

    assertEquals(503, failure.getHttpStatus());
    verifyNoInteractions(acceptance);
    verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
  }

  @Test
  void retryUsesPersistedOrderPayloadWhenRedisMarkerExists() {
    String originalOrderNo = "202609original";
    String originalPayload = prepareClaim(originalOrderNo);
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForHash()).thenReturn(hashes);
    when(redis.opsForValue()).thenReturn(values);
    Map<Object, Object> meta = new HashMap<>();
    meta.put("startAt", Instant.now().minusSeconds(60).toString());
    meta.put("endAt", Instant.now().plusSeconds(3600).toString());
    meta.put("perUserLimit", "1");
    meta.put("stockLimit", "5");
    when(hashes.entries(any())).thenReturn(meta);
    when(redis.execute(
            any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(3L);
    when(values.get(any())).thenReturn(originalOrderNo);

    service.seckill(request(), com.cloudmall.common.auth.AuthContext.requireUserId());

    verify(acceptance).accept(eq(33L), eq("retry-key"), eq(originalOrderNo), any(),
        eq(originalPayload), any(), any());
  }

  @Test
  void unknownLuaResultNeverReportsAcceptance() {
    prepareClaim("202609original");
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hashes);
    Map<Object, Object> meta = new HashMap<>();
    meta.put("startAt", Instant.now().minusSeconds(60).toString());
    meta.put("endAt", Instant.now().plusSeconds(3600).toString());
    meta.put("perUserLimit", "1");
    meta.put("stockLimit", "5");
    when(hashes.entries(any())).thenReturn(meta);

    BizException failure =
        assertThrows(
            BizException.class,
            () ->
                service.seckill(request(), com.cloudmall.common.auth.AuthContext.requireUserId()));

    assertEquals(503, failure.getHttpStatus());
    verifyNoInteractions(acceptance);
  }

  @Test
  void userPurchaseLimitKeepsItsBusinessCodeDistinctFromSentinel() {
    prepareClaim("202610original");
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    when(redis.opsForHash()).thenReturn(hashes);
    Map<Object, Object> meta = new HashMap<>();
    meta.put("startAt", Instant.now().minusSeconds(60).toString());
    meta.put("endAt", Instant.now().plusSeconds(3600).toString());
    meta.put("perUserLimit", "1");
    meta.put("stockLimit", "5");
    when(hashes.entries(any())).thenReturn(meta);
    when(redis.execute(
            any(RedisScript.class), anyList(), any(), any(), any(), any(), any(), any(), any()))
        .thenReturn(2L);

    BizException limited =
        assertThrows(
            BizException.class,
            () ->
                service.seckill(request(), com.cloudmall.common.auth.AuthContext.requireUserId()));

    assertEquals(429, limited.getHttpStatus());
    assertEquals("SECKILL_LIMIT_REACHED", limited.getCode());
    verifyNoInteractions(acceptance);
  }

  private String prepareClaim(String originalOrderNo) {
    ReflectionTestUtils.setField(service, "seckillRequestClaimService", claims);
    ReflectionTestUtils.setField(service, "seckillAcceptanceService", acceptance);
    AuthContext.set(33L, "USER");
    String hash = SeckillRequestFingerprint.hash(11L, 22L, 33L, 1, "{}");
    String originalPayload = "{\"activityId\":11,\"skuId\":22,\"userId\":33,"
        + "\"quantity\":1,\"idempotencyKey\":\"retry-key\","
        + "\"addressSnapshot\":\"{}\",\"orderNo\":\"" + originalOrderNo + "\"}";
    when(claims.claim(eq(11L), eq(22L), eq(33L), any(), eq("retry-key"), any(),
        eq(hash), any())).thenReturn(Map.of("order_no", originalOrderNo,
            "request_hash", hash, "request_payload", originalPayload, "status", "PENDING"));
    return originalPayload;
  }

  private static SeckillReservationDTO request() {
    SeckillReservationDTO request = new SeckillReservationDTO();
    request.activityId = 11L;
    request.skuId = 22L;
    request.userId = 33L;
    request.idempotencyKey = "retry-key";
    request.addressSnapshot = "{}";
    return request;
  }
}
