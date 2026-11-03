package com.cloudmall.stock.mapper;

import com.cloudmall.stock.domain.dto.StockSalesEventDTO;
import com.cloudmall.stock.domain.po.StockSalesOutboxPO;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.*;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** Atomic sale proofs and leased outbox SQL using the original Stock JDBC template. */
@Repository
@ConditionalOnProperty(name = "cloudmall.stock.sales-metrics.enabled", havingValue = "true")
public class StockSalesOutboxMapper {
  /** Original Stock JDBC resource, never a separately committed metrics connection. */
  private final JdbcTemplate jdbc;

  /** Construct the mapper on the existing business JDBC template. */
  public StockSalesOutboxMapper(JdbcTemplate jdbc) {
    this.jdbc = jdbc;
  }

  /** Resource identity used by enabled-mode startup admission. */
  public javax.sql.DataSource dataSource() {
    return jdbc.getDataSource();
  }

  /**
   * Read raw original identities after the caller has locked the SKU; CI aggregation is
   * insufficient.
   */
  public List<Map<String, Object>> flowProof(String orderNo, long skuId) {
    return jdbc.queryForList(
        "SELECT order_no,sku_id,flow_type,quantity,idempotency_key FROM stock_flow WHERE order_no=?"
            + " AND sku_id=? ORDER BY id",
        orderNo,
        skuId);
  }

  /** Insert immutable sale bytes inside the caller's actual confirmation transaction. */
  public void insert(
      StockSalesEventDTO event, String fingerprint, String body, LocalDateTime time) {
    jdbc.update(
        "INSERT INTO"
            + " stock_sales_outbox(event_id,product_id,event_type,fingerprint,payload_json,next_retry_at,created_at,updated_at,sku_id,order_no,quantity,source_ref,source_revision)"
            + " VALUES(?,?,?,?,CAST(? AS JSON),?,?,?,?,?,?,?,?)",
        event.eventId,
        event.productId,
        event.eventType,
        fingerprint,
        body,
        time,
        time,
        time,
        event.skuId,
        event.orderNo,
        event.quantity,
        event.sourceRef,
        event.sourceRevision);
  }

  /** Claim at most fifty due identities in one short transaction. */
  public List<StockSalesOutboxPO> claim(String owner, LocalDateTime time) {
    List<StockSalesOutboxPO> rows =
        jdbc.query(
            "SELECT * FROM stock_sales_outbox WHERE status IN ('PENDING','UNKNOWN') AND"
                + " next_retry_at<=? AND (lease_until IS NULL OR lease_until<=?) ORDER BY"
                + " next_retry_at,event_id LIMIT 50 FOR UPDATE SKIP LOCKED",
            StockSalesOutboxMapper::read,
            time,
            time);
    for (StockSalesOutboxPO row : rows) {
      if (jdbc.update(
              "UPDATE stock_sales_outbox SET lease_owner=?,lease_until=?,updated_at=? WHERE"
                  + " event_id=? AND (lease_until IS NULL OR lease_until<=?)",
              owner,
              time.plusSeconds(30),
              time,
              row.eventId,
              time)
          != 1) throw new IllegalStateException("SALES_CLAIM_LOST");
      row.leaseOwner = owner;
      row.leaseUntil = time.plusSeconds(30);
    }
    return rows;
  }

  /** Settle only an exact unexpired owner; uncertain delivery keeps the same identity/body. */
  public int finish(
      String eventId, String owner, boolean success, String error, LocalDateTime time) {
    if (success)
      return jdbc.update(
          "UPDATE stock_sales_outbox SET"
              + " status='PUBLISHED',published_at=?,lease_owner=NULL,lease_until=NULL,last_error=NULL,updated_at=?"
              + " WHERE event_id=? AND BINARY lease_owner=BINARY ? AND lease_until>?",
          time,
          time,
          eventId,
          owner,
          time);
    return jdbc.update(
        "UPDATE stock_sales_outbox SET status='UNKNOWN',attempts=LEAST(2147483647,CAST(attempts AS"
            + " UNSIGNED)+1),next_retry_at=TIMESTAMPADD(SECOND,LEAST(300,POW(2,LEAST(attempts,8))),?),lease_owner=NULL,lease_until=NULL,last_error=?,updated_at=?"
            + " WHERE event_id=? AND BINARY lease_owner=BINARY ? AND lease_until>?",
        time,
        error,
        time,
        eventId,
        owner,
        time);
  }

  /** Reject an incompatible migration without repairing, clearing, or resetting existing data. */
  public void verifySchema() {
    verifyTables();
  }

  private static StockSalesOutboxPO read(ResultSet result, int index) throws SQLException {
    try {
      StockSalesOutboxPO row = new StockSalesOutboxPO();
      for (java.lang.reflect.Field field : StockSalesOutboxPO.class.getFields()) {
        String column =
            field.getName().replaceAll("([a-z])([A-Z])", "$1_$2").toLowerCase(Locale.ROOT);
        Object value = result.getObject(column);
        if (value == null) continue;
        if (field.getType() == Long.class) value = ((Number) value).longValue();
        else if (field.getType() == Integer.class) value = ((Number) value).intValue();
        else if (field.getType() == LocalDateTime.class)
          value = LocalDateTime.parse(result.getString(column).replace(' ', 'T'));
        field.set(row, value);
      }
      return row;
    } catch (ReflectiveOperationException failure) {
      throw new SQLException("SALES_ROW_MAPPING", failure);
    }
  }

  private void verifyTables() {
    verifyTable(
        "stock_sales_outbox",
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
            "updated_at|datetime(3)|NO|-|-",
            "sku_id|bigint|NO|-|-",
            "order_no|varchar(64)|NO|utf8mb4|utf8mb4_bin",
            "quantity|int|NO|-|-",
            "source_ref|varchar(160)|NO|utf8mb4|utf8mb4_bin",
            "source_revision|bigint|NO|-|-"),
        Set.of(
            "PRIMARY|0|event_id",
            "uk_stock_sales_identity|0|order_no,sku_id",
            "idx_stock_sales_due|1|status,next_retry_at,lease_until,event_id"),
        List.of(
            "product_id>0 AND sku_id>0 AND quantity>0 AND source_revision>=0 AND attempts>=0 AND"
                + " status IN ('PENDING','UNKNOWN','PUBLISHED')"));
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
      throw new IllegalStateException("SALES_SCHEMA_COLUMNS_" + table);
    Set<String> indexes =
        new HashSet<>(
            jdbc.query(
                "SELECT index_name,non_unique,GROUP_CONCAT(column_name ORDER BY seq_in_index"
                    + " SEPARATOR ',') FROM information_schema.statistics WHERE"
                    + " table_schema=DATABASE() AND table_name=? GROUP BY index_name,non_unique",
                (row, index) -> row.getString(1) + "|" + row.getInt(2) + "|" + row.getString(3),
                table));
    if (!expectedIndexes.equals(indexes))
      throw new IllegalStateException("SALES_SCHEMA_INDEXES_" + table);
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
                expectedChecks.stream().map(StockSalesOutboxMapper::normalizeCheck).toList())))
      throw new IllegalStateException("SALES_SCHEMA_CHECKS_" + table);
    String kind =
        jdbc.queryForObject(
            "SELECT CONCAT(table_type,'|',engine,'|',SUBSTRING_INDEX(table_collation,'_',1)) FROM"
                + " information_schema.tables WHERE table_schema=DATABASE() AND table_name=?",
            String.class,
            table);
    if (!"BASE TABLE|InnoDB|utf8mb4".equals(kind))
      throw new IllegalStateException("SALES_SCHEMA_BASE_TABLE_" + table);
  }

  private static String normalizeCheck(String value) {
    return value
        .toLowerCase(Locale.ROOT)
        .replace("_ascii", "")
        .replace("_utf8mb4", "")
        .replace("\\'", "'")
        .replaceAll("[\\s`()]+", "");
  }
}
