package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementRecovery;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 独立恢复入口的坏原候选必须在claim与Redis前拒绝。 */
class SeckillRecoveryCandidateGuardTest {
  @Test
  void invalidCandidatesCannotClaimLeaseOrCallRedis() {
    List<SeckillQuotaSettlementPO> candidates = new ArrayList<>();
    candidates.add(null);
    for (Consumer<SeckillQuotaSettlementPO> mutation : invalidMutations()) {
      var original = row();
      mutation.accept(original);
      candidates.add(original);
    }
    List<Executable> assertions = new ArrayList<>();
    for (var candidate : candidates)
      assertions.add(
          () -> {
            var mapper = mock(SeckillQuotaSettlementMapper.class);
            var redis = mock(StringRedisTemplate.class);
            when(mapper.findDue(any(), eq(50))).thenReturn(Arrays.asList(candidate));
            when(mapper.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(1);
            when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
                .thenReturn(7L);
            when(mapper.markReleased(any(), any(), anyLong(), anyLong(), any())).thenReturn(1);
            assertEquals(0, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
            verify(mapper).findDue(any(), eq(50));
            verifyNoMoreInteractions(mapper);
            verifyNoInteractions(redis);
          });
    assertAll(assertions);
  }

  @Test
  void validCandidatesPreserveOriginalUnicodeKeysAndRollbackProtocol() {
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String idempotency :
          List.of("original-key", " original-key ", "NULL", "null ", "😺".repeat(128))) {
        var original = row();
        original.status = state;
        original.idempotencyKey = idempotency;
        original.orderNo = "😺".repeat(64);
        original.operationKey = original.orderNo + ":ROLLBACK";
        var mapper = mock(SeckillQuotaSettlementMapper.class);
        var redis = mock(StringRedisTemplate.class);
        when(mapper.findDue(any(), eq(50))).thenReturn(List.of(original));
        when(mapper.claim(
                eq(original.orderNo), eq(original.operationKey), eq(2L), any(), any(), any()))
            .thenReturn(1);
        List<String> expectedKeys =
            List.of(
                "seckill:stock:11:22",
                "seckill:meta:11:22",
                "seckill:accepted:11:22:33:" + idempotency,
                "seckill:quota:op:" + original.orderNo,
                "seckill:quota:revision:11:22");
        when(redis.execute(
                any(RedisScript.class),
                eq(expectedKeys),
                eq(original.orderNo),
                eq(original.operationKey),
                eq("2"),
                eq("1")))
            .thenReturn(7L);
        when(mapper.markReleased(
                eq(original.orderNo), eq(original.operationKey), eq(2L), eq(7L), any()))
            .thenReturn(1);
        assertEquals(1, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
        verify(redis)
            .execute(
                any(RedisScript.class),
                eq(expectedKeys),
                eq(original.orderNo),
                eq(original.operationKey),
                eq("2"),
                eq("1"));
        verify(mapper)
            .markReleased(eq(original.orderNo), eq(original.operationKey), eq(2L), eq(7L), any());
        verifyNoMoreInteractions(redis);
        assertEquals(idempotency, original.idempotencyKey);
      }
  }

  @Test
  void validBlockedConfirmDirectionRemainsSkipped() {
    var original = row();
    original.status = "PROJECTION_BLOCKED";
    original.operationKey = original.orderNo + ":CONFIRM";
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var redis = mock(StringRedisTemplate.class);
    when(mapper.findDue(any(), eq(50))).thenReturn(List.of(original));
    assertEquals(0, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
    verify(mapper).findDue(any(), eq(50));
    verifyNoMoreInteractions(mapper);
    verifyNoInteractions(redis);
  }

  @Test
  void badCandidateAheadOfHealthyCandidateDoesNotPreventRecovery() {
    var invalid = row();
    invalid.operationKey = "other:ROLLBACK";
    var healthy = row();
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var redis = mock(StringRedisTemplate.class);
    when(mapper.findDue(any(), eq(50))).thenReturn(List.of(invalid, healthy));
    when(mapper.claim(any(), any(), anyLong(), any(), any(), any())).thenReturn(1);
    when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
        .thenReturn(7L);
    when(mapper.markReleased(any(), any(), anyLong(), anyLong(), any())).thenReturn(1);
    assertEquals(1, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
    verify(mapper).findDue(any(), eq(50));
    verify(mapper)
        .claim(eq(healthy.orderNo), eq(healthy.operationKey), eq(2L), any(), any(), any());
    verify(mapper)
        .markReleased(eq(healthy.orderNo), eq(healthy.operationKey), eq(2L), eq(7L), any());
    verifyNoMoreInteractions(mapper);
  }

  /** 原候选各字段独立变异；NULL/超长仅unit，不假称正常DDL可存。 */
  private static List<Consumer<SeckillQuotaSettlementPO>> invalidMutations() {
    List<Consumer<SeckillQuotaSettlementPO>> mutations = new ArrayList<>();
    for (String order : Arrays.asList(null, "", " ", "null", "😺".repeat(65)))
      mutations.add(row -> row.orderNo = order);
    for (String operation :
        Arrays.asList(
            null,
            "",
            "other:ROLLBACK",
            "ORDER-24:ROLLBACK",
            "órdér-24:ROLLBACK",
            "order-24 :ROLLBACK",
            "order-24:ROLLBACK ")) mutations.add(row -> row.operationKey = operation);
    for (String state :
        Arrays.asList(
            null, "OPEN", "CONFIRMED", "RELEASED", "CONFLICT", "release_pending", "unknown"))
      mutations.add(row -> row.status = state);
    for (Long dimension : Arrays.asList(null, 0L, -1L)) {
      mutations.add(row -> row.activityId = dimension);
      mutations.add(row -> row.skuId = dimension);
      mutations.add(row -> row.userId = dimension);
    }
    for (String key :
        Arrays.asList(null, "", " ", "\t\n", "\u2003", "null", "x".repeat(129), "😺".repeat(129)))
      mutations.add(row -> row.idempotencyKey = key);
    for (Long revision : Arrays.asList(null, 0L, 1L, 3L))
      mutations.add(row -> row.revision = revision);
    for (Integer quantity : Arrays.asList(null, 0, 2))
      mutations.add(row -> row.quantity = quantity);
    return mutations;
  }

  /** 原合法待释放候选，无source证明/自动修复含义。 */
  private static SeckillQuotaSettlementPO row() {
    var original = new SeckillQuotaSettlementPO();
    original.orderNo = "order-24";
    original.operationKey = "order-24:ROLLBACK";
    original.status = "RELEASE_PENDING";
    original.revision = 2L;
    original.quantity = 1;
    original.activityId = 11L;
    original.skuId = 22L;
    original.userId = 33L;
    original.idempotencyKey = "original-key";
    return original;
  }
}
