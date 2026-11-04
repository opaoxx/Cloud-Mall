package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartItemDTO;
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
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;

/** 价格语法失败必须在写回前拒绝，并保留原解析异常供定位。 */
class CartProductPriceTest {
  /** 清理本例登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void malformedPriceIsControlled500WithNoWritesAndOriginalParseEvidence() {
    for (String operation : List.of("add", "all", "preview")) {
      for (String price : java.util.Arrays.asList(null, "", " ", "NaN", "Infinity", "bad")) {
        AuthContext.set(33L, "USER");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        when(hashes.entries("cart:33"))
            .thenReturn(
                Map.of(
                    "7", "{\"skuId\":7,\"quantity\":2,\"checked\":true,\"unitPrice\":\"1.00\"}"));
        ProductClient products = mock(ProductClient.class);
        when(products.getSku(7L))
            .thenReturn(ApiResponse.ok(new ProductSkuDTO(7L, 99L, "product", price, Map.of())));
        CartController controller =
            new CartController(
                new CartServiceImpl(new CartStorage(redis, new ObjectMapper()), products));
        CartItemDTO request = new CartItemDTO();
        request.skuId = 7L;
        request.quantity = 1;
        BizException failure =
            assertThrows(
                BizException.class,
                () -> {
                  switch (operation) {
                    case "add" -> controller.add(request);
                    case "all" -> controller.all();
                    case "preview" ->
                        controller.preview(new CartSelectionDTO(Map.of("skuIds", List.of(7L))));
                    default -> throw new AssertionError(operation);
                  }
                });
        assertEquals(500, failure.getHttpStatus());
        assertEquals(ErrorCodes.INTERNAL, failure.getCode());
        if (price == null) assertEquals(0, failure.getSuppressed().length);
        else {
          assertEquals(1, failure.getSuppressed().length);
          assertInstanceOf(NumberFormatException.class, failure.getSuppressed()[0]);
        }
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
}
