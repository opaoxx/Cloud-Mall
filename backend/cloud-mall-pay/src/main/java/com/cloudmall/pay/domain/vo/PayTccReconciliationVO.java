package com.cloudmall.pay.domain.vo;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Pay本地只读取证报告；任何分类均不授权资金动作。 */
public final class PayTccReconciliationVO {
  /** 本切片的固定证据范围。 */
  public final String scope = "PAY_LOCAL_ONLY";

  /** 本工具永远不授权自动动作。 */
  public final boolean automaticActionsAllowed = false;

  /** 明确尚未查询的外部系统。 */
  public final List<String> externalSystemsNotQueried =
      List.of("USER", "ORDER", "STOCK", "TC", "MQ");

  /** 报告采集时间，固定上海偏移。 */
  public final String collectedAt;

  /** 本地阻塞或冲突分类，不是资金终态。 */
  public final String classification;

  /** 固定诊断码，不包含原始错误或凭证。 */
  public final List<String> reasonCodes;

  /** 不可变本地快照摘要。 */
  public final PayTccReconciliationSnapshotVO snapshot;

  /** 原标识的SHA256指纹，缺失值为null。 */
  public final Map<String, String> identifiers;

  /** 创建不可变报告并标注采集时间，保留标识缺失的null语义。 */
  public PayTccReconciliationVO(
      String classification,
      List<String> reasonCodes,
      PayTccReconciliationSnapshotVO snapshot,
      Map<String, String> identifiers) {
    this.collectedAt = OffsetDateTime.now(ZoneOffset.ofHours(8)).toString();
    this.classification = classification;
    this.reasonCodes = List.copyOf(reasonCodes);
    this.snapshot = snapshot;
    this.identifiers = Collections.unmodifiableMap(new LinkedHashMap<>(identifiers));
  }
}
