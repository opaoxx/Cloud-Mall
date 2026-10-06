package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccExecutionVO;
import com.cloudmall.pay.service.PayTccCoordinatorService;
import io.seata.core.context.RootContext;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 有界恢复调度；恢复 lease 与原不可转移的 begin/Try 权限无关。 */
@Component
@ConditionalOnProperty(name = "cloudmall.pay.tcc-recovery.enabled", havingValue = "true")
@DependsOn("payTccExecutionTableInitializer")
public class PayTccRecoveryScheduler {
  /** 只记录调度失败，不打印内部凭证或完整载荷。 */
  private static final Logger LOG = LoggerFactory.getLogger(PayTccRecoveryScheduler.class);

  /** 本实例只允许一轮调度运行，不因手动重入堆积 RPC。 */
  private final AtomicBoolean running = new AtomicBoolean();

  /** 隔离于网络调用的短事务租约边界。 */
  private final PayTccExecutionRecoveryStore store;

  /** 唯一允许调用的既有恢复接口，不执行 execute/begin/Try。 */
  private final PayTccCoordinatorService coordinator;

  /** 每轮候选上限。 */
  private final int batchSize;

  /** 租约有效秒数，过期后的旧 RPC 仍可能在途，但只能同向幂等恢复。 */
  private final int leaseSeconds;

  /** 启动下一条前检查的单轮时间预算，不强杀在途 RPC。 */
  private final long budgetNanos;

  /** 创建默认关闭的调度器；依赖缺失或无界配置均启动失败。 */
  public PayTccRecoveryScheduler(
      PayTccExecutionRecoveryStore store,
      PayTccCoordinatorService coordinator,
      @Value("${cloudmall.pay.tcc-recovery.batch-size:20}") int batchSize,
      @Value("${cloudmall.pay.tcc-recovery.lease-seconds:60}") int leaseSeconds,
      @Value("${cloudmall.pay.tcc-recovery.budget-ms:10000}") long budgetMillis) {
    if (batchSize < 1
        || batchSize > 20
        || leaseSeconds < 1
        || leaseSeconds > 300
        || budgetMillis < 1
        || budgetMillis > 10000) throw new IllegalArgumentException("TCC 恢复配置超出有界范围");
    this.store = store;
    this.coordinator = coordinator;
    this.batchSize = batchSize;
    this.leaseSeconds = leaseSeconds;
    this.budgetNanos = TimeUnit.MILLISECONDS.toNanos(budgetMillis);
  }

  /** 每轮最多一批，失败和仍未知的记录都进入有限退避。 */
  @Scheduled(
      fixedDelayString = "${cloudmall.pay.tcc-recovery.interval-ms:5000}",
      initialDelayString = "${cloudmall.pay.tcc-recovery.interval-ms:5000}")
  public void recoverDue() {
    if (RootContext.getXID() != null
        || TransactionSynchronizationManager.isActualTransactionActive())
      throw new IllegalStateException("恢复扫描不得继承事务上下文");
    if (!running.compareAndSet(false, true)) return;
    long start = System.nanoTime();
    try {
      // 1. SQL 在 LIMIT 前过滤模式、载荷、状态、决议、XID 与到期时间。
      for (PayTccExecutionPO candidate : store.findDue(now(), batchSize)) {
        if (System.nanoTime() - start >= budgetNanos) break;
        String token = UUID.randomUUID().toString();
        LocalDateTime claimedAt = now();
        try {
          // 2. 随机 token 独立提交后才调用 RPC；失败或失去认领时跳过。
          PayTccExecutionPO claimed =
              store.claimRecovery(
                  candidate.payNo, token, claimedAt.plusSeconds(leaseSeconds), claimedAt);
          if (claimed == null) continue;
          String error = null;
          try {
            PayTccExecutionVO observed = coordinator.recover(claimed.payNo);
            if (observed == null) error = "恢复未返回可核验结果";
            else if (!PayTccExecutionStore.terminal(observed.state))
              error = observed.lastError == null ? "原事务仍待核对" : observed.lastError;
            else if ("CONFLICT".equals(observed.state)) error = "两端永久事实冲突，待人工核验";
          } catch (RuntimeException failure) {
            error =
                failure instanceof BizException business
                    ? business.getCode()
                    : failure.getClass().getSimpleName();
          }
          // 3. 只写调度字段；旧 token 不能释放接班者的租约或改写其重试时间。
          store.finishRecovery(
              claimed.payNo,
              token,
              now().plusSeconds(retryDelaySeconds(claimed.recoveryAttempts)),
              error);
        } catch (RuntimeException storageFailure) {
          LOG.warn("TCC 恢复调度本轮失败，payNo={}", candidate.payNo, storageFailure);
        }
      }
    } catch (RuntimeException scanFailure) {
      LOG.warn("TCC 恢复候选查询失败，保留原事实等待下轮", scanFailure);
    } finally {
      running.set(false);
    }
  }

  /** 尝试次数封顶后仍每 300 秒重查，避免整数溢出或忙循环。 */
  public static long retryDelaySeconds(Integer attempts) {
    int exponent = Math.min(6, Math.max(1, attempts == null ? 1 : attempts) - 1);
    return Math.min(300L, 5L << exponent);
  }

  /** 所有调度时间沿用数据库上海 DATETIME(3) 语义。 */
  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
  }
}
