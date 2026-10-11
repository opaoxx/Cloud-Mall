package com.cloudmall.pay.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** 关联退款上下文的原账户载体，仅增加原生原因码，不改变旧T10 DTO。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserRefundBaseEvidenceDTO extends UserAccountEvidenceDTO {
  /** 原生原因数组，只允许完整退款上下文解除固定部分退款缺口。 */
  public JsonNode reasonCodes;

  /** 创建不补类型或缺失默认值的反序列化载体。 */
  public UserRefundBaseEvidenceDTO() {}
}
