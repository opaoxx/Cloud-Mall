package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoMoreInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.product.domain.vo.HotStatVO;
import com.cloudmall.product.mapper.ProductHotStatMapper;
import com.cloudmall.product.mapper.ProductIndexMapper;
import com.cloudmall.product.service.impl.metrics.ProductHotStatFreshnessAudit;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import java.time.LocalDateTime;
import java.util.Map;
import org.elasticsearch.client.RequestOptions;
import org.elasticsearch.client.RestHighLevelClient;
import org.elasticsearch.client.core.CountRequest;
import org.elasticsearch.client.core.CountResponse;
import org.junit.jupiter.api.Test;

/** 指标来源对外可见，审计只读且能观察 ES 来源字段缺失。 */
class ProductHotStatSourceTest {
  /** 旧响应字段保留，并增加明确的历史目录来源。 */
  @Test
  void hotStatResponseExposesLegacySource() throws Exception {
    JsonNode json =
        new ObjectMapper()
            .valueToTree(new HotStatVO(42L, 12L, 3L, "12.000000", "LEGACY_IMPORT", "hm-item.item"));

    assertEquals(12L, json.path("viewCount").asLong());
    assertEquals("LEGACY_IMPORT", json.path("sourceType").asText());
    assertEquals("hm-item.item", json.path("sourceRef").asText());
  }

  /** 缺失 ES 来源标记只更新 Gauge 和日志，不改写 MySQL 或索引。 */
  @Test
  void freshnessAuditReportsMissingIndexSourceWithoutWrites() throws Exception {
    ProductHotStatMapper hotStatMapper = mock(ProductHotStatMapper.class);
    ProductIndexMapper indexMapper = mock(ProductIndexMapper.class);
    RestHighLevelClient elasticsearch = mock(RestHighLevelClient.class);
    CountResponse allDocuments = mock(CountResponse.class);
    CountResponse markedDocuments = mock(CountResponse.class);
    CountResponse matchingLegacy = mock(CountResponse.class);
    when(allDocuments.getCount()).thenReturn(2L);
    when(markedDocuments.getCount()).thenReturn(1L);
    when(matchingLegacy.getCount()).thenReturn(1L);
    when(elasticsearch.count(any(CountRequest.class), eq(RequestOptions.DEFAULT)))
        .thenReturn(allDocuments, markedDocuments, matchingLegacy);
    when(indexMapper.countIndexSources()).thenReturn(2L);
    when(hotStatMapper.summarizeSources())
        .thenReturn(
            Map.of(
                "total_count",
                2L,
                "legacy_count",
                2L,
                "unknown_count",
                0L,
                "latest_legacy_update",
                LocalDateTime.now().minusDays(2)));
    SimpleMeterRegistry registry = new SimpleMeterRegistry();
    ProductHotStatFreshnessAudit audit =
        new ProductHotStatFreshnessAudit(
            hotStatMapper, indexMapper, elasticsearch, registry, "cloudmall_product");

    audit.audit();

    assertEquals(
        1.0, registry.get("cloudmall.product.hot_stat.index_missing_sources").gauge().value());
    assertEquals(
        1.0,
        registry.get("cloudmall.product.hot_stat.inconsistent_legacy_sources").gauge().value());
    assertEquals(1.0, registry.get("cloudmall.product.hot_stat.audit_healthy").gauge().value());
    assertTrue(
        registry.get("cloudmall.product.hot_stat.latest_age_seconds").gauge().value() > 24 * 3600);
    verify(hotStatMapper).summarizeSources();
    verify(indexMapper).countIndexSources();
    verifyNoMoreInteractions(hotStatMapper, indexMapper);
  }
}
