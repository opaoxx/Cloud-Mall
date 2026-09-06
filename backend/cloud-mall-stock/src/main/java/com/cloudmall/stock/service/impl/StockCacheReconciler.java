package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.mapper.StockSqlMapper;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;

/** Rebuilds the normal stock cache from the durable stock table after Redis data loss or drift. */
@Component
public class StockCacheReconciler {
  /** 执行 getLogger 相关操作。 */
  private static final Logger log = LoggerFactory.getLogger(StockCacheReconciler.class);

  /** 保存 KEY_PREFIX 的业务状态或配置。 */
  private static final String KEY_PREFIX = "stock:available:";

  /** 保存 redis 的业务状态或配置。 */
  private final StringRedisTemplate redis;

  /** 保存 db 的业务状态或配置。 */
  private final StockSqlMapper stockSqlMapper;

  /** 创建 StockCacheReconciler 实例。 */
  public StockCacheReconciler(StringRedisTemplate redis, StockSqlMapper stockSqlMapper) {
    // 1. 接收并整理 StockCacheReconciler 的业务请求。
    // 2. 执行 StockCacheReconciler 的核心业务校验与状态处理。
    // 3. 返回 StockCacheReconciler 的处理结果。
    this.redis = redis;
    this.stockSqlMapper = stockSqlMapper;
  }

  @PostConstruct
  /** 执行 rebuildFromDatabase 相关操作。 */
  public void rebuildFromDatabase() {
    // 1. 接收并整理 rebuildFromDatabase 的业务请求。
    // 2. 执行 rebuildFromDatabase 的核心业务校验与状态处理。
    // 3. 返回 rebuildFromDatabase 的处理结果。
    List<Map<String, Object>> stocks = stockSqlMapper.findAvailableStock();
    for (Map<String, Object> stock : stocks) {
      Number skuId = (Number) stock.get("sku_id");
      Number available = (Number) stock.get("available_quantity");
      if (skuId == null || available == null) {
        continue;
      }
      redis
          .opsForValue()
          .set(KEY_PREFIX + skuId.longValue(), String.valueOf(Math.max(0, available.intValue())));
    }
    log.info("普通库存 Redis 缓存已从数据库校准，SKU 数量={}", stocks.size());
  }
}
