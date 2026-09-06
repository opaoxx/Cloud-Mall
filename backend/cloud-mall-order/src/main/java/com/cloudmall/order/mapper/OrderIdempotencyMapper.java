package com.cloudmall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.order.domain.po.OrderIdempotencyPO;
import org.apache.ibatis.annotations.Mapper;

/** 订单幂等表数据访问接口。 */
@Mapper
public interface OrderIdempotencyMapper extends BaseMapper<OrderIdempotencyPO> {}
