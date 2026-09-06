package com.cloudmall.stock.mapper;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.stereotype.Repository;

/** 库存条件更新和流水 SQL 的统一持久化入口。 */
@Repository
public class StockSqlMapper {
  /** Spring JDBC 执行器。 */
  private final JdbcTemplate jdbcTemplate;

  /** 创建库存 SQL Mapper。 */
  public StockSqlMapper(JdbcTemplate jdbcTemplate) {
    this.jdbcTemplate = jdbcTemplate;
  }

  /** 执行库存查询。 */
  private <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
    return jdbcTemplate.query(sql, rowMapper, arguments);
  }

  /** 查询库存行集合。 */
  private List<Map<String, Object>> queryForList(String sql, Object... arguments) {
    return jdbcTemplate.queryForList(sql, arguments);
  }

  /** 查询库存单值。 */
  private <T> T queryForObject(String sql, Class<T> resultType, Object... arguments) {
    return jdbcTemplate.queryForObject(sql, resultType, arguments);
  }

  /** 执行库存写操作。 */
  private int update(String sql, Object... arguments) {
    return jdbcTemplate.update(sql, arguments);
  }

  /** 执行库存 DDL。 */
  private void execute(String sql) {
    jdbcTemplate.execute(sql);
  }

  /** 原子预扣可用库存。 */
  public int reserveQuantity(Long skuId, int quantity, OffsetDateTime updatedAt) {
    return update(
        "update stock_sku set"
            + " available_quantity=available_quantity-?,reserved_quantity=reserved_quantity+?,version=version+1,updated_at=?"
            + " where sku_id=? and available_quantity>=?",
        quantity,
        quantity,
        updatedAt,
        skuId,
        quantity);
  }

  /** 将预扣库存确认为已售库存。 */
  public int confirmQuantity(Long skuId, int quantity, OffsetDateTime updatedAt) {
    return update(
        "update stock_sku set"
            + " reserved_quantity=reserved_quantity-?,sold_quantity=sold_quantity+?,version=version+1,updated_at=?"
            + " where sku_id=? and reserved_quantity>=?",
        quantity,
        quantity,
        updatedAt,
        skuId,
        quantity);
  }

  /** 回滚预扣库存。 */
  public int rollbackQuantity(Long skuId, int quantity, OffsetDateTime updatedAt) {
    return update(
        "update stock_sku set"
            + " reserved_quantity=reserved_quantity-?,available_quantity=available_quantity+?,version=version+1,updated_at=?"
            + " where sku_id=? and reserved_quantity>=?",
        quantity,
        quantity,
        updatedAt,
        skuId,
        quantity);
  }

  /** 保存秒杀库存预扣台账并保持请求幂等。 */
  public int saveSeckillPending(
      long activityId,
      long skuId,
      long userId,
      String orderNo,
      String idempotencyKey,
      OffsetDateTime occurredAt) {
    return update(
        "insert into"
            + " seckill_reservation(id,activity_id,sku_id,user_id,order_no,idempotency_key,status,created_at,updated_at)"
            + " values(?,?,?,?,?,?,'PENDING',?,?) on duplicate key update"
            + " order_no=if(status='ACCEPTED',order_no,values(order_no)),status=if(status='ACCEPTED','ACCEPTED','PENDING'),updated_at=values(updated_at)",
        Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()),
        activityId,
        skuId,
        userId,
        orderNo,
        idempotencyKey,
        occurredAt,
        occurredAt);
  }

  /** 标记秒杀请求已接受。 */
  public int markSeckillAccepted(
      long activityId, long skuId, long userId, String idempotencyKey, OffsetDateTime updatedAt) {
    return update(
        "update seckill_reservation set status='ACCEPTED',updated_at=? where activity_id=? and"
            + " sku_id=? and user_id=? and idempotency_key=?",
        updatedAt,
        activityId,
        skuId,
        userId,
        idempotencyKey);
  }

  /** 标记秒杀请求已拒绝。 */
  public int markSeckillRejected(
      long activityId, long skuId, long userId, String idempotencyKey, OffsetDateTime updatedAt) {
    return update(
        "update seckill_reservation set status='REJECTED',updated_at=? where activity_id=? and"
            + " sku_id=? and user_id=? and idempotency_key=? and status='PENDING'",
        updatedAt,
        activityId,
        skuId,
        userId,
        idempotencyKey);
  }

  /** 判断库存流水幂等键是否已存在。 */
  public boolean flowExists(String idempotencyKey) {
    return !query(
            "select id from stock_flow where idempotency_key=?",
            (resultSet, rowNumber) -> resultSet.getLong(1),
            idempotencyKey)
        .isEmpty();
  }

  /** 写入库存流水并保持幂等。 */
  public int insertFlow(
      Long skuId,
      String orderNo,
      String flowType,
      int quantity,
      String idempotencyKey,
      OffsetDateTime createdAt) {
    return update(
        "insert into stock_flow(id,sku_id,order_no,flow_type,quantity,idempotency_key,created_at)"
            + " values(?,?,?,?,?,?,?) on duplicate key update"
            + " idempotency_key=values(idempotency_key)",
        Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()),
        skuId,
        orderNo,
        flowType,
        quantity,
        idempotencyKey,
        createdAt);
  }

  /** 创建秒杀恢复所需的台账表。 */
  public void ensureSeckillLedgerTable() {
    execute(
        "CREATE TABLE IF NOT EXISTS seckill_reservation (id BIGINT PRIMARY KEY, activity_id BIGINT"
            + " NOT NULL, sku_id BIGINT NOT NULL, user_id BIGINT NOT NULL, order_no VARCHAR(64) NOT"
            + " NULL, idempotency_key VARCHAR(128) NOT NULL, status VARCHAR(16) NOT NULL,"
            + " created_at DATETIME(3) NOT NULL, updated_at DATETIME(3) NOT NULL, UNIQUE KEY"
            + " uk_seckill_request(activity_id,user_id,idempotency_key), UNIQUE KEY"
            + " uk_seckill_order(order_no), KEY idx_seckill_stock(activity_id,sku_id,status))");
  }

  /** 查询仍处于预扣状态的普通库存流水。 */
  public List<Map<String, Object>> findOpenReservations() {
    return queryForList(
        "select order_no,sku_id,sum(case when flow_type='RESERVE' then quantity when flow_type in"
            + " ('CONFIRM','ROLLBACK') then -quantity else 0 end) quantity from stock_flow group by"
            + " order_no,sku_id having quantity>0");
  }

  /** 将已接受的秒杀记录写回持久化台账。 */
  public int insertAcceptedReservation(
      long activityId,
      long skuId,
      long userId,
      String orderNo,
      String idempotencyKey,
      OffsetDateTime occurredAt) {
    return update(
        "insert ignore into seckill_reservation"
            + " (id,activity_id,sku_id,user_id,order_no,idempotency_key,status,created_at,updated_at)"
            + " values(?,?,?,?,?,?, 'ACCEPTED',?,?)",
        Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()),
        activityId,
        skuId,
        userId,
        orderNo,
        idempotencyKey,
        occurredAt,
        occurredAt);
  }

  /** 查询普通库存可用数量。 */
  public List<Map<String, Object>> findAvailableStock() {
    return queryForList("select sku_id,available_quantity from stock_sku");
  }

  /** 统计已接受的秒杀预扣数量。 */
  public Long countAcceptedReservations(long activityId, long skuId) {
    return queryForObject(
        "select count(*) from seckill_reservation where activity_id=? and sku_id=? and"
            + " status='ACCEPTED'",
        Long.class,
        activityId,
        skuId);
  }
}
