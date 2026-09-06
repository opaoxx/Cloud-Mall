package com.cloudmall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.order.domain.po.OrderPO;
import org.apache.ibatis.annotations.Mapper;

/** 订单主表数据访问接口。 */
@Mapper
public interface OrderMapper extends BaseMapper<OrderPO> {}
