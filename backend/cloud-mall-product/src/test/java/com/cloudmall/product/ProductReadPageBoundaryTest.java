package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.error.BizException;
import com.cloudmall.product.controller.ProductController;
import com.cloudmall.product.mapper.ProductSqlMapper;
import com.cloudmall.product.service.impl.index.ProductIndexOutboxService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;

class ProductReadPageBoundaryTest {
  private final ProductSqlMapper mapper = mock(ProductSqlMapper.class);
  private final ProductController service =
      ProductTestServices.controller(
          mapper,
          mock(StringRedisTemplate.class),
          new ObjectMapper(),
          mock(ProductIndexOutboxService.class));

  @Test
  void overflowingPageOffsetReturns400BeforeIssuingListSql() {
    when(mapper.countProducts(any(), any(), any())).thenReturn(88476L);

    BizException failure =
        assertThrows(
            BizException.class,
            () -> service.products(null, null, null, Integer.MAX_VALUE, 20, null));

    assertEquals(400, failure.getHttpStatus());
    verify(mapper, never()).findProducts(any(), any(), any(), anyInt(), anyInt(), any());
  }

  @Test
  void pagePastTotalReturnsEmptyWithoutScanningTheCatalog() {
    when(mapper.countProducts(any(), any(), any())).thenReturn(88476L);

    ApiResponse<?> response = service.products(null, null, null, 5000, 20, null);

    PageResult<?> page = (PageResult<?>) response.data;
    assertEquals(88476L, page.total);
    assertEquals(List.of(), page.items);
    verify(mapper, never()).findProducts(any(), any(), any(), anyInt(), anyInt(), any());
  }
}
