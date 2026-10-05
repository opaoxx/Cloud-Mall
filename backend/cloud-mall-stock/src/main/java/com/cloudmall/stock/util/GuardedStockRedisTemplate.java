package com.cloudmall.stock.util;

import java.io.Closeable;
import org.springframework.data.redis.connection.RedisConnectionFactory;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 保留原 String 序列化与连接工厂，统一保护 ValueOps/HashOps/Lua/KEYS/SCAN 底层入口。 */
public class GuardedStockRedisTemplate extends StringRedisTemplate {
  /** 用 Spring 自动配置的原连接工厂创建 Stock 专用模板。 */
  public GuardedStockRedisTemplate(RedisConnectionFactory connectionFactory) {
    super(connectionFactory);
  }

  /** 覆盖委托中心，Redis Lua 的 NOSCRIPT 回退仍在同一次原有调用内完成。 */
  @Override
  public <T> T execute(RedisCallback<T> callback, boolean exposeConnection, boolean pipeline) {
    return StockDependencyGuard.call(
        StockDependencyGuard.redisResource(),
        () -> super.execute(callback, exposeConnection, pipeline));
  }

  /** 保护 SCAN 游标创建；缓存校准外层 guard 同时覆盖其完整有界迭代。 */
  @Override
  public <T extends Closeable> T executeWithStickyConnection(RedisCallback<T> callback) {
    return StockDependencyGuard.call(
        StockDependencyGuard.redisResource(), () -> super.executeWithStickyConnection(callback));
  }
}
