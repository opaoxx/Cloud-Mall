package com.cloudmall.order.mapper;

import com.cloudmall.order.domain.po.OrderOutboxPO;
import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.temporal.ChronoUnit;
import java.util.HexFormat;
import java.util.List;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 订单超时 outbox 的唯一数据库访问入口。 */
@Repository
public class OrderOutboxMapper {
  /** Java17空白字符的补集；以参数绑定传入ICU，保留NBSP等非Java空白，升级JDK须重审此集合。 */
  private static final String NONBLANK_IDENTITY_PATTERN =
      "[^\\x{0009}-\\x{000D}\\x{001C}-\\x{0020}\\x{1680}\\x{2000}-\\x{2006}"
          + "\\x{2008}-\\x{200A}\\x{2028}-\\x{2029}\\x{205F}\\x{3000}]";

  /** UTF8正则的ASCII十六进制传输值；服务器恢复文本，避免客户端SQL模式改写反斜杠。 */
  private static final String NONBLANK_IDENTITY_PATTERN_HEX =
      HexFormat.of().formatHex(NONBLANK_IDENTITY_PATTERN.getBytes(StandardCharsets.UTF_8));

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
   * 在LIMIT前精确匹配状态/类型，排除负计数及Java空白身份，查询已提交并到期的超时候选。
   *
   * @param limit 本轮候选上限，非正值沿原规则归一为1
   * @return 按原重试时间及主键顺序的合资格候选，身份原样映射，不修改未入选事实
   */
  public List<OrderOutboxPO> findPendingOutbox(int limit) {
    return jdbcTemplate.query(
        "SELECT"
            + " id,event_id,event_type,order_no,payload,status,attempts,next_retry_at,last_error,published_at,created_at,updated_at"
            + " FROM order_outbox WHERE CAST(status AS BINARY)=CAST('PENDING' AS BINARY) AND"
            + " next_retry_at<=? AND attempts>=0 AND BINARY event_type=BINARY 'ORDER_TIMEOUT' AND"
            + " REGEXP_LIKE(event_id, CONVERT(UNHEX(?) USING utf8mb4) COLLATE utf8mb4_bin, 'c') AND"
            + " REGEXP_LIKE(order_no, CONVERT(UNHEX(?) USING utf8mb4) COLLATE utf8mb4_bin, 'c')"
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
        NONBLANK_IDENTITY_PATTERN_HEX,
        NONBLANK_IDENTITY_PATTERN_HEX,
        Math.max(1, limit));
  }

  /**
   * 只读查询完成状态候选，数据库排序规则命中后仍由业务层核验精确原标识。
   *
   * @param eventId 原消息事件标识
   * @return eventId/status/type/orderNo/attempts部分投影，不用于重投，不读取或修改载荷及时间
   */
  public List<OrderOutboxPO> findPublicationFacts(String eventId) {
    return jdbcTemplate.query(
        "SELECT event_id,status,event_type,order_no,attempts FROM order_outbox WHERE event_id=?",
        (resultSet, rowNumber) -> {
          OrderOutboxPO fact = new OrderOutboxPO();
          fact.eventId = resultSet.getString("event_id");
          fact.status = resultSet.getString("status");
          fact.eventType = resultSet.getString("event_type");
          fact.orderNo = resultSet.getString("order_no");
          fact.attempts = resultSet.getObject("attempts", Integer.class);
          return fact;
        },
        eventId);
  }

  /**
   * 仅在publisher confirm成功后将原身份、待发布状态、超时类型精确且计数非负的事件标记为已发布。
   *
   * @param eventId 原消息事件标识，不做大小写、重音或空格归一化
   * @param publishedAt 调用方提供的上海发布时间
   * @return 精确原身份且仍符合超时投递资格的匹配行数，资格不匹配或缺失为0
   */
  public int markOutboxPublished(String eventId, LocalDateTime publishedAt) {
    return jdbcTemplate.update(
        "UPDATE order_outbox SET status='PUBLISHED',published_at=?,updated_at=?,last_error=NULL"
            + " WHERE event_id=? AND CAST(event_id AS BINARY)=CAST(? AS BINARY)"
            + " AND CAST(status AS BINARY)=CAST('PENDING' AS BINARY)"
            + " AND CAST(event_type AS BINARY)=CAST('ORDER_TIMEOUT' AS BINARY) AND attempts>=0",
        publishedAt,
        publishedAt,
        eventId,
        eventId);
  }

  /**
   * 记录最多512个完整Unicode错误字符并推迟原事件重试；非负次数在INT上限饱和，负值不猜测修复。
   *
   * @param eventId 原超时事件唯一标识
   * @param error 原失败文本，可为null，内容不做归一化
   * @param nextRetryAt 原调用方提供的上海重试时间，年份1000..9999，写入前向下截到毫秒
   * @return 原身份/状态/超时类型精确且次数非负的匹配行数，资格不匹配或未命中为0
   * @throws InvalidDataAccessApiUsageException 时间为空或年份超出DATETIME范围，首次JDBC前拒绝
   */
  public int recordOutboxFailure(String eventId, String error, LocalDateTime nextRetryAt) {
    // 1. 校验调用方时间并向下截到毫秒，避免数据库舍入跨秒、跨月或年份上限。
    LocalDateTime normalizedRetryAt = normalizeFailureTime(nextRetryAt, "nextRetryAt");
    // 2. 只采样一次上海更新时间，应用同一DATETIME(3)边界，不替换原重试时间。
    LocalDateTime updatedAt =
        normalizeFailureTime(LocalDateTime.now(java.time.ZoneId.of("Asia/Shanghai")), "updatedAt");
    // 3. 按原资格和参数顺序写入，保持错误裁剪、计数饱和及返回行数语义。
    return jdbcTemplate.update(
        "UPDATE order_outbox SET attempts=CASE WHEN attempts<2147483647 THEN attempts+1 ELSE"
            + " attempts END,last_error=?,next_retry_at=?,updated_at=? WHERE event_id=?"
            + " AND CAST(event_id AS BINARY)=CAST(? AS BINARY)"
            + " AND CAST(status AS BINARY)=CAST('PENDING' AS BINARY)"
            + " AND CAST(event_type AS BINARY)=CAST('ORDER_TIMEOUT' AS BINARY) AND attempts>=0",
        clipOutboxError(error),
        normalizedRetryAt,
        updatedAt,
        eventId,
        eventId);
  }

  /** 仅失败写时间边界：先拒绝空值/非法年份，再向下截毫秒；不转换时区、裁剪年份或补造时间。 */
  private static LocalDateTime normalizeFailureTime(LocalDateTime timestamp, String fieldName) {
    if (timestamp == null || timestamp.getYear() < 1000 || timestamp.getYear() > 9999) {
      throw new InvalidDataAccessApiUsageException(
          fieldName + " must be non-null with year in 1000..9999");
    }
    return timestamp.truncatedTo(ChronoUnit.MILLIS);
  }

  /** 按VARCHAR512字符列宽取完整code point，不分裂有效代理对或修复原异常输入。 */
  private static String clipOutboxError(String error) {
    if (error == null || error.codePointCount(0, error.length()) <= 512) return error;
    return error.substring(0, error.offsetByCodePoints(0, 512));
  }
}
