package com.cloudmall.product.service;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.domain.po.ProductMetricProjectionOutboxPO;
import com.cloudmall.product.service.impl.ElasticsearchProductIndexWriter;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.TimeUnit;
import javax.annotation.PreDestroy;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

/** Projects at most fifty distinct products without one ES write for every queued event version. */
@Component
@ConditionalOnProperty(name = "cloudmall.product.metrics.enabled", havingValue = "true")
public class ProductMetricProjectionDispatcher {
  /** Short proxy transactions claim and settle durable obligations. */
  private final ProductMetricService metrics;

  /** One RR content/stat snapshot, released before the network write. */
  private final ProductIndexDocumentLoader loader;

  /** The same production writer uses metric-only atomic updates for this path. */
  private final ElasticsearchProductIndexWriter writer;

  /** Bounded lifecycle state belongs only to this dispatcher. */
  private boolean closed;

  /** Own active scheduled rounds, not framework executor threads. */
  private int active;

  /** Bind existing production projection collaborators. */
  public ProductMetricProjectionDispatcher(
      ProductMetricService metrics,
      ProductIndexDocumentLoader loader,
      ElasticsearchProductIndexWriter writer) {
    this.metrics = metrics;
    this.loader = loader;
    this.writer = writer;
  }

  /** Claim, load once, refresh ES, then settle only covered owned versions. */
  @Scheduled(
      initialDelay = 1000,
      fixedDelayString = "${cloudmall.product.metrics.projection-interval-ms:1000}")
  public void dispatch() {
    synchronized (this) {
      if (closed) return;
      active++;
    }
    try {
      // 1. One claim transaction returns before the consistent snapshot or remote write.
      String owner = UUID.randomUUID().toString();
      List<ProductMetricProjectionOutboxPO> claims = metrics.claimProjection(owner);
      List<Long> ids = claims.stream().map(row -> row.productId).distinct().toList();
      if (ids.isEmpty()) return;
      List<ProductIndexDocument> snapshots = loader.loadOnline(ids);
      Set<Long> present = new HashSet<>();
      for (ProductIndexDocument document : snapshots) present.add(document.id());
      // 2. A missing product is durable diagnostic evidence, never a fabricated visible ES
      // document.
      for (long id : ids)
        if (!present.contains(id)) {
          byte[] evidence =
              ("{\"productId\":" + id + ",\"reason\":\"PROJECTION_PRODUCT_MISSING\"}")
                  .getBytes(StandardCharsets.UTF_8);
          metrics.quarantine("metric-projection", evidence, null, "PROJECTION_PRODUCT_MISSING");
          metrics.finishProjection(id, 0, owner, false);
        }
      if (snapshots.isEmpty()) return;
      boolean success = false;
      try {
        writer.upsertMetrics(snapshots);
        success = true;
      } catch (Exception failure) {
        /* Keep durable UNKNOWN obligations, with no claim of a partial bulk success. */
      }
      // 3. A newer unclaimed or unsent version is never marked DONE by this refresh.
      Map<Long, Long> sentVersions = new LinkedHashMap<>();
      for (ProductIndexDocument snapshot : snapshots)
        sentVersions.put(snapshot.id(), snapshot.onlineStat().metricVersion);
      if (!sentVersions.isEmpty()) metrics.finishProjectionBatch(sentVersions, owner, success);
    } finally {
      synchronized (this) {
        active--;
        notifyAll();
      }
    }
  }

  /** Stop new rounds and await this component's active work with a fifteen-second bound. */
  @PreDestroy
  public void close() {
    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(15);
    synchronized (this) {
      closed = true;
      while (active > 0) {
        long left = deadline - System.nanoTime();
        if (left <= 0) throw new IllegalStateException("METRIC_PROJECTION_STOP_UNKNOWN");
        try {
          TimeUnit.NANOSECONDS.timedWait(this, left);
        } catch (InterruptedException failure) {
          Thread.currentThread().interrupt();
          throw new IllegalStateException("METRIC_PROJECTION_STOP_INTERRUPTED", failure);
        }
      }
    }
  }
}
