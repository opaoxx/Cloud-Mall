package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayRecordPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 支付记录表数据访问接口。 */
@Mapper
public interface PayRecordMapper extends BaseMapper<PayRecordPO> {
  /** 只读取证的有界身份候选，不加锁或修改记录。 */
  @Select(
      "SELECT pay_no,order_no,user_id,amount,status,debit_mode FROM pay_record WHERE"
          + " pay_no=#{payNo} LIMIT 2")
  List<PayRecordPO> findReconciliationCandidates(@Param("payNo") String payNo);

  /** 查询用户订单的支付记录。 */
  @Select(
      "select pay_no,order_no,user_id,amount,status,debit_mode,paid_at from pay_record where"
          + " order_no=#{orderNo} and user_id=#{userId}")
  List<PayRecordPO> selectByOrderNoAndUserId(
      @Param("orderNo") String orderNo, @Param("userId") Long userId);

  /** 按支付单号查询并锁定支付记录。 */
  @Select(
      "select pay_no,order_no,user_id,amount,status,debit_mode,paid_at from pay_record where"
          + " pay_no=#{payNo} for update")
  List<PayRecordPO> selectForUpdate(@Param("payNo") String payNo);

  /** 按订单号查询支付记录。 */
  @Select(
      "select pay_no,order_no,user_id,amount,status,debit_mode,paid_at from pay_record where"
          + " order_no=#{orderNo}")
  List<PayRecordPO> selectByOrderNo(@Param("orderNo") String orderNo);

  /** 按支付单号查询支付记录。 */
  @Select(
      "select pay_no,order_no,user_id,amount,status,debit_mode,paid_at from pay_record where"
          + " pay_no=#{payNo}")
  List<PayRecordPO> selectByPayNo(@Param("payNo") String payNo);

  /** 将待支付记录更新为成功。 */
  @Update(
      "update pay_record set status='SUCCESS',paid_at=#{paidAt},updated_at=#{updatedAt} where"
          + " pay_no=#{payNo} and status in ('PENDING','ORDER_SYNC_UNKNOWN')")
  int markSuccess(
      @Param("payNo") String payNo,
      @Param("paidAt") java.time.LocalDateTime paidAt,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** 将待支付记录更新为失败。 */
  @Update(
      "update pay_record set status='FAILED',updated_at=#{updatedAt} where pay_no=#{payNo} and"
          + " status in ('PENDING','ORDER_SYNC_UNKNOWN')")
  int markFailed(
      @Param("payNo") String payNo, @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** 扣款事实已存在但订单同步未确认时保留可恢复状态。 */
  @Update(
      "update pay_record set status='ORDER_SYNC_UNKNOWN',updated_at=#{updatedAt}"
          + " where pay_no=#{payNo} and status in ('PENDING','ORDER_SYNC_UNKNOWN')")
  int markOrderUnknown(
      @Param("payNo") String payNo, @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** 只在订单明确取消后进入等待退款状态。 */
  @Update(
      "update pay_record set status='REFUND_PENDING',updated_at=#{updatedAt}"
          + " where pay_no=#{payNo}"
          + " and status in ('PENDING','ORDER_SYNC_UNKNOWN','REFUND_PENDING')")
  int markRefundPending(
      @Param("payNo") String payNo, @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** 用户账本确认退款后才把支付记录标记为已退。 */
  @Update(
      "update pay_record set status='REFUNDED',updated_at=#{updatedAt}"
          + " where pay_no=#{payNo} and status in ('REFUND_PENDING','ORDER_SYNC_UNKNOWN')")
  int markRefunded(
      @Param("payNo") String payNo, @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** LegacySUCCESS终态仅由当前未过期owner原子推进，旧方法继续供其它调用者使用。 */
  @Update(
      "UPDATE pay_record payment JOIN pay_debit_intent intent ON BINARY payment.pay_no=BINARY"
          + " intent.pay_no SET"
          + " payment.status='SUCCESS',payment.paid_at=#{paidAt},payment.updated_at=#{updatedAt}"
          + " WHERE BINARY payment.pay_no=BINARY #{payNo} AND BINARY intent.lease_owner=BINARY"
          + " #{claimToken} AND intent.lease_until>#{updatedAt} AND BINARY"
          + " payment.debit_mode='LEGACY' AND BINARY intent.debit_mode='LEGACY' AND intent.status"
          + " IN ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND payment.status IN"
          + " ('PENDING','ORDER_SYNC_UNKNOWN')")
  int markOwnedLegacySuccess(
      @Param("payNo") String payNo,
      @Param("claimToken") String claimToken,
      @Param("paidAt") java.time.LocalDateTime paidAt,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** LegacyFAILED终态仅由当前未过期owner原子推进，旧方法继续供其它调用者使用。 */
  @Update(
      "UPDATE pay_record payment JOIN pay_debit_intent intent ON BINARY payment.pay_no=BINARY"
          + " intent.pay_no SET payment.status='FAILED',payment.updated_at=#{updatedAt} WHERE"
          + " BINARY payment.pay_no=BINARY #{payNo} AND BINARY intent.lease_owner=BINARY"
          + " #{claimToken} AND intent.lease_until>#{updatedAt} AND BINARY"
          + " payment.debit_mode='LEGACY' AND BINARY intent.debit_mode='LEGACY' AND intent.status"
          + " IN ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND payment.status IN"
          + " ('PENDING','ORDER_SYNC_UNKNOWN')")
  int markOwnedLegacyFailed(
      @Param("payNo") String payNo,
      @Param("claimToken") String claimToken,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** LegacyREFUNDED终态仅由当前未过期owner原子推进，旧方法继续供其它调用者使用。 */
  @Update(
      "UPDATE pay_record payment JOIN pay_debit_intent intent ON BINARY payment.pay_no=BINARY"
          + " intent.pay_no SET payment.status='REFUNDED',payment.updated_at=#{updatedAt} WHERE"
          + " BINARY payment.pay_no=BINARY #{payNo} AND BINARY intent.lease_owner=BINARY"
          + " #{claimToken} AND intent.lease_until>#{updatedAt} AND BINARY"
          + " payment.debit_mode='LEGACY' AND BINARY intent.debit_mode='LEGACY' AND intent.status"
          + " IN ('INIT','DEBITED','ORDER_SYNC_UNKNOWN','REFUND_PENDING') AND payment.status IN"
          + " ('REFUND_PENDING','ORDER_SYNC_UNKNOWN')")
  int markOwnedLegacyRefunded(
      @Param("payNo") String payNo,
      @Param("claimToken") String claimToken,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** 按回调结果更新支付状态。 */
  @Update(
      "update pay_record set status=#{status},paid_at=case when #{success}=true then #{paidAt} else"
          + " paid_at end,updated_at=#{updatedAt} where pay_no=#{payNo} and status='PENDING'")
  int markCallback(
      @Param("payNo") String payNo,
      @Param("status") String status,
      @Param("success") boolean success,
      @Param("paidAt") java.time.LocalDateTime paidAt,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);
}
