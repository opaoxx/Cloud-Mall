package com.cloudmall.pay.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** Order基本证据原生token载体，不强转身份或金额，不改变旧业务DTO。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class OrderBasicEvidenceDTO {
  /** 原生订单号文本，仍与可信Pay anchor精确核对。 */
  public JsonNode orderNo;

  /** 原生正整数用户主键，字符串/小数不是身份凭据。 */
  public JsonNode userId;

  /** 生产金额字符串原token，不把number当金额协议证明。 */
  public JsonNode payAmount;

  /** 原生订单状态文本，仅接受原四状态。 */
  public JsonNode status;

  /** 创建不补类型或缺失默认值的反序列化载体。 */
  public OrderBasicEvidenceDTO() {}
}
