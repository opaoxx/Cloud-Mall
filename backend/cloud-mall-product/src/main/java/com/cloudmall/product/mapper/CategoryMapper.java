package com.cloudmall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.product.domain.po.CategoryPO;
import org.apache.ibatis.annotations.Mapper;

/** 商品分类表数据访问接口。 */
@Mapper
public interface CategoryMapper extends BaseMapper<CategoryPO> {}
