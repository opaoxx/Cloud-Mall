package com.cloudmall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.product.domain.po.ProductSkuPO;
import org.apache.ibatis.annotations.Mapper;

/** 商品 SKU 表数据访问接口。 */
@Mapper
public interface ProductSkuMapper extends BaseMapper<ProductSkuPO> {}
