package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import com.cloudmall.pay.domain.vo.PayVO;
import com.cloudmall.pay.mapper.PayDebitIntentMapper;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccExecutionMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.util.PayErrorMessages;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** 主动 TCC 取消的本地抢占、状态 CAS 和恢复 lease 事务边界。 */
@Service
public class PayTccCancelStore {
  /** 取消台账 Mapper。 */
  private final PayTccCancelMapper cancels;

  /** 原支付记录行锁 Mapper。 */
  private final PayRecordMapper payments;

  /** 一次性 TCC execution 事实 Mapper。 */
  private final PayTccExecutionMapper executions;

  /** 旧桥 dispatch 事实 Mapper。 */
  private final PayTccDispatchMapper dispatches;

  /** Pay receipt 事实 Mapper。 */
  private final PayTccReceiptMapper receipts;

  /** Legacy 意图隔离 Mapper。 */
  private final PayDebitIntentMapper intents;

  /** 创建取消台账存储器。 */
  public PayTccCancelStore(
      PayTccCancelMapper cancels,
      PayRecordMapper payments,
      PayTccExecutionMapper executions,
      PayTccDispatchMapper dispatches,
      PayTccReceiptMapper receipts,
      PayDebitIntentMapper intents) {
    this.cancels = cancels;
    this.payments = payments;
    this.executions = executions;
    this.dispatches = dispatches;
    this.receipts = receipts;
    this.intents = intents;
  }

  /** 首次抢占无 execution 的 TCC 支付；已有资金事实以 202 阻断且不写取消行。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccCancelPO claimOrRead(PayVO payment) {
    // 1. 先锁原支付行，冻结取消与首次 TCC begin 的竞态顺序。
    validatePaymentView(payment);
    List<PayRecordPO> records = payments.selectForUpdate(payment.payNo);
    if (records.size() != 1) throw conflict("支付记录不存在或不唯一");
    PayRecordPO lockedPayment = records.get(0);
    validatePayment(lockedPayment, payment);
    if (!"TCC".equals(lockedPayment.debitMode)) throw conflict("支付模式不允许取消");
    PayTccCancelPO current = cancels.selectForUpdate(payment.payNo);
    if (executions.selectByPayNo(payment.payNo) != null
        || dispatches.selectByPayNo(payment.payNo) != null
        || receipts.selectByPayNo(payment.payNo) != null
        || intents.findByPayNo(payment.payNo) != null) {
      throw pending("已存在资金执行，取消未执行，请等待原资金结果");
    }
    if (current != null) {
      validateCancel(current, payment);
      if (!("COMPLETED".equals(current.state)
          ? "FAILED".equals(lockedPayment.status)
          : "PENDING".equals(lockedPayment.status))) throw conflict("取消台账与支付状态冲突");
      return current;
    }
    // 2. 任何旧 TCC/Legacy 事实都不能被主动取消迁移或覆盖。
    if (!"TCC".equals(lockedPayment.debitMode) || !"PENDING".equals(lockedPayment.status))
      throw conflict("支付模式或状态不允许主动取消");
    // 3. 只提交 REQUESTED 抢占意图，事务内绝不调用 Order。
    LocalDateTime now = now();
    PayTccCancelPO created = new PayTccCancelPO();
    created.id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    created.payNo = payment.payNo;
    created.userId = payment.userId;
    created.orderNo = payment.orderNo;
    created.amount = payment.amount;
    created.state = "REQUESTED";
    created.attempts = 0;
    created.nextRetryAt = now;
    created.createdAt = now;
    created.updatedAt = now;
    if (cancels.insertRequested(created) != 1) {
      PayTccCancelPO raced = cancels.selectForUpdate(payment.payNo);
      if (raced != null) {
        validateCancel(raced, payment);
        return raced;
      }
      throw conflict("取消抢占未成功");
    }
    return created;
  }

  /** 认领到期取消恢复任务，旧事实或载荷变化均拒绝认领。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public PayTccCancelPO claimRecovery(String payNo, String token) {
    if (payNo == null || !validToken(token)) throw conflict("取消恢复参数不完整");
    payments.selectForUpdate(payNo);
    LocalDateTime currentTime = now();
    if (cancels.claimRecovery(payNo, token, currentTime.plusSeconds(60), currentTime) != 1)
      return null;
    return cancels.selectByPayNo(payNo);
  }

  /** 短事务推进取消状态，并在 COMPLETED 时与 Pay FAILED 同事务 CAS。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean stage(String payNo, String token, String state, String error) {
    // 1. 重新锁定支付和取消行，防止旧 lease 覆盖新业务事实。
    List<PayRecordPO> records = payments.selectForUpdate(payNo);
    PayTccCancelPO current = cancels.selectForUpdate(payNo);
    LocalDateTime currentTime = now();
    if (records.size() != 1
        || current == null
        || !validToken(token)
        || current.leaseOwner == null
        || !current.leaseOwner.equals(token)
        || current.leaseUntil == null
        || !current.leaseUntil.isAfter(currentTime)
        || java.util.Set.of("COMPLETED", "CONFLICT").contains(current.state)) return false;
    PayRecordPO payment = records.get(0);
    validateCancel(current, payment);
    if (!"TCC".equals(payment.debitMode)
        || !"PENDING".equals(payment.status)
        || executions.selectByPayNo(payNo) != null
        || dispatches.selectByPayNo(payNo) != null
        || receipts.selectByPayNo(payNo) != null
        || intents.findByPayNo(payNo) != null) throw conflict("取消期间支付或资金事实冲突");
    if (!SetOfStates.allowed(state)) throw conflict("取消状态非法");
    // 2. COMPLETED 必须同时满足无任何 TCC 事实和 Pay PENDING→FAILED。
    if ("COMPLETED".equals(state) && payments.markFailed(payNo, currentTime) != 1)
      throw new IllegalStateException("支付取消投影未成功");
    if ("ORDER_CANCELLED".equals(current.state) && "CANCEL_UNKNOWN".equals(state))
      state = "ORDER_CANCELLED";
    // 3. 台账 CAS 失败时让本地事务回滚 Pay FAILED，避免半完成。
    if (cancels.updateState(payNo, token, state, truncate(error), currentTime) != 1)
      throw new IllegalStateException("取消台账状态未更新，回滚支付投影");
    return true;
  }

  /** 释放当前 lease 并按尝试次数设置有界退避，不改变业务状态。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public void finish(String payNo, String token, int attempts, String error) {
    if (!validToken(token)) return;
    int boundedAttempts = Math.max(1, Math.min(31, attempts));
    long seconds = 5L * (1L << Math.min(6, boundedAttempts - 1));
    LocalDateTime currentTime = now();
    cancels.finish(
        payNo,
        token,
        currentTime.plusSeconds(Math.min(300L, seconds)),
        truncate(error),
        currentTime);
  }

  /** 只返回原支付号的取消事实；缺失仍NULL，查询别名行冲突且不创建台账。 */
  public PayTccCancelPO read(String payNo) {
    PayTccCancelPO current = cancels.selectByPayNo(payNo);
    if (current != null && (current.payNo == null || !Objects.equals(current.payNo, payNo)))
      throw conflict("取消台账与原支付号不一致");
    return current;
  }

  private void validatePaymentView(PayVO payment) {
    if (payment == null
        || payment.payNo == null
        || payment.payNo.isBlank()
        || payment.userId == null
        || payment.orderNo == null
        || payment.amount == null
        || payment.amount.signum() <= 0) throw conflict("取消支付载荷不完整");
  }

  private void validatePayment(PayRecordPO payment, PayVO view) {
    if (!Objects.equals(payment.payNo, view.payNo)
        || !Objects.equals(payment.userId, view.userId)
        || !Objects.equals(payment.orderNo, view.orderNo)
        || payment.amount == null
        || payment.amount.compareTo(view.amount) != 0) throw conflict("取消支付载荷不一致");
  }

  private void validateCancel(PayTccCancelPO cancel, PayVO payment) {
    if (!Objects.equals(cancel.payNo, payment.payNo)
        || !Objects.equals(cancel.userId, payment.userId)
        || !Objects.equals(cancel.orderNo, payment.orderNo)
        || cancel.amount == null
        || cancel.amount.compareTo(payment.amount) != 0) throw conflict("取消台账载荷不一致");
  }

  private void validateCancel(PayTccCancelPO cancel, PayRecordPO payment) {
    if (!Objects.equals(cancel.payNo, payment.payNo)
        || !Objects.equals(cancel.userId, payment.userId)
        || !Objects.equals(cancel.orderNo, payment.orderNo)
        || cancel.amount == null
        || cancel.amount.compareTo(payment.amount) != 0) throw conflict("取消台账载荷不一致");
  }

  /** 按UTF-16限制租约身份为非blank且不超过128单位，不归一化原token。 */
  private static boolean validToken(String token) {
    return token != null && !token.isBlank() && token.length() <= 128;
  }

  private static String truncate(String error) {
    return PayErrorMessages.truncate(error);
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
  }

  private BizException pending(String message) {
    return new BizException("PAY_ORDER_SYNC_UNKNOWN", message, 202);
  }

  private BizException conflict(String message) {
    return new BizException("PAY_TCC_CANCEL_CONFLICT", message, 409);
  }

  private static final class SetOfStates {
    private static boolean allowed(String state) {
      return java.util.Set.of(
              "REQUESTED", "ORDER_CANCELLED", "CANCEL_UNKNOWN", "COMPLETED", "CONFLICT")
          .contains(state);
    }
  }
}
