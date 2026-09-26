package com.cloudmall.product.domain.po;

import java.math.BigDecimal;

/** The small, read-only projection used by the product search index. */
public record ProductIndexDocument(
    long id,
    long categoryId,
    String name,
    String mainImage,
    String description,
    BigDecimal price,
    int status,
    long version) {
  /** 兼容早期调用方的七字段构造，运行时事实请使用数据库版本。 */
  public ProductIndexDocument(
      long id,
      long categoryId,
      String name,
      String mainImage,
      String description,
      BigDecimal price,
      int status) {
    this(id, categoryId, name, mainImage, description, price, status, 0L);
  }
}
