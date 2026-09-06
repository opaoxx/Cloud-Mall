package com.cloudmall.cart.domain.dto;

/** 购物车新增或更新请求。 */
public class CartItemDTO {
  /** SKU 主键。 */
  public Long skuId;

  /** 商品主键。 */
  public Long productId;

  /** 商品名称。 */
  public String productName;

  /** 商品单价字符串。 */
  public String unitPrice;

  /** 购买数量。 */
  public int quantity = 1;

  /** 是否勾选。 */
  public Boolean checked = true;

  /** 加入购物车时间。 */
  public String addedAt;
}
