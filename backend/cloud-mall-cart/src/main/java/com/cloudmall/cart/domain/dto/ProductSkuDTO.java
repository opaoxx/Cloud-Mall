package com.cloudmall.cart.domain.dto;

import java.util.Map;

/**
 * 商品服务通过 Feign 返回的 SKU 事实。
 *
 * @param skuId SKU 主键
 * @param productId 商品主键
 * @param productName 商品名称
 * @param unitPrice 原始单价字符串
 * @param skuSnapshot SKU 规格快照
 */
public record ProductSkuDTO(
    Long skuId,
    Long productId,
    String productName,
    String unitPrice,
    Map<String, String> skuSnapshot) {}
