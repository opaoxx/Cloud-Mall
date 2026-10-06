package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccDispatchDTO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import io.seata.rm.tcc.api.BusinessActionContext;
import java.util.Objects;

/** Pay receipt Fence 事务中的纯业务状态回调。 */
public class PayTccReceiptBusinessCallback {
  /** Fence 外层事务中的 receipt 持久状态。 */
  private final PayTccReceiptStore receipts;

  /** 创建 receipt 业务回调。 */
  public PayTccReceiptBusinessCallback(PayTccReceiptStore receipts) {
    this.receipts = receipts;
  }

  /** 只提交 Pay receipt Try，禁止在 Fence 本地事务中调用 User。 */
  public boolean tryBusiness(BusinessActionContext context) {
    // 1. 核对 Seata 原载荷与永久认领。
    PayTccDispatchDTO request = payload(context);
    PayTccReceiptPO receipt = receipts.lock(request.payNo);
    validateReceipt(receipt, request, context);
    if (receipt == null || "CONFIRMED".equals(receipt.state) || "CANCELLED".equals(receipt.state))
      throw new BizException("PAY_TCC_RECEIPT_CONFLICT", "Pay receipt 不允许迟到 Try", 409);
    if ("CLAIMED".equals(receipt.state)) {
      // 2. 原子绑定首个真实 branch，迟到 Try 不复活终态。
      if (receipts.transitionClaimed(request, context.getXid(), context.getBranchId(), "TRYING")
          != 1) throw new IllegalStateException("Pay receipt Try CAS 未成功");
    } else if (!"TRYING".equals(receipt.state)) throw new IllegalStateException("无效 receipt 状态");
    return true;
  }

  /** CAS TRYING→CONFIRMED，不产生 User 资金副作用。 */
  public boolean confirmBusiness(BusinessActionContext context) {
    // 1. 锁定并核对原分支。
    PayTccDispatchDTO request = payload(context);
    PayTccReceiptPO receipt = receipts.lock(request.payNo);
    validateReceipt(receipt, request, context);
    if (receipt == null) return false;
    if ("CONFIRMED".equals(receipt.state)) return true;
    if (!"TRYING".equals(receipt.state)) return false;
    // 2. 只有已受理 receipt 可以确认；重复确认幂等。
    return receipts.transition(
            request, context.getXid(), context.getBranchId(), "TRYING", "CONFIRMED")
        == 1;
  }

  /** 将 CLAIMED/TRYING 置为 CANCELLED，不释放 User 余额。 */
  public boolean cancelBusiness(BusinessActionContext context) {
    // 1. 缺失归属不得凭空创建支付事实。
    PayTccDispatchDTO request = payload(context);
    PayTccReceiptPO receipt = receipts.lock(request.payNo);
    validateReceipt(receipt, request, context);
    if (receipt == null) return false;
    if ("CANCELLED".equals(receipt.state)) return true;
    if ("CONFIRMED".equals(receipt.state)) return false;
    if ("CLAIMED".equals(receipt.state)) {
      // 2. 空 Cancel 也绑定原分支并留下永久取消记录。
      return receipts.transitionClaimed(
              request, context.getXid(), context.getBranchId(), "CANCELLED")
          == 1;
    }
    return "TRYING".equals(receipt.state)
        && receipts.transition(
                request, context.getXid(), context.getBranchId(), "TRYING", "CANCELLED")
            == 1;
  }

  /** 从 Seata action context 构造不可变 Pay 载荷。 */
  public PayTccDispatchDTO payload(BusinessActionContext context) {
    if (context == null
        || context.getXid() == null
        || context.getXid().isBlank()
        || context.getBranchId() <= 0
        || !PayTccReceiptLocalResource.ACTION_NAME.equals(context.getActionName())) {
      throw new BizException("PAY_TCC_RECEIPT_CONFLICT", "Pay receipt 分支上下文无效", 409);
    }
    PayTccDispatchDTO source = context.getActionContext("request", PayTccDispatchDTO.class);
    if (source == null
        || source.payNo == null
        || !source.payNo.matches("[A-Za-z0-9_-]{1,64}")
        || source.userId == null
        || source.userId <= 0
        || source.orderNo == null
        || source.orderNo.isBlank()
        || source.amount == null
        || source.amount.signum() <= 0
        || source.amount.stripTrailingZeros().scale() > 2)
      throw new BizException("PAY_TCC_RECEIPT_CONFLICT", "Pay receipt 缺少原始载荷", 409);
    return source;
  }

  /** 核对 receipt 与当前 Seata 分支的全部不可变字段。 */
  private void validateReceipt(
      PayTccReceiptPO receipt, PayTccDispatchDTO request, BusinessActionContext context) {
    if (receipt == null) return;
    if (!Objects.equals(receipt.payNo, request.payNo)
        || !Objects.equals(receipt.userId, request.userId)
        || !Objects.equals(receipt.orderNo, request.orderNo)
        || receipt.amount.compareTo(request.amount) != 0
        || !Objects.equals(receipt.xid, context.getXid())
        || !Objects.equals(receipt.operationKey, "tcc-debit:" + request.payNo)
        || !Objects.equals(receipt.ledgerKey, request.payNo)
        || (receipt.branchId == null && !"CLAIMED".equals(receipt.state))
        || (receipt.branchId != null && !Objects.equals(receipt.branchId, context.getBranchId()))) {
      throw new BizException("PAY_TCC_RECEIPT_CONFLICT", "Pay receipt 载荷不一致", 409);
    }
  }
}
