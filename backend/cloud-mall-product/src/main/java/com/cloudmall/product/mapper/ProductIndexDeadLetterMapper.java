package com.cloudmall.product.mapper;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 商品索引死信原始字节、隔离状态和恢复租约的数据库入口。 */
@Repository
public class ProductIndexDeadLetterMapper {
  private final JdbcTemplate jdbcTemplate;

  public ProductIndexDeadLetterMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 只插入首次证据；重复死信不能重置已完成的恢复状态。 */
  public void insert(String messageId, String contentType, byte[] rawPayload, String sha256,
      String reason, OffsetDateTime occurredAt) {
    Timestamp now = Timestamp.from(occurredAt.toInstant());
    jdbcTemplate.update(
        "insert ignore into product_index_dlq_quarantine(quarantine_id,queue_name,content_type,"
            + "payload_sha256,raw_payload,payload_size,reason,status,next_retry_at,created_at,updated_at)"
            + " values(?,?,?,?,?,?,?,'QUARANTINED',?,?,?)",
        messageId, "cloudmall.product.index.dlq", contentType, sha256, rawPayload,
        rawPayload.length, reason, now, now, now);
    List<byte[]> stored = jdbcTemplate.query(
        "select raw_payload from product_index_dlq_quarantine where queue_name=? and payload_sha256=?",
        (resultSet, rowNumber) -> resultSet.getBytes(1),
        "cloudmall.product.index.dlq", sha256);
    if (stored.size() != 1 || !Arrays.equals(rawPayload, stored.get(0))) {
      throw new IllegalStateException("商品索引死信原字节保存核验失败");
    }
  }

  /** 原有数据卷第一次启动时准备死信证据表。 */
  public void ensureTable() {
    jdbcTemplate.execute("create table if not exists product_index_dlq_quarantine ("
        + "quarantine_id varchar(128) primary key,queue_name varchar(255) not null,"
        + "content_type varchar(128) null,content_encoding varchar(64) null,"
        + "payload_sha256 char(64) not null,raw_payload longblob not null,"
        + "payload_size int unsigned not null,product_id bigint null,"
        + "reason varchar(512) not null,status varchar(32) not null,"
        + "attempts int not null default 0,next_retry_at datetime(3) not null,"
        + "lease_owner varchar(64) null,lease_until datetime(3) null,"
        + "created_at datetime(3) not null,updated_at datetime(3) not null,"
        + "unique key uk_product_index_quarantine_hash(queue_name,payload_sha256),"
        + "key idx_product_index_quarantine_status(status,next_retry_at,lease_until))");
  }

  /** 多实例按租约认领可恢复的 JSON 死信。 */
  public List<DeadLetterPayload> claimRetryPayloads(String owner, int limit,
      OffsetDateTime now, OffsetDateTime leaseUntil) {
    jdbcTemplate.update(
        "update product_index_dlq_quarantine set lease_owner=?,lease_until=?,updated_at=?"
            + " where status='QUARANTINED' and reason='json-retry-pending'"
            + " and next_retry_at<=? and (lease_until is null or lease_until<?)"
            + " order by created_at limit ?",
        owner, Timestamp.from(leaseUntil.toInstant()), Timestamp.from(now.toInstant()),
        Timestamp.from(now.toInstant()), Timestamp.from(now.toInstant()), limit);
    return jdbcTemplate.query(
        "select quarantine_id,raw_payload,attempts from product_index_dlq_quarantine"
            + " where lease_owner=? and lease_until>? and status='QUARANTINED'"
            + " order by created_at limit ?",
        (resultSet, rowNumber) -> new DeadLetterPayload(resultSet.getString(1),
            resultSet.getBytes(2), resultSet.getInt(3)),
        owner, Timestamp.from(now.toInstant()), limit);
  }

  /** 只有当前租约持有者可以完成死信重放。 */
  public int markReplayed(String quarantineId, String owner) {
    return jdbcTemplate.update(
        "update product_index_dlq_quarantine set status='REPLAYED',attempts=attempts+1,"
            + "lease_owner=null,lease_until=null,updated_at=?"
            + " where quarantine_id=? and lease_owner=? and status='QUARANTINED'",
        Timestamp.from(OffsetDateTime.now().toInstant()), quarantineId, owner);
  }

  /** 发布结果未知时保留证据并延迟下一次重放。 */
  public int markRetry(String quarantineId, String owner, OffsetDateTime nextRetryAt) {
    return jdbcTemplate.update(
        "update product_index_dlq_quarantine set attempts=attempts+1,next_retry_at=?,"
            + "lease_owner=null,lease_until=null,updated_at=?"
            + " where quarantine_id=? and lease_owner=? and status='QUARANTINED'",
        Timestamp.from(nextRetryAt.toInstant()), Timestamp.from(OffsetDateTime.now().toInstant()),
        quarantineId, owner);
  }

  /** 无法与原 outbox 核对的消息保持隔离，等待人工审查。 */
  public int markUnverifiable(String quarantineId, String owner) {
    return jdbcTemplate.update(
        "update product_index_dlq_quarantine set reason='unverifiable-envelope',"
            + "lease_owner=null,lease_until=null,updated_at=?"
            + " where quarantine_id=? and lease_owner=? and status='QUARANTINED'",
        Timestamp.from(OffsetDateTime.now().toInstant()), quarantineId, owner);
  }

  public record DeadLetterPayload(String quarantineId, byte[] body, int attempts) {}
}
