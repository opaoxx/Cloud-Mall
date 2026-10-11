package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementRecovery;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** CAS读回标签不能代替同一原行、原tuple及当前Lua回执证明。 */
class SeckillRecoveryCasReadbackTest {
  @Test
  void invalidReleasedReadbacksCannotBeCountedAsThisRelease() {
    List<SeckillQuotaSettlementPO> candidates = new ArrayList<>();
    candidates.add(null);
    for (Consumer<SeckillQuotaSettlementPO> mutation : mutations()) {
      var current = row();
      current.status = "RELEASED";
      current.redisRevision = 7L;
      mutation.accept(current);
      candidates.add(current);
    }
    List<Executable> assertions = new ArrayList<>();
    for (var current : candidates)
      assertions.add(
          () -> {
            var mapper = mapper();
            var redis = redis();
            when(mapper.findSettlement("order-24")).thenReturn(current);
            assertEquals(0, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
            verify(mapper)
                .retry(
                    eq("order-24"),
                    eq("order-24:ROLLBACK"),
                    eq(2L),
                    anyString(),
                    eq("SECKILL_RELEASE_CAS_UNKNOWN"),
                    any(),
                    any());
            verify(mapper)
                .markReleased(eq("order-24"), eq("order-24:ROLLBACK"), eq(2L), eq(7L), any());
            verify(mapper).findSettlement("order-24");
            verify(mapper).findDue(any(), eq(50));
            verify(mapper)
                .claim(eq("order-24"), eq("order-24:ROLLBACK"), eq(2L), any(), any(), any());
            verifyNoMoreInteractions(mapper);
            verify(redis)
                .execute(
                    any(RedisScript.class),
                    anyList(),
                    eq("order-24"),
                    eq("order-24:ROLLBACK"),
                    eq("2"),
                    eq("1"));
            verifyNoMoreInteractions(redis);
          });
    assertAll(assertions);
  }

  @Test
  void exactAnotherWorkerReleasedFactIgnoresMutableDiagnosticFields() {
    var current = row();
    current.status = "RELEASED";
    current.redisRevision = 7L;
    current.updatedAt = LocalDateTime.of(2026, 10, 12, 1, 0);
    current.leaseOwner = "another-worker";
    current.leaseUntil = LocalDateTime.of(2026, 10, 12, 1, 1);
    current.lastError = "diagnostic-only";
    current.nextRetryAt = LocalDateTime.of(2026, 10, 12, 1, 2);
    var mapper = mapper();
    var redis = redis();
    when(mapper.findSettlement("order-24")).thenReturn(current);
    assertEquals(1, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
    verify(mapper, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
    verify(mapper).findSettlement("order-24");
    verify(redis)
        .execute(
            any(RedisScript.class),
            anyList(),
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq("2"),
            eq("1"));
    verifyNoMoreInteractions(redis);
  }

  @Test
  void successfulOriginalCasStillNeedsNoReadback() {
    var mapper = mapper();
    when(mapper.markReleased(eq("order-24"), eq("order-24:ROLLBACK"), eq(2L), eq(7L), any()))
        .thenReturn(1);
    assertEquals(1, new SeckillQuotaSettlementRecovery(mapper, redis()).recoverDue());
    verify(mapper, never()).findSettlement(any());
    verify(mapper, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void readbackFailureKeepsExistingRetryWithOriginalIdentity() {
    var mapper = mapper();
    var redis = redis();
    when(mapper.findSettlement("order-24"))
        .thenThrow(new IllegalStateException("owned-readback-failure"));
    assertEquals(0, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
    verify(mapper)
        .retry(
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq(2L),
            any(),
            contains("owned-readback-failure"),
            any(),
            any());
    verify(redis)
        .execute(
            any(RedisScript.class),
            anyList(),
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq("2"),
            eq("1"));
    verifyNoMoreInteractions(redis);
    // 原/current创建字段均缺失时不能把两个NULL当作同一原行证明。
    var originalMissing = row();
    originalMissing.createdAt = null;
    var currentMissing = row();
    currentMissing.status = "RELEASED";
    currentMissing.redisRevision = 7L;
    currentMissing.createdAt = null;
    var missingMapper = mapper();
    when(missingMapper.findDue(any(), eq(50))).thenReturn(List.of(originalMissing));
    when(missingMapper.findSettlement("order-24")).thenReturn(currentMissing);
    assertEquals(0, new SeckillQuotaSettlementRecovery(missingMapper, redis()).recoverDue());
    verify(missingMapper)
        .retry(
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq(2L),
            any(),
            eq("SECKILL_RELEASE_CAS_UNKNOWN"),
            any(),
            any());
  }

  /** 各原字段独立变异；NULL NOTNULL列只unit，不改变DDL。 */
  private static List<Consumer<SeckillQuotaSettlementPO>> mutations() {
    List<Consumer<SeckillQuotaSettlementPO>> mutations = new ArrayList<>();
    mutations.add(current -> current.id = null);
    mutations.add(current -> current.id = 2L);
    for (String order : Arrays.asList(null, "other", "ORDER-24", "órdér-24", "order-24 "))
      mutations.add(current -> current.orderNo = order);
    for (Long dimension : Arrays.asList(null, 0L, 99L)) {
      mutations.add(current -> current.activityId = dimension);
      mutations.add(current -> current.skuId = dimension);
      mutations.add(current -> current.userId = dimension);
    }
    for (String key : Arrays.asList(null, "other", "ORIGINAL-KEY", "original-key "))
      mutations.add(current -> current.idempotencyKey = key);
    for (Integer quantity : Arrays.asList(null, 0, 2))
      mutations.add(current -> current.quantity = quantity);
    for (Long revision : Arrays.asList(null, 1L, 3L))
      mutations.add(current -> current.revision = revision);
    for (String operation :
        Arrays.asList(
            null, "other:ROLLBACK", "order-24:CONFIRM", "ORDER-24:ROLLBACK", "order-24:ROLLBACK "))
      mutations.add(current -> current.operationKey = operation);
    mutations.add(current -> current.createdAt = null);
    mutations.add(current -> current.createdAt = current.createdAt.plusNanos(1000000));
    for (Long receipt : Arrays.asList(null, 0L, -1L, 8L))
      mutations.add(current -> current.redisRevision = receipt);
    for (String state : Arrays.asList(null, "CONFIRMED", "released"))
      mutations.add(current -> current.status = state);
    return mutations;
  }

  /** 合法原候选，CAS返回0触发真实被测分支。 */
  private static SeckillQuotaSettlementMapper mapper() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    when(mapper.findDue(any(), eq(50))).thenReturn(List.of(row()));
    when(mapper.claim(any(), any(), eq(2L), any(), any(), any())).thenReturn(1);
    return mapper;
  }

  /** 固定本次原Lua回执；只验证读回分支，不伪称真实Redis。 */
  private static StringRedisTemplate redis() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
        .thenReturn(7L);
    return redis;
  }

  /** 同一原持久行的基础tuple与创建时间。 */
  private static SeckillQuotaSettlementPO row() {
    var original = new SeckillQuotaSettlementPO();
    original.id = 1L;
    original.orderNo = "order-24";
    original.activityId = 11L;
    original.skuId = 22L;
    original.userId = 33L;
    original.idempotencyKey = "original-key";
    original.quantity = 1;
    original.revision = 2L;
    original.operationKey = "order-24:ROLLBACK";
    original.status = "RELEASE_PENDING";
    original.createdAt = LocalDateTime.of(2000, 1, 1, 0, 0);
    return original;
  }
}
