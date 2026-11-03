package com.cloudmall.product.mapper;

import com.cloudmall.product.domain.dto.ProductMetricApplySnapshotDTO;
import com.cloudmall.product.domain.dto.ProductMetricEventDTO;
import com.cloudmall.product.domain.dto.ProductMetricInitialApplySnapshotDTO;
import com.cloudmall.product.domain.po.*;
import java.lang.reflect.Field;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** All online metric SQL, including bounded admission and leased delivery, stays in this mapper. */
@Repository
@ConditionalOnProperty(name = "cloudmall.product.metrics.enabled", havingValue = "true")
public class ProductMetricMapper {
  /** The original Product JDBC template participates in the original local transaction manager. */
  private final JdbcTemplate jdbc;

  /** One-second indexed cut probes never modify the shared template timeout. */
  private final JdbcTemplate admission;

  /** Projection reads are independently bounded to five seconds. */
  private final JdbcTemplate projection;

  /** Bind the production JDBC template without creating a second business data source. */
  public ProductMetricMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
    this.admission = new JdbcTemplate(java.util.Objects.requireNonNull(jdbc.getDataSource()));
    this.admission.setQueryTimeout(1);
    this.projection = new JdbcTemplate(jdbc.getDataSource());
    this.projection.setQueryTimeout(5);
  }

  /** Expose connection identity for startup same-resource admission. */
  public javax.sql.DataSource dataSource() {
    return jdbc.getDataSource();
  }

  /** Read or lock the shared cut at the point required by each reviewed transaction path. */
  public ProductMetricControlPO control(boolean lock) {
    return one(
        "SELECT * FROM product_metric_control WHERE singleton_id=1" + (lock ? " FOR UPDATE" : ""),
        ProductMetricControlPO.class);
  }

  /**
   * Read complete control, optional immutable identity and product mapping in one ordinary RR
   * snapshot. Missing control yields null; missing ledger leaves both identity fields null.
   */
  public ProductMetricInitialApplySnapshotDTO initialApplySnapshot(
      String eventId, long productId, Long skuId) {
    String mappingProjection =
        skuId == null
            ? "EXISTS(SELECT 1 FROM product WHERE id=?)"
            : "EXISTS(SELECT 1 FROM product_sku s JOIN product p ON p.id=s.product_id WHERE s.id=?"
                + " AND p.id=?)";
    Object[] parameters =
        skuId == null
            ? new Object[] {productId, eventId}
            : new Object[] {skuId, productId, eventId};
    List<ProductMetricInitialApplySnapshotDTO> snapshots =
        jdbc.query(
            "SELECT metric_control.singleton_id,metric_control.series_id,metric_control.revision,"
                + "metric_control.started_at,metric_control.index_uuid,metric_control.index_ready,"
                + "metric_control.updated_at,applied.event_id AS applied_event_id,"
                + "applied.fingerprint AS applied_fingerprint,"
                + mappingProjection
                + " AS mapping_present"
                + " FROM product_metric_control metric_control"
                + " LEFT JOIN product_metric_applied_event applied ON applied.event_id=?"
                + " WHERE metric_control.singleton_id=1",
            (result, index) -> {
              ProductMetricControlPO control = read(result, ProductMetricControlPO.class);
              String appliedEventId = result.getString("applied_event_id");
              String appliedFingerprint = result.getString("applied_fingerprint");
              boolean mappingPresent = result.getBoolean("mapping_present");
              if (result.wasNull()) throw new SQLException("METRIC_MAPPING_PROJECTION_NULL");
              return new ProductMetricInitialApplySnapshotDTO(
                  control, appliedEventId, appliedFingerprint, mappingPresent);
            },
            parameters);
    return snapshots.isEmpty() ? null : snapshots.get(0);
  }

  /** Increment the committed cut after one atomic content, metric, or quarantine change. */
  public int advance() {
    return jdbc.update(
        "UPDATE product_metric_control SET revision=revision+1,updated_at=? WHERE singleton_id=1"
            + " AND revision<9223372036854775807",
        now());
  }

  /**
   * Advance a bounded batch after its caller has acquired and validated the current control lock.
   */
  public int advanceBy(int count) {
    if (count < 1 || count > 4) throw new IllegalArgumentException("METRIC_BATCH_ADVANCE_INVALID");
    return jdbc.update(
        "UPDATE product_metric_control SET revision=revision+?,updated_at=?"
            + " WHERE singleton_id=1 AND revision<=?",
        count,
        now(),
        Long.MAX_VALUE - count);
  }

  /** Publish readiness only for the currently observed physical index generation. */
  public int readiness(String uuid, boolean ready) {
    return jdbc.update(
        "UPDATE product_metric_control SET"
            + " index_uuid=?,index_ready=?,revision=revision+1,updated_at=? WHERE singleton_id=1"
            + " AND revision<9223372036854775807",
        uuid,
        ready ? 1 : 0,
        now());
  }

  /** Read an immutable applied identity after obtaining the control lock. */
  public ProductMetricAppliedEventPO applied(String eventId) {
    return one(
        "SELECT * FROM product_metric_applied_event WHERE event_id=? FOR UPDATE",
        ProductMetricAppliedEventPO.class,
        eventId);
  }

  /**
   * Read only immutable identity. Normal apply uses the RR snapshot; a current locking read is
   * reserved for failure resolution followed only by the final gate and transaction rollback.
   */
  public ProductMetricApplySnapshotDTO findAppliedIdentity(String eventId, boolean forUpdate) {
    List<ProductMetricApplySnapshotDTO> identities =
        jdbc.query(
            "SELECT event_id,fingerprint FROM product_metric_applied_event WHERE event_id=?"
                + (forUpdate ? " FOR UPDATE" : ""),
            (result, index) ->
                new ProductMetricApplySnapshotDTO(
                    result.getString("event_id"), result.getString("fingerprint"), null),
            eventId);
    return identities.isEmpty() ? null : identities.get(0);
  }

  /**
   * Acquire the sole aggregate PK without a preceding missing-key locking read; existing values and
   * timestamps remain unchanged. A later failure rolls back a newly inserted zero row.
   */
  public void ensureStat(long productId, LocalDateTime time) {
    jdbc.update(
        "INSERT INTO"
            + " product_online_stat(product_id,view_count,search_count,sold_count,hot_score,metric_version,created_at,updated_at)"
            + " VALUES(?,0,0,0,0,0,?,?) ON DUPLICATE KEY UPDATE product_id=product_id",
        productId,
        time,
        time);
  }

  /**
   * Read ledger identity and the optional aggregate together after the caller independently locks
   * control. Existing events ignore aggregate mapping so duplicate/conflict priority is preserved.
   */
  public ProductMetricApplySnapshotDTO applySnapshot(String eventId, long productId) {
    List<ProductMetricApplySnapshotDTO> snapshots =
        jdbc.query(
            "SELECT applied.event_id AS applied_event_id,"
                + " applied.fingerprint AS applied_fingerprint,"
                + " stat.product_id AS stat_product_id,stat.view_count AS stat_view_count,"
                + " stat.search_count AS stat_search_count,stat.sold_count AS stat_sold_count,"
                + " stat.hot_score AS stat_hot_score,stat.metric_version AS stat_metric_version,"
                + " stat.last_event_id AS stat_last_event_id,stat.created_at AS stat_created_at,"
                + " stat.updated_at AS stat_updated_at"
                + " FROM product_metric_control metric_control"
                + " LEFT JOIN product_metric_applied_event applied ON applied.event_id=?"
                + " LEFT JOIN product_online_stat stat ON stat.product_id=?"
                + " WHERE metric_control.singleton_id=1 FOR UPDATE",
            (result, index) -> {
              String appliedEventId = result.getString("applied_event_id");
              if (appliedEventId != null)
                return new ProductMetricApplySnapshotDTO(
                    appliedEventId, result.getString("applied_fingerprint"), null);
              return new ProductMetricApplySnapshotDTO(null, null, readApplyStat(result));
            },
            eventId,
            productId);
    if (snapshots.isEmpty()) throw new IllegalStateException("METRIC_CONTROL_INVALID");
    return snapshots.get(0);
  }

  /** Interpret only an actually present aggregate, preserving original numeric and time values. */
  private static ProductOnlineStatPO readApplyStat(ResultSet result) throws SQLException {
    Object productId = result.getObject("stat_product_id");
    if (productId == null) return null;
    ProductOnlineStatPO stat = new ProductOnlineStatPO();
    stat.productId = ((Number) productId).longValue();
    stat.viewCount = readApplyLong(result, "stat_view_count");
    stat.searchCount = readApplyLong(result, "stat_search_count");
    stat.soldCount = readApplyLong(result, "stat_sold_count");
    stat.hotScore = readApplyLong(result, "stat_hot_score");
    stat.metricVersion = readApplyLong(result, "stat_metric_version");
    stat.lastEventId = result.getString("stat_last_event_id");
    stat.createdAt = readApplyTime(result, "stat_created_at");
    stat.updatedAt = readApplyTime(result, "stat_updated_at");
    return stat;
  }

  /** Match the existing row reader's nullable Long conversion without narrowing BIGINT values. */
  private static Long readApplyLong(ResultSet result, String column) throws SQLException {
    Object value = result.getObject(column);
    return value == null ? null : ((Number) value).longValue();
  }

  /**
   * Match the existing LocalDateTime mapping without introducing timestamp/time-zone conversion.
   */
  private static LocalDateTime readApplyTime(ResultSet result, String column) throws SQLException {
    Object value = result.getObject(column);
    if (value == null) return null;
    return value instanceof LocalDateTime
        ? (LocalDateTime) value
        : LocalDateTime.parse(result.getString(column).replace(' ', 'T'));
  }

  /** Read one aggregate, optionally locking the row already acquired by the apply PK upsert. */
  public ProductOnlineStatPO stat(long productId, boolean lock) {
    return one(
        "SELECT * FROM product_online_stat WHERE product_id=?" + (lock ? " FOR UPDATE" : ""),
        ProductOnlineStatPO.class,
        productId);
  }

  /** Product/sku validation is a consistent read, avoiding a content-lock inversion. */
  public boolean productExists(long productId, Long skuId) {
    Long count =
        skuId == null
            ? jdbc.queryForObject("SELECT COUNT(*) FROM product WHERE id=?", Long.class, productId)
            : jdbc.queryForObject(
                "SELECT COUNT(*) FROM product_sku s JOIN product p ON p.id=s.product_id WHERE"
                    + " s.id=? AND p.id=?",
                Long.class,
                skuId,
                productId);
    return count != null && count == 1;
  }

  /** Insert the first zero aggregate only after validating the source product mapping. */
  public void insertStat(long productId, LocalDateTime time) {
    jdbc.update(
        "INSERT INTO"
            + " product_online_stat(product_id,view_count,search_count,sold_count,hot_score,metric_version,created_at,updated_at)"
            + " VALUES(?,0,0,0,0,0,?,?)",
        productId,
        time,
        time);
  }

  /** Append an applied event and its immutable business fingerprint. */
  public void insertApplied(
      ProductMetricEventDTO event, String fingerprint, long version, LocalDateTime time) {
    jdbc.update(
        "INSERT INTO"
            + " product_metric_applied_event(event_id,fingerprint,event_type,product_id,sku_id,quantity,order_no,source_ref,source_revision,occurred_at,applied_at,metric_version)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        event.eventId,
        fingerprint,
        event.eventType,
        event.productId,
        event.skuId,
        event.quantity,
        event.orderNo,
        event.sourceRef,
        event.sourceRevision,
        java.time.OffsetDateTime.parse(event.occurredAt).toLocalDateTime(),
        time,
        version);
  }

  /**
   * Persist exact integer arithmetic and enqueue that aggregate version in the same transaction.
   */
  public void updateStat(ProductOnlineStatPO stat, String eventId, LocalDateTime time) {
    if (jdbc.update(
            "UPDATE product_online_stat SET"
                + " view_count=?,search_count=?,sold_count=?,hot_score=?,metric_version=?,last_event_id=?,updated_at=?"
                + " WHERE product_id=?",
            stat.viewCount,
            stat.searchCount,
            stat.soldCount,
            stat.hotScore,
            stat.metricVersion,
            eventId,
            time,
            stat.productId)
        != 1) throw new IllegalStateException("METRIC_STAT_LOST");
    jdbc.update(
        "INSERT INTO"
            + " product_metric_projection_outbox(product_id,metric_version,created_at,updated_at,next_retry_at)"
            + " VALUES(?,?,?,?,?)",
        stat.productId,
        stat.metricVersion,
        time,
        time,
        time);
  }

  /** Persist a successful public request's event before allowing its HTTP success. */
  public void register(
      ProductMetricEventDTO event, String fingerprint, byte[] body, LocalDateTime time) {
    jdbc.update(
        "INSERT INTO"
            + " product_metric_outbox(event_id,product_id,event_type,fingerprint,payload_json,next_retry_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,CAST(? AS JSON),?,?,?)",
        event.eventId,
        event.productId,
        event.eventType,
        fingerprint,
        new String(body, java.nio.charset.StandardCharsets.UTF_8),
        time,
        time,
        time);
  }

  /** Claim a bounded batch under one short transaction, without network access. */
  public List<ProductMetricOutboxPO> claim(String owner, LocalDateTime time) {
    List<ProductMetricOutboxPO> rows =
        rows(
            "SELECT * FROM product_metric_outbox WHERE status IN ('PENDING','UNKNOWN') AND"
                + " next_retry_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY"
                + " next_retry_at,event_id LIMIT 50 FOR UPDATE SKIP LOCKED",
            ProductMetricOutboxPO.class,
            time,
            time);
    for (ProductMetricOutboxPO row : rows) {
      if (jdbc.update(
              "UPDATE product_metric_outbox SET lease_owner=?,lease_until=?,updated_at=? WHERE"
                  + " event_id=? AND (lease_until IS NULL OR lease_until<=?)",
              owner,
              time.plusSeconds(30),
              time,
              row.eventId,
              time)
          != 1) throw new IllegalStateException("METRIC_CLAIM_LOST");
      row.leaseOwner = owner;
      row.leaseUntil = time.plusSeconds(30);
    }
    return rows;
  }

  /** Finish only the still-owned unexpired lease; uncertain confirms remain retryable. */
  public int finish(
      String eventId, String owner, boolean success, String error, LocalDateTime time) {
    if (success)
      return jdbc.update(
          "UPDATE product_metric_outbox SET"
              + " status='PUBLISHED',published_at=?,lease_owner=NULL,lease_until=NULL,last_error=NULL,updated_at=?"
              + " WHERE event_id=? AND BINARY lease_owner=BINARY ? AND lease_until>?",
          time,
          time,
          eventId,
          owner,
          time);
    return jdbc.update(
        "UPDATE product_metric_outbox SET status='UNKNOWN',attempts=LEAST(2147483647,CAST(attempts"
            + " AS UNSIGNED)+1),next_retry_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(attempts,8))),?),lease_owner=NULL,lease_until=NULL,last_error=?,updated_at=?"
            + " WHERE event_id=? AND BINARY lease_owner=BINARY ? AND lease_until>?",
        time,
        error,
        time,
        eventId,
        owner,
        time);
  }

  /** Claim at most fifty products and five hundred versions per product. */
  public List<ProductMetricProjectionOutboxPO> claimProjection(String owner, LocalDateTime time) {
    List<Long> products =
        projection.queryForList(
            "SELECT product_id FROM product_metric_projection_outbox WHERE status IN"
                + " ('PENDING','UNKNOWN') AND next_retry_at<=? AND (lease_until IS NULL OR"
                + " lease_until<=?) GROUP BY product_id ORDER BY MIN(next_retry_at),product_id"
                + " LIMIT 50",
            Long.class,
            time,
            time);
    List<ProductMetricProjectionOutboxPO> result = new ArrayList<>();
    for (long product : products) {
      List<ProductMetricProjectionOutboxPO> due =
          projection.query(
              "SELECT * FROM product_metric_projection_outbox WHERE product_id=? AND status IN"
                  + " ('PENDING','UNKNOWN') AND next_retry_at<=? AND (lease_until IS NULL OR"
                  + " lease_until<=?) ORDER BY metric_version LIMIT 500 FOR UPDATE SKIP LOCKED",
              (resultSet, index) -> read(resultSet, ProductMetricProjectionOutboxPO.class),
              product,
              time,
              time);
      for (ProductMetricProjectionOutboxPO row : due) {
        if (jdbc.update(
                "UPDATE product_metric_projection_outbox SET"
                    + " lease_owner=?,lease_until=?,updated_at=? WHERE product_id=? AND"
                    + " metric_version=? AND (lease_until IS NULL OR lease_until<=?)",
                owner,
                time.plusSeconds(30),
                time,
                row.productId,
                row.metricVersion,
                time)
            != 1) throw new IllegalStateException("PROJECTION_CLAIM_LOST");
        row.leaseOwner = owner;
        row.leaseUntil = time.plusSeconds(30);
        result.add(row);
      }
    }
    return result;
  }

  /** A successful refresh may settle only versions actually covered by the sent snapshot. */
  public int finishProjection(
      long product, long version, String owner, boolean success, LocalDateTime time) {
    return success
        ? jdbc.update(
            "UPDATE product_metric_projection_outbox SET"
                + " status='DONE',lease_owner=NULL,lease_until=NULL,last_error=NULL,updated_at=?"
                + " WHERE product_id=? AND metric_version<=? AND BINARY lease_owner=BINARY ? AND"
                + " lease_until>?",
            time,
            product,
            version,
            owner,
            time)
        : jdbc.update(
            "UPDATE product_metric_projection_outbox SET"
                + " status='UNKNOWN',attempts=LEAST(2147483647,CAST(attempts AS"
                + " UNSIGNED)+1),next_retry_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(attempts,8))),?),lease_owner=NULL,lease_until=NULL,last_error='PROJECTION_UNCERTAIN',updated_at=?"
                + " WHERE product_id=? AND BINARY lease_owner=BINARY ? AND lease_until>?",
            time,
            time,
            product,
            owner,
            time);
  }

  /** Save raw evidence once; an existing quarantine is never silently resolved or rewritten. */
  public boolean quarantine(
      String id,
      String queue,
      String sha,
      byte[] raw,
      boolean complete,
      String eventId,
      String reason,
      long length) {
    if (jdbc.queryForObject(
            "SELECT COUNT(*) FROM product_metric_quarantine WHERE queue_name=? AND"
                + " payload_sha256=?",
            Long.class,
            queue,
            sha)
        > 0) return false;
    jdbc.update(
        "INSERT INTO"
            + " product_metric_quarantine(quarantine_id,queue_name,payload_sha256,payload_size,raw_prefix,raw_complete,event_id,reason,created_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?)",
        id,
        queue,
        sha,
        length,
        raw,
        complete ? 1 : 0,
        eventId,
        reason,
        now());
    return true;
  }

  /**
   * Pending evidence closes online ES admission, including legacy content awaiting consumer ACK.
   */
  public boolean pending() {
    return admission.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM product_metric_projection_outbox WHERE status IN"
            + " ('PENDING','UNKNOWN') LIMIT 1) OR EXISTS(SELECT 1 FROM product_metric_quarantine"
            + " WHERE status='OPEN' LIMIT 1) OR EXISTS(SELECT 1 FROM product_index_outbox WHERE"
            + " status IN ('PENDING','UNKNOWN') LIMIT 1) OR EXISTS(SELECT 1 FROM"
            + " product_index_outbox WHERE status='PUBLISHED' AND ack_deadline_at IS NOT NULL LIMIT"
            + " 1)",
        Boolean.class);
  }

  /** Return a bounded batch of current metric facts for ES page admission. */
  public Map<Long, ProductOnlineStatPO> stats(List<Long> ids) {
    if (ids.size() > 100) throw new IllegalArgumentException("METRIC_PAGE_LIMIT");
    Map<Long, ProductOnlineStatPO> result = new HashMap<>();
    if (ids.isEmpty()) return result;
    for (ProductOnlineStatPO row :
        rows(
            "SELECT * FROM product_online_stat WHERE product_id IN ("
                + String.join(",", Collections.nCopies(ids.size(), "?"))
                + ")",
            ProductOnlineStatPO.class,
            ids.toArray())) result.put(row.productId, row);
    return result;
  }

  /** Validate the reviewed migration without repairing or resetting existing business tables. */
  public void verifySchema() {
    verifyReviewedSchema();
    ProductMetricControlPO row = control(false);
    if (row == null || !"cm21-online-v1".equals(row.seriesId) || row.revision < 0)
      throw new IllegalStateException("METRIC_CONTROL_INVALID");
  }

  private void verifyReviewedSchema() {
    // Generated exact columns/index/check contracts are inserted below; no DDL runs at startup.
    verifyTables();
  }

  private <T> T one(String sql, Class<T> type, Object... arguments) {
    List<T> result = rows(sql, type, arguments);
    return result.isEmpty() ? null : result.get(0);
  }

  private <T> List<T> rows(String sql, Class<T> type, Object... arguments) {
    return jdbc.query(sql, (result, index) -> read(result, type), arguments);
  }

  private static <T> T read(ResultSet result, Class<T> type) throws SQLException {
    try {
      T value = type.getDeclaredConstructor().newInstance();
      for (Field field : type.getFields()) {
        String column =
            field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        Object cell = result.getObject(column);
        if (cell == null) continue;
        if (field.getType() == Long.class) cell = ((Number) cell).longValue();
        else if (field.getType() == Integer.class) cell = ((Number) cell).intValue();
        else if (field.getType() == LocalDateTime.class && !(cell instanceof LocalDateTime))
          cell = LocalDateTime.parse(result.getString(column).replace(' ', 'T'));
        field.set(value, cell);
      }
      return value;
    } catch (ReflectiveOperationException failure) {
      throw new SQLException("METRIC_ROW_MAPPING", failure);
    }
  }

  private static LocalDateTime now() {
    return LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai"))
        .truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
  }

  private void verifyTables() {
    verifyTable(
        "product_metric_control",
        List.of(
            "singleton_id|tinyint|NO|-|-",
            "series_id|varchar(32)|NO|ascii|ascii_bin",
            "revision|bigint|NO|-|-",
            "started_at|datetime(3)|NO|-|-",
            "index_uuid|varchar(64)|YES|ascii|ascii_bin",
            "index_ready|tinyint|NO|-|-",
            "updated_at|datetime(3)|NO|-|-"),
        Set.of("PRIMARY|0|singleton_id"),
        List.of("singleton_id=1 AND revision>=0 AND index_ready IN (0,1)"));
    verifyTable(
        "product_online_stat",
        List.of(
            "product_id|bigint|NO|-|-",
            "view_count|bigint|NO|-|-",
            "search_count|bigint|NO|-|-",
            "sold_count|bigint|NO|-|-",
            "hot_score|bigint|NO|-|-",
            "metric_version|bigint|NO|-|-",
            "last_event_id|char(64)|YES|ascii|ascii_bin",
            "created_at|datetime(3)|NO|-|-",
            "updated_at|datetime(3)|NO|-|-"),
        Set.of("PRIMARY|0|product_id"),
        List.of(
            "product_id>0 AND view_count>=0 AND search_count>=0 AND sold_count>=0 AND hot_score>=0"
                + " AND metric_version>=0"));
    verifyTable(
        "product_metric_applied_event",
        List.of(
            "event_id|char(64)|NO|ascii|ascii_bin",
            "fingerprint|char(64)|NO|ascii|ascii_bin",
            "event_type|varchar(32)|NO|ascii|ascii_bin",
            "product_id|bigint|NO|-|-",
            "sku_id|bigint|YES|-|-",
            "quantity|int|NO|-|-",
            "order_no|varchar(64)|YES|utf8mb4|utf8mb4_bin",
            "source_ref|varchar(160)|NO|utf8mb4|utf8mb4_bin",
            "source_revision|bigint|YES|-|-",
            "occurred_at|datetime(3)|NO|-|-",
            "applied_at|datetime(3)|NO|-|-",
            "metric_version|bigint|NO|-|-"),
        Set.of("PRIMARY|0|event_id", "uk_metric_applied_version|0|product_id,metric_version"),
        List.of(
            "product_id>0 AND quantity>0 AND metric_version>0 AND (sku_id IS NULL OR sku_id>0) AND"
                + " (source_revision IS NULL OR source_revision>=0)"));
    verifyTable(
        "product_metric_outbox",
        List.of(
            "event_id|char(64)|NO|ascii|ascii_bin",
            "product_id|bigint|NO|-|-",
            "event_type|varchar(32)|NO|ascii|ascii_bin",
            "fingerprint|char(64)|NO|ascii|ascii_bin",
            "payload_json|json|NO|-|-",
            "status|varchar(16)|NO|ascii|ascii_bin",
            "attempts|int|NO|-|-",
            "next_retry_at|datetime(3)|NO|-|-",
            "lease_owner|varchar(64)|YES|ascii|ascii_bin",
            "lease_until|datetime(3)|YES|-|-",
            "published_at|datetime(3)|YES|-|-",
            "last_error|varchar(64)|YES|ascii|ascii_bin",
            "created_at|datetime(3)|NO|-|-",
            "updated_at|datetime(3)|NO|-|-"),
        Set.of(
            "PRIMARY|0|event_id",
            "idx_metric_outbox_due|1|status,next_retry_at,lease_until,event_id"),
        List.of("product_id>0 AND attempts>=0 AND status IN ('PENDING','UNKNOWN','PUBLISHED')"));
    verifyTable(
        "product_metric_projection_outbox",
        List.of(
            "product_id|bigint|NO|-|-",
            "metric_version|bigint|NO|-|-",
            "created_at|datetime(3)|NO|-|-",
            "updated_at|datetime(3)|NO|-|-",
            "status|varchar(16)|NO|ascii|ascii_bin",
            "attempts|int|NO|-|-",
            "next_retry_at|datetime(3)|NO|-|-",
            "lease_owner|varchar(64)|YES|ascii|ascii_bin",
            "lease_until|datetime(3)|YES|-|-",
            "last_error|varchar(64)|YES|ascii|ascii_bin"),
        Set.of(
            "PRIMARY|0|product_id,metric_version",
            "idx_metric_projection_due|1|status,next_retry_at,lease_until,product_id"),
        List.of(
            "product_id>0 AND metric_version>0 AND attempts>=0 AND status IN"
                + " ('PENDING','UNKNOWN','DONE')"));
    verifyTable(
        "product_metric_quarantine",
        List.of(
            "quarantine_id|char(64)|NO|ascii|ascii_bin",
            "queue_name|varchar(160)|NO|utf8mb4|utf8mb4_bin",
            "payload_sha256|char(64)|NO|ascii|ascii_bin",
            "payload_size|bigint|NO|-|-",
            "raw_prefix|mediumblob|NO|-|-",
            "raw_complete|tinyint|NO|-|-",
            "event_id|char(64)|YES|ascii|ascii_bin",
            "reason|varchar(64)|NO|ascii|ascii_bin",
            "status|varchar(16)|NO|ascii|ascii_bin",
            "created_at|datetime(3)|NO|-|-"),
        Set.of(
            "PRIMARY|0|quarantine_id",
            "uk_metric_quarantine_payload|0|queue_name,payload_sha256",
            "idx_metric_quarantine_open|1|status,created_at"),
        List.of("payload_size>=0 AND raw_complete IN (0,1) AND status IN ('OPEN','RESOLVED')"));
  }

  private void verifyTable(
      String table,
      List<String> expectedColumns,
      Set<String> expectedIndexes,
      List<String> expectedChecks) {
    List<String> actual =
        jdbc.query(
            "SELECT"
                + " column_name,column_type,is_nullable,character_set_name,collation_name,datetime_precision"
                + " FROM information_schema.columns WHERE table_schema=DATABASE() AND table_name=?"
                + " ORDER BY ordinal_position",
            (row, index) -> {
              String type =
                  row.getString(2)
                      .toLowerCase(Locale.ROOT)
                      .replace("bigint(20)", "bigint")
                      .replace("int(11)", "int")
                      .replace("tinyint(4)", "tinyint");
              return row.getString(1)
                  + "|"
                  + type
                  + "|"
                  + row.getString(3)
                  + "|"
                  + Objects.toString(row.getString(4), "-")
                  + "|"
                  + Objects.toString(row.getString(5), "-");
            },
            table);
    if (!expectedColumns.equals(actual))
      throw new IllegalStateException("METRIC_SCHEMA_COLUMNS_" + table);
    Set<String> indexes =
        new HashSet<>(
            jdbc.query(
                "SELECT index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index"
                    + " SEPARATOR ',') FROM information_schema.statistics WHERE"
                    + " table_schema=DATABASE() AND table_name=? GROUP BY index_name,non_unique",
                (row, index) -> row.getString(1) + "|" + row.getInt(2) + "|" + row.getString(3),
                table));
    if (!expectedIndexes.equals(indexes))
      throw new IllegalStateException("METRIC_SCHEMA_INDEXES_" + table);
    List<String> checks =
        jdbc.query(
            "SELECT c.check_clause FROM information_schema.check_constraints c JOIN"
                + " information_schema.table_constraints t ON"
                + " c.constraint_schema=t.constraint_schema AND c.constraint_name=t.constraint_name"
                + " WHERE t.table_schema=DATABASE() AND t.table_name=? AND"
                + " t.constraint_type='CHECK' AND t.enforced='YES'",
            (row, index) -> normalizeCheck(row.getString(1)),
            table);
    if (!new HashSet<>(checks)
        .equals(
            new HashSet<>(
                expectedChecks.stream().map(ProductMetricMapper::normalizeCheck).toList())))
      throw new IllegalStateException("METRIC_SCHEMA_CHECKS_" + table);
    String kind =
        jdbc.queryForObject(
            "SELECT CONCAT(table_type,'|',engine,'|',SUBSTRING_INDEX(table_collation,'_',1)) FROM"
                + " information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
            String.class,
            table);
    if (!"BASE TABLE|InnoDB|utf8mb4".equals(kind))
      throw new IllegalStateException("METRIC_SCHEMA_BASE_TABLE_" + table);
  }

  private static String normalizeCheck(String value) {
    return value
        .toLowerCase(Locale.ROOT)
        .replace("_ascii", "")
        .replace("_utf8mb4", "")
        .replace("\\'", "'")
        .replaceAll("[\\s`()]+", "");
  }

  /** The original rebuild cursor must be completed for the configured physical generation. */
  public boolean jobComplete() {
    return admission.queryForObject(
        "SELECT EXISTS(SELECT 1 FROM product_index_rebuild_job WHERE"
            + " job_id='cloudmall-product-index' AND status='COMPLETED' LIMIT 1)",
        Boolean.class);
  }
}
