package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;

import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.seckill.SeckillReservationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 依赖断连形成熔断后，请求不能继续反复访问 Redis。 */
class StockRedisDependencyTest {
  @AfterEach
  void clearRules() {
    DegradeRuleManager.loadRules(List.of());
  }

  @Test
  void consecutiveRedisFailuresBlockTheNextCallBeforeItRuns() {
    DegradeRuleManager.loadRules(
        List.of(
            new DegradeRule("stock-redis-seckill-write")
                .setGrade(1)
                .setCount(0.5)
                .setMinRequestAmount(2)
                .setStatIntervalMs(1000)
                .setTimeWindow(10)));
    SeckillReservationServiceImpl service =
        StockTestServices.seckillService(
            mock(StringRedisTemplate.class), mock(StockSqlMapper.class), new ObjectMapper());
    AtomicInteger calls = new AtomicInteger();
    Supplier<Object> unavailable =
        () -> {
          calls.incrementAndGet();
          throw new RedisConnectionFailureException("Redis unavailable");
        };

    for (int request = 0; request < 3; request++) {
      BizException failure =
          assertThrows(
              BizException.class,
              () -> ReflectionTestUtils.invokeMethod(service, "redisOperation", unavailable));
      assertEquals(503, failure.getHttpStatus());
    }

    assertEquals(2, calls.get());
  }
}
