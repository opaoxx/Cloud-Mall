package com.cloudmall.product.mapper;

import java.util.List;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Param;
import org.apache.ibatis.annotations.Select;

/** 为商品索引加载商品事实数据的 Mapper。 */
@Mapper
public interface ProductIndexMapper {
  /** 查询可用于构建 ES 文档的商品字段。 */
  @Select(
      "select id,category_id,name,main_image,description,price,status from product where"
          + " id=#{productId} and status=1")
  List<Map<String, Object>> selectIndexSource(@Param("productId") long productId);
}
