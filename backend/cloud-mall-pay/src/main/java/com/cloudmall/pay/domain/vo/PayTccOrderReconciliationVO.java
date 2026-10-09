package com.cloudmall.pay.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Pay/Order基本事实报告；不证明订单库存收尾、退款或完整Order快照。 */
public final class PayTccOrderReconciliationVO {
  /** 固定采集范围。 */
  public final String scope = "PAY_ORDER_BASIC_ONLY";

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

  /** Order证据核验摘要。 */
  public final PayTccOrderEvidenceVO orderEvidence;

  /** 尚未发起查询的外部系统。 */
  public final List<String> externalSystemsNotQueried;

  /** 创建有界只读报告，不接收或输出原始资金查询锚点。 */
  public PayTccOrderReconciliationVO(
      String classification,
      List<String> reasonCodes,
      PayTccReconciliationVO localEvidence,
      PayTccOrderEvidenceVO orderEvidence) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.localEvidence = localEvidence;
    this.orderEvidence = orderEvidence;
    this.externalSystemsNotQueried =
        orderEvidence.queryAttempted()
            ? List.of("USER", "STOCK", "TC", "MQ")
            : List.of("USER", "ORDER", "STOCK", "TC", "MQ");
  }
}
