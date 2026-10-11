package com.cloudmall.pay.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** User16T25原生JSON载体，不补默认值、不作为响应直接输出。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserPaymentLedgerEvidenceDTO {
  /** 原同支付号账本观察范围文本。 */
  public JsonNode scope;

  /** 显式禁止动作原生boolean。 */
  public JsonNode automaticActionsAllowed;

  /** 其它支付号原行未检查原生boolean。 */
  public JsonNode otherPaymentRowsNotInspected;

  /** 全账户因果未证明原生boolean。 */
  public JsonNode accountCausalityNotProven;

  /** 固定原生整数上限，包含原DEBIT。 */
  public JsonNode candidateLimit;

  /** 原User固定分类文本。 */
  public JsonNode classification;

  /** 是否查询候选原生boolean。 */
  public JsonNode queryAttempted;

  /** 当前有界候选是否完整原生boolean。 */
  public JsonNode candidatesComplete;

  /** 原生整数同pay候选数量。 */
  public JsonNode observedCount;

  /** 原生整数已核DEBIT数量。 */
  public JsonNode validatedDebitCount;

  /** 原生整数已核REFUND数量。 */
  public JsonNode validatedRefundCount;

  /** 原生退款合计金额文本，不包含DEBIT。 */
  public JsonNode validatedRefundTotalAmount;

  /** 原生同pay候选向量指纹文本。 */
  public JsonNode vectorSha256;

  /** 原16T11关联退款报告，不改变原账户/退款事实语义。 */
  public UserLinkedRefundEvidenceDTO linkedEvidence;

  /** 创建原生反序列化载体，不改变证据类型或缺失语义。 */
  public UserPaymentLedgerEvidenceDTO() {}
}
