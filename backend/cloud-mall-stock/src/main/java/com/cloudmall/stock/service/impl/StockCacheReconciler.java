package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.mapper.StockSqlMapper;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 将 MySQL 普通库存事实按版本投影到 Redis，并恢复提交后的投影故障。 */
@Component
public class StockCacheReconciler {
  /** 缓存修复失败及旧协议迁移的日志。 */
  private static final Logger log = LoggerFactory.getLogger(StockCacheReconciler.class);

  /** 对外普通库存可用量的 Redis Key 前缀。 */
  private static final String STOCK_PREFIX = "stock:available:";

  /** 旧 Redis 预扣尚未裁决时的自动覆盖阻断标记。 */
  private static final String BLOCKED_PREFIX = "stock:repair-blocked:";

  /** 旧版普通预扣明细 Key 的匹配模式。 */
  private static final String LEGACY_RESERVATIONS = "stock:reservation:*:lines";

  /** Redis 投影只接受不旧于当前版本的 MySQL 库存事实。 */
  private static final String VERSIONED_PROJECTION =
      "local kind=redis.call('TYPE',KEYS[1]).ok;"
          + " if kind~='none' and kind~='hash' then return -1 end;"
          + " local current=tonumber(redis.call('HGET',KEYS[1],'version') or '-1');"
          + " local source=tonumber(ARGV[2]); if current>source then return 0 end;"
          + " redis.call('HSET',KEYS[1],'quantity',ARGV[1],'version',ARGV[2]); return 1";

  /** 仅在旧预扣标记已排除后，把旧字符串缓存迁移成带版本 Hash。 */
  private static final String MIGRATION_PROJECTION =
      "local kind=redis.call('TYPE',KEYS[1]).ok;"
          + " if kind=='string' then redis.call('DEL',KEYS[1]) end;"
          + " local current=tonumber(redis.call('HGET',KEYS[1],'version') or '-1');"
          + " local source=tonumber(ARGV[2]); if current>source then return 0 end;"
          + " redis.call('HSET',KEYS[1],'quantity',ARGV[1],'version',ARGV[2]); return 1";

  /** 初次迁移使用的 Redis pipeline 与数据库批量更新大小。 */
  private static final int STARTUP_BATCH_SIZE = 10000;

  /** 每轮修复的最大行数，避免单次任务长期占用调度线程。 */
  private static final int REPAIR_BATCH_SIZE = 2000;

  /** 访问 Redis 普通库存投影。 */
  private final StringRedisTemplate redis;

  /** 访问库存事实与持久化修复记录。 */
  private final StockSqlMapper stockSqlMapper;

  /** 对同一修复记录进行数据库租约认领的实例标识。 */
  private final String owner = UUID.randomUUID().toString();

  /** 带版本条件的 Redis Lua 脚本。 */
  private final DefaultRedisScript<Long> projectionScript =
      new DefaultRedisScript<>(VERSIONED_PROJECTION, Long.class);

  /** 创建普通库存缓存修复器。 */
  public StockCacheReconciler(StringRedisTemplate redis, StockSqlMapper stockSqlMapper) {
    this.redis = redis;
    this.stockSqlMapper = stockSqlMapper;
  }

  /** 初始化新表，并在旧缓存写入者退出后的启动过程中创建当前版本投影。 */
  @PostConstruct
  public void initialize() {
    // 1. 保证已有本地 MySQL 数据卷也具备持久化修复表。
    stockSqlMapper.ensureStockReliabilityTables();
    // 2. 旧协议仍保留预扣标记的 SKU 暂不覆盖，避免覆盖未完成的写入。
    Set<Long> activeLegacySkus;
    boolean redisAvailable = true;
    try {
      activeLegacySkus = collectLegacyReservationSkus();
    } catch (RuntimeException unavailableRedis) {
      activeLegacySkus = Set.of();
      redisAvailable = false;
      log.warn("Redis 暂不可用，保留 MySQL 库存事实并等待定时投影恢复", unavailableRedis);
    }
    List<Map<String, Object>> stockRows = stockSqlMapper.findAvailableStockWithVersion();
    List<Map<String, Object>> eligibleRows = new ArrayList<>(stockRows.size());
    OffsetDateTime currentTime = now();
    int blockedCount = 0;
    for (Map<String, Object> stockRow : stockRows) {
      long skuId = ((Number) stockRow.get("sku_id")).longValue();
      if (activeLegacySkus.contains(skuId)) {
        if (redisAvailable) redis.opsForValue().set(BLOCKED_PREFIX + skuId, "LEGACY_RESERVATION");
        blockedCount++;
        continue;
      }
      eligibleRows.add(stockRow);
    }
    // 3. 先批量持久化修复意图；之后任一 Redis/进程失败都可重试。
    stockSqlMapper.enqueueCurrentCacheRepairs(currentTime);
    // 4. Redis 迁移脚本以 pipeline 批处理，避免 88,476 次网络往返阻塞启动。
    if (redisAvailable) projectInitialBatches(eligibleRows, blockedCount == 0);
    log.info("普通库存投影已排队，SKU={}，旧协议待裁决={}", stockRows.size(), blockedCount);
    // 5. 任何未被批量确认的行继续由定时修复任务处理。
  }

  /** 批量迁移旧字符串库存；仅完整返回的批次才确认修复记录。 */
  private void projectInitialBatches(
      List<Map<String, Object>> eligibleRows, boolean coversEveryStockRow) {
    byte[] script = MIGRATION_PROJECTION.getBytes(StandardCharsets.UTF_8);
    for (int batchStart = 0; batchStart < eligibleRows.size(); batchStart += STARTUP_BATCH_SIZE) {
      List<Map<String, Object>> batch =
          eligibleRows.subList(
              batchStart, Math.min(batchStart + STARTUP_BATCH_SIZE, eligibleRows.size()));
      try {
        List<Object> outcomes =
            redis.executePipelined(
                (RedisCallback<Object>)
                    connection -> {
                      for (Map<String, Object> stockRow : batch) {
                        byte[] stockKey =
                            (STOCK_PREFIX + ((Number) stockRow.get("sku_id")).longValue())
                                .getBytes(StandardCharsets.UTF_8);
                        byte[] quantity =
                            String.valueOf(stockRow.get("available_quantity"))
                                .getBytes(StandardCharsets.UTF_8);
                        byte[] version =
                            String.valueOf(stockRow.get("version"))
                                .getBytes(StandardCharsets.UTF_8);
                        connection.eval(
                            script, ReturnType.INTEGER, 1, stockKey, quantity, version);
                      }
                      return null;
                    });
        if (outcomes.size() != batch.size()
            || outcomes.stream().anyMatch(result -> !Long.valueOf(1L).equals(result)
                && !Long.valueOf(0L).equals(result))) {
          log.warn("库存启动投影批次结果不完整，保留待重试记录，batchStart={}", batchStart);
          continue;
        }
        if (coversEveryStockRow && outcomes.stream().allMatch(Long.valueOf(1L)::equals)) {
          stockSqlMapper.markInitialCacheRangeApplied(
              ((Number) batch.get(0).get("sku_id")).longValue(),
              ((Number) batch.get(batch.size() - 1).get("sku_id")).longValue(),
              now());
        } else {
          stockSqlMapper.finishInitialCacheRepairs(batch, outcomes, now());
        }
      } catch (RuntimeException migrationFailure) {
        log.warn("库存启动投影批次失败，后续由持久化修复任务重试，batchStart={}",
            batchStart, migrationFailure);
        return;
      }
    }
  }

  /** 定时把已提交的库存版本投影到 Redis。 */
  @Scheduled(fixedDelayString = "${cloudmall.stock.cache-repair-interval-ms:1000}")
  public void repairPending() {
    // 1. 只扫描已到重试时间且无有效租约的记录。
    OffsetDateTime currentTime = now();
    List<Map<String, Object>> pending =
        stockSqlMapper.findPendingCacheRepairs(currentTime, REPAIR_BATCH_SIZE);
    // 2. 按记录认领；Lua 版本检查阻止旧任务覆盖新的库存事实。
    for (Map<String, Object> repair : pending) {
      long skuId = ((Number) repair.get("sku_id")).longValue();
      if (Boolean.TRUE.equals(redis.hasKey(BLOCKED_PREFIX + skuId))) continue;
      long repairId = ((Number) repair.get("id")).longValue();
      if (stockSqlMapper.claimCacheRepair(
              repairId, owner, currentTime.plusSeconds(30), currentTime)
          != 1) continue;
      try {
        Long outcome =
            redis.execute(
                projectionScript,
                List.of(STOCK_PREFIX + skuId),
                String.valueOf(repair.get("target_available")),
                String.valueOf(repair.get("source_version")));
        if (Long.valueOf(1L).equals(outcome)) {
          finish(repairId, "APPLIED", null, currentTime.plusYears(10));
        } else if (Long.valueOf(0L).equals(outcome)) {
          finish(repairId, "STALE", "Redis 已有更高库存版本", currentTime.plusYears(10));
        } else if (Long.valueOf(-1L).equals(outcome)) {
          redis.opsForValue().set(BLOCKED_PREFIX + skuId, "LEGACY_STRING");
          finish(repairId, "PENDING", "旧版字符串库存 Key 待裁决", currentTime.plusSeconds(30));
        } else {
          finish(repairId, "PENDING", "Redis 投影结果未知", currentTime.plusSeconds(5));
        }
      } catch (RuntimeException projectionFailure) {
        finish(repairId, "PENDING", projectionFailure.toString(), currentTime.plusSeconds(5));
        log.warn("库存投影失败，skuId={}，sourceVersion={}",
            skuId, repair.get("source_version"), projectionFailure);
      }
    }
  }

  /** 周期性检查旧预扣标记，完成后清除迁移阻断并排队当前 MySQL 版本。 */
  @Scheduled(fixedDelayString = "${cloudmall.stock.legacy-recheck-interval-ms:30000}")
  public void unblockResolvedLegacyStocks() {
    Set<String> blockedKeys = redis.keys(BLOCKED_PREFIX + "*");
    if (blockedKeys == null || blockedKeys.isEmpty()) return;
    Set<Long> activeLegacySkus = collectLegacyReservationSkus();
    for (String blockedKey : blockedKeys) {
      long skuId;
      try {
        skuId = Long.parseLong(blockedKey.substring(BLOCKED_PREFIX.length()));
      } catch (NumberFormatException malformedKey) {
        log.warn("忽略无法识别的库存迁移阻断 Key={}", blockedKey);
        continue;
      }
      if (activeLegacySkus.contains(skuId)) continue;
      Map<String, Object> currentStock = stockSqlMapper.findStock(skuId);
      if (currentStock == null) continue;
      String stockKey = STOCK_PREFIX + skuId;
      if (redis.type(stockKey) == DataType.STRING) redis.delete(stockKey);
      redis.delete(blockedKey);
      stockSqlMapper.insertCacheRepair(
          skuId,
          ((Number) currentStock.get("available_quantity")).intValue(),
          ((Number) currentStock.get("version")).intValue(),
          now());
    }
  }

  /** 从旧预扣明细中收集尚未裁决的 SKU。 */
  private Set<Long> collectLegacyReservationSkus() {
    Set<Long> activeSkuIds = new HashSet<>();
    Set<String> reservationKeys = redis.keys(LEGACY_RESERVATIONS);
    if (reservationKeys == null) return activeSkuIds;
    for (String reservationKey : reservationKeys) {
      Map<Object, Object> lines = redis.opsForHash().entries(reservationKey);
      for (Object skuField : lines.keySet()) {
        try {
          activeSkuIds.add(Long.parseLong(String.valueOf(skuField)));
        } catch (NumberFormatException malformedSku) {
          log.warn("旧预扣明细包含非法 SKU，key={}，field={}", reservationKey, skuField);
        }
      }
    }
    return activeSkuIds;
  }

  /** 用租约持有者条件更新持久化修复结果。 */
  private void finish(long repairId, String status, String error, OffsetDateTime nextRetryAt) {
    String summary = error == null ? null : error.substring(0, Math.min(512, error.length()));
    stockSqlMapper.finishCacheRepair(repairId, owner, status, summary, nextRetryAt, now());
  }

  /** 以项目固定时区获取时间。 */
  private static OffsetDateTime now() {
    return OffsetDateTime.now(ZoneOffset.ofHours(8));
  }
}
