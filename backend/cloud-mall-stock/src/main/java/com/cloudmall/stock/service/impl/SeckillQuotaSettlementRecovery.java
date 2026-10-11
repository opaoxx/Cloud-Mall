package com.cloudmall.stock.service.impl;

import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.util.StockDependencyGuard;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.io.ClassPathResource;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

/** 秒杀配额释放投影恢复器；以数据库 revision 和租约保证单订单单次执行。 */
@Service
public class SeckillQuotaSettlementRecovery {
  /** 记录恢复失败及其订单归属。 */
  private static final Logger log = LoggerFactory.getLogger(SeckillQuotaSettlementRecovery.class);

  /** 每批最多恢复数量。 */
  private static final int BATCH_SIZE = 50;

  /** 恢复租约秒数。 */
  private static final int LEASE_SECONDS = 30;

  /** 结算事实 Mapper。 */
  private final SeckillQuotaSettlementMapper settlementMapper;

  /** Redis 投影客户端。 */
  private final StringRedisTemplate redis;

  /** 释放 Lua 脚本。 */
  private final DefaultRedisScript<Long> releaseScript;

  /** 创建秒杀结算恢复器。 */
  public SeckillQuotaSettlementRecovery(
      SeckillQuotaSettlementMapper settlementMapper, StringRedisTemplate redis) {
    this.settlementMapper = settlementMapper;
    this.redis = redis;
    this.releaseScript = new DefaultRedisScript<>();
    this.releaseScript.setLocation(new ClassPathResource("lua/seckill-quota-release.lua"));
    this.releaseScript.setResultType(Long.class);
  }

  /** 定时扫描待释放和投影阻塞事实。 */
  @Scheduled(fixedDelayString = "${cloudmall.stock.seckill.settlement-recovery-interval-ms:5000}")
  public void scheduledRecover() {
    try {
      recoverDue();
    } catch (RuntimeException scanFailure) {
      StockDependencyGuard.noteMysqlFailure(scanFailure);
      log.warn("秒杀结算恢复扫描失败，分类=SCAN_FAILURE", scanFailure);
    }
  }

  /** 扫描最多 50 条到期记录并使用原 operationKey/revision 重试。 */
  public int recoverDue() {
    LocalDateTime now = now();
    List<SeckillQuotaSettlementPO> due;
    try {
      due = settlementMapper.findDue(now, BATCH_SIZE);
    } catch (RuntimeException scanFailure) {
      log.warn("秒杀结算恢复查询失败，分类=SCAN_FAILURE", scanFailure);
      return 0;
    }
    int handled = 0;
    for (SeckillQuotaSettlementPO settlement : due) {
      if (recoverOne(settlement, now)) handled++;
    }
    return handled;
  }

  /** 恢复单条结算事实。 */
  private boolean recoverOne(SeckillQuotaSettlementPO settlement, LocalDateTime now) {
    // 1. 核原候选再领取租约；损坏字段不能拼Redis键或产生任何状态写。
    if (!isRollbackCandidate(settlement)) return false;
    long revision = settlement.revision;
    String owner = "seckill-settlement-" + UUID.randomUUID();
    LocalDateTime leaseUntil = now.plusSeconds(LEASE_SECONDS);
    if (settlementMapper.claim(
            settlement.orderNo, settlement.operationKey, revision, owner, leaseUntil, now)
        != 1) return false;
    try {
      // 2. 仅用通过校验的原字段调用既有Lua投影，不重写动作方向。
      List<String> keys =
          List.of(
              "seckill:stock:" + settlement.activityId + ":" + settlement.skuId,
              "seckill:meta:" + settlement.activityId + ":" + settlement.skuId,
              "seckill:accepted:"
                  + settlement.activityId
                  + ":"
                  + settlement.skuId
                  + ":"
                  + settlement.userId
                  + ":"
                  + settlement.idempotencyKey,
              "seckill:quota:op:" + settlement.orderNo,
              "seckill:quota:revision:" + settlement.activityId + ":" + settlement.skuId);
      Long redisRevision =
          StockDependencyGuard.redis(
              StockDependencyGuard.REDIS_PROJECTION,
              () -> {
                Long outcome =
                    redis.execute(
                        releaseScript,
                        keys,
                        settlement.orderNo,
                        settlement.operationKey,
                        String.valueOf(revision),
                        String.valueOf(settlement.quantity));
                if (outcome == null) throw StockDependencyGuard.redisProtocolFailure();
                return outcome;
              });
      // 3. 按既有回执/CAS及重试协议处理结果。
      if (redisRevision != null && redisRevision > 0) {
        if (settlementMapper.markReleased(
                settlement.orderNo, settlement.operationKey, revision, redisRevision, owner)
            != 1) {
          SeckillQuotaSettlementPO current = settlementMapper.findSettlement(settlement.orderNo);
          if (releasedReadbackMatches(settlement, current, redisRevision)) return true;
          log.warn(
              "秒杀结算释放 CAS 未命中，orderNo={},operationKey={},revision={},分类=CAS_UNKNOWN",
              settlement.orderNo,
              settlement.operationKey,
              revision);
          settlementMapper.retry(
              settlement.orderNo,
              settlement.operationKey,
              revision,
              owner,
              "SECKILL_RELEASE_CAS_UNKNOWN",
              now().plusSeconds(10),
              now());
          return false;
        }
        return true;
      }
      LocalDateTime retryAt = now().plusSeconds(10);
      settlementMapper.markProjectionBlocked(
          settlement.orderNo,
          settlement.operationKey,
          revision,
          "SECKILL_RELEASE_PROJECTION_BLOCKED",
          retryAt,
          owner);
      log.warn(
          "秒杀结算投影被阻塞，orderNo={},operationKey={},revision={},分类=PROJECTION_BLOCKED",
          settlement.orderNo,
          settlement.operationKey,
          revision);
      return false;
    } catch (RuntimeException failure) {
      StockDependencyGuard.noteMysqlFailure(failure);
      settlementMapper.retry(
          settlement.orderNo,
          settlement.operationKey,
          revision,
          owner,
          errorSummary(failure),
          now().plusSeconds(10),
          now());
      log.warn(
          "秒杀结算释放重试，orderNo={},operationKey={},revision={},分类=REDIS_OR_CAS_FAILURE",
          settlement.orderNo,
          settlement.operationKey,
          revision,
          failure);
      return false;
    }
  }

  /** CAS读回必须绑定同一不可变行及本次Lua回执；可变诊断不参与，缺证沿原重试。 */
  private static boolean releasedReadbackMatches(
      SeckillQuotaSettlementPO original, SeckillQuotaSettlementPO current, Long redisRevision) {
    return current != null
        && "RELEASED".equals(current.status)
        && current.id != null
        && Objects.equals(original.id, current.id)
        && Objects.equals(original.orderNo, current.orderNo)
        && Objects.equals(original.activityId, current.activityId)
        && Objects.equals(original.skuId, current.skuId)
        && Objects.equals(original.userId, current.userId)
        && Objects.equals(original.idempotencyKey, current.idempotencyKey)
        && Objects.equals(original.quantity, current.quantity)
        && Objects.equals(original.revision, current.revision)
        && Objects.equals(original.operationKey, current.operationKey)
        && original.createdAt != null
        && Objects.equals(original.createdAt, current.createdAt)
        && Objects.equals(redisRevision, current.redisRevision);
  }

  /** 只接受原待释放方向及合法基础维度；非法/confirm候选跳过，不领取租约或规范化原字段。 */
  private static boolean isRollbackCandidate(SeckillQuotaSettlementPO settlement) {
    return settlement != null
        && settlement.orderNo != null
        && !"null".equals(settlement.orderNo)
        && !settlement.orderNo.isBlank()
        && settlement.orderNo.codePointCount(0, settlement.orderNo.length()) <= 64
        && ("RELEASE_PENDING".equals(settlement.status)
            || "PROJECTION_BLOCKED".equals(settlement.status))
        && Objects.equals(settlement.orderNo + ":ROLLBACK", settlement.operationKey)
        && settlement.revision != null
        && settlement.revision == 2L
        && settlement.quantity != null
        && settlement.quantity == 1
        && settlement.activityId != null
        && settlement.activityId > 0
        && settlement.skuId != null
        && settlement.skuId > 0
        && settlement.userId != null
        && settlement.userId > 0
        && settlement.idempotencyKey != null
        && !"null".equals(settlement.idempotencyKey)
        && !settlement.idempotencyKey.isBlank()
        && settlement.idempotencyKey.codePointCount(0, settlement.idempotencyKey.length()) <= 128;
  }

  /** 生成恢复任务统一使用的上海本地时间。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
  }

  /** 错误摘要只保留类型和短消息，避免把连接凭据写入台账。 */
  private static String errorSummary(RuntimeException failure) {
    String message = failure.getMessage() == null ? "" : failure.getMessage();
    return (failure.getClass().getSimpleName() + ":" + message)
        .substring(
            0, Math.min(512, failure.getClass().getSimpleName().length() + 1 + message.length()));
  }
}
