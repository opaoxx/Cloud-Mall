package com.cloudmall.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class SeckillAddressPropagationTest {
  @Test
  void persistsAddressSnapshotFromAcceptedSeckillMessage() {
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    ProductClient products = mock(ProductClient.class);
    when(orderSqlMapper.findIdempotentOrderNos(8L, "request-1")).thenReturn(List.of());
    ProductClient.SkuView sku =
        new ProductClient.SkuView(7L, 9L, "商品", "SKU-7", new BigDecimal("12.30"), Map.of());
    when(products.sku(7L)).thenReturn(ApiResponse.ok(sku));
    when(orderSqlMapper.findOrderId(anyString(), anyString())).thenReturn(88L);

    OrderServiceImpl service =
        new OrderServiceImpl(
            mock(StockClient.class),
            products,
            mock(com.cloudmall.order.feign.UserClient.class),
            mock(CartClient.class),
            orderSqlMapper,
            new ObjectMapper());
    service.consumeSeckill(
        Map.of(
            "orderNo", "20260914-seckill-1",
            "idempotencyKey", "request-1",
            "skuId", 7L,
            "userId", 8L,
            "addressSnapshot",
                "{\"receiver\":\"小明\",\"phone\":\"13800138000\",\"detailAddress\":\"云购路1号\"}"));

    verify(orderSqlMapper).insertOrder(anyString(), any(OrderPO.class));
    org.mockito.ArgumentCaptor<OrderPO> captor =
        org.mockito.ArgumentCaptor.forClass(OrderPO.class);
    verify(orderSqlMapper).insertOrder(anyString(), captor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "{\"receiver\":\"小明\",\"phone\":\"13800138000\",\"detailAddress\":\"云购路1号\"}",
        captor.getValue().addressSnapshot);
  }
}
