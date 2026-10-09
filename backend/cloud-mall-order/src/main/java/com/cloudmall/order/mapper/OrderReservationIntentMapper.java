package com.cloudmall.order.mapper;

import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import java.time.LocalDateTime;
import java.util.List;
import org.springframework.dao.DuplicateKeyException;
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

  /**
   * 严格保存新预扣意图，禁止必填字段默认化或字符串静默截断。
   *
   * @param intent 调用方已构造的完整意图，nullable恢复字段保持原值
   * @return 首次插入为1，唯一键重复为0，不修改首事实
   * @throws IllegalArgumentException 必填字段缺失、身份空白或字符串超过物理列宽
   */
  public int insertIfAbsent(OrderReservationIntentPO intent) {
    // 1. 先校验物理必填字段，非strict会话也不得产生默认化恢复事实。
    if (intent == null
        || intent.id == null
        || intent.userId == null
        || intent.itemsSnapshot == null
        || intent.createdAt == null
        || intent.updatedAt == null) {
      throw new IllegalArgumentException("订单预扣意图缺少必填字段");
    }
    requireIntentIdentifier(intent.orderNo, 64, "orderNo");
    requireIntentIdentifier(intent.idempotencyKey, 128, "idempotencyKey");
    requireIntentIdentifier(intent.status, 32, "status");
    if (intent.lastError != null
        && intent.lastError.codePointCount(0, intent.lastError.length()) > 512) {
      throw new IllegalArgumentException("订单预扣意图lastError超过列宽");
    }
    // 2. 普通INSERT保留真实JSON/SQL错误，只将唯一键重复视为已有认领。
    try {
      return jdbcTemplate.update(
          "INSERT INTO order_reservation_intent(id,order_no,user_id,idempotency_key,"
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
    } catch (DuplicateKeyException duplicateKey) {
      return 0;
    }
  }

  /** 按数据库Unicode字符宽度核验身份，不trim、不改大小写或业务状态。 */
  private static void requireIntentIdentifier(
      String fieldValue, int maximumCharacters, String fieldName) {
    if (fieldValue == null
        || fieldValue.isBlank()
        || fieldValue.codePointCount(0, fieldValue.length()) > maximumCharacters) {
      throw new IllegalArgumentException("订单预扣意图字段不符合列约束: " + fieldName);
    }
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
        "SELECT * FROM order_reservation_intent WHERE status IN ('INIT','RESERVE_UNKNOWN','RESERVED')"
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
            + " WHERE order_no=? AND status IN ('INIT','RESERVE_UNKNOWN','RESERVED')"
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

  /**
   * 保存最近恢复错误的前512个完整Unicode字符及原重试时间。
   *
   * @param orderNo 原意图订单号
   * @param error 原错误文本，可为null，不做内容归一化
   * @param nextRetryAt 原调用方提供的上海重试时间
   * @return 匹配非终态意图的更新行数，其他状态为0
   */
  public int recordFailure(String orderNo, String error, LocalDateTime nextRetryAt) {
    return jdbcTemplate.update(
        "UPDATE order_reservation_intent SET last_error=?,next_retry_at=?,updated_at=?"
            + " WHERE order_no=? AND status IN ('INIT','RESERVE_UNKNOWN','RESERVED')",
        clipIntentError(error),
        nextRetryAt,
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
        orderNo);
  }

  /** 将已提交的订单与预扣意图在同一事务中绑定。 */
  public int markCommitted(String orderNo, LocalDateTime updatedAt) {
    return updateStatus(orderNo, "RESERVED", "COMMITTED", updatedAt);
  }

  /**
   * 将INIT预扣响应标记为未知，保持原身份并安全保存最多512个Unicode错误字符。
   *
   * @param orderNo 原意图订单号
   * @param error 原错误文本，可为null
   * @param nextRetryAt 原调用方提供的上海重试时间
   * @return INIT匹配为1，其他状态为0
   */
  public int markReserveUnknown(String orderNo, String error, LocalDateTime nextRetryAt) {
    return jdbcTemplate.update(
        "UPDATE order_reservation_intent SET status='RESERVE_UNKNOWN',last_error=?,"
            + "next_retry_at=?,updated_at=? WHERE order_no=? AND status='INIT'",
        clipIntentError(error),
        nextRetryAt,
        LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")),
        orderNo);
  }

  /** 按VARCHAR512字符列宽截断，不分裂有效代理对，不替换或修复原异常Unicode输入。 */
  private static String clipIntentError(String error) {
    if (error == null || error.codePointCount(0, error.length()) <= 512) return error;
    return error.substring(0, error.offsetByCodePoints(0, 512));
  }

  /** 将已确认回滚的意图标记为已释放。 */
  public int markReleased(String orderNo, LocalDateTime updatedAt) {
    return jdbcTemplate.update(
        "UPDATE order_reservation_intent SET status='RELEASED',last_error=NULL,"
            + "next_retry_at=NULL,updated_at=? WHERE order_no=?"
            + " AND status IN ('INIT','RESERVE_UNKNOWN','RESERVED')",
        updatedAt,
        orderNo);
  }

  /** 将订单表中的预扣意图改为已提交，供恢复任务兜底。 */
  public int forceCommitted(String orderNo, LocalDateTime updatedAt) {
    return jdbcTemplate.update(
        "UPDATE order_reservation_intent SET status='COMMITTED',last_error=NULL,"
            + "next_retry_at=NULL,updated_at=? WHERE order_no=?"
            + " AND status IN ('INIT','RESERVE_UNKNOWN','RESERVED')",
        updatedAt,
        orderNo);
  }

  /** 将 JDBC 行转换为预扣意图 PO，DATETIME直接读取组件并保留可空重试时间。 */
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
        intent.nextRetryAt = resultSet.getObject("next_retry_at", LocalDateTime.class);
        intent.createdAt = resultSet.getObject("created_at", LocalDateTime.class);
        intent.updatedAt = resultSet.getObject("updated_at", LocalDateTime.class);
        return intent;
      };
}
