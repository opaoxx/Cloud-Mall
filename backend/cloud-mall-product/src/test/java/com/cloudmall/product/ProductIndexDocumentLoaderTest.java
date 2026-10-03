package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudmall.product.domain.po.ProductIndexDocument;
import com.cloudmall.product.mapper.ProductIndexMapper;
import com.cloudmall.product.service.ProductIndexDocumentLoader;
import java.math.BigDecimal;
import java.time.LocalDateTime;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 真实 MyBatis Map 将 MySQL DATETIME 映射为 LocalDateTime。 */
class ProductIndexDocumentLoaderTest {
  /** 回填必须接受 LocalDateTime 并投影缺省热度字段。 */
  @Test
  void rebuildProjectionAcceptsLocalDateTime() {
    ProductIndexMapper mapper = mock(ProductIndexMapper.class);
    LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 4, 9, 30);
    Map<String, Object> row = new HashMap<>();
    row.put("id", 42L);
    row.put("category_id", 3L);
    row.put("name", "旅行箱");
    row.put("price", new BigDecimal("99.00"));
    row.put("status", 1);
    row.put("version", 7L);
    row.put("updated_at", updatedAt);
    row.put("view_count", 9L);
    row.put("search_count", 2L);
    row.put("hot_score", new BigDecimal("9.000000"));
    when(mapper.selectIndexSourcesAfter(0L, 200)).thenReturn(List.of(row));

    ProductIndexDocument document = new ProductIndexDocumentLoader(mapper).loadBatch(0L, 200).get(0);

    assertEquals(updatedAt, document.updatedAt());
    assertEquals(9L, document.viewCount());
    assertEquals(new BigDecimal("9.000000"), document.hotScore());
  }
}
