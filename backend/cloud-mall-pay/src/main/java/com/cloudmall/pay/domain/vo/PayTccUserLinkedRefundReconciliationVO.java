package com.cloudmall.pay.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Pay消费原扣款关联退款集合观察，不声明跨库原子或资金终态。 */
public final class PayTccUserLinkedRefundReconciliationVO {
  /** 固定本切片观察范围。 */
  public final String scope = "PAY_USER_LINKED_REFUNDS_OBSERVED_ONLY";

  /** 任何分类均禁止自动资金动作。 */
  public final boolean automaticActionsAllowed = false;

  /** 未关联或错关联账本行不在本切片观察范围。 */
  public final boolean unlinkedLedgerRowsNotInspected = true;

  /** 不证明全账户财务因果。 */
  public final boolean accountCausalityNotProven = true;

  /** 上海偏移的采集时刻。 */
  public final String collectedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();

  /** 冲突或固定阻塞分类。 */
  public final String classification;

  /** 本地固定原因码，不回显User原reason/error。 */
  public final List<String> reasonCodes;

  /** 第一段Pay只读事实，不代表统一跨库快照。 */
  public final PayTccReconciliationVO localEvidence;

  /** 完整验证全部条件后才提供原账户摘要，其它分类null。 */
  public final PayTccUserAccountEvidenceVO userAccountEvidence;

  /** 固定采集状态及已核关联退款观察。 */
  public final PayTccUserLinkedRefundEvidenceVO linkedRefundEvidence;

  /** 尚未尝试读取的外部系统。 */
  public final List<String> externalSystemsNotQueried;

  /** 冻结已核摘要，不保存原User响应或凭证。 */
  public PayTccUserLinkedRefundReconciliationVO(
      String classification,
      List<String> reasonCodes,
      PayTccReconciliationVO localEvidence,
      PayTccUserAccountEvidenceVO userAccountEvidence,
      PayTccUserLinkedRefundEvidenceVO linkedRefundEvidence) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.localEvidence = localEvidence;
    this.userAccountEvidence = userAccountEvidence;
    this.linkedRefundEvidence = linkedRefundEvidence;
    this.externalSystemsNotQueried =
        linkedRefundEvidence.queryAttempted()
            ? List.of("ORDER", "STOCK", "TC", "MQ")
            : List.of("USER", "ORDER", "STOCK", "TC", "MQ");
  }
}
