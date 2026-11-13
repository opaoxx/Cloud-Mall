package com.cloudmall.pay.service.impl.callback;

import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import com.cloudmall.pay.mapper.PayCallbackLogMapper;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** 有界轮转扫描已有支付终态的回调收据，只调用独立日志完成事务。 */
@Component
public class PayCallbackReceiptRecoveryScheduler {
  /** 单条坏载荷只记持久主键和异常类型，不输出原始载荷。 */
  private static final Logger log =
      LoggerFactory.getLogger(PayCallbackReceiptRecoveryScheduler.class);

  /** 每轮最多处理的候选数，不随着坏载荷增加扫描预算。 */
  private static final int BATCH_SIZE = 50;

  /** 候选查询入口，SQL 和状态过滤均留在持久层。 */
  private final PayCallbackLogMapper callbackLogs;

  /** Spring 代理的收据服务，每次外部调用建立独立 REQUIRES_NEW 事务。 */
  private final PayCallbackReceiptService receipts;

  /** 仅本进程扫描进度，重启从头重扫；它不代表任何日志已经完成。 */
  private final AtomicReference<Long> scanAfterId = new AtomicReference<>();

  /**
   * 创建回调收据恢复扫描器。
   *
   * @param callbackLogs 回调日志候选查询入口
   * @param receipts 独立事务的收据服务代理
   */
  public PayCallbackReceiptRecoveryScheduler(
      PayCallbackLogMapper callbackLogs, PayCallbackReceiptService receipts) {
    this.callbackLogs = callbackLogs;
    this.receipts = receipts;
  }

  /** 每轮最多50条，空段最多回头一次，坏行不能阻塞后续候选或反复占据队头。 */
  @Scheduled(fixedDelayString = "${cloudmall.pay.recovery-interval-ms:5000}")
  public void recoverDue() {
    // 1. 只读获取升序候选；查询失败保留游标，不扫描第二个非空批次。
    Long startCursor = scanAfterId.get();
    List<PayCallbackLogPO> candidates = callbackLogs.findCompletableAfter(startCursor, BATCH_SIZE);
    if (candidates.isEmpty() && startCursor != null)
      candidates = callbackLogs.findCompletableAfter(null, BATCH_SIZE);
    Long endCursor = candidates.isEmpty() ? null : candidates.get(candidates.size() - 1).id;
    if (!candidates.isEmpty() && endCursor == null) endCursor = startCursor;
    try {
      // 2. 调用另一Bean的代理，每条独立提交或回滚，不重入任何支付/资金编排。
      for (PayCallbackLogPO candidate : candidates) {
        try {
          receipts.recoverReceived(candidate.callbackId);
        } catch (RuntimeException failure) {
          log.warn(
              "回调收据恢复本轮未完成，receiptId={},failureType={}",
              candidate.id,
              failure.getClass().getSimpleName());
        }
      }
    } finally {
      // 3. 即使坏行也前移，只允许原游标更新，避免迟到轮次倒退已经推进的进度。
      scanAfterId.compareAndSet(startCursor, endCursor);
    }
  }
}
