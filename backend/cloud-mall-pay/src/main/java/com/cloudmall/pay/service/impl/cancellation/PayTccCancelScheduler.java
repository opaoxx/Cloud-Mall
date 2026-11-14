package com.cloudmall.pay.service.impl.cancellation;

import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicBoolean;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 仅恢复已有取消意图，每批最多二十条且不超预算启动新任务。 */
@Component
@ConditionalOnProperty(name = "cloudmall.pay.tcc-cancel.enabled", havingValue = "true")
@DependsOn("payTccCancelConfiguration")
public class PayTccCancelScheduler {
  /** 记录恢复失败，不改变业务结论。 */
  private static final Logger LOG = LoggerFactory.getLogger(PayTccCancelScheduler.class);

  /** 防止同实例重入。 */
  private final AtomicBoolean running = new AtomicBoolean();

  /** 在 SQL 限批前过滤不合法任务。 */
  private final PayTccCancelMapper mapper;

  /** 事务外核验和幂等取消。 */
  private final PayTccCancelService service;

  /** 创建持久取消恢复器。 */
  public PayTccCancelScheduler(PayTccCancelMapper mapper, PayTccCancelService service) {
    this.mapper = mapper;
    this.service = service;
  }

  /** 每轮固定预算十秒；不强杀在途 RPC，由 token 阻止迟到写入。 */
  @Scheduled(
      fixedDelayString = "${cloudmall.pay.tcc-cancel.interval-ms:5000}",
      initialDelayString = "${cloudmall.pay.tcc-cancel.interval-ms:5000}")
  public void recoverDue() {
    if (!running.compareAndSet(false, true)) return;
    long deadline = System.nanoTime() + 10_000_000_000L;
    try {
      for (PayTccCancelPO job : mapper.findDue(LocalDateTime.now(ZoneId.of("Asia/Shanghai")), 20)) {
        if (System.nanoTime() >= deadline) break;
        try {
          service.reconcile(job.payNo);
        } catch (RuntimeException failure) {
          LOG.warn("TCC 取消待恢复，payNo={}", job.payNo, failure);
        }
      }
    } catch (RuntimeException unavailable) {
      LOG.warn("TCC 取消扫描不可用", unavailable);
    } finally {
      running.set(false);
    }
  }
}
