package com.cloudmall.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.stock.domain.po.StockSkuPO;
import org.apache.ibatis.annotations.Mapper;

/** SKU 库存表数据访问接口。 */
@Mapper
public interface StockSkuMapper extends BaseMapper<StockSkuPO> {}
