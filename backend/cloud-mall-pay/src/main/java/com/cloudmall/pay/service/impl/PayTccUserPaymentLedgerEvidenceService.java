package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.dto.UserPaymentLedgerEvidenceDTO;
import com.cloudmall.pay.domain.vo.PayTccUserPaymentLedgerEvidenceVO;
import com.cloudmall.pay.domain.vo.PayTccUserPaymentLedgerReconciliationVO;
import com.cloudmall.pay.feign.UserPaymentLedgerEvidenceClient;
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

/** 两次Pay只读快照夹住一次事务外User同支付号账本 GET，任何结果均不授权资金动作。 */
@Service
public class PayTccUserPaymentLedgerEvidenceService {
  /** 既有入口认证和上下文门。 */
  private final PayTccReconciliationService gate;

  /** 独立RR快照代理，不使用恢复Store。 */
  private final PayTccReconciliationReadService reader;

  /** 仅有GET的User同支付号账本诊断客户端。 */
  private final UserPaymentLedgerEvidenceClient users;

  /** 本切片独立默认关闭开关。 */
  private final boolean enabled;

  /** 仅内部GET使用的实例callback凭证。 */
  private final String callbackToken;

  /** 创建无事务采集编排器，不触发数据库或外部请求。 */
  public PayTccUserPaymentLedgerEvidenceService(
      PayTccReconciliationService gate,
      PayTccReconciliationReadService reader,
      UserPaymentLedgerEvidenceClient users,
      @Value("${cloudmall.pay.tcc-user-payment-ledger-evidence.enabled:false}") boolean enabled,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String callbackToken) {
    this.gate = gate;
    this.reader = reader;
    this.users = users;
    this.enabled = enabled;
    this.callbackToken = callbackToken;
  }

  /** 校验后依次读取Pay、User同支付号账本、Pay；不持有资金事务、不发起任何写操作。 */
  public PayTccUserPaymentLedgerReconciliationVO inspect(
      String payNo, String caller, String token) {
    // 1. 两个开关和共享入口门均先于所有DB或Feign访问。
    if (!enabled)
      throw new BizException(
          "PAY_TCC_USER_PAYMENT_LEDGER_EVIDENCE_DISABLED", "User同支付号账本取证入口未启用", 503);
    gate.validateForRead(payNo, caller, token);
    PayTccEvidenceCaptureDTO before = capture(payNo);
    PayTccEvidenceAnchorDTO anchor = before.trustedAnchor();
    if (anchor == null) {
      List<String> reasons = new ArrayList<>(before.localReport().reasonCodes);
      reasons.add("LOCAL_ANCHOR_NOT_TRUSTED");
      return new PayTccUserPaymentLedgerReconciliationVO(
          before.localReport().classification,
          reasons,
          before.localReport(),
          null,
          null,
          empty(false, "NOT_QUERIED"));
    }
    // 2. 第一段RR事务已经结束，原始查询参数只来自可信Pay锚点。
    gate.validateForRead(payNo, caller, token);
    ApiResponse<UserPaymentLedgerEvidenceDTO> response = null;
    String failure = null;
    try {
      response = queryUserWithoutCallerContext(anchor);
    } catch (RuntimeException unavailable) {
      failure = "USER_PAYMENT_LEDGER_QUERY_UNAVAILABLE";
    }
    // 3. 无论User同支付号账本成功或失败，都重新读取Pay；变更优先于远端基本事实。
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
      return report(
          before, "USER_EVIDENCE_UNAVAILABLE", "USER_PAYMENT_LEDGER_RESPONSE_UNAVAILABLE");
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
  private ApiResponse<UserPaymentLedgerEvidenceDTO> queryUserWithoutCallerContext(
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

  /** 核原生范围与来源、完整关联报告，再交叉核同pay原DEBIT和退款摘要，不推断动作。 */
  private static PayTccUserPaymentLedgerReconciliationVO verify(
      PayTccEvidenceCaptureDTO capture, UserPaymentLedgerEvidenceDTO observed) {
    // 1. 只接受原User同pay范围，源缺证/partial/未知origin或冲突不抬高。
    if (!"USER_PAYMENT_LEDGER_OBSERVED_ONLY".equals(text(observed.scope))
        || !nativeBoolean(observed.automaticActionsAllowed, false)
        || !nativeBoolean(observed.otherPaymentRowsNotInspected, true)
        || !nativeBoolean(observed.accountCausalityNotProven, true)
        || integer(observed.candidateLimit) == null
        || integer(observed.candidateLimit) != 100)
      return report(
          capture, "USER_EVIDENCE_INCOMPLETE", "USER_PAYMENT_LEDGER_SCOPE_OR_FLAGS_INVALID");
    String classification = text(observed.classification);
    if ("LOCAL_CONFLICT".equals(classification))
      return report(capture, "USER_EVIDENCE_CONFLICT", "USER_PAYMENT_LEDGER_REPORTED_CONFLICT");
    if (!"BLOCKED_EXTERNAL_EVIDENCE".equals(classification))
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_PAYMENT_LEDGER_REPORTED_INCOMPLETE");
    if (observed.linkedEvidence == null)
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_PAYMENT_LEDGER_LINKED_MISSING");
    // 2. 原关联范围必须整体MATCHED，保持T28原账户/固定退款与严格partial上下文门。
    var linked = PayTccUserLinkedRefundEvidenceService.verify(capture, observed.linkedEvidence);
    if (!"MATCHED".equals(linked.linkedRefundEvidence.status()))
      return new PayTccUserPaymentLedgerReconciliationVO(
          linked.classification,
          linked.reasonCodes,
          capture.localReport(),
          null,
          null,
          empty(true, linked.classification));
    Integer observedCount = integer(observed.observedCount);
    Integer debitCount = integer(observed.validatedDebitCount);
    Integer refundCount = integer(observed.validatedRefundCount);
    BigDecimal refundTotal = money(observed.validatedRefundTotalAmount);
    String vector = text(observed.vectorSha256);
    if (!nativeBoolean(observed.queryAttempted, true)
        || !nativeBoolean(observed.candidatesComplete, true)
        || !validCount(observedCount)
        || !validCount(debitCount)
        || !validCount(refundCount)
        || refundTotal == null
        || vector == null
        || !vector.matches("[0-9a-f]{64}"))
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_PAYMENT_LEDGER_SUMMARY_NOT_PROVEN");
    // 3. 同pay集合包含原扣款，退款数/金额必须与已核原关联范围一致；不证明其它pay或全账户因果。
    boolean confirmed = "CONFIRMED".equals(linked.userAccountEvidence.tccState());
    if (refundTotal.signum() < 0
        || debitCount + refundCount != observedCount
        || !Objects.equals(refundCount, linked.linkedRefundEvidence.observedCount())
        || refundTotal.compareTo(new BigDecimal(linked.linkedRefundEvidence.validatedTotalAmount()))
            != 0
        || confirmed
            && (debitCount != 1 || !Boolean.TRUE.equals(linked.userAccountEvidence.ledgerApplied()))
        || !confirmed
            && (debitCount != 0
                || refundCount != 0
                || observedCount != 0
                || refundTotal.signum() != 0
                || !Boolean.FALSE.equals(linked.userAccountEvidence.ledgerApplied()))
        || observedCount == 0 && !hash("").equals(vector))
      return report(
          capture, "USER_EVIDENCE_CONFLICT", "USER_PAYMENT_LEDGER_COUNTS_TOTAL_OR_STATE_CONFLICT");
    return new PayTccUserPaymentLedgerReconciliationVO(
        "BLOCKED_REMAINING_EVIDENCE",
        List.of("ORDER_STOCK_TC_MQ_NOT_QUERIED"),
        capture.localReport(),
        linked.userAccountEvidence,
        linked.linkedRefundEvidence,
        new PayTccUserPaymentLedgerEvidenceVO(
            true,
            "MATCHED",
            true,
            true,
            observedCount,
            debitCount,
            refundCount,
            refundTotal.toPlainString(),
            vector));
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
  private static PayTccUserPaymentLedgerReconciliationVO report(
      PayTccEvidenceCaptureDTO capture, String classification, String reason) {
    return new PayTccUserPaymentLedgerReconciliationVO(
        classification,
        List.of(reason),
        capture.localReport(),
        null,
        null,
        empty(true, classification));
  }

  /** 未验证计数与指纹保持null。 */
  private static PayTccUserPaymentLedgerEvidenceVO empty(boolean attempted, String status) {
    return new PayTccUserPaymentLedgerEvidenceVO(
        attempted, status, false, false, null, null, null, null, null);
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

  /** 同pay候选及两类型计数在当前100上限内。 */
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
