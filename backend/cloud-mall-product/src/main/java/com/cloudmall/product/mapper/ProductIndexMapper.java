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
      "select p.id,p.category_id,p.name,p.main_image,p.description,p.price,p.status,p.version,"
          + "p.updated_at,coalesce(h.view_count,0) view_count,"
          + "coalesce(h.search_count,0) search_count,coalesce(h.hot_score,0) hot_score"
          + " from product p left join product_hot_stat h on h.product_id=p.id"
          + " where p.id=#{productId}")
  List<Map<String, Object>> selectIndexSource(@Param("productId") long productId);

  /** 按主键游标有界读取当前商品事实，供 ES 存量重建。 */
  @Select(
      "select p.id,p.category_id,p.name,p.main_image,p.description,p.price,p.status,p.version,"
          + "p.updated_at,coalesce(h.view_count,0) view_count,"
          + "coalesce(h.search_count,0) search_count,coalesce(h.hot_score,0) hot_score"
          + " from product p left join product_hot_stat h on h.product_id=p.id"
          + " where p.id>#{cursorProductId} order by p.id limit #{batchSize}")
  List<Map<String, Object>> selectIndexSourcesAfter(
      @Param("cursorProductId") long cursorProductId, @Param("batchSize") int batchSize);

  /** 统计 MySQL 中应具备索引投影的商品总数。 */
  @Select("select count(*) from product")
  long countIndexSources();
}
