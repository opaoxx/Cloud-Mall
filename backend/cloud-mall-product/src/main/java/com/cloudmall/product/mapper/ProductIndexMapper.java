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
      "select id,category_id,name,main_image,description,price,status,version from product where"
          + " id=#{productId}")
  List<Map<String, Object>> selectIndexSource(@Param("productId") long productId);

  /** 按主键游标有界读取当前商品事实，供 ES 存量重建。 */
  @Select(
      "select id,category_id,name,main_image,description,price,status,version from product"
          + " where id>#{cursorProductId} order by id limit #{batchSize}")
  List<Map<String, Object>> selectIndexSourcesAfter(
      @Param("cursorProductId") long cursorProductId, @Param("batchSize") int batchSize);

  /** 统计 MySQL 中应具备索引投影的商品总数。 */
  @Select("select count(*) from product")
  long countIndexSources();
}
