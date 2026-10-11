package com.cloudmall.user.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 两种终态冻结候选的有界归属摘要，不代表完整历史或账户财务因果。 */
public final class UserTerminalFrozenOwnershipEvidenceVO {
  /** 固定的终态冻结行观察范围。 */
  public final String scope = "USER_TERMINAL_FROZEN_OWNERSHIP_OBSERVED_ONLY";

  /** 不允许自动资金动作。 */
  public final boolean automaticActionsAllowed = false;

  /** 候选之外的TRYING、未知或NULL状态未由本入口逐笔核验。 */
  public final boolean otherDebitStatesNotInspected = true;

  /** 仅attempt的空取消不在冻结行候选范围内。 */
  public final boolean attemptOnlyCancelledNotInspected = true;

  /** 历史归属不构成全账户因果证明。 */
  public final boolean accountCausalityNotProven = true;

  /** 缺失终态Fence保留原TTL语义，不宣称永久Fence证据。 */
  public final boolean terminalFenceMayBeExpired = true;

  /** 实际允许完整核验的候选上限。 */
  public final int candidateLimit = 100;

  /** 固定上海偏移的采集时刻。 */
  public final String collectedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();

  /** 本地冲突或阻塞分类。 */
  public final String classification;

  /** 固定原因码，不输出原异常或候选标识。 */
  public final List<String> reasonCodes;

  /** 同RR原基线，保留原账户/固定退款范围与缺口。 */
  public final UserTccReconciliationVO baseEvidence;

  /** 是否读取终态候选。 */
  public final boolean queryAttempted;

  /** 未查询为null；超限仅返回哨兵观察数。 */
  public final Integer observedCount;

  /** 两种终态CI候选是否未截断且全部核验，不代表全历史完整。 */
  public final boolean candidatesComplete;

  /** 完整核验的确认行数量，缺证/冲突时null。 */
  public final Integer validatedConfirmedCount;

  /** 完整核验的取消行数量，缺证/冲突时null。 */
  public final Integer validatedCancelledCount;

  /** 原归属、精确状态及金额快照的脱敏向量摘要。 */
  public final String vectorSha256;

  /** 未采集的外部系统证据。 */
  public final List<String> externalSystemsNotQueried =
      List.of("PAY", "ORDER", "STOCK", "TC", "MQ");

  /** 创建不可变报告，不输出历史金额合计或自动资金结论。 */
  public UserTerminalFrozenOwnershipEvidenceVO(
      String classification,
      List<String> reasonCodes,
      UserTccReconciliationVO baseEvidence,
      boolean queryAttempted,
      Integer observedCount,
      boolean candidatesComplete,
      Integer validatedConfirmedCount,
      Integer validatedCancelledCount,
      String vectorSha256) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.baseEvidence = baseEvidence;
    this.queryAttempted = queryAttempted;
    this.observedCount = observedCount;
    this.candidatesComplete = candidatesComplete;
    this.validatedConfirmedCount = validatedConfirmedCount;
    this.validatedCancelledCount = validatedCancelledCount;
    this.vectorSha256 = vectorSha256;
  }
}
