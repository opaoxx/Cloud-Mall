package com.cloudmall.pay.domain.dto;

import com.fasterxml.jackson.databind.JsonNode;

/** Stock GET只在内存接收的原生JSON字段，不作为外部诊断报告输出。 */
public class StockReservationEvidenceDTO {
  /** Stock返回的原订单号token，必须是原生文本。 */
  public JsonNode orderNo;

  /** 原生状态token，报告仅允许冻结白名单。 */
  public JsonNode status;

  /** 原生SKU/数量/状态数组，防止默认整数反序列化截断或文本强转。 */
  public JsonNode items;

  /** 创建Feign反序列化载体，不补造缺失字段。 */
  public StockReservationEvidenceDTO() {}
}
