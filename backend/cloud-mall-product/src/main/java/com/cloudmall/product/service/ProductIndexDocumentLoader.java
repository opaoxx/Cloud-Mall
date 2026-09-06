package com.cloudmall.product.service;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.mapper.ProductIndexMapper;
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
    return sources.stream()
        .findFirst()
        .map(
            source ->
                new ProductIndexDocument(
                    ((Number) source.get("id")).longValue(),
                    ((Number) source.get("category_id")).longValue(),
                    String.valueOf(source.get("name")),
                    (String) source.get("main_image"),
                    (String) source.get("description"),
                    (java.math.BigDecimal) source.get("price"),
                    ((Number) source.get("status")).intValue()));
  }
}
