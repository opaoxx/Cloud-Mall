package com.cloudmall.product.domain.dto;

import com.cloudmall.product.domain.po.ProductMetricControlPO;

/** Internal ordinary-read projection; never serialized as a public or persistent business model. */
public class ProductMetricInitialApplySnapshotDTO {
  /** Complete control row, retaining its original numeric, nullable and timestamp mapping. */
  public final ProductMetricControlPO control;

  /** Immutable ledger identity; null means the joined ledger row is absent. */
  public final String appliedEventId;

  /** Original immutable fingerprint; consulted only when the ledger identity exists. */
  public final String appliedFingerprint;

  /** Product and optional SKU mapping observed in this ordinary repeatable-read snapshot. */
  public final boolean mappingPresent;

  /**
   * Preserve complete control, optional identity and mapping presence from one ordinary RR read.
   */
  public ProductMetricInitialApplySnapshotDTO(
      ProductMetricControlPO control,
      String appliedEventId,
      String appliedFingerprint,
      boolean mappingPresent) {
    this.control = control;
    this.appliedEventId = appliedEventId;
    this.appliedFingerprint = appliedFingerprint;
    this.mappingPresent = mappingPresent;
  }
}
