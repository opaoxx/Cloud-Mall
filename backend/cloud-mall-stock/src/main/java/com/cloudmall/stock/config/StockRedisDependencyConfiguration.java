package com.cloudmall.stock.config;

import com.cloudmall.stock.util.GuardedStockRedisTemplate;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 替换 Stock 本身的 Redis Template，认证、端口、数据库和连接池仍由 Boot 配置。 */
@Configuration
public class StockRedisDependencyConfiguration {
  /** 所有 Stock Redis 使用者继续注入相同 StringRedisTemplate 契约。 */
  @Bean
  public StringRedisTemplate stringRedisTemplate(RedisConnectionFactory connectionFactory) {
    return new GuardedStockRedisTemplate(connectionFactory);
  }
}
