package com.cloudmall.stock.domain.dto;

/** 一个 SKU 的库存数量请求。 */
public class StockLineDTO {
  /** SKU 主键。 */
  public Long skuId;

  /** 变更数量。 */
  public int quantity;

  /** 创建库存数量请求。 */
  public StockLineDTO() {}

  /** 创建库存数量请求。 */
  public StockLineDTO(Long skuId, int quantity) {
    this.skuId = skuId;
    this.quantity = quantity;
  }
}
