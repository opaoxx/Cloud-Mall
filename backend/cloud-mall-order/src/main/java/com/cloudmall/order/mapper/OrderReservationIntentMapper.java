package com.cloudmall.order.mapper;

import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 普通订单预扣意图的唯一数据库访问入口。 */
@Repository
public class OrderReservationIntentMapper {
  /** Spring JDBC 执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建预扣意图 Mapper。 */
  public OrderReservationIntentMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 为已有本地数据库补建预扣意图表。 */
  public void ensureTable() {
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS order_reservation_intent ("
            + "id BIGINT PRIMARY KEY,order_no VARCHAR(64) NOT NULL UNIQUE,"
            + "user_id BIGINT NOT NULL,idempotency_key VARCHAR(128) NOT NULL,"
            + "items_snapshot JSON NOT NULL,status VARCHAR(32) NOT NULL,"
            + "last_error VARCHAR(512),next_retry_at DATETIME(3),"
            + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "UNIQUE KEY uk_intent_user_key(user_id,idempotency_key),"
            + "KEY idx_intent_status_retry(status,next_retry_at),"
            + "KEY idx_intent_user(user_id,created_at))");
  }

  /** 写入新的 INIT 意图；同一用户幂等键只允许一个意图。 */
  public int insertIfAbsent(OrderReservationIntentPO intent) {
    return jdbcTemplate.update(
        "INSERT IGNORE INTO order_reservation_intent(id,order_no,user_id,idempotency_key,"
            + "items_snapshot,status,last_error,next_retry_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?)",
        intent.id,
        intent.orderNo,
        intent.userId,
        intent.idempotencyKey,
        intent.itemsSnapshot,
        intent.status,
        intent.lastError,
        intent.nextRetryAt,
        intent.createdAt,
        intent.updatedAt);
  }

  /** 按用户与幂等键读取已有意图。 */
  public List<OrderReservationIntentPO> findByUserAndKey(long userId, String idempotencyKey) {
    return jdbcTemplate.query(
        "SELECT * FROM order_reservation_intent WHERE user_id=? AND idempotency_key=?",
        ROW_MAPPER,
        userId,
        idempotencyKey);
  }

  /** 查询需要尝试恢复的陈旧预扣意图。 */
  public List<OrderReservationIntentPO> findRecoverable(LocalDateTime staleBefore, LocalDateTime now, int limit) {
    return jdbcTemplate.query(
        "SELECT * FROM order_reservation_intent WHERE status IN ('INIT','RESERVED')"
            + " AND created_at<? AND (next_retry_at IS NULL OR next_retry_at<=?)"
            + " ORDER BY created_at,id LIMIT ?",
        ROW_MAPPER,
        staleBefore,
        now,
        Math.max(1, limit));
  }

  /** 领取一条到期恢复记录，避免多个实例在同一轮重复执行。 */
  public int claimRecovery(String orderNo, LocalDateTime now, LocalDateTime nextRetryAt) {
    return jdbcTemplate.update(
        "UPDATE order_reservation_intent SET next_retry_at=?,updated_at=?"
            + " WHERE order_no=? AND status IN ('INIT','RESERVED')"
            + " AND (next_retry_at IS NULL OR next_retry_at<=?)",
        nextRetryAt,
        now,
        orderNo,
        now);
  }

  /** 使用期待的源状态迁移意图，避免覆盖其他处理方结果。 */
  public int updateStatus(String orderNo, String sourceStatus, String targetStatus, LocalDateTime updatedAt) {
    return jdbcTemplate.update(
        "UPDATE order_reservation_intent SET status=?,last_error=NULL,next_retry_at=NULL,updated_at=?"
            + " WHERE order_no=? AND status=? AND (next_retry_at IS NULL OR next_retry_at<=?)",
        targetStatus,
        updatedAt,
        orderNo,
        sourceStatus,
        updatedAt);
  }

  /** 保存最近的恢复错误及重试时间。 */
  public int recordFailure(String orderNo, String error, LocalDateTime nextRetryAt) {
    return jdbcTemplate.update(
        "UPDATE order_reservation_intent SET last_error=?,next_retry_at=?,updated_at=?"
            + " WHERE order_no=? AND status IN ('INIT','RESERVED')",
        error == null ? null : error.substring(0, Math.min(512, error.length())),
        nextRetryAt,
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
        orderNo);
  }

  /** 将已提交的订单与预扣意图在同一事务中绑定。 */
  public int markCommitted(String orderNo, LocalDateTime updatedAt) {
    return updateStatus(orderNo, "RESERVED", "COMMITTED", updatedAt);
  }

  /** 将已确认回滚的意图标记为已释放。 */
  public int markReleased(String orderNo, LocalDateTime updatedAt) {
    return jdbcTemplate.update(
        "UPDATE order_reservation_intent SET status='RELEASED',last_error=NULL,"
            + "next_retry_at=NULL,updated_at=? WHERE order_no=? AND status IN ('INIT','RESERVED')",
        updatedAt,
        orderNo);
  }

  /** 将订单表中的预扣意图改为已提交，供恢复任务兜底。 */
  public int forceCommitted(String orderNo, LocalDateTime updatedAt) {
    return jdbcTemplate.update(
        "UPDATE order_reservation_intent SET status='COMMITTED',last_error=NULL,"
            + "next_retry_at=NULL,updated_at=? WHERE order_no=? AND status IN ('INIT','RESERVED')",
        updatedAt,
        orderNo);
  }

  /** 将 JDBC 行转换为预扣意图 PO。 */
  private static final RowMapper<OrderReservationIntentPO> ROW_MAPPER =
      (resultSet, rowNumber) -> {
        OrderReservationIntentPO intent = new OrderReservationIntentPO();
        intent.id = resultSet.getLong("id");
        intent.orderNo = resultSet.getString("order_no");
        intent.userId = resultSet.getLong("user_id");
        intent.idempotencyKey = resultSet.getString("idempotency_key");
        intent.itemsSnapshot = resultSet.getString("items_snapshot");
        intent.status = resultSet.getString("status");
        intent.lastError = resultSet.getString("last_error");
        Timestamp nextRetryAt = resultSet.getTimestamp("next_retry_at");
        intent.nextRetryAt = nextRetryAt == null ? null : nextRetryAt.toLocalDateTime();
        intent.createdAt = resultSet.getTimestamp("created_at").toLocalDateTime();
        intent.updatedAt = resultSet.getTimestamp("updated_at").toLocalDateTime();
        return intent;
      };
}
