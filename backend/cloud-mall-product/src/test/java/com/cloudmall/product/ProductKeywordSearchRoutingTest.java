package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.error.BizException;
import com.cloudmall.product.controller.ProductController;
import com.cloudmall.product.domain.po.ProductSearchHit;
import com.cloudmall.product.domain.po.ProductSearchPage;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.impl.index.ProductIndexOutboxService;
import com.cloudmall.product.service.impl.search.ElasticsearchProductKeywordSearch;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 检查关键词路由、事实回读和 ES 失败契约。 */
class ProductKeywordSearchRoutingTest {
  /** 非空关键词必须走 ES，ES 故障不能偷偷回退 MySQL LIKE。 */
  @Test
  void esFailureDoesNotCallLegacyLike() throws Exception {
    ProductSqlMapper mapper = mock(ProductSqlMapper.class);
    ElasticsearchProductKeywordSearch search = mock(ElasticsearchProductKeywordSearch.class);
    ProductController service = service(mapper, search);
    when(search.search(eq("旅行箱"), eq(null), eq(1), eq(0), eq(20), eq(null)))
        .thenThrow(new IllegalStateException("ES unavailable"));

    BizException error =
        assertThrows(BizException.class, () -> service.products(" 旅行箱 ", null, 1, 1, 20, null));

    assertEquals(503, error.getHttpStatus());
    assertEquals("PRODUCT_SEARCH_UNAVAILABLE", error.getCode());
    verify(mapper, never()).countProducts(any(), any(), any());
    verify(mapper, never()).findProducts(any(), any(), any(), anyInt(), anyInt(), any());
  }

  /** ES 顺序和精确 total 保留，响应摘要使用同版本的 MySQL 商品事实。 */
  @Test
  void matchingPageUsesMysqlFactsWithoutLike() throws Exception {
    ProductSqlMapper mapper = mock(ProductSqlMapper.class);
    ElasticsearchProductKeywordSearch search = mock(ElasticsearchProductKeywordSearch.class);
    ProductController service = service(mapper, search);
    LocalDateTime updatedAt = LocalDateTime.of(2026, 10, 4, 8, 30);
    long epochMillis = updatedAt.atZone(ZoneId.of("Asia/Shanghai")).toInstant().toEpochMilli();
    when(search.search("旅行箱", null, 1, 0, 20, "price_asc"))
        .thenReturn(
            new ProductSearchPage(
                List.of(
                    new ProductSearchHit(
                        42L, 3L, "旅行箱", new BigDecimal("99.00"), 1, 7L, epochMillis)),
                12293L));
    when(mapper.findProductFacts(List.of(42L)))
        .thenReturn(
            List.of(
                Map.of(
                    "id",
                    42L,
                    "category_id",
                    3L,
                    "name",
                    "旅行箱",
                    "main_image",
                    "image",
                    "description",
                    "desc",
                    "price",
                    new BigDecimal("99.00"),
                    "status",
                    1,
                    "version",
                    7L,
                    "updated_at",
                    Timestamp.valueOf(updatedAt))));

    ApiResponse<?> response = service.products("旅行箱", null, 1, 1, 20, "price_asc");

    PageResult<?> page = (PageResult<?>) response.data;
    assertEquals(12293L, page.total);
    assertEquals(1, page.items.size());
    verify(mapper, never()).countProducts(any(), any(), any());
    verify(mapper, never()).findProducts(any(), any(), any(), anyInt(), anyInt(), any());
  }

  /** ES 连续返回过期版本时必须重试后返回 503，不能泄露旧商品摘要。 */
  @Test
  void staleIndexPageIsRejectedAfterBoundedRetry() throws Exception {
    ProductSqlMapper mapper = mock(ProductSqlMapper.class);
    ElasticsearchProductKeywordSearch search = mock(ElasticsearchProductKeywordSearch.class);
    ProductController service = service(mapper, search);
    when(search.search("旅行箱", null, 1, 0, 20, null))
        .thenReturn(
            new ProductSearchPage(
                List.of(new ProductSearchHit(42L, 3L, "旅行箱", new BigDecimal("99.00"), 1, 7L, 1L)),
                1L));
    when(mapper.findProductFacts(List.of(42L)))
        .thenReturn(List.of(Map.of("version", 8L, "id", 42L)));

    BizException error =
        assertThrows(BizException.class, () -> service.products("旅行箱", null, 1, 1, 20, null));

    assertEquals("PRODUCT_SEARCH_RETRYABLE", error.getCode());
    verify(search, org.mockito.Mockito.times(2)).search("旅行箱", null, 1, 0, 20, null);
    verify(mapper, never()).countProducts(any(), any(), any());
  }

  /** 构造搜索开关开启的服务实例。 */
  private static ProductController service(
      ProductSqlMapper mapper, ElasticsearchProductKeywordSearch search) {
    ProductController service =
        ProductTestServices.controller(
            mapper,
            mock(StringRedisTemplate.class),
            new ObjectMapper(),
            mock(ProductIndexOutboxService.class));
    ReflectionTestUtils.setField(ProductTestServices.queries(service), "searchEnabled", true);
    ReflectionTestUtils.setField(
        ProductTestServices.queries(service), "productKeywordSearch", search);
    return service;
  }
}
