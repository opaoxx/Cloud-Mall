package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import java.util.List;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 支付回调日志表数据访问接口。 */
@Mapper
public interface PayCallbackLogMapper extends BaseMapper<PayCallbackLogPO> {
  /** 按回调幂等标识查询处理记录。 */
  @Select("select pay_no from pay_callback_log where callback_id=#{callbackId}")
  List<PayCallbackLogPO> selectByCallbackId(@Param("callbackId") String callbackId);
}
