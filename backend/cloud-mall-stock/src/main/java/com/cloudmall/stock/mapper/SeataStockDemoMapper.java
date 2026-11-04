package com.cloudmall.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.stock.domain.po.SeataStockDemoPO;
import org.apache.ibatis.annotations.Mapper;
import org.springframework.stereotype.Component;

/** CM-18 Stock 演练表 Mapper。 */
@Mapper
@Component("cm18SeataStockDemoMapper")
public interface SeataStockDemoMapper extends BaseMapper<SeataStockDemoPO> {}
