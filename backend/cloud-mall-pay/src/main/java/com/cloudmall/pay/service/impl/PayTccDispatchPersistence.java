package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import java.time.LocalDateTime;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Pay TCC 派发的独立本地事务边界，避免桥服务自调用失效。 */
@Service
public class PayTccDispatchPersistence {
  /** 派发事实的事务访问器。 */
  private final PayTccDispatchMapper dispatchMapper;

  /** POST 前再次锁定原支付状态。 */
  private final PayRecordMapper payRecordMapper;

  /** 创建派发持久化边界。 */
  public PayTccDispatchPersistence(
      PayTccDispatchMapper dispatchMapper, PayRecordMapper payRecordMapper) {
    this.dispatchMapper = dispatchMapper;
    this.payRecordMapper = payRecordMapper;
  }

  /** 独立提交 PREPARED 事实。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccDispatchPO prepare(PayTccDispatchPO dispatch) {
    if (dispatchMapper.insert(dispatch) != 1) {
      throw new IllegalStateException("TCC 派发事实写入未影响唯一记录");
    }
    return dispatch;
  }

  /** 独立提交唯一 POST_ATTEMPTED CAS。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markPostAttempted(PayTccDispatchPO dispatch, LocalDateTime attemptedAt) {
    java.util.List<PayRecordPO> records = payRecordMapper.selectForUpdate(dispatch.payNo);
    PayRecordPO payment = records.size() == 1 ? records.get(0) : null;
    if (payment == null
        || !"TCC".equals(payment.debitMode)
        || !"PENDING".equals(payment.status)
        || !dispatch.payNo.equals(payment.payNo)
        || !dispatch.orderNo.equals(payment.orderNo)
        || dispatch.userId == null
        || !dispatch.userId.equals(payment.userId)
        || dispatch.amount.compareTo(payment.amount) != 0) {
      return false;
    }
    return dispatchMapper.markPostAttempted(dispatch.payNo, attemptedAt, attemptedAt) == 1;
  }

  /** 独立提交 User 只读结果镜像。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean mirror(
      String payNo,
      String expectedState,
      String targetState,
      Long branchId,
      String lastError,
      LocalDateTime updatedAt) {
    PayTccDispatchPO current = dispatchMapper.selectForUpdate(payNo);
    if (current == null) throw new BizException("PAY_TCC_DISPATCH_NOT_FOUND", "TCC 派发不存在", 404);
    if (branchId != null && branchId <= 0)
      throw new BizException("PAY_TCC_UNKNOWN", "User 分支标识不合法", 503);
    if (current.userBranchId != null
        && branchId != null
        && !current.userBranchId.equals(branchId)) {
      throw new BizException("PAY_TCC_DISPATCH_CONFLICT", "User 分支标识不一致", 409);
    }
    if ("CONFIRMED".equals(current.state) || "CANCELLED".equals(current.state)) {
      if (("CONFIRMED".equals(targetState) || "CANCELLED".equals(targetState))
          && !current.state.equals(targetState))
        throw new BizException("PAY_TCC_DISPATCH_CONFLICT", "TCC 终态矛盾", 409);
      return false;
    }
    if ("PREPARED".equals(current.state)) return false;
    if ("TRY_UNKNOWN".equals(targetState) && "TRY_ACCEPTED".equals(current.state)) return false;
    expectedState = current.state;
    if (dispatchMapper.mirrorState(
            payNo, expectedState, targetState, branchId, lastError, updatedAt)
        != 1) {
      throw new IllegalStateException("TCC 派发镜像状态未更新");
    }
    return true;
  }
}
