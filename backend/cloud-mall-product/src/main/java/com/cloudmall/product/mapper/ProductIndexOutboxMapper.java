package com.cloudmall.product.mapper;

import com.cloudmall.product.domain.po.ProductIndexOutbox;
import java.sql.Timestamp;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 商品索引 outbox 的唯一数据库访问入口。 */
@Repository
public class ProductIndexOutboxMapper {
  /** 访问商品事务事件及其投递、消费回执和恢复租约。 */
  private final JdbcTemplate jdbcTemplate;

  /** 注入参与商品本地事务的 JDBC 执行器。 */
  public ProductIndexOutboxMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 在商品本地事务中追加不可变商品索引事件。 */
  public int insert(
      long id,
      String eventId,
      String eventType,
      long productId,
      long productVersion,
      String source,
      String envelopeJson,
      OffsetDateTime occurredAt) {
    return jdbcTemplate.update(
        "insert into product_index_outbox(id,event_id,product_id,product_version,event_type,source,payload_json,status,attempts,next_retry_at,created_at,updated_at) values(?,?,?,?,?,?,?,'PENDING',0,?,?,?)",
        id,
        eventId,
        productId,
        productVersion,
        eventType,
        source,
        envelopeJson,
        Timestamp.from(occurredAt.toInstant()),
        Timestamp.from(occurredAt.toInstant()),
        Timestamp.from(occurredAt.toInstant()));
  }

  /** 领取待投递或 ACK 超时的事件，并以租约 owner 做隔离。 */
  public List<ProductIndexOutbox> claimDue(
      String owner, int limit, OffsetDateTime now, OffsetDateTime leaseUntil) {
    jdbcTemplate.update(
        "update product_index_outbox set lease_owner=?,lease_until=?,updated_at=? where next_retry_at<=? and (status in ('PENDING','UNKNOWN') or (status='PUBLISHED' and ack_deadline_at is not null and ack_deadline_at<?)) and (lease_until is null or lease_until<?) order by id limit ?",
        owner,
        Timestamp.from(leaseUntil.toInstant()),
        Timestamp.from(now.toInstant()),
        Timestamp.from(now.toInstant()),
        Timestamp.from(now.toInstant()),
        Timestamp.from(now.toInstant()),
        limit);
    return jdbcTemplate.query(
        "select id,event_id,product_id,product_version,event_type,source,payload_json,status,attempts,next_retry_at,last_published_at,ack_deadline_at from product_index_outbox where lease_owner=? and lease_until>? order by id limit ?",
        (resultSet, rowNum) ->
            new ProductIndexOutbox(
                resultSet.getLong("id"),
                resultSet.getString("event_id"),
                resultSet.getString("event_type"),
                resultSet.getLong("product_id"),
                resultSet.getLong("product_version"),
                resultSet.getString("source"),
                resultSet.getString("payload_json"),
                resultSet.getString("status"),
                resultSet.getInt("attempts"),
                timestamp(resultSet, "next_retry_at"),
                timestamp(resultSet, "last_published_at"),
                timestamp(resultSet, "ack_deadline_at")),
        owner,
        Timestamp.from(now.toInstant()),
        limit);
  }

  /** 仅更新仍由本次 dispatcher 租约持有的记录。 */
  public int markPublished(
      long id,
      String owner,
      OffsetDateTime publishedAt,
      OffsetDateTime ackDeadlineAt,
      OffsetDateTime nextRetryAt) {
    return jdbcTemplate.update(
        "update product_index_outbox set status='PUBLISHED',attempts=attempts+1,last_published_at=?,ack_deadline_at=?,next_retry_at=?,lease_owner=null,lease_until=null,updated_at=? where id=? and lease_owner=? and status in ('PENDING','UNKNOWN','PUBLISHED') and not exists (select 1 from product_index_consumption c where c.event_id=product_index_outbox.event_id and c.status in ('APPLIED','STALE'))",
        Timestamp.from(publishedAt.toInstant()),
        Timestamp.from(ackDeadlineAt.toInstant()),
        Timestamp.from(nextRetryAt.toInstant()),
        Timestamp.from(publishedAt.toInstant()),
        id,
        owner);
  }

  /** 原租约仍归当前 owner 时记录 UNKNOWN、累计尝试并释放租约；返回实际更新行数。 */
  public int markRetry(long id, String owner, String error, OffsetDateTime nextRetryAt) {
    return jdbcTemplate.update(
        "update product_index_outbox set status='UNKNOWN',attempts=attempts+1,last_error=?,next_retry_at=?,lease_owner=null,lease_until=null,updated_at=? where id=? and lease_owner=? and status in ('PENDING','UNKNOWN','PUBLISHED')",
        error == null ? "unknown" : error.substring(0, Math.min(1024, error.length())),
        Timestamp.from(nextRetryAt.toInstant()),
        Timestamp.from(OffsetDateTime.now().toInstant()),
        id,
        owner);
  }

  /** 消费端完成 ACK 时清除投递截止时间，保留 PUBLISHED 交付事实。 */
  public int markAcknowledged(String eventId) {
    return jdbcTemplate.update(
        "update product_index_outbox set status='PUBLISHED',ack_deadline_at=null,lease_owner=null,lease_until=null,updated_at=? where event_id=? and status in ('PENDING','UNKNOWN','PUBLISHED')",
        Timestamp.from(OffsetDateTime.now().toInstant()),
        eventId);
  }

  /** 用于核验死信是否与原事务提交的不可变事件一致。 */
  public String findEnvelopeJson(String eventId) {
    List<String> envelopes =
        jdbcTemplate.query(
            "select payload_json from product_index_outbox where event_id=?",
            (resultSet, rowNumber) -> resultSet.getString(1),
            eventId);
    return envelopes.isEmpty() ? null : envelopes.get(0);
  }

  /** 创建已有 MySQL volume 缺少的可靠性表。 */
  public void ensureTable() {
    jdbcTemplate.execute(
        "create table if not exists product_index_outbox (id bigint primary key,event_id varchar(128) not null unique,product_id bigint not null,product_version int not null,event_type varchar(32) not null,source varchar(32) not null,payload_json json not null,status varchar(32) not null,attempts int not null default 0,next_retry_at datetime(3) not null,last_published_at datetime(3) null,ack_deadline_at datetime(3) null,lease_owner varchar(64) null,lease_until datetime(3) null,last_error varchar(1024) null,created_at datetime(3) not null,updated_at datetime(3) not null,key idx_product_index_outbox_retry(status,next_retry_at,lease_until),key idx_product_index_outbox_ack(status,ack_deadline_at),key idx_product_index_product(product_id,product_version))");
  }

  private static OffsetDateTime timestamp(java.sql.ResultSet resultSet, String column)
      throws java.sql.SQLException {
    Timestamp value = resultSet.getTimestamp(column);
    return value == null ? null : value.toInstant().atOffset(java.time.ZoneOffset.UTC);
  }
}
