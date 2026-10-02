package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyMap;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.domain.dto.SeckillOrderDTO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 库存 Sentinel 拒绝与用户限购必须保留不同业务码。 */
class SeckillSentinelFeignMappingTest {
  private final StockClient stock = mock(StockClient.class);
  private final UserClient users = mock(UserClient.class);
  private final OrderServiceImpl orders = new OrderServiceImpl(stock, mock(ProductClient.class),
      users, mock(CartClient.class), mock(OrderSqlMapper.class), new ObjectMapper());

  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void knownAndUnknownStock429HaveDifferentRetrySemantics() {
    AuthContext.set(33L, "USER");
    when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 3L))));
    SeckillOrderDTO request = new SeckillOrderDTO();
    request.activityId = 11L;
    request.skuId = 22L;
    request.addressId = 3L;
    FeignException response = mock(FeignException.class);
    when(response.status()).thenReturn(429);
    when(stock.seckill(anyMap())).thenThrow(response);

    when(response.contentUTF8()).thenReturn("{\"code\":\"GATEWAY_RATE_LIMITED\"}");
    BizException limited = assertThrows(BizException.class,
        () -> orders.seckill("same-key", request));
    assertEquals(429, limited.getHttpStatus());
    assertEquals("GATEWAY_RATE_LIMITED", limited.getCode());

    when(response.contentUTF8()).thenReturn("{\"code\":\"SECKILL_LIMIT_REACHED\"}");
    BizException userLimit = assertThrows(BizException.class,
        () -> orders.seckill("same-key", request));
    assertEquals(429, userLimit.getHttpStatus());
    assertEquals("SECKILL_LIMIT_REACHED", userLimit.getCode());

    when(response.contentUTF8()).thenReturn("garbled");
    BizException unknown = assertThrows(BizException.class,
        () -> orders.seckill("same-key", request));
    assertEquals(503, unknown.getHttpStatus());
    assertEquals("GATEWAY_SERVICE_UNAVAILABLE", unknown.getCode());
  }
}
