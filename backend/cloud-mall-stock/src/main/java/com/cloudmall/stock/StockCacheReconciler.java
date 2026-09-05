package com.cloudmall.stock;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

import javax.annotation.PostConstruct;
import java.util.List;
import java.util.Map;

/** Rebuilds the normal stock cache from the durable stock table after Redis data loss or drift. */
@Component
public class StockCacheReconciler {
    private static final Logger log = LoggerFactory.getLogger(StockCacheReconciler.class);
    private static final String KEY_PREFIX = "stock:available:";

    private final StringRedisTemplate redis;
    private final JdbcTemplate db;

    public StockCacheReconciler(StringRedisTemplate redis, JdbcTemplate db) {
        this.redis = redis;
        this.db = db;
    }

    @PostConstruct
    public void rebuildFromDatabase() {
        List<Map<String, Object>> stocks = db.queryForList(
                "select sku_id, available_quantity from stock_sku");
        for (Map<String, Object> stock : stocks) {
            Number skuId = (Number) stock.get("sku_id");
            Number available = (Number) stock.get("available_quantity");
            if (skuId == null || available == null) {
                continue;
            }
            redis.opsForValue().set(KEY_PREFIX + skuId.longValue(),
                    String.valueOf(Math.max(0, available.intValue())));
        }
        log.info("普通库存 Redis 缓存已从数据库校准，SKU 数量={}", stocks.size());
    }
}
