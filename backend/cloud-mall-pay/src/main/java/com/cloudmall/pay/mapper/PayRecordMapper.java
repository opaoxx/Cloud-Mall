package com.cloudmall.pay.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.pay.domain.po.PayRecordPO;
import org.apache.ibatis.annotations.Mapper;

/** 支付记录表数据访问接口。 */
@Mapper
public interface PayRecordMapper extends BaseMapper<PayRecordPO> {}
