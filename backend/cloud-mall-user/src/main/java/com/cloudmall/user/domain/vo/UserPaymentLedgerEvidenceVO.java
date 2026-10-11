package com.cloudmall.user.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;

/** 同pay原账本有界观察；缺link不猜原扣款、不授权补链或补退。 */
public final class UserPaymentLedgerEvidenceVO {
  /** 固定同支付号原账本范围。 */
  public final String scope = "USER_PAYMENT_LEDGER_OBSERVED_ONLY";

  /** 不允许自动资金动作或原关联修复。 */
  public final boolean automaticActionsAllowed = false;

  /** 其它支付号范围原行未由本报告逐行检查。 */
  public final boolean otherPaymentRowsNotInspected = true;

  /** 当前行归属不构成全账户财务因果证明。 */
  public final boolean accountCausalityNotProven = true;

  /** 上限包含原DEBIT，不是最多100笔退款。 */
  public final int candidateLimit = 100;

  /** 固定上海偏移的采集时间。 */
  public final String collectedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();

  /** 原冲突或阻塞分类，不是资金终态。 */
  public final String classification;

  /** 固定诊断原因，不输出原key或错误文本。 */
  public final List<String> reasonCodes;

  /** 同RR原关联退款报告，保留其原范围和缺口。 */
  public final UserLinkedRefundEvidenceVO linkedEvidence;

  /** 是否读取新的同pay账本候选。 */
  public final boolean queryAttempted;

  /** 未查询为null；超限仅为哨兵观察数。 */
  public final Integer observedCount;

  /** 当前有界同pay候选是否全部核验，不代表全系统退款完备或结清。 */
  public final boolean candidatesComplete;

  /** 核验后的原DEBIT行数，缺证/冲突时null。 */
  public final Integer validatedDebitCount;

  /** 核验后的REFUND行数，缺证/冲突时null。 */
  public final Integer validatedRefundCount;

  /** 仅退款的已观察合计，不包含原扣款；partial仍不授权补退。 */
  public final String validatedRefundTotalAmount;

  /** 操作键指纹/类型/金额/delta快照向量，未完整核验为null。 */
  public final String vectorSha256;

  /** 未采集的外部系统证据。 */
  public final List<String> externalSystemsNotQueried =
      List.of("PAY", "ORDER", "STOCK", "TC", "MQ");

  /** 创建不可变报告，源缺证或超限时不输出部分资金摘要。 */
  public UserPaymentLedgerEvidenceVO(
      String classification,
      List<String> reasonCodes,
      UserLinkedRefundEvidenceVO linkedEvidence,
      boolean queryAttempted,
      Integer observedCount,
      boolean candidatesComplete,
      Integer validatedDebitCount,
      Integer validatedRefundCount,
      String validatedRefundTotalAmount,
      String vectorSha256) {
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.linkedEvidence = linkedEvidence;
    this.queryAttempted = queryAttempted;
    this.observedCount = observedCount;
    this.candidatesComplete = candidatesComplete;
    this.validatedDebitCount = validatedDebitCount;
    this.validatedRefundCount = validatedRefundCount;
    this.validatedRefundTotalAmount = validatedRefundTotalAmount;
    this.vectorSha256 = vectorSha256;
  }
}
