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
          + "coalesce(h.search_count,0) search_count,coalesce(h.hot_score,0) hot_score,"
          + "coalesce(h.source_type,'UNKNOWN') metric_source_type,h.source_ref metric_source_ref"
          + " from product p left join product_hot_stat h on h.product_id=p.id"
          + " where p.id=#{productId}")
  List<Map<String, Object>> selectIndexSource(@Param("productId") long productId);

  /** 按主键游标有界读取当前商品事实，供 ES 存量重建。 */
  @Select(
      "select p.id,p.category_id,p.name,p.main_image,p.description,p.price,p.status,p.version,"
          + "p.updated_at,coalesce(h.view_count,0) view_count,"
          + "coalesce(h.search_count,0) search_count,coalesce(h.hot_score,0) hot_score,"
          + "coalesce(h.source_type,'UNKNOWN') metric_source_type,h.source_ref metric_source_ref"
          + " from product p left join product_hot_stat h on h.product_id=p.id"
          + " where p.id>#{cursorProductId} order by p.id limit #{batchSize}")
  List<Map<String, Object>> selectIndexSourcesAfter(
      @Param("cursorProductId") long cursorProductId, @Param("batchSize") int batchSize);

  /** 统计 MySQL 中应具备索引投影的商品总数。 */
  @Select("select count(*) from product")
  long countIndexSources();
  /** Current content and online aggregates come from one repeatable-read SQL snapshot. */
  @Select("select p.id,p.category_id,p.name,p.main_image,p.description,p.price,p.status,p.version,p.updated_at,coalesce(h.view_count,0) view_count,coalesce(h.search_count,0) search_count,coalesce(h.hot_score,0) hot_score,coalesce(h.source_type,'UNKNOWN') metric_source_type,h.source_ref metric_source_ref,coalesce(m.view_count,0) online_view_count,coalesce(m.search_count,0) online_search_count,coalesce(m.sold_count,0) online_sold_count,coalesce(m.hot_score,0) online_hot_score,coalesce(m.metric_version,0) metric_version from product p left join product_hot_stat h on h.product_id=p.id left join product_online_stat m on m.product_id=p.id where p.id=#{productId}")
  List<Map<String,Object>> selectOnlineSource(@Param("productId") long productId);
  /** Rebuild uses a bounded original product key cursor and includes both version axes. */
  @Select("select p.id,p.category_id,p.name,p.main_image,p.description,p.price,p.status,p.version,p.updated_at,coalesce(h.view_count,0) view_count,coalesce(h.search_count,0) search_count,coalesce(h.hot_score,0) hot_score,coalesce(h.source_type,'UNKNOWN') metric_source_type,h.source_ref metric_source_ref,coalesce(m.view_count,0) online_view_count,coalesce(m.search_count,0) online_search_count,coalesce(m.sold_count,0) online_sold_count,coalesce(m.hot_score,0) online_hot_score,coalesce(m.metric_version,0) metric_version from product p left join product_hot_stat h on h.product_id=p.id left join product_online_stat m on m.product_id=p.id where p.id>#{cursorProductId} order by p.id limit #{batchSize}")
  List<Map<String,Object>> selectOnlineSourcesAfter(@Param("cursorProductId") long cursorProductId,@Param("batchSize") int batchSize);
  /** The projector reads at most fifty products with one SQL query, avoiding per-event reloads. */
  @Select({"<script>","select p.id,p.category_id,p.name,p.main_image,p.description,p.price,p.status,p.version,p.updated_at,coalesce(h.view_count,0) view_count,coalesce(h.search_count,0) search_count,coalesce(h.hot_score,0) hot_score,coalesce(h.source_type,'UNKNOWN') metric_source_type,h.source_ref metric_source_ref,coalesce(m.view_count,0) online_view_count,coalesce(m.search_count,0) online_search_count,coalesce(m.sold_count,0) online_sold_count,coalesce(m.hot_score,0) online_hot_score,coalesce(m.metric_version,0) metric_version from product p left join product_hot_stat h on h.product_id=p.id left join product_online_stat m on m.product_id=p.id where p.id in", "<foreach collection='ids' item='id' open='(' separator=',' close=')'>#{id}</foreach>","order by p.id", "</script>"})
  List<Map<String,Object>> selectOnlineSources(@Param("ids") List<Long> ids);

}
