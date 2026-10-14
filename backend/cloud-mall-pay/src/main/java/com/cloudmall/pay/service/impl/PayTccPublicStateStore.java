package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 只把已经证明的资金回滚投影为公开失败，不主动发起取消或全局决议。 */
@Service
public class PayTccPublicStateStore {
  /** Legacy 意图不得与 TCC 投影共用同一支付事实。 */
  private final PayDebitIntentMapper intents;

  /** 无执行取消与已回滚 execution 不得共存。 */
  private final PayTccCancelMapper cancellations;

  /** 原支付模式与状态的持久入口。 */
  private final PayRecordMapper payments;

  /** 原全局执行和不可变载荷。 */
  private final PayTccExecutionMapper executions;

  /** 原 User 分支的持久镜像。 */
  private final PayTccDispatchMapper dispatches;

  /** 同库 Pay receipt/Fence 一致快照验证。 */
  private final PayTccExecutionEvidence evidence;

  /** 创建本地状态投影服务。 */
  public PayTccPublicStateStore(
      PayRecordMapper payments,
      PayTccExecutionMapper executions,
      PayTccDispatchMapper dispatches,
      PayTccExecutionEvidence evidence,
      PayTccCancelMapper cancellations,
      PayDebitIntentMapper intents) {
    this.intents = intents;
    this.cancellations = cancellations;
    this.payments = payments;
    this.executions = executions;
    this.dispatches = dispatches;
    this.evidence = evidence;
  }

  /** 检查是否已有执行；调用者不得因此推断可主动取消。 */
  public PayTccExecutionPO execution(String payNo) {
    return executions.selectByPayNo(payNo);
  }

  /** 用 Pay 本地快照获取原取消证明，User 当前证明在事务外另行核对。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PayTccDispatchVO cancelledProof(PayVO payment) {
    List<PayRecordPO> records = payments.selectByPayNo(payment.payNo);
    if (records.size() != 1
        || cancellations.selectByPayNo(payment.payNo) != null
        || intents.findByPayNo(payment.payNo) != null) throw pending();
    PayRecordPO record = records.get(0);
    if (!"TCC".equals(record.debitMode)
        || !Objects.equals(record.payNo, payment.payNo)
        || !Objects.equals(record.userId, payment.userId)
        || !Objects.equals(record.orderNo, payment.orderNo)
        || record.amount.compareTo(payment.amount) != 0) throw pending();
    PayTccExecutionPO execution = executions.selectByPayNo(payment.payNo);
    validate(payment, execution);
    PayTccDispatchPO dispatch = dispatches.selectByPayNo(payment.payNo);
    if (dispatch == null) throw pending();
    PayTccDispatchVO proof = PayTccDispatchVO.from(dispatch);
    if (!"ROLLEDBACK".equals(evidence.classify(execution, proof))) throw pending();
    return proof;
  }

  /** 原公开支付行与 execution 再核对，CAS 不得覆盖成功/退款或其他终态。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String projectFailed(PayVO payment, String xid) {
    return projectLocked(payment, xid, null);
  }

  /** 后台投影必须仍持有有效随机租约，旧工作者不得通过公开投影绕开认领。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public String projectFailedClaimed(PayVO payment, String xid, String token) {
    if (token == null || token.isBlank() || token.length() > 128) throw pending();
    return projectLocked(payment, xid, token);
  }

  /** Pay→execution锁内核验所有事实，公开投影无租约，后台投影强制验证原token。 */
  private String projectLocked(PayVO payment, String xid, String token) {
    // 1. 支付行锁与一次性 begin 使用相同互斥边界。
    List<PayRecordPO> records = payments.selectForUpdate(payment.payNo);
    PayTccExecutionPO execution =
        token == null
            ? executions.selectByPayNo(payment.payNo)
            : executions.selectForUpdate(payment.payNo);
    validate(payment, execution);
    if (token != null
        && (!Objects.equals(token, execution.recoveryOwner)
            || execution.recoveryLeaseUntil == null
            || !execution.recoveryLeaseUntil.isAfter(
                LocalDateTime.now(ZoneId.of("Asia/Shanghai"))))) throw pending();
    if (records.size() != 1
        || !Objects.equals(xid, execution.xid)
        || cancellations.selectForUpdate(payment.payNo) != null
        || intents.findByPayNo(payment.payNo) != null) throw pending();
    PayRecordPO record = records.get(0);
    if (!"TCC".equals(record.debitMode)
        || !Objects.equals(record.payNo, payment.payNo)
        || !Objects.equals(record.userId, payment.userId)
        || !Objects.equals(record.orderNo, payment.orderNo)
        || record.amount.compareTo(payment.amount) != 0) throw pending();
    // 2. 只投影已取消事实，不生成退款、不调用订单或改变原 decision。
    if ("FAILED".equals(record.status)) return "FAILED";
    if (Set.of("SUCCESS", "REFUNDED").contains(record.status))
      throw new BizException(com.cloudmall.common.error.ErrorCodes.PAY_DONE, "支付已处理", 409);
    if (!Set.of("PENDING", "ORDER_SYNC_UNKNOWN").contains(record.status)) throw pending();
    if (payments.markFailed(payment.payNo, LocalDateTime.now(ZoneId.of("Asia/Shanghai"))) != 1)
      throw pending();
    return "FAILED";
  }

  /** 已决方向是请求意图，实际回滚必须由两端永久事实证明。 */
  private void validate(PayVO payment, PayTccExecutionPO execution) {
    if (execution == null
        || !"ROLLEDBACK".equals(execution.state)
        || !Set.of("NONE", "COMMIT", "ROLLBACK").contains(execution.decision)
        || execution.xid == null
        || execution.xid.isBlank()
        || !Objects.equals(payment.payNo, execution.payNo)
        || !Objects.equals(payment.userId, execution.userId)
        || !Objects.equals(payment.orderNo, execution.orderNo)
        || payment.amount.compareTo(execution.amount) != 0
        || !Objects.equals(execution.operationKey, "tcc-debit:" + payment.payNo)
        || !Objects.equals(execution.ledgerKey, payment.payNo)) throw pending();
  }

  private BizException pending() {
    return new BizException("PAY_ORDER_SYNC_UNKNOWN", "资金回滚证据尚未齐全", 202);
  }
}
