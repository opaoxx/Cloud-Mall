package com.cloudmall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.product.domain.po.ProductParameterPO;
import org.apache.ibatis.annotations.Mapper;

/** 商品参数表数据访问接口。 */
@Mapper
public interface ProductParameterMapper extends BaseMapper<ProductParameterPO> {}
