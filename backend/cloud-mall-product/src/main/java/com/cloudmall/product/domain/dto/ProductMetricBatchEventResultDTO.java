package com.cloudmall.product.domain.dto;

/** One original input position's successful batch outcome; not independent commit evidence. */
public final class ProductMetricBatchEventResultDTO {
  /** Zero-based position of the original delivery, including repeated event identities. */
  public final int index;

  /** Original immutable event identity. */
  public final String eventId;

  /** Canonical business fingerprint, excluding transport metadata. */
  public final String fingerprint;

  /** APPLIED for a new unique event, otherwise DUPLICATE. */
  public final String outcome;

  /** Preserve one input position without collapsing multiple deliveries of the same event. */
  public ProductMetricBatchEventResultDTO(
      int index, String eventId, String fingerprint, String outcome) {
    this.index = index;
    this.eventId = eventId;
    this.fingerprint = fingerprint;
    this.outcome = outcome;
  }
}
