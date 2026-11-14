package com.cloudmall.product.mapper;

import com.baomidou.mybatisplus.core.mapper.BaseMapper;
import com.cloudmall.product.domain.po.ProductHotStatPO;
import java.util.Map;
import org.apache.ibatis.annotations.Mapper;
import org.apache.ibatis.annotations.Select;

/** 商品热度统计表数据访问接口。 */
@Mapper
public interface ProductHotStatMapper extends BaseMapper<ProductHotStatPO> {
  /** 只读汇总热度来源和最近更新时间，不改写历史指标。 */
  @Select(
      "SELECT COUNT(*) total_count,"
          + "COALESCE(SUM(source_type='LEGACY_IMPORT'),0) legacy_count,"
          + "COALESCE(SUM(source_type='UNKNOWN'),0) unknown_count,"
          + "MAX(CASE WHEN source_type='LEGACY_IMPORT' THEN updated_at END)"
          + " latest_legacy_update FROM product_hot_stat")
  Map<String, Object> summarizeSources();
}
