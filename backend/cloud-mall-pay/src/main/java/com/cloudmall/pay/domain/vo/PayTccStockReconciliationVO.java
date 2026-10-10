package com.cloudmall.pay.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Pay/Stock只读观察报告；不证明原订单SKU、库存余额、归属、退款或全链终态。 */
public final class PayTccStockReconciliationVO {
  /** 固定采集范围。 */
  public final String scope = "PAY_STOCK_OBSERVED_ONLY";

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

  /** Stock证据核验摘要。 */
  public final PayTccStockEvidenceVO stockEvidence;

  /** 尚未发起查询的外部系统。 */
  public final List<String> externalSystemsNotQueried;

  /** 创建有界只读报告，不接收或输出原始资金查询锚点。 */
  public PayTccStockReconciliationVO(
      String classification,
      List<String> reasonCodes,
      PayTccReconciliationVO localEvidence,
      PayTccStockEvidenceVO stockEvidence) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.localEvidence = localEvidence;
    this.stockEvidence = stockEvidence;
    this.externalSystemsNotQueried =
        stockEvidence.queryAttempted()
            ? List.of("USER", "ORDER", "TC", "MQ")
            : List.of("USER", "ORDER", "STOCK", "TC", "MQ");
  }
}
