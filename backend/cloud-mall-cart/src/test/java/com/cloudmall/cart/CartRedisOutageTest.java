package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
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
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

class CartRedisOutageTest {
  private final StringRedisTemplate redis = mock(StringRedisTemplate.class);
  private final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
  private final ProductClient products = mock(ProductClient.class);
  private final CartController cart =
      new CartController(new CartServiceImpl(new CartStorage(redis, new ObjectMapper()), products));

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void redisReadFailureDoesNotLookLikeAnEmptyCart() {
    AuthContext.set(33L, "USER");
    when(redis.opsForHash()).thenReturn(hashes);
    when(hashes.entries("cart:33")).thenThrow(new RedisConnectionFailureException("redis down"));

    BizException failure = assertThrows(BizException.class, cart::all);

    assertEquals(503, failure.getHttpStatus());
    assertEquals("REDIS_SERVICE_UNAVAILABLE", failure.getCode());
    verifyNoInteractions(products);
  }

  @Test
  void refreshWriteFailureCannotBeSilentlyIgnored() {
    AuthContext.set(33L, "USER");
    when(redis.opsForHash()).thenReturn(hashes);
    when(hashes.entries("cart:33"))
        .thenReturn(
            Map.of("22", "{\"skuId\":22,\"productId\":11,\"quantity\":1,\"checked\":true}"));
    when(products.getSku(22L))
        .thenReturn(ApiResponse.ok(new ProductSkuDTO(22L, 11L, "keyboard", "1.00", Map.of())));
    when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    BizException failure = assertThrows(BizException.class, cart::all);

    assertEquals(503, failure.getHttpStatus());
    assertEquals("REDIS_SERVICE_UNAVAILABLE", failure.getCode());
  }

  @Test
  void settlementPreviewDoesNotReportRedisFailureAsInvalidSku() {
    AuthContext.set(33L, "USER");
    when(redis.opsForHash()).thenReturn(hashes);
    when(hashes.entries("cart:33"))
        .thenReturn(
            Map.of("22", "{\"skuId\":22,\"productId\":11,\"quantity\":1,\"checked\":true}"));
    when(products.getSku(22L))
        .thenReturn(ApiResponse.ok(new ProductSkuDTO(22L, 11L, "keyboard", "1.00", Map.of())));
    when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
        .thenThrow(new RedisConnectionFailureException("redis down"));

    BizException failure =
        assertThrows(
            BizException.class,
            () -> cart.preview(new CartSelectionDTO(Map.of("skuIds", java.util.List.of(22L)))));

    assertEquals(503, failure.getHttpStatus());
  }
}
