package com.cloudmall.order.service.impl;

import com.cloudmall.order.feign.ProductClient;
import java.math.BigDecimal;

/** 普通创建与秒杀落单共用的原商品金额/持久化快照门。 */
final class OrderProductSnapshotRules {
  /** 原订单/明细DECIMAL(18,2)容量，不是商业限额。 */
  private static final BigDecimal MAX_ORDER_AMOUNT = new BigDecimal("9999999999999999.99");

  /** 禁止实例化纯校验规则。 */
  private OrderProductSnapshotRules() {}

  /** 只核原BigDecimal存在且数值非负；零价沿原兼容行为，不重建、舍入或改变scale。 */
  static boolean hasNonnegativeProductPrice(BigDecimal unitPrice) {
    return unitPrice != null && unitPrice.signum() >= 0;
  }

  /** 仅在非空/非负价格门后核数学分精度，去尾零临时值不写回。先检查scale<=2， 避免极负scale整数去尾零下溢；不展开、舍入或修改原价格对象，不定义整数位容量。 */
  static boolean hasCentPrecision(BigDecimal amount) {
    return amount.scale() <= 2 || amount.stripTrailingZeros().scale() <= 2;
  }

  /** 在非空/非负及分精度通过后只比较既有列数值上界，不展开、舍入或改变原金额表示。 */
  static boolean fitsOrderAmountCapacity(BigDecimal amount) {
    return amount.compareTo(MAX_ORDER_AMOUNT) <= 0;
  }

  /** 仅核已存在商品事实的持久化必填字段和Unicode字符宽度，不截断/规范化或查询父关系。 */
  static boolean hasPersistableProductMetadata(ProductClient.SkuView productSku) {
    return productSku.productId() != null
        && productSku.productName() != null
        && productSku.productName().codePointCount(0, productSku.productName().length()) <= 255;
  }
}
