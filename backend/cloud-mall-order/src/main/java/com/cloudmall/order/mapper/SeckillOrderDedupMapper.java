package com.cloudmall.order.mapper;

import java.sql.SQLException;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 秒杀订单消费侧独立幂等记录。 */
@Repository
public class SeckillOrderDedupMapper {
  /** 当前订单库的SQL入口，建表和冲突证据双写均由本Mapper负责。 */
  private final JdbcTemplate jdbcTemplate;

  /**
   * 创建订单库幂等及冲突证据访问入口。
   *
   * @param jdbcTemplate 当前订单库连接入口
   */
  public SeckillOrderDedupMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /**
   * 创建幂等和冲突表，并在消费启动前确保原文证据列兼容。
   *
   * @throws DataAccessException 建表或增列失败，不允许降级为只写JSON
   * @throws IllegalStateException 已有原文列类型、字符集或排序规则不兼容
   */
  public void ensureTable() {
    // 1. 保留原消费幂等表和唯一键。
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS seckill_order_dedup ("
            + "id BIGINT PRIMARY KEY,user_id BIGINT NOT NULL,idempotency_key VARCHAR(128) NOT NULL,"
            + "request_hash CHAR(64) NOT NULL,order_no VARCHAR(64) NOT NULL,status VARCHAR(16) NOT NULL,"
            + "event_id VARCHAR(128),created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "UNIQUE KEY uk_seckill_dedup_user_key(user_id,idempotency_key),"
            + "UNIQUE KEY uk_seckill_dedup_order(order_no),KEY idx_seckill_dedup_event(event_id))");
    // 2. 新冲突表同时创建JSON视图和可空文本证据列。
    jdbcTemplate.execute(
        "CREATE TABLE IF NOT EXISTS seckill_order_dedup_conflict ("
            + "id BIGINT PRIMARY KEY,event_id VARCHAR(128) NOT NULL,"
            + "user_id BIGINT NOT NULL,idempotency_key VARCHAR(128) NOT NULL,"
            + "order_no VARCHAR(64),existing_order_no VARCHAR(64),request_hash CHAR(64) NOT NULL,"
            + "existing_request_hash CHAR(64),conflict_type VARCHAR(32) NOT NULL,"
            + "payload JSON NOT NULL,raw_payload LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL,"
            + "status VARCHAR(16) NOT NULL,"
            + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "UNIQUE KEY uk_seckill_conflict_event(event_id),"
            + "UNIQUE KEY uk_seckill_conflict_fingerprint(user_id,idempotency_key,request_hash,conflict_type),"
            + "KEY idx_seckill_dedup_conflict_created(created_at))");
    // 3. 旧表只增列并验结构，不从历史JSON回填证据。
    ensureRawPayloadColumn();
  }

  /** 只增列、不回填历史；并发1060需重新验列，其它DDL故障直接传播。 */
  private void ensureRawPayloadColumn() {
    List<Map<String, Object>> columns = rawPayloadColumns();
    if (columns.isEmpty()) {
      try {
        jdbcTemplate.execute(
            "ALTER TABLE seckill_order_dedup_conflict ADD COLUMN raw_payload "
                + "LONGTEXT CHARACTER SET utf8mb4 COLLATE utf8mb4_bin NULL AFTER payload");
      } catch (DataAccessException migrationFailure) {
        if (!isDuplicateColumn(migrationFailure)) throw migrationFailure;
      }
      columns = rawPayloadColumns();
    }
    if (columns.size() != 1
        || !"longtext".equals(columns.get(0).get("data_type"))
        || !"YES".equals(columns.get(0).get("is_nullable"))
        || !"utf8mb4".equals(columns.get(0).get("character_set_name"))
        || !"utf8mb4_bin".equals(columns.get(0).get("collation_name"))) {
      throw new IllegalStateException("秒杀冲突raw_payload列结构不兼容，禁止启动消费");
    }
  }

  /** 按当前连接数据库读取证据列结构，不跨库推断。 */
  private List<Map<String, Object>> rawPayloadColumns() {
    return jdbcTemplate.queryForList(
        "SELECT DATA_TYPE AS data_type,IS_NULLABLE AS is_nullable,"
            + "CHARACTER_SET_NAME AS character_set_name,COLLATION_NAME AS collation_name "
            + "FROM information_schema.columns WHERE table_schema=DATABASE() "
            + "AND table_name='seckill_order_dedup_conflict' AND column_name='raw_payload'");
  }

  /** 仅MySQL重复列1060允许重查后收敛，不能掩盖权限、连接或类型错误。 */
  private static boolean isDuplicateColumn(DataAccessException failure) {
    for (Throwable cause = failure; cause != null; cause = cause.getCause()) {
      if (cause instanceof SQLException sqlFailure && sqlFailure.getErrorCode() == 1060)
        return true;
    }
    return false;
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

  /**
   * 按原用户和幂等键将PROCESSING记录更新为COMMITTED。
   *
   * @param userId 原消费用户
   * @param idempotencyKey 原幂等键
   * @param now 调用方提供的更新时间
   * @return SQL影响行数，由调用方检查是否恰好为1
   */
  public int markCommitted(long userId, String idempotencyKey, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE seckill_order_dedup SET status='COMMITTED',updated_at=? "
            + "WHERE user_id=? AND idempotency_key=? AND status='PROCESSING'",
        now,
        userId,
        idempotencyKey);
  }

  /**
   * 将请求指纹不同的原PROCESSING记录标记为CONFLICT。
   *
   * @param userId 原消费用户
   * @param idempotencyKey 原幂等键
   * @param requestHash 当前消息的请求指纹，用于原SQL差异条件
   * @param now 调用方提供的更新时间
   * @return SQL影响行数，不改变不满足条件的记录
   */
  public int markConflict(
      long userId, String idempotencyKey, String requestHash, LocalDateTime now) {
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
      long id,
      long userId,
      String idempotencyKey,
      String requestHash,
      String orderNo,
      String eventId,
      String reason,
      LocalDateTime now) {
    return recordConflictEvent(
        id, userId, idempotencyKey, requestHash, orderNo, eventId, reason, "{}", now);
  }

  /**
   * 同一INSERT双写JSON查询视图和完整文本证据；重复事件或同指纹不覆盖首条记录。
   *
   * @param id 冲突记录主键
   * @param userId 解析后的用户身份，无法确认时由上游传0
   * @param idempotencyKey 原消息幂等键
   * @param requestHash 原消息或证据摘要指纹
   * @param orderNo 原消息订单号
   * @param eventId 稳定事件标识
   * @param reason 冲突原因，沿原规则归类
   * @param payload 当前序列化JSON文本，原样绑定到两个证据列
   * @param now 原审计时间
   * @return 首次插入1，原唯一键重复0
   * @throws DataAccessException 缺列或写入失败，不进行只写JSON的降级
   */
  public int recordConflictEvent(
      long id,
      long userId,
      String idempotencyKey,
      String requestHash,
      String orderNo,
      String eventId,
      String reason,
      String payload,
      LocalDateTime now) {
    // 1. 保留原冲突类型分类。
    String conflictType =
        reason.contains("orderNo")
            ? "ORDER_NO_MISMATCH"
            : reason.contains("requestHash") || reason.contains("哈希")
                ? "HASH_MISMATCH"
                : "INVALID_PAYLOAD";
    // 2. 同一次写入保存查询视图与文本；重复时原记录保持不可变。
    return jdbcTemplate.update(
        "INSERT IGNORE INTO seckill_order_dedup_conflict "
            + "(id,event_id,user_id,idempotency_key,order_no,request_hash,conflict_type,payload,raw_payload,status,created_at,updated_at) "
            + "VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
        id,
        eventId,
        userId,
        idempotencyKey,
        orderNo,
        requestHash,
        conflictType,
        payload,
        payload,
        "RECORDED",
        now,
        now);
  }
}
