package com.cloudmall.order.domain.vo;

import java.math.BigDecimal;
import java.util.Map;

/**
 * 订单明细快照响应，保留原身份、金额和规格字段。
 *
 * @param productId 原商品身份
 * @param skuId 原SKU身份
 * @param productNameSnapshot 下单时的商品名称
 * @param skuSnapshot 下单时的SKU规格快照
 * @param unitPrice 原商品单价，沿公共金额序列化规则输出
 * @param quantity 原购买数量
 * @param lineAmount 原订单行金额
 */
public record OrderItemVO(
    Long productId,
    Long skuId,
    String productNameSnapshot,
    Map<String, String> skuSnapshot,
    BigDecimal unitPrice,
    int quantity,
    BigDecimal lineAmount) {}
