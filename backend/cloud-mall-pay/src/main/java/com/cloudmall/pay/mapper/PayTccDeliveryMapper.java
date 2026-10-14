package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayTccDeliveryPO;
import com.cloudmall.pay.domain.po.PayTccExecutionPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Pay TCC 交付台账和有界租约的语义化数据访问接口。 */
@Mapper
public interface PayTccDeliveryMapper extends BaseMapper<PayTccDeliveryPO> {
  /** 幂等创建交付台账表。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS pay_tcc_delivery (id BIGINT PRIMARY KEY,pay_no VARCHAR(64) NOT"
          + " NULL,xid VARCHAR(128) NOT NULL,user_id BIGINT NOT NULL,order_no VARCHAR(64) NOT"
          + " NULL,amount DECIMAL(18,2) NOT NULL,state VARCHAR(32) NOT NULL,refund_operation_key"
          + " VARCHAR(128) NOT NULL,attempts INT NOT NULL DEFAULT 0,next_retry_at DATETIME(3) NOT"
          + " NULL,lease_owner VARCHAR(128),lease_until DATETIME(3),last_error VARCHAR(512),"
          + "created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,UNIQUE KEY"
          + " uk_pay_tcc_delivery_pay(pay_no),UNIQUE KEY uk_pay_tcc_delivery_xid(xid),UNIQUE KEY"
          + " uk_pay_tcc_delivery_refund(refund_operation_key),KEY idx_pay_tcc_delivery_due(state,"
          + "next_retry_at,lease_until))")
  void ensureTable();

  /** 按支付号读取交付台账。 */
  @Select("SELECT * FROM pay_tcc_delivery WHERE pay_no=#{payNo}")
  PayTccDeliveryPO selectByPayNo(@Param("payNo") String payNo);

  /** 按支付号锁定交付台账。 */
  @Select("SELECT * FROM pay_tcc_delivery WHERE pay_no=#{payNo} FOR UPDATE")
  PayTccDeliveryPO selectForUpdate(@Param("payNo") String payNo);

  /**
   * 找出已确认、支付仍可交付且尚无台账的 execution。
   *
   * <p>完整 tuple 和固定业务键在 LIMIT 前过滤，避免脏数据占满批次。
   */
  @Select(
      "SELECT execution.* FROM pay_tcc_execution execution JOIN pay_record payment ON"
          + " payment.pay_no=execution.pay_no LEFT JOIN pay_tcc_delivery delivery ON"
          + " delivery.pay_no=execution.pay_no WHERE BINARY execution.state='COMMITTED' AND BINARY"
          + " execution.decision='COMMIT' AND execution.xid IS NOT NULL AND"
          + " LENGTH(TRIM(execution.xid))>0 AND BINARY payment.debit_mode='TCC' AND BINARY"
          + " payment.pay_no=BINARY execution.pay_no AND payment.user_id=execution.user_id AND"
          + " BINARY payment.order_no=BINARY execution.order_no AND payment.amount=execution.amount"
          + " AND BINARY execution.operation_key=BINARY CONCAT('tcc-debit:',execution.pay_no) AND"
          + " BINARY execution.ledger_key=BINARY execution.pay_no AND BINARY payment.status IN"
          + " ('PENDING','ORDER_SYNC_UNKNOWN') AND delivery.pay_no IS NULL ORDER BY"
          + " execution.created_at,execution.id LIMIT #{limit}")
  List<PayTccExecutionPO> findMissing(@Param("limit") int limit);

  /** 仅按当前状态改变取消方向，保留原租约、尝试次数和 immutable tuple。 */
  @Update(
      "UPDATE pay_tcc_delivery SET state=CASE WHEN BINARY state='READY' THEN 'CANCEL_REQUESTED'"
          + " WHEN BINARY state='ORDER_SYNC_UNKNOWN' THEN 'CANCEL_RECONCILING'"
          + " END,next_retry_at=LEAST(next_retry_at,#{now}),updated_at=#{now} WHERE BINARY"
          + " pay_no=BINARY #{payNo} AND BINARY state IN ('READY','ORDER_SYNC_UNKNOWN') AND"
          + " attempts IS NOT NULL AND attempts>=0 AND ((lease_owner IS NULL AND lease_until IS"
          + " NULL) OR (lease_owner IS NOT NULL AND TRIM(lease_owner)<>'' AND lease_until IS NOT"
          + " NULL))")
  int requestCancellation(@Param("payNo") String payNo, @Param("now") LocalDateTime now);

  /** 找出状态可恢复、重试到期且租约已释放的交付台账。 */
  @Select(
      "SELECT delivery.* FROM pay_tcc_delivery delivery JOIN pay_tcc_execution execution ON"
          + " execution.pay_no=delivery.pay_no JOIN pay_record payment ON"
          + " payment.pay_no=execution.pay_no WHERE BINARY delivery.state IN"
          + " ('READY','CANCEL_REQUESTED','CANCEL_RECONCILING','ORDER_SYNC_UNKNOWN','REFUND_PENDING')"
          + " AND delivery.next_retry_at<=#{now} AND (delivery.lease_until IS NULL OR"
          + " delivery.lease_until<=#{now}) AND BINARY execution.state='COMMITTED' AND BINARY"
          + " execution.decision='COMMIT' AND execution.xid IS NOT NULL AND"
          + " LENGTH(TRIM(execution.xid))>0 AND BINARY payment.debit_mode='TCC' AND BINARY"
          + " payment.pay_no=BINARY execution.pay_no AND payment.user_id=execution.user_id AND"
          + " BINARY payment.order_no=BINARY execution.order_no AND payment.amount=execution.amount"
          + " AND BINARY delivery.pay_no=BINARY execution.pay_no AND"
          + " delivery.user_id=execution.user_id AND BINARY delivery.order_no=BINARY"
          + " execution.order_no AND delivery.amount=execution.amount AND BINARY"
          + " delivery.xid=BINARY execution.xid AND BINARY delivery.refund_operation_key=BINARY"
          + " CONCAT('refund:',delivery.pay_no) AND BINARY execution.operation_key=BINARY"
          + " CONCAT('tcc-debit:',execution.pay_no) AND BINARY execution.ledger_key=BINARY"
          + " execution.pay_no AND BINARY payment.status IN"
          + " ('PENDING','ORDER_SYNC_UNKNOWN','REFUND_PENDING','SUCCESS','REFUNDED') ORDER BY"
          + " delivery.next_retry_at,delivery.id LIMIT #{limit}")
  List<PayTccDeliveryPO> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

  /** 在完整原 tuple、状态和租约条件仍满足时认领单个交付台账。 */
  @Update(
      "UPDATE pay_tcc_delivery delivery JOIN pay_tcc_execution execution ON"
          + " execution.pay_no=delivery.pay_no JOIN pay_record payment ON"
          + " payment.pay_no=execution.pay_no SET delivery.lease_owner=#{token},"
          + "delivery.lease_until=#{leaseUntil},delivery.attempts=LEAST(delivery.attempts+1,31),delivery.updated_at=#{now}"
          + " WHERE BINARY delivery.pay_no=BINARY #{payNo} AND BINARY delivery.state IN"
          + " ('READY','CANCEL_REQUESTED','CANCEL_RECONCILING','ORDER_SYNC_UNKNOWN','REFUND_PENDING')"
          + " AND delivery.next_retry_at<=#{now} AND (delivery.lease_until IS NULL OR"
          + " delivery.lease_until<=#{now}) AND BINARY execution.state='COMMITTED' AND BINARY"
          + " execution.decision='COMMIT' AND execution.xid IS NOT NULL AND"
          + " LENGTH(TRIM(execution.xid))>0 AND BINARY payment.debit_mode='TCC' AND BINARY"
          + " payment.pay_no=BINARY execution.pay_no AND payment.user_id=execution.user_id AND"
          + " BINARY payment.order_no=BINARY execution.order_no AND payment.amount=execution.amount"
          + " AND BINARY delivery.pay_no=BINARY execution.pay_no AND"
          + " delivery.user_id=execution.user_id AND BINARY delivery.order_no=BINARY"
          + " execution.order_no AND delivery.amount=execution.amount AND BINARY"
          + " delivery.xid=BINARY execution.xid AND BINARY delivery.refund_operation_key=BINARY"
          + " CONCAT('refund:',delivery.pay_no) AND BINARY execution.operation_key=BINARY"
          + " CONCAT('tcc-debit:',execution.pay_no) AND BINARY execution.ledger_key=BINARY"
          + " execution.pay_no AND BINARY payment.status IN"
          + " ('PENDING','ORDER_SYNC_UNKNOWN','REFUND_PENDING','SUCCESS','REFUNDED')")
  int claim(
      @Param("payNo") String payNo,
      @Param("token") String token,
      @Param("leaseUntil") LocalDateTime leaseUntil,
      @Param("now") LocalDateTime now);

  /** 以租约和当前状态为条件推进交付状态，不覆盖成功或已退款终态。 */
  @Update(
      "UPDATE pay_tcc_delivery SET state=#{targetState},last_error=#{error},updated_at=#{now}"
          + " WHERE BINARY pay_no=BINARY #{payNo} AND BINARY lease_owner=BINARY #{token}"
          + " AND lease_until IS NOT NULL AND lease_until>#{now} AND BINARY state=BINARY"
          + " #{expectedState} AND state NOT IN ('SUCCESS','REFUNDED')")
  int updateState(
      @Param("payNo") String payNo,
      @Param("token") String token,
      @Param("expectedState") String expectedState,
      @Param("targetState") String targetState,
      @Param("error") String error,
      @Param("now") LocalDateTime now);

  /** 释放租约并更新重试元数据，保持业务状态不变。 */
  @Update(
      "UPDATE pay_tcc_delivery SET"
          + " lease_owner=NULL,lease_until=NULL,next_retry_at=#{nextAt},last_error=#{error},updated_at=#{now}"
          + " WHERE BINARY pay_no=BINARY #{payNo} AND BINARY lease_owner=BINARY #{token} AND"
          + " lease_until IS NOT NULL AND lease_until>#{now}")
  int finishAttempt(
      @Param("payNo") String payNo,
      @Param("token") String token,
      @Param("nextAt") LocalDateTime nextAt,
      @Param("error") String error,
      @Param("now") LocalDateTime now);
}
