package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.dto.ProductSkuDTO;
import com.cloudmall.cart.domain.vo.CartItemVO;
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
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** 仅明确compare mismatch才重试，原JSON期望字节、预算和未知结果不重复应用。 */
class CartAddCompareWriteTest {
  /** 清理本例登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void threeConfirmedMismatchesReturn409WithoutDirectWritesOrExtraProductCalls() {
    Fixture fixture = new Fixture();
    when(fixture.redis.execute(
            any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
        .thenReturn(0L);
    BizException failure =
        assertThrows(BizException.class, () -> fixture.controller.add(request()));
    assertEquals(409, failure.getHttpStatus());
    assertEquals(ErrorCodes.CONFLICT, failure.getCode());
    verify(fixture.hashes, times(3)).entries("cart:33");
    verifyNoMoreInteractions(fixture.hashes);
    verify(fixture.redis, times(3))
        .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
    verify(fixture.redis, never()).expire(anyString(), any(java.time.Duration.class));
    verify(fixture.products).getSku(7L);
  }

  @Test
  void mismatchRereadsRawJsonAndCalculatesFromNewQuantityOnlyOnce() {
    Fixture fixture = new Fixture();
    String original = " { \"skuId\":7, \"quantity\":2, \"checked\":false } ";
    String latest = "{\"skuId\":7,\"quantity\":5,\"checked\":false}";
    when(fixture.hashes.entries("cart:33")).thenReturn(Map.of("7", original), Map.of("7", latest));
    AtomicInteger calls = new AtomicInteger();
    when(fixture.redis.execute(
            any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
        .thenAnswer(
            invocation -> {
              int call = calls.incrementAndGet();
              assertEquals(call == 1 ? original : latest, invocation.getArgument(4));
              return call == 1 ? 0L : 1L;
            });
    assertEquals(7, ((CartItemVO) fixture.controller.add(request()).data).quantity);
    assertEquals(2, calls.get());
    verify(fixture.hashes, times(2)).entries("cart:33");
    verifyNoMoreInteractions(fixture.hashes);
    verify(fixture.products).getSku(7L);
  }

  @Test
  void unknownReplyAndRedisExceptionDoNotRereadOrRetryTheWrite() {
    for (String result : List.of("null", "other", "redis-error")) {
      Fixture fixture = new Fixture();
      if ("redis-error".equals(result))
        when(fixture.redis.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
            .thenThrow(new RedisConnectionFailureException("write outcome unavailable"));
      else
        when(fixture.redis.execute(
                any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
            .thenReturn("null".equals(result) ? null : Long.valueOf(9));
      BizException failure =
          assertThrows(BizException.class, () -> fixture.controller.add(request()));
      assertEquals("redis-error".equals(result) ? 503 : 500, failure.getHttpStatus());
      verify(fixture.hashes).entries("cart:33");
      verifyNoMoreInteractions(fixture.hashes);
      verify(fixture.redis)
          .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
      verify(fixture.products).getSku(7L);
    }
  }

  /** 全新替身防止执行结果和读取次数在case间互相污染。 */
  private static class Fixture {
    /** Redis读与脚本入口替身。 */
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    /** Hash快照读取替身。 */
    final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);

    /** 一次商品事实查询替身。 */
    final ProductClient products = mock(ProductClient.class);

    /** 实际被测追加编排。 */
    final CartController controller =
        new CartController(
            new CartServiceImpl(new CartStorage(redis, new ObjectMapper()), products));

    private Fixture() {
      AuthContext.set(33L, "USER");
      when(redis.opsForHash()).thenReturn(hashes);
      when(hashes.entries("cart:33"))
          .thenReturn(Map.of("7", "{\"skuId\":7,\"quantity\":2,\"checked\":false}"));
      when(products.getSku(7L))
          .thenReturn(ApiResponse.ok(new ProductSkuDTO(7L, 99L, "product", "12.30", Map.of())));
    }
  }

  /** 原请求增量2不随竞争重新生成或重复应用。 */
  private static CartItemDTO request() {
    CartItemDTO request = new CartItemDTO();
    request.skuId = 7L;
    request.quantity = 2;
    return request;
  }
}
