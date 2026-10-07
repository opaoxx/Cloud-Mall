package com.cloudmall.order.mapper;

import java.time.Clock;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 秒杀死信原始载荷和自动重放状态。 */
@Repository
public class SeckillMessageRecoveryMapper {
  /** 恢复台账的 SQL 执行入口。 */
  private final JdbcTemplate jdbcTemplate;

  /** 完成写每次使用新鲜上海时间；隔离测试可固定精确毫秒边界。 */
  private Clock completionClock = Clock.system(ZoneOffset.ofHours(8));

  /** 完成写只接受当前精确 owner、未到期租约以及原可恢复状态。 */
  private static final String COMPLETION_GUARD =
      " AND BINARY lease_owner=BINARY ? AND lease_until IS NOT NULL AND lease_until>?"
          + " AND status IN ('REPLAY_PENDING','REPLAYED','QUARANTINED','VERIFYING')";

  /** 验真流程已经使用的三种目标状态，不开放任意状态覆盖。 */
  private static final Set<String> VERIFICATION_STATES =
      Set.of("QUARANTINED", "VERIFYING", "CONFLICT");

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

  /** 扫描到期记录；租约等于当前毫秒时已经失效，可被重新认领。 */
  public List<Map<String, Object>> findDue(int limit, LocalDateTime now) {
    return jdbcTemplate.queryForList(
        "SELECT id,event_id,request_hash,payload,attempts FROM seckill_message_recovery "
            + "WHERE status IN ('REPLAY_PENDING','REPLAYED','QUARANTINED','VERIFYING') AND next_retry_at<=? "
            + "AND (lease_until IS NULL OR lease_until<=?) ORDER BY id LIMIT ?",
        now,
        now,
        limit);
  }

  /** 按调用方本次新鲜时刻认领到期记录，拒绝仍被有效租约占用的行。 */
  public int claim(long id, String owner, LocalDateTime leaseUntil, LocalDateTime now) {
    return jdbcTemplate.update(
        "UPDATE seckill_message_recovery SET lease_owner=?,lease_until=?,updated_at=? WHERE id=? "
            + "AND status IN ('REPLAY_PENDING','REPLAYED','QUARANTINED','VERIFYING') AND next_retry_at<=? "
            + "AND (lease_until IS NULL OR lease_until<=?)",
        owner,
        leaseUntil,
        now,
        id,
        now,
        now);
  }

  /**
   * 只读核验当前 owner、有效租约及可恢复状态，不延长租约或修改记录。
   *
   * @param id 恢复台账主键
   * @param owner 本次认领的原始 token
   * @return 当前时刻是否仍可继续验真或尝试发送；不保证检查之后的网络调用原子性
   */
  public boolean hasActiveLease(long id, String owner) {
    LocalDateTime queryNow = completionTime();
    List<LocalDateTime> deadlines =
        jdbcTemplate.queryForList(
            "SELECT lease_until FROM seckill_message_recovery WHERE id=?" + COMPLETION_GUARD,
            LocalDateTime.class,
            id,
            owner,
            queryNow);
    // 查询本身也可能阻塞；返回时必须仍未到期，不能只相信请求发起时的有效性。
    return deadlines.size() == 1
        && deadlines.get(0) != null
        && deadlines.get(0).isAfter(completionTime());
  }

  /**
   * 认领后读取当前持久事实，替代可能已陈旧的扫描行。
   *
   * @param id 恢复台账主键
   * @param owner 本次认领的原始 token
   * @return 当前有效归属下的快照；无记录或读取期间到期时返回 null
   */
  public Map<String, Object> findClaimed(long id, String owner) {
    LocalDateTime queryNow = completionTime();
    List<Map<String, Object>> rows =
        jdbcTemplate.queryForList(
            "SELECT id,event_id,request_hash,payload,attempts,status,lease_until"
                + " FROM seckill_message_recovery WHERE id=?"
                + COMPLETION_GUARD,
            id,
            owner,
            queryNow);
    if (rows.size() != 1) return null;
    Map<String, Object> row = rows.get(0);
    Object storedDeadline = row.get("lease_until");
    LocalDateTime deadline =
        storedDeadline instanceof LocalDateTime local
            ? local
            : storedDeadline instanceof java.sql.Timestamp timestamp
                ? timestamp.toLocalDateTime()
                : null;
    // 查询可能跨过截止时间；只有读取完成时仍有效才能把快照交给 Service。
    return deadline != null && deadline.isAfter(completionTime()) ? row : null;
  }

  /** 只有仍持有有效租约的 worker 可完成重放；失效 owner 或终态记录保持原样。 */
  public void markReplayed(
      long id, String owner, int retryCount, LocalDateTime nextRetry, String error) {
    // 1. 每次完成调用重新采样，不能复用批次开始时或重试计划中的时间。
    LocalDateTime now = completionTime();
    // 2. 状态和租约在同一 UPDATE 内核对，避免迟到完成覆盖 CONFLICT 或新 owner。
    jdbcTemplate.update(
        "UPDATE seckill_message_recovery SET status='REPLAYED',attempts=?,next_retry_at=?,last_error=?,"
            + "lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=?"
            + COMPLETION_GUARD
            + " AND attempts>=0 AND attempts<=?",
        retryCount,
        nextRetry,
        compactError(error),
        now,
        id,
        owner,
        now,
        retryCount);
  }

  /** 在有效 owner/租约及非终态下写入验真结果；不改变原 void 调用契约。 */
  public void markVerification(
      long id, String owner, String status, String error, LocalDateTime nextRetry) {
    // 1. 只允许现有验真状态，并为本次完成采样同一个上海毫秒时间。
    if (status == null || !VERIFICATION_STATES.contains(status)) {
      throw new IllegalArgumentException("不支持的秒杀恢复验真状态");
    }
    LocalDateTime now = completionTime();
    // 2. 过期、失去归属或已终结的记录必须零更新，不释放他人的租约。
    jdbcTemplate.update(
        "UPDATE seckill_message_recovery SET status=?,next_retry_at=?,last_error=?,"
            + "lease_owner=NULL,lease_until=NULL,updated_at=? WHERE id=?"
            + COMPLETION_GUARD,
        status,
        nextRetry,
        compactError(error),
        now,
        id,
        owner,
        now);
  }

  /** 固定业务偏移并截断亚毫秒；UPDATE 时间和租约条件复用这一次采样。 */
  private LocalDateTime completionTime() {
    return LocalDateTime.ofInstant(completionClock.instant(), ZoneOffset.ofHours(8))
        .truncatedTo(ChronoUnit.MILLIS);
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
