package com.cloudmall.stock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.cache.StockCacheReconciler;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

class StockCacheWarmStartupTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final StockSqlMapper mapper = mock(StockSqlMapper.class);
  private final StockCacheReconciler reconciler = new StockCacheReconciler(redis, mapper);

  @Test
  void matchingHashDoesNotRewriteAppliedFactsOrRedis() {
    emptyLegacyScan();
    oneFact();
    when(redis.execute(any(RedisCallback.class)))
        .thenReturn(List.of("hash", "8", "4"));

    reconciler.initialize();

    verify(mapper, never()).enqueueCurrentCacheRepairs(any());
    verify(mapper, never()).enqueueCacheRepairsForSkuIds(any(), any());
    verify(redis, times(1)).execute(any(RedisCallback.class));
  }

  @Test
  void missingHashCreatesOnlyItsCurrentVersionRepairIntent() {
    emptyLegacyScan();
    oneFact();
    when(redis.execute(any(RedisCallback.class)))
        .thenReturn(List.of("none", "", ""));

    reconciler.initialize();

    verify(mapper).enqueueCacheRepairsForSkuIds(eq(List.of(7L)), any(OffsetDateTime.class));
    verify(mapper, never()).enqueueCurrentCacheRepairs(any());
  }

  @Test
  void missingHashFieldCreatesRepairIntent() {
    emptyLegacyScan();
    oneFact();
    when(redis.execute(any(RedisCallback.class)))
        .thenReturn(List.of("hash", "", "4"));

    reconciler.initialize();

    verify(mapper).enqueueCacheRepairsForSkuIds(eq(List.of(7L)), any(OffsetDateTime.class));
  }

  @Test
  void incompleteRedisBatchFallsBackWithoutConfirmingApplied() {
    emptyLegacyScan();
    oneFact();
    when(redis.execute(any(RedisCallback.class)))
        .thenReturn(List.of("hash", "8"));

    reconciler.initialize();

    verify(mapper).enqueueCurrentCacheRepairs(any());
    verify(mapper, never()).enqueueCacheRepairsForSkuIds(any(), any());
  }

  @Test
  void newerRedisVersionIsNeverOverwrittenByOlderSnapshot() {
    emptyLegacyScan();
    oneFact();
    when(redis.execute(any(RedisCallback.class)))
        .thenReturn(List.of("hash", "6", "5"));

    reconciler.initialize();

    verify(mapper, never()).enqueueCacheRepairsForSkuIds(any(), any());
  }

  @Test
  void wrongTypeIsBlockedUntilLegacyFactsAreChecked() {
    emptyLegacyScan();
    oneFact();
    ValueOperations<String, String> values = mock(ValueOperations.class);
    when(redis.opsForValue()).thenReturn(values);
    when(redis.execute(any(RedisCallback.class)))
        .thenReturn(List.of("string", "", ""));

    reconciler.initialize();

    verify(values).set("stock:repair-blocked:7", "LEGACY_STRING");
    verify(mapper, never()).enqueueCacheRepairsForSkuIds(any(), any());
  }

  @Test
  void redisOutagePreservesAppliedRowsAndRescansAfterRecovery() {
    Cursor<String> cursor = mock(Cursor.class);
    when(cursor.hasNext()).thenReturn(false);
    when(redis.scan(any(ScanOptions.class)))
        .thenThrow(new RedisConnectionFailureException("down"))
        .thenReturn(cursor);
    oneFact();
    when(redis.execute(any(RedisCallback.class)))
        .thenReturn(List.of("none", "", ""));

    reconciler.initialize();
    reconciler.retryIncompleteCalibration();

    verify(mapper).enqueueCurrentCacheRepairs(any());
    verify(mapper).enqueueCacheRepairsForSkuIds(eq(List.of(7L)), any());
  }

  private void emptyLegacyScan() {
    Cursor<String> cursor = mock(Cursor.class);
    when(cursor.hasNext()).thenReturn(false);
    when(redis.scan(any(ScanOptions.class))).thenReturn(cursor);
  }

  private void oneFact() {
    when(mapper.findAvailableStockBatchAfter(0L, 1000)).thenReturn(List.of(Map.of(
        "sku_id", 7L, "available_quantity", 8, "version", 4)));
  }
}
