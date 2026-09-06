package com.cloudmall.cart.domain.vo;

import com.cloudmall.cart.domain.po.CartItemPO;

/** 购物车项响应视图。 */
public class CartItemVO extends CartItemPO {
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
