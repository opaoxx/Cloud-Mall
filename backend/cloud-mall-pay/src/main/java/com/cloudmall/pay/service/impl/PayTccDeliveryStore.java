package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.mapper.PayRecordMapper;
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
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 交付任务、租约和 Pay 记录的本地事务边界；禁止在其中执行 RPC。 */
@Service
public class PayTccDeliveryStore {
  /** 交付持久意图和随机租约。 */
  private final PayTccDeliveryMapper deliveries;

  /** 原支付载荷及公开支付状态。 */
  private final PayRecordMapper payments;

  /** TCC 执行的不可变载荷与全局资金结果。 */
  private final PayTccExecutionMapper executions;

  /** 原 User branch 镜像，不作为当前远程状态替代品。 */
  private final PayTccDispatchMapper dispatches;

  /** Pay receipt/Fence 的一致快照核验。 */
  private final PayTccExecutionEvidence evidence;

  /** 创建独立事务存储器。 */
  public PayTccDeliveryStore(
      PayTccDeliveryMapper deliveries,
      PayRecordMapper payments,
      PayTccExecutionMapper executions,
      PayTccDispatchMapper dispatches,
      PayTccExecutionEvidence evidence) {
    this.deliveries = deliveries;
    this.payments = payments;
    this.executions = executions;
    this.dispatches = dispatches;
    this.evidence = evidence;
  }

  /** 创建待核验意图；READY 不代表已取得当前 User/Order 的交付许可。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccDeliveryPO ensure(String payNo) {
    // 1. 原支付先锁定，沿用所有 Pay 写入的锁顺序。
    List<PayRecordPO> records = payments.selectForUpdate(payNo);
    PayTccExecutionPO execution = executions.selectByPayNo(payNo);
    if (execution == null
        || !"COMMITTED".equals(execution.state)
        || !"COMMIT".equals(execution.decision)) return null;
    if (records.size() != 1) throw conflict();
    requirePayment(records.get(0), execution);
    PayTccDeliveryPO current = deliveries.selectForUpdate(payNo);
    if (current != null) {
      requireJob(current, execution);
      return current;
    }
    if (!Set.of("PENDING", "ORDER_SYNC_UNKNOWN").contains(records.get(0).status)) throw conflict();
    // 2. 独立持久化后再进行外部核对，缺失远程事实也能公平退避。
    PayTccDeliveryPO created = new PayTccDeliveryPO();
    created.id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    created.payNo = execution.payNo;
    created.xid = execution.xid;
    created.userId = execution.userId;
    created.orderNo = execution.orderNo;
    created.amount = execution.amount;
    created.refundOperationKey = "refund:" + execution.payNo;
    created.state = "READY";
    created.attempts = 0;
    created.nextRetryAt = now();
    created.createdAt = created.nextRetryAt;
    created.updatedAt = created.createdAt;
    if (deliveries.insert(created) != 1) throw conflict();
    return created;
  }

  /** 单次随机 token 认领后立即结束事务，不跨 RPC 持锁。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccDeliveryPO claim(String payNo, String token) {
    LocalDateTime now = now();
    if (deliveries.claim(payNo, token, now.plusSeconds(60), now) != 1) return null;
    return deliveries.selectByPayNo(payNo);
  }

  /** 同一 Pay 快照核对 execution/receipt/Fence，远程 User 由调用者另行实时查询。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public PayTccDispatchVO proof(PayTccDeliveryPO job) {
    PayTccExecutionPO execution = executions.selectByPayNo(job.payNo);
    requireJob(job, execution);
    PayTccDispatchPO dispatch = dispatches.selectByPayNo(job.payNo);
    if (dispatch == null) throw conflict();
    PayTccDispatchVO view = PayTccDispatchVO.from(dispatch);
    if (!"COMMITTED".equals(evidence.classify(execution, view))) throw conflict();
    return view;
  }

  /** 交付状态与 PayRecord 同一事务推进，旧 token 不得写任何本地业务状态。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean stage(String payNo, String token, String target) {
    // 1. 完整原载荷与 lease 再次核验。
    List<PayRecordPO> records = payments.selectForUpdate(payNo);
    PayTccDeliveryPO job = deliveries.selectForUpdate(payNo);
    LocalDateTime now = now();
    if (job == null
        || token == null
        || job.leaseOwner == null
        || !job.leaseOwner.equals(token)
        || job.leaseUntil == null
        || !job.leaseUntil.isAfter(now)
        || terminal(job.state)) return false;
    PayTccExecutionPO execution = executions.selectByPayNo(payNo);
    requireJob(job, execution);
    if (records.size() != 1) throw conflict();
    PayRecordPO payment = records.get(0);
    requirePayment(payment, execution);
    // 2. REFUND_PENDING 不允许回到订单交付，也不能覆盖任一相反终态。
    if (!Set.of("ORDER_SYNC_UNKNOWN", "REFUND_PENDING", "SUCCESS", "REFUNDED").contains(target)
        || ("REFUND_PENDING".equals(job.state)
            && Set.of("ORDER_SYNC_UNKNOWN", "SUCCESS").contains(target))
        || ("REFUNDED".equals(target) && !"REFUND_PENDING".equals(job.state))) throw conflict();
    int changed;
    if (target.equals(payment.status)) changed = 1;
    else if ("ORDER_SYNC_UNKNOWN".equals(target)) changed = payments.markOrderUnknown(payNo, now);
    else if ("REFUND_PENDING".equals(target)) changed = payments.markRefundPending(payNo, now);
    else if ("SUCCESS".equals(target)) changed = payments.markSuccess(payNo, now, now);
    else changed = payments.markRefunded(payNo, now);
    if (changed != 1) throw conflict();
    // 3. receipt 交付 CAS 失败会一起回滚上述 PayRecord 更新。
    if (deliveries.updateState(payNo, token, job.state, target, null, now) != 1) throw conflict();
    return true;
  }

  /** 只更新当前 lease 的恢复元数据，不覆写终态。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(String payNo, String token, int attempts, String error) {
    if (error != null && error.length() > 512) error = error.substring(0, 512);
    LocalDateTime now = now();
    deliveries.finishAttempt(
        payNo,
        token,
        now.plusSeconds(PayTccRecoveryScheduler.retryDelaySeconds(attempts)),
        error,
        now);
  }

  /** 读取原任务，不补建、扣款或调用远程资源。 */
  public PayTccDeliveryPO read(String payNo) {
    return deliveries.selectByPayNo(payNo);
  }

  /** 返回已落库的交付终态。 */
  public static boolean terminal(String state) {
    return "SUCCESS".equals(state) || "REFUNDED".equals(state);
  }

  /** 严格匹配冻结的业务载荷与支付模式，不能把 Legacy 交易迁入。 */
  private void requirePayment(PayRecordPO payment, PayTccExecutionPO execution) {
    if (!"TCC".equals(payment.debitMode)
        || !Objects.equals(payment.payNo, execution.payNo)
        || !Objects.equals(payment.userId, execution.userId)
        || !Objects.equals(payment.orderNo, execution.orderNo)
        || payment.amount.compareTo(execution.amount) != 0
        || execution.xid == null
        || execution.xid.isBlank()
        || !Objects.equals(execution.operationKey, "tcc-debit:" + execution.payNo)
        || !Objects.equals(execution.ledgerKey, execution.payNo)) throw conflict();
  }

  /** 一致快照中的原 execution 与 delivery 不允许换 XID、金额或退款键。 */
  private void requireJob(PayTccDeliveryPO job, PayTccExecutionPO execution) {
    if (execution == null
        || !"COMMITTED".equals(execution.state)
        || !"COMMIT".equals(execution.decision)
        || !Objects.equals(job.payNo, execution.payNo)
        || !Objects.equals(job.xid, execution.xid)
        || !Objects.equals(job.userId, execution.userId)
        || !Objects.equals(job.orderNo, execution.orderNo)
        || job.amount.compareTo(execution.amount) != 0
        || !Objects.equals(job.refundOperationKey, "refund:" + job.payNo)) throw conflict();
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
  }

  private BizException conflict() {
    return new BizException("PAY_TCC_DELIVERY_CONFLICT", "交付原载荷、租约或状态不一致", 409);
  }
}
