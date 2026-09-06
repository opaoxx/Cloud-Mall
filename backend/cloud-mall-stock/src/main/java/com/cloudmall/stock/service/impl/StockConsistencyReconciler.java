package com.cloudmall.stock.service.impl;

import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers durable reservations and audits normal/seckill stock consistency. */
@Component
public class StockConsistencyReconciler {
  private static final Logger log = LoggerFactory.getLogger(StockConsistencyReconciler.class);
  private static final String NORMAL_PREFIX = "stock:available:";
  private static final String RESERVATION_PREFIX = "stock:reservation:";
  private static final String SECKILL_META_PREFIX = "seckill:meta:";
  private static final String SECKILL_STOCK_PREFIX = "seckill:stock:";
  private static final String SECKILL_ACCEPTED_PREFIX = "seckill:accepted:";
  private static final String LOCK_KEY = "stock:consistency:lock";

  private final StringRedisTemplate redis;
  private final JdbcTemplate db;

  public StockConsistencyReconciler(StringRedisTemplate redis, JdbcTemplate db) {
    this.redis = redis;
    this.db = db;
  }

  @PostConstruct
  public void initialize() {
    ensureSeckillLedgerTable();
    recoverNormalReservations();
    recoverAcceptedSeckillLedger();
    reconcileSeckillStock();
  }

  @Scheduled(fixedDelayString = "${cloudmall.stock.consistency-interval-ms:30000}")
  public void scheduledAudit() {
    String token = UUID.randomUUID().toString();
    if (!Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(LOCK_KEY, token, Duration.ofSeconds(20)))) {
      return;
    }
    try {
      auditNormalStock();
      recoverAcceptedSeckillLedger();
      reconcileSeckillStock();
    } finally {
      redis.delete(LOCK_KEY);
    }
  }

  private void ensureSeckillLedgerTable() {
    db.execute(
        "CREATE TABLE IF NOT EXISTS seckill_reservation (id BIGINT PRIMARY KEY, activity_id BIGINT"
            + " NOT NULL, sku_id BIGINT NOT NULL, user_id BIGINT NOT NULL, order_no VARCHAR(64) NOT"
            + " NULL, idempotency_key VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL,"
            + " created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, UNIQUE KEY"
            + " uk_seckill_request(activity_id,user_id,idempotency_key), UNIQUE KEY"
            + " uk_seckill_order(order_no), KEY idx_seckill_stock(activity_id,sku_id,status))");
  }

  private void recoverNormalReservations() {
    List<Map<String, Object>> rows =
        db.queryForList(
            "select order_no,sku_id,sum(case when flow_type='RESERVE' then quantity "
                + "when flow_type in ('CONFIRM','ROLLBACK') then -quantity else 0 end) quantity "
                + "from stock_flow group by order_no,sku_id having quantity>0");
    Map<String, Map<Long, Integer>> reservations = new LinkedHashMap<>();
    for (Map<String, Object> row : rows) {
      Number skuId = (Number) row.get("sku_id");
      Number quantity = (Number) row.get("quantity");
      if (skuId != null && quantity != null && quantity.intValue() > 0) {
        reservations
            .computeIfAbsent(String.valueOf(row.get("order_no")), ignored -> new LinkedHashMap<>())
            .put(skuId.longValue(), quantity.intValue());
      }
    }
    for (Map.Entry<String, Map<Long, Integer>> reservation : reservations.entrySet()) {
      String key = RESERVATION_PREFIX + reservation.getKey();
      redis.opsForValue().set(key, "RESERVED", Duration.ofDays(1));
      for (Map.Entry<Long, Integer> line : reservation.getValue().entrySet()) {
        redis
            .opsForHash()
            .put(key + ":lines", String.valueOf(line.getKey()), String.valueOf(line.getValue()));
      }
    }
    if (!reservations.isEmpty()) {
      log.info("普通库存 reservation 已从 stock_flow 恢复，订单数={}", reservations.size());
    }
  }

  private void recoverAcceptedSeckillLedger() {
    Set<String> keys = redis.keys(SECKILL_ACCEPTED_PREFIX + "*");
    if (keys == null) return;
    for (String key : keys) {
      String[] parts = key.substring(SECKILL_ACCEPTED_PREFIX.length()).split(":", 4);
      if (parts.length != 4) continue;
      String orderNo = redis.opsForValue().get(key);
      if (orderNo == null || orderNo.isBlank()) continue;
      OffsetDateTime now = now();
      db.update(
          "insert ignore into seckill_reservation"
              + " (id,activity_id,sku_id,user_id,order_no,idempotency_key,status,created_at,updated_at)"
              + " values(?,?,?,?,?,?, 'ACCEPTED',?,?)",
          id(),
          Long.parseLong(parts[0]),
          Long.parseLong(parts[1]),
          Long.parseLong(parts[2]),
          orderNo,
          parts[3],
          now,
          now);
    }
  }

  private void auditNormalStock() {
    for (Map<String, Object> row :
        db.queryForList("select sku_id,available_quantity from stock_sku")) {
      Number skuId = (Number) row.get("sku_id");
      Number mysql = (Number) row.get("available_quantity");
      if (skuId == null || mysql == null) continue;
      String redisValue = redis.opsForValue().get(NORMAL_PREFIX + skuId.longValue());
      if (redisValue == null || !String.valueOf(Math.max(0, mysql.intValue())).equals(redisValue)) {
        log.warn("普通库存缓存与数据库不一致，skuId={}，redis={}，mysql={}", skuId, redisValue, mysql);
      }
    }
  }

  private void reconcileSeckillStock() {
    Set<String> metaKeys = redis.keys(SECKILL_META_PREFIX + "*");
    if (metaKeys == null) return;
    for (String metaKey : metaKeys) {
      String suffix = metaKey.substring(SECKILL_META_PREFIX.length());
      String[] activitySku = suffix.split(":", 2);
      if (activitySku.length != 2) continue;
      Object limitValue = redis.opsForHash().get(metaKey, "stockLimit");
      if (limitValue == null) continue;
      int stockLimit = Integer.parseInt(String.valueOf(limitValue));
      Long accepted =
          db.queryForObject(
              "select count(*) from seckill_reservation "
                  + "where activity_id=? and sku_id=? and status='ACCEPTED'",
              Long.class,
              Long.parseLong(activitySku[0]),
              Long.parseLong(activitySku[1]));
      long expected = Math.max(0, stockLimit - (accepted == null ? 0 : accepted));
      String stockKey = SECKILL_STOCK_PREFIX + suffix;
      String actual = redis.opsForValue().get(stockKey);
      if (actual == null) {
        redis.opsForValue().set(stockKey, String.valueOf(expected));
        log.warn("秒杀库存 key 缺失，已按 durable ledger 重建，activitySku={}，remaining={}", suffix, expected);
      } else if (!String.valueOf(expected).equals(actual)) {
        log.warn(
            "秒杀库存对账不一致，activitySku={}，redis={}，ledgerExpected={}；保留现值等待补偿",
            suffix,
            actual,
            expected);
      }
    }
  }

  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  private static long id() {
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }
}
