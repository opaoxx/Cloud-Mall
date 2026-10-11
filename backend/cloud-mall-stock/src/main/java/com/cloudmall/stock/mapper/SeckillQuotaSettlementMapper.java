package com.cloudmall.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 秒杀配额结算事实和恢复租约的 SQL 入口。 */
@Mapper
public interface SeckillQuotaSettlementMapper extends BaseMapper<SeckillQuotaSettlementPO> {
  /** 启动时幂等创建结算表。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS seckill_quota_settlement (id BIGINT PRIMARY KEY,order_no"
          + " VARCHAR(64) NOT NULL,activity_id BIGINT NOT NULL,sku_id BIGINT NOT NULL,user_id"
          + " BIGINT NOT NULL,idempotency_key VARCHAR(128) NOT NULL,quantity INT NOT NULL,status"
          + " VARCHAR(32) NOT NULL,revision BIGINT NOT NULL,operation_key"
          + " VARCHAR(160),redis_revision BIGINT,last_error VARCHAR(512),next_retry_at"
          + " DATETIME(3),lease_owner VARCHAR(128),lease_until DATETIME(3),created_at DATETIME(3)"
          + " NOT NULL,updated_at DATETIME(3) NOT NULL,UNIQUE KEY"
          + " uk_seckill_settlement_order(order_no),UNIQUE KEY"
          + " uk_seckill_settlement_operation(operation_key),KEY"
          + " idx_seckill_settlement_retry(status,next_retry_at,lease_until),KEY"
          + " idx_seckill_settlement_activity(activity_id,sku_id,status))")
  int ensureTable();

  /** 启动恢复已有 ACCEPTED 受理事实，仍不猜测 Redis 释放结果。 */
  @Insert(
      "INSERT IGNORE INTO seckill_quota_settlement"
          + " (id,order_no,activity_id,sku_id,user_id,idempotency_key,quantity,status,revision,created_at,updated_at)"
          + " SELECT"
          + " UUID_SHORT(),r.order_no,r.activity_id,r.sku_id,r.user_id,r.idempotency_key,1,'OPEN',1,#{occurredAt},#{occurredAt}"
          + " FROM seckill_reservation r LEFT JOIN seckill_quota_settlement s ON"
          + " s.order_no=r.order_no WHERE r.status='ACCEPTED' AND s.order_no IS NULL")
  int insertMissingAcceptedOpen(LocalDateTime occurredAt);

  /** 查询并锁定指定订单的结算事实。 */
  @Select("SELECT * FROM seckill_quota_settlement WHERE order_no=#{orderNo} FOR UPDATE")
  SeckillQuotaSettlementPO findSettlementForUpdate(@Param("orderNo") String orderNo);

  /** 查询指定订单的结算事实。 */
  @Select("SELECT * FROM seckill_quota_settlement WHERE order_no=#{orderNo}")
  SeckillQuotaSettlementPO findSettlement(@Param("orderNo") String orderNo);

  /** 从已锁定的 ACCEPTED 受理事实幂等补建 OPEN 结算事实。 */
  @Insert(
      "INSERT IGNORE INTO seckill_quota_settlement"
          + " (id,order_no,activity_id,sku_id,user_id,idempotency_key,quantity,status,revision,created_at,updated_at)"
          + " SELECT"
          + " UUID_SHORT(),order_no,activity_id,sku_id,user_id,idempotency_key,1,'OPEN',1,#{occurredAt},#{occurredAt}"
          + " FROM seckill_reservation WHERE order_no=#{orderNo} AND status='ACCEPTED'")
  int insertOpenIfAbsent(
      @Param("orderNo") String orderNo, @Param("occurredAt") LocalDateTime occurredAt);

  /** 原受理来源一致时，OPEN 按唯一 confirm 操作键迁移为 CONFIRMED。 */
  @Update(
      "UPDATE seckill_quota_settlement SET"
          + " status='CONFIRMED',operation_key=#{operationKey},revision=revision+1,updated_at=NOW(3)"
          + " WHERE order_no=#{orderNo} AND status='OPEN' AND revision=#{expectedRevision} AND"
          + " (operation_key IS NULL OR operation_key=#{operationKey}) AND EXISTS (SELECT 1 FROM"
          + " seckill_reservation accepted WHERE accepted.order_no=#{orderNo} AND BINARY"
          + " accepted.order_no=BINARY #{orderNo} AND BINARY accepted.order_no=BINARY"
          + " seckill_quota_settlement.order_no AND BINARY accepted.status IN (BINARY"
          + " 'ACCEPTED',BINARY 'PUBLISHED',BINARY 'ORDER_CREATED') AND"
          + " accepted.activity_id=seckill_quota_settlement.activity_id AND"
          + " accepted.sku_id=seckill_quota_settlement.sku_id AND"
          + " accepted.user_id=seckill_quota_settlement.user_id AND BINARY"
          + " accepted.idempotency_key=BINARY seckill_quota_settlement.idempotency_key)")
  int transitionOpenToConfirmed(
      @Param("orderNo") String orderNo,
      @Param("operationKey") String operationKey,
      @Param("expectedRevision") long expectedRevision);

  /** 原受理来源一致时，将保留 confirm 方向的投影阻塞事实直接确认，不写 Redis。 */
  @Update(
      "UPDATE seckill_quota_settlement SET"
          + " status='CONFIRMED',last_error=NULL,lease_owner=NULL,lease_until=NULL,next_retry_at=NULL,updated_at=NOW(3)"
          + " WHERE order_no=#{orderNo} AND status='PROJECTION_BLOCKED' AND"
          + " revision=#{expectedRevision} AND operation_key=#{operationKey} AND EXISTS (SELECT 1"
          + " FROM seckill_reservation accepted WHERE accepted.order_no=#{orderNo} AND BINARY"
          + " accepted.order_no=BINARY #{orderNo} AND BINARY accepted.order_no=BINARY"
          + " seckill_quota_settlement.order_no AND BINARY accepted.status IN (BINARY"
          + " 'ACCEPTED',BINARY 'PUBLISHED',BINARY 'ORDER_CREATED') AND"
          + " accepted.activity_id=seckill_quota_settlement.activity_id AND"
          + " accepted.sku_id=seckill_quota_settlement.sku_id AND"
          + " accepted.user_id=seckill_quota_settlement.user_id AND BINARY"
          + " accepted.idempotency_key=BINARY seckill_quota_settlement.idempotency_key)")
  int transitionBlockedToConfirmed(
      @Param("orderNo") String orderNo,
      @Param("operationKey") String operationKey,
      @Param("expectedRevision") long expectedRevision);

  /** 原受理来源一致时，OPEN 按唯一 rollback 操作键迁移为 RELEASE_PENDING。 */
  @Update(
      "UPDATE seckill_quota_settlement SET"
          + " status='RELEASE_PENDING',operation_key=#{operationKey},revision=2,next_retry_at=NOW(3),updated_at=NOW(3)"
          + " WHERE order_no=#{orderNo} AND status='OPEN' AND revision=#{expectedRevision} AND"
          + " (operation_key IS NULL OR operation_key=#{operationKey}) AND EXISTS (SELECT 1 FROM"
          + " seckill_reservation accepted WHERE accepted.order_no=#{orderNo} AND BINARY"
          + " accepted.order_no=BINARY #{orderNo} AND BINARY accepted.order_no=BINARY"
          + " seckill_quota_settlement.order_no AND BINARY accepted.status IN (BINARY"
          + " 'ACCEPTED',BINARY 'PUBLISHED',BINARY 'ORDER_CREATED') AND"
          + " accepted.activity_id=seckill_quota_settlement.activity_id AND"
          + " accepted.sku_id=seckill_quota_settlement.sku_id AND"
          + " accepted.user_id=seckill_quota_settlement.user_id AND BINARY"
          + " accepted.idempotency_key=BINARY seckill_quota_settlement.idempotency_key)")
  int transitionToReleasePending(
      @Param("orderNo") String orderNo,
      @Param("operationKey") String operationKey,
      @Param("expectedRevision") long expectedRevision);

  /** 以精确订单/操作键、revision及有效本owner租约及原完整tuple快照CAS标记Redis释放已完成。 */
  @Update(
      "UPDATE seckill_quota_settlement SET"
          + " status='RELEASED',redis_revision=#{redisRevision},last_error=NULL,lease_owner=NULL,lease_until=NULL,next_retry_at=NULL,updated_at=NOW(3)"
          + " WHERE order_no=#{orderNo} AND status IN ('RELEASE_PENDING','PROJECTION_BLOCKED') AND"
          + " revision=#{expectedRevision} AND operation_key=#{operationKey} AND"
          + " lease_owner=#{owner} AND BINARY order_no=BINARY #{orderNo} AND BINARY"
          + " operation_key=BINARY #{operationKey} AND BINARY lease_owner=BINARY #{owner} AND"
          + " BINARY status IN (BINARY 'RELEASE_PENDING',BINARY 'PROJECTION_BLOCKED') AND"
          + " lease_until IS NOT NULL AND lease_until>NOW(3) AND id=#{original.id} AND"
          + " activity_id=#{original.activityId} AND sku_id=#{original.skuId} AND"
          + " user_id=#{original.userId} AND quantity=#{original.quantity} AND"
          + " revision=#{original.revision} AND created_at=#{original.createdAt} AND BINARY"
          + " order_no=BINARY #{original.orderNo} AND BINARY operation_key=BINARY"
          + " #{original.operationKey} AND BINARY idempotency_key=BINARY"
          + " #{original.idempotencyKey}")
  int markReleased(
      @Param("orderNo") String orderNo,
      @Param("operationKey") String operationKey,
      @Param("expectedRevision") long expectedRevision,
      @Param("redisRevision") long redisRevision,
      @Param("owner") String owner,
      @Param("original") SeckillQuotaSettlementPO original);

  /** 在精确订单/操作键、revision及有效本owner租约及原完整tuple快照下保留方向并标记投影阻塞。 */
  @Update(
      "UPDATE seckill_quota_settlement SET"
          + " status='PROJECTION_BLOCKED',last_error=#{error},next_retry_at=#{nextRetryAt},lease_owner=NULL,lease_until=NULL,updated_at=NOW(3)"
          + " WHERE order_no=#{orderNo} AND revision=#{expectedRevision} AND"
          + " operation_key=#{operationKey} AND lease_owner=#{owner} AND status IN"
          + " ('RELEASE_PENDING','PROJECTION_BLOCKED') AND BINARY order_no=BINARY #{orderNo} AND"
          + " BINARY operation_key=BINARY #{operationKey} AND BINARY lease_owner=BINARY #{owner}"
          + " AND BINARY status IN (BINARY 'RELEASE_PENDING',BINARY 'PROJECTION_BLOCKED') AND"
          + " lease_until IS NOT NULL AND lease_until>NOW(3) AND id=#{original.id} AND"
          + " activity_id=#{original.activityId} AND sku_id=#{original.skuId} AND"
          + " user_id=#{original.userId} AND quantity=#{original.quantity} AND"
          + " revision=#{original.revision} AND created_at=#{original.createdAt} AND BINARY"
          + " order_no=BINARY #{original.orderNo} AND BINARY operation_key=BINARY"
          + " #{original.operationKey} AND BINARY idempotency_key=BINARY"
          + " #{original.idempotencyKey}")
  int markProjectionBlocked(
      @Param("orderNo") String orderNo,
      @Param("operationKey") String operationKey,
      @Param("expectedRevision") long expectedRevision,
      @Param("error") String error,
      @Param("nextRetryAt") LocalDateTime nextRetryAt,
      @Param("owner") String owner,
      @Param("original") SeckillQuotaSettlementPO original);

  /** 查找到期的 rollback 恢复任务。 */
  @Select(
      "SELECT * FROM seckill_quota_settlement WHERE status IN"
          + " ('RELEASE_PENDING','PROJECTION_BLOCKED') AND next_retry_at<=#{now} AND (lease_until"
          + " IS NULL OR lease_until<#{now}) ORDER BY id LIMIT #{limit}")
  List<SeckillQuotaSettlementPO> findDue(
      @Param("now") LocalDateTime now, @Param("limit") int limit);

  /** 查询原到期条件下游标后的候选；不隐藏坏事实，也不修改重试/租约状态。 */
  @Select(
      "SELECT * FROM seckill_quota_settlement WHERE status IN"
          + " ('RELEASE_PENDING','PROJECTION_BLOCKED') AND next_retry_at<=#{now} AND (lease_until"
          + " IS NULL OR lease_until<#{now}) AND id>#{afterId} ORDER BY id LIMIT #{limit}")
  List<SeckillQuotaSettlementPO> findDueAfter(
      @Param("now") LocalDateTime now, @Param("afterId") long afterId, @Param("limit") int limit);

  /** 原受理状态及订单/活动/SKU/用户/幂等来源一致时，按原revision/状态/租约认领恢复任务。 */
  @Update(
      "UPDATE seckill_quota_settlement SET"
          + " lease_owner=#{owner},lease_until=#{leaseUntil},updated_at=#{now} WHERE"
          + " order_no=#{orderNo} AND revision=#{revision} AND operation_key=#{operationKey} AND"
          + " status IN ('RELEASE_PENDING','PROJECTION_BLOCKED') AND next_retry_at<=#{now} AND"
          + " (lease_until IS NULL OR lease_until<#{now}) AND EXISTS (SELECT 1 FROM"
          + " seckill_reservation accepted WHERE accepted.order_no=#{orderNo} AND BINARY"
          + " accepted.order_no=BINARY #{orderNo} AND BINARY accepted.order_no=BINARY"
          + " seckill_quota_settlement.order_no AND BINARY accepted.status IN (BINARY"
          + " 'ACCEPTED',BINARY 'PUBLISHED',BINARY 'ORDER_CREATED') AND"
          + " accepted.activity_id=seckill_quota_settlement.activity_id AND"
          + " accepted.sku_id=seckill_quota_settlement.sku_id AND"
          + " accepted.user_id=seckill_quota_settlement.user_id AND BINARY"
          + " accepted.idempotency_key=BINARY seckill_quota_settlement.idempotency_key)")
  int claim(
      @Param("orderNo") String orderNo,
      @Param("operationKey") String operationKey,
      @Param("revision") long revision,
      @Param("owner") String owner,
      @Param("leaseUntil") LocalDateTime leaseUntil,
      @Param("now") LocalDateTime now);

  /** 延长仍由当前实例持有的恢复租约。 */
  @Update(
      "UPDATE seckill_quota_settlement SET lease_until=#{leaseUntil},updated_at=#{now} "
          + "WHERE order_no=#{orderNo} AND lease_owner=#{owner} AND lease_until>=#{now}")
  int renew(
      @Param("orderNo") String orderNo,
      @Param("owner") String owner,
      @Param("leaseUntil") LocalDateTime leaseUntil,
      @Param("now") LocalDateTime now);

  /** 在精确订单/操作键、revision及有效本owner租约及原完整tuple快照下记录失败并清租约，不改原动作/版本。 */
  @Update(
      "UPDATE seckill_quota_settlement SET"
          + " last_error=#{error},next_retry_at=#{nextRetryAt},lease_owner=NULL,lease_until=NULL,updated_at=#{now}"
          + " WHERE order_no=#{orderNo} AND lease_owner=#{owner} AND revision=#{revision} AND"
          + " operation_key=#{operationKey} AND status IN ('RELEASE_PENDING','PROJECTION_BLOCKED')"
          + " AND BINARY order_no=BINARY #{orderNo} AND BINARY operation_key=BINARY #{operationKey}"
          + " AND BINARY lease_owner=BINARY #{owner} AND BINARY status IN (BINARY"
          + " 'RELEASE_PENDING',BINARY 'PROJECTION_BLOCKED') AND lease_until IS NOT NULL AND"
          + " lease_until>NOW(3) AND id=#{original.id} AND activity_id=#{original.activityId} AND"
          + " sku_id=#{original.skuId} AND user_id=#{original.userId} AND"
          + " quantity=#{original.quantity} AND revision=#{original.revision} AND"
          + " created_at=#{original.createdAt} AND BINARY order_no=BINARY #{original.orderNo} AND"
          + " BINARY operation_key=BINARY #{original.operationKey} AND BINARY"
          + " idempotency_key=BINARY #{original.idempotencyKey}")
  int retry(
      @Param("orderNo") String orderNo,
      @Param("operationKey") String operationKey,
      @Param("revision") long revision,
      @Param("owner") String owner,
      @Param("error") String error,
      @Param("nextRetryAt") LocalDateTime nextRetryAt,
      @Param("now") LocalDateTime now,
      @Param("original") SeckillQuotaSettlementPO original);

  /** 统计仍占用活动配额的结算事实。 */
  @Select(
      "SELECT COALESCE(SUM(quantity),0) FROM seckill_quota_settlement "
          + "WHERE activity_id=#{activityId} AND sku_id=#{skuId} "
          + "AND status IN ('OPEN','RELEASE_PENDING','CONFIRMED','PROJECTION_BLOCKED')")
  Long countActiveSettlements(@Param("activityId") long activityId, @Param("skuId") long skuId);
}
