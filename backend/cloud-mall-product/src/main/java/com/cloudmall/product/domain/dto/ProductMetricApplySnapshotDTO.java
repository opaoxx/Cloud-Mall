package com.cloudmall.product.domain.dto;

import com.cloudmall.product.domain.po.ProductOnlineStatPO;

/** Internal locked query projection used only after the application control lock is held. */
public class ProductMetricApplySnapshotDTO {
  /** Existing immutable event identity; null means that no applied ledger row exists. */
  public final String appliedEventId;

  /** Existing business fingerprint; read only when the applied ledger identity is present. */
  public final String appliedFingerprint;

  /** Complete locked aggregate for a new event; null for duplicates or an absent aggregate. */
  public final ProductOnlineStatPO stat;

  /** Retain ledger identity and the optional aggregate without changing persistent values. */
  public ProductMetricApplySnapshotDTO(
      String appliedEventId, String appliedFingerprint, ProductOnlineStatPO stat) {
    this.appliedEventId = appliedEventId;
    this.appliedFingerprint = appliedFingerprint;
    this.stat = stat;
  }
}
