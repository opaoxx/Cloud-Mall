package com.cloudmall.order.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.order.domain.po.OrderItemPO;
import org.apache.ibatis.annotations.Mapper;

/** 订单明细表数据访问接口。 */
@Mapper
public interface OrderItemMapper extends BaseMapper<OrderItemPO> {}
