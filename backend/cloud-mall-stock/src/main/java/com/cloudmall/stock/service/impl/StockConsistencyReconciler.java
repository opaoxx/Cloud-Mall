package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.mapper.StockSqlMapper;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Recovers durable reservations and audits normal/seckill stock consistency. */
@Component
public class StockConsistencyReconciler {
  /** 执行 getLogger 相关操作。 */
  private static final Logger log = LoggerFactory.getLogger(StockConsistencyReconciler.class);

  /** 保存 NORMAL_PREFIX 的业务状态或配置。 */
  private static final String NORMAL_PREFIX = "stock:available:";

  /** 保存 RESERVATION_PREFIX 的业务状态或配置。 */
  private static final String RESERVATION_PREFIX = "stock:reservation:";

  /** 保存 SECKILL_META_PREFIX 的业务状态或配置。 */
  private static final String SECKILL_META_PREFIX = "seckill:meta:";

  /** 保存 SECKILL_STOCK_PREFIX 的业务状态或配置。 */
  private static final String SECKILL_STOCK_PREFIX = "seckill:stock:";

  /** 保存 SECKILL_ACCEPTED_PREFIX 的业务状态或配置。 */
  private static final String SECKILL_ACCEPTED_PREFIX = "seckill:accepted:";

  /** 保存 LOCK_KEY 的业务状态或配置。 */
  private static final String LOCK_KEY = "stock:consistency:lock";

  /** 仅锁持有者可以原子释放，避免删除租约过期后新实例取得的锁。 */
  private static final DefaultRedisScript<Long> RELEASE_LOCK =
      new DefaultRedisScript<>(
          "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('DEL',KEYS[1]) end return 0",
          Long.class);

  /** 只有当前持有者可以延长锁租约，过期后旧持有者不能复活。 */
  private static final DefaultRedisScript<Long> RENEW_LOCK =
      new DefaultRedisScript<>(
          "if redis.call('GET',KEYS[1])==ARGV[1] then return redis.call('PEXPIRE',KEYS[1],ARGV[2]) end return 0",
          Long.class);

  /** 对账租约为 20 秒，由独立线程每 5 秒续期。 */
  private static final Duration LOCK_TTL = Duration.ofSeconds(20);

  /** 保存 redis 的业务状态或配置。 */
  private final StringRedisTemplate redis;

  /** 保存 db 的业务状态或配置。 */
  private final StockSqlMapper stockSqlMapper;

  /** 创建 StockConsistencyReconciler 实例。 */
  public StockConsistencyReconciler(StringRedisTemplate redis, StockSqlMapper stockSqlMapper) {
    // 1. 接收并整理 StockConsistencyReconciler 的业务请求。
    // 2. 执行 StockConsistencyReconciler 的核心业务校验与状态处理。
    // 3. 返回 StockConsistencyReconciler 的处理结果。
    this.redis = redis;
    this.stockSqlMapper = stockSqlMapper;
  }

  @PostConstruct
  /** 执行 initialize 相关操作。 */
  public void initialize() {
    // 1. 接收并整理 initialize 的业务请求。
    // 2. 执行 initialize 的核心业务校验与状态处理。
    // 3. 返回 initialize 的处理结果。
    ensureSeckillLedgerTable();
    // 普通库存预扣事实已经由 stock_flow 和 stock_cache_repair 恢复；启动时不再重建旧 Redis 标记。
    try {
      recoverAcceptedSeckillLedger(() -> {});
      reconcileSeckillStock(() -> {});
    } catch (RuntimeException unavailableRedis) {
      log.warn("Redis 暂不可用，秒杀台账恢复将在定时对账时重试；普通库存仍以 MySQL 为准", unavailableRedis);
    }
  }

  @Scheduled(fixedDelayString = "${cloudmall.stock.consistency-interval-ms:30000}")
  /** 执行 scheduledAudit 相关操作。 */
  public void scheduledAudit() {
    // 1. 接收并整理 scheduledAudit 的业务请求。
    // 2. 执行 scheduledAudit 的核心业务校验与状态处理。
    // 3. 返回 scheduledAudit 的处理结果。
    String token = UUID.randomUUID().toString();
    if (!Boolean.TRUE.equals(
        redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL))) {
      return;
    }
    AtomicBoolean leaseOwned = new AtomicBoolean(true);
    ScheduledExecutorService renewalExecutor = Executors.newSingleThreadScheduledExecutor(
        task -> {
          Thread thread = new Thread(task, "stock-consistency-lock-renewal");
          thread.setDaemon(true);
          return thread;
        });
    ScheduledFuture<?> renewal = renewalExecutor.scheduleAtFixedRate(
        () -> renewLease(token, leaseOwned), 5, 5, TimeUnit.SECONDS);
    Runnable guard = () -> requireLease(token, leaseOwned);
    try {
      auditNormalStock(leaseOwned);
      guard.run();
      recoverAcceptedSeckillLedger(guard);
      guard.run();
      reconcileSeckillStock(guard);
    } finally {
      renewal.cancel(true);
      renewalExecutor.shutdownNow();
      redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), token);
    }
  }

  /** 原子续期失败后撤销本次任务的写权限。 */
  private void renewLease(String token, AtomicBoolean leaseOwned) {
    try {
      Long renewed = redis.execute(
          RENEW_LOCK, List.of(LOCK_KEY), token, String.valueOf(LOCK_TTL.toMillis()));
      if (!Objects.equals(renewed, 1L)) leaseOwned.set(false);
    } catch (RuntimeException renewalFailure) {
      leaseOwned.set(false);
      log.warn("库存对账锁续期失败，本轮停止写入", renewalFailure);
    }
  }

  /** 每批写入前重新核对 token，旧实例不得继续写入。 */
  private void requireLease(String token, AtomicBoolean leaseOwned) {
    if (!leaseOwned.get() || !token.equals(redis.opsForValue().get(LOCK_KEY))) {
      leaseOwned.set(false);
      throw new IllegalStateException("库存对账锁已失效，本轮停止写入");
    }
  }

  /** 执行 ensureSeckillLedgerTable 相关操作。 */
  private void ensureSeckillLedgerTable() {
    // 1. 接收并整理 ensureSeckillLedgerTable 的业务请求。
    // 2. 执行 ensureSeckillLedgerTable 的核心业务校验与状态处理。
    // 3. 返回 ensureSeckillLedgerTable 的处理结果。
    stockSqlMapper.ensureSeckillLedgerTable();
  }

  /** 执行 recoverNormalReservations 相关操作。 */
  private void recoverNormalReservations() {
    // 1. 接收并整理 recoverNormalReservations 的业务请求。
    // 2. 执行 recoverNormalReservations 的核心业务校验与状态处理。
    // 3. 返回 recoverNormalReservations 的处理结果。
    List<Map<String, Object>> rows = stockSqlMapper.findOpenReservations();
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

  /** 执行 recoverAcceptedSeckillLedger 相关操作。 */
  private void recoverAcceptedSeckillLedger(Runnable guard) {
    // 1. 接收并整理 recoverAcceptedSeckillLedger 的业务请求。
    // 2. 执行 recoverAcceptedSeckillLedger 的核心业务校验与状态处理。
    // 3. 返回 recoverAcceptedSeckillLedger 的处理结果。
    Set<String> keys = redis.keys(SECKILL_ACCEPTED_PREFIX + "*");
    if (keys == null) return;
    for (String key : keys) {
      guard.run();
      String[] parts = key.substring(SECKILL_ACCEPTED_PREFIX.length()).split(":", 4);
      if (parts.length != 4) continue;
      String orderNo = redis.opsForValue().get(key);
      if (orderNo == null || orderNo.isBlank()) continue;
      OffsetDateTime now = now();
      stockSqlMapper.insertAcceptedReservation(
          Long.parseLong(parts[0]),
          Long.parseLong(parts[1]),
          Long.parseLong(parts[2]),
          orderNo,
          parts[3],
          now);
    }
  }

  /** 执行 auditNormalStock 相关操作。 */
  private void auditNormalStock(AtomicBoolean leaseOwned) {
    // 1. 接收并整理 auditNormalStock 的业务请求。
    // 2. 执行 auditNormalStock 的核心业务校验与状态处理。
    // 3. 返回 auditNormalStock 的处理结果。
    for (Map<String, Object> row : stockSqlMapper.findAvailableStock()) {
      if (!leaseOwned.get()) throw new IllegalStateException("库存对账锁已失效，本轮停止审计");
      Number skuId = (Number) row.get("sku_id");
      Number mysql = (Number) row.get("available_quantity");
      if (skuId == null || mysql == null) continue;
      String stockKey = NORMAL_PREFIX + skuId.longValue();
      if (redis.type(stockKey) == DataType.STRING) {
        log.warn("普通库存仍使用旧字符串缓存，等待版本投影迁移，skuId={}", skuId);
        continue;
      }
      Object redisValue = redis.opsForHash().get(stockKey, "quantity");
      if (redisValue == null || !String.valueOf(Math.max(0, mysql.intValue())).equals(String.valueOf(redisValue))) {
        log.warn("普通库存缓存与数据库不一致，skuId={}，redis={}，mysql={}", skuId, redisValue, mysql);
      }
    }
  }

  /** 执行 reconcileSeckillStock 相关操作。 */
  private void reconcileSeckillStock(Runnable guard) {
    // 1. 接收并整理 reconcileSeckillStock 的业务请求。
    // 2. 执行 reconcileSeckillStock 的核心业务校验与状态处理。
    // 3. 返回 reconcileSeckillStock 的处理结果。
    Set<String> metaKeys = redis.keys(SECKILL_META_PREFIX + "*");
    if (metaKeys == null) return;
    for (String metaKey : metaKeys) {
      guard.run();
      String suffix = metaKey.substring(SECKILL_META_PREFIX.length());
      String[] activitySku = suffix.split(":", 2);
      if (activitySku.length != 2) continue;
      Object limitValue = redis.opsForHash().get(metaKey, "stockLimit");
      if (limitValue == null) continue;
      int stockLimit = Integer.parseInt(String.valueOf(limitValue));
      Long accepted =
          stockSqlMapper.countAcceptedReservations(
              Long.parseLong(activitySku[0]), Long.parseLong(activitySku[1]));
      long expected = Math.max(0, stockLimit - (accepted == null ? 0 : accepted));
      String stockKey = SECKILL_STOCK_PREFIX + suffix;
      String actual = redis.opsForValue().get(stockKey);
      if (actual == null) {
        guard.run();
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

  /** 执行 now 相关操作。 */
  private static OffsetDateTime now() {
    // 1. 接收并整理 now 的业务请求。
    // 2. 执行 now 的核心业务校验与状态处理。
    // 3. 返回 now 的处理结果。
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }

  /** 执行 id 相关操作。 */
  private static long id() {
    // 1. 接收并整理 id 的业务请求。
    // 2. 执行 id 的核心业务校验与状态处理。
    // 3. 返回 id 的处理结果。
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }
}
