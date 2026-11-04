package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.dto.ProductSkuDTO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.cart.service.impl.CartStorage;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 数量拒绝必须在Hash写入和TTL刷新前结束，不能用错误响应掩盖存储变更。 */
class CartQuantityMergeTest {
  /** 清理本例登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void rejectedOverflowAndCorruptSourceNeverWriteOrRefreshTtl() {
    for (int[] values :
        List.of(
            new int[] {Integer.MAX_VALUE, 1},
            new int[] {1, Integer.MAX_VALUE},
            new int[] {0, 5},
            new int[] {-1, 5},
            new int[] {Integer.MIN_VALUE, 5})) {
      AuthContext.set(33L, "USER");
      StringRedisTemplate redis = mock(StringRedisTemplate.class);
      @SuppressWarnings("unchecked")
      HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
      when(redis.opsForHash()).thenReturn(hashes);
      when(hashes.entries("cart:33"))
          .thenReturn(
              Map.of(
                  "7",
                  "{\"skuId\":7,\"quantity\":"
                      + values[0]
                      + ",\"checked\":false,\"addedAt\":\"original\"}"));
      ProductClient products = mock(ProductClient.class);
      when(products.getSku(7L))
          .thenReturn(
              ApiResponse.ok(new ProductSkuDTO(7L, 99L, "current product", "12.30", Map.of())));
      CartItemDTO request = new CartItemDTO();
      request.skuId = 7L;
      request.quantity = values[1];
      BizException failure =
          assertThrows(
              BizException.class,
              () ->
                  new CartController(
                          new CartServiceImpl(new CartStorage(redis, new ObjectMapper()), products))
                      .add(request));
      assertEquals(values[0] < 1 ? 500 : 400, failure.getHttpStatus());
      assertEquals(values[0] < 1 ? ErrorCodes.INTERNAL : ErrorCodes.INVALID, failure.getCode());
      verify(hashes).entries("cart:33");
      verifyNoMoreInteractions(hashes);
      verify(redis).opsForHash();
      verifyNoMoreInteractions(redis);
    }
  }
}
