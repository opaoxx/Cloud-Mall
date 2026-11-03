package com.cloudmall.product.domain.vo;

import com.cloudmall.common.api.PageResult;

/**
 * Online paging keeps the original response fields and exposes its bounded Product-local revision.
 */
public final class ProductMetricPageVO<T> extends PageResult<T> {
  /** Explicit opt-in mode, never an implicit legacy reinterpretation. */
  public final String metricMode = "ONLINE";

  /** Fixed independent online series. */
  public final String metricSeries = "cm21-online-v1";

  /** Revision of a proven SQL snapshot/ES cut; null for non-metric ordering. */
  public final Long metricReadRevision;

  /** Copies the original page without altering items or total. */
  public ProductMetricPageVO(PageResult<T> page, Long revision) {
    super(page.items, page.page, page.pageSize, page.total);
    metricReadRevision = revision;
  }
}
