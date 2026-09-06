package com.cloudmall.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.stock.domain.po.StockFlowPO;
import org.apache.ibatis.annotations.Mapper;

/** 库存流水表数据访问接口。 */
@Mapper
public interface StockFlowMapper extends BaseMapper<StockFlowPO> {}
