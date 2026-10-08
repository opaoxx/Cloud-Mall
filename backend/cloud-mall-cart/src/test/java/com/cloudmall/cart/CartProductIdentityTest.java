package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
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

/** 不可信商品响应只能读取Cart，不能刷新Hash/TTL或输出结算价格。 */
class CartProductIdentityTest {
  /** 清理本例登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void failedProofNeverWritesAcrossAddListAndPreview() {
    for (String operation : List.of("add", "all", "preview")) {
      for (String variant : List.of("wrong-id", "missing-id", "error-code")) {
        AuthContext.set(33L, "USER");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("cart:33"))
            .thenReturn(Map.of("7", "{\"skuId\":7,\"quantity\":2,\"unitPrice\":\"1.00\"}"));
        ProductClient products = mock(ProductClient.class);
        Long responseSku = 7L;
        if ("wrong-id".equals(variant)) responseSku = 8L;
        else if ("missing-id".equals(variant)) responseSku = null;
        ApiResponse<ProductClient.SkuView> response =
            ApiResponse.ok(
                new ProductClient.SkuView(responseSku, 999L, "wrong product", "999.00", Map.of()));
        if ("error-code".equals(variant)) response.code = "COMMON_INTERNAL_ERROR";
        when(products.getSku(7L)).thenReturn(response);
        CartServiceImpl service = new CartServiceImpl(redis, new ObjectMapper(), products);
        BizException failure =
            assertThrows(
                BizException.class,
                () -> {
                  switch (operation) {
                    case "add" -> service.add(request());
                    case "all" -> service.all();
                    case "preview" -> service.preview(Map.of("skuIds", List.of(7L)));
                    default -> throw new AssertionError(operation);
                  }
                });
        assertEquals(500, failure.getHttpStatus());
        assertEquals(ErrorCodes.INTERNAL, failure.getCode());
        verify(products).getSku(7L);
        if ("add".equals(operation)) verifyNoInteractions(redis, hashes);
        else {
          verify(redis).opsForHash();
          verify(hashes).entries("cart:33");
          verifyNoMoreInteractions(redis, hashes);
        }
      }
    }
  }

  @Test
  void remoteFailureIsStillPropagatedWithoutGuessingAbsenceOrWritingCart() {
    AuthContext.set(33L, "USER");
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    ProductClient products = mock(ProductClient.class);
    IllegalStateException original = new IllegalStateException("product unavailable");
    when(products.getSku(7L)).thenThrow(original);
    CartServiceImpl service = new CartServiceImpl(redis, new ObjectMapper(), products);
    assertSame(original, assertThrows(IllegalStateException.class, () -> service.add(request())));
    verifyNoInteractions(redis);
    verify(products).getSku(7L);
  }

  /** 原合法SKU与数量请求，不授权响应替换SKU身份。 */
  private static CartItemDTO request() {
    CartItemDTO request = new CartItemDTO();
    request.skuId = 7L;
    request.quantity = 1;
    return request;
  }
}
