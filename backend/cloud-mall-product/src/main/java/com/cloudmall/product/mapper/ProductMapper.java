package com.cloudmall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.product.domain.po.ProductPO;
import org.apache.ibatis.annotations.Mapper;

/** 商品表数据访问接口。 */
@Mapper
public interface ProductMapper extends BaseMapper<ProductPO> {}
