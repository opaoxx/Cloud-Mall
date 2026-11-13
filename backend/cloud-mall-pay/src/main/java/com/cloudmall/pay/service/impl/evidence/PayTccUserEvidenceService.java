package com.cloudmall.pay.service.impl.evidence;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.vo.PayTccUserEvidenceVO;
import com.cloudmall.pay.domain.vo.PayTccUserReconciliationVO;
import com.cloudmall.pay.domain.vo.UserTccStateVO;
import com.cloudmall.pay.feign.UserTccClient;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 两次Pay只读快照夹住一次事务外User GET，任何结果均不授权资金动作。 */
@Service
public class PayTccUserEvidenceService {
  /** 既有入口认证和上下文门。 */
  private final PayTccReconciliationService gate;

  /** 独立RR快照代理，不使用恢复Store。 */
  private final PayTccReconciliationReadService reader;

  /** 只调用state GET的既有客户端。 */
  private final UserTccClient user;

  /** 本切片独立默认关闭开关。 */
  private final boolean enabled;

  /** 仅用于User内部认证的实例凭证。 */
  private final String callbackToken;

  /** User状态白名单，不回显未识别值。 */
  private static final Set<String> USER_STATES =
      Set.of("UNKNOWN", "TRYING", "CONFIRMED", "CANCELLED");

  /** 创建无事务采集编排器，不触发数据库或外部请求。 */
  public PayTccUserEvidenceService(
      PayTccReconciliationService gate,
      PayTccReconciliationReadService reader,
      UserTccClient user,
      @Value("${cloudmall.pay.tcc-user-evidence.enabled:false}") boolean enabled,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String callbackToken) {
    this.gate = gate;
    this.reader = reader;
    this.user = user;
    this.enabled = enabled;
    this.callbackToken = callbackToken;
  }

  /** 校验后依次读取Pay、User、Pay；不持有资金事务、不发起任何写操作。 */
  public PayTccUserReconciliationVO inspect(String payNo, String caller, String token) {
    // 1. 两个开关和共享入口门均先于所有DB或Feign访问。
    if (!enabled) throw new BizException("PAY_TCC_USER_EVIDENCE_DISABLED", "User取证入口未启用", 503);
    gate.validateForRead(payNo, caller, token);
    PayTccEvidenceCaptureDTO before = capture(payNo);
    PayTccEvidenceAnchorDTO anchor = before.trustedAnchor();
    if (anchor == null) {
      List<String> reasons = new ArrayList<>(before.localReport().reasonCodes);
      reasons.add("LOCAL_ANCHOR_NOT_TRUSTED");
      return new PayTccUserReconciliationVO(
          before.localReport().classification,
          reasons,
          before.localReport(),
          summary(false, "NOT_QUERIED", null, false, null));
    }
    // 2. 第一段RR事务已经结束，原始查询参数只来自可信Pay锚点。
    gate.validateForRead(payNo, caller, token);
    ApiResponse<UserTccStateVO> response = null;
    String failure = null;
    try {
      response = queryUserWithoutCallerContext(anchor);
    } catch (RuntimeException unavailable) {
      failure =
          unavailable instanceof BizException business
                  && "PAY_TCC_DISPATCH_CONFLICT".equals(business.getCode())
                  && business.getHttpStatus() == 409
              ? "USER_PERSISTED_FACT_CONFLICT"
              : "USER_QUERY_UNAVAILABLE";
    }
    // 3. 无论User成功或失败，都重新读取Pay；变更优先于远端结论。
    PayTccEvidenceCaptureDTO after;
    try {
      after = capture(payNo);
    } catch (BizException identityLost) {
      if (identityLost.getHttpStatus() != 404 && identityLost.getHttpStatus() != 409)
        throw identityLost;
      return report(before, "LOCAL_EVIDENCE_CHANGED", "LOCAL_IDENTITY_CHANGED", null);
    }
    if (!sameCapture(before, after))
      return report(before, "LOCAL_EVIDENCE_CHANGED", "LOCAL_SNAPSHOT_CHANGED", null);
    if (failure != null)
      return report(
          before,
          "USER_PERSISTED_FACT_CONFLICT".equals(failure)
              ? "USER_EVIDENCE_CONFLICT"
              : "USER_EVIDENCE_UNAVAILABLE",
          failure,
          null);
    if (response == null || !"0".equals(response.code))
      return report(before, "USER_EVIDENCE_UNAVAILABLE", "USER_RESPONSE_UNAVAILABLE", null);
    return verify(before, response.data);
  }

  /** 数据库不可用与缺行区别对待，不输出底层错误文本。 */
  private PayTccEvidenceCaptureDTO capture(String payNo) {
    try {
      return reader.capture(payNo);
    } catch (DataAccessException unavailable) {
      throw new BizException("PAY_TCC_RECONCILIATION_UNAVAILABLE", "本地资金证据暂不可用", 503);
    }
  }

  /** 仅在此GET窗口隔离请求用户上下文，防止全局Feign拦截器追加身份；finally完整恢复。 */
  private ApiResponse<UserTccStateVO> queryUserWithoutCallerContext(
      PayTccEvidenceAnchorDTO anchor) {
    Long originalUserId = AuthContext.userId();
    String originalRole = AuthContext.role();
    AuthContext.clear();
    try {
      return user.state(
          anchor.userId(),
          anchor.operationKey(),
          anchor.userId(),
          "cloud-mall-pay",
          callbackToken,
          anchor.xid());
    } finally {
      AuthContext.clear();
      if (originalUserId != null || originalRole != null)
        AuthContext.set(originalUserId, originalRole);
    }
  }

  /** 忽略采集时刻；比较全部已冻结的本地摘要及原始锚点。 */
  private static boolean sameCapture(
      PayTccEvidenceCaptureDTO before, PayTccEvidenceCaptureDTO after) {
    return Objects.equals(before.trustedAnchor(), after.trustedAnchor())
        && Objects.equals(before.localReport().snapshot, after.localReport().snapshot)
        && Objects.equals(before.localReport().identifiers, after.localReport().identifiers)
        && Objects.equals(before.localReport().classification, after.localReport().classification)
        && Objects.equals(before.localReport().reasonCodes, after.localReport().reasonCodes);
  }

  /** 核对原身份、数值金额、原分支和账本语义；缺证不推断无扣款。 */
  private static PayTccUserReconciliationVO verify(
      PayTccEvidenceCaptureDTO capture, UserTccStateVO observed) {
    if (observed == null)
      return report(capture, "USER_EVIDENCE_UNAVAILABLE", "USER_BODY_MISSING", null);
    PayTccEvidenceAnchorDTO anchor = capture.trustedAnchor();
    if (observed.userId == null
        || observed.payNo == null
        || observed.orderNo == null
        || observed.amount == null
        || observed.operationKey == null
        || observed.ledgerKey == null
        || observed.xid == null
        || observed.state == null)
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_FIELDS_MISSING", observed);
    BigDecimal amount;
    try {
      amount = new BigDecimal(observed.amount);
    } catch (NumberFormatException malformedAmount) {
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_AMOUNT_INVALID", observed);
    }
    if (!Objects.equals(anchor.userId(), observed.userId)
        || !Objects.equals(anchor.payNo(), observed.payNo)
        || !Objects.equals(anchor.orderNo(), observed.orderNo)
        || !Objects.equals(anchor.operationKey(), observed.operationKey)
        || !Objects.equals(anchor.ledgerKey(), observed.ledgerKey)
        || !Objects.equals(anchor.xid(), observed.xid)
        || new BigDecimal(anchor.amountString()).compareTo(amount) != 0)
      return report(capture, "USER_EVIDENCE_CONFLICT", "USER_TUPLE_CONFLICT", observed);
    if (!USER_STATES.contains(observed.state)
        || "UNKNOWN".equals(observed.state)
        || observed.branchId == null
        || observed.branchId <= 0
        || !observed.ledgerAppliedPresent)
      return report(
          capture, "USER_EVIDENCE_INCOMPLETE", "USER_PROTOCOL_EVIDENCE_MISSING", observed);
    if (!Objects.equals(anchor.userBranchId(), observed.branchId)
        || Objects.equals(anchor.payBranchId(), observed.branchId))
      return report(capture, "USER_EVIDENCE_CONFLICT", "USER_BRANCH_CONFLICT", observed);
    if ("CONFIRMED".equals(observed.state) != observed.ledgerApplied)
      return report(capture, "USER_EVIDENCE_CONFLICT", "USER_LEDGER_STATE_CONFLICT", observed);
    String receiptState = capture.localReport().snapshot.receipt().state();
    String dispatchState = capture.localReport().snapshot.dispatch().state();
    if (opposite(receiptState, observed.state)
        || opposite(dispatchState, observed.state)
        || "COMMITTED".equals(anchor.executionState()) && "CANCELLED".equals(observed.state)
        || "ROLLEDBACK".equals(anchor.executionState()) && "CONFIRMED".equals(observed.state))
      return report(capture, "USER_EVIDENCE_CONFLICT", "USER_TERMINAL_CONFLICT", observed);
    if ("TRYING".equals(observed.state)
        && Set.of("COMMITTED", "ROLLEDBACK").contains(anchor.executionState()))
      return report(
          capture, "USER_EVIDENCE_INCOMPLETE", "USER_TERMINAL_EVIDENCE_PENDING", observed);
    return new PayTccUserReconciliationVO(
        "BLOCKED_REMAINING_EVIDENCE",
        List.of("ORDER_STOCK_TC_MQ_NOT_QUERIED"),
        capture.localReport(),
        summary(true, "MATCHED", observed, true, observed.ledgerApplied));
  }

  /** 只有已明确相反的终态构成矛盾，处理中状态仍需其余参与方取证。 */
  private static boolean opposite(String local, String external) {
    return "CONFIRMED".equals(local) && "CANCELLED".equals(external)
        || "CANCELLED".equals(local) && "CONFIRMED".equals(external);
  }

  /** 阻塞摘要不携带未核实的账本布尔值。 */
  private static PayTccUserReconciliationVO report(
      PayTccEvidenceCaptureDTO capture,
      String classification,
      String reason,
      UserTccStateVO observed) {
    return new PayTccUserReconciliationVO(
        classification,
        List.of(reason),
        capture.localReport(),
        summary(true, classification, observed, false, null));
  }

  /** 只输出白名单状态和单向指纹，不暴露原标识或异常。 */
  private static PayTccUserEvidenceVO summary(
      boolean attempted, String status, UserTccStateVO observed, boolean matched, Boolean applied) {
    return new PayTccUserEvidenceVO(
        attempted,
        status,
        observed != null && USER_STATES.contains(Objects.toString(observed.state, ""))
            ? observed.state
            : null,
        matched,
        applied,
        hash(observed == null ? null : observed.xid),
        hash(observed == null || observed.branchId == null ? null : observed.branchId.toString()));
  }

  /** 指纹仅用于比较，缺值保持null。 */
  private static String hash(String value) {
    if (value == null) return null;
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
