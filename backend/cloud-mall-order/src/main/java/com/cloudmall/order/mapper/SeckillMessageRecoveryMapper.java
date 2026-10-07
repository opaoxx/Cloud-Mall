package com.cloudmall.order.mapper;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 秒杀死信原始载荷和自动重放状态。 */
@Repository
public class SeckillMessageRecoveryMapper {
  /** 恢复台账的 SQL 执行入口。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建使用当前订单数据源的恢复台账 Mapper。 */
  public SeckillMessageRecoveryMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  public void ensureTable() {
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS seckill_message_recovery ("
            + "id BIGINT PRIMARY KEY,event_id VARCHAR(128) NOT NULL,request_hash CHAR(64) NOT NULL,"
            + "payload JSON NOT NULL,status VARCHAR(20) NOT NULL,attempts INT NOT NULL DEFAULT 0,"
            + "next_retry_at DATETIME(3) NOT NULL,last_error VARCHAR(512),lease_owner VARCHAR(128),"
            + "lease_until DATETIME(3),created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "UNIQUE KEY uk_seckill_recovery_event(event_id),KEY idx_seckill_recovery_retry(status,next_retry_at,lease_until))");
  }

  /**
   * 按事件号保存首份死信证据；重复时精确核对哈希并用数据库 JSON 值比较载荷。
   *
   * @param id 首次插入使用的内部主键
   * @param eventId 不可变事件号
   * @param requestHash 原请求指纹，按字符串精确比较
   * @param payload 待保存的 JSON，等价重复不覆盖已存储值
   * @param status 首次保存的恢复状态
   * @param now 本次持久化时间
   * @return 首次插入或冲突更新的影响行数；等价重复直接返回0，不修改任何字段
   */
  public int saveDlq(
      long id,
      String eventId,
      String requestHash,
      String payload,
      String status,
      LocalDateTime now) {
    // 1. 唯一事件号完成并发认领；后到的相同事件不会改写原始载荷。
    int inserted =
        jdbcTemplate.update(
            "INSERT IGNORE INTO seckill_message_recovery "
                + "(id,event_id,request_hash,payload,status,attempts,next_retry_at,created_at,updated_at) "
                + "VALUES (?,?,?,?,?,0,?,?,?)",
            id,
            eventId,
            requestHash,
            payload,
            status,
            now,
            now,
            now);
    if (inserted == 1) return 1;
    // 2. JSON 列会规范化空白/键顺序，由数据库比较 JSON 值，不能比较往返后的字符串。
    List<Map<String, Object>> existing =
        jdbcTemplate.queryForList(
            "SELECT request_hash,payload=CAST(? AS JSON) AS payload_matches"
                + " FROM seckill_message_recovery WHERE event_id=?",
            payload,
            eventId);
    if (existing.isEmpty()) throw new IllegalStateException("秒杀死信事件号冲突后仍未找到原记录: " + eventId);
    Map<String, Object> row = existing.get(0);
    Object payloadMatch = row.get("payload_matches");
    boolean samePayload =
        Boolean.TRUE.equals(payloadMatch)
            || payloadMatch instanceof Number numericMatch && numericMatch.intValue() == 1;
    if (Objects.equals(requestHash, String.valueOf(row.get("request_hash"))) && samePayload)
      return 0;
    // 3. 同事件号不同载荷保留原消息并标记冲突，禁止继续自动重放。
    return jdbcTemplate.update(
        "UPDATE seckill_message_recovery SET status='CONFLICT',last_error=?,updated_at=?"
            + " WHERE event_id=?",
        "同一 eventId 的 requestHash 或 payload 不一致",
        now,
        eventId);
  }

  public List<Map<String, Object>> findDue(int limit, LocalDateTime now) {
    return jdbcTemplate.queryForList(
        "SELECT id,event_id,request_hash,payload,attempts FROM seckill_message_recovery "
            + "WHERE status IN ('REPLAY_PENDING','REPLAYED','QUARANTINED','VERIFYING') AND next_retry_at<=? "
            + "AND (lease_until IS NULL OR lease_until<?) ORDER BY id LIMIT ?",
        now,
        now,
        limit);
  }

  public int claim(long id, String owner, LocalDateTime leaseUntil, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE seckill_message_recovery SET lease_owner=?,lease_until=?,updated_at=? WHERE id=? "
            + "AND status IN ('REPLAY_PENDING','REPLAYED','QUARANTINED','VERIFYING') AND next_retry_at<=? "
            + "AND (lease_until IS NULL OR lease_until<?)",
        owner,
        leaseUntil,
        now,
        id,
        now,
        now);
  }

  public void markReplayed(long id, String owner, int retryCount, LocalDateTime nextRetry, String error) {
    jdbcTemplate.update(
        "UPDATE seckill_message_recovery SET status='REPLAYED',attempts=?,next_retry_at=?,last_error=?,"
            + "lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND lease_owner=?",
        retryCount,
        nextRetry,
        compactError(error),
        LocalDateTime.now(),
        id,
        owner);
  }

  public void markVerification(long id, String owner, String status, String error, LocalDateTime nextRetry) {
    jdbcTemplate.update(
        "UPDATE seckill_message_recovery SET status=?,next_retry_at=?,last_error=?,"
            + "lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=? AND lease_owner=?",
        status, nextRetry, compactError(error), LocalDateTime.now(), id, owner);
  }

  public void markResolved(String eventId, LocalDateTime now) {
    jdbcTemplate.update(
        "UPDATE seckill_message_recovery SET status='RESOLVED',lease_owner=NULL,lease_until=NULL,updated_at=? "
            + "WHERE event_id=? AND status IN ('REPLAY_PENDING','REPLAYED')",
        now,
        eventId);
  }

  /** 避免恢复错误超过数据库列宽后反复无法释放租约。 */
  private static String compactError(String error) {
    return error == null ? null : error.substring(0, Math.min(512, error.length()));
  }
}
