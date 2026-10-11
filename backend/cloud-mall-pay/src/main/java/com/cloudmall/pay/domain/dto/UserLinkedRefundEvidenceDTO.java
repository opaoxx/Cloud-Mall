package com.cloudmall.pay.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** User16T11原生JSON载体，不补默认值、不作为响应直接输出。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserLinkedRefundEvidenceDTO {
  /** 原关联退款观察范围文本。 */
  public JsonNode scope;

  /** 显式禁止动作原生boolean。 */
  public JsonNode automaticActionsAllowed;

  /** 未关联账本未检查原生boolean。 */
  public JsonNode unlinkedLedgerRowsNotInspected;

  /** 固定原生整数上限。 */
  public JsonNode candidateLimit;

  /** 原User固定分类文本。 */
  public JsonNode classification;

  /** 是否查询候选原生boolean。 */
  public JsonNode queryAttempted;

  /** 有界候选是否完整原生boolean。 */
  public JsonNode candidatesComplete;

  /** 原生整数候选数量。 */
  public JsonNode observedCount;

  /** 原生关联退款总额文本。 */
  public JsonNode validatedTotalAmount;

  /** 原生候选向量指纹文本。 */
  public JsonNode vectorSha256;

  /** 原账户原生事实与上下文原因，不改原User响应。 */
  public UserRefundBaseEvidenceDTO baseEvidence;

  /** 创建原生反序列化载体，不改变证据类型或缺失语义。 */
  public UserLinkedRefundEvidenceDTO() {}
}
