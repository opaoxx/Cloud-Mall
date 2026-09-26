package com.cloudmall.product.mapper;

import java.sql.Timestamp;
import java.time.OffsetDateTime;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

/** 商品索引消费 ACK 的持久化入口。 */
@Repository
public class ProductIndexConsumptionMapper {
  private final JdbcTemplate jdbcTemplate;

  public ProductIndexConsumptionMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 按 eventId 幂等写入消费状态。 */
  public int record(
      String eventId, long productId, long eventVersion, long appliedVersion, String status, String error, OffsetDateTime occurredAt) {
    return jdbcTemplate.update(
        "insert into product_index_consumption(event_id,product_id,event_version,applied_version,status,attempts,last_error,created_at,updated_at)"
            + " values(?,?,?,?,?,1,?,?,?) on duplicate key update status=if(status in ('APPLIED','STALE'),status,values(status)),last_error=if(status in ('APPLIED','STALE'),last_error,values(last_error)),attempts=attempts+1,updated_at=values(updated_at)",
        eventId,
        productId,
        eventVersion < 0 ? null : eventVersion,
        appliedVersion < 0 ? null : appliedVersion,
        status,
        error == null ? null : error.substring(0, Math.min(512, error.length())),
        Timestamp.from(occurredAt.toInstant()),
        Timestamp.from(occurredAt.toInstant()));
  }

  public void ensureTable() {
    jdbcTemplate.execute("create table if not exists product_index_consumption (event_id varchar(128) primary key,product_id bigint not null,event_version int null,applied_version int null,status varchar(32) not null,attempts int not null default 0,last_error varchar(1024) null,lease_owner varchar(64) null,lease_until datetime(3) null,created_at datetime(3) not null,updated_at datetime(3) not null,key idx_product_index_consume_retry(status,updated_at,lease_until),key idx_product_index_consume_product(product_id,applied_version))");
  }

  public String findStatus(String eventId) {
    java.util.List<String> statuses = jdbcTemplate.query("select status from product_index_consumption where event_id=?", (resultSet, rowNum) -> resultSet.getString(1), eventId);
    return statuses.isEmpty() ? null : statuses.get(0);
  }
}
