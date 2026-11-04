package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.cart.service.impl.CartStorage;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.HashOperations;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;

/** clearChecked只对同次读取的原JSON执行一次条件删除，无重读、续TTL或异常补偿。 */
class CartCheckedCompareDeleteTest {
  /** 本例结束时释放登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void confirmedMismatchAndSuccessUseExactRawJsonOnceWithoutDirectDeletes() {
    for (Long outcome : List.of(0L, 1L)) {
      Fixture fixture = new Fixture();
      String raw =
          " { \"skuId\":7, \"checked\":true, \"productId\":9223372036854775807,"
              + " \"unitPrice\":\"0012.30\" } ";
      when(fixture.hashes.entries("cart:33")).thenReturn(Map.of("7", raw));
      when(fixture.redis.execute(any(RedisScript.class), eq(List.of("cart:33")), eq("7"), eq(raw)))
          .thenReturn(outcome);
      var response = fixture.controller.clearChecked();
      assertEquals("0", response.code);
      assertNull(response.data);
      verify(fixture.redis).opsForHash();
      verify(fixture.hashes).entries("cart:33");
      verify(fixture.redis)
          .execute(any(RedisScript.class), eq(List.of("cart:33")), eq("7"), eq(raw));
      verifyNoMoreInteractions(fixture.hashes, fixture.redis);
      verifyNoInteractions(fixture.products);
    }
  }

  @Test
  void unknownResultsDependencyFailureAndErrorsAreNeverRepeated() {
    for (String fault : List.of("null", "other", "redis", "error")) {
      Fixture fixture = new Fixture();
      AssertionError fatal = new AssertionError("fatal script execution");
      if ("redis".equals(fault))
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(), any()))
            .thenThrow(new RedisConnectionFailureException("unknown delete"));
      else if ("error".equals(fault))
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(), any()))
            .thenThrow(fatal);
      else
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(), any()))
            .thenReturn("null".equals(fault) ? null : Long.valueOf(9L));
      if ("error".equals(fault))
        assertSame(fatal, assertThrows(AssertionError.class, fixture.controller::clearChecked));
      else {
        BizException failure = assertThrows(BizException.class, fixture.controller::clearChecked);
        assertEquals("redis".equals(fault) ? 503 : 500, failure.getHttpStatus());
        assertEquals(
            "redis".equals(fault) ? ErrorCodes.REDIS_UNAVAILABLE : ErrorCodes.INTERNAL,
            failure.getCode());
      }
      verify(fixture.redis).opsForHash();
      verify(fixture.hashes).entries("cart:33");
      verify(fixture.redis).execute(any(RedisScript.class), eq(List.of("cart:33")), eq("7"), any());
      verifyNoMoreInteractions(fixture.redis, fixture.hashes);
      verifyNoInteractions(fixture.products);
    }
  }

  @Test
  void emptyAndUncheckedSnapshotsNeverCallTheScriptOrRenewTtl() {
    for (Map<Object, Object> snapshot :
        List.<Map<Object, Object>>of(
            Map.of(),
            Map.of("7", "{\"skuId\":7,\"checked\":false}"),
            Map.of("7", "{\"skuId\":7,\"checked\":null}"))) {
      Fixture fixture = new Fixture();
      when(fixture.hashes.entries("cart:33")).thenReturn(snapshot);
      assertNull(fixture.controller.clearChecked().data);
      verify(fixture.redis).opsForHash();
      verify(fixture.hashes).entries("cart:33");
      verifyNoMoreInteractions(fixture.redis, fixture.hashes);
      verifyNoInteractions(fixture.products);
    }
  }

  @Test
  void aLaterUnknownCandidateDoesNotRetryOrCompensateAnEarlierDeletion() {
    Fixture fixture = new Fixture();
    Map<Object, Object> snapshot = new LinkedHashMap<>();
    for (long skuId : List.of(7L, 8L, 9L))
      snapshot.put(String.valueOf(skuId), "{\"skuId\":" + skuId + ",\"checked\":true}");
    when(fixture.hashes.entries("cart:33")).thenReturn(snapshot);
    when(fixture.redis.execute(any(RedisScript.class), anyList(), any(), any()))
        .thenReturn(1L, null);
    BizException failure = assertThrows(BizException.class, fixture.controller::clearChecked);
    assertEquals(500, failure.getHttpStatus());
    var ordered = inOrder(fixture.redis, fixture.hashes);
    ordered.verify(fixture.redis).opsForHash();
    ordered.verify(fixture.hashes).entries("cart:33");
    ordered
        .verify(fixture.redis)
        .execute(any(RedisScript.class), eq(List.of("cart:33")), eq("7"), eq(snapshot.get("7")));
    ordered
        .verify(fixture.redis)
        .execute(any(RedisScript.class), eq(List.of("cart:33")), eq("8"), eq(snapshot.get("8")));
    verifyNoMoreInteractions(fixture.redis, fixture.hashes);
    verifyNoInteractions(fixture.products);
  }

  /** 每个测试独立的Redis与Product替身，实际业务编排保持。 */
  private static class Fixture {
    /** Hash读取与Lua入口。 */
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    /** 一次原始快照读取。 */
    final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);

    /** 清理不能引入商品调用。 */
    final ProductClient products = mock(ProductClient.class);

    /** 实际购物车业务实现。 */
    final CartController controller =
        new CartController(
            new CartServiceImpl(new CartStorage(redis, new ObjectMapper()), products));

    private Fixture() {
      AuthContext.set(33L, "USER");
      when(redis.opsForHash()).thenReturn(hashes);
      when(hashes.entries("cart:33")).thenReturn(Map.of("7", "{\"skuId\":7,\"checked\":true}"));
    }
  }
}
