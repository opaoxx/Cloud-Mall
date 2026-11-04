package com.cloudmall.cart.domain.vo;

import java.util.List;

/**
 * 购物车结算预览，保留原金额字符串与失效项表示。
 *
 * @param items 选中的购物车项，包含失效商品的原快照
 * @param invalidItems 本次商品查询明确失效的项
 * @param totalAmount 按当前商品事实计算的总金额字符串
 * @param payAmount 本次预览的应付金额字符串
 */
public record CartSettlementPreviewVO(
    List<CartItemVO> items,
    List<CartInvalidItemVO> invalidItems,
    String totalAmount,
    String payAmount) {}
