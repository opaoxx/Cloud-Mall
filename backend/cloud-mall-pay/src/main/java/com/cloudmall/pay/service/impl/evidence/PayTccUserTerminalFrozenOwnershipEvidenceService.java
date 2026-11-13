package com.cloudmall.pay.service.impl.evidence;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.pay.domain.dto.PayTccEvidenceAnchorDTO;
import com.cloudmall.pay.domain.dto.PayTccEvidenceCaptureDTO;
import com.cloudmall.pay.domain.dto.UserTerminalFrozenOwnershipEvidenceDTO;
import com.cloudmall.pay.domain.vo.PayTccUserTerminalFrozenOwnershipEvidenceVO;
import com.cloudmall.pay.domain.vo.PayTccUserTerminalFrozenOwnershipReconciliationVO;
import com.cloudmall.pay.feign.UserTerminalFrozenOwnershipEvidenceClient;
import com.fasterxml.jackson.databind.JsonNode;
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

/** 两次Pay只读快照夹住一次事务外User历史终态冻结归属 GET，任何结果均不授权资金动作。 */
@Service
public class PayTccUserTerminalFrozenOwnershipEvidenceService {
  /** 既有入口认证和上下文门。 */
  private final PayTccReconciliationService gate;

  /** 独立RR快照代理，不使用恢复Store。 */
  private final PayTccReconciliationReadService reader;

  /** 仅有GET的User历史终态冻结归属诊断客户端。 */
  private final UserTerminalFrozenOwnershipEvidenceClient users;

  /** 本切片独立默认关闭开关。 */
  private final boolean enabled;

  /** 仅内部GET使用的实例callback凭证。 */
  private final String callbackToken;

  /** 创建无事务采集编排器，不触发数据库或外部请求。 */
  public PayTccUserTerminalFrozenOwnershipEvidenceService(
      PayTccReconciliationService gate,
      PayTccReconciliationReadService reader,
      UserTerminalFrozenOwnershipEvidenceClient users,
      @Value("${cloudmall.pay.tcc-user-terminal-frozen-ownership-evidence.enabled:false}")
          boolean enabled,
      @Value("${cloudmall.internal.callback-token:cloudmall-local-callback}")
          String callbackToken) {
    this.gate = gate;
    this.reader = reader;
    this.users = users;
    this.enabled = enabled;
    this.callbackToken = callbackToken;
  }

  /** 校验后依次读取Pay、User历史终态冻结归属、Pay；不持有资金事务、不发起任何写操作。 */
  public PayTccUserTerminalFrozenOwnershipReconciliationVO inspect(
      String payNo, String caller, String token) {
    // 1. 两个开关和共享入口门均先于所有DB或Feign访问。
    if (!enabled)
      throw new BizException(
          "PAY_TCC_USER_TERMINAL_FROZEN_OWNERSHIP_EVIDENCE_DISABLED", "User历史终态冻结归属取证入口未启用", 503);
    gate.validateForRead(payNo, caller, token);
    PayTccEvidenceCaptureDTO before = capture(payNo);
    PayTccEvidenceAnchorDTO anchor = before.trustedAnchor();
    if (anchor == null) {
      List<String> reasons = new ArrayList<>(before.localReport().reasonCodes);
      reasons.add("LOCAL_ANCHOR_NOT_TRUSTED");
      return new PayTccUserTerminalFrozenOwnershipReconciliationVO(
          before.localReport().classification,
          reasons,
          before.localReport(),
          null,
          empty(false, "NOT_QUERIED"));
    }
    // 2. 第一段RR事务已经结束，原始查询参数只来自可信Pay锚点。
    gate.validateForRead(payNo, caller, token);
    ApiResponse<UserTerminalFrozenOwnershipEvidenceDTO> response = null;
    String failure = null;
    try {
      response = queryUserWithoutCallerContext(anchor);
    } catch (RuntimeException unavailable) {
      failure = "USER_TERMINAL_FROZEN_QUERY_UNAVAILABLE";
    }
    // 3. 无论User历史终态冻结归属成功或失败，都重新读取Pay；变更优先于远端基本事实。
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
          before, "USER_EVIDENCE_UNAVAILABLE", "USER_TERMINAL_FROZEN_RESPONSE_UNAVAILABLE");
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
  private ApiResponse<UserTerminalFrozenOwnershipEvidenceDTO> queryUserWithoutCallerContext(
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

  /** 先验原生范围与来源，再复用原完整T9门，最后验证终态计数和向量。 */
  private static PayTccUserTerminalFrozenOwnershipReconciliationVO verify(
      PayTccEvidenceCaptureDTO capture, UserTerminalFrozenOwnershipEvidenceDTO observed) {
    if (!"USER_TERMINAL_FROZEN_OWNERSHIP_OBSERVED_ONLY".equals(text(observed.scope))
        || !nativeBoolean(observed.automaticActionsAllowed, false)
        || !nativeBoolean(observed.otherDebitStatesNotInspected, true)
        || !nativeBoolean(observed.attemptOnlyCancelledNotInspected, true)
        || !nativeBoolean(observed.accountCausalityNotProven, true)
        || !nativeBoolean(observed.terminalFenceMayBeExpired, true)
        || integer(observed.candidateLimit) == null
        || integer(observed.candidateLimit) != 100)
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_TERMINAL_SCOPE_OR_FLAGS_INVALID");
    String classification = text(observed.classification);
    if ("LOCAL_CONFLICT".equals(classification))
      return report(capture, "USER_EVIDENCE_CONFLICT", "USER_TERMINAL_REPORTED_CONFLICT");
    if (!"BLOCKED_EXTERNAL_EVIDENCE".equals(classification))
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_TERMINAL_REPORTED_INCOMPLETE");
    if (observed.baseEvidence == null)
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_TERMINAL_BASE_MISSING");
    var base = PayTccUserAccountEvidenceService.verify(capture, observed.baseEvidence);
    if (!"MATCHED".equals(base.userAccountEvidence.status()))
      return new PayTccUserTerminalFrozenOwnershipReconciliationVO(
          base.classification,
          base.reasonCodes,
          capture.localReport(),
          null,
          empty(true, base.classification));
    Integer count = integer(observed.observedCount),
        confirmed = integer(observed.validatedConfirmedCount),
        cancelled = integer(observed.validatedCancelledCount);
    String vector = text(observed.vectorSha256);
    if (!nativeBoolean(observed.queryAttempted, true)
        || !nativeBoolean(observed.candidatesComplete, true)
        || !validCount(count)
        || !validCount(confirmed)
        || !validCount(cancelled)
        || vector == null
        || !vector.matches("[0-9a-f]{64}"))
      return report(capture, "USER_EVIDENCE_INCOMPLETE", "USER_TERMINAL_SUMMARY_NOT_PROVEN");
    if (confirmed + cancelled != count
        || count == 0 && !hash("").equals(vector)
        || "CONFIRMED".equals(base.userAccountEvidence.tccState()) && confirmed == 0)
      return report(capture, "USER_EVIDENCE_CONFLICT", "USER_TERMINAL_COUNTS_OR_VECTOR_CONFLICT");
    return new PayTccUserTerminalFrozenOwnershipReconciliationVO(
        "BLOCKED_REMAINING_EVIDENCE",
        List.of("ORDER_STOCK_TC_MQ_NOT_QUERIED"),
        capture.localReport(),
        base.userAccountEvidence,
        new PayTccUserTerminalFrozenOwnershipEvidenceVO(
            true, "MATCHED", true, true, count, confirmed, cancelled, vector));
  }

  /** 固定分类/原因，不输出未验证原User字段。 */
  private static PayTccUserTerminalFrozenOwnershipReconciliationVO report(
      PayTccEvidenceCaptureDTO capture, String classification, String reason) {
    return new PayTccUserTerminalFrozenOwnershipReconciliationVO(
        classification, List.of(reason), capture.localReport(), null, empty(true, classification));
  }

  /** 未验证计数与指纹保持null。 */
  private static PayTccUserTerminalFrozenOwnershipEvidenceVO empty(
      boolean attempted, String status) {
    return new PayTccUserTerminalFrozenOwnershipEvidenceVO(
        attempted, status, false, false, null, null, null, null);
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

  /** 候选及两种终态数都在当前100上限内。 */
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
