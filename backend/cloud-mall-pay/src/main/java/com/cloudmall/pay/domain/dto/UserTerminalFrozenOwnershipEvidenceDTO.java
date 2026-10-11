package com.cloudmall.pay.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** User16T24原生JSON载体，不补默认值、不作为响应直接输出。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserTerminalFrozenOwnershipEvidenceDTO {
  /** 固定终态观察范围文本。 */
  public JsonNode scope;

  /** 显式禁止动作原生boolean。 */
  public JsonNode automaticActionsAllowed;

  /** 其它状态未查原生boolean。 */
  public JsonNode otherDebitStatesNotInspected;

  /** 仅attempt取消未作为冻结候选检查原生boolean。 */
  public JsonNode attemptOnlyCancelledNotInspected;

  /** 不证明账户因果原生boolean。 */
  public JsonNode accountCausalityNotProven;

  /** 保原终态Fence TTL语义原生boolean。 */
  public JsonNode terminalFenceMayBeExpired;

  /** 固定原生整数上限。 */
  public JsonNode candidateLimit;

  /** 原User固定分类文本。 */
  public JsonNode classification;

  /** 是否查询候选原生boolean。 */
  public JsonNode queryAttempted;

  /** 是否完成有界候选原生boolean。 */
  public JsonNode candidatesComplete;

  /** 原生整数候选数量。 */
  public JsonNode observedCount;

  /** 原生整数确认行数量。 */
  public JsonNode validatedConfirmedCount;

  /** 原生整数取消行数量。 */
  public JsonNode validatedCancelledCount;

  /** 原生终态归属向量指纹文本。 */
  public JsonNode vectorSha256;

  /** 原T9的13个字段仍为JsonNode。 */
  public UserAccountEvidenceDTO baseEvidence;

  /** 创建原生反序列化载体，不改变证据类型或缺失语义。 */
  public UserTerminalFrozenOwnershipEvidenceDTO() {}
}
