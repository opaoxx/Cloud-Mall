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

  /**
   * 按主键游标只读取得已有 Legacy 匹配终态的未完成回调，不以候选快照授权更新。
   *
   * @param afterId 上轮最后观察主键，空值表示从头扫描
   * @param limit 本轮候选上限
   * @return 按主键升序排列的候选日志
   */
  @Select(
      "<script>SELECT callback.* FROM pay_callback_log callback"
          + " JOIN pay_record payment ON BINARY payment.pay_no=BINARY callback.pay_no"
          + " WHERE callback.processed_at IS NULL AND BINARY payment.debit_mode=BINARY 'LEGACY'"
          + " AND ((BINARY callback.callback_status=BINARY 'RECEIVED_SUCCESS'"
          + " AND BINARY payment.status=BINARY 'SUCCESS')"
          + " OR (BINARY callback.callback_status=BINARY 'RECEIVED_FAILURE'"
          + " AND BINARY payment.status=BINARY 'FAILED'))"
          + " <if test='afterId != null'>AND callback.id &gt; #{afterId}</if>"
          + " ORDER BY callback.id ASC LIMIT #{limit}</script>")
  List<PayCallbackLogPO> findCompletableAfter(
      @Param("afterId") Long afterId, @Param("limit") int limit);

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
