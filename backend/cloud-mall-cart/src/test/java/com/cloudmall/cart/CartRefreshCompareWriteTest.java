package com.cloudmall.cart;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.cart.controller.CartController;
import com.cloudmall.cart.domain.vo.CartItemVO;
import com.cloudmall.cart.domain.vo.CartSettlementPreviewVO;
import com.cloudmall.cart.feign.ProductClient;
import com.cloudmall.cart.service.impl.CartServiceImpl;
import com.cloudmall.common.api.ApiResponse;
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

/** 摘要刷新每项一次CAS，冲突/未知不能变成成功预览或追写并发状态。 */
class CartRefreshCompareWriteTest {
  /** 清理本例登录上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void confirmedConflictStopsTheRequestWithoutRereadingOrBecomingAnInvalidSku() {
    for (String operation : List.of("all", "preview")) {
      Fixture fixture = new Fixture();
      String raw = " { \"skuId\":7, \"quantity\":2, \"checked\":true, \"unitPrice\":\"1.00\" } ";
      when(fixture.hashes.entries("cart:33")).thenReturn(Map.of("7", raw));
      when(fixture.redis.execute(
              any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
          .thenAnswer(
              invocation -> {
                assertEquals(List.of("cart:33"), invocation.getArgument(1));
                assertEquals("7", invocation.getArgument(2));
                assertEquals("1", invocation.getArgument(3));
                assertEquals(raw, invocation.getArgument(4));
                return 0L;
              });
      BizException failure = assertThrows(BizException.class, () -> fixture.refresh(operation));
      assertEquals(409, failure.getHttpStatus());
      assertEquals(ErrorCodes.CONFLICT, failure.getCode());
      fixture.verifySingleRefresh();
    }
  }

  @Test
  void unknownResultsDependencyFailuresAndErrorsAreNotRepeated() {
    for (String operation : List.of("all", "preview")) {
      for (String fault : List.of("null", "other", "redis", "error")) {
        Fixture fixture = new Fixture();
        AssertionError fatal = new AssertionError("fatal refresh");
        if ("redis".equals(fault))
          when(fixture.redis.execute(
                  any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
              .thenThrow(new RedisConnectionFailureException("unknown refresh"));
        else if ("error".equals(fault))
          when(fixture.redis.execute(
                  any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
              .thenThrow(fatal);
        else
          when(fixture.redis.execute(
                  any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
              .thenReturn("null".equals(fault) ? null : Long.valueOf(9L));
        if ("error".equals(fault))
          assertSame(fatal, assertThrows(AssertionError.class, () -> fixture.refresh(operation)));
        else {
          BizException failure = assertThrows(BizException.class, () -> fixture.refresh(operation));
          assertEquals("redis".equals(fault) ? 503 : 500, failure.getHttpStatus());
          assertEquals(
              "redis".equals(fault) ? ErrorCodes.REDIS_UNAVAILABLE : ErrorCodes.INTERNAL,
              failure.getCode());
        }
        fixture.verifySingleRefresh();
      }
    }
  }

  @Test
  void aLaterConflictDoesNotCompensateAnEarlierRefreshOrContinueToTheNextItem() {
    for (String operation : List.of("all", "preview")) {
      Fixture fixture = new Fixture();
      Map<Object, Object> snapshot = new LinkedHashMap<>();
      for (long skuId : List.of(7L, 8L, 9L)) {
        snapshot.put(String.valueOf(skuId), json(skuId, true));
        doReturn(product(skuId)).when(fixture.products).getSku(skuId);
      }
      when(fixture.hashes.entries("cart:33")).thenReturn(snapshot);
      when(fixture.redis.execute(
              any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
          .thenReturn(1L, 0L);
      BizException failure = assertThrows(BizException.class, () -> fixture.refresh(operation));
      assertEquals(409, failure.getHttpStatus());
      var ordered = inOrder(fixture.redis, fixture.hashes, fixture.products);
      ordered.verify(fixture.redis).opsForHash();
      ordered.verify(fixture.hashes).entries("cart:33");
      ordered.verify(fixture.products).getSku(7L);
      ordered
          .verify(fixture.redis)
          .execute(
              any(RedisScript.class),
              eq(List.of("cart:33")),
              eq("7"),
              eq("1"),
              eq(snapshot.get("7")),
              any(),
              any());
      ordered.verify(fixture.products).getSku(8L);
      ordered
          .verify(fixture.redis)
          .execute(
              any(RedisScript.class),
              eq(List.of("cart:33")),
              eq("8"),
              eq("1"),
              eq(snapshot.get("8")),
              any(),
              any());
      verifyNoMoreInteractions(fixture.redis, fixture.hashes, fixture.products);
    }
  }

  @Test
  void missingProductsStayVisibleAndPreviewKeepsItsOriginalSelectionAndAmountRules() {
    for (String operation : List.of("all", "preview")) {
      Fixture fixture = new Fixture();
      Map<Object, Object> snapshot = new LinkedHashMap<>();
      snapshot.put("7", json(7L, true));
      snapshot.put("8", json(8L, true));
      snapshot.put("9", json(9L, false));
      when(fixture.hashes.entries("cart:33")).thenReturn(snapshot);
      doReturn(ApiResponse.ok(null)).when(fixture.products).getSku(8L);
      doReturn(product(9L)).when(fixture.products).getSku(9L);
      when(fixture.redis.execute(
              any(RedisScript.class), anyList(), any(), any(), any(), any(), any()))
          .thenReturn(1L);
      var response = fixture.refresh(operation);
      List<?> items;
      if ("preview".equals(operation)) {
        CartSettlementPreviewVO preview = (CartSettlementPreviewVO) response.data;
        assertEquals("24.60", preview.totalAmount());
        assertEquals("24.60", preview.payAmount());
        List<?> invalid = (List<?>) preview.invalidItems();
        assertEquals(1, invalid.size());
        assertEquals(8L, preview.invalidItems().get(0).skuId());
        assertEquals(ErrorCodes.NOT_FOUND, preview.invalidItems().get(0).code());
        items = (List<?>) preview.items();
        assertEquals(2, items.size());
        verify(fixture.products, never()).getSku(9L);
      } else {
        items = (List<?>) response.data;
        assertEquals(3, items.size());
        verify(fixture.products).getSku(9L);
      }
      CartItemVO missing = (CartItemVO) items.get(1);
      assertEquals(8L, missing.skuId);
      assertEquals(2, missing.quantity);
      assertEquals("1.00", missing.unitPrice);
      assertTrue(missing.checked);
      verify(fixture.redis, never())
          .execute(any(RedisScript.class), anyList(), eq("8"), any(), any(), any(), any());
      verify(fixture.redis, times("all".equals(operation) ? 2 : 1))
          .execute(any(RedisScript.class), anyList(), any(), any(), any(), any(), any());
      verify(fixture.products).getSku(7L);
      verify(fixture.products).getSku(8L);
      verify(fixture.redis).opsForHash();
      verify(fixture.hashes).entries("cart:33");
      verifyNoMoreInteractions(fixture.redis, fixture.hashes, fixture.products);
    }
  }

  /** 合法原始项，选择语义不能因商品失效或刷新而被改变。 */
  private static String json(long skuId, boolean checked) {
    return "{\"skuId\":"
        + skuId
        + ",\"quantity\":2,\"checked\":"
        + checked
        + ",\"unitPrice\":\"1.00\"}";
  }

  /** 当前商品事实替身，价格按原字符串表示。 */
  private static ApiResponse<ProductClient.SkuView> product(long skuId) {
    return ApiResponse.ok(
        new ProductClient.SkuView(skuId, 99L, "current product", "12.30", Map.of()));
  }

  /** 独立依赖替身与实际业务编排。 */
  private static class Fixture {
    /** Redis读取和脚本入口。 */
    final StringRedisTemplate redis = mock(StringRedisTemplate.class);

    /** 原始Hash快照。 */
    final HashOperations<String, Object, Object> hashes = mock(HashOperations.class);

    /** 每项一次商品事实查询。 */
    final ProductClient products = mock(ProductClient.class);

    /** 实际被测购物车实现。 */
    final CartController controller =
        new CartController(new CartServiceImpl(redis, new ObjectMapper(), products));

    private Fixture() {
      AuthContext.set(33L, "USER");
      when(redis.opsForHash()).thenReturn(hashes);
      when(hashes.entries("cart:33")).thenReturn(Map.of("7", json(7L, true)));
      when(products.getSku(7L)).thenReturn(product(7L));
    }

    private ApiResponse<?> refresh(String operation) {
      return "all".equals(operation)
          ? controller.all()
          : controller.preview(Map.of("skuIds", List.of()));
    }

    /** 单次失败最多一读、一查询、一CAS，不能使用旧Hash put/expire或继续调用。 */
    private void verifySingleRefresh() {
      verify(redis).opsForHash();
      verify(hashes).entries("cart:33");
      verify(products).getSku(7L);
      verify(redis)
          .execute(
              any(RedisScript.class),
              eq(List.of("cart:33")),
              eq("7"),
              eq("1"),
              any(),
              any(),
              any());
      verifyNoMoreInteractions(redis, hashes, products);
    }
  }
}
