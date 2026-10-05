package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.util.StockDependencyGuard;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;
import javax.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.DataType;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.Cursor;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.ScanOptions;
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

  /** 每批只读返回固定的 type/quantity/version 三元组；异常长字段只返回无效标记。 */
  private static final byte[] READ_PROJECTION = (
      "local out={}; for i=1,#KEYS do local kind=redis.call('TYPE',KEYS[i]).ok;"
          + " out[#out+1]=kind; if kind=='hash' then"
          + " if redis.call('HSTRLEN',KEYS[i],'quantity')>64 then out[#out+1]='!'"
          + " else out[#out+1]=redis.call('HGET',KEYS[i],'quantity') or '' end;"
          + " if redis.call('HSTRLEN',KEYS[i],'version')>64 then out[#out+1]='!'"
          + " else out[#out+1]=redis.call('HGET',KEYS[i],'version') or '' end;"
          + " else out[#out+1]=''; out[#out+1]=''; end end; return out")
      .getBytes(StandardCharsets.UTF_8);

  /** Redis 游标扫描的每次提示数量和单轮匹配上限。 */
  private static final int SCAN_COUNT = 1000;
  private static final int SCAN_MAX_MATCHES = 10000;

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

  /** 启动对账的有界 MySQL/Redis 批次，测试手工构造实例也沿用该默认值。 */
  @Value("${cloudmall.stock.cache-startup-batch-size:1000}")
  private int startupBatchSize = 1000;

  /** 启动对账总时间上限；超时保留持久修复事实并后台复扫。 */
  @Value("${cloudmall.stock.cache-startup-max-ms:60000}")
  private long startupMaxMillis = 60000L;

  /** 单批只读 Lua 的耗时和返回字节上限，阻止大批次阻塞 Redis。 */
  @Value("${cloudmall.stock.cache-redis-batch-max-ms:2000}")
  private long redisBatchMaxMillis = 2000L;

  @Value("${cloudmall.stock.cache-redis-reply-max-bytes:256000}")
  private long redisReplyMaxBytes = 256000L;

  /** Redis 读不可用或校准未完成时由定时任务重新扫描全量事实。 */
  private volatile boolean rescanRequired;

  /** 旧预扣扫描可信时才允许待修复记录的 Redis 投影。 */
  private volatile boolean legacyScanComplete = true;

  /** 创建普通库存缓存修复器。 */
  public StockCacheReconciler(StringRedisTemplate redis, StockSqlMapper stockSqlMapper) {
    this.redis = redis;
    this.stockSqlMapper = stockSqlMapper;
  }

  /** 在服务启动前有界核对库存事实，只为真实差异写入持久修复意图。 */
  @PostConstruct
  public void initialize() {
    StockDependencyGuard.initialize(this::initializeStorage);
  }

  /** 校准和 DDL 仍沿用原流程，尚未就绪的耗时不污染业务熔断统计。 */
  private void initializeStorage() {
    long startedAt = System.nanoTime();
    // 1. 准备数据库可靠性表并核验旧协议预扣。
    stockSqlMapper.ensureStockReliabilityTables();
    long schemaReadyAt = System.nanoTime();
    legacyScanComplete = false;
    try {
      Set<Long> activeLegacySkus = collectLegacyReservationSkus();
      legacyScanComplete = true;
      long legacyScannedAt = System.nanoTime();
      // 2. MySQL 游标和 Redis 有界 Lua 只读比较；投影由带租约的恢复器处理。
      CalibrationStats stats = calibrate(activeLegacySkus, startedAt);
      logCalibration("startup", schemaReadyAt, legacyScannedAt, stats);
      rescanRequired = false;
    } catch (RedisCalibrationException unavailableRedis) {
      // 3. Redis 不可用时单条 SQL 确保意图，恢复后再次逐批核对 APPLIED 的真实 Key。
      long fallbackStartedAt = System.nanoTime();
      stockSqlMapper.enqueueCurrentCacheRepairs(now());
      rescanRequired = true;
      log.warn("Redis 启动校准未完成，保留已完成记录并排队可重试修复；恢复后重新逐批核对，fallbackMs={}",
          elapsedMillis(fallbackStartedAt, System.nanoTime()), unavailableRedis);
    }
  }

  /** 单调时钟计量阶段耗时，避免系统时间调整影响对比。 */
  private static long elapsedMillis(long startedAt, long finishedAt) {
    return TimeUnit.NANOSECONDS.toMillis(finishedAt - startedAt);
  }

  /** Redis 恢复后重新扫描；APPLIED 记录也要核验其 Hash 仍真实存在。 */
  @Scheduled(fixedDelayString = "${cloudmall.stock.cache-rescan-interval-ms:5000}")
  public void retryIncompleteCalibration() {
    if (!rescanRequired) return;
    try {
      long startedAt = System.nanoTime();
      legacyScanComplete = false;
      Set<Long> activeLegacySkus = collectLegacyReservationSkus();
      legacyScanComplete = true;
      long legacyScannedAt = System.nanoTime();
      CalibrationStats stats = calibrate(activeLegacySkus, startedAt);
      logCalibration("recovery", startedAt, legacyScannedAt, stats);
      rescanRequired = false;
    } catch (RedisCalibrationException unavailableRedis) {
      log.warn("Redis 仍不可用于库存全量差异复核，保留下一轮重试", unavailableRedis);
    }
  }

  /** 只持有一个有界批次，先查 Redis，再按当前 MySQL 事实批量写差异意图。 */
  private CalibrationStats calibrate(Set<Long> activeLegacySkus, long startedAt) {
    int batchSize = Math.max(1, Math.min(1000, startupBatchSize));
    long cursorSkuId = 0L;
    long readMillis = 0L;
    long redisMillis = 0L;
    long enqueueMillis = 0L;
    long rowsChecked = 0L;
    long matching = 0L;
    long newer = 0L;
    long blocked = 0L;
    long differences = 0L;
    long intentsAffected = 0L;
    long peakBatchHeapMiB = 0L;
    long maxReplyBytes = 0L;
    List<Long> redisCallMicros = new ArrayList<>();
    while (true) {
      if (elapsedMillis(startedAt, System.nanoTime()) > Math.max(1000L, startupMaxMillis))
        throw new RedisCalibrationException("库存启动校准超过总耗时上限");
      long readStartedAt = System.nanoTime();
      List<Map<String, Object>> batch =
          stockSqlMapper.findAvailableStockBatchAfter(cursorSkuId, batchSize);
      readMillis += elapsedMillis(readStartedAt, System.nanoTime());
      if (batch.isEmpty()) break;
      rowsChecked += batch.size();
      cursorSkuId = ((Number) batch.get(batch.size() - 1).get("sku_id")).longValue();
      List<Map<String, Object>> eligible = new ArrayList<>(batch.size());
      for (Map<String, Object> row : batch) {
        long skuId = ((Number) row.get("sku_id")).longValue();
        if (activeLegacySkus.contains(skuId)) {
          redisRead(() -> {
            redis.opsForValue().set(BLOCKED_PREFIX + skuId, "LEGACY_RESERVATION");
            return null;
          });
          blocked++;
        } else {
          eligible.add(row);
        }
      }
      long redisStartedAt = System.nanoTime();
      BatchComparison comparison = compareRedis(eligible);
      redisMillis += elapsedMillis(redisStartedAt, System.nanoTime());
      if (comparison.redisCallMicros >= 0L) redisCallMicros.add(comparison.redisCallMicros);
      maxReplyBytes = Math.max(maxReplyBytes, comparison.replyBytes);
      matching += comparison.matching;
      newer += comparison.newer;
      blocked += comparison.blocked;
      differences += comparison.differenceSkuIds.size();
      if (!comparison.differenceSkuIds.isEmpty()) {
        long enqueueStartedAt = System.nanoTime();
        intentsAffected += stockSqlMapper.enqueueCacheRepairsForSkuIds(
            comparison.differenceSkuIds, now());
        enqueueMillis += elapsedMillis(enqueueStartedAt, System.nanoTime());
      }
      peakBatchHeapMiB = Math.max(peakBatchHeapMiB,
          java.lang.management.ManagementFactory.getMemoryMXBean()
              .getHeapMemoryUsage().getUsed() / (1024L * 1024L));
      if (batch.size() < batchSize) break;
    }
    Collections.sort(redisCallMicros);
    return new CalibrationStats(rowsChecked, matching, newer, blocked, differences,
        intentsAffected, readMillis, redisMillis, enqueueMillis, peakBatchHeapMiB,
        redisCallMicros.size(), percentile(redisCallMicros, 50), percentile(redisCallMicros, 95),
        percentile(redisCallMicros, 99), maxReplyBytes);
  }

  /** 有界批次耗时分位数，单位微秒。 */
  private static long percentile(List<Long> sortedMicros, int percentage) {
    if (sortedMicros.isEmpty()) return 0L;
    int index = (int) Math.ceil(sortedMicros.size() * percentage / 100.0) - 1;
    return sortedMicros.get(Math.max(0, index));
  }

  /** 一次有界只读 Lua 返回每个 SKU 的类型、数量和版本。 */
  private BatchComparison compareRedis(List<Map<String, Object>> rows) {
    if (rows.isEmpty()) return new BatchComparison(List.of(), 0, 0, 0, -1L, 0L);
    byte[][] keys = rows.stream().map(StockCacheReconciler::stockKeyBytes)
        .toArray(byte[][]::new);
    long readStartedAt = System.nanoTime();
    List<?> values = redisRead(() -> redis.execute(
        (RedisCallback<List<?>>) connection ->
            (List<?>) connection.eval(READ_PROJECTION, ReturnType.MULTI, rows.size(), keys)));
    long redisCallMicros = TimeUnit.NANOSECONDS.toMicros(System.nanoTime() - readStartedAt);
    if (elapsedMillis(readStartedAt, System.nanoTime()) > Math.max(100L, redisBatchMaxMillis))
      throw new RedisCalibrationException("库存 Redis 只读批次超过耗时上限");
    if (values == null || values.size() != rows.size() * 3)
      throw new RedisCalibrationException("库存 Redis 只读批次返回数量不完整");
    List<Long> differences = new ArrayList<>();
    long replyBytes = 0L;
    int matching = 0;
    int newer = 0;
    int blocked = 0;
    for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
      Map<String, Object> row = rows.get(rowIndex);
      Object rawType = values.get(rowIndex * 3);
      Object rawQuantity = values.get(rowIndex * 3 + 1);
      Object rawVersion = values.get(rowIndex * 3 + 2);
      replyBytes += byteSize(rawType) + byteSize(rawQuantity) + byteSize(rawVersion);
      if (replyBytes > Math.max(3000L, redisReplyMaxBytes))
        throw new RedisCalibrationException("库存 Redis 只读批次超过返回字节上限");
      String type = decoded(rawType).toLowerCase(Locale.ROOT);
      if ("none".equals(type)) {
        differences.add(((Number) row.get("sku_id")).longValue());
      } else if ("hash".equals(type)) {
        Long redisQuantity = numeric(rawQuantity);
        Long redisVersion = numeric(rawVersion);
        long mysqlVersion = ((Number) row.get("version")).longValue();
        long mysqlQuantity = ((Number) row.get("available_quantity")).longValue();
        if (redisVersion != null && redisVersion > mysqlVersion) {
          newer++;
        } else if (redisVersion != null && redisVersion == mysqlVersion
            && redisQuantity != null && redisQuantity == mysqlQuantity) {
          matching++;
        } else {
          differences.add(((Number) row.get("sku_id")).longValue());
        }
      } else if (type.isBlank()) {
        throw new RedisCalibrationException("库存 Redis 只读批次类型未知");
      } else {
        long skuId = ((Number) row.get("sku_id")).longValue();
        redisRead(() -> {
          redis.opsForValue().set(BLOCKED_PREFIX + skuId, "LEGACY_STRING");
          return null;
        });
        blocked++;
        log.warn("库存投影 Key 类型不符，保持旧协议阻断，skuId={}，type={}", skuId, type);
      }
    }
    return new BatchComparison(differences, matching, newer, blocked, redisCallMicros,
        replyBytes);
  }

  /** StringRedisTemplate RedisCallback 可返回 String 或 byte[]。 */
  private static String decoded(Object value) {
    if (value == null) return "";
    return value instanceof byte[] bytes
        ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
  }

  private static long byteSize(Object value) {
    return value instanceof byte[] bytes ? bytes.length : decoded(value).length();
  }

  /** 把 Redis 返回值按十进制解析，缺字段或损坏视为差异。 */
  private static Long numeric(Object value) {
    if (value == null) return null;
    try {
      return Long.parseLong(decoded(value));
    } catch (NumberFormatException malformed) {
      return null;
    }
  }

  private static byte[] stockKeyBytes(Map<String, Object> row) {
    return (STOCK_PREFIX + ((Number) row.get("sku_id")).longValue())
        .getBytes(StandardCharsets.UTF_8);
  }

  /** Redis 故障与结果未知都转为可恢复的校准失败，不遮蔽 MySQL 异常。 */
  private <T> T redisRead(Supplier<T> operation) {
    try {
      return StockDependencyGuard.redis(StockDependencyGuard.REDIS_PROJECTION, operation);
    } catch (RedisCalibrationException knownFailure) {
      throw knownFailure;
    } catch (RuntimeException redisFailure) {
      throw new RedisCalibrationException("库存 Redis 启动校准失败", redisFailure);
    }
  }

  /** 日志区分“修复意图已落库”和“Redis 投影已完成”，避免误报闭环。 */
  private void logCalibration(String phase, long startedAt, long legacyScannedAt,
      CalibrationStats stats) {
    log.info("库存缓存差异校准，phase={}，sku={}，matched={}，newer={}，diff={}，intentsAffected={}，projectionWrites=0，blocked={}，legacyMs={}，readMs={}，redisReadMs={}，enqueueMs={}，totalMs={}，peakBatchHeapMiB={}，redisCalls={}，redisP50Us={}，redisP95Us={}，redisP99Us={}，maxReplyBytes={}",
        phase, stats.rowsChecked, stats.matching, stats.newer, stats.differences,
        stats.intentsAffected, stats.blocked, elapsedMillis(startedAt, legacyScannedAt),
        stats.readMillis, stats.redisMillis, stats.enqueueMillis,
        elapsedMillis(startedAt, System.nanoTime()), stats.peakBatchHeapMiB,
        stats.redisCalls, stats.redisP50Us, stats.redisP95Us, stats.redisP99Us,
        stats.maxReplyBytes);
  }

  private record BatchComparison(List<Long> differenceSkuIds, int matching, int newer, int blocked,
      long redisCallMicros, long replyBytes) {}

  private record CalibrationStats(long rowsChecked, long matching, long newer, long blocked,
      long differences, long intentsAffected, long readMillis, long redisMillis,
      long enqueueMillis, long peakBatchHeapMiB, long redisCalls, long redisP50Us,
      long redisP95Us, long redisP99Us, long maxReplyBytes) {}

  private static final class RedisCalibrationException extends RuntimeException {
    private RedisCalibrationException(String message) { super(message); }
    private RedisCalibrationException(String message, Throwable cause) { super(message, cause); }
  }

  /** 定时把已提交的库存版本投影到 Redis。 */
  @Scheduled(fixedDelayString = "${cloudmall.stock.cache-repair-interval-ms:1000}")
  public void repairPending() {
    if (!legacyScanComplete) return;
    // 1. 只扫描已到重试时间且无有效租约的记录。
    OffsetDateTime currentTime = now();
    List<Map<String, Object>> pending =
        stockSqlMapper.findPendingCacheRepairs(currentTime, REPAIR_BATCH_SIZE);
    // 2. 按记录认领；Lua 版本检查阻止旧任务覆盖新的库存事实。
    for (Map<String, Object> repair : pending) {
      long skuId = ((Number) repair.get("sku_id")).longValue();
      try {
        if (Boolean.TRUE.equals(redis.hasKey(BLOCKED_PREFIX + skuId))) continue;
      } catch (RuntimeException unavailableRedis) {
        rescanRequired = true;
        log.warn("Redis 不可用，库存修复任务暂停并等待全量差异复核", unavailableRedis);
        return;
      }
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
        rescanRequired = true;
        finish(repairId, "PENDING", projectionFailure.toString(), currentTime.plusSeconds(5));
        log.warn("库存投影失败，skuId={}，sourceVersion={}",
            skuId, repair.get("source_version"), projectionFailure);
      }
    }
  }

  /** 周期性检查旧预扣标记，完成后清除迁移阻断并排队当前 MySQL 版本。 */
  @Scheduled(fixedDelayString = "${cloudmall.stock.legacy-recheck-interval-ms:30000}")
  public void unblockResolvedLegacyStocks() {
    Set<String> blockedKeys;
    try {
      blockedKeys = scanKeys(BLOCKED_PREFIX + "*");
    } catch (RedisCalibrationException incompleteScan) {
      rescanRequired = true;
      log.warn("库存迁移阻断 Key 扫描未完成，保留阻断等待重试", incompleteScan);
      return;
    }
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
    Set<String> reservationKeys = scanKeys(LEGACY_RESERVATIONS);
    for (String reservationKey : reservationKeys) {
      Map<Object, Object> lines = redisRead(() -> redis.opsForHash().entries(reservationKey));
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

  /** 以游标替代阻塞式 KEYS；未完整扫描时不能认定没有旧协议预扣。 */
  private Set<String> scanKeys(String pattern) {
    return redisRead(() -> {
      long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
      Set<String> matches = new HashSet<>();
      ScanOptions options = ScanOptions.scanOptions().match(pattern).count(SCAN_COUNT).build();
      try (Cursor<String> cursor = redis.scan(options)) {
        while (cursor.hasNext()) {
          if (System.nanoTime() > deadline || matches.size() >= SCAN_MAX_MATCHES)
            throw new RedisCalibrationException("库存旧协议 Redis SCAN 未在资源边界内完成");
          matches.add(cursor.next());
        }
      }
      return matches;
    });
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
