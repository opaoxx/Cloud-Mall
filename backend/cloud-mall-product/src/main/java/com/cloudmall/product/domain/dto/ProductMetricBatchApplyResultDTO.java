package com.cloudmall.product.domain.dto;

import java.util.List;

/**
 * Immutable batch application results; only normal transaction-proxy return confirms completion.
 */
public final class ProductMetricBatchApplyResultDTO {
  /** One immutable result per original input position, retaining delivery order and duplicates. */
  public final List<ProductMetricBatchEventResultDTO> events;

  /** Number of unique new events written by this batch transaction. */
  public final int appliedCount;

  /**
   * Copy the bounded results without implying that construction itself committed the transaction.
   */
  public ProductMetricBatchApplyResultDTO(
      List<ProductMetricBatchEventResultDTO> events, int appliedCount) {
    this.events = List.copyOf(events);
    this.appliedCount = appliedCount;
  }
}
