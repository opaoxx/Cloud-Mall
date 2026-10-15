package com.cloudmall.pay.service.impl;

import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import com.cloudmall.pay.domain.po.PayTccFencePO;
import com.cloudmall.pay.domain.po.PayTccReceiptPO;
import com.cloudmall.pay.domain.vo.PayTccDispatchVO;
import com.cloudmall.pay.mapper.PayTccFenceMapper;
import com.cloudmall.pay.mapper.PayTccReceiptMapper;
import com.cloudmall.pay.service.PayTccReceiptLocalResource;
import java.math.BigDecimal;
import java.util.Objects;
import java.util.Set;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

/** Pay 同一快照与已校验 User 镜像共同确定扣款切片结果。 */
@Service
public class PayTccExecutionEvidence {
  /** Pay 永久分支归属与本地状态。 */
  private final PayTccReceiptMapper receipts;

  /** Pay 官方 Fence 的最新持久证据。 */
  private final PayTccFenceMapper fences;

  /** 创建无外部副作用的事实核对器。 */
  public PayTccExecutionEvidence(PayTccReceiptMapper receipts, PayTccFenceMapper fences) {
    this.receipts = receipts;
    this.fences = fences;
  }

  /** 缺失一端只能未知；TC 响应和网络超时不参与本方法的资金结论。 */
  @Transactional(readOnly = true, isolation = Isolation.REPEATABLE_READ)
  public String classify(PayTccExecutionPO execution, PayTccDispatchVO user) {
    require(execution != null && execution.amount != null);
    // 1. User 镜像必须完整匹配本次 execution，不可只看状态字串。
    if (user != null)
      require(
          Objects.equals(user.payNo, execution.payNo)
              && Objects.equals(user.xid, execution.xid)
              && Objects.equals(user.userId, execution.userId)
              && Objects.equals(user.orderNo, execution.orderNo)
              && Objects.equals(user.operationKey, execution.operationKey)
              && Objects.equals(user.ledgerKey, execution.ledgerKey)
              && matchesUserAmount(user.amount, execution.amount));
    PayTccReceiptPO pay = receipts.selectByPayNo(execution.payNo);
    if (pay == null) return "UNKNOWN";
    require(
        Objects.equals(pay.payNo, execution.payNo)
            && Objects.equals(pay.xid, execution.xid)
            && Objects.equals(pay.userId, execution.userId)
            && Objects.equals(pay.orderNo, execution.orderNo)
            && Objects.equals(pay.operationKey, execution.operationKey)
            && Objects.equals(pay.ledgerKey, execution.ledgerKey)
            && pay.amount != null
            && pay.amount.compareTo(execution.amount) == 0);
    // 2. 先核状态/分支；合法CLAIMED仍UNKNOWN，不推断分支登记或资金结果。
    require(PayTccReceiptStore.hasValidStateAndBranch(pay));
    if ("CLAIMED".equals(pay.state)) return "UNKNOWN";
    PayTccFencePO fence = fences.findByBranch(pay.xid, pay.branchId);
    if (fence == null) require(!"TRYING".equals(pay.state));
    else
      require(
          fence.status != null
              && fence.xid != null
              && Objects.equals(fence.xid, pay.xid)
              && Objects.equals(fence.branchId, pay.branchId)
              && PayTccReceiptLocalResource.ACTION_NAME.equals(fence.actionName)
              && ("TRYING".equals(pay.state)
                  ? Integer.valueOf(1).equals(fence.status)
                  : "CONFIRMED".equals(pay.state)
                      ? Integer.valueOf(2).equals(fence.status)
                      : Set.of(3, 4).contains(fence.status)));
    if (user == null
        || user.state == null
        || !Set.of("TRY_ACCEPTED", "CONFIRMED", "CANCELLED").contains(user.state)) return "UNKNOWN";
    require(
        user.userBranchId != null
            && user.userBranchId > 0
            && !pay.branchId.equals(user.userBranchId));
    // 3. 只在两端永久终态一致时完成；相反终态需要人工核验。
    if ("CONFIRMED".equals(pay.state) && "CONFIRMED".equals(user.state)) return "COMMITTED";
    if ("CANCELLED".equals(pay.state) && "CANCELLED".equals(user.state)) return "ROLLEDBACK";
    if (Set.of("CONFIRMED", "CANCELLED").contains(pay.state)
        && Set.of("CONFIRMED", "CANCELLED").contains(user.state)) return "CONFLICT";
    if ("TRYING".equals(pay.state) && "TRY_ACCEPTED".equals(user.state)) return "TRYING";
    return "UNKNOWN";
  }

  /** 缺失或不可解析的金额文本是载荷冲突；合法数字保持原BigDecimal数值等价，不归一化。 */
  private boolean matchesUserAmount(String amount, BigDecimal expectedAmount) {
    if (amount == null) return false;
    try {
      return new BigDecimal(amount).compareTo(expectedAmount) == 0;
    } catch (NumberFormatException malformedAmount) {
      return false;
    }
  }

  /** 字段矛盾不能被解释成普通缺失或自动改绑。 */
  private void require(boolean matches) {
    if (!matches)
      throw new BizException("PAY_TCC_EXECUTION_CONFLICT", "TCC 两端载荷与 execution 不一致", 409);
  }
}
