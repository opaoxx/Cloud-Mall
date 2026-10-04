package com.cloudmall.product.domain.po;

import java.math.BigDecimal;
import java.time.LocalDateTime;

/** The small, read-only projection used by the product search index. */
public record ProductIndexDocument(
    long id,
    long categoryId,
    String name,
    String mainImage,
    String description,
    BigDecimal price,
    int status,
    long version,
    LocalDateTime updatedAt,
    long viewCount,
    long searchCount,
    BigDecimal hotScore,
    String metricSourceType,
    String metricSourceRef) {
  /** 兼容既有测试调用；运行时投影必须由 Mapper 提供完整排序字段。 */
  public ProductIndexDocument(long id, long categoryId, String name, String mainImage,
      String description, BigDecimal price, int status, long version) {
    this(id, categoryId, name, mainImage, description, price, status, version,
        LocalDateTime.of(1970, 1, 1, 0, 0), 0L, 0L, BigDecimal.ZERO,
        "UNKNOWN", null);
  }
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
