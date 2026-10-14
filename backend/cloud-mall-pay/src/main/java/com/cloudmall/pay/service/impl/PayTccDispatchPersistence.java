package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.util.PayErrorMessages;
import java.time.LocalDateTime;
import java.util.Objects;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Pay TCC 派发的独立本地事务边界，避免桥服务自调用失效。 */
@Service
@DependsOn("payTccCancelTableInitializer")
public class PayTccDispatchPersistence {
  /** 派发事实的事务访问器。 */
  private final PayTccDispatchMapper dispatchMapper;

  /** POST 前再次锁定原支付状态。 */
  private final PayRecordMapper payRecordMapper;

  /** 主动取消 gate，阻止旧 dispatch 与取消意图并存。 */
  private final PayTccCancelMapper cancelMapper;

  /** 创建派发持久化边界。 */
  public PayTccDispatchPersistence(
      PayTccDispatchMapper dispatchMapper,
      PayRecordMapper payRecordMapper,
      PayTccCancelMapper cancelMapper) {
    this.dispatchMapper = dispatchMapper;
    this.payRecordMapper = payRecordMapper;
    this.cancelMapper = cancelMapper;
  }

  /** 独立提交 PREPARED 事实。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccDispatchPO prepare(PayTccDispatchPO dispatch) {
    java.util.List<PayRecordPO> records = payRecordMapper.selectForUpdate(dispatch.payNo);
    if (records.size() != 1) throw conflict();
    PayRecordPO payment = records.get(0);
    if (!"TCC".equals(payment.debitMode)
        || !"PENDING".equals(payment.status)
        || !dispatch.payNo.equals(payment.payNo)
        || !dispatch.orderNo.equals(payment.orderNo)
        || !dispatch.userId.equals(payment.userId)
        || dispatch.amount.compareTo(payment.amount) != 0
        || !Objects.equals(dispatch.operationKey, "tcc-debit:" + dispatch.payNo)
        || !Objects.equals(dispatch.ledgerKey, dispatch.payNo)) throw conflict();
    if (cancelMapper.selectForUpdate(dispatch.payNo) != null) throw conflict();
    if (dispatchMapper.insert(dispatch) != 1) {
      throw new IllegalStateException("TCC 派发事实写入未影响唯一记录");
    }
    return dispatch;
  }

  /**
   * 锁内核对原始派发锚点和未消耗权限，独立提交唯一POST_ATTEMPTED CAS。
   *
   * @param dispatch 调用方此前读取的原始派发快照
   * @param attemptedAt 本次POST权限消耗时间
   * @return 是否成功消费原始派发的一次POST权限
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean markPostAttempted(PayTccDispatchPO dispatch, LocalDateTime attemptedAt) {
    // 1. 保留原Pay支付行锁及载荷门，无效snapshot不能触发数据库访问。
    if (dispatch == null || dispatch.payNo == null) throw conflict();
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
    // 2. 按Pay→cancel→dispatch顺序锁定原行，阻止读取到claim期间的载荷漂移。
    if (cancelMapper.selectForUpdate(dispatch.payNo) != null) return false;
    PayTccDispatchPO current = dispatchMapper.selectForUpdate(dispatch.payNo);
    if (current == null) return false;
    if (!sameAnchor(dispatch, current)) throw conflict();
    if (!"PREPARED".equals(current.state) || current.postAttemptedAt != null) return false;
    // 3. 只有未消费的当前PREPARED可以CAS，不回收已有POST标记。
    return dispatchMapper.markPostAttempted(dispatch.payNo, attemptedAt, attemptedAt) == 1;
  }

  /** 构造派发认领冲突异常。 */
  private BizException conflict() {
    return new BizException("PAY_TCC_DISPATCH_CONFLICT", "Pay dispatch 原支付或取消 gate 冲突", 409);
  }

  /**
   * 锁内核对RPC前原始派发锚点后提交User镜像，诊断保留512完整Unicode码点。
   *
   * @param expected User调用前读取的原始派发快照
   * @param expectedState 调用方此前观察状态，CAS仍使用锁内当前状态
   * @param targetState 已核验的User镜像目标状态
   * @param branchId 本次观察到的User分支，未知时为null
   * @param lastError 未知原因，正常证明时为null
   * @param updatedAt 本次镜像更新时间
   * @return 是否提交了镜像更新
   */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean mirror(
      PayTccDispatchPO expected,
      String expectedState,
      String targetState,
      Long branchId,
      String lastError,
      LocalDateTime updatedAt) {
    // 1. 只使用调用方原始快照定位行，不能锁内重建锚点掩盖RPC窗口漂移。
    if (expected == null || expected.payNo == null) throw conflict();
    String payNo = expected.payNo;
    PayTccDispatchPO current = dispatchMapper.selectForUpdate(payNo);
    if (current == null) throw new BizException("PAY_TCC_DISPATCH_NOT_FOUND", "TCC 派发不存在", 404);
    // 2. 不可变载荷必须仍属于原证据，先于分支、终态和未知诊断的任何处理。
    if (current.payNo == null || !sameAnchor(expected, current)) throw conflict();
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
    // 3. 允许合法状态进展，沿原锁内当前状态CAS，保持终态和分支不可逆规则。
    expectedState = current.state;
    if (dispatchMapper.mirrorState(
            payNo,
            expectedState,
            targetState,
            branchId,
            PayErrorMessages.truncate(lastError),
            updatedAt)
        != 1) {
      throw new IllegalStateException("TCC 派发镜像状态未更新");
    }
    return true;
  }

  /** 包内共享的纯锚点比较；金额按数值比较，状态/分支/时间不作为不可变锚点。 */
  static boolean sameAnchor(PayTccDispatchPO expected, PayTccDispatchPO current) {
    return expected != null
        && current != null
        && Objects.equals(expected.id, current.id)
        && Objects.equals(expected.payNo, current.payNo)
        && Objects.equals(expected.xid, current.xid)
        && Objects.equals(expected.userId, current.userId)
        && Objects.equals(expected.orderNo, current.orderNo)
        && expected.amount != null
        && current.amount != null
        && expected.amount.compareTo(current.amount) == 0
        && Objects.equals(expected.operationKey, current.operationKey)
        && Objects.equals(expected.ledgerKey, current.ledgerKey);
  }
}
