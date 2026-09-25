package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockConsistencyReconciler;
import java.time.Duration;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.ValueOperations;

/** 旧实例审计结束时不能删除锁过期后由新实例取得的租约。 */
class StockConsistencyLockTest {
  @Test
  void expiredOwnerDoesNotDeleteNewOwnersLock() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    AtomicReference<String> lockValue = new AtomicReference<>();
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(
            anyString(), anyString(), any(Duration.class)))
        .thenAnswer(invocation -> {
          lockValue.set(invocation.getArgument(1));
          return true;
        });
    when(stockSqlMapper.findAvailableStock()).thenAnswer(invocation -> {
      lockValue.set("new-owner");
      return List.of();
    });
    when(values.get(anyString())).thenAnswer(invocation -> lockValue.get());
    when(redis.delete(anyString())).thenAnswer(invocation -> {
      lockValue.set(null);
      return true;
    });
    when(redis.execute(any(), any(List.class), any())).thenAnswer(invocation -> {
      String expectedOwner = invocation.getArgument(2);
      return lockValue.compareAndSet(expectedOwner, null) ? 1L : 0L;
    });

    assertThrows(IllegalStateException.class,
        () -> new StockConsistencyReconciler(redis, stockSqlMapper).scheduledAudit());

    assertEquals("new-owner", lockValue.get());
  }

  @Test
  void longAuditRenewsItsOwnedLeaseBeforeFinishing() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    ValueOperations<String, String> values = mock(ValueOperations.class);
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    AtomicReference<String> lockValue = new AtomicReference<>();
    AtomicInteger renewals = new AtomicInteger();
    CountDownLatch renewalSeen = new CountDownLatch(1);
    when(redis.opsForValue()).thenReturn(values);
    when(values.setIfAbsent(anyString(), anyString(), any(Duration.class)))
        .thenAnswer(invocation -> {
          lockValue.set(invocation.getArgument(1));
          return true;
        });
    when(values.get(anyString())).thenAnswer(invocation -> lockValue.get());
    when(stockSqlMapper.findAvailableStock()).thenAnswer(invocation -> {
      renewalSeen.await(8, TimeUnit.SECONDS);
      return List.of();
    });
    when(redis.execute(any(), any(List.class), any())).thenAnswer(invocation -> {
      String owner = invocation.getArgument(2);
      if (invocation.getArguments().length == 4) {
        if (!owner.equals(lockValue.get())) return 0L;
        renewals.incrementAndGet();
        renewalSeen.countDown();
        return 1L;
      }
      return lockValue.compareAndSet(owner, null) ? 1L : 0L;
    });

    new StockConsistencyReconciler(redis, stockSqlMapper).scheduledAudit();

    assertTrue(renewals.get() >= 1);
    assertEquals(null, lockValue.get());
  }
}
