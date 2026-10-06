package com.cloudmall.pay.service.impl;

import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.service.PayTccDeliveryService;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 两阶段有界种任务和交付；资金未知不阻塞后续已确认记录。 */
@Component
@ConditionalOnProperty(name = "cloudmall.pay.tcc-delivery.enabled", havingValue = "true")
@DependsOn("payTccDeliveryConfiguration")
public class PayTccDeliveryScheduler {
  /** 调度层失败日志，保留原意图等待后续恢复。 */
  private static final Logger LOG = LoggerFactory.getLogger(PayTccDeliveryScheduler.class);

  /** 防止同实例定时/手动重入。 */
  private final AtomicBoolean running = new AtomicBoolean();

  /** 有界种任务/候选 SQL。 */
  private final PayTccDeliveryMapper mapper;

  /** 短本地事务。 */
  private final PayTccDeliveryStore store;

  /** 完整证据核对与远程交付。 */
  private final PayTccDeliveryService service;

  /** 创建交付调度器。 */
  public PayTccDeliveryScheduler(
      PayTccDeliveryMapper mapper, PayTccDeliveryStore store, PayTccDeliveryService service) {
    this.mapper = mapper;
    this.store = store;
    this.service = service;
  }

  /** 每阶段最多 20 条，10 秒后不开始新任务，不强杀在途 RPC。 */
  @Scheduled(
      fixedDelayString = "${cloudmall.pay.tcc-delivery.interval-ms:5000}",
      initialDelayString = "${cloudmall.pay.tcc-delivery.interval-ms:5000}")
  public void recoverDue() {
    if (!running.compareAndSet(false, true)) return;
    long deadline = System.nanoTime() + 10_000_000_000L;
    try {
      for (PayTccExecutionPO execution : mapper.findMissing(20)) {
        if (System.nanoTime() >= deadline) return;
        try {
          store.ensure(execution.payNo);
        } catch (RuntimeException failure) {
          LOG.warn("TCC 交付意图待核验，payNo={}", execution.payNo, failure);
        }
      }
      for (PayTccDeliveryPO job :
          mapper.findDue(LocalDateTime.now(ZoneId.of("Asia/Shanghai")), 20)) {
        if (System.nanoTime() >= deadline) return;
        try {
          service.deliver(job.payNo);
        } catch (RuntimeException failure) {
          LOG.warn("TCC 交付本轮失败，payNo={}", job.payNo, failure);
        }
      }
    } catch (RuntimeException unavailable) {
      LOG.warn("TCC 交付扫描不可用", unavailable);
    } finally {
      running.set(false);
    }
  }
}
