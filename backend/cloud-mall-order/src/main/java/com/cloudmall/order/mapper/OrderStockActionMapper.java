package com.cloudmall.order.mapper;

import com.cloudmall.order.domain.po.OrderStockActionPO;
import com.cloudmall.order.util.OrderCreationTime;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Objects;
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

  /**
   * 校验物理身份和必填字段后，与订单状态CAS在原事务中插入唯一动作。
   *
   * @param id 正的动作主键
   * @param orderNo 非空白且最多64个code point的原订单号，不做归一化
   * @param actionType 精确CONFIRM或ROLLBACK方向
   * @param occurredAt 上海语义的非null本地时间，年份1000..9999，向下截到毫秒
   * @return 原INSERT更新行数
   * @throws IllegalArgumentException 输入或年份范围不合法，执行SQL前拒绝
   */
  public int insert(long id, String orderNo, String actionType, LocalDateTime occurredAt) {
    // 1. 独立于MySQL sql_mode验证输入，避免截断或默认化污染首次事实。
    if (id <= 0
        || orderNo == null
        || orderNo.isBlank()
        || orderNo.codePointCount(0, orderNo.length()) > 64
        || (!"CONFIRM".equals(actionType) && !"ROLLBACK".equals(actionType))
        || occurredAt == null) {
      throw new IllegalArgumentException("库存动作主键、订单号、方向或发生时间不合法");
    }
    // 2. SQL前统一上海毫秒时间和年份范围，避免数据库舍入跨秒/月或默认化。
    LocalDateTime normalizedTime = normalizeActionTime(occurredAt);
    // 3. 保留plain INSERT和原key，三个时间列共用规范值，SQL异常原样传播。
    return jdbcTemplate.update(
        "INSERT INTO order_stock_action(id,order_no,action_type,operation_key,status,attempts,"
            + "next_retry_at,created_at,updated_at) VALUES(?,?,?,?, 'PENDING',0,?,?,?)",
        id,
        orderNo,
        actionType,
        orderNo + ":" + actionType,
        normalizedTime,
        normalizedTime,
        normalizedTime);
  }

  /**
   * 核验原订单、合法类型及操作键后读取动作状态，保留旧签名不限制订单期望方向。
   *
   * @param orderNo 原查询订单号，不进行裁剪或归一化
   * @return 可信动作的原状态、不可信候选的UNKNOWN，或真正无候选时的null
   */
  public String statusForOrder(String orderNo) {
    return readStatusForOrder(orderNo, null, false);
  }

  /**
   * 核验动作完整性及订单终态期望方向；未知方向不能授权已有动作状态。
   *
   * @param orderNo 原查询订单号，不进行裁剪或归一化
   * @param expectedActionType 订单期望的精确CONFIRM/ROLLBACK，其他值不授权已有动作
   * @return 完整且方向匹配的原状态、不可信候选的UNKNOWN，或真正无候选时的null
   */
  public String statusForOrder(String orderNo, String expectedActionType) {
    return readStatusForOrder(orderNo, expectedActionType, true);
  }

  /** 单次索引候选读取后逐项核原字段；UNKNOWN不混同缺行，不清洗或写回历史数据。 */
  private String readStatusForOrder(
      String orderNo, String expectedActionType, boolean requireExpectedType) {
    // 1. 保留collation候选，避免把不可信动作伪装成缺行并触发legacy兜底。
    List<OrderStockActionPO> actions =
        jdbcTemplate.query(
            "SELECT order_no,action_type,operation_key,status FROM order_stock_action WHERE order_no=?",
            (resultSet, rowNumber) -> {
              OrderStockActionPO action = new OrderStockActionPO();
              action.orderNo = resultSet.getString("order_no");
              action.actionType = resultSet.getString("action_type");
              action.operationKey = resultSet.getString("operation_key");
              action.status = resultSet.getString("status");
              return action;
            },
            orderNo);
    if (actions.isEmpty()) return null;
    // 2. 顺序核验原订单、精确类型、操作键绑定及调用方提供的方向。
    OrderStockActionPO action = actions.get(0);
    if (!Objects.equals(orderNo, action.orderNo)
        || (!"CONFIRM".equals(action.actionType) && !"ROLLBACK".equals(action.actionType))
        || !Objects.equals(action.operationKey, action.orderNo + ":" + action.actionType)
        || (requireExpectedType && !Objects.equals(expectedActionType, action.actionType))) {
      return "UNKNOWN";
    }
    // 3. 仅可信候选返回原状态，沿既有状态优先级，不执行任何写入或额外查询。
    return action.status;
  }

  /**
   * 按到期时间取得有限数量的待恢复动作，在LIMIT前排除负计数记录。
   *
   * @param now 原调用方提供的上海扫描时间，向下毫秒规范化后共用谓词
   * @param limit 原候选数量上限，沿既有规则至少为1
   * @return 按重试时间和主键排序的非负计数待恢复动作
   * @throws IllegalArgumentException 有效调用的时间为空、年份越界或派生时间越界
   */
  public List<OrderStockActionPO> findDue(LocalDateTime now, int limit) {
    LocalDateTime normalizedNow = normalizeActionTime(now);
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
        normalizedNow,
        normalizedNow,
        Math.max(1, limit));
  }

  /**
   * 以数据库租约认领非负计数动作，次数在INT上限饱和递增。
   *
   * @param id 原动作主键
   * @param owner 本次认领的独立标识
   * @param now 原调用方提供的上海认领时间，向下毫秒后派生30秒租约
   * @return 原租约条件匹配的更新行数，负计数或不匹配为0
   * @throws IllegalArgumentException 有效调用的时间为空、年份越界或派生时间越界
   */
  public int claim(long id, String owner, LocalDateTime now) {
    LocalDateTime normalizedNow = normalizeActionTime(now);
    LocalDateTime leaseUntil = normalizeActionTime(normalizedNow.plusSeconds(30));
    return jdbcTemplate.update(
        "UPDATE order_stock_action SET lease_owner=?,lease_until=?,"
            + "attempts=CASE WHEN attempts<2147483647 THEN attempts+1 ELSE attempts END,"
            + "updated_at=? WHERE id=? AND status IN ('PENDING','UNKNOWN')"
            + " AND attempts>=0 AND next_retry_at<=? AND (lease_until IS NULL OR lease_until<?)",
        owner,
        leaseUntil,
        normalizedNow,
        id,
        normalizedNow,
        normalizedNow);
  }

  /**
   * 仅由本次认领的有效租约持有者完成动作；过期或被接管时零更新。
   *
   * @param id 原动作主键
   * @param owner 本次认领标识，按原字符串精确比较
   * @param status 原调用方决定的目标状态
   * @param error 原错误文本，可为null
   * @param now 本次完成写的新鲜上海时间，同一规范毫秒值用于更新和租约判断
   * @return 非负计数且有效租约匹配的更新行数，不匹配或空白标识为0
   * @throws IllegalArgumentException 有效调用的时间为空、年份越界或派生时间越界
   */
  public int finish(long id, String owner, String status, String error, LocalDateTime now) {
    if (owner == null || owner.isBlank()) return 0;
    LocalDateTime normalizedNow = normalizeActionTime(now);
    return jdbcTemplate.update(
        "UPDATE order_stock_action SET status=?,last_error=?,lease_owner=NULL,lease_until=NULL,"
            + "updated_at=? WHERE id=? AND lease_owner IS NOT NULL AND BINARY lease_owner=BINARY ?"
            + " AND lease_until IS NOT NULL AND lease_until>? AND status IN ('PENDING','UNKNOWN')"
            + " AND attempts>=0",
        status,
        shorten(error),
        normalizedNow,
        id,
        owner,
        normalizedNow);
  }

  /**
   * 在本次有效租约内保留未知动作和原操作键，等待退避后重试。
   *
   * @param id 原动作主键
   * @param owner 本次认领标识，按原字符串精确比较
   * @param error 原错误文本，可为null
   * @param retryAt 原调用方提供的下次重试时间，规范毫秒值只用于延期
   * @param now 本次重试写的新鲜上海时间，同一规范毫秒值用于更新和租约判断
   * @return 非负计数且有效租约匹配的更新行数，不匹配或空白标识为0
   * @throws IllegalArgumentException 有效调用的时间为空、年份越界或派生时间越界
   */
  public int retry(long id, String owner, String error, LocalDateTime retryAt, LocalDateTime now) {
    if (owner == null || owner.isBlank()) return 0;
    LocalDateTime normalizedRetryAt = normalizeActionTime(retryAt);
    LocalDateTime normalizedNow = normalizeActionTime(now);
    return jdbcTemplate.update(
        "UPDATE order_stock_action SET status='UNKNOWN',last_error=?,next_retry_at=?,"
            + "lease_owner=NULL,lease_until=NULL,updated_at=?"
            + " WHERE id=? AND lease_owner IS NOT NULL AND BINARY lease_owner=BINARY ?"
            + " AND lease_until IS NOT NULL AND lease_until>? AND status IN ('PENDING','UNKNOWN')"
            + " AND attempts>=0",
        shorten(error),
        normalizedRetryAt,
        normalizedNow,
        id,
        owner,
        normalizedNow);
  }

  /** 同调用参数按上海local字段解释并floor毫秒；null/越界均在SQL前拒绝，不采新时钟。 */
  private static LocalDateTime normalizeActionTime(LocalDateTime timestamp) {
    return OrderCreationTime.normalize(
            timestamp == null ? null : timestamp.atOffset(ZoneOffset.ofHours(8)))
        .toLocalDateTime();
  }

  /** 按VARCHAR512字符列宽保留完整code point，不拆有效代理对或归一化原错误文本。 */
  private static String shorten(String error) {
    if (error == null || error.codePointCount(0, error.length()) <= 512) return error;
    return error.substring(0, error.offsetByCodePoints(0, 512));
  }
}
