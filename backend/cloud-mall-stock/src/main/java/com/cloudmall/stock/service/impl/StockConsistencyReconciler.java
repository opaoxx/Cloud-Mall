package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.domain.po.StockConsistencyAuditStatePO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockConsistencyAuditStateMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.LocalDateTime;
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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.redis.connection.ReturnType;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;
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

  /** 单次有界 Lua 同序读取缓存类型、数量和版本。 */
  private static final byte[] READ_NORMAL_STOCK = (
      "local result={}; for index=1,#KEYS do "
          + "local kind=redis.call('TYPE',KEYS[index]).ok; result[#result+1]=kind;"
          + "if kind=='hash' then result[#result+1]=redis.call('HGET',KEYS[index],'quantity') or '';"
          + "result[#result+1]=redis.call('HGET',KEYS[index],'version') or '';"
          + "else result[#result+1]=''; result[#result+1]=''; end; end; return result"
      ).getBytes(StandardCharsets.UTF_8);

  /** 保存 redis 的业务状态或配置。 */
  private final StringRedisTemplate redis;

  /** 保存 db 的业务状态或配置。 */
  private final StockSqlMapper stockSqlMapper;

  /** 跨实例和重启均可恢复的普通库存审计游标。 */
  private final StockConsistencyAuditStateMapper auditStateMapper;

  /** 为存量秒杀受理恢复独立 OPEN 结算事实，不猜测最终动作。 */
  @Autowired private SeckillQuotaSettlementMapper settlementMapper;

  /** 普通库存单批读取数量，运行时限制在 1 至 1000。 */
  @Value("${cloudmall.stock.consistency.normal-audit-batch-size:500}")
  private int configuredBatchSize = 500;

  /** 每次调度的最大普通库存批次数。 */
  @Value("${cloudmall.stock.consistency.normal-audit-max-batches:4}")
  private int configuredMaxBatches = 4;

  /** 单轮普通库存审计最大时长，单位毫秒。 */
  @Value("${cloudmall.stock.consistency.normal-audit-max-ms:3000}")
  private long configuredMaxMillis = 3000L;

  /** Redis Lua 单批最大允许耗时，单位毫秒。 */
  @Value("${cloudmall.stock.consistency.normal-audit-redis-max-ms:500}")
  private long configuredRedisMaxMillis = 500L;

  /** Redis Lua 单批最大返回字节数。 */
  @Value("${cloudmall.stock.consistency.normal-audit-reply-max-bytes:100000}")
  private long configuredReplyMaxBytes = 100000L;

  /** 创建 StockConsistencyReconciler 实例。 */
  public StockConsistencyReconciler(StringRedisTemplate redis, StockSqlMapper stockSqlMapper,
      StockConsistencyAuditStateMapper auditStateMapper) {
    // 1. 接收并整理 StockConsistencyReconciler 的业务请求。
    // 2. 执行 StockConsistencyReconciler 的核心业务校验与状态处理。
    // 3. 返回 StockConsistencyReconciler 的处理结果。
    this.redis = redis;
    this.stockSqlMapper = stockSqlMapper;
    this.auditStateMapper = auditStateMapper;
  }

  @PostConstruct
  /** 执行 initialize 相关操作。 */
  public void initialize() {
    // 1. 接收并整理 initialize 的业务请求。
    // 2. 执行 initialize 的核心业务校验与状态处理。
    // 3. 返回 initialize 的处理结果。
    ensureSeckillLedgerTable();
    if (settlementMapper != null) {
      settlementMapper.ensureTable();
      settlementMapper.insertMissingAcceptedOpen(LocalDateTime.now(ZoneOffset.ofHours(8)));
    }
    auditStateMapper.ensureTable();
    auditStateMapper.insertIfAbsent(LocalDateTime.now(ZoneOffset.ofHours(8)));
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
    boolean acquired;
    try {
      acquired = Boolean.TRUE.equals(redis.opsForValue().setIfAbsent(LOCK_KEY, token, LOCK_TTL));
    } catch (RuntimeException lockFailure) {
      recordAuditFailure(lockFailure);
      log.warn("库存对账无法获取 Redis 租约，本轮稍后重试", lockFailure);
      return;
    }
    if (!acquired) {
      log.debug("库存对账租约由其他实例持有，本轮跳过");
      return;
    }
    long lockStartedAt = System.nanoTime();
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
      auditNormalStock(guard);
      guard.run();
      recoverAcceptedSeckillLedger(guard);
      guard.run();
      reconcileSeckillStock(guard);
    } catch (RuntimeException auditFailure) {
      recordAuditFailure(auditFailure);
      log.warn("库存周期对账本轮失败，游标保留并等待下一轮重试", auditFailure);
    } finally {
      renewal.cancel(true);
      renewalExecutor.shutdownNow();
      try {
        redis.execute(RELEASE_LOCK, List.of(LOCK_KEY), token);
      } catch (RuntimeException releaseFailure) {
        log.warn("库存对账租约释放失败，等待 TTL 自然过期", releaseFailure);
      }
      log.info("库存周期对账本轮结束，lockHeldMs={}，leaseOwned={}",
          TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - lockStartedAt), leaseOwned.get());
    }
  }

  /** 只记录失败摘要，不改变已经完成的库存审计进度。 */
  private void recordAuditFailure(RuntimeException failure) {
    try {
      String description = failure.toString();
      auditStateMapper.recordFailure(description.substring(0, Math.min(512, description.length())),
          LocalDateTime.now(ZoneOffset.ofHours(8)));
    } catch (RuntimeException stateFailure) {
      log.warn("库存审计失败状态无法落库，原始故障仍保留在调度日志", stateFailure);
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
      if (settlementMapper != null)
        settlementMapper.insertOpenIfAbsent(orderNo, now.toLocalDateTime());
    }
  }

  /** 执行 auditNormalStock 相关操作。 */
  private void auditNormalStock(Runnable guard) {
    // 1. 从持久游标开始，每轮只获取有限的 MySQL 库存事实批次。
    StockConsistencyAuditStatePO state = auditStateMapper.findState();
    if (state == null || state.cursorSkuId == null || state.cursorSkuId < 0)
      throw new IllegalStateException("普通库存审计游标缺失或无效");
    long cursor = state.cursorSkuId;
    long startCursor = cursor;
    long startedAt = System.nanoTime();
    int batchSize = Math.max(1, Math.min(1000, configuredBatchSize));
    int maxBatches = Math.max(1, Math.min(16, configuredMaxBatches));
    long maxMillis = Math.max(100L, configuredMaxMillis);
    long checked = 0L;
    long different = 0L;
    long newer = 0L;
    int batches = 0;
    long mysqlMillis = 0L;
    long redisMillis = 0L;
    for (; batches < maxBatches
        && TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt) < maxMillis; batches++) {
      guard.run();
      long mysqlStartedAt = System.nanoTime();
      List<Map<String, Object>> rows = stockSqlMapper.findAvailableStockBatchAfter(cursor, batchSize);
      mysqlMillis += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - mysqlStartedAt);
      if (rows.isEmpty()) {
        guard.run();
        if (auditStateMapper.completeCycle(cursor, LocalDateTime.now(ZoneOffset.ofHours(8))) != 1)
          throw new IllegalStateException("普通库存审计周期完成时游标竞争失败");
        log.info("普通库存审计周期完成，startCursor={}，lastCursor={}，rowsThisRun={}，"
                + "batches={}，mysqlMs={}，redisMs={}", startCursor, cursor, checked,
            batches, mysqlMillis, redisMillis);
        return;
      }
      // 2. 一个 Redis Lua 读取同序类型、数量和版本；未知观察不推进游标。
      long redisStartedAt = System.nanoTime();
      List<?> values = readNormalStockBatch(rows);
      redisMillis += TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - redisStartedAt);
      int batchDifferences = 0;
      for (int rowIndex = 0; rowIndex < rows.size(); rowIndex++) {
        Map<String, Object> row = rows.get(rowIndex);
        long skuId = ((Number) row.get("sku_id")).longValue();
        String type = decode(values.get(rowIndex * 3));
        Long redisQuantity = parseNumber(values.get(rowIndex * 3 + 1));
        Long redisVersion = parseNumber(values.get(rowIndex * 3 + 2));
        long mysqlQuantity = ((Number) row.get("available_quantity")).longValue();
        long mysqlVersion = ((Number) row.get("version")).longValue();
        if ("hash".equals(type) && redisVersion != null && redisVersion > mysqlVersion) {
          newer++;
          continue;
        }
        if (!"hash".equals(type) || redisQuantity == null || redisVersion == null
            || redisQuantity != mysqlQuantity || redisVersion != mysqlVersion) {
          batchDifferences++;
          if (batchDifferences <= 5)
            log.warn("普通库存审计差异，skuId={}，type={}，redisQuantity={}，redisVersion={}，"
                    + "mysqlQuantity={}，mysqlVersion={}", skuId, type, redisQuantity,
                redisVersion, mysqlQuantity, mysqlVersion);
        }
      }
      // 3. 再次核对租约，仅完整观察的批次能通过数据库 CAS 推进游标。
      guard.run();
      long nextCursor = ((Number) rows.get(rows.size() - 1).get("sku_id")).longValue();
      if (nextCursor <= cursor || auditStateMapper.advance(cursor, nextCursor, rows.size(),
          LocalDateTime.now(ZoneOffset.ofHours(8))) != 1)
        throw new IllegalStateException("普通库存审计游标推进失败");
      cursor = nextCursor;
      checked += rows.size();
      different += batchDifferences;
      if (batchDifferences > 5)
        log.warn("普通库存审计当前批次差异较多，示例已限制 5 条，batchDifferences={}",
            batchDifferences);
    }
    log.info("普通库存审计有界批次完成，startCursor={}，cursor={}，batches={}，rows={}，"
            + "different={}，newer={}，mysqlMs={}，redisMs={}", startCursor, cursor,
        batches, checked, different, newer,
        mysqlMillis, redisMillis);
  }

  /** 以 Redis 单次只读 Lua 批量查询，并拒绝耗时、长度或返回字节超限的结果。 */
  private List<?> readNormalStockBatch(List<Map<String, Object>> rows) {
    byte[][] keys = rows.stream()
        .map(row -> (NORMAL_PREFIX + ((Number) row.get("sku_id")).longValue())
            .getBytes(StandardCharsets.UTF_8))
        .toArray(byte[][]::new);
    long startedAt = System.nanoTime();
    List<?> values = redis.execute((RedisCallback<List<?>>) connection ->
        (List<?>) connection.eval(READ_NORMAL_STOCK, ReturnType.MULTI, rows.size(), keys));
    long elapsedMillis = TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startedAt);
    if (elapsedMillis > Math.max(1L, configuredRedisMaxMillis))
      throw new IllegalStateException("普通库存审计 Redis 批次超过耗时上限");
    if (values == null || values.size() != rows.size() * 3)
      throw new IllegalStateException("普通库存审计 Redis 批次返回不完整");
    long replyBytes = 0L;
    for (Object value : values) {
      replyBytes += value instanceof byte[] bytes ? bytes.length : decode(value).length();
      if (replyBytes > Math.max(3000L, configuredReplyMaxBytes))
        throw new IllegalStateException("普通库存审计 Redis 批次返回过大");
    }
    log.debug("普通库存审计 Redis 批次完成，keys={}，elapsedMs={}，replyBytes={}",
        rows.size(), elapsedMillis, replyBytes);
    return values;
  }

  /** Redis 回调可能返回原始字节或已转换字符串。 */
  private static String decode(Object value) {
    if (value == null) return "";
    return value instanceof byte[] bytes
        ? new String(bytes, StandardCharsets.UTF_8) : String.valueOf(value);
  }

  /** 字段缺失或非法按观察差异处理，不能把无效数字当作零库存。 */
  private static Long parseNumber(Object value) {
    try {
      return Long.valueOf(decode(value));
    } catch (NumberFormatException malformed) {
      return null;
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
        // Lua 已扣但数据库尚未受理、或取消投影尚未确认时，计数不能证明可售数量。
        log.error(
            "秒杀库存 key 缺失，活动保持不可售，禁止根据计数自动重建；" + "需核验受理与结算回执，activitySku={}，ledgerExpected={}",
            suffix,
            expected);
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
