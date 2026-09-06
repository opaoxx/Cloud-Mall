package com.cloudmall.cart.domain.po;

/** Redis 购物车 Hash 中保存的购物车项。 */
public class CartItemPO {
  /** SKU 主键。 */
  public Long skuId;

  /** 商品主键。 */
  public Long productId;

  /** 商品名称。 */
  public String productName = "CloudMall 商品";

  /** 商品单价字符串。 */
  public String unitPrice = "0.00";

  /** 购买数量。 */
  public int quantity = 1;

  /** 是否勾选。 */
  public Boolean checked = true;

  /** 加入购物车时间。 */
  public String addedAt;
}
