package com.cloudmall.pay.service.impl.delivery;

import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.service.PayTccDeliveryService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 两阶段有界种任务和交付；资金未知不阻塞后续已确认记录。 */
@Component
@ConditionalOnProperty(name = "cloudmall.pay.tcc-delivery.enabled", havingValue = "true")
@DependsOn("payTccDeliveryConfiguration")
public class PayTccDeliveryScheduler {
  /** 单次恢复轮次的共享预算。 */
  static final long BUDGET_NANOS = 10_000_000_000L;

  /** 调度层失败日志，保留原意图等待后续恢复。 */
  private static final Logger LOG = LoggerFactory.getLogger(PayTccDeliveryScheduler.class);

  /** 防止同实例定时/手动重入。 */
  private final AtomicBoolean running = new AtomicBoolean();

  /** 仅用于恢复预算的单调时钟，不参与数据库时间条件。 */
  private final LongSupplier clock;

  /** 下一轮是否优先扫描尚未创建交付台账的 execution。 */
  private boolean seedFirst = true;

  /** 有界种任务/候选 SQL。 */
  private final PayTccDeliveryMapper mapper;

  /** 短本地事务。 */
  private final PayTccDeliveryStore store;

  /** 完整证据核对与远程交付。 */
  private final PayTccDeliveryService service;

  /** 创建交付调度器。 */
  @Autowired
  public PayTccDeliveryScheduler(
      PayTccDeliveryMapper mapper, PayTccDeliveryStore store, PayTccDeliveryService service) {
    this(mapper, store, service, System::nanoTime);
  }

  /** 供确定性测试注入预算时钟；生产调用统一使用 System.nanoTime。 */
  PayTccDeliveryScheduler(
      PayTccDeliveryMapper mapper,
      PayTccDeliveryStore store,
      PayTccDeliveryService service,
      LongSupplier clock) {
    this.mapper = mapper;
    this.store = store;
    this.service = service;
    this.clock = clock;
  }

  /** 每阶段最多 20 条，10 秒后不开始新任务，不强杀在途 RPC。 */
  @Scheduled(
      fixedDelayString = "${cloudmall.pay.tcc-delivery.interval-ms:5000}",
      initialDelayString = "${cloudmall.pay.tcc-delivery.interval-ms:5000}")
  public void recoverDue() {
    if (!running.compareAndSet(false, true)) return;
    try {
      // 1. 只有取得running权限的轮次才切换优先级，两阶段共用单调时间起点。
      long startedAt = clock.getAsLong();
      boolean currentSeedFirst = seedFirst;
      seedFirst = !seedFirst;
      if (currentSeedFirst) {
        runSeedPhase(startedAt);
        runDuePhase(startedAt);
      } else {
        runDuePhase(startedAt);
        runSeedPhase(startedAt);
      }
    } finally {
      // 2. 查询异常、超时预算或提前退出都不能阻断下一轮调度。
      running.set(false);
    }
  }

  /** 在共享预算内扫描并处理最多 20 条缺失交付意图。 */
  private void runSeedPhase(long startedAt) {
    if (budgetExceeded(startedAt)) return;
    try {
      List<PayTccExecutionPO> executions = mapper.findMissing(20);
      for (int index = 0; index < Math.min(20, executions.size()); index++) {
        if (budgetExceeded(startedAt)) return;
        PayTccExecutionPO execution = executions.get(index);
        try {
          store.ensure(execution.payNo);
        } catch (RuntimeException failure) {
          LOG.warn("TCC 交付意图待核验，payNo={}", execution.payNo, failure);
        }
      }
    } catch (RuntimeException unavailable) {
      LOG.warn("TCC 交付缺失扫描不可用", unavailable);
    }
  }

  /** 在共享预算内扫描并处理最多 20 条到期交付任务。 */
  private void runDuePhase(long startedAt) {
    if (budgetExceeded(startedAt)) return;
    try {
      List<PayTccDeliveryPO> jobs =
          mapper.findDue(LocalDateTime.now(ZoneId.of("Asia/Shanghai")), 20);
      for (int index = 0; index < Math.min(20, jobs.size()); index++) {
        if (budgetExceeded(startedAt)) return;
        PayTccDeliveryPO job = jobs.get(index);
        try {
          service.deliver(job.payNo);
        } catch (RuntimeException failure) {
          LOG.warn("TCC 交付本轮失败，payNo={}", job.payNo, failure);
        }
      }
    } catch (RuntimeException unavailable) {
      LOG.warn("TCC 交付到期扫描不可用", unavailable);
    }
  }

  /** 使用差值比较预算，避免单调时钟绝对值加法溢出。 */
  private boolean budgetExceeded(long startedAt) {
    return clock.getAsLong() - startedAt >= BUDGET_NANOS;
  }
}
