package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.util.GuardedStockRedisTemplate;
import com.cloudmall.stock.util.StockDependencyGuard;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.connection.RedisConnectionFactory;

/** 验证真实 RedisTemplate 的 ValueOps 委托进入统一保护，不依赖服务显式包装。 */
class StockRedisTemplateGuardTest {
  @AfterEach
  void clearRules() {
    DegradeRuleManager.loadRules(List.of());
  }

  @Test
  void valueOpsStopsAcquiringConnectionsAfterCircuitOpens() {
    DegradeRuleManager.loadRules(
        List.of(
            new DegradeRule(StockDependencyGuard.REDIS_PROJECTION)
                .setGrade(1)
                .setCount(.5)
                .setMinRequestAmount(2)
                .setStatIntervalMs(1000)
                .setTimeWindow(10)));
    RedisConnectionFactory connections = mock(RedisConnectionFactory.class);
    AtomicInteger attempts = new AtomicInteger();
    when(connections.getConnection())
        .thenAnswer(
            invocation -> {
              attempts.incrementAndGet();
              throw new RedisConnectionFailureException("Redis unavailable");
            });
    GuardedStockRedisTemplate template = new GuardedStockRedisTemplate(connections);

    for (int request = 0; request < 3; request++) {
      assertThrows(BizException.class, () -> template.opsForValue().get("stock:available:7"));
    }

    assertEquals(2, attempts.get());
  }
}
