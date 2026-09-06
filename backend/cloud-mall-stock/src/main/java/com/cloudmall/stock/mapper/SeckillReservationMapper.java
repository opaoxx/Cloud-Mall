package com.cloudmall.stock.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.stock.domain.po.SeckillReservationPO;
import org.apache.ibatis.annotations.Mapper;

/** 秒杀预扣表数据访问接口。 */
@Mapper
public interface SeckillReservationMapper extends BaseMapper<SeckillReservationPO> {}
