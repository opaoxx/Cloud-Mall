package com.cloudmall.order.domain.vo;

import java.math.BigDecimal;
import java.util.Map;

/** 订单明细快照响应。 */
public record OrderItemVO(
    Long productId,
    Long skuId,
    String productNameSnapshot,
    Map<String, String> skuSnapshot,
    BigDecimal unitPrice,
    int quantity,
    BigDecimal lineAmount) {}
