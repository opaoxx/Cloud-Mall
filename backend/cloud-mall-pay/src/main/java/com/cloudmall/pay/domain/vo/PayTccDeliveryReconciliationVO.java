package com.cloudmall.pay.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 同一本地RR快照的八来源报告，所有分类均禁止自动资金动作。 */
public final class PayTccDeliveryReconciliationVO {
  /** 固定本地交付与取消观察范围。 */
  public final String scope = "PAY_LOCAL_DELIVERY_CANCEL_ONLY";

  /** 本工具不授权写入、退款或恢复。 */
  public final boolean automaticActionsAllowed = false;

  /** 尚未读取的外部证据来源。 */
  public final List<String> externalSystemsNotQueried =
      List.of("USER", "ORDER", "STOCK", "TC", "MQ");

  /** 上海偏移的报告生成时间。 */
  public final String collectedAt;

  /** 本地冲突或受控阻塞分类，不是资金终态。 */
  public final String classification;

  /** 不包含原标识或错误的固定诊断码。 */
  public final List<String> reasonCodes;

  /** 同一RR快照中的原六来源报告。 */
  public final PayTccReconciliationVO localEvidence;

  /** 交付与退款台账摘要。 */
  public final PayTccTaskEvidenceVO delivery;

  /** 扣款前取消台账摘要。 */
  public final PayTccTaskEvidenceVO cancel;

  /** 冻结报告与诊断码，不消除原报告缺证。 */
  public PayTccDeliveryReconciliationVO(
      String classification,
      List<String> reasonCodes,
      PayTccReconciliationVO localEvidence,
      PayTccTaskEvidenceVO delivery,
      PayTccTaskEvidenceVO cancel) {
    this.collectedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.localEvidence = localEvidence;
    this.delivery = delivery;
    this.cancel = cancel;
  }
}
