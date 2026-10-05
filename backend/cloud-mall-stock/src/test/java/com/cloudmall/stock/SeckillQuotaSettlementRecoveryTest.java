package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementRecovery;
import java.util.List;
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
    when(mapper.markReleased(eq("order-24"), eq("order-24:ROLLBACK"), eq(2L), eq(7L), anyString()))
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
            any());
    verify(mapper)
        .markReleased(eq("order-24"), eq("order-24:ROLLBACK"), eq(2L), eq(7L), anyString());
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
            eq("order-24"), eq("order-24:ROLLBACK"), eq(2L), anyString(), any(), anyString());
    verify(mapper, never())
        .markReleased(anyString(), anyString(), anyLong(), anyLong(), anyString());
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
    settlement.orderNo = "order-24";
    settlement.operationKey = "order-24:ROLLBACK";
    settlement.activityId = 11L;
    settlement.skuId = 22L;
    settlement.userId = 33L;
    settlement.idempotencyKey = "original-key";
    settlement.status = "RELEASE_PENDING";
    settlement.revision = 2L;
    settlement.quantity = 1;
    SeckillQuotaSettlementMapper mapper = mock(SeckillQuotaSettlementMapper.class);
    when(mapper.findDue(any(), anyInt())).thenReturn(List.of(settlement));
    when(mapper.claim(anyString(), anyString(), eq(2L), anyString(), any(), any())).thenReturn(1);
    return mapper;
  }
}
