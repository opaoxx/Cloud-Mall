package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.domain.dto.CartItemDTO;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
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

/** PUT/checked只在确认冲突后重读，只更新本次字段，不重建缺失项。 */
class CartPutCheckedCompareWriteTest {
  /** 清理本例登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void repeatedConflictsAreBoundedAndNeverUseDirectHashWrites() {
    for (String operation : List.of("update", "checked")) {
      Fixture fixture = new Fixture();
      when(fixture.redis.execute(
              any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
          .thenReturn(0L);
      BizException failure = assertThrows(BizException.class, () -> fixture.mutate(operation));
      assertEquals(409, failure.getHttpStatus());
      assertEquals(ErrorCodes.CONFLICT, failure.getCode());
      verify(fixture.hashes, times(3)).entries("cart:33");
      verifyNoMoreInteractions(fixture.hashes);
      verify(fixture.redis, times(3))
          .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
      verifyNoInteractions(fixture.products);
    }
  }

  @Test
  void retryPreservesLatestUnrequestedFieldsAndRawJsonExpectations() {
    for (String operation : List.of("update", "checked")) {
      Fixture fixture = new Fixture();
      String original =
          " { \"skuId\":7, \"quantity\":2, \"checked\":false, \"unitPrice\":\"1.00\" } ";
      String latest =
          "{\"skuId\":7,\"quantity\":5,\"checked\":true,\"unitPrice\":\"7.80\",\"productName\":\"latest\"}";
      when(fixture.hashes.entries("cart:33"))
          .thenReturn(Map.of("7", original), Map.of("7", latest));
      AtomicInteger calls = new AtomicInteger();
      when(fixture.redis.execute(
              any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
          .thenAnswer(
              invocation -> {
                int call = calls.incrementAndGet();
                assertEquals("1", invocation.getArgument(3));
                assertEquals(call == 1 ? original : latest, invocation.getArgument(4));
                return call == 1 ? 0L : 1L;
              });
      CartItemVO result = (CartItemVO) fixture.mutate(operation).data;
      assertEquals("update".equals(operation) ? 7 : 5, result.quantity);
      assertTrue(result.checked);
      assertEquals("7.80", result.unitPrice);
      assertEquals("latest", result.productName);
      assertEquals(2, calls.get());
      verifyNoInteractions(fixture.products);
    }
  }

  @Test
  void missingFieldAndUnknownExecutionNeverCreateOrRepeatTheMutation() {
    for (String operation : List.of("update", "checked")) {
      for (String fault : List.of("missing", "null", "other", "redis")) {
        Fixture fixture = new Fixture();
        if ("missing".equals(fault)) when(fixture.hashes.entries("cart:33")).thenReturn(Map.of());
        else if ("redis".equals(fault))
          when(fixture.redis.execute(
                  any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
              .thenThrow(new RedisConnectionFailureException("unknown write"));
        else
          when(fixture.redis.execute(
                  any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
              .thenReturn("null".equals(fault) ? null : Long.valueOf(9));
        BizException failure = assertThrows(BizException.class, () -> fixture.mutate(operation));
        assertEquals(
            "missing".equals(fault) ? 404 : "redis".equals(fault) ? 503 : 500,
            failure.getHttpStatus());
        verify(fixture.hashes).entries("cart:33");
        verifyNoMoreInteractions(fixture.hashes);
        verify(fixture.redis, times("missing".equals(fault) ? 0 : 1))
            .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
        verifyNoInteractions(fixture.products);
      }
    }
  }

  /** 全新替身，只给已有canonical项，不注入新的商品事实。 */
  private static class Fixture {
    /** Redis查询与脚本入口替身。 */
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    /** 原Hash快照读取替身。 */
    final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);

    /** 两种更新不应新增商品调用。 */
    final ProductClient products = mock(ProductClient.class);

    /** 实际业务更新编排。 */
    final CartServiceImpl service = new CartServiceImpl(redis, new ObjectMapper(), products);

    private Fixture() {
      AuthContext.set(33L, "USER");
      when(redis.opsForHash()).thenReturn(hashes);
      when(hashes.entries("cart:33"))
          .thenReturn(Map.of("7", "{\"skuId\":7,\"quantity\":2,\"checked\":false}"));
    }

    private com.cloudmall.common.api.ApiResponse<?> mutate(String operation) {
      if ("checked".equals(operation)) return service.checked(7L, Map.of("checked", true));
      CartItemDTO request = new CartItemDTO();
      request.quantity = 7;
      request.checked = null;
      return service.update(7L, request);
    }
  }
}
