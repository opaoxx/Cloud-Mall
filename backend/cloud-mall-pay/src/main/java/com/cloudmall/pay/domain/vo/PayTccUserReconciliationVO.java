package com.cloudmall.pay.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Pay/User只读证据报告；仍缺其他参与方事实，永不授权资金动作。 */
public final class PayTccUserReconciliationVO {
  /** 固定采集范围。 */
  public final String scope = "PAY_USER_READ_ONLY";

  /** 本报告永远不允许自动处置。 */
  public final boolean automaticActionsAllowed = false;

  /** 本次组合采集时间。 */
  public final String collectedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();

  /** 固定阻塞分类。 */
  public final String classification;

  /** 固定诊断码，不含原错误。 */
  public final List<String> reasonCodes;

  /** 第一次Pay本地快照。 */
  public final PayTccReconciliationVO localEvidence;

  /** User证据核验摘要。 */
  public final PayTccUserEvidenceVO userEvidence;

  /** 尚未发起查询的外部系统。 */
  public final List<String> externalSystemsNotQueried;

  /** 创建有界只读报告，不接收或输出原始资金查询锚点。 */
  public PayTccUserReconciliationVO(
      String classification,
      List<String> reasonCodes,
      PayTccReconciliationVO localEvidence,
      PayTccUserEvidenceVO userEvidence) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.localEvidence = localEvidence;
    this.userEvidence = userEvidence;
    this.externalSystemsNotQueried =
        userEvidence.queryAttempted()
            ? List.of("ORDER", "STOCK", "TC", "MQ")
            : List.of("USER", "ORDER", "STOCK", "TC", "MQ");
  }
}
