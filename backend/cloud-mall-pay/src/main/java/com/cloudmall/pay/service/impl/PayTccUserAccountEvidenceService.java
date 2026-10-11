package com.cloudmall.pay.service.impl;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.dto.UserAccountEvidenceDTO;
import com.cloudmall.pay.domain.vo.PayTccUserAccountEvidenceVO;
import com.cloudmall.pay.domain.vo.PayTccUserAccountReconciliationVO;
import com.cloudmall.pay.feign.UserAccountEvidenceClient;
import com.fasterxml.jackson.databind.JsonNode;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.dao.DataAccessException;
import org.springframework.stereotype.Service;

/** 两次Pay只读快照夹住一次事务外User账户 GET，任何结果均不授权资金动作。 */
@Service
public class PayTccUserAccountEvidenceService {
  /** 既有入口认证和上下文门。 */
  private final PayTccReconciliationService gate;

  /** 独立RR快照代理，不使用恢复Store。 */
  private final PayTccReconciliationReadService reader;

  /** 仅有GET的User账户诊断客户端。 */
  private final UserAccountEvidenceClient users;

  /** 本切片独立默认关闭开关。 */
  private final boolean enabled;

  /** 仅内部GET使用的实例callback凭证。 */
  private final String callbackToken;

  /** User资金状态白名单，不回显未知文本。 */
  private static final Set<String> USER_STATES =
      Set.of("TRYING", "CONFIRMED", "CANCELLED", "UNKNOWN");

  /** 固定退款摘要必须存在的字段，缺行时每项显式null。 */
  private static final List<String> REFUND_FIELDS =
      List.of(
          "state",
          "amount",
          "balanceBefore",
          "balanceAfter",
          "identityMatched",
          "operationKeySha256",
          "originalOperationKeySha256");

  /** 创建无事务采集编排器，不触发数据库或外部请求。 */
  public PayTccUserAccountEvidenceService(
      PayTccReconciliationService gate,
      PayTccReconciliationReadService reader,
      UserAccountEvidenceClient users,
      @Value("${cloudmall.pay.tcc-user-account-evidence.enabled:false}") boolean enabled,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String callbackToken) {
    this.gate = gate;
    this.reader = reader;
    this.users = users;
    this.enabled = enabled;
    this.callbackToken = callbackToken;
  }

  /** 校验后依次读取Pay、User账户、Pay；不持有资金事务、不发起任何写操作。 */
  public PayTccUserAccountReconciliationVO inspect(String payNo, String caller, String token) {
    // 1. 两个开关和共享入口门均先于所有DB或Feign访问。
    if (!enabled)
      throw new BizException("PAY_TCC_USER_ACCOUNT_EVIDENCE_DISABLED", "User账户取证入口未启用", 503);
    gate.validateForRead(payNo, caller, token);
    PayTccEvidenceCaptureDTO before = capture(payNo);
    PayTccEvidenceAnchorDTO anchor = before.trustedAnchor();
    if (anchor == null) {
      List<String> reasons = new ArrayList<>(before.localReport().reasonCodes);
      reasons.add("LOCAL_ANCHOR_NOT_TRUSTED");
      return new PayTccUserAccountReconciliationVO(
          before.localReport().classification,
          reasons,
          before.localReport(),
          empty(false, "NOT_QUERIED", null));
    }
    // 2. 第一段RR事务已经结束，原始查询参数只来自可信Pay锚点。
    gate.validateForRead(payNo, caller, token);
    ApiResponse<UserAccountEvidenceDTO> response = null;
    String failure = null;
    try {
      response = queryUserWithoutCallerContext(anchor);
    } catch (RuntimeException unavailable) {
      failure = "USER_ACCOUNT_QUERY_UNAVAILABLE";
    }
    // 3. 无论User账户成功或失败，都重新读取Pay；变更优先于远端基本事实。
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
    if (failure != null) return report(before, "USER_EVIDENCE_UNAVAILABLE", failure, null);
    if (response == null || !"0".equals(response.code) || response.data == null)
      return report(before, "USER_EVIDENCE_UNAVAILABLE", "USER_ACCOUNT_RESPONSE_UNAVAILABLE", null);
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
  private ApiResponse<UserAccountEvidenceDTO> queryUserWithoutCallerContext(
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

  /** 先验证原身份与指纹，再核源分类和完整账户/固定退款摘要，不猜资金动作。 */
  private static PayTccUserAccountReconciliationVO verify(
      PayTccEvidenceCaptureDTO capture, UserAccountEvidenceDTO observed) {
    var anchor = capture.trustedAnchor();
    if (!"USER_ACCOUNT_FROZEN_REFUND_OBSERVED_ONLY".equals(text(observed.scope))
        || !isBoolean(observed.automaticActionsAllowed, false)
        || !isBoolean(observed.otherRefundsNotInspected, true))
      return incomplete(capture, "USER_REPORT_SCOPE_OR_FLAGS_INVALID", observed);
    String sourceClassification = text(observed.classification);
    if ("LOCAL_CONFLICT".equals(sourceClassification))
      return conflict(capture, "USER_REPORTED_LOCAL_CONFLICT", observed);
    if ("BLOCKED_LOCAL_EVIDENCE".equals(sourceClassification))
      return incomplete(capture, "USER_REPORTED_INCOMPLETE", observed);
    if (!"BLOCKED_EXTERNAL_EVIDENCE".equals(sourceClassification))
      return incomplete(capture, "USER_CLASSIFICATION_INVALID", observed);
    if (observed.userId == null
        || !observed.userId.isIntegralNumber()
        || !observed.userId.canConvertToLong()
        || observed.userId.longValue() <= 0
        || text(observed.payNo) == null
        || text(observed.orderNo) == null)
      return incomplete(capture, "USER_IDENTITY_FIELDS_MISSING", observed);
    BigDecimal originalAmount = money(observed.amount, false);
    if (originalAmount == null) return incomplete(capture, "USER_AMOUNT_FIELD_INVALID", observed);
    if (!Objects.equals(anchor.userId(), observed.userId.longValue())
        || !anchor.payNo().equals(text(observed.payNo))
        || !anchor.orderNo().equals(text(observed.orderNo))
        || originalAmount.signum() <= 0
        || originalAmount.compareTo(new BigDecimal(anchor.amountString())) != 0)
      return conflict(capture, "USER_ORIGINAL_IDENTITY_CONFLICT", observed);
    if (observed.identifiers == null || !observed.identifiers.isObject())
      return incomplete(capture, "USER_IDENTIFIERS_MISSING", observed);
    var expected =
        Map.of(
            "xidSha256",
            hash(anchor.xid()),
            "branchIdSha256",
            hash(anchor.userBranchId().toString()),
            "operationKeySha256",
            hash(anchor.operationKey()),
            "ledgerKeySha256",
            hash(anchor.ledgerKey()));
    for (var entry : expected.entrySet()) {
      String actual = text(observed.identifiers.get(entry.getKey()));
      if (actual == null || !actual.matches("[0-9a-f]{64}"))
        return incomplete(capture, "USER_IDENTIFIER_FIELD_INVALID", observed);
      if (!entry.getValue().equals(actual))
        return conflict(capture, "USER_ORIGINAL_IDENTIFIER_CONFLICT", observed);
    }
    String state = text(observed.tccState);
    if (state == null
        || !USER_STATES.contains(state)
        || "UNKNOWN".equals(state)
        || bool(observed.ledgerApplied) == null)
      return incomplete(capture, "USER_TCC_STATE_NOT_PROVEN", observed);
    if ("CONFIRMED".equals(state) != observed.ledgerApplied.booleanValue())
      return conflict(capture, "USER_DEBIT_PROOF_CONFLICT", observed);
    if ("COMMITTED".equals(anchor.executionState()) && "CANCELLED".equals(state)
        || "ROLLEDBACK".equals(anchor.executionState()) && "CONFIRMED".equals(state))
      return conflict(capture, "USER_OPPOSITE_TERMINAL_STATE", observed);
    if (Set.of("COMMITTED", "ROLLEDBACK").contains(anchor.executionState())
        && "TRYING".equals(state))
      return incomplete(capture, "USER_TERMINAL_EVIDENCE_PENDING", observed);
    JsonNode account = observed.account;
    if (account == null || !account.isObject() || !isBoolean(account.get("present"), true))
      return incomplete(capture, "USER_ACCOUNT_NOT_PROVEN", observed);
    String accountStatus = text(account.get("status"));
    if (accountStatus == null || !Set.of("ACTIVE", "DISABLED").contains(accountStatus))
      return incomplete(capture, "USER_ACCOUNT_STATUS_INVALID", observed);
    BigDecimal balance = money(account.get("balance"), false),
        frozen = money(account.get("frozenBalance"), false),
        total = money(account.get("tryingFrozenTotal"), true);
    Boolean projection = bool(account.get("frozenProjectionMatched"));
    if (balance == null || frozen == null || total == null || projection == null)
      return incomplete(capture, "USER_ACCOUNT_FIELDS_MISSING", observed);
    if (balance.signum() < 0
        || frozen.signum() < 0
        || total.signum() < 0
        || !projection
        || frozen.compareTo(total) != 0)
      return conflict(capture, "USER_FROZEN_PROJECTION_CONFLICT", observed);
    JsonNode refund = observed.refund;
    Boolean present = refund == null || !refund.isObject() ? null : bool(refund.get("present"));
    if (present == null) return incomplete(capture, "USER_REFUND_PRESENCE_MISSING", observed);
    BigDecimal refundAmount = null;
    if (!present) {
      for (String field : REFUND_FIELDS)
        if (!refund.has(field) || !refund.get(field).isNull())
          return incomplete(capture, "USER_ABSENT_REFUND_FIELDS_INVALID", observed);
      if ("REFUNDED".equals(capture.localReport().snapshot.paymentState()))
        return incomplete(capture, "USER_REFUNDED_FIXED_PROOF_MISSING", observed);
    } else {
      if (text(refund.get("state")) == null || bool(refund.get("identityMatched")) == null)
        return incomplete(capture, "USER_REFUND_FIELDS_MISSING", observed);
      if (!"APPLIED".equals(text(refund.get("state")))
          || !refund.get("identityMatched").booleanValue())
        return conflict(capture, "USER_REFUND_PROOF_CONFLICT", observed);
      refundAmount = money(refund.get("amount"), false);
      BigDecimal beforeBalance = money(refund.get("balanceBefore"), false),
          afterBalance = money(refund.get("balanceAfter"), false);
      if (refundAmount == null || beforeBalance == null || afterBalance == null)
        return incomplete(capture, "USER_REFUND_MONEY_MISSING", observed);
      if (refundAmount.signum() <= 0
          || refundAmount.compareTo(originalAmount) > 0
          || beforeBalance.signum() < 0
          || afterBalance.signum() < 0
          || afterBalance.subtract(beforeBalance).compareTo(refundAmount) != 0)
        return conflict(capture, "USER_REFUND_MONEY_CONFLICT", observed);
      for (var entry :
          Map.of(
                  "operationKeySha256",
                  hash("refund:" + anchor.payNo()),
                  "originalOperationKeySha256",
                  hash(anchor.ledgerKey()))
              .entrySet()) {
        String actual = text(refund.get(entry.getKey()));
        if (actual == null || !actual.matches("[0-9a-f]{64}"))
          return incomplete(capture, "USER_REFUND_IDENTIFIER_MISSING", observed);
        if (!entry.getValue().equals(actual))
          return conflict(capture, "USER_REFUND_IDENTIFIER_CONFLICT", observed);
      }
      if (!"CONFIRMED".equals(state))
        return conflict(capture, "USER_REFUND_TCC_STATE_CONFLICT", observed);
      if (refundAmount.compareTo(originalAmount) < 0)
        return incomplete(capture, "REFUND_PARTIAL_OBSERVED", observed);
    }
    return new PayTccUserAccountReconciliationVO(
        "BLOCKED_REMAINING_EVIDENCE",
        List.of("ORDER_STOCK_TC_MQ_NOT_QUERIED"),
        capture.localReport(),
        new PayTccUserAccountEvidenceVO(
            true,
            "MATCHED",
            state,
            true,
            observed.ledgerApplied.booleanValue(),
            accountStatus,
            balance.toPlainString(),
            frozen.toPlainString(),
            total.toPlainString(),
            present,
            refundAmount == null ? null : refundAmount.toPlainString()));
  }

  /** 缺证不输出未验证的资金字符串或原User响应。 */
  private static PayTccUserAccountReconciliationVO incomplete(
      PayTccEvidenceCaptureDTO capture, String reason, UserAccountEvidenceDTO observed) {
    return report(capture, "USER_EVIDENCE_INCOMPLETE", reason, observed);
  }

  /** 明确事实或源报告冲突也永远不授权资金处置。 */
  private static PayTccUserAccountReconciliationVO conflict(
      PayTccEvidenceCaptureDTO capture, String reason, UserAccountEvidenceDTO observed) {
    return report(capture, "USER_EVIDENCE_CONFLICT", reason, observed);
  }

  /** 固定分类与白名单状态，所有未验证财务字段保持null。 */
  private static PayTccUserAccountReconciliationVO report(
      PayTccEvidenceCaptureDTO capture,
      String classification,
      String reason,
      UserAccountEvidenceDTO observed) {
    return new PayTccUserAccountReconciliationVO(
        classification,
        List.of(reason),
        capture.localReport(),
        empty(true, classification, observed));
  }

  /** 不保留原JSON对象，不回显未知状态或异常。 */
  private static PayTccUserAccountEvidenceVO empty(
      boolean attempted, String status, UserAccountEvidenceDTO observed) {
    String state = observed == null ? null : text(observed.tccState);
    return new PayTccUserAccountEvidenceVO(
        attempted,
        status,
        state != null && USER_STATES.contains(state) ? state : null,
        false,
        null,
        null,
        null,
        null,
        null,
        null,
        null);
  }

  /** 只有原生boolean才有存在性证据，不强转0/1或文本。 */
  private static Boolean bool(JsonNode value) {
    return value != null && value.isBoolean() ? value.booleanValue() : null;
  }

  /** 必须显式声明期望的原生boolean值，缺字段不等于false。 */
  private static boolean isBoolean(JsonNode value, boolean expected) {
    return value != null && value.isBoolean() && value.booleanValue() == expected;
  }

  /** 原生金额文本按最多65 digits/2scale或账户18.2解析，不trim/截断/浮点强转。 */
  private static BigDecimal money(JsonNode value, boolean sum) {
    String raw = text(value);
    if (raw == null
        || raw.length() > (sum ? 67 : 20)
        || !raw.matches("-?(0|[1-9][0-9]*)([.][0-9]{1,2})?")) return null;
    BigDecimal amount = new BigDecimal(raw);
    return amount.precision() <= (sum ? 65 : 18)
            && (sum || amount.abs().compareTo(new BigDecimal("9999999999999999.99")) <= 0)
        ? amount
        : null;
  }

  /** 非原生文本不改变token类型。 */
  private static String text(JsonNode token) {
    return token != null && token.isTextual() ? token.textValue() : null;
  }

  /** 原标识只用于本地比较指纹，不进入响应。 */
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
