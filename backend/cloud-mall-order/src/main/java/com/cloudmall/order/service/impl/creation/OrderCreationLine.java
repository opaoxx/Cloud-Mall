package com.cloudmall.order.service.impl.creation;

import com.cloudmall.order.feign.ProductClient;
import java.math.BigDecimal;

/**
 * 创建过程的计算明细，不作为HTTP或持久化对象。
 *
 * @param sku 已验证的原商品快照
 * @param lineAmount 保留原scale的行金额
 * @param quantity 原购买数量
 */
public record OrderCreationLine(ProductClient.SkuView sku, BigDecimal lineAmount, int quantity) {}
