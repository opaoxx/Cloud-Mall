package com.cloudmall.order.mapper;

import com.cloudmall.order.domain.po.OrderIdempotencyPO;
import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderPO;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 订单动态分片和兼容性 SQL 的统一持久化入口。 */
@Repository
public class OrderSqlMapper {
  /** Spring JDBC 执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建订单 SQL Mapper。 */
  public OrderSqlMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 执行订单查询。 */
  private <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 使用参数数组执行订单查询。 */
  private <T> List<T> query(String sql, Object[] arguments, RowMapper<T> rowMapper) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 查询订单单值。 */
  private <T> T queryForObject(String sql, Class<T> resultType, Object... arguments) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  /** 查询订单行集合。 */
  private List<Map<String, Object>> queryForList(String sql, Object... arguments) {
    return jdbcTemplate.queryForList(sql, arguments);
  }

  /** 执行订单写操作。 */
  private int update(String sql, Object... arguments) {
    return jdbcTemplate.update(sql, arguments);
  }

  /** 执行订单 DDL。 */
  public void execute(String sql) {
    jdbcTemplate.execute(sql);
  }

  /** 查询用户幂等键已关联的订单号。 */
  public List<String> findIdempotentOrderNos(Long userId, String idempotencyKey) {
    return query(
        "select order_no from order_idempotency where user_id=? and idempotency_key=?",
        (resultSet, rowNumber) -> resultSet.getString(1),
        userId,
        idempotencyKey);
  }

  /** 保存订单主表记录。 */
  public int insertOrder(String tableName, OrderPO order) {
    return update(
        "insert into "
            + tableName
            + "(id,order_no,user_id,status,total_amount,pay_amount,address_snapshot,expire_at,created_at,updated_at)"
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

  /** 按订单号读取订单内部主键。 */
  public Long findOrderId(String tableName, String orderNo) {
    return queryForObject("select id from " + tableName + " where order_no=?", Long.class, orderNo);
  }

  /** 保存订单明细快照。 */
  public int insertOrderItem(String tableName, OrderItemPO item) {
    return update(
        "insert into "
            + tableName
            + "(id,order_id,order_no,product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount,created_at)"
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

  /** 保存订单创建幂等记录。 */
  public int insertIdempotency(OrderIdempotencyPO idempotency) {
    return update(
        "insert into order_idempotency(id,user_id,idempotency_key,order_no,created_at)"
            + " values(?,?,?,?,?)",
        idempotency.id,
        idempotency.userId,
        idempotency.idempotencyKey,
        idempotency.orderNo,
        idempotency.createdAt);
  }

  /** 按用户、时间和状态查询订单摘要。 */
  public List<Map<String, Object>> findOrders(
      String tableName, long userId, Object startTime, Object endTime, String status) {
    String condition = " where user_id=? and created_at>=? and created_at<?";
    List<Object> arguments = new java.util.ArrayList<>(List.of(userId, startTime, endTime));
    if (status != null) {
      condition += " and status=?";
      arguments.add(status);
    }
    return queryForList(
        "select"
            + " order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,expire_at"
            + " from "
            + tableName
            + condition,
        arguments.toArray());
  }

  /** 按订单号查询订单主表。 */
  public List<Map<String, Object>> findOrder(String tableName, String orderNo) {
    return queryForList(
        "select"
            + " order_no,user_id,status,total_amount,pay_amount,address_snapshot,created_at,expire_at"
            + " from "
            + tableName
            + " where order_no=?",
        orderNo);
  }

  /** 查询订单明细快照。 */
  public List<Map<String, Object>> findOrderItems(String tableName, String orderNo) {
    return queryForList(
        "select"
            + " product_id,sku_id,product_name_snapshot,sku_snapshot,unit_price,quantity,line_amount"
            + " from "
            + tableName
            + " where order_no=? order by id",
        orderNo);
  }

  /** 按当前状态条件迁移订单状态。 */
  public int updateStatus(
      String tableName,
      String orderNo,
      String targetStatus,
      String currentStatus,
      Object updatedAt) {
    return update(
        "update "
            + tableName
            + " set status=?,updated_at=?,paid_at=case when ?='PAID' then ? else paid_at"
            + " end,cancelled_at=case when ?='CANCELLED' then ? else cancelled_at end where"
            + " order_no=? and status=?",
        targetStatus,
        updatedAt,
        targetStatus,
        updatedAt,
        targetStatus,
        updatedAt,
        orderNo,
        currentStatus);
  }
}
