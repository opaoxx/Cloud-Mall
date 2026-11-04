package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.po.CartItemPO;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.mockito.Mockito;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 新购物车项首次响应与 Redis 值必须具有商品服务返回的摘要。 */
class CartItemCreationTest {
  /** 清理当前测试设置的登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  /** 创建新项时不信任请求中的商品名或金额，并保存完整的首次响应。 */
  @Test
  void firstAddPersistsCurrentSkuFactsAndReturnsThem() throws Exception {
    AuthContext.set(33L, "USER");
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    @SuppressWarnings("unchecked")
    HashOperations<String, Object, Object> hashes = mock(HashOperations.class);
    ProductClient products = mock(ProductClient.class);
    ObjectMapper objectMapper = new ObjectMapper();
    when(redis.opsForHash()).thenReturn(hashes);
    when(hashes.entries("cart:33")).thenReturn(Map.of());
    when(products.getSku(22L))
        .thenReturn(
            ApiResponse.ok(new ProductClient.SkuView(22L, 11L, "商品服务名称", "289.00", Map.of())));
    when(redis.execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
        .thenReturn(1L);
    CartItemDTO request = new CartItemDTO();
    request.skuId = 22L;
    request.quantity = 2;
    request.productName = "请求伪造名称";
    request.unitPrice = "0.01";

    CartItemVO response =
        (CartItemVO)
            new CartController(new CartServiceImpl(redis, objectMapper, products))
                .add(request)
                .data;

    assertEquals(11L, response.productId);
    assertEquals("商品服务名称", response.productName);
    assertEquals("289.00", response.unitPrice);
    assertEquals(2, response.quantity);
    assertTrue(response.checked);
    assertNotNull(response.addedAt);
    assertEquals(ZoneOffset.ofHours(8), OffsetDateTime.parse(response.addedAt).getOffset());
    ArgumentCaptor<Object> savedJson = ArgumentCaptor.forClass(Object.class);
    verify(redis)
        .execute(
            any(RedisScript.class),
            eq(java.util.List.of("cart:33")),
            eq("22"),
            eq("0"),
            eq(""),
            savedJson.capture(),
            eq(String.valueOf(java.time.Duration.ofDays(30).toMillis())));
    verify(hashes).entries("cart:33");
    Mockito.verifyNoMoreInteractions(hashes);
    CartItemPO saved = objectMapper.readValue((String) savedJson.getValue(), CartItemPO.class);
    assertEquals(response.productName, saved.productName);
    assertEquals(response.unitPrice, saved.unitPrice);
    assertEquals(response.addedAt, saved.addedAt);
  }
}
