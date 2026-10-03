package com.cloudmall.product.domain.po;

import java.math.BigDecimal;

/** ES 中用于分页排序和商品事实校验的最小商品投影。 */
public record ProductSearchHit(long id, long categoryId, String name, BigDecimal price,
    int status, long productVersion, long updatedAt) {}
