package com.cloudmall.product.domain.po;

import java.util.List;

/** 同一次 ES 查询返回的精确总数和当前页商品投影。 */
public record ProductSearchPage(List<ProductSearchHit> hits, long total) {}
