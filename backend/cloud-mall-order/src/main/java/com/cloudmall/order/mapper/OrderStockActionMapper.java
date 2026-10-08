package com.cloudmall.order.mapper;

import com.cloudmall.order.domain.po.OrderStockActionPO;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 持久化订单终态对应的唯一库存动作及恢复租约。 */
@Repository
public class OrderStockActionMapper {
  /** 数据库访问执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建库存动作 Mapper。 */
  public OrderStockActionMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 为已有本地数据库创建库存动作表。 */
  public void ensureTable() {
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS order_stock_action ("
            + "id BIGINT PRIMARY KEY,order_no VARCHAR(64) NOT NULL UNIQUE,"
            + "action_type VARCHAR(16) NOT NULL,operation_key VARCHAR(128) NOT NULL UNIQUE,"
            + "status VARCHAR(16) NOT NULL,attempts INT NOT NULL DEFAULT 0,"
            + "next_retry_at DATETIME(3) NOT NULL,lease_owner VARCHAR(128),"
            + "lease_until DATETIME(3),last_error VARCHAR(512),"
            + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "KEY idx_order_stock_action_retry(status,next_retry_at,lease_until))");
  }

  /** 与订单状态 CAS 在同一事务中插入唯一动作。 */
  public int insert(long id, String orderNo, String actionType, LocalDateTime occurredAt) {
    return jdbcTemplate.update(
        "INSERT INTO order_stock_action(id,order_no,action_type,operation_key,status,attempts,"
            + "next_retry_at,created_at,updated_at) VALUES(?,?,?,?, 'PENDING',0,?,?,?)",
        id, orderNo, actionType, orderNo + ":" + actionType,
        occurredAt, occurredAt, occurredAt);
  }

  /** 按订单号读取最终库存动作状态。 */
  public String statusForOrder(String orderNo) {
    List<String> statuses = jdbcTemplate.queryForList(
        "SELECT status FROM order_stock_action WHERE order_no=?", String.class, orderNo);
    return statuses.isEmpty() ? null : statuses.get(0);
  }

  /** 按到期时间取得有限数量的待恢复动作。 */
  public List<OrderStockActionPO> findDue(LocalDateTime now, int limit) {
    return jdbcTemplate.query(
        "SELECT id,order_no,action_type,operation_key,status,attempts,next_retry_at"
            + " FROM order_stock_action WHERE status IN ('PENDING','UNKNOWN')"
            + " AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<?)"
            + " ORDER BY next_retry_at,id LIMIT ?",
        (resultSet, rowNumber) -> {
          OrderStockActionPO action = new OrderStockActionPO();
          action.id = resultSet.getLong("id");
          action.orderNo = resultSet.getString("order_no");
          action.actionType = resultSet.getString("action_type");
          action.operationKey = resultSet.getString("operation_key");
          action.status = resultSet.getString("status");
          action.attempts = resultSet.getInt("attempts");
          action.nextRetryAt = resultSet.getTimestamp("next_retry_at").toLocalDateTime();
          return action;
        }, now, now, Math.max(1, limit));
  }

  /** 以数据库租约排除并发恢复。 */
  public int claim(long id, String owner, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE order_stock_action SET lease_owner=?,lease_until=?,attempts=attempts+1,"
            + "updated_at=? WHERE id=? AND status IN ('PENDING','UNKNOWN')"
            + " AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<?)",
        owner, now.plusSeconds(30), now, id, now, now);
  }

  /** 仅由租约持有者完成动作。 */
  public int finish(long id, String owner, String status, String error, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE order_stock_action SET status=?,last_error=?,lease_owner=NULL,lease_until=NULL,"
            + "updated_at=? WHERE id=? AND lease_owner=? AND status IN ('PENDING','UNKNOWN')",
        status, shorten(error), now, id, owner);
  }

  /** 远端结果未知时保留动作和原操作键，等待退避后重试。 */
  public int retry(long id, String owner, String error, LocalDateTime retryAt, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE order_stock_action SET status='UNKNOWN',last_error=?,next_retry_at=?,"
            + "lease_owner=NULL,lease_until=NULL,updated_at=?"
            + " WHERE id=? AND lease_owner=? AND status IN ('PENDING','UNKNOWN')",
        shorten(error), retryAt, now, id, owner);
  }

  /** 按VARCHAR512字符列宽保留完整code point，不拆有效代理对或归一化原错误文本。 */
  private static String shorten(String error) {
    if (error == null || error.codePointCount(0, error.length()) <= 512) return error;
    return error.substring(0, error.offsetByCodePoints(0, 512));
  }
}
