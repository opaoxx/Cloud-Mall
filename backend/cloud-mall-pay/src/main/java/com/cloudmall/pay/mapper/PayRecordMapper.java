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
  /** 查询用户订单的支付记录。 */
  @Select(
      "select pay_no,order_no,user_id,amount,status,paid_at from pay_record where"
          + " order_no=#{orderNo} and user_id=#{userId}")
  List<PayRecordPO> selectByOrderNoAndUserId(
      @Param("orderNo") String orderNo, @Param("userId") Long userId);

  /** 按支付单号查询并锁定支付记录。 */
  @Select(
      "select pay_no,order_no,user_id,amount,status,paid_at from pay_record where pay_no=#{payNo}"
          + " for update")
  List<PayRecordPO> selectForUpdate(@Param("payNo") String payNo);

  /** 按订单号查询支付记录。 */
  @Select(
      "select pay_no,order_no,user_id,amount,status,paid_at from pay_record where"
          + " order_no=#{orderNo}")
  List<PayRecordPO> selectByOrderNo(@Param("orderNo") String orderNo);

  /** 按支付单号查询支付记录。 */
  @Select(
      "select pay_no,order_no,user_id,amount,status,paid_at from pay_record where pay_no=#{payNo}")
  List<PayRecordPO> selectByPayNo(@Param("payNo") String payNo);

  /** 将待支付记录更新为成功。 */
  @Update(
      "update pay_record set status='SUCCESS',paid_at=#{paidAt},updated_at=#{updatedAt} where"
          + " pay_no=#{payNo} and status='PENDING'")
  int markSuccess(
      @Param("payNo") String payNo,
      @Param("paidAt") java.time.LocalDateTime paidAt,
      @Param("updatedAt") java.time.LocalDateTime updatedAt);

  /** 将待支付记录更新为失败。 */
  @Update(
      "update pay_record set status='FAILED',updated_at=#{updatedAt} where pay_no=#{payNo} and"
          + " status='PENDING'")
  int markFailed(
      @Param("payNo") String payNo, @Param("updatedAt") java.time.LocalDateTime updatedAt);

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
