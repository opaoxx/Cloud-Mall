package com.cloudmall.pay.service.impl.evidence;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.dto.UserLinkedRefundEvidenceDTO;
import com.cloudmall.pay.domain.vo.PayTccUserLinkedRefundEvidenceVO;
import com.cloudmall.pay.domain.vo.PayTccUserLinkedRefundReconciliationVO;
import com.cloudmall.pay.feign.UserLinkedRefundEvidenceClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 两次Pay只读快照夹住一次事务外User原扣款关联退款集合 GET，任何结果均不授权资金动作。 */
@Service
public class PayTccUserLinkedRefundEvidenceService {
  /** 既有入口认证和上下文门。 */
  private final PayTccReconciliationService gate;

  /** 独立RR快照代理，不使用恢复Store。 */
  private final PayTccReconciliationReadService reader;

  /** 仅有GET的User原扣款关联退款集合诊断客户端。 */
  private final UserLinkedRefundEvidenceClient users;

  /** 本切片独立默认关闭开关。 */
  private final boolean enabled;

  /** 仅内部GET使用的实例callback凭证。 */
  private final String callbackToken;

  /** 创建无事务采集编排器，不触发数据库或外部请求。 */
  public PayTccUserLinkedRefundEvidenceService(
      PayTccReconciliationService gate,
      PayTccReconciliationReadService reader,
      UserLinkedRefundEvidenceClient users,
      @Value("${cloudmall.pay.tcc-user-linked-refund-evidence.enabled:false}") boolean enabled,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String callbackToken) {
    this.gate = gate;
    this.reader = reader;
    this.users = users;
    this.enabled = enabled;
    this.callbackToken = callbackToken;
  }

  /** 校验后依次读取Pay、User原扣款关联退款集合、Pay；不持有资金事务、不发起任何写操作。 */
  public PayTccUserLinkedRefundReconciliationVO inspect(String payNo, String caller, String token) {
    // 1. 两个开关和共享入口门均先于所有DB或Feign访问。
    if (!enabled)
      throw new BizException(
          "PAY_TCC_USER_LINKED_REFUND_EVIDENCE_DISABLED", "User原扣款关联退款集合取证入口未启用", 503);
    gate.validateForRead(payNo, caller, token);
    PayTccEvidenceCaptureDTO before = capture(payNo);
    PayTccEvidenceAnchorDTO anchor = before.trustedAnchor();
    if (anchor == null) {
      List<String> reasons = new ArrayList<>(before.localReport().reasonCodes);
      reasons.add("LOCAL_ANCHOR_NOT_TRUSTED");
      return new PayTccUserLinkedRefundReconciliationVO(
          before.localReport().classification,
          reasons,
          before.localReport(),
          null,
          empty(false, "NOT_QUERIED"));
    }
    // 2. 第一段RR事务已经结束，原始查询参数只来自可信Pay锚点。
    gate.validateForRead(payNo, caller, token);
    ApiResponse<UserLinkedRefundEvidenceDTO> response = null;
    String failure = null;
    try {
      response = queryUserWithoutCallerContext(anchor);
    } catch (RuntimeException unavailable) {
      failure = "USER_LINKED_REFUND_QUERY_UNAVAILABLE";
    }
    // 3. 无论User原扣款关联退款集合成功或失败，都重新读取Pay；变更优先于远端基本事实。
    PayTccEvidenceCaptureDTO after;
    try {
      after = capture(payNo);
    } catch (BizException identityLost) {
      if (identityLost.getHttpStatus() != 404 && identityLost.getHttpStatus() != 409)
        throw identityLost;
      return report(before, "LOCAL_EVIDENCE_CHANGED", "LOCAL_IDENTITY_CHANGED");
    }
    if (!sameCapture(before, after))
      return report(before, "LOCAL_EVIDENCE_CHANGED", "LOCAL_SNAPSHOT_CHANGED");
    if (failure != null) return report(before, "USER_EVIDENCE_UNAVAILABLE", failure);
    if (response == null || !"0".equals(response.code) || response.data == null)
      return report(before, "USER_EVIDENCE_UNAVAILABLE", "USER_LINKED_REFUND_RESPONSE_UNAVAILABLE");
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
  private ApiResponse<UserLinkedRefundEvidenceDTO> queryUserWithoutCallerContext(
      PayTccEvidenceAnchorDTO anchor) {
    Long originalUserId = AuthContext.userId();
    String originalRole = AuthContext.role();
    AuthContext.clear();
    try {
      return users.inspect(anchor.userId(), anchor.payNo(), "cm18-reconciliation", callbackToken);
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

  /** 先核原生范围/完整总额，再以明确完整集合上下文复用原账户门，不推断资金动作。 */
  static PayTccUserLinkedRefundReconciliationVO verify(
      PayTccEvidenceCaptureDTO capture, UserLinkedRefundEvidenceDTO observed) {
    // 1. 只接受既有User有界原关联范围，源缺证或冲突不抬高。
    if (!"USER_LINKED_REFUNDS_OBSERVED_ONLY".equals(text(observed.scope))
        || !nativeBoolean(observed.automaticActionsAllowed, false)
        || !nativeBoolean(observed.unlinkedLedgerRowsNotInspected, true)
        || integer(observed.candidateLimit) == null
        || integer(observed.candidateLimit) != 100)
      return report(
          capture, "USER_EVIDENCE_INCOMPLETE", "USER_LINKED_REFUND_SCOPE_OR_FLAGS_INVALID");
    String classification = text(observed.classification);
    if ("LOCAL_CONFLICT".equals(classification))
      return report(capture, "USER_EVIDENCE_CONFLICT", "USER_LINKED_REFUND_REPORTED_CONFLICT");
    if (!"BLOCKED_EXTERNAL_EVIDENCE".equals(classification))
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_LINKED_REFUND_REPORTED_INCOMPLETE");
    Integer count = integer(observed.observedCount);
    BigDecimal total = money(observed.validatedTotalAmount);
    String vector = text(observed.vectorSha256);
    if (!nativeBoolean(observed.queryAttempted, true)
        || !nativeBoolean(observed.candidatesComplete, true)
        || !validCount(count)
        || total == null
        || vector == null
        || !vector.matches("[0-9a-f]{64}"))
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_LINKED_REFUND_SUMMARY_NOT_PROVEN");
    // 2. 空集合只证明原关联范围；正数集合必须完整全额，partial不能伪称完整。
    BigDecimal originalAmount = new BigDecimal(capture.trustedAnchor().amountString());
    if (total.signum() < 0
        || total.compareTo(originalAmount) > 0
        || count == 0 && (total.signum() != 0 || !hash("").equals(vector))
        || count > 0 && (total.signum() <= 0 || total.compareTo(originalAmount) != 0))
      return report(
          capture, "USER_EVIDENCE_CONFLICT", "USER_LINKED_REFUND_TOTAL_OR_VECTOR_CONFLICT");
    boolean fullLinkedRefund = count > 0 && total.compareTo(originalAmount) == 0;
    if (observed.baseEvidence == null)
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_LINKED_REFUND_BASE_MISSING");
    var base =
        PayTccUserAccountEvidenceService.verifyLinkedRefundBase(
            capture, observed.baseEvidence, fullLinkedRefund);
    if (!"MATCHED".equals(base.userAccountEvidence.status()))
      return new PayTccUserLinkedRefundReconciliationVO(
          base.classification,
          base.reasonCodes,
          capture.localReport(),
          null,
          empty(true, base.classification));
    // 3. 原退款仅可来自已确认扣款，固定退款是关联集合子集；不宣告全账户结清。
    if (count > 0
            && (!"CONFIRMED".equals(base.userAccountEvidence.tccState())
                || !Boolean.TRUE.equals(base.userAccountEvidence.ledgerApplied()))
        || base.userAccountEvidence.fixedRefundAmount() != null
            && total.compareTo(new BigDecimal(base.userAccountEvidence.fixedRefundAmount())) < 0)
      return report(
          capture, "USER_EVIDENCE_CONFLICT", "USER_LINKED_REFUND_BASE_OR_SUBSET_CONFLICT");
    if ("REFUNDED".equals(capture.localReport().snapshot.paymentState()) && !fullLinkedRefund)
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_REFUNDED_LINKED_PROOF_MISSING");
    return new PayTccUserLinkedRefundReconciliationVO(
        "BLOCKED_REMAINING_EVIDENCE",
        List.of("ORDER_STOCK_TC_MQ_NOT_QUERIED"),
        capture.localReport(),
        base.userAccountEvidence,
        new PayTccUserLinkedRefundEvidenceVO(
            true, "MATCHED", true, true, count, total.toPlainString(), vector));
  }

  /** 仅原生DECIMAL(18,2)文本，不截断、浮点强转或去空白。 */
  private static BigDecimal money(JsonNode value) {
    String raw = text(value);
    if (raw == null || raw.length() > 20 || !raw.matches("-?(0|[1-9][0-9]*)([.][0-9]{1,2})?"))
      return null;
    BigDecimal amount = new BigDecimal(raw);
    return amount.precision() <= 18
            && amount.abs().compareTo(new BigDecimal("9999999999999999.99")) <= 0
        ? amount
        : null;
  }

  /** 固定分类/原因，不输出未验证原User字段。 */
  private static PayTccUserLinkedRefundReconciliationVO report(
      PayTccEvidenceCaptureDTO capture, String classification, String reason) {
    return new PayTccUserLinkedRefundReconciliationVO(
        classification, List.of(reason), capture.localReport(), null, empty(true, classification));
  }

  /** 未验证计数与指纹保持null。 */
  private static PayTccUserLinkedRefundEvidenceVO empty(boolean attempted, String status) {
    return new PayTccUserLinkedRefundEvidenceVO(attempted, status, false, false, null, null, null);
  }

  /** 必須原生boolean且为要求值，不强转缺省/文本/数字。 */
  private static boolean nativeBoolean(JsonNode value, boolean expected) {
    return value != null && value.isBoolean() && value.booleanValue() == expected;
  }

  /** 保留原生整数类型且不溢出。 */
  private static Integer integer(JsonNode value) {
    return value != null && value.isIntegralNumber() && value.canConvertToInt()
        ? value.intValue()
        : null;
  }

  /** 关联候选数在当前100上限内。 */
  private static boolean validCount(Integer count) {
    return count != null && count >= 0 && count <= 100;
  }

  /** 非原生文本不转换为状态或向量。 */
  private static String text(JsonNode value) {
    return value != null && value.isTextual() ? value.textValue() : null;
  }

  /** 空候选的规范指纹，不用于猜测历史原行。 */
  private static String hash(String value) {
    try {
      return HexFormat.of()
          .formatHex(
              MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8)));
    } catch (NoSuchAlgorithmException impossible) {
      throw new IllegalStateException("SHA-256 unavailable", impossible);
    }
  }
}
