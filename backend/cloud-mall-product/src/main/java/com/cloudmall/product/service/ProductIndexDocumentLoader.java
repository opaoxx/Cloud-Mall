package com.cloudmall.product.service;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.mapper.ProductIndexMapper;
import com.cloudmall.product.util.ProductDateTimes;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.springframework.stereotype.Component;

/** Reads the current MySQL fact before projecting it to Elasticsearch. */
@Component
public class ProductIndexDocumentLoader {
  /** 商品索引事实数据 Mapper。 */
  private final ProductIndexMapper productIndexMapper;

  /** 创建 ProductIndexDocumentLoader 实例。 */
  public ProductIndexDocumentLoader(ProductIndexMapper productIndexMapper) {
    // 1. 接收并整理 ProductIndexDocumentLoader 的业务请求。
    // 2. 执行 ProductIndexDocumentLoader 的核心业务校验与状态处理。
    // 3. 返回 ProductIndexDocumentLoader 的处理结果。
    this.productIndexMapper = productIndexMapper;
  }

  /** 执行 load 相关操作。 */
  public Optional<ProductIndexDocument> load(long productId) {
    // 1. 接收并整理 load 的业务请求。
    // 2. 执行 load 的核心业务校验与状态处理。
    // 3. 返回 load 的处理结果。
    List<Map<String, Object>> sources = productIndexMapper.selectIndexSource(productId);
    return sources.stream().findFirst().map(ProductIndexDocumentLoader::toDocument);
  }

  /** 按稳定商品主键游标读取一个有界重建批次。 */
  public List<ProductIndexDocument> loadBatch(long cursorProductId, int batchSize) {
    if (cursorProductId < 0 || batchSize < 1 || batchSize > 500)
      throw new IllegalArgumentException("商品索引重建游标或批次无效");
    return productIndexMapper.selectIndexSourcesAfter(cursorProductId, batchSize).stream()
        .map(ProductIndexDocumentLoader::toDocument)
        .toList();
  }

  /** 查询商品事实总数，用于存量重建进度与索引核对。 */
  public long countSources() {
    return productIndexMapper.countIndexSources();
  }

  /** 将一张商品事实行转换为带版本的 ES 投影。 */
  private static ProductIndexDocument toDocument(Map<String, Object> source) {
    return new ProductIndexDocument(
        ((Number) source.get("id")).longValue(),
        ((Number) source.get("category_id")).longValue(),
        String.valueOf(source.get("name")),
        (String) source.get("main_image"),
        (String) source.get("description"),
        (java.math.BigDecimal) source.get("price"),
        ((Number) source.get("status")).intValue(),
        ((Number) source.get("version")).longValue(),
        ProductDateTimes.asLocalDateTime(source.get("updated_at")),
        ((Number) source.get("view_count")).longValue(),
        ((Number) source.get("search_count")).longValue(),
        (java.math.BigDecimal) source.get("hot_score"),
        source.get("metric_source_type") == null
            ? "UNKNOWN" : String.valueOf(source.get("metric_source_type")),
        (String) source.get("metric_source_ref"));
  }
}
