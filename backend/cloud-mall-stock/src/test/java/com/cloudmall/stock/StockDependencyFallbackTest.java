package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.vo.StockQuantityVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;

/** Redis 不可用时仍能读 MySQL 事实，双依赖故障不得返回虚假零库存。 */
class StockDependencyFallbackTest {
  @Test
  void redisFailureReturnsTheDurableMysqlQuantity() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.type(any())).thenThrow(new RedisConnectionFailureException("Redis down"));
    StockSqlMapper database = mock(StockSqlMapper.class);
    when(database.findStock(7L)).thenReturn(Map.of("available_quantity", 15));

    StockQuantityVO result =
        (StockQuantityVO)
            new StockServiceImpl(redis, database, mock(RabbitTemplate.class), new ObjectMapper())
                .get(7L)
                .data;

    assertEquals(15, result.availableQuantity);
  }

  @Test
  void bothDependenciesUnavailableReturns503RatherThanZero() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(redis.type(any())).thenThrow(new RedisConnectionFailureException("Redis down"));
    StockSqlMapper database = mock(StockSqlMapper.class);
    when(database.findStock(7L)).thenThrow(new DataAccessResourceFailureException("Database down"));

    BizException failure =
        assertThrows(
            BizException.class,
            () ->
                new StockServiceImpl(
                        redis, database, mock(RabbitTemplate.class), new ObjectMapper())
                    .get(7L));

    assertEquals("STOCK_SERVICE_UNAVAILABLE", failure.getCode());
    assertEquals(503, failure.getHttpStatus());
  }
}
