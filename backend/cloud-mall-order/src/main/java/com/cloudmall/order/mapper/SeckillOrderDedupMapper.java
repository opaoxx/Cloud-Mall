package com.cloudmall.order.mapper;

import java.time.LocalDateTime;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 秒杀订单消费侧独立幂等记录。 */
@Repository
public class SeckillOrderDedupMapper {
  private final JdbcTemplate jdbcTemplate;

  public SeckillOrderDedupMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 创建订单消费幂等表；正式部署由 schema.sql 提前创建。 */
  public void ensureTable() {
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS seckill_order_dedup ("
            + "id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,idempotency_key VARCHAR(128) NOT NULL,"
            + "request_hash CHAR(64) NOT NULL,order_no VARCHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,"
            + "event_id VARCHAR(128),created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "UNIQUE KEY uk_seckill_dedup_user_key(user_id,idempotency_key),"
            + "UNIQUE KEY uk_seckill_order_dedup_order(order_no),KEY idx_seckill_order_dedup_event(event_id))");
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS seckill_order_dedup_conflict ("
            + "id BIGINT PRIMARY KEY,event_id VARCHAR(128) NOT NULL,"
            + "user_id BIGINT NOT NULL,idempotency_key VARCHAR(128) NOT NULL,"
            + "order_no VARCHAR(64),existing_order_no VARCHAR(64),request_hash CHAR(64) NOT NULL,"
            + "existing_request_hash CHAR(64),conflict_type VARCHAR(32) NOT NULL,"
            + "payload JSON NOT NULL,status VARCHAR(16) NOT NULL,"
            + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "UNIQUE KEY uk_seckill_conflict_event(event_id),"
            + "UNIQUE KEY uk_seckill_conflict_fingerprint(user_id,idempotency_key,request_hash,conflict_type),"
            + "KEY idx_seckill_dedup_conflict_created(created_at))");
  }

  /** 首次插入并锁定幂等键；调用方必须在订单本地事务内完成。 */
  public int claim(
      long id,
      long userId,
      String idempotencyKey,
      String requestHash,
      String orderNo,
      String eventId,
      LocalDateTime now) {
    return jdbcTemplate.update(
        "INSERT IGNORE INTO seckill_order_dedup "
            + "(id,user_id,idempotency_key,request_hash,order_no,status,event_id,created_at,updated_at) "
            + "VALUES (?,?,?,?,?,'PROCESSING',?,?,?)",
        id,
        userId,
        idempotencyKey,
        requestHash,
        orderNo,
        eventId,
        now,
        now);
  }

  /** 按用户和幂等键读取提交后的消费记录。 */
  public Map<String, Object> find(long userId, String idempotencyKey) {
    return jdbcTemplate.queryForMap(
        "SELECT user_id,idempotency_key,request_hash,order_no,status,event_id "
            + "FROM seckill_order_dedup WHERE user_id=? AND idempotency_key=?",
        userId,
        idempotencyKey);
  }

  public int markCommitted(long userId, String idempotencyKey, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE seckill_order_dedup SET status='COMMITTED',updated_at=? "
            + "WHERE user_id=? AND idempotency_key=? AND status='PROCESSING'",
        now,
        userId,
        idempotencyKey);
  }

  public int markConflict(long userId, String idempotencyKey, String requestHash, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE seckill_order_dedup SET status='CONFLICT',updated_at=? "
            + "WHERE user_id=? AND idempotency_key=? AND request_hash<>? AND status='PROCESSING'",
        now,
        userId,
        idempotencyKey,
        requestHash);
  }

  /** 记录没有先验幂等行的不可变载荷冲突证据。 */
  public int insertConflict(
      long id,
      long userId,
      String idempotencyKey,
      String requestHash,
      String eventId,
      LocalDateTime now) {
    return jdbcTemplate.update(
        "INSERT IGNORE INTO seckill_order_dedup "
            + "(id,user_id,idempotency_key,request_hash,order_no,status,event_id,created_at,updated_at) "
            + "VALUES (?,?,?,?,?,'CONFLICT',?,?,?)",
        id,
        userId,
        idempotencyKey,
        requestHash,
        "CONFLICT:" + eventId,
        eventId,
        now,
        now);
  }

  /** 保存事件冲突证据，不占用或改写原幂等键。 */
  public int recordConflictEvent(
      long id, long userId, String idempotencyKey, String requestHash, String orderNo,
      String eventId, String reason, LocalDateTime now) {
    return recordConflictEvent(id, userId, idempotencyKey, requestHash, orderNo, eventId, reason,
        "{}", now);
  }

  /** 写入原始冲突载荷；重复事件或同指纹冲突按唯一键幂等保留。 */
  public int recordConflictEvent(
      long id, long userId, String idempotencyKey, String requestHash, String orderNo,
      String eventId, String reason, String payload, LocalDateTime now) {
    String conflictType = reason.contains("orderNo") ? "ORDER_NO_MISMATCH"
        : reason.contains("requestHash") || reason.contains("哈希") ? "HASH_MISMATCH" : "INVALID_PAYLOAD";
    return jdbcTemplate.update(
        "INSERT IGNORE INTO seckill_order_dedup_conflict "
            + "(id,event_id,user_id,idempotency_key,order_no,request_hash,conflict_type,payload,status,created_at,updated_at) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?)",
        id, eventId, userId, idempotencyKey, orderNo, requestHash, conflictType, payload, "RECORDED", now, now);
  }
}
