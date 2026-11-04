package com.cloudmall.cart.domain.vo;

import com.cloudmall.cart.domain.po.CartItemPO;

/** 购物车项响应视图。 */
public class CartItemVO {
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

  /** 从 Redis 购物车项构造响应视图。 */
  public static CartItemVO from(CartItemPO item) {
    CartItemVO view = new CartItemVO();
    view.skuId = item.skuId;
    view.productId = item.productId;
    view.productName = item.productName;
    view.unitPrice = item.unitPrice;
    view.quantity = item.quantity;
    view.checked = item.checked;
    view.addedAt = item.addedAt;
    return view;
  }
}
