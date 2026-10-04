package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.stock.domain.po.StockConsistencyAuditStatePO;
import com.cloudmall.stock.mapper.StockConsistencyAuditStateMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockConsistencyReconciler;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;
import org.springframework.test.util.ReflectionTestUtils;

/** 普通库存周期审计在进程重启和 Redis 故障下保持有界游标。 */
class StockConsistencyAuditTest {
  /** 两个实例依赖同一持久游标，单轮只读固定批次且不全表读取。 */
  @Test
  void twoInstancesContinueFromDurableCursor() {
    Fixture fixture = new Fixture();
    when(fixture.stateMapper.findState()).thenReturn(state(0L), state(10L));
    when(fixture.stockMapper.findAvailableStockBatchAfter(0L, 500))
        .thenReturn(List.of(row(10L)));
    when(fixture.stockMapper.findAvailableStockBatchAfter(10L, 500))
        .thenReturn(List.of(row(20L)));
    when(fixture.redis.execute(any(RedisCallback.class)))
        .thenReturn(List.of("hash", "5", "2"));
    when(fixture.stateMapper.advance(anyLong(), anyLong(), anyInt(), any())).thenReturn(1);

    fixture.reconciler().scheduledAudit();
    fixture.reconciler().scheduledAudit();

    verify(fixture.stockMapper).findAvailableStockBatchAfter(0L, 500);
    verify(fixture.stockMapper).findAvailableStockBatchAfter(10L, 500);
    verify(fixture.stockMapper, never()).findAvailableStock();
    verify(fixture.redis, times(2)).execute(any(RedisCallback.class));
    verify(fixture.stateMapper).advance(eq(0L), eq(10L), eq(1), any());
    verify(fixture.stateMapper).advance(eq(10L), eq(20L), eq(1), any());
  }

  /** 返回不完整时记录失败且保留旧游标，下一轮可以重试同一 SKU。 */
  @Test
  void incompleteRedisBatchDoesNotAdvanceCursor() {
    Fixture fixture = new Fixture();
    when(fixture.stateMapper.findState()).thenReturn(state(0L));
    when(fixture.stockMapper.findAvailableStockBatchAfter(0L, 500))
        .thenReturn(List.of(row(10L)));
    when(fixture.redis.execute(any(RedisCallback.class)))
        .thenReturn(List.of("hash", "5"));

    assertDoesNotThrow(() -> fixture.reconciler().scheduledAudit());

    verify(fixture.stateMapper, never()).advance(anyLong(), anyLong(), anyInt(), any());
    verify(fixture.stateMapper).recordFailure(anyString(), any());
  }

  /** 批次读取中租约转移后，旧实例即使拿到完整结果也不能提交游标。 */
  @Test
  void lostLeaseAfterRedisReadCannotCommitObservedBatch() {
    Fixture fixture = new Fixture();
    when(fixture.stateMapper.findState()).thenReturn(state(0L));
    when(fixture.stockMapper.findAvailableStockBatchAfter(0L, 500))
        .thenReturn(List.of(row(10L)));
    when(fixture.redis.execute(any(RedisCallback.class))).thenAnswer(invocation -> {
      fixture.owner.set("new-owner");
      return List.of("hash", "5", "2");
    });

    assertDoesNotThrow(() -> fixture.reconciler().scheduledAudit());

    assertEquals("new-owner", fixture.owner.get());
    verify(fixture.stateMapper, never()).advance(anyLong(), anyLong(), anyInt(), any());
    verify(fixture.stateMapper).recordFailure(anyString(), any());
  }

  /** Redis 未能取得租约时调度线程正常返回，且不得读取库存全表。 */
  @Test
  void redisOutageBeforeLockIsCaught() {
    Fixture fixture = new Fixture();
    when(fixture.values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    assertDoesNotThrow(() -> fixture.reconciler().scheduledAudit());

    verify(fixture.stockMapper, never()).findAvailableStockBatchAfter(anyLong(), anyInt());
    verify(fixture.stateMapper).recordFailure(anyString(), any());
  }

  /** 为单个 SKU 构造版本 2、可用量 5 的 MySQL 事实。 */
  private static Map<String, Object> row(long skuId) {
    return Map.of("sku_id", skuId, "available_quantity", 5, "version", 2);
  }

  /** 构造一个持久游标状态。 */
  private static StockConsistencyAuditStatePO state(long cursor) {
    StockConsistencyAuditStatePO state = new StockConsistencyAuditStatePO();
    state.stateKey = "NORMAL_STOCK";
    state.cursorSkuId = cursor;
    return state;
  }

  /** 准备当前测试共享的锁、Redis 和 Mapper Mock。 */
  private static final class Fixture {
    private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    private final ValueOperations<String, String> values = mock(ValueOperations.class);
    private final StockSqlMapper stockMapper = mock(StockSqlMapper.class);
    private final StockConsistencyAuditStateMapper stateMapper =
        mock(StockConsistencyAuditStateMapper.class);
    private final AtomicReference<String> owner = new AtomicReference<>();

    /** 设置 token 校验和释放，模拟正常的 Redis 租约。 */
    private Fixture() {
      when(redis.opsForValue()).thenReturn(values);
      when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
          .thenAnswer(invocation -> {
            owner.set(invocation.getArgument(1));
            return true;
          });
      when(values.get("stock:consistency:lock")).thenAnswer(invocation -> owner.get());
      when(redis.execute(any(), any(List.class), any())).thenReturn(1L);
    }

    /** 每次构造新实例模拟重启并强制单次调度只运行一个批次。 */
    private StockConsistencyReconciler reconciler() {
      StockConsistencyReconciler reconciler =
          new StockConsistencyReconciler(redis, stockMapper, stateMapper);
      ReflectionTestUtils.setField(reconciler, "configuredMaxBatches", 1);
      return reconciler;
    }
  }
}
