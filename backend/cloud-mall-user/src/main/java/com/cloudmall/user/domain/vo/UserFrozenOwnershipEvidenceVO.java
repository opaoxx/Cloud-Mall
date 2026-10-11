package com.cloudmall.user.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 当前TRYING冻结归属摘要，不证明历史终态或全账户财务因果。 */
public final class UserFrozenOwnershipEvidenceVO {
  /** 固定的当前TRYING冻结归属观察范围。 */
  public final String scope = "USER_TRYING_FROZEN_OWNERSHIP_OBSERVED_ONLY";

  /** 本工具不允许自动释放冻结、重新Try或其他资金动作。 */
  public final boolean automaticActionsAllowed = false;

  /** 历史CONFIRMED/CANCELLED冻结行未核验。 */
  public final boolean terminalFrozenRowsNotInspected = true;

  /** 实际可完整核验的候选上限，不包含额外哨兵。 */
  public final int candidateLimit = 100;

  /** 上海偏移的报告采集时间。 */
  public final String collectedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();

  /** 本地冲突或阻塞分类，不是资金终态。 */
  public final String classification;

  /** 固定诊断原因，不回显原行/异常/凭证。 */
  public final List<String> reasonCodes;

  /** 同RR的原账户/冻结/固定键报告，不修改其观察结论。 */
  public final UserTccReconciliationVO baseEvidence;

  /** 是否查询当前用户的活跃TRYING冻结候选。 */
  public final boolean queryAttempted;

  /** 本次候选数量，未查询时null，超限时仅为哨兵观察数。 */
  public final Integer observedCount;

  /** 当前TRYING范围候选是否未截断且全部校验，不代表财务结清。 */
  public final boolean candidatesComplete;

  /** 已完整核验候选的总额，源缺证/坏行/超限时null。 */
  public final String validatedFrozenTotal;

  /** 按其他支付号指纹升序的归属/金额/冻结增量向量SHA256，未完整核验时null。 */
  public final String vectorSha256;

  /** 尚未读取的外部证据系统。 */
  public final List<String> externalSystemsNotQueried =
      List.of("PAY", "ORDER", "STOCK", "TC", "MQ");

  /** 创建不可变、有界、无原资金标识的报告。 */
  public UserFrozenOwnershipEvidenceVO(
      String classification,
      List<String> reasonCodes,
      UserTccReconciliationVO baseEvidence,
      boolean queryAttempted,
      Integer observedCount,
      boolean candidatesComplete,
      String validatedFrozenTotal,
      String vectorSha256) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.baseEvidence = baseEvidence;
    this.queryAttempted = queryAttempted;
    this.observedCount = observedCount;
    this.candidatesComplete = candidatesComplete;
    this.validatedFrozenTotal = validatedFrozenTotal;
    this.vectorSha256 = vectorSha256;
  }
}
