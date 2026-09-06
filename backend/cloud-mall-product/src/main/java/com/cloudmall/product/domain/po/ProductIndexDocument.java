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
    int status) {}
