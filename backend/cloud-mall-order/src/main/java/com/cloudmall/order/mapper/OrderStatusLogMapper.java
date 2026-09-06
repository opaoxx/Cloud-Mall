package com.cloudmall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.order.domain.po.OrderStatusLogPO;
import org.apache.ibatis.annotations.Mapper;

/** 订单状态流水表数据访问接口。 */
@Mapper
public interface OrderStatusLogMapper extends BaseMapper<OrderStatusLogPO> {}
