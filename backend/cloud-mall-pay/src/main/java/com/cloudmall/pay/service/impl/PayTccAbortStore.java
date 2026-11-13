package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.domain.vo.PayTccAbortProofVO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.impl.receipt.PayTccExecutionEvidence;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 原双分支受理后竞争尚未选择的回滚决议，事务内不调用远端。 */
@Service
@ConditionalOnProperty(name = "cloudmall.pay.tcc-cancel.enabled", havingValue = "true")
@DependsOn("payTccCancelConfiguration")
public class PayTccAbortStore {
  /** 原支付身份、模式与状态行锁。 */
  private final PayRecordMapper payments;

  /** 与 coordinator 共用的原执行行锁与单向决议 CAS。 */
  private final PayTccExecutionMapper executions;

  /** 原 User 分支镜像与不可变归属。 */
  private final PayTccDispatchMapper dispatches;

  /** 原 Pay 分支身份。 */
  private final PayTccReceiptMapper receipts;

  /** Pay receipt/Fence 与 dispatch 的一致快照核验。 */
  private final PayTccExecutionEvidence evidence;

  /** 无 execution 取消不得与本路径共存。 */
  private final PayTccCancelMapper cancellations;

  /** Legacy 意图不得进入 TCC 决议。 */
  private final PayDebitIntentMapper intents;

  /** 创建不涉及 TC 网络请求的短事务决议存储。 */
  public PayTccAbortStore(
      PayRecordMapper payments,
      PayTccExecutionMapper executions,
      PayTccDispatchMapper dispatches,
      PayTccReceiptMapper receipts,
      PayTccExecutionEvidence evidence,
      PayTccCancelMapper cancellations,
      PayDebitIntentMapper intents) {
    this.payments = payments;
    this.executions = executions;
    this.dispatches = dispatches;
    this.receipts = receipts;
    this.evidence = evidence;
    this.cancellations = cancellations;
    this.intents = intents;
  }

  /** 第一段短事务，提交完整的本地 TRYING 快照后才能读取 User live 事实。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
  public PayTccAbortProofVO preparedProof(PayVO payment) {
    return lockedProof(payment);
  }

  /** User 证明之后重新锁定当前 execution，唯一 CAS 决定回滚，绝不覆盖 COMMIT。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW, isolation = Isolation.REPEATABLE_READ)
  public void requestRollback(PayVO payment, PayTccAbortProofVO expected) {
    // 1. 重新开始快照并按 Pay→execution 锁序验证当前未决事实。
    PayTccAbortProofVO current = lockedProof(payment);
    if (expected == null
        || !Objects.equals(current.payNo, expected.payNo)
        || !Objects.equals(current.orderNo, expected.orderNo)
        || !Objects.equals(current.userId, expected.userId)
        || !Objects.equals(current.amount, expected.amount)
        || !Objects.equals(current.xid, expected.xid)
        || !Objects.equals(current.operationKey, expected.operationKey)
        || !Objects.equals(current.ledgerKey, expected.ledgerKey)
        || !Objects.equals(current.payBranchId, expected.payBranchId)
        || !Objects.equals(current.userBranchId, expected.userBranchId)) throw conflict();
    // 2. 原 lease 不改绑；让已有恢复器尽快处理持久 ROLLBACK 请求。
    if (executions.requestRollback(
            payment.payNo, current.xid, LocalDateTime.now(ZoneId.of("Asia/Shanghai")))
        != 1) {
      PayTccExecutionPO observed = executions.selectForUpdate(payment.payNo);
      if (observed != null
          && Objects.equals(observed.xid, current.xid)
          && "ROLLBACK".equals(observed.decision)
          && "ROLLBACK_REQUESTED".equals(observed.state)) return;
      throw pending();
    }
  }

  /** 锁仅覆盖 Pay/execution；不锁 receipt→Fence，以免与 RM 的 Fence→receipt 反序。 */
  private PayTccAbortProofVO lockedProof(PayVO expected) {
    if (expected == null || expected.payNo == null || expected.amount == null) throw pending();
    List<PayRecordPO> records = payments.selectForUpdate(expected.payNo);
    PayTccExecutionPO execution = executions.selectForUpdate(expected.payNo);
    if (records.size() != 1 || execution == null) throw pending();
    PayRecordPO payment = records.get(0);
    if (!"TCC".equals(payment.debitMode)
        || !("PENDING".equals(payment.status) || "ORDER_SYNC_UNKNOWN".equals(payment.status))
        || cancellations.selectByPayNo(expected.payNo) != null
        || intents.findByPayNo(expected.payNo) != null) throw pending();
    if (!"NONE".equals(execution.decision)
        || !("RUNNING".equals(execution.state) || "UNKNOWN".equals(execution.state))
        || execution.xid == null
        || execution.xid.isBlank()) throw pending();
    if (!Objects.equals(expected.payNo, payment.payNo)
        || !Objects.equals(expected.orderNo, payment.orderNo)
        || !Objects.equals(expected.userId, payment.userId)
        || !sameAmount(expected.amount, payment.amount)
        || !Objects.equals(execution.payNo, payment.payNo)
        || !Objects.equals(execution.userId, payment.userId)
        || !Objects.equals(execution.orderNo, payment.orderNo)
        || !sameAmount(execution.amount, payment.amount)
        || !Objects.equals(execution.operationKey, "tcc-debit:" + payment.payNo)
        || !Objects.equals(execution.ledgerKey, payment.payNo)) throw conflict();
    PayTccDispatchPO dispatch = dispatches.selectByPayNo(payment.payNo);
    if (dispatch == null || !"TRY_ACCEPTED".equals(dispatch.state)) throw pending();
    if (!"TRYING".equals(evidence.classify(execution, PayTccDispatchVO.from(dispatch))))
      throw pending();
    PayTccReceiptPO receipt = receipts.selectByPayNo(payment.payNo);
    if (receipt == null || receipt.branchId == null || dispatch.userBranchId == null)
      throw pending();
    PayTccAbortProofVO proof = new PayTccAbortProofVO();
    proof.payNo = execution.payNo;
    proof.orderNo = execution.orderNo;
    proof.userId = execution.userId;
    proof.amount = execution.amount.toPlainString();
    proof.xid = execution.xid;
    proof.operationKey = execution.operationKey;
    proof.ledgerKey = execution.ledgerKey;
    proof.payBranchId = receipt.branchId;
    proof.userBranchId = dispatch.userBranchId;
    return proof;
  }

  /** 金额比较保持数值语义，不丢失分单位。 */
  private boolean sameAmount(BigDecimal left, BigDecimal right) {
    return left != null && right != null && left.compareTo(right) == 0;
  }

  /** 证据未齐或决议已选时保持未知，不发相反请求。 */
  private BizException pending() {
    return new BizException("PAY_ORDER_SYNC_UNKNOWN", "未决回滚条件尚未满足，请等待原资金结果", 202);
  }

  /** 原载荷/分支矛盾不允许通过重新选择决议修复。 */
  private BizException conflict() {
    return new BizException("PAY_TCC_EXECUTION_CONFLICT", "回滚请求与原执行事实不一致", 409);
  }
}
