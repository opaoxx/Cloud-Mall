package com.cloudmall.stock.domain.dto;

/**
 * Immutable-once-persisted V1 business envelope; explicit null dimensions remain in its canonical
 * fingerprint.
 */
public class StockSalesEventDTO {
  /** Wire schema revision; V1 accepts exactly 1. */
  public int payloadVersion;

  /** Online series identity, independent of imported historical metrics. */
  public String seriesId;

  /** Stable lowercase SHA-256 business identity, unchanged by delivery retries. */
  public String eventId;

  /** One reviewed view, search exposure, or confirmed sale event kind. */
  public String eventType;

  /** Owning source service: PRODUCT or STOCK. */
  public String producer;

  /** Server-generated UUID for one successful public read; null for a sale. */
  public String requestId;

  /** Positive product identity confirmed against the source mapping. */
  public Long productId;

  /** Positive sold SKU identity; null for a public read. */
  public Long skuId;

  /** Positive event increment, not the existing accumulated counter. */
  public Integer quantity;

  /** Unmodified original confirmation order identity; null for a public read. */
  public String orderNo;

  /** Original flow or settlement proof, or server request source reference. */
  public String sourceRef;

  /** Actual persisted source version after confirmation; null for public reads. */
  public Long sourceRevision;

  /** Original event time persisted once as Shanghai ISO-8601 milliseconds. */
  public String occurredAt;

  /** Optional diagnostic correlation excluded from business fingerprint. */
  public String traceId;
}
