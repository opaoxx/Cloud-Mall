package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDeliveryMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 已提交TCC的严格取消或订单结果待核验意图本地存储器。 */
@Service
public class PayTccCommittedCancelStore {
  /** Pay 原记录 Mapper。 */
  private final PayRecordMapper payments;

  /** 交付台账 Mapper。 */
  private final PayTccDeliveryMapper deliveries;

  /** 原 TCC execution Mapper。 */
  private final PayTccExecutionMapper executions;

  /** 原 User 分支镜像 Mapper。 */
  private final PayTccDispatchMapper dispatches;

  /** 永久 Pay receipt/Fence 证据核验器。 */
  private final PayTccExecutionEvidence evidence;

  /** 16E 取消 gate。 */
  private final PayTccCancelMapper cancellations;

  /** Legacy 扣款意图 gate。 */
  private final PayDebitIntentMapper intents;

  /** 创建本地取消意图存储器。 */
  public PayTccCommittedCancelStore(
      PayRecordMapper payments,
      PayTccDeliveryMapper deliveries,
      PayTccExecutionMapper executions,
      PayTccDispatchMapper dispatches,
      PayTccExecutionEvidence evidence,
      PayTccCancelMapper cancellations,
      PayDebitIntentMapper intents) {
    this.payments = payments;
    this.deliveries = deliveries;
    this.executions = executions;
    this.dispatches = dispatches;
    this.evidence = evidence;
    this.cancellations = cancellations;
    this.intents = intents;
  }

  /** 只提交与原交付阶段匹配的取消意图，接受后正常返回，拒绝时保留既有错误语义。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void request(PayVO payment) {
    validateInput(payment);
    // 1. 统一按 Pay → delivery 顺序取得本地互斥锁。
    List<PayRecordPO> records = payments.selectForUpdate(payment.payNo);
    if (records.size() != 1) throw conflict("支付记录不存在或不唯一");
    PayRecordPO lockedPayment = records.get(0);
    validatePayment(lockedPayment, payment);
    if (!"TCC".equals(lockedPayment.debitMode)
        || !Set.of("PENDING", "ORDER_SYNC_UNKNOWN").contains(lockedPayment.status))
      throw pending("支付已离开可取消窗口");
    PayTccDeliveryPO current = deliveries.selectForUpdate(payment.payNo);
    // 2. 永久 gate 与原 TCC 事实优先于任何改向。
    if (cancellations.selectForUpdate(payment.payNo) != null
        || intents.findByPayNo(payment.payNo) != null) throw pending("存在其他取消或 Legacy 意图");
    LocalDateTime currentTime = now();
    PayTccExecutionPO execution = executions.selectForUpdate(payment.payNo);
    if (execution == null
        || !"COMMITTED".equals(execution.state)
        || !"COMMIT".equals(execution.decision)) throw pending("原资金结果尚未确认");
    validateExecution(lockedPayment, execution);
    PayTccDispatchPO dispatch = dispatches.selectByPayNo(payment.payNo);
    if (dispatch == null
        || !"COMMITTED".equals(evidence.classify(execution, PayTccDispatchVO.from(dispatch))))
      throw pending("TCC 永久证据尚未确认");
    // 3. READY选择严格取消，已有paid许可选择结果待核验；均不接管原lease。
    if (current == null) {
      insertCancellation(execution, currentTime);
      return;
    }
    validateDelivery(current, execution);
    if (Set.of("CANCEL_REQUESTED", "CANCEL_RECONCILING").contains(current.state)) return;
    boolean noLease = current.leaseOwner == null && current.leaseUntil == null;
    boolean completeLease =
        current.leaseOwner != null && !current.leaseOwner.isBlank() && current.leaseUntil != null;
    if (!Set.of("READY", "ORDER_SYNC_UNKNOWN").contains(current.state)
        || current.attempts == null
        || current.attempts < 0
        || (!noLease && !completeLease)) throw pending("交付已经开始，保留原资金结果");
    if (deliveries.requestCancellation(payment.payNo, currentTime) != 1) {
      PayTccDeliveryPO observed = deliveries.selectForUpdate(payment.payNo);
      if (observed == null
          || !Set.of("CANCEL_REQUESTED", "CANCEL_RECONCILING").contains(observed.state))
        throw pending("取消与交付竞争失败");
    }
  }

  /** 插入 immutable 原载荷的取消交付台账。 */
  private void insertCancellation(PayTccExecutionPO execution, LocalDateTime currentTime) {
    PayTccDeliveryPO created = new PayTccDeliveryPO();
    created.id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    created.payNo = execution.payNo;
    created.xid = execution.xid;
    created.userId = execution.userId;
    created.orderNo = execution.orderNo;
    created.amount = execution.amount;
    created.state = "CANCEL_REQUESTED";
    created.refundOperationKey = "refund:" + execution.payNo;
    created.attempts = 0;
    created.nextRetryAt = currentTime;
    created.createdAt = currentTime;
    created.updatedAt = currentTime;
    if (deliveries.insert(created) != 1) throw conflict("取消交付意图写入失败");
  }

  /** 检查 Pay 输入载荷。 */
  private void validateInput(PayVO payment) {
    if (payment == null
        || payment.payNo == null
        || payment.payNo.isBlank()
        || payment.userId == null
        || payment.orderNo == null
        || payment.amount == null
        || payment.amount.signum() <= 0) throw conflict("取消支付载荷不完整");
  }

  /** 检查 Pay 行和入口载荷完全一致。 */
  private void validatePayment(PayRecordPO payment, PayVO input) {
    if (!Objects.equals(payment.payNo, input.payNo)
        || !Objects.equals(payment.userId, input.userId)
        || !Objects.equals(payment.orderNo, input.orderNo)
        || payment.amount == null
        || payment.amount.compareTo(input.amount) != 0) throw conflict("支付载荷不一致");
  }

  /** 检查 execution 与 Pay 的完整 immutable tuple。 */
  private void validateExecution(PayRecordPO payment, PayTccExecutionPO execution) {
    if (!Objects.equals(payment.payNo, execution.payNo)
        || !Objects.equals(payment.userId, execution.userId)
        || !Objects.equals(payment.orderNo, execution.orderNo)
        || payment.amount.compareTo(execution.amount) != 0
        || execution.xid == null
        || execution.xid.isBlank()
        || !Objects.equals(execution.operationKey, "tcc-debit:" + execution.payNo)
        || !Objects.equals(execution.ledgerKey, execution.payNo)) throw conflict("execution 载荷不一致");
  }

  /** 检查 delivery 与 execution 的完整 immutable tuple。 */
  private void validateDelivery(PayTccDeliveryPO delivery, PayTccExecutionPO execution) {
    if (!Objects.equals(delivery.payNo, execution.payNo)
        || !Objects.equals(delivery.xid, execution.xid)
        || !Objects.equals(delivery.userId, execution.userId)
        || !Objects.equals(delivery.orderNo, execution.orderNo)
        || delivery.amount == null
        || delivery.amount.compareTo(execution.amount) != 0
        || !Objects.equals(delivery.refundOperationKey, "refund:" + delivery.payNo))
      throw conflict("交付载荷不一致");
  }

  private static LocalDateTime now() {
    LocalDateTime currentTime = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
    return currentTime.withNano((currentTime.getNano() / 1_000_000) * 1_000_000);
  }

  private BizException pending(String message) {
    return new BizException("PAY_ORDER_SYNC_UNKNOWN", message, 202);
  }

  private BizException conflict(String message) {
    return new BizException("PAY_TCC_DELIVERY_CONFLICT", message, 409);
  }
}
