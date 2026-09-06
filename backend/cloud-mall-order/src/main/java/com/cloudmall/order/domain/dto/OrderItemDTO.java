package com.cloudmall.order.domain.dto;

/** 创建订单中的商品数量请求。 */
public class OrderItemDTO {
  /** SKU 主键。 */
  public Long skuId;

  /** 购买数量。 */
  public int quantity;
}
