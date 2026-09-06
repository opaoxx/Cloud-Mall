package com.cloudmall.stock.domain.vo;

/** SKU 可售库存响应。 */
public class StockQuantityVO {
  /** SKU 主键。 */
  public Long skuId;

  /** 可售数量。 */
  public int availableQuantity;

  /** 创建库存响应。 */
  public StockQuantityVO(Long skuId, int availableQuantity) {
    this.skuId = skuId;
    this.availableQuantity = availableQuantity;
  }
}
