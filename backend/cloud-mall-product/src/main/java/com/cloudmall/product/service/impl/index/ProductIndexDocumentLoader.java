package com.cloudmall.product.service.impl.index;

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
  /** Disabled legacy construction never asks the mapper for new online tables. */
  @org.springframework.beans.factory.annotation.Value("${cloudmall.product.metrics.enabled:false}")
  private boolean metricsEnabled;

  /** 商品索引事实数据 Mapper。 */
  private final ProductIndexMapper productIndexMapper;

  /** 注入当前商品事实 Mapper，读取模式仍由原配置开关决定。 */
  public ProductIndexDocumentLoader(ProductIndexMapper productIndexMapper) {
    this.productIndexMapper = productIndexMapper;
  }

  /** 在原只读 RR 事务内读取当前商品快照；无记录返回空，不使用事件旧内容替代数据库事实。 */
  @org.springframework.transaction.annotation.Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
      timeout = 5)
  public Optional<ProductIndexDocument> load(long productId) {
    // 1. 按原开关选择普通内容或带在线指标的事实查询。
    List<Map<String, Object>> sources =
        metricsEnabled
            ? productIndexMapper.selectOnlineSource(productId)
            : productIndexMapper.selectIndexSource(productId);
    // 2. 只映射首条当前事实，字段、金额、时间与空结果语义保持。
    return sources.stream().findFirst().map(ProductIndexDocumentLoader::toDocument);
  }

  /** 按稳定商品主键游标读取一个有界重建批次。 */
  @org.springframework.transaction.annotation.Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
      timeout = 5)
  public List<ProductIndexDocument> loadBatch(long cursorProductId, int batchSize) {
    if (cursorProductId < 0 || batchSize < 1 || batchSize > 500)
      throw new IllegalArgumentException("商品索引重建游标或批次无效");
    return (metricsEnabled
            ? productIndexMapper.selectOnlineSourcesAfter(cursorProductId, batchSize)
            : productIndexMapper.selectIndexSourcesAfter(cursorProductId, batchSize))
        .stream().map(ProductIndexDocumentLoader::toDocument).toList();
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
            ? "UNKNOWN"
            : String.valueOf(source.get("metric_source_type")),
        (String) source.get("metric_source_ref"),
        onlineStat(source));
  }

  /** Read one bounded current content/stat snapshot, then release the transaction before ES. */
  @org.springframework.transaction.annotation.Transactional(
      readOnly = true,
      isolation = org.springframework.transaction.annotation.Isolation.REPEATABLE_READ,
      timeout = 5)
  public List<ProductIndexDocument> loadOnline(List<Long> ids) {
    if (ids.isEmpty()) return List.of();
    if (ids.size() > 100) throw new IllegalArgumentException("METRIC_SNAPSHOT_PRODUCT_LIMIT");
    return productIndexMapper.selectOnlineSources(ids).stream()
        .map(ProductIndexDocumentLoader::toDocument)
        .toList();
  }

  /** 从同一查询快照中提取当前商品的在线指标字段。 */
  private static com.cloudmall.product.domain.po.ProductOnlineStatPO onlineStat(
      Map<String, Object> source) {
    if (!source.containsKey("metric_version")) return null;
    com.cloudmall.product.domain.po.ProductOnlineStatPO stat =
        new com.cloudmall.product.domain.po.ProductOnlineStatPO();
    stat.productId = ((Number) source.get("id")).longValue();
    stat.viewCount = ((Number) source.get("online_view_count")).longValue();
    stat.searchCount = ((Number) source.get("online_search_count")).longValue();
    stat.soldCount = ((Number) source.get("online_sold_count")).longValue();
    stat.hotScore = ((Number) source.get("online_hot_score")).longValue();
    stat.metricVersion = ((Number) source.get("metric_version")).longValue();
    return stat;
  }
}
