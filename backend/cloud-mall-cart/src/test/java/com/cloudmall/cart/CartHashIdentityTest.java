package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
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

/** 原Hash字段与PO身份不一致时，共享读不能授权写/删或商品查询。 */
class CartHashIdentityTest {
  /** 清理本例登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void untrustedRawFieldsAndSkuValuesNeverWriteDeleteExpireOrQueryTheWrongProduct() {
    for (String operation : List.of("add", "update", "checked", "all", "preview", "clear")) {
      for (String variant :
          List.of(
              "wrong-id",
              "missing-id",
              "null-po",
              "leading-zero",
              "non-string",
              "malformed-json")) {
        AuthContext.set(33L, "USER");
        StringRedisTemplate redis = mock(StringRedisTemplate.class);
        @SuppressWarnings("unchecked")
        HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
        when(redis.opsForHash()).thenReturn(hashes);
        Object field =
            "non-string".equals(variant)
                ? Long.valueOf(7L)
                : "leading-zero".equals(variant) ? "07" : "7";
        String json =
            switch (variant) {
              case "wrong-id" -> "{\"skuId\":8,\"quantity\":2}";
              case "missing-id" -> "{\"quantity\":2}";
              case "null-po" -> "null";
              case "malformed-json" -> "not-json";
              default -> "{\"skuId\":7,\"quantity\":2}";
            };
        when(hashes.entries("cart:33")).thenReturn(Map.of(field, json));
        ProductClient products = mock(ProductClient.class);
        when(products.getSku(7L))
            .thenReturn(
                ApiResponse.ok(new ProductClient.SkuView(7L, 99L, "product", "12.30", Map.of())));
        CartServiceImpl service = new CartServiceImpl(redis, new ObjectMapper(), products);
        CartItemDTO request = new CartItemDTO();
        request.skuId = 7L;
        request.quantity = 1;
        BizException failure =
            assertThrows(
                BizException.class,
                () -> {
                  switch (operation) {
                    case "add" -> service.add(request);
                    case "update" -> service.update(7L, request);
                    case "checked" -> service.checked(7L, Map.of("checked", false));
                    case "all" -> service.all();
                    case "preview" -> service.preview(Map.of("skuIds", List.of(7L, 8L)));
                    case "clear" -> service.clearChecked();
                    default -> throw new AssertionError(operation);
                  }
                });
        assertEquals(500, failure.getHttpStatus());
        assertEquals(ErrorCodes.INTERNAL, failure.getCode());
        verify(redis).opsForHash();
        verify(hashes).entries("cart:33");
        verifyNoMoreInteractions(redis, hashes);
        if ("add".equals(operation)) {
          verify(products).getSku(7L);
          verifyNoMoreInteractions(products);
        } else verifyNoInteractions(products);
      }
    }
  }
}
