package com.cloudmall.stock;

import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.stock.mapper.StockConsistencyAuditStateMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.cache.StockConsistencyReconciler;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/** 审计不得用数据库计数覆盖受理 Lua 与取消投影之间的窗口。 */
class SeckillQuotaReconciliationTest {
  @Test
  void missingQuotaCannotBeRecreatedEvenWhenDatabaseHasNoAcceptedRows() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ValueOperations<String, String> values = mock(ValueOperations.class);
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    StockSqlMapper stockMapper = mock(StockSqlMapper.class);
    when(redis.keys("seckill:meta:*")).thenReturn(Set.of("seckill:meta:11:22"));
    when(redis.opsForHash()).thenReturn(hashes);
    when(redis.opsForValue()).thenReturn(values);
    when(hashes.get("seckill:meta:11:22", "stockLimit")).thenReturn("5");
    when(stockMapper.countAcceptedReservations(11L, 22L)).thenReturn(0L);
    StockConsistencyReconciler reconciler =
        new StockConsistencyReconciler(
            redis, stockMapper, mock(StockConsistencyAuditStateMapper.class));

    ReflectionTestUtils.invokeMethod(reconciler, "reconcileSeckillStock", (Runnable) () -> {});

    verify(values, never()).set(anyString(), anyString());
    verify(values, never()).increment(anyString());
  }
}
