package com.cloudmall.user.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 本地User账户/冻结/固定退款键观察，所有结果均不授权资金动作。 */
public final class UserTccReconciliationVO {
  /** 本批固定的有限观察范围。 */
  public final String scope = "USER_ACCOUNT_FROZEN_REFUND_OBSERVED_ONLY";

  /** 无资金自动动作许可。 */
  public final boolean automaticActionsAllowed = false;

  /** 未查询的外部证据来源。 */
  public final List<String> externalSystemsNotQueried =
      List.of("PAY", "ORDER", "STOCK", "TC", "MQ");

  /** 固定键之外的退款仍未查询。 */
  public final boolean otherRefundsNotInspected = true;

  /** 上海偏移的报告生成时间。 */
  public final String collectedAt;

  /** 精确核验后的原支付号。 */
  public final String payNo;

  /** 精确核验后的资金用户。 */
  public final Long userId;

  /** 原订单号。 */
  public final String orderNo;

  /** 原金额字符串。 */
  public final String amount;

  /** 原状态校验器验证的本地资金状态，非全链资金终态。 */
  public final String tccState;

  /** 原校验器是否观察到匹配的APPLIED扣款账本。 */
  public final boolean ledgerApplied;

  /** 固定本地冲突或阻塞分类。 */
  public final String classification;

  /** 不含原始错误的固定原因码。 */
  public final List<String> reasonCodes;

  /** 账户资金部分投影与冻结观察。 */
  public final UserAccountEvidenceVO account;

  /** 固定退款键观察。 */
  public final UserRefundEvidenceVO refund;

  /** 原资金标识的SHA256指纹，缺失保留null。 */
  public final Map<String, String> identifiers;

  /** 冻结观察报告和集合，不携带原XID、分支或操作键。 */
  public UserTccReconciliationVO(
      UserTccStateVO state,
      String classification,
      List<String> reasonCodes,
      UserAccountEvidenceVO account,
      UserRefundEvidenceVO refund,
      Map<String, String> identifiers) {
    this.collectedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();
    this.payNo = state.payNo;
    this.userId = state.userId;
    this.orderNo = state.orderNo;
    this.amount = state.amount;
    this.tccState = state.state;
    this.ledgerApplied = state.ledgerApplied;
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.account = account;
    this.refund = refund;
    this.identifiers = Collections.unmodifiableMap(new LinkedHashMap<>(identifiers));
  }
}
