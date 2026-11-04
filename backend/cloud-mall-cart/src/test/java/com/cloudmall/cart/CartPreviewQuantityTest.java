package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartSelectionDTO;
import com.cloudmall.cart.domain.dto.ProductSkuDTO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.cart.service.impl.CartStorage;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 已选数量必须整集预检，不能先查询/刷新前序合法SKU。 */
class CartPreviewQuantityTest {
  /** 清理本例登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void invalidLaterQuantityStopsAllProductAndRedisWritesBeforeAnyAmountWork() {
    for (int quantity : List.of(0, -1, Integer.MIN_VALUE)) {
      AuthContext.set(33L, "USER");
      StringRedisTemplate redis = mock(StringRedisTemplate.class);
      @SuppressWarnings("unchecked")
      HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
      when(redis.opsForHash()).thenReturn(hashes);
      Map<Object, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("7", "{\"skuId\":7,\"quantity\":2,\"checked\":true}");
      snapshot.put("8", "{\"skuId\":8,\"quantity\":" + quantity + ",\"checked\":true}");
      when(hashes.entries("cart:33")).thenReturn(snapshot);
      ProductClient products = mock(ProductClient.class);
      when(products.getSku(7L))
          .thenReturn(ApiResponse.ok(new ProductSkuDTO(7L, 99L, "product7", "12.30", Map.of())));
      when(products.getSku(8L))
          .thenReturn(ApiResponse.ok(new ProductSkuDTO(8L, 88L, "product8", "88.00", Map.of())));
      CartController controller =
          new CartController(
              new CartServiceImpl(new CartStorage(redis, new ObjectMapper()), products));
      BizException failure =
          assertThrows(
              BizException.class,
              () -> controller.preview(new CartSelectionDTO(Map.of("skuIds", List.of(7L, 8L)))));
      assertEquals(500, failure.getHttpStatus());
      assertEquals(ErrorCodes.INTERNAL, failure.getCode());
      verifyNoInteractions(products);
      verify(redis).opsForHash();
      verify(hashes).entries("cart:33");
      verifyNoMoreInteractions(redis, hashes);
    }
  }
}
