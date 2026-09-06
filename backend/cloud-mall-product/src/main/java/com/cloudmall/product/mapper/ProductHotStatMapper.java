package com.cloudmall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.product.domain.po.ProductHotStatPO;
import org.apache.ibatis.annotations.Mapper;

/** 商品热度统计表数据访问接口。 */
@Mapper
public interface ProductHotStatMapper extends BaseMapper<ProductHotStatPO> {}
