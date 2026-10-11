package com.cloudmall.pay.domain.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.databind.JsonNode;

/** User诊断报告的原生JSON内存载体；缺字段不补值，不作为响应直接输出。 */
@JsonIgnoreProperties(ignoreUnknown = true)
public class UserAccountEvidenceDTO {
  /** 固定User观察范围token。 */
  public JsonNode scope;

  /** 显式禁止自动动作token，不允许缺字段默认false。 */
  public JsonNode automaticActionsAllowed;

  /** 未查询其他退款token，必须显式true。 */
  public JsonNode otherRefundsNotInspected;

  /** 原生整数用户标识token。 */
  public JsonNode userId;

  /** 原支付号token。 */
  public JsonNode payNo;

  /** 原订单号token。 */
  public JsonNode orderNo;

  /** 原金额字符串token。 */
  public JsonNode amount;

  /** 原资金状态token。 */
  public JsonNode tccState;

  /** 原匹配DEBIT存在性token。 */
  public JsonNode ledgerApplied;

  /** 固定User本地分类token。 */
  public JsonNode classification;

  /** 原资金标识指纹对象token。 */
  public JsonNode identifiers;

  /** 账户和冻结投影对象token。 */
  public JsonNode account;

  /** 固定退款键摘要对象token。 */
  public JsonNode refund;

  /** 创建反序列化载体，不改变原JSON token类型。 */
  public UserAccountEvidenceDTO() {}
}
