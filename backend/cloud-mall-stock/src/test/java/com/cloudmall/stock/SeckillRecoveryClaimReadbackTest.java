package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementRecovery;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** claim成功不等于原tuple和租约仍有效；投影之前必须读回证明。 */
class SeckillRecoveryClaimReadbackTest {
  @Test
  void driftedOrLostClaimCannotCallLuaOrWriteCompensation() {
    List<Executable> assertions = new ArrayList<>();
    for (Consumer<SeckillQuotaSettlementPO> mutation : mutations())
      assertions.add(
          () -> {
            var mapper = mapper(mutation);
            var redis = redis();
            assertEquals(0, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
            verify(mapper).findDue(any(), eq(50));
            verify(mapper)
                .claim(eq("order-24"), eq("order-24:ROLLBACK"), eq(2L), any(), any(), any());
            verify(mapper).findSettlement("order-24");
            verifyNoMoreInteractions(mapper);
            verifyNoInteractions(redis);
          });
    assertions.add(
        () -> {
          var mapper = mapper(current -> {});
          when(mapper.findSettlement("order-24")).thenReturn(null);
          var redis = redis();
          assertEquals(0, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
          verifyNoInteractions(redis);
          verify(mapper, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
        });
    assertAll(assertions);
  }

  @Test
  void healthyClaimAllowsPendingBlockedAndMutableDiagnosticChanges() {
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      var mapper =
          mapper(
              current -> {
                current.status = state;
                current.updatedAt = LocalDateTime.of(2026, 10, 12, 1, 0);
                current.lastError = "diagnostic-only";
              });
      var redis = redis();
      when(mapper.markReleased(any(), any(), eq(2L), eq(7L), any())).thenReturn(1);
      assertEquals(1, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
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
  }

  @Test
  void claimReadFailureUsesOldRetryAndNeverCallsLua() {
    var mapper = mapper(current -> {});
    when(mapper.findSettlement("order-24"))
        .thenThrow(new IllegalStateException("owned-claim-read-failure"));
    var redis = redis();
    assertEquals(0, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
    verifyNoInteractions(redis);
    verify(mapper)
        .retry(
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq(2L),
            any(),
            contains("owned-claim-read-failure"),
            any(),
            any());
  }

  @Test
  void claimPointReadAndCasReadbackRemainSeparateProofs() {
    var mapper = mapper(current -> {});
    var original = row();
    var terminal = row();
    terminal.status = "RELEASED";
    terminal.redisRevision = 7L;
    final SeckillQuotaSettlementPO[] claimed = new SeckillQuotaSettlementPO[1];
    when(mapper.claim(any(), any(), eq(2L), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              claimed[0] = copy(original);
              claimed[0].leaseOwner = invocation.getArgument(3);
              claimed[0].leaseUntil = invocation.getArgument(4);
              when(mapper.findSettlement("order-24")).thenReturn(claimed[0], terminal);
              return 1;
            });
    var redis = redis();
    assertEquals(1, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());
    verify(mapper, times(2)).findSettlement("order-24");
    verify(mapper, never()).retry(any(), any(), anyLong(), any(), any(), any(), any());
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

  /** 每个字段独立变异，包含合法数值却非同原行的tuple。 */
  private static List<Consumer<SeckillQuotaSettlementPO>> mutations() {
    List<Consumer<SeckillQuotaSettlementPO>> mutations = new ArrayList<>();
    mutations.add(current -> current.id = null);
    mutations.add(current -> current.id = 2L);
    mutations.add(current -> current.orderNo = "ORDER-24");
    mutations.add(current -> current.orderNo = "order-24 ");
    mutations.add(current -> current.activityId = 12L);
    mutations.add(current -> current.skuId = 23L);
    mutations.add(current -> current.userId = 34L);
    mutations.add(current -> current.idempotencyKey = "ORIGINAL-KEY");
    mutations.add(current -> current.quantity = 2);
    mutations.add(current -> current.revision = 3L);
    mutations.add(current -> current.operationKey = "order-24:CONFIRM");
    mutations.add(current -> current.createdAt = null);
    mutations.add(current -> current.createdAt = current.createdAt.plusSeconds(1));
    mutations.add(current -> current.status = "RELEASED");
    mutations.add(current -> current.status = "release_pending");
    mutations.add(current -> current.leaseOwner = null);
    mutations.add(current -> current.leaseOwner = "other-owner");
    mutations.add(
        current -> current.leaseOwner = current.leaseOwner.toUpperCase(java.util.Locale.ROOT));
    mutations.add(current -> current.leaseUntil = null);
    mutations.add(
        current ->
            current.leaseUntil = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1));
    return mutations;
  }

  /** claim回执携原owner/lease，fresh PO与扫描snapshot分离以避免共享变异掩盖错误。 */
  private static SeckillQuotaSettlementMapper mapper(Consumer<SeckillQuotaSettlementPO> mutation) {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var original = row();
    when(mapper.findDue(any(), eq(50))).thenReturn(List.of(original));
    var currentClaimed =
        new java.util.concurrent.atomic.AtomicReference<SeckillQuotaSettlementPO>();
    when(mapper.findSettlement("order-24")).thenAnswer(invocation -> currentClaimed.get());
    when(mapper.claim(any(), any(), eq(2L), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              var claimed = copy(original);
              claimed.leaseOwner = invocation.getArgument(3);
              claimed.leaseUntil = invocation.getArgument(4);
              mutation.accept(claimed);
              currentClaimed.set(claimed);
              return 1;
            });
    return mapper;
  }

  /** 单元只模拟合法原Lua回执。 */
  private static StringRedisTemplate redis() {
    var redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
        .thenReturn(7L);
    return redis;
  }

  /** 复制原持久基础字段，不共享可变PO。 */
  private static SeckillQuotaSettlementPO copy(SeckillQuotaSettlementPO original) {
    var copied = new SeckillQuotaSettlementPO();
    copied.id = original.id;
    copied.orderNo = original.orderNo;
    copied.activityId = original.activityId;
    copied.skuId = original.skuId;
    copied.userId = original.userId;
    copied.idempotencyKey = original.idempotencyKey;
    copied.quantity = original.quantity;
    copied.revision = original.revision;
    copied.status = original.status;
    copied.operationKey = original.operationKey;
    copied.createdAt = original.createdAt;
    return copied;
  }

  /** 合法原pending，PK与createdAt按真实NOTNULL准备。 */
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
    original.status = "RELEASE_PENDING";
    original.operationKey = "order-24:ROLLBACK";
    original.createdAt = LocalDateTime.of(2000, 1, 1, 0, 0);
    return original;
  }
}
