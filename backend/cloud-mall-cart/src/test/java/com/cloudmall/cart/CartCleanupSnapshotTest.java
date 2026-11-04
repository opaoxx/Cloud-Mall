package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.cart.service.impl.CartStorage;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.RedisConnectionFailureException;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.RedisScript;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;

/** 不可信跨服务证明零Redis操作，可信证明每项仅一次条件删除。 */
class CartCleanupSnapshotTest {
  /** 清理测试原用户上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void untrustedProofsCannotReadWriteDeleteOrRenewRedis() {
    List<CartCleanupSnapshotDTO> snapshots = new ArrayList<>();
    snapshots.add(null);
    snapshots.add(new CartCleanupSnapshotDTO(null, "{\"skuId\":7}"));
    snapshots.add(new CartCleanupSnapshotDTO(8L, "{\"skuId\":7}"));
    snapshots.add(new CartCleanupSnapshotDTO(7L, null));
    for (String proof :
        List.of(
            " ",
            "null",
            "[]",
            "bad",
            "{}",
            "{\"skuId\":8}",
            "{\"skuId\":\"7\"}",
            "{\"skuId\":7.0}",
            "{\"skuId\":18446744073709551623}",
            "{\"skuId\":7} {}")) snapshots.add(new CartCleanupSnapshotDTO(7L, proof));
    for (CartCleanupSnapshotDTO snapshot : snapshots) {
      Fixture fixture = new Fixture();
      BizException failure =
          assertThrows(BizException.class, () -> fixture.controller.cleanupItem(7L, snapshot));
      assertEquals(400, failure.getHttpStatus());
      verifyNoInteractions(fixture.redis, fixture.products);
    }
  }

  @Test
  void confirmedAndUnknownOutcomesAreSingleAttemptsWithoutReadsOrFallback() {
    for (String outcome : List.of("zero", "one", "null", "other", "redis", "error")) {
      Fixture fixture = new Fixture();
      String raw = " { \"skuId\":7, \"quantity\":2, \"unitPrice\":\"0012.30\" } ";
      CartCleanupSnapshotDTO snapshot = new CartCleanupSnapshotDTO(7L, raw);
      AssertionError fatal = new AssertionError("fatal cleanup");
      if ("redis".equals(outcome))
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(), any()))
            .thenThrow(new RedisConnectionFailureException("unknown delete"));
      else if ("error".equals(outcome))
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(), any()))
            .thenThrow(fatal);
      else
        when(fixture.redis.execute(any(RedisScript.class), anyList(), any(), any()))
            .thenReturn(
                switch (outcome) {
                  case "zero" -> 0L;
                  case "one" -> 1L;
                  case "null" -> null;
                  default -> 9L;
                });
      if ("zero".equals(outcome) || "one".equals(outcome))
        assertEquals("one".equals(outcome), fixture.controller.cleanupItem(7L, snapshot).data);
      else if ("error".equals(outcome))
        assertSame(
            fatal,
            assertThrows(AssertionError.class, () -> fixture.controller.cleanupItem(7L, snapshot)));
      else {
        BizException failure =
            assertThrows(BizException.class, () -> fixture.controller.cleanupItem(7L, snapshot));
        assertEquals("redis".equals(outcome) ? 503 : 500, failure.getHttpStatus());
      }
      verify(fixture.redis)
          .execute(any(RedisScript.class), eq(List.of("cart:33")), eq("7"), eq(raw));
      verifyNoMoreInteractions(fixture.redis);
      verifyNoInteractions(fixture.products);
    }
  }

  @Test
  void snapshotJsonAndCartMappingsExposeTheReviewedContract() throws Exception {
    ObjectMapper mapper = new ObjectMapper();
    var snapshot =
        new CartCleanupSnapshotDTO(Long.MAX_VALUE, " { \"skuId\":9223372036854775807 } ");
    String json = mapper.writeValueAsString(snapshot);
    assertEquals(snapshot, mapper.readValue(json, CartCleanupSnapshotDTO.class));
    assertEquals(2, mapper.readTree(json).size());
    assertTrue(mapper.readTree(json).path("skuId").isIntegralNumber());
    assertEquals(snapshot.expectedJson(), mapper.readTree(json).path("expectedJson").textValue());
    var read = CartController.class.getMethod("cleanupSnapshot", Long.class);
    var cleanup =
        CartController.class.getMethod("cleanupItem", Long.class, CartCleanupSnapshotDTO.class);
    assertArrayEquals(
        new String[] {"/items/{skuId}/cleanup-snapshot"},
        read.getAnnotation(GetMapping.class).value());
    assertArrayEquals(
        new String[] {"/items/{skuId}/cleanup"}, cleanup.getAnnotation(PostMapping.class).value());
    assertNotNull(cleanup.getParameters()[1].getAnnotation(RequestBody.class));
  }

  /** 原用户上下文、真实Service及独立远端替身。 */
  private static class Fixture {
    /** 唯一Redis执行入口。 */
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    /** 清理不应调用商品。 */
    final ProductClient products = mock(ProductClient.class);

    /** 实际被测快照和清理编排。 */
    final CartController controller =
        new CartController(
            new CartServiceImpl(new CartStorage(redis, new ObjectMapper()), products));

    private Fixture() {
      AuthContext.set(33L, "USER");
    }
  }
}
