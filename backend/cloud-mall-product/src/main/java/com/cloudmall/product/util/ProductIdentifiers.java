package com.cloudmall.product.util;

import java.util.UUID;

/** 商品、分类与活动沿用的本地主键生成方式。 */
public final class ProductIdentifiers {
  /** 禁止实例化主键工具。 */
  private ProductIdentifiers() {}

  /** 按原 UUID 高位绝对值算法生成业务主键。 */
  public static long nextId() {
    return Math.abs(UUID.randomUUID().getMostSignificantBits());
  }
}
