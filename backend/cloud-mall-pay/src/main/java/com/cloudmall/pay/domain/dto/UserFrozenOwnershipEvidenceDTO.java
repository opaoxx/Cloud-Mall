package com.cloudmall.pay.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** User16T12原生JSON载体，缺失不补默认值，不直接输出为响应。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserFrozenOwnershipEvidenceDTO {
  /** 固定观察范围原生文本。 */
  public JsonNode scope;

  /** 禁止自动资金动作原生boolean。 */
  public JsonNode automaticActionsAllowed;

  /** 未检查历史终态原生boolean。 */
  public JsonNode terminalFrozenRowsNotInspected;

  /** 固定候选上限原生整数。 */
  public JsonNode candidateLimit;

  /** 原User固定分类文本。 */
  public JsonNode classification;

  /** 是否查询候选原生boolean。 */
  public JsonNode queryAttempted;

  /** 候选观察数量原生整数。 */
  public JsonNode observedCount;

  /** 当前有界候选全部完成原生boolean。 */
  public JsonNode candidatesComplete;

  /** 当前已核冻结合计原生金额文本。 */
  public JsonNode validatedFrozenTotal;

  /** 原归属向量的原生SHA256文本。 */
  public JsonNode vectorSha256;

  /** 原T9的13个字段均保持JsonNode类型。 */
  public UserAccountEvidenceDTO baseEvidence;

  /** 创建反序列化载体，不转换或补造原证据。 */
  public UserFrozenOwnershipEvidenceDTO() {}
}
