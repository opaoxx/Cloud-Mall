package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayRecordPO;
import com.cloudmall.pay.domain.po.PayTccDispatchPO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.mapper.PayRecordMapper;
import com.cloudmall.pay.mapper.PayTccCancelMapper;
import com.cloudmall.pay.mapper.PayTccDispatchMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Pay receipt 的独立 CLAIMED 认领事务。 */
@Service
@DependsOn("payTccCancelTableInitializer")
public class PayTccReceiptStore {
  /** 数据库时间采用上海时区。 */
  private static final ZoneId BUSINESS_ZONE = ZoneId.of("Asia/Shanghai");

  /** receipt 唯一数据库访问入口。 */
  private final PayTccReceiptMapper receiptMapper;

  /** 原支付行作为同 payNo 认领的互斥锁。 */
  private final PayRecordMapper payments;

  /** receipt 必须绑定真实存在的已提交派发锚点。 */
  private final PayTccDispatchMapper dispatches;

  /** 主动取消 gate，阻止 receipt 与取消意图并存。 */
  private final PayTccCancelMapper cancels;

  /** 创建 receipt 存储服务。 */
  public PayTccReceiptStore(
      PayTccReceiptMapper receiptMapper,
      PayRecordMapper payments,
      PayTccDispatchMapper dispatches,
      PayTccCancelMapper cancels) {
    this.receiptMapper = receiptMapper;
    this.payments = payments;
    this.dispatches = dispatches;
    this.cancels = cancels;
  }

  /** 只允许完整不可变载荷的首个请求成为 winner。 */
  @Transactional(propagation = Propagation.REQUIRES_NEW)
  public boolean claim(PayTccDispatchVO dispatch) {
    // 1. 固定原支付→派发→取消gate→receipt锁序，核两来源原支付号后才能认领。
    List<PayRecordPO> records = payments.selectForUpdate(dispatch.payNo);
    PayTccDispatchPO anchor = dispatches.selectForUpdate(dispatch.payNo);
    if (records.size() != 1 || anchor == null) throw conflict();
    if (cancels.selectForUpdate(dispatch.payNo) != null) throw conflict();
    PayRecordPO payment = records.get(0);
    if (!"TCC".equals(payment.debitMode)
        || dispatch.payNo == null
        || !Objects.equals(payment.payNo, dispatch.payNo)
        || !Objects.equals(anchor.payNo, dispatch.payNo)
        || !Objects.equals(payment.userId, dispatch.userId)
        || !Objects.equals(payment.orderNo, dispatch.orderNo)
        || payment.amount.compareTo(new BigDecimal(dispatch.amount)) != 0
        || !Objects.equals(anchor.xid, dispatch.xid)
        || !Objects.equals(anchor.userId, dispatch.userId)
        || !Objects.equals(anchor.orderNo, dispatch.orderNo)
        || anchor.amount.compareTo(new BigDecimal(dispatch.amount)) != 0
        || !Objects.equals(anchor.operationKey, dispatch.operationKey)
        || !Objects.equals(anchor.ledgerKey, dispatch.ledgerKey)
        || !Objects.equals(dispatch.operationKey, "tcc-debit:" + dispatch.payNo)
        || !Objects.equals(dispatch.ledgerKey, dispatch.payNo)) throw conflict();
    PayTccReceiptPO existing = receiptMapper.selectByPayNoForUpdate(dispatch.payNo);
    if (existing != null) {
      validate(existing, dispatch);
      return false;
    }
    // 首次Pay认领必须先于User派发；已存在receipt的重放不受这些fresh条件影响。
    if (!"PENDING".equals(payment.status)
        || !"PREPARED".equals(dispatch.state)
        || dispatch.userBranchId != null
        || !"PREPARED".equals(anchor.state)
        || anchor.postAttemptedAt != null
        || anchor.userBranchId != null) throw conflict();
    // 2. 独立提交唯一认领，只有插入胜者才可在事务外注册 Seata 分支。
    PayTccReceiptPO candidate = toReceipt(dispatch);
    int inserted = receiptMapper.claim(candidate);
    PayTccReceiptPO stored = receiptMapper.selectByPayNoForUpdate(dispatch.payNo);
    validate(stored, dispatch);
    return inserted == 1;
  }

  /** 返回原支付号receipt的最新状态；缺失保持NULL，查询别名不能作为本次事实。 */
  public PayTccReceiptPO read(String payNo) {
    PayTccReceiptPO receipt = receiptMapper.selectByPayNo(payNo);
    if (receipt != null && (receipt.payNo == null || !Objects.equals(receipt.payNo, payNo)))
      throw conflict();
    return receipt;
  }

  /** 锁定原支付号receipt供callback完整核验；缺失保持NULL，不返回查询别名行。 */
  public PayTccReceiptPO lock(String payNo) {
    PayTccReceiptPO receipt = receiptMapper.selectByPayNoForUpdate(payNo);
    if (receipt != null && (receipt.payNo == null || !Objects.equals(receipt.payNo, payNo)))
      throw conflict();
    return receipt;
  }

  /** 将 CLAIMED receipt 绑定首个 Pay branch 并推进目标状态。 */
  @Transactional
  public int transitionClaimed(
      PayTccDispatchDTO request, String xid, Long branchId, String targetState) {
    return receiptMapper.bindAndTransition(
        request.payNo, xid, branchId, "CLAIMED", targetState, LocalDateTime.now(BUSINESS_ZONE));
  }

  /** 按既有分支 CAS 推进 receipt 状态。 */
  @Transactional
  public int transition(
      PayTccDispatchDTO request,
      String xid,
      Long branchId,
      String expectedState,
      String targetState) {
    return receiptMapper.transitionState(
        request.payNo, xid, branchId, expectedState, targetState, LocalDateTime.now(BUSINESS_ZONE));
  }

  /** 从已校验的原锚点构造一次性认领，不推断资金结果。 */
  private PayTccReceiptPO toReceipt(PayTccDispatchVO dispatch) {
    PayTccReceiptPO receipt = new PayTccReceiptPO();
    receipt.id = UUID.randomUUID().getMostSignificantBits() & Long.MAX_VALUE;
    receipt.payNo = dispatch.payNo;
    receipt.xid = dispatch.xid;
    receipt.userId = dispatch.userId;
    receipt.orderNo = dispatch.orderNo;
    receipt.amount = new BigDecimal(dispatch.amount);
    receipt.operationKey = dispatch.operationKey;
    receipt.ledgerKey = dispatch.ledgerKey;
    receipt.state = "CLAIMED";
    receipt.createdAt = LocalDateTime.now(BUSINESS_ZONE);
    receipt.updatedAt = receipt.createdAt;
    return receipt;
  }

  /** 重放必须匹配全部不可变字段和有效状态，未知分支不获得新认领权。 */
  private void validate(PayTccReceiptPO stored, PayTccDispatchVO dispatch) {
    if (!samePayload(stored, dispatch)) {
      throw new BizException("PAY_TCC_RECEIPT_CONFLICT", "Pay receipt 载荷不一致", 409);
    }
    if (!hasValidStateAndBranch(stored)) throw conflict();
  }

  /** 本地资源证据的纯有效域门；CLAIMED可未知或已有正分支，不据此推断资金结果。 */
  static boolean hasValidStateAndBranch(PayTccReceiptPO receipt) {
    if (receipt == null || receipt.state == null) return false;
    if (receipt.branchId != null && receipt.branchId <= 0) return false;
    return switch (receipt.state) {
      case "CLAIMED" -> true;
      case "TRYING", "CONFIRMED", "CANCELLED" -> receipt.branchId != null;
      default -> false;
    };
  }

  /** 包内共享的原载荷纯比较；缺金额或格式错误只拒绝证据，状态/分支/时间不参与。 */
  static boolean samePayload(PayTccReceiptPO stored, PayTccDispatchVO expected) {
    if (stored == null || expected == null || stored.amount == null || expected.amount == null)
      return false;
    BigDecimal expectedAmount;
    try {
      expectedAmount = new BigDecimal(expected.amount);
    } catch (NumberFormatException malformedAmount) {
      return false;
    }
    return Objects.equals(stored.payNo, expected.payNo)
        && Objects.equals(stored.xid, expected.xid)
        && Objects.equals(stored.userId, expected.userId)
        && Objects.equals(stored.orderNo, expected.orderNo)
        && stored.amount.compareTo(expectedAmount) == 0
        && Objects.equals(stored.operationKey, expected.operationKey)
        && Objects.equals(stored.ledgerKey, expected.ledgerKey);
  }

  /** 锚点不完整或原身份冲突时不创建 receipt。 */
  private BizException conflict() {
    return new BizException("PAY_TCC_RECEIPT_CONFLICT", "Pay receipt 原事实不一致", 409);
  }
}
