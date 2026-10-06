package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import java.util.List;
import org.apache.ibatis.annotations.Insert;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;
import org.apache.ibatis.annotations.Update;

/** 支付回调日志表数据访问接口。 */
@Mapper
public interface PayCallbackLogMapper extends BaseMapper<PayCallbackLogPO> {
  /** 按回调幂等标识查询处理记录。 */
  @Select("select * from pay_callback_log where callback_id=#{callbackId}")
  List<PayCallbackLogPO> selectByCallbackId(@Param("callbackId") String callbackId);

  /** 按回调幂等标识锁定唯一处理记录。 */
  @Select("select * from pay_callback_log where callback_id=#{callbackId} for update")
  PayCallbackLogPO lockedSelect(@Param("callbackId") String callbackId);

  /** 在独立事务中预绑定不可变的收到状态与完整回调载荷。 */
  @Insert(
      "insert ignore into pay_callback_log"
          + "(id,pay_no,callback_id,callback_status,payload,processed_at,created_at)"
          + " values(#{id},#{payNo},#{callbackId},#{callbackStatus},#{payload},#{processedAt},#{createdAt})")
  int insertReceived(PayCallbackLogPO callbackLog);

  /** 仅将同方向 received 记录 CAS 为已处理终态。 */
  @Update(
      "update pay_callback_log set callback_status=#{targetStatus},processed_at=#{now}"
          + " where callback_id=#{callbackId} and callback_status=#{expectedStatus}"
          + " and processed_at is null")
  int markProcessed(
      @Param("callbackId") String callbackId,
      @Param("expectedStatus") String expectedStatus,
      @Param("targetStatus") String targetStatus,
      @Param("now") java.time.LocalDateTime now);
}
