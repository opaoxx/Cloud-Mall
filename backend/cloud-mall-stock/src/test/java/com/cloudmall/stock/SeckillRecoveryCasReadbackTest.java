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
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
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
            stubClaimThenCurrent(mapper, current);
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
            verify(mapper, times(2)).findSettlement("order-24");
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
    stubClaimThenCurrent(mapper, current);
    assertEquals(1, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
    verify(mapper, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
    verify(mapper, times(2)).findSettlement("order-24");
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
  void successfulOriginalCasStillReadsClaimProofOnly() {
    var mapper = mapper();
    stubClaimThenCurrent(mapper, releasedRow());
    when(mapper.markReleased(eq("order-24"), eq("order-24:ROLLBACK"), eq(2L), eq(7L), any()))
        .thenReturn(1);
    assertEquals(1, new SeckillQuotaSettlementRecovery(mapper, redis()).recoverDue());
    verify(mapper).findSettlement("order-24");
    verify(mapper, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
  }

  @Test
  void readbackFailureKeepsExistingRetryWithOriginalIdentity() {
    var mapper = mapper();
    var redis = redis();
    stubClaimThenFailure(mapper);
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
    var missingRedis = redis();
    when(missingMapper.findDue(any(), eq(50))).thenReturn(List.of(originalMissing));
    stubClaimThenCurrent(missingMapper, originalMissing, currentMissing);
    assertEquals(0, new SeckillQuotaSettlementRecovery(missingMapper, missingRedis).recoverDue());
    verify(missingMapper, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
    verify(missingMapper, never()).markReleased(any(), any(), anyLong(), anyLong(), any());
    verifyNoInteractions(missingRedis);
    verify(missingMapper).findSettlement("order-24");
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

  /** 第一次点读返回本次claim持有的pending原行，第二次返回CAS读回值。 */
  private static void stubClaimThenCurrent(
      SeckillQuotaSettlementMapper mapper, SeckillQuotaSettlementPO current) {
    stubClaimThenCurrent(mapper, row(), current);
  }

  /** 指定第一次claim proof，覆盖原候选创建字段缺失的早期immutable门。 */
  private static void stubClaimThenCurrent(
      SeckillQuotaSettlementMapper mapper,
      SeckillQuotaSettlementPO proofSource,
      SeckillQuotaSettlementPO current) {
    var owner = new AtomicReference<String>();
    var leaseUntil = new AtomicReference<java.time.LocalDateTime>();
    doAnswer(
            invocation -> {
              owner.set(invocation.getArgument(3));
              leaseUntil.set(invocation.getArgument(4));
              return 1;
            })
        .when(mapper)
        .claim(any(), any(), anyLong(), any(), any(), any());
    var reads = new AtomicInteger();
    when(mapper.findSettlement("order-24"))
        .thenAnswer(
            invocation -> {
              if (reads.getAndIncrement() == 0) {
                var proof = copy(proofSource);
                proof.leaseOwner = owner.get();
                proof.leaseUntil = leaseUntil.get();
                return proof;
              }
              return current;
            });
  }

  /** 第一次claim点读正常，CAS失败后的第二次读回抛出，保持旧retry语义。 */
  private static void stubClaimThenFailure(SeckillQuotaSettlementMapper mapper) {
    var owner = new AtomicReference<String>();
    var leaseUntil = new AtomicReference<java.time.LocalDateTime>();
    doAnswer(
            invocation -> {
              owner.set(invocation.getArgument(3));
              leaseUntil.set(invocation.getArgument(4));
              return 1;
            })
        .when(mapper)
        .claim(any(), any(), anyLong(), any(), any(), any());
    when(mapper.findSettlement("order-24"))
        .thenAnswer(
            new org.mockito.stubbing.Answer<SeckillQuotaSettlementPO>() {
              private int reads;

              @Override
              public SeckillQuotaSettlementPO answer(
                  org.mockito.invocation.InvocationOnMock invocation) {
                if (reads++ == 0) {
                  var proof = copy(row());
                  proof.leaseOwner = owner.get();
                  proof.leaseUntil = leaseUntil.get();
                  return proof;
                }
                throw new IllegalStateException("owned-readback-failure");
              }
            });
  }

  private static SeckillQuotaSettlementPO releasedRow() {
    var current = row();
    current.status = "RELEASED";
    current.redisRevision = 7L;
    return current;
  }

  /** 复制读回候选，避免测试把扫描快照和point readback共享同一对象。 */
  private static SeckillQuotaSettlementPO copy(SeckillQuotaSettlementPO source) {
    var copy = new SeckillQuotaSettlementPO();
    copy.id = source.id;
    copy.orderNo = source.orderNo;
    copy.activityId = source.activityId;
    copy.skuId = source.skuId;
    copy.userId = source.userId;
    copy.idempotencyKey = source.idempotencyKey;
    copy.quantity = source.quantity;
    copy.status = source.status;
    copy.revision = source.revision;
    copy.operationKey = source.operationKey;
    copy.createdAt = source.createdAt;
    return copy;
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
