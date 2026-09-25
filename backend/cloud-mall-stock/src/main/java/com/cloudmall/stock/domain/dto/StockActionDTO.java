package com.cloudmall.stock.domain.dto;

/** 订单终态提交后用于库存结算的稳定操作键。 */
public class StockActionDTO {
  /** 由原订单号和库存动作类型确定，不得在重试时改变。 */
  public String operationKey;
}
