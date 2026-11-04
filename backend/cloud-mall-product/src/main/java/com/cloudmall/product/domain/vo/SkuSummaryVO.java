package com.cloudmall.product.domain.vo;

import java.util.Map;

/**
 * 提供给购物车及订单调用方的可购买 SKU 事实。
 *
 * @param skuId 原数值 SKU 主键
 * @param productId 原数值商品主键
 * @param productName 商品名称
 * @param skuCode SKU 编码
 * @param unitPrice 原两位小数金额字符串
 * @param skuSnapshot 原字符串规格对象
 */
public record SkuSummaryVO(
    Long skuId,
    Long productId,
    String productName,
    String skuCode,
    String unitPrice,
    Map<String, String> skuSnapshot) {}
