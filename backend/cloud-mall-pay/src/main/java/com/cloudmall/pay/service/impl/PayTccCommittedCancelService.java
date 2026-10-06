package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.vo.PayVO;
import io.seata.core.context.RootContext;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 已提交 TCC 尚未开始交付窗口的公开取消编排。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-cancel.enabled", havingValue = "true")
@DependsOn("payTccCancelConfiguration")
public class PayTccCommittedCancelService {
  /** 本地取消意图存储器。 */
  private final PayTccCommittedCancelStore store;

  /** 创建公开取消编排器。 */
  public PayTccCommittedCancelService(PayTccCommittedCancelStore store) {
    this.store = store;
  }

  /** 在无外层事务上下文中提交本地意图后返回既有 202 语义。 */
  @Transactional(propagation = Propagation.NOT_SUPPORTED)
  public void request(PayVO payment) {
    // 1. 不允许继承全局决议或跨RPC本地事务。
    if (RootContext.getXID() != null
        || TransactionSynchronizationManager.isActualTransactionActive())
      throw new BizException("PAY_TCC_DELIVERY_CONFLICT", "取消不能继承事务上下文", 409);
    // 2. 先由独立事务提交取消方向，不能把受理响应放在该事务内抛出。
    store.request(payment);
    // 3. 受理不代表订单已取消或退款已完成。
    throw new BizException("PAY_ORDER_SYNC_UNKNOWN", "取消意图已受理，等待交付恢复", 202);
  }
}
