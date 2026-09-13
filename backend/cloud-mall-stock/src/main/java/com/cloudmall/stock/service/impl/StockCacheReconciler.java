package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.mapper.StockSqlMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.stereotype.Component;
import org.springframework.util.StopWatch;

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

  /** 单次 Redis MSET 写入的库存条数，控制启动期间的内存占用。 */
  private static final int MSET_BATCH_SIZE = 10000;

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
    StopWatch stopWatch = new StopWatch("stock-cache-rebuild");
    stopWatch.start();
    List<Map<String, Object>> stocks = stockSqlMapper.findAvailableStock();
    for (int batchStart = 0; batchStart < stocks.size(); batchStart += MSET_BATCH_SIZE) {
      int batchEnd = Math.min(batchStart + MSET_BATCH_SIZE, stocks.size());
      writeBatch(stocks.subList(batchStart, batchEnd));
    }
    stopWatch.stop();
    log.info(
        "普通库存 Redis 缓存已从数据库校准，SKU 数量={}，耗时={}ms，批大小={}",
        stocks.size(),
        stopWatch.getTotalTimeMillis(),
        MSET_BATCH_SIZE);
  }

  /**
   * 使用 Redis MSET 批量写入一组库存缓存。
   *
   * <p>数据库仍是库存事实源；MSET 只减少网络往返，不改变每个 SKU 的覆盖写语义。
   */
  private void writeBatch(List<Map<String, Object>> stocks) {
    Map<String, String> cacheValues = new LinkedHashMap<>(stocks.size());
    for (Map<String, Object> stock : stocks) {
      Number skuId = (Number) stock.get("sku_id");
      Number available = (Number) stock.get("available_quantity");
      if (skuId == null || available == null) {
        continue;
      }
      cacheValues.put(
          KEY_PREFIX + skuId.longValue(), String.valueOf(Math.max(0, available.intValue())));
    }
    if (!cacheValues.isEmpty()) {
      redis.opsForValue().multiSet(cacheValues);
    }
  }
}
