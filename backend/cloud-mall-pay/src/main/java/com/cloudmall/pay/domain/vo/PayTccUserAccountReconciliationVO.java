package com.cloudmall.pay.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Pay与User有限只读观察组合，不声明全退款、统一快照或资金终态。 */
public final class PayTccUserAccountReconciliationVO {
  /** 本切片固定观察范围。 */
  public final String scope = "PAY_USER_ACCOUNT_OBSERVED_ONLY";

  /** 任何结果都不允许自动资金动作。 */
  public final boolean automaticActionsAllowed = false;

  /** 固定键之外的退款未查询。 */
  public final boolean otherRefundsNotInspected = true;

  /** 上海偏移的组合报告时间。 */
  public final String collectedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();

  /** 固定阻塞分类。 */
  public final String classification;

  /** 固定原因码，不回显User的原reason或错误。 */
  public final List<String> reasonCodes;

  /** 第一段Pay本地快照，非统一跨库事务。 */
  public final PayTccReconciliationVO localEvidence;

  /** User字段核验后的有限摘要。 */
  public final PayTccUserAccountEvidenceVO userAccountEvidence;

  /** 尚未尝试读取的外部系统。 */
  public final List<String> externalSystemsNotQueried;

  /** 冻结组合摘要，无原资金参数或原User响应对象。 */
  public PayTccUserAccountReconciliationVO(
      String classification,
      List<String> reasonCodes,
      PayTccReconciliationVO localEvidence,
      PayTccUserAccountEvidenceVO userAccountEvidence) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.localEvidence = localEvidence;
    this.userAccountEvidence = userAccountEvidence;
    this.externalSystemsNotQueried =
        userAccountEvidence.queryAttempted()
            ? List.of("ORDER", "STOCK", "TC", "MQ")
            : List.of("USER", "ORDER", "STOCK", "TC", "MQ");
  }
}
