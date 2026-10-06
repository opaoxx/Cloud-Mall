package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayTccCancelPO;
import java.time.LocalDateTime;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** Pay TCC 主动取消台账与租约语义化数据访问接口。 */
@Mapper
public interface PayTccCancelMapper extends BaseMapper<PayTccCancelPO> {
  /** 无条件幂等创建取消台账表。 */
  @Update(
      "CREATE TABLE IF NOT EXISTS pay_tcc_cancel (id BIGINT PRIMARY KEY,pay_no VARCHAR(64) NOT"
          + " NULL,user_id BIGINT NOT NULL,order_no VARCHAR(64) NOT NULL,amount DECIMAL(18,2) NOT"
          + " NULL,state VARCHAR(32) NOT NULL,attempts INT NOT NULL DEFAULT 0,next_retry_at"
          + " DATETIME(3) NOT NULL,lease_owner VARCHAR(128),lease_until DATETIME(3),last_error"
          + " VARCHAR(512),created_at DATETIME(3) NOT NULL,updated_at DATETIME(3) NOT NULL,UNIQUE"
          + " KEY uk_pay_tcc_cancel_pay(pay_no),KEY idx_pay_tcc_cancel_due(state,next_retry_at,"
          + "lease_until))")
  void ensureTable();

  /** 按支付号读取取消台账。 */
  @Select("SELECT * FROM pay_tcc_cancel WHERE pay_no=#{payNo}")
  PayTccCancelPO selectByPayNo(@Param("payNo") String payNo);

  /** 按支付号锁定取消台账。 */
  @Select("SELECT * FROM pay_tcc_cancel WHERE pay_no=#{payNo} FOR UPDATE")
  PayTccCancelPO selectForUpdate(@Param("payNo") String payNo);

  /** 插入首次 REQUESTED 取消抢占记录。 */
  @Insert(
      "INSERT IGNORE INTO"
          + " pay_tcc_cancel(id,pay_no,user_id,order_no,amount,state,attempts,next_retry_at,lease_owner,lease_until,last_error,created_at,updated_at)"
          + " VALUES"
          + "(#{id},#{payNo},#{userId},#{orderNo},#{amount},'REQUESTED',0,#{nextRetryAt},NULL,NULL,NULL,"
          + "#{createdAt},#{updatedAt})")
  int insertRequested(PayTccCancelPO cancel);

  /** 按完整资金互斥门筛选可恢复取消任务，LIMIT 在全部过滤后执行。 */
  @Select(
      "SELECT cancel.* FROM pay_tcc_cancel cancel JOIN pay_record payment ON"
          + " payment.pay_no=cancel.pay_no LEFT JOIN pay_tcc_execution execution ON"
          + " execution.pay_no=cancel.pay_no LEFT JOIN pay_tcc_dispatch dispatch ON"
          + " dispatch.pay_no=cancel.pay_no LEFT JOIN pay_tcc_receipt receipt ON"
          + " receipt.pay_no=cancel.pay_no LEFT JOIN pay_debit_intent intent ON"
          + " intent.pay_no=cancel.pay_no WHERE BINARY payment.debit_mode='TCC' AND BINARY"
          + " payment.pay_no=BINARY cancel.pay_no AND payment.user_id=cancel.user_id AND BINARY"
          + " payment.order_no=BINARY cancel.order_no AND payment.amount=cancel.amount AND BINARY"
          + " payment.status='PENDING' AND execution.pay_no IS NULL AND"
          + " dispatch.pay_no IS NULL AND receipt.pay_no IS NULL AND intent.pay_no IS NULL AND"
          + " BINARY cancel.state IN ('REQUESTED','ORDER_CANCELLED','CANCEL_UNKNOWN') AND"
          + " cancel.next_retry_at<=#{now} AND (cancel.lease_until IS NULL OR"
          + " cancel.lease_until<=#{now}) ORDER BY cancel.next_retry_at,cancel.id LIMIT #{limit}")
  List<PayTccCancelPO> findDue(@Param("now") LocalDateTime now, @Param("limit") int limit);

  /** 以支付互斥门、完整 tuple 和租约条件认领单个取消任务。 */
  @Update(
      "UPDATE pay_tcc_cancel cancel JOIN pay_record payment ON payment.pay_no=cancel.pay_no LEFT"
          + " JOIN pay_tcc_execution execution ON execution.pay_no=cancel.pay_no LEFT JOIN"
          + " pay_tcc_dispatch dispatch ON dispatch.pay_no=cancel.pay_no LEFT JOIN pay_tcc_receipt"
          + " receipt ON receipt.pay_no=cancel.pay_no LEFT JOIN pay_debit_intent intent ON"
          + " intent.pay_no=cancel.pay_no SET"
          + " cancel.lease_owner=#{token},cancel.lease_until=#{leaseUntil},cancel.attempts=LEAST(cancel.attempts+1,31),cancel.updated_at=#{now}"
          + " WHERE cancel.pay_no=#{payNo} AND BINARY payment.debit_mode='TCC' AND BINARY"
          + " payment.pay_no=BINARY cancel.pay_no AND payment.user_id=cancel.user_id AND BINARY"
          + " payment.order_no=BINARY cancel.order_no AND payment.amount=cancel.amount AND BINARY"
          + " payment.status='PENDING' AND execution.pay_no IS NULL AND dispatch.pay_no IS NULL AND"
          + " receipt.pay_no IS NULL AND intent.pay_no IS NULL AND BINARY cancel.state IN"
          + " ('REQUESTED','ORDER_CANCELLED','CANCEL_UNKNOWN') AND cancel.next_retry_at<=#{now} AND"
          + " (cancel.lease_until IS NULL OR cancel.lease_until<=#{now})")
  int claimRecovery(
      @Param("payNo") String payNo,
      @Param("token") String token,
      @Param("leaseUntil") LocalDateTime leaseUntil,
      @Param("now") LocalDateTime now);

  /** 仅由当前状态和 lease token 共同允许推进取消台账。 */
  @Update(
      "UPDATE pay_tcc_cancel SET state=#{state},last_error=#{error},updated_at=#{now} WHERE"
          + " pay_no=#{payNo} AND lease_owner=#{token} AND state NOT IN ('COMPLETED','CONFLICT')")
  int updateState(
      @Param("payNo") String payNo,
      @Param("token") String token,
      @Param("state") String state,
      @Param("error") String error,
      @Param("now") LocalDateTime now);

  /** 释放取消恢复 lease 并写入退避信息，不改变取消业务状态。 */
  @Update(
      "UPDATE pay_tcc_cancel SET lease_owner=NULL,lease_until=NULL,next_retry_at=#{nextAt},"
          + "last_error=#{error},updated_at=#{now} WHERE pay_no=#{payNo} AND lease_owner=#{token}")
  int finish(
      @Param("payNo") String payNo,
      @Param("token") String token,
      @Param("nextAt") LocalDateTime nextAt,
      @Param("error") String error,
      @Param("now") LocalDateTime now);
}
