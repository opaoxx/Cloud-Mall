package com.cloudmall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.product.domain.po.SeckillActivityPO;
import org.apache.ibatis.annotations.Mapper;

/** 秒杀活动表数据访问接口。 */
@Mapper
public interface SeckillActivityMapper extends BaseMapper<SeckillActivityPO> {}
