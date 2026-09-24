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

  /** 为已有本地数据库幂等准备普通库存请求和投影修复表。 */
  public void ensureStockReliabilityTables() {
    execute(
        "CREATE TABLE IF NOT EXISTS stock_reservation_request ("
            + "id BIGINT PRIMARY KEY,order_no VARCHAR(64) NOT NULL UNIQUE,"
            + "scene VARCHAR(32) NOT NULL,items_snapshot JSON NOT NULL,items_hash CHAR(64) NOT NULL,"
            + "status VARCHAR(32) NOT NULL,result_code VARCHAR(64),last_error VARCHAR(512),"
            + "next_retry_at DATETIME(3),lease_owner VARCHAR(128),lease_until DATETIME(3),"
            + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,"
            + "KEY idx_stock_request_status(status,next_retry_at,lease_until))");
    execute(
        "CREATE TABLE IF NOT EXISTS stock_cache_repair ("
            + "id BIGINT PRIMARY KEY,sku_id BIGINT NOT NULL,target_available INT NOT NULL,"
            + "source_version INT NOT NULL,status VARCHAR(16) NOT NULL,attempts INT NOT NULL DEFAULT 0,"
            + "next_retry_at DATETIME(3) NOT NULL,lease_owner VARCHAR(128),"
            + "lease_until DATETIME(3),last_error VARCHAR(512),created_at DATETIME(3) NOT NULL,"
            + "updated_at DATETIME(3) NOT NULL,"
            + "UNIQUE KEY uk_stock_cache_version(sku_id,source_version),"
            + "KEY idx_stock_cache_retry(status,next_retry_at,lease_until))");
  }

  /** 在独立事务中认领订单号与不可变 SKU 快照。 */
  public int insertReservationRequest(
      String orderNo,
      String scene,
      String itemsSnapshot,
      String itemsHash,
      OffsetDateTime createdAt) {
    return update(
        "INSERT IGNORE INTO stock_reservation_request"
            + "(id,order_no,scene,items_snapshot,items_hash,status,next_retry_at,"
            + "created_at,updated_at) VALUES(?,?,?,?,?,'INIT',?,?,?)",
        Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()),
        orderNo,
        scene,
        itemsSnapshot,
        itemsHash,
        createdAt.plusMinutes(2),
        createdAt,
        createdAt);
  }

  /** 按订单号读取普通预扣请求状态。 */
  public Map<String, Object> findReservationRequest(String orderNo) {
    List<Map<String, Object>> rows =
        queryForList(
            "SELECT order_no,scene,items_snapshot,items_hash,status,result_code,"
                + "next_retry_at,lease_owner,lease_until,created_at,updated_at"
                + " FROM stock_reservation_request WHERE order_no=?",
            orderNo);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 锁定同一订单号的预扣请求，串行化确认、回滚和预扣。 */
  public Map<String, Object> findReservationRequestForUpdate(String orderNo) {
    List<Map<String, Object>> rows =
        queryForList(
            "SELECT order_no,scene,items_snapshot,items_hash,status,result_code,"
                + "next_retry_at,lease_owner,lease_until,created_at,updated_at"
                + " FROM stock_reservation_request WHERE order_no=? FOR UPDATE",
            orderNo);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 按当前状态迁移预扣请求，库存变化和此更新处于同一事务。 */
  public int updateReservationRequestStatus(
      String orderNo,
      String sourceStatus,
      String targetStatus,
      String resultCode,
      OffsetDateTime updatedAt) {
    return update(
        "UPDATE stock_reservation_request SET status=?,result_code=?,"
            + "lease_owner=NULL,lease_until=NULL,next_retry_at=NULL,updated_at=?"
            + " WHERE order_no=? AND status=?",
        targetStatus,
        resultCode,
        updatedAt,
        orderNo,
        sourceStatus);
  }

  /** 锁定库存事实行，先验证整笔请求再变更任一 SKU。 */
  public Map<String, Object> findStockForUpdate(Long skuId) {
    List<Map<String, Object>> rows =
        queryForList(
            "SELECT sku_id,available_quantity,reserved_quantity,sold_quantity,version"
                + " FROM stock_sku WHERE sku_id=? FOR UPDATE",
            skuId);
    return rows.isEmpty() ? null : rows.get(0);
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

  /** 查询带乐观锁版本的库存行。 */
  public Map<String, Object> findStock(Long skuId) {
    List<Map<String, Object>> rows = queryForList(
        "select sku_id,available_quantity,reserved_quantity,sold_quantity,version from stock_sku where sku_id=?",
        skuId);
    return rows.isEmpty() ? null : rows.get(0);
  }

  /** 按库存版本条件更新普通库存。 */
  public int updateAvailableWithVersion(Long skuId, int quantity, int expectedVersion, OffsetDateTime updatedAt) {
    return update("update stock_sku set available_quantity=available_quantity-?,reserved_quantity=reserved_quantity+?,version=version+1,updated_at=? where sku_id=? and version=? and available_quantity>=?",
        quantity, quantity, updatedAt, skuId, expectedVersion, quantity);
  }

  /** 按库存版本条件结算预扣库存。 */
  public int updateSettlementWithVersion(Long skuId, String flowType, int quantity, int expectedVersion, OffsetDateTime updatedAt) {
    String fields = "CONFIRM".equals(flowType)
        ? "reserved_quantity=reserved_quantity-?,sold_quantity=sold_quantity+?"
        : "reserved_quantity=reserved_quantity-?,available_quantity=available_quantity+?";
    return update("update stock_sku set " + fields + ",version=version+1,updated_at=? where sku_id=? and version=? and reserved_quantity>=?",
        quantity, quantity, updatedAt, skuId, expectedVersion, quantity);
  }

  /** 在库存事实事务内写入缓存修复意图。 */
  public int insertCacheRepair(long skuId, int targetAvailable, int sourceVersion, OffsetDateTime occurredAt) {
    return update("insert into stock_cache_repair(id,sku_id,target_available,source_version,status,attempts,next_retry_at,created_at,updated_at) values(?,?,?,?,'PENDING',0,?,?,?) on duplicate key update target_available=values(target_available),status='PENDING',next_retry_at=values(next_retry_at),last_error=null,updated_at=values(updated_at)",
        Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()), skuId, targetAvailable, sourceVersion, occurredAt, occurredAt, occurredAt);
  }

  /** 在 MySQL 内以单条 INSERT SELECT 排队当前库存版本，避免 88,476 次往返。 */
  public void enqueueCurrentCacheRepairs(OffsetDateTime occurredAt) {
    update(
        "INSERT INTO stock_cache_repair(id,sku_id,target_available,source_version,status,attempts,"
            + "next_retry_at,created_at,updated_at)"
            + " SELECT UUID_SHORT(),sku_id,available_quantity,version,'PENDING',0,?,?,?"
            + " FROM stock_sku"
            + " ON DUPLICATE KEY UPDATE target_available=VALUES(target_available),status='PENDING',"
            + "next_retry_at=VALUES(next_retry_at),last_error=NULL,updated_at=VALUES(updated_at)",
        occurredAt,
        occurredAt,
        occurredAt);
  }

  /** 整批 Redis EVAL 都成功后仅确认 MySQL 仍处于来源版本的投影记录。 */
  public int markInitialCacheRangeApplied(
      long firstSkuId, long lastSkuId, OffsetDateTime updatedAt) {
    return update(
        "UPDATE stock_cache_repair repair JOIN stock_sku stock"
            + " ON repair.sku_id=stock.sku_id AND repair.source_version=stock.version"
            + " SET repair.status='APPLIED',repair.lease_owner=NULL,repair.lease_until=NULL,"
            + "repair.updated_at=? WHERE repair.status='PENDING'"
            + " AND repair.sku_id>=? AND repair.sku_id<=?",
        updatedAt,
        firstSkuId,
        lastSkuId);
  }

  /** 仅在整批 Redis 投影已返回时标记对应版本，失败批次保留 PENDING。 */
  public void finishInitialCacheRepairs(
      List<Map<String, Object>> stockRows, List<Object> outcomes, OffsetDateTime updatedAt) {
    if (stockRows.isEmpty()) return;
    jdbcTemplate.batchUpdate(
        "UPDATE stock_cache_repair SET status=?,lease_owner=NULL,lease_until=NULL,updated_at=?"
            + " WHERE sku_id=? AND source_version=? AND status='PENDING'",
        new org.springframework.jdbc.core.BatchPreparedStatementSetter() {
          @Override
          public void setValues(java.sql.PreparedStatement statement, int index)
              throws java.sql.SQLException {
            Map<String, Object> stockRow = stockRows.get(index);
            Object outcome = outcomes.get(index);
            statement.setString(1, Long.valueOf(0L).equals(outcome) ? "STALE" : "APPLIED");
            statement.setTimestamp(
                2, java.sql.Timestamp.valueOf(updatedAt.toLocalDateTime()));
            statement.setLong(3, ((Number) stockRow.get("sku_id")).longValue());
            statement.setInt(4, ((Number) stockRow.get("version")).intValue());
          }

          @Override
          public int getBatchSize() {
            return stockRows.size();
          }
        });
  }

  /** 读取待修复的普通库存投影。 */
  public List<Map<String, Object>> findPendingCacheRepairs(OffsetDateTime now, int limit) {
    return queryForList("select id,sku_id,target_available,source_version,attempts from stock_cache_repair where status='PENDING' and next_retry_at<=? and (lease_until is null or lease_until<?) order by sku_id,source_version limit ?", now, now, Math.max(1, limit));
  }

  /** 以数据库租约认领缓存修复记录。 */
  public int claimCacheRepair(long id, String owner, OffsetDateTime leaseUntil, OffsetDateTime now) {
    return update("update stock_cache_repair set lease_owner=?,lease_until=?,attempts=attempts+1,updated_at=? where id=? and status='PENDING' and next_retry_at<=? and (lease_until is null or lease_until<?)", owner, leaseUntil, now, id, now, now);
  }

  /** 标记缓存修复结果。 */
  public int finishCacheRepair(long id, String owner, String status, String error, OffsetDateTime nextRetry, OffsetDateTime now) {
    return update("update stock_cache_repair set status=?,last_error=?,next_retry_at=?,lease_owner=null,lease_until=null,updated_at=? where id=? and status='PENDING' and lease_owner=?", status, error, nextRetry, now, id, owner);
  }

  /** 查询指定订单的库存流水汇总。 */
  public List<Map<String, Object>> findReservationFlows(String orderNo) {
    return queryForList("select sku_id, sum(case when flow_type='RESERVE' then quantity else 0 end) reserved_quantity, sum(case when flow_type='CONFIRM' then quantity else 0 end) confirmed_quantity, sum(case when flow_type='ROLLBACK' then quantity else 0 end) rolled_back_quantity from stock_flow where order_no=? group by sku_id", orderNo);
  }

  /** 读取普通库存的当前版本快照。 */
  public List<Map<String, Object>> findAvailableStockWithVersion() {
    return queryForList("select sku_id,available_quantity,version from stock_sku order by sku_id");
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

  /**
   * Atomically claims a settlement flow and applies its stock mutation.
   *
   * <p>The flow row is inserted first with its unique idempotency key. A duplicate claim is
   * treated as an already completed operation. Both statements run in the caller's transaction,
   * so a failed stock update rolls the flow claim back as well.
   */
  public int settleQuantity(
      Long skuId,
      String orderNo,
      String flowType,
      int quantity,
      String idempotencyKey,
      OffsetDateTime updatedAt) {
    int inserted =
        update(
            "insert ignore into stock_flow(id,sku_id,order_no,flow_type,quantity,idempotency_key,created_at)"
                + " values(?,?,?,?,?,?,?)",
            Math.abs(java.util.UUID.randomUUID().getMostSignificantBits()),
            skuId,
            orderNo,
            flowType,
            quantity,
            idempotencyKey,
            updatedAt);
    if (inserted == 0) return 0;
    String quantityUpdate =
        "CONFIRM".equals(flowType)
            ? "reserved_quantity=reserved_quantity-?,sold_quantity=sold_quantity+?"
            : "reserved_quantity=reserved_quantity-?,available_quantity=available_quantity+?";
    int changed =
        update(
            "update stock_sku set "
                + quantityUpdate
                + ",version=version+1,updated_at=? where sku_id=? and reserved_quantity>=?",
            quantity,
            quantity,
            updatedAt,
            skuId,
            quantity);
    if (changed != 1) return -1;
    return 1;
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

  /** 查询指定订单仍处于预扣状态的普通库存流水。 */
  public List<Map<String, Object>> findOpenReservations(String orderNo) {
    return queryForList(
        "select sku_id,sum(case when flow_type='RESERVE' then quantity when flow_type in"
            + " ('CONFIRM','ROLLBACK') then -quantity else 0 end) quantity from stock_flow"
            + " where order_no=? group by order_no,sku_id having quantity>0",
        orderNo);
  }

  /** 查询指定订单的普通库存预扣快照，供预扣重放做请求一致性校验。 */
  public List<Map<String, Object>> findReservationSnapshot(String orderNo) {
    return queryForList(
        "select sku_id,sum(case when flow_type='RESERVE' then quantity else 0 end) reserved_quantity,"
            + "sum(case when flow_type in ('CONFIRM','ROLLBACK') then quantity else 0 end) settled_quantity"
            + " from stock_flow where order_no=? group by order_no,sku_id",
        orderNo);
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
