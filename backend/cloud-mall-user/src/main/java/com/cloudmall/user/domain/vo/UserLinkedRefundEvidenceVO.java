package com.cloudmall.user.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 原扣款键关联退款候选的只读摘要，不代表全系统退款或资金终态。 */
public final class UserLinkedRefundEvidenceVO {
  /** 固定的原账本关联候选观察范围。 */
  public final String scope = "USER_LINKED_REFUNDS_OBSERVED_ONLY";

  /** 本工具不允许自动退款、补退或其他资金动作。 */
  public final boolean automaticActionsAllowed = false;

  /** 错link/未关联到原账本的其他行仍未查询。 */
  public final boolean unlinkedLedgerRowsNotInspected = true;

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

  /** 是否查询原账本关联候选。 */
  public final boolean queryAttempted;

  /** 本次候选数量，未查询时null，超限时仅为哨兵观察数。 */
  public final Integer observedCount;

  /** 当前关联范围候选是否未截断且全部校验，不代表财务结清。 */
  public final boolean candidatesComplete;

  /** 已完整核验候选的总额，源缺证/坏行/超限时null。 */
  public final String validatedTotalAmount;

  /** 按操作键指纹升序的金额/余额向量SHA256，未完整核验时null。 */
  public final String vectorSha256;

  /** 尚未读取的外部证据系统。 */
  public final List<String> externalSystemsNotQueried =
      List.of("PAY", "ORDER", "STOCK", "TC", "MQ");

  /** 创建不可变、有界、无原资金标识的报告。 */
  public UserLinkedRefundEvidenceVO(
      String classification,
      List<String> reasonCodes,
      UserTccReconciliationVO baseEvidence,
      boolean queryAttempted,
      Integer observedCount,
      boolean candidatesComplete,
      String validatedTotalAmount,
      String vectorSha256) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.baseEvidence = baseEvidence;
    this.queryAttempted = queryAttempted;
    this.observedCount = observedCount;
    this.candidatesComplete = candidatesComplete;
    this.validatedTotalAmount = validatedTotalAmount;
    this.vectorSha256 = vectorSha256;
  }
}
