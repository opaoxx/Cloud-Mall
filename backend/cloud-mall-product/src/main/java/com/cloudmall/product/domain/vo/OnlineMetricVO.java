package com.cloudmall.product.domain.vo;

/** Product-applied online facts, distinct from legacy imports and undelivered Stock events. */
public record OnlineMetricVO(
    String seriesId,
    String sourceType,
    long viewCount,
    long searchCount,
    long soldCount,
    String hotScore,
    long metricVersion,
    String startedAt,
    String updatedAt) {}
