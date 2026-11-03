package com.cloudmall.order.mapper;

import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.util.OrderJdbcLocalDateTime;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/**
 * Explicit monthly logical adapter; inherited ledgers retain the original SQL and JDBC identity.
 */
public final class ShardingOrderSqlMapper extends OrderSqlMapper {
  /** Selected business JDBC, also passed unchanged to the inherited ledger mapper. */
  private final JdbcTemplate jdbc;

  /** Defensive immutable copy of the nodes published at context construction. */
  private final Set<String> publishedMonths;

  /** Eight original result keys; temporal components survive Sharding materialization as CHAR. */
  private static final String COLUMNS =
      "order_no,user_id,status,total_amount,pay_amount,address_snapshot,CAST(created_at AS CHAR) AS"
          + " cm_local_created_at,CAST(expire_at AS CHAR) AS cm_local_expire_at";

  /** Reconstitutes the original eight map keys without a Timestamp conversion. */
  private static final RowMapper<Map<String, Object>> ORDER_ROW =
      (row, index) -> {
        Map<String, Object> values = new LinkedHashMap<>();
        for (String column :
            List.of(
                "order_no", "user_id", "status", "total_amount", "pay_amount", "address_snapshot"))
          values.put(column, row.getObject(column));
        values.put("created_at", OrderJdbcLocalDateTime.readRequired(row, "cm_local_created_at"));
        values.put("expire_at", OrderJdbcLocalDateTime.readNullable(row, "cm_local_expire_at"));
        return values;
      };

  /** Creates the sole logical mapper for the already published immutable snapshot. */
  public ShardingOrderSqlMapper(JdbcTemplate jdbc, Collection<String> publishedMonths) {
    super(jdbc);
    this.jdbc = Objects.requireNonNull(jdbc);
    this.publishedMonths = Set.copyOf(publishedMonths);
    if (this.publishedMonths.isEmpty())
      throw new IllegalArgumentException("ORDER_SHARD_EMPTY_SNAPSHOT");
    this.publishedMonths.forEach(ShardingOrderSqlMapper::parseMonth);
  }

  /** Rejects DDL on the business routing connection. */
  @Override
  public void ensureMonthlyCompanionTemplates() {
    throw new IllegalStateException("ORDER_SHARD_USE_PHYSICAL_MAINTENANCE");
  }

  /** Rejects DDL on the business routing connection. */
  @Override
  public void createMonthlyOrderTables(String suffix) {
    throw new IllegalStateException("ORDER_SHARD_USE_PHYSICAL_MAINTENANCE");
  }

  /** Persists all original main PO fields through the logical table. */
  @Override
  public int insertOrder(String tableName, OrderPO order) {
    // 1. Reject inconsistent identity before executing SQL.
    if (order == null || order.createdAt == null) throw invalid();
    requireIdentity(tableName, "mall_order", order.orderNo, order.createdAt);
    // 2. Keep the original ten bindings and their order.
    // 3. Return the original affected-row count from the selected business JDBC.
    return jdbc.update(
        "insert into"
            + " mall_order(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?, ?,?,?)",
        order.id,
        order.orderNo,
        order.userId,
        order.status,
        order.totalAmount,
        order.payAmount,
        order.addressSnapshot,
        order.expireAt,
        order.createdAt,
        order.updatedAt);
  }

  /** Persists all original item PO fields through the logical table. */
  @Override
  public int insertOrderItem(String tableName, OrderItemPO item) {
    // 1. Validate the same original identity and local creation month.
    if (item == null || item.createdAt == null) throw invalid();
    requireIdentity(tableName, "mall_order_item", item.orderNo, item.createdAt);
    // 2. Preserve all eleven original bindings.
    // 3. Return the original affected-row count from the selected business JDBC.
    return jdbc.update(
        "insert into"
            + " mall_order_item(id,order_id,order_no,product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount,created_at)"
            + " values(?,?,?,?,?,?,?,?,?,?,?)",
        item.id,
        item.orderId,
        item.orderNo,
        item.productId,
        item.skuId,
        item.productNameSnapshot,
        item.skuSnapshot,
        item.unitPrice,
        item.quantity,
        item.lineAmount,
        item.createdAt);
  }

  /** Reads the internal ID within the original number's half-open month. */
  @Override
  public Long findOrderId(String tableName, String orderNo) {
    // 1. Validate the original number and the published monthly hint.
    YearMonth month = requireIdentity(tableName, "mall_order", orderNo, null);
    // 2. Retain the original fields and parameter order with two local month bounds.
    // 3. Return the original JDBC result without fallback or exception translation.
    return jdbc.queryForObject(
        "select id from mall_order where order_no=? and created_at>=? and created_at<?",
        Long.class,
        orderNo,
        month.atDay(1).atStartOfDay(),
        month.plusMonths(1).atDay(1).atStartOfDay());
  }

  /** Reads original main fields while preserving DATETIME local components. */
  @Override
  public List<Map<String, Object>> findOrder(String tableName, String orderNo) {
    // 1. Validate the original number and the published monthly hint.
    YearMonth month = requireIdentity(tableName, "mall_order", orderNo, null);
    // 2. Retain the original fields and parameter order with two local month bounds.
    // 3. Return the original JDBC result without fallback or exception translation.
    return jdbc.query(
        "select "
            + COLUMNS
            + " from mall_order where order_no=? and created_at>=? and created_at<?",
        ORDER_ROW,
        orderNo,
        month.atDay(1).atStartOfDay(),
        month.plusMonths(1).atDay(1).atStartOfDay());
  }

  /** Reads original item fields in the original generated-ID order. */
  @Override
  public List<Map<String, Object>> findOrderItems(String tableName, String orderNo) {
    // 1. Validate the original number and the published monthly hint.
    YearMonth month = requireIdentity(tableName, "mall_order_item", orderNo, null);
    // 2. Retain the original fields and parameter order with two local month bounds.
    // 3. Return the original JDBC result without fallback or exception translation.
    return jdbc.queryForList(
        "select"
            + " product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount"
            + " from mall_order_item where order_no=? and created_at>=? and created_at<? order by"
            + " id",
        orderNo,
        month.atDay(1).atStartOfDay(),
        month.plusMonths(1).atDay(1).atStartOfDay());
  }

  /** Preserves absent/exact/ambiguous existence semantics; a missing node is never absence. */
  @Override
  public boolean orderExists(String tableName, String orderNo) {
    // 1. Query collation candidates within the validated, published original month.
    YearMonth month = requireIdentity(tableName, "mall_order", orderNo, null);
    List<Map<String, Object>> candidates =
        jdbc.queryForList(
            "select order_no from mall_order where order_no=? and created_at>=? and created_at<?",
            orderNo,
            month.atDay(1).atStartOfDay(),
            month.plusMonths(1).atDay(1).atStartOfDay());
    // 2. Only an empty successful query proves absence.
    if (candidates.isEmpty()) return false;
    // 3. Preserve exact unique identity rather than filtering aliases to absence.
    if (candidates.size() != 1
        || !(candidates.get(0).get("order_no") instanceof String)
        || !Objects.equals(orderNo, candidates.get(0).get("order_no")))
      throw new IllegalStateException("订单存在性候选身份与原意图不一致");
    return true;
  }

  /** Counts only the intersection of this hinted month and the original global window. */
  @Override
  public long countOrders(
      String tableName, long userId, Object startTime, Object endTime, String status) {
    // 1. Intersect the original global window with this one validated month.
    List<Object> arguments = window(tableName, userId, startTime, endTime, status);
    // 2. Empty intersections execute no SQL.
    if (arguments.isEmpty()) return 0;
    // 3. Query only this intersection, retaining status and count failure semantics.
    Long count =
        jdbc.queryForObject(
            "select count(*) from mall_order" + condition(status), Long.class, arguments.toArray());
    if (count == null) throw new IllegalStateException("ORDER_SHARD_COUNT_NULL");
    return count;
  }

  /** Reads the original bounded page from this month's intersection without rebroadcasting it. */
  @Override
  public List<Map<String, Object>> findOrderPage(
      String tableName,
      long userId,
      Object startTime,
      Object endTime,
      String status,
      long offset,
      int limit) {
    if (offset < 0 || limit <= 0) throw new IllegalArgumentException("订单分页参数无效");
    // 1. Intersect the original global window with this one validated month.
    List<Object> arguments = window(tableName, userId, startTime, endTime, status);
    // 2. Empty intersections execute no SQL.
    if (arguments.isEmpty()) return List.of();
    // 3. Retain the original ordering, bounded limit and offset.
    arguments.add(limit);
    arguments.add(offset);
    return jdbc.query(
        "select "
            + COLUMNS
            + " from mall_order"
            + condition(status)
            + " order by created_at desc,order_no desc limit ? offset ?",
        ORDER_ROW,
        arguments.toArray());
  }

  /** Preserves the original IF CAS, eight original parameters and two original-month bounds. */
  @Override
  public int updateStatus(
      String tableName,
      String orderNo,
      String targetStatus,
      String currentStatus,
      Object updatedAt) {
    // 1. Validate the original number and the published monthly hint.
    YearMonth month = requireIdentity(tableName, "mall_order", orderNo, null);
    // 2. Retain the original fields and parameter order with two local month bounds.
    // 3. Return the original JDBC result without fallback or exception translation.
    return jdbc.update(
        "update mall_order set"
            + " status=?,updated_at=?,paid_at=IF(?='PAID',?,paid_at),cancelled_at=IF(?='CANCELLED',?,cancelled_at)"
            + " where order_no=? and status=? and created_at>=? and created_at<?",
        targetStatus,
        updatedAt,
        targetStatus,
        updatedAt,
        targetStatus,
        updatedAt,
        orderNo,
        currentStatus,
        month.atDay(1).atStartOfDay(),
        month.plusMonths(1).atDay(1).atStartOfDay());
  }

  /** Rejects malformed hints separately from valid but unpublished months. */
  private YearMonth hint(String tableName, String logicalTable) {
    String prefix = logicalTable + "_";
    if (tableName == null || !tableName.matches(prefix + "[0-9]{6}")) throw invalid();
    return parseMonth(tableName.substring(prefix.length()));
  }

  /** Validates input identity first, then rejects unavailable published nodes as a DB failure. */
  private YearMonth requireIdentity(
      String tableName, String logicalTable, String orderNo, LocalDateTime createdAt) {
    YearMonth month = hint(tableName, logicalTable);
    if (orderNo == null
        || !orderNo.matches("[0-9]{6}[A-Za-z0-9-]+")
        || !month.equals(parseMonth(orderNo.substring(0, 6)))
        || (createdAt != null && !month.equals(YearMonth.from(createdAt)))) throw invalid();
    requirePublished(month);
    return month;
  }

  /** Computes a half-open intersection before selecting SQL or bindings. */
  private List<Object> window(
      String tableName, long userId, Object startTime, Object endTime, String status) {
    YearMonth month = hint(tableName, "mall_order");
    LocalDateTime start = local(startTime), end = local(endTime);
    LocalDateTime monthStart = month.atDay(1).atStartOfDay(),
        monthEnd = month.plusMonths(1).atDay(1).atStartOfDay();
    if (start.isBefore(monthStart)) start = monthStart;
    if (end.isAfter(monthEnd)) end = monthEnd;
    if (!start.isBefore(end)) return new ArrayList<>();
    requirePublished(month);
    List<Object> arguments = new ArrayList<>(List.of(userId, start, end));
    if (status != null) arguments.add(status);
    return arguments;
  }

  /** Only existing local component input types are accepted. */
  private static LocalDateTime local(Object value) {
    if (value instanceof LocalDateTime local) return local;
    if (value instanceof Timestamp timestamp) return timestamp.toLocalDateTime();
    throw invalid();
  }

  /** Published membership is not replaced with a physical fallback. */
  private void requirePublished(YearMonth month) {
    if (!publishedMonths.contains(
        String.format(java.util.Locale.ROOT, "%04d%02d", month.getYear(), month.getMonthValue())))
      throw new DataAccessResourceFailureException("ORDER_SHARD_MONTH_NOT_PUBLISHED");
  }

  /** Validates the database's original six-digit year/month range. */
  private static YearMonth parseMonth(String suffix) {
    try {
      if (suffix == null || !suffix.matches("[1-9][0-9]{3}(0[1-9]|1[0-2])")) throw invalid();
      return YearMonth.of(
          Integer.parseInt(suffix.substring(0, 4)), Integer.parseInt(suffix.substring(4)));
    } catch (java.time.DateTimeException failure) {
      throw invalid();
    }
  }

  private static String condition(String status) {
    return " where user_id=? and created_at>=? and created_at<?"
        + (status == null ? "" : " and status=?");
  }

  private static IllegalArgumentException invalid() {
    return new IllegalArgumentException("ORDER_SHARD_IDENTITY_INVALID");
  }
}
