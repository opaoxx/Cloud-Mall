package com.cloudmall.product.service.impl.metrics;

import com.cloudmall.product.domain.dto.ProductMetricApplySnapshotDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchApplyResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricBatchEventResultDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.domain.dto.ProductMetricInitialApplySnapshotDTO;
import com.cloudmall.product.domain.po.*;
import com.cloudmall.product.domain.vo.OnlineMetricVO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import com.cloudmall.product.service.impl.index.ProductIndexDocumentLoader;
import com.cloudmall.product.util.ProductMetricEvents;
import com.cloudmall.product.util.ProductMetricEvents.InvalidEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.*;
import java.time.temporal.ChronoUnit;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.*;

/**
 * Short, real local transactions separate public registration, immutable application and network
 * delivery.
 */
@Service
@ConditionalOnProperty(name = "cloudmall.product.metrics.enabled", havingValue = "true")
public class ProductMetricService {
  /** All online SQL shares Product's original local resource. */
  private final ProductMetricMapper mapper;

  /** Existing joined source loader participates in the same RR admission snapshot. */
  @org.springframework.beans.factory.annotation.Autowired private ProductIndexDocumentLoader loader;

  /** Construct the atomic metric transaction boundary. */
  public ProductMetricService(ProductMetricMapper mapper) {
    this.mapper = mapper;
  }

  /** Commit a detail event before the public successful response is returned. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public void registerViews(String requestId, long productId, String traceId) {
    // 1. A server request ID identifies this successful detail read.
    // 2. Store immutable event bytes without touching counters or Elasticsearch.
    register(ProductMetricEvents.product(requestId, productId, false, traceId));
    // 3. The proxy commits before the controller can send HTTP 200.
  }

  /** Commit exactly one event for each distinct product in the final nonblank-keyword result. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public void registerSearchPage(String requestId, List<Long> productIds, String traceId) {
    // 1. The final validated page, never discarded ES attempts, supplies the identities.
    if (productIds.size() > 100) throw new IllegalArgumentException("METRIC_PAGE_LIMIT");
    // 2. All distinct page exposures form one bounded atomic outbox batch.
    for (long id : new LinkedHashSet<>(productIds))
      register(ProductMetricEvents.product(requestId, id, true, traceId));
    // 3. An insert/commit failure rejects the HTTP success; no partial page is registered.
  }

  /** Apply one immutable event under its product anchor, taking the shared cut gate only last. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public String apply(ProductMetricEventDTO event) {
    // 1. Validate before mutations; immutable committed identities have a write-free fast path.
    ProductMetricEvents.validate(event);
    ProductMetricInitialApplySnapshotDTO previous =
        mapper.initialApplySnapshot(event.eventId, event.productId, event.skuId);
    if (previous == null
        || previous.control == null
        || !ProductMetricEvents.SERIES.equals(previous.control.seriesId))
      throw new IllegalStateException("METRIC_CONTROL_INVALID");
    String fingerprint = ProductMetricEvents.fingerprint(event);
    if (previous.appliedEventId != null) {
      requireControl();
      if (!fingerprint.equals(previous.appliedFingerprint))
        throw new InvalidEvent("EVENT_FINGERPRINT_CONFLICT");
      return "DUPLICATE";
    }
    // 2. A PK-only upsert anchors this product without locking a missing ledger key or product.
    if (!previous.mappingPresent)
      throw resolveApplyFailure(event.eventId, fingerprint, "PRODUCT_MAPPING_MISSING");
    LocalDateTime time = now();
    mapper.ensureStat(event.productId, time);
    ProductOnlineStatPO stat = mapper.stat(event.productId, true);
    // 3. Write the exact event version, then validate and advance the final shared cut gate.
    applyNewEvent(event, fingerprint, stat, time);
    requireControl();
    advance();
    return "APPLIED";
  }

  /**
   * Apply one bounded immutable input batch in one RR transaction; the proxy commits after this
   * method returns its result, so a result constructed inside the method is not commit evidence.
   */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      isolation = Isolation.REPEATABLE_READ,
      timeout = 3)
  public ProductMetricBatchApplyResultDTO applyBatch(List<ProductMetricEventDTO> events) {
    // 1. Freeze at most four canonical envelopes before retaining any caller-owned DTO reference.
    if (events == null) throw new IllegalArgumentException("METRIC_APPLY_BATCH_INVALID");
    int inputSize = events.size();
    if (inputSize < 1 || inputSize > 4)
      throw new IllegalArgumentException("METRIC_APPLY_BATCH_INVALID");
    List<ProductMetricEventDTO> frozenEvents = new ArrayList<>(inputSize);
    for (int index = 0; index < inputSize; index++) {
      ProductMetricEventDTO event = events.get(index);
      if (event == null) throw new IllegalArgumentException("METRIC_APPLY_BATCH_INVALID");
      frozenEvents.add(ProductMetricEvents.parse(ProductMetricEvents.bytes(event)));
    }
    if (events.size() != inputSize)
      throw new IllegalArgumentException("METRIC_APPLY_BATCH_INVALID");

    // 2. Preflight in input order; a failure takes its original current gate and only throws.
    Map<String, String> fingerprints = new HashMap<>();
    Set<Integer> newIndexes = new HashSet<>();
    Map<Integer, LocalDateTime> eventTimes = new HashMap<>();
    Map<Long, LocalDateTime> anchorTimes = new TreeMap<>();
    List<ProductMetricBatchEventResultDTO> results = new ArrayList<>(inputSize);
    for (int index = 0; index < inputSize; index++) {
      ProductMetricEventDTO event = frozenEvents.get(index);
      ProductMetricInitialApplySnapshotDTO previous =
          mapper.initialApplySnapshot(event.eventId, event.productId, event.skuId);
      if (previous == null
          || previous.control == null
          || !ProductMetricEvents.SERIES.equals(previous.control.seriesId))
        throw new IllegalStateException("METRIC_CONTROL_INVALID");
      String fingerprint = ProductMetricEvents.fingerprint(event);
      String earlierFingerprint = fingerprints.putIfAbsent(event.eventId, fingerprint);
      if (earlierFingerprint != null && !fingerprint.equals(earlierFingerprint)
          || previous.appliedEventId != null && !fingerprint.equals(previous.appliedFingerprint)) {
        requireControl();
        throw new InvalidEvent("EVENT_FINGERPRINT_CONFLICT");
      }
      boolean duplicate = earlierFingerprint != null || previous.appliedEventId != null;
      if (!duplicate) {
        if (!previous.mappingPresent)
          throw resolveApplyFailure(event.eventId, fingerprint, "PRODUCT_MAPPING_MISSING");
        LocalDateTime time = now();
        newIndexes.add(index);
        eventTimes.put(index, time);
        anchorTimes.putIfAbsent(event.productId, time);
      }
      results.add(
          new ProductMetricBatchEventResultDTO(
              index, event.eventId, fingerprint, duplicate ? "DUPLICATE" : "APPLIED"));
    }

    // 3. Acquire each distinct product anchor in ascending order before any ledger insertion.
    Map<Long, ProductOnlineStatPO> lockedStats = new HashMap<>();
    for (Map.Entry<Long, LocalDateTime> anchor : anchorTimes.entrySet()) {
      mapper.ensureStat(anchor.getKey(), anchor.getValue());
      ProductOnlineStatPO stat = mapper.stat(anchor.getKey(), true);
      if (stat == null) throw new IllegalStateException("METRIC_STAT_LOST");
      lockedStats.put(anchor.getKey(), stat);
    }

    // 4. Retain original input order and one precise version/projection per new unique event.
    for (int index = 0; index < inputSize; index++) {
      if (!newIndexes.contains(index)) continue;
      ProductMetricEventDTO event = frozenEvents.get(index);
      applyNewEvent(
          event,
          results.get(index).fingerprint,
          lockedStats.get(event.productId),
          eventTimes.get(index));
    }

    // 5. The sole global gate is last; all-DUP batches do not change revision or its clock.
    ProductMetricControlPO current = requireControl();
    int appliedCount = newIndexes.size();
    if (appliedCount > 0) {
      if (current.revision > Long.MAX_VALUE - appliedCount || mapper.advanceBy(appliedCount) != 1)
        throw new IllegalStateException("METRIC_CONTROL_EXHAUSTED");
    }
    return new ProductMetricBatchApplyResultDTO(results, appliedCount);
  }

  /**
   * Mutate one already-locked aggregate and write its immutable identity/version. Arithmetic
   * failure uses the original current-identity/gate path and never resumes business writes
   * afterward.
   */
  private void applyNewEvent(
      ProductMetricEventDTO event,
      String fingerprint,
      ProductOnlineStatPO stat,
      LocalDateTime time) {
    // 1. Compute exact counters, score and version; arithmetic failure only rechecks and throws.
    try {
      if ("PRODUCT_VIEW_CONFIRMED".equals(event.eventType))
        stat.viewCount = Math.addExact(stat.viewCount, event.quantity.longValue());
      else if ("PRODUCT_SEARCH_EXPOSED".equals(event.eventType))
        stat.searchCount = Math.addExact(stat.searchCount, event.quantity.longValue());
      else stat.soldCount = Math.addExact(stat.soldCount, event.quantity.longValue());
      stat.hotScore =
          Math.addExact(
              Math.addExact(stat.viewCount, stat.searchCount),
              Math.multiplyExact(10L, stat.soldCount));
      stat.metricVersion = Math.addExact(stat.metricVersion, 1L);
    } catch (ArithmeticException overflow) {
      throw resolveApplyFailure(event.eventId, fingerprint, "COUNTER_OVERFLOW");
    }
    // 2. Plain ledger INSERT conflicts escape the proxy and roll back every tentative write.
    mapper.insertApplied(event, fingerprint, stat.metricVersion, time);
    // 3. Persist the aggregate and its individual version projection in the same transaction.
    mapper.updateStat(stat, event.eventId, time);
  }

  /**
   * Resolve a permanent failure against current immutable identity, never the earlier RR snapshot.
   * No business write may follow this current read and final gate. A newly discovered duplicate
   * retries in a fresh transaction so a tentative zero aggregate cannot be committed by accident.
   */
  private RuntimeException resolveApplyFailure(
      String eventId, String fingerprint, String permanentReason) {
    ProductMetricApplySnapshotDTO current = mapper.findAppliedIdentity(eventId, true);
    requireControl();
    if (current == null) return new InvalidEvent(permanentReason);
    if (!fingerprint.equals(current.appliedFingerprint))
      return new InvalidEvent("EVENT_FINGERPRINT_CONFLICT");
    return new ConcurrencyFailureException("METRIC_REPLAY_REQUIRES_NEW_SNAPSHOT");
  }

  /** Independently retain permanent bad/conflicting bytes before parking or acknowledging them. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public void quarantine(String queue, byte[] body, String eventId, String reason) {
    requireControl();
    String sha = ProductMetricEvents.sha256(body);
    boolean complete = body.length <= 1048576;
    byte[] retained = complete ? body : Arrays.copyOf(body, Math.min(body.length, 65536));
    String id = ProductMetricEvents.sha256((queue + ":" + sha).getBytes(StandardCharsets.UTF_8));
    if (mapper.quarantine(id, queue, sha, retained, complete, eventId, reason, body.length))
      advance();
  }

  /** Join the original CRUD/outbox transaction and invalidate a previously admitted search cut. */
  @Transactional(propagation = Propagation.MANDATORY, timeout = 3)
  public void contentChanged() {
    requireControl();
    advance();
  }

  /** Claim one source outbox batch; the proxy returns only after transaction completion. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public List<ProductMetricOutboxPO> claim(String owner) {
    return mapper.claim(owner, now());
  }

  /** Record a source delivery result independently after publisher confirm/return observation. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public int finish(String eventId, String owner, boolean success, String reason) {
    int changed = mapper.finish(eventId, owner, success, reason, now());
    if (changed != 1) throw new IllegalStateException("METRIC_FINISH_OWNER_LOST");
    return changed;
  }

  /** Commit one observed publisher round atomically, preserving every event's lease CAS. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public int finishBatch(Map<String, Boolean> outcomes, String owner) {
    // 1. Validate the complete bounded round before the first durable result is updated.
    requireBatchOwner(owner);
    if (outcomes == null || outcomes.size() > 50)
      throw new IllegalArgumentException("METRIC_FINISH_BATCH_INVALID");
    Map<String, Boolean> batch = new LinkedHashMap<>(outcomes);
    for (Map.Entry<String, Boolean> outcome : batch.entrySet()) {
      if (outcome.getKey() == null
          || !outcome.getKey().matches("[0-9a-f]{64}")
          || outcome.getValue() == null)
        throw new IllegalArgumentException("METRIC_FINISH_BATCH_INVALID");
    }
    // 2. Network observations are already complete; each original SQL uses its current lease time.
    int changed = 0;
    for (Map.Entry<String, Boolean> outcome : batch.entrySet()) {
      boolean success = outcome.getValue();
      int affected =
          mapper.finish(
              outcome.getKey(), owner, success, success ? null : "PUBLISH_UNCERTAIN", now());
      if (affected != 1) throw new IllegalStateException("METRIC_FINISH_OWNER_LOST");
      changed += affected;
    }
    // 3. A later failure rolls back the whole round; the proxy commits before returning this count.
    return changed;
  }

  /** Claim bounded projection obligations without holding a transaction during ES refresh. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public List<ProductMetricProjectionOutboxPO> claimProjection(String owner) {
    return mapper.claimProjection(owner, now());
  }

  /** Settle only the acknowledged snapshot and still-owned versions covered by it. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public int finishProjection(long product, long version, String owner, boolean success) {
    int changed = mapper.finishProjection(product, version, owner, success, now());
    if (changed < 1) throw new IllegalStateException("METRIC_PROJECTION_OWNER_LOST");
    return changed;
  }

  /** Settle one sent ES snapshot batch only after the original bulk visibility result is known. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public int finishProjectionBatch(Map<Long, Long> sentVersions, String owner, boolean success) {
    // 1. Retain the actual sent versions and order without accepting unbounded or invalid
    // identities.
    requireBatchOwner(owner);
    if (sentVersions == null || sentVersions.size() > 50)
      throw new IllegalArgumentException("METRIC_PROJECTION_BATCH_INVALID");
    Map<Long, Long> batch = new LinkedHashMap<>(sentVersions);
    for (Map.Entry<Long, Long> snapshot : batch.entrySet()) {
      if (snapshot.getKey() == null
          || snapshot.getKey() <= 0
          || snapshot.getValue() == null
          || snapshot.getValue() <= 0)
        throw new IllegalArgumentException("METRIC_PROJECTION_BATCH_INVALID");
    }
    // 2. Keep the original per-product owner/live-lease and covered-version SQL predicates.
    int changed = 0;
    for (Map.Entry<Long, Long> snapshot : batch.entrySet()) {
      int affected =
          mapper.finishProjection(snapshot.getKey(), snapshot.getValue(), owner, success, now());
      if (affected < 1) throw new IllegalStateException("METRIC_PROJECTION_OWNER_LOST");
      changed += affected;
    }
    // 3. No successful prefix is committed when another product loses its claimed obligation.
    return changed;
  }

  /** Generated claim owners fit the existing ASCII column and are never trimmed or aliased. */
  private static void requireBatchOwner(String owner) {
    if (owner == null
        || owner.isEmpty()
        || owner.length() > 64
        || owner.chars().anyMatch(character -> character < 33 || character > 126))
      throw new IllegalArgumentException("METRIC_FINISH_OWNER_INVALID");
  }

  /** Read control/pending facts in a short repeatable-read transaction before or after ES. */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      isolation = Isolation.REPEATABLE_READ,
      readOnly = true,
      timeout = 3)
  public Cut cut() {
    ProductMetricControlPO control = mapper.control(false);
    return new Cut(
        control.seriesId,
        control.revision,
        control.indexUuid,
        control.indexReady == 1,
        mapper.pending() || !mapper.jobComplete());
  }

  /** Publish index readiness only while both obligation families and quarantine are settled. */
  @Transactional(propagation = Propagation.REQUIRES_NEW, timeout = 3)
  public boolean readiness(String uuid, boolean requested) {
    ProductMetricControlPO old = requireControl();
    boolean ready = requested && !mapper.pending() && mapper.jobComplete();
    if (!Objects.equals(uuid, old.indexUuid) || old.indexReady != (ready ? 1 : 0)) {
      if (mapper.readiness(uuid, ready) != 1)
        throw new IllegalStateException("METRIC_CONTROL_EXHAUSTED");
    }
    return ready;
  }

  /** Read a stable current aggregate batch for final-page version/value admission. */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      isolation = Isolation.REPEATABLE_READ,
      readOnly = true,
      timeout = 3)
  public Map<Long, ProductOnlineStatPO> stats(List<Long> ids) {
    return mapper.stats(ids);
  }

  /** Expose the online series separately while leaving the imported HotStat values untouched. */
  @Transactional(
      propagation = Propagation.REQUIRES_NEW,
      isolation = Isolation.REPEATABLE_READ,
      readOnly = true,
      timeout = 3)
  public OnlineMetricVO online(long productId) {
    ProductMetricControlPO control = mapper.control(false);
    ProductOnlineStatPO stat = mapper.stat(productId, false);
    return new OnlineMetricVO(
        control.seriesId,
        "ONLINE_EVENT",
        stat == null ? 0 : stat.viewCount,
        stat == null ? 0 : stat.searchCount,
        stat == null ? 0 : stat.soldCount,
        BigDecimal.valueOf(stat == null ? 0 : stat.hotScore).setScale(6).toPlainString(),
        stat == null ? 0 : stat.metricVersion,
        iso(control.startedAt),
        stat == null ? null : iso(stat.updatedAt));
  }

  private void register(ProductMetricEventDTO event) {
    mapper.register(
        event, ProductMetricEvents.fingerprint(event), ProductMetricEvents.bytes(event), now());
  }

  private ProductMetricControlPO requireControl() {
    ProductMetricControlPO control = mapper.control(true);
    if (control == null || !ProductMetricEvents.SERIES.equals(control.seriesId))
      throw new IllegalStateException("METRIC_CONTROL_INVALID");
    return control;
  }

  private void advance() {
    if (mapper.advance() != 1) throw new IllegalStateException("METRIC_CONTROL_EXHAUSTED");
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
  }

  private static String iso(LocalDateTime value) {
    return value
        .atOffset(ZoneOffset.ofHours(8))
        .format(java.time.format.DateTimeFormatter.ofPattern("uuuu-MM-dd'T'HH:mm:ss.SSSXXX"));
  }

  /**
   * Immutable committed Product-APPLIED cut; this makes no claim about undelivered Stock events.
   */
  public record Cut(
      String series, long revision, String indexUuid, boolean ready, boolean pending) {
    /** Admission requires an initialized generation and no unresolved obligations. */
    public boolean admissible() {
      return "cm21-online-v1".equals(series) && ready && !pending && indexUuid != null;
    }
  }

  /** Recheck cut and exact current content/metric axes together in one short RR snapshot. */
  @Transactional(
      propagation = Propagation.REQUIRED,
      isolation = Isolation.REPEATABLE_READ,
      readOnly = true,
      timeout = 3)
  public boolean admit(Cut before, List<com.cloudmall.product.domain.po.ProductSearchHit> hits) {
    ProductMetricControlPO control = mapper.control(false);
    if (!before.admissible()
        || control.revision != before.revision()
        || control.indexReady != 1
        || !Objects.equals(control.indexUuid, before.indexUuid())
        || mapper.pending()
        || !mapper.jobComplete()) return false;
    List<Long> ids =
        hits.stream().map(com.cloudmall.product.domain.po.ProductSearchHit::id).toList();
    Map<Long, com.cloudmall.product.domain.po.ProductIndexDocument> docs = new HashMap<>();
    for (var doc : loader.loadOnline(ids)) docs.put(doc.id(), doc);
    if (docs.size() != ids.size()) return false;
    for (var hit : hits) {
      var doc = docs.get(hit.id());
      var stat = doc.onlineStat();
      if (stat == null
          || !Objects.equals(hit.onlineSeriesId(), ProductMetricEvents.SERIES)
          || !Objects.equals(hit.metricVersion(), stat.metricVersion)
          || !Objects.equals(hit.onlineViewCount(), stat.viewCount)
          || !Objects.equals(hit.onlineSearchCount(), stat.searchCount)
          || !Objects.equals(hit.onlineSoldCount(), stat.soldCount)
          || !Objects.equals(hit.onlineHotScore(), stat.hotScore)
          || !Objects.equals(hit.contentFingerprint(), ProductMetricEvents.contentFingerprint(doc))
          || !Objects.equals(hit.onlineFingerprint(), ProductMetricEvents.metricFingerprint(stat)))
        return false;
    }
    return true;
  }

  /** Read the SQL page's revision from its already-active repeatable-read snapshot. */
  @Transactional(propagation = Propagation.MANDATORY, readOnly = true)
  public long sqlRevision() {
    return mapper.control(false).revision;
  }
}
