package com.cloudmall.stock.domain.vo;

/** 订单库存流水中的 SKU 状态。 */
public class StockReservationItemVO {
  /** SKU 主键。 */
  public Long skuId;

  /** 原始预扣数量。 */
  public int quantity;

  /** 当前结算状态。 */
  public String status;

  /** 创建库存预扣明细响应。 */
  public StockReservationItemVO(Long skuId, int quantity, String status) {
    this.skuId = skuId;
    this.quantity = quantity;
    this.status = status;
  }
}
