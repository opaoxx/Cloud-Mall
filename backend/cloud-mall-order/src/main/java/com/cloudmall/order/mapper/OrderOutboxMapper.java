package com.cloudmall.order.mapper;

import com.cloudmall.order.domain.po.OrderOutboxPO;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 订单超时 outbox 的唯一数据库访问入口。 */
@Repository
public class OrderOutboxMapper {
  /** Spring JDBC 执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建订单 outbox Mapper。 */
  public OrderOutboxMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 为已有本地数据库补建 outbox 表。 */
  public void ensureTable() {
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS order_outbox ("
            + "id BIGINT PRIMARY KEY,event_id VARCHAR(128) NOT NULL UNIQUE,"
            + "event_type VARCHAR(64) NOT NULL,order_no VARCHAR(64) NOT NULL,"
            + "payload JSON NOT NULL,status VARCHAR(32) NOT NULL,"
            + "attempts INT NOT NULL DEFAULT 0,next_retry_at DATETIME(3) NOT NULL,"
            + "last_error VARCHAR(512),published_at DATETIME(3),"
            + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "KEY idx_outbox_status_retry(status,next_retry_at),"
            + "KEY idx_outbox_order(order_no))");
  }

  /** 在订单本地事务中写入待发布的超时事件。 */
  public int insert(OrderOutboxPO outbox) {
    return jdbcTemplate.update(
        "INSERT INTO order_outbox(id,event_id,event_type,order_no,payload,status,attempts,"
            + "next_retry_at,last_error,published_at,created_at,updated_at)"
            + " VALUES(?,?,?,?,?,?,?,?,?,?,?,?)",
        outbox.id,
        outbox.eventId,
        outbox.eventType,
        outbox.orderNo,
        outbox.payload,
        outbox.status,
        outbox.attempts,
        outbox.nextRetryAt,
        outbox.lastError,
        outbox.publishedAt,
        outbox.createdAt,
        outbox.updatedAt);
  }

  /**
   * 在LIMIT之前排除负计数，查询已提交并到达重试时间的待发布事件。
   *
   * @param limit 本轮候选上限，非正值沿原规则归一为1
   * @return 按原重试时间及主键顺序的非负计数候选，不修改未入选事实
   */
  public List<OrderOutboxPO> findPendingOutbox(int limit) {
    return jdbcTemplate.query(
        "SELECT id,event_id,event_type,order_no,payload,status,attempts,next_retry_at,"
            + "last_error,published_at,created_at,updated_at FROM order_outbox"
            + " WHERE status='PENDING' AND next_retry_at<=? AND attempts>=0"
            + " ORDER BY next_retry_at,id LIMIT ?",
        (resultSet, rowNumber) -> {
          OrderOutboxPO outbox = new OrderOutboxPO();
          outbox.id = resultSet.getLong("id");
          outbox.eventId = resultSet.getString("event_id");
          outbox.eventType = resultSet.getString("event_type");
          outbox.orderNo = resultSet.getString("order_no");
          outbox.payload = resultSet.getString("payload");
          outbox.status = resultSet.getString("status");
          outbox.attempts = resultSet.getInt("attempts");
          outbox.nextRetryAt = resultSet.getTimestamp("next_retry_at").toLocalDateTime();
          outbox.lastError = resultSet.getString("last_error");
          Timestamp publishedAt = resultSet.getTimestamp("published_at");
          outbox.publishedAt = publishedAt == null ? null : publishedAt.toLocalDateTime();
          outbox.createdAt = resultSet.getTimestamp("created_at").toLocalDateTime();
          outbox.updatedAt = resultSet.getTimestamp("updated_at").toLocalDateTime();
          return outbox;
        },
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
        Math.max(1, limit));
  }

  /** 仅在 publisher confirm 成功后标记事件已发布。 */
  public int markOutboxPublished(String eventId, LocalDateTime publishedAt) {
    return jdbcTemplate.update(
        "UPDATE order_outbox SET status='PUBLISHED',published_at=?,updated_at=?,last_error=NULL"
            + " WHERE event_id=? AND status='PENDING'",
        publishedAt,
        publishedAt,
        eventId);
  }

  /**
   * 记录最多512个完整Unicode错误字符并推迟原事件重试；非负次数在INT上限饱和，负值不猜测修复。
   *
   * @param eventId 原超时事件唯一标识
   * @param error 原失败文本，可为null，内容不做归一化
   * @param nextRetryAt 原调用方提供的上海重试时间
   * @return PENDING且次数非负的匹配行数，负值、其他状态或未命中为0
   */
  public int recordOutboxFailure(String eventId, String error, LocalDateTime nextRetryAt) {
    return jdbcTemplate.update(
        "UPDATE order_outbox SET attempts=CASE WHEN attempts<2147483647 THEN attempts+1 ELSE attempts END,"
            + "last_error=?,next_retry_at=?,updated_at=?"
            + " WHERE event_id=? AND status='PENDING' AND attempts>=0",
        clipOutboxError(error),
        nextRetryAt,
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
        eventId);
  }

  /** 按VARCHAR512字符列宽取完整code point，不分裂有效代理对或修复原异常输入。 */
  private static String clipOutboxError(String error) {
    if (error == null || error.codePointCount(0, error.length()) <= 512) return error;
    return error.substring(0, error.offsetByCodePoints(0, 512));
  }
}
