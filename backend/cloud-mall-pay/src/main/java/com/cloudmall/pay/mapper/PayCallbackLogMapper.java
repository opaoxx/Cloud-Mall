package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayCallbackLogPO;
import org.apache.ibatis.annotations.Mapper;

/** 支付回调日志表数据访问接口。 */
@Mapper
public interface PayCallbackLogMapper extends BaseMapper<PayCallbackLogPO> {}
