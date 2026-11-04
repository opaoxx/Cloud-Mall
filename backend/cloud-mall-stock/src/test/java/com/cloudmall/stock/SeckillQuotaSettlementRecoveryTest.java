package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.service.impl.seckill.SeckillQuotaSettlementRecovery;
import java.time.LocalDateTime;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 验证释放结果未知、缺少投影和租约竞争下的恢复行为。 */
class SeckillQuotaSettlementRecoveryTest {
  @Test
  void lostLuaResponseKeepsTheOriginalRevisionAndRetriesTheReceipt() {
    SeckillQuotaSettlementMapper mapper = mapper();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(
            any(RedisScript.class),
            anyList(),
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq("2"),
            eq("1")))
        .thenThrow(new RedisConnectionFailureException("response lost"))
        .thenReturn(7L);
    when(mapper.markReleased(
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq(2L),
            eq(7L),
            anyString(),
            argThat(SeckillQuotaSettlementRecoveryTest::isOriginal)))
        .thenReturn(1);
    SeckillQuotaSettlementRecovery recovery = new SeckillQuotaSettlementRecovery(mapper, redis);

    assertEquals(0, recovery.recoverDue());
    assertEquals(1, recovery.recoverDue());

    verify(mapper)
        .retry(
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq(2L),
            anyString(),
            anyString(),
            any(),
            any(),
            argThat(SeckillQuotaSettlementRecoveryTest::isOriginal));
    verify(mapper)
        .markReleased(
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq(2L),
            eq(7L),
            anyString(),
            argThat(SeckillQuotaSettlementRecoveryTest::isOriginal));
  }

  @Test
  void missingProjectionCannotMarkReleaseSuccessful() {
    SeckillQuotaSettlementMapper mapper = mapper();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any()))
        .thenReturn(0L);

    assertEquals(0, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());

    verify(mapper)
        .markProjectionBlocked(
            eq("order-24"),
            eq("order-24:ROLLBACK"),
            eq(2L),
            anyString(),
            any(),
            anyString(),
            argThat(SeckillQuotaSettlementRecoveryTest::isOriginal));
    verify(mapper, never())
        .markReleased(
            anyString(),
            anyString(),
            anyLong(),
            anyLong(),
            anyString(),
            argThat(SeckillQuotaSettlementRecoveryTest::isOriginal));
  }

  @Test
  void losingTheDatabaseLeaseNeverCallsRedis() {
    SeckillQuotaSettlementMapper mapper = mapper();
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(mapper.claim(anyString(), anyString(), eq(2L), anyString(), any(), any())).thenReturn(0);

    assertEquals(0, new SeckillQuotaSettlementRecovery(mapper, redis).recoverDue());

    verify(redis, never()).execute(any(RedisScript.class), anyList(), any(Object[].class));
  }

  /** 构造已经持久提交的单条固定释放意图。 */
  private static SeckillQuotaSettlementMapper mapper() {
    SeckillQuotaSettlementPO settlement = new SeckillQuotaSettlementPO();
    settlement.id = 1L;
    settlement.orderNo = "order-24";
    settlement.operationKey = "order-24:ROLLBACK";
    settlement.createdAt = LocalDateTime.of(2000, 1, 1, 0, 0);
    settlement.activityId = 11L;
    settlement.skuId = 22L;
    settlement.userId = 33L;
    settlement.idempotencyKey = "original-key";
    settlement.status = "RELEASE_PENDING";
    settlement.revision = 2L;
    settlement.quantity = 1;
    SeckillQuotaSettlementMapper mapper = mock(SeckillQuotaSettlementMapper.class);
    when(mapper.findDue(any(), anyInt())).thenReturn(List.of(settlement));
    when(mapper.findDueAfter(any(), anyLong(), anyInt())).thenReturn(List.of());
    when(mapper.findMatchingAcceptanceId(any())).thenReturn(1L);
    var owner = new AtomicReference<String>();
    var leaseUntil = new AtomicReference<LocalDateTime>();
    when(mapper.claim(anyString(), anyString(), eq(2L), anyString(), any(), any()))
        .thenAnswer(
            invocation -> {
              owner.set(invocation.getArgument(3));
              leaseUntil.set(invocation.getArgument(4));
              return 1;
            });
    when(mapper.findSettlement(eq("order-24")))
        .thenAnswer(
            invocation -> {
              var current = row();
              current.leaseOwner = owner.get();
              current.leaseUntil = leaseUntil.get();
              return current;
            });
    return mapper;
  }

  /** 与扫描快照独立的claim后读回副本，带真实id/createdAt及本次owner/lease。 */
  private static SeckillQuotaSettlementPO row() {
    var row = new SeckillQuotaSettlementPO();
    row.id = 1L;
    row.orderNo = "order-24";
    row.activityId = 11L;
    row.skuId = 22L;
    row.userId = 33L;
    row.idempotencyKey = "original-key";
    row.status = "RELEASE_PENDING";
    row.revision = 2L;
    row.quantity = 1;
    row.operationKey = "order-24:ROLLBACK";
    row.createdAt = LocalDateTime.of(2000, 1, 1, 0, 0);
    return row;
  }

  /** 追加写参数必须是原扫描tuple，而非claim后的新读回对象。 */
  private static boolean isOriginal(SeckillQuotaSettlementPO value) {
    return value != null
        && Long.valueOf(1L).equals(value.id)
        && "order-24".equals(value.orderNo)
        && Long.valueOf(11L).equals(value.activityId)
        && Long.valueOf(22L).equals(value.skuId)
        && Long.valueOf(33L).equals(value.userId)
        && "original-key".equals(value.idempotencyKey)
        && Integer.valueOf(1).equals(value.quantity)
        && Long.valueOf(2L).equals(value.revision)
        && "order-24:ROLLBACK".equals(value.operationKey)
        && LocalDateTime.of(2000, 1, 1, 0, 0).equals(value.createdAt);
  }
}
