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

  /**
   * 按到期时间取得有限数量的待恢复动作，在LIMIT前排除负计数记录。
   *
   * @param now 原调用方提供的上海扫描时间
   * @param limit 原候选数量上限，沿既有规则至少为1
   * @return 按重试时间和主键排序的非负计数待恢复动作
   */
  public List<OrderStockActionPO> findDue(LocalDateTime now, int limit) {
    return jdbcTemplate.query(
        "SELECT id,order_no,action_type,operation_key,status,attempts,next_retry_at"
            + " FROM order_stock_action WHERE status IN ('PENDING','UNKNOWN')"
            + " AND attempts>=0 AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<?)"
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
        },
        now,
        now,
        Math.max(1, limit));
  }

  /**
   * 以数据库租约认领非负计数动作，次数在INT上限饱和递增。
   *
   * @param id 原动作主键
   * @param owner 本次认领的独立标识
   * @param now 原调用方提供的上海认领时间
   * @return 原租约条件匹配的更新行数，负计数或不匹配为0
   */
  public int claim(long id, String owner, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE order_stock_action SET lease_owner=?,lease_until=?,"
            + "attempts=CASE WHEN attempts<2147483647 THEN attempts+1 ELSE attempts END,"
            + "updated_at=? WHERE id=? AND status IN ('PENDING','UNKNOWN')"
            + " AND attempts>=0 AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<?)",
        owner,
        now.plusSeconds(30),
        now,
        id,
        now,
        now);
  }

  /**
   * 仅由本次认领的有效租约持有者完成动作；过期或被接管时零更新。
   *
   * @param id 原动作主键
   * @param owner 本次认领标识，按原字符串精确比较
   * @param status 原调用方决定的目标状态
   * @param error 原错误文本，可为null
   * @param now 本次完成写的新鲜上海时间，同时用于更新和租约判断
   * @return 非负计数且有效租约匹配的更新行数，不匹配或空白标识为0
   */
  public int finish(long id, String owner, String status, String error, LocalDateTime now) {
    if (owner == null || owner.isBlank()) return 0;
    return jdbcTemplate.update(
        "UPDATE order_stock_action SET status=?,last_error=?,lease_owner=NULL,lease_until=NULL,"
            + "updated_at=? WHERE id=? AND lease_owner IS NOT NULL AND BINARY lease_owner=BINARY ?"
            + " AND lease_until IS NOT NULL AND lease_until>? AND status IN ('PENDING','UNKNOWN')"
            + " AND attempts>=0",
        status,
        shorten(error),
        now,
        id,
        owner,
        now);
  }

  /**
   * 在本次有效租约内保留未知动作和原操作键，等待退避后重试。
   *
   * @param id 原动作主键
   * @param owner 本次认领标识，按原字符串精确比较
   * @param error 原错误文本，可为null
   * @param retryAt 原调用方提供的下次重试时间
   * @param now 本次重试写的新鲜上海时间，同时用于更新和租约判断
   * @return 非负计数且有效租约匹配的更新行数，不匹配或空白标识为0
   */
  public int retry(long id, String owner, String error, LocalDateTime retryAt, LocalDateTime now) {
    if (owner == null || owner.isBlank()) return 0;
    return jdbcTemplate.update(
        "UPDATE order_stock_action SET status='UNKNOWN',last_error=?,next_retry_at=?,"
            + "lease_owner=NULL,lease_until=NULL,updated_at=?"
            + " WHERE id=? AND lease_owner IS NOT NULL AND BINARY lease_owner=BINARY ?"
            + " AND lease_until IS NOT NULL AND lease_until>? AND status IN ('PENDING','UNKNOWN')"
            + " AND attempts>=0",
        shorten(error),
        retryAt,
        now,
        id,
        owner,
        now);
  }

  /** 按VARCHAR512字符列宽保留完整code point，不拆有效代理对或归一化原错误文本。 */
  private static String shorten(String error) {
    if (error == null || error.codePointCount(0, error.length()) <= 512) return error;
    return error.substring(0, error.offsetByCodePoints(0, 512));
  }
}
