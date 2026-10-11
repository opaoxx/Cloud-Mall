package com.cloudmall.pay.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** Pay消费历史终态冻结归属观察，不声明跨库原子或资金终态。 */
public final class PayTccUserTerminalFrozenOwnershipReconciliationVO {
  /** 固定本切片观察范围。 */
  public final String scope = "PAY_USER_TERMINAL_FROZEN_OWNERSHIP_OBSERVED_ONLY";

  /** 任何分类均禁止自动资金动作。 */
  public final boolean automaticActionsAllowed = false;

  /** 两种终态之外的状态未检查。 */
  public final boolean otherDebitStatesNotInspected = true;

  /** 仅attempt的空取消未作为冻结候选检查。 */
  public final boolean attemptOnlyCancelledNotInspected = true;

  /** 不证明全账户财务因果。 */
  public final boolean accountCausalityNotProven = true;

  /** 终态Fence缺失沿原TTL语义，不强造永久证明。 */
  public final boolean terminalFenceMayBeExpired = true;

  /** 固定退款键之外的退款仍未由本切片检查。 */
  public final boolean otherRefundsNotInspected = true;

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

  /** 固定采集状态及已核当前冻结观察。 */
  public final PayTccUserTerminalFrozenOwnershipEvidenceVO userTerminalFrozenOwnershipEvidence;

  /** 尚未尝试读取的外部系统。 */
  public final List<String> externalSystemsNotQueried;

  /** 冻结已核摘要，不保存原User响应或凭证。 */
  public PayTccUserTerminalFrozenOwnershipReconciliationVO(
      String classification,
      List<String> reasonCodes,
      PayTccReconciliationVO localEvidence,
      PayTccUserAccountEvidenceVO userAccountEvidence,
      PayTccUserTerminalFrozenOwnershipEvidenceVO userTerminalFrozenOwnershipEvidence) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.localEvidence = localEvidence;
    this.userAccountEvidence = userAccountEvidence;
    this.userTerminalFrozenOwnershipEvidence = userTerminalFrozenOwnershipEvidence;
    this.externalSystemsNotQueried =
        userTerminalFrozenOwnershipEvidence.queryAttempted()
            ? List.of("ORDER", "STOCK", "TC", "MQ")
            : List.of("USER", "ORDER", "STOCK", "TC", "MQ");
  }
}
