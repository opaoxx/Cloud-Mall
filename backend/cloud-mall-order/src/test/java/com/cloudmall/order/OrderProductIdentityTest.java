package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 普通订单只消费请求SKU的商品事实，未通过身份门不得授权库存或持久写。 */
class OrderProductIdentityTest {
  /** 清理本例原用户上下文。 */
  @AfterEach
  void clearAuth() {
    AuthContext.clear();
  }

  @Test
  void wrongAndMissingResponseSkuAreRejectedBeforeIntentStockAndOrderWrites() {
    for (String variant : List.of("wrong", "missing")) {
      Fixture fixture = new Fixture();
      Long responseSku = null;
      if ("wrong".equals(variant)) responseSku = 8L;
      doReturn(ApiResponse.ok(product(responseSku, BigDecimal.TEN))).when(fixture.products).sku(7L);
      BizException failure =
          assertThrows(
              BizException.class,
              () -> fixture.service.create("wrong-product-" + variant, request(false)));
      assertEquals(500, failure.getHttpStatus());
      assertEquals(ErrorCodes.INTERNAL, failure.getCode());
      verify(fixture.products).sku(7L);
      fixture.verifyNoBusinessWrites();
    }
  }

  @Test
  void aLaterIdentityFailureDoesNotPersistTheEarlierLegitimateLine() {
    Fixture fixture = new Fixture();
    doReturn(ApiResponse.ok(product(9L, new BigDecimal("99.90")))).when(fixture.products).sku(8L);
    BizException failure =
        assertThrows(
            BizException.class, () -> fixture.service.create("later-wrong-product", request(true)));
    assertEquals(500, failure.getHttpStatus());
    assertEquals(ErrorCodes.INTERNAL, failure.getCode());
    verify(fixture.products).sku(7L);
    verify(fixture.products).sku(8L);
    fixture.verifyNoBusinessWrites();
  }

  @Test
  void originalMissingEnvelopeAndDataClassificationPrecedesTheNewIdentityGate() {
    for (String fault : List.of("null-response", "null-data", "nonzero")) {
      Fixture fixture = new Fixture();
      if ("null-response".equals(fault)) doReturn(null).when(fixture.products).sku(7L);
      else if ("null-data".equals(fault))
        doReturn(ApiResponse.ok(null)).when(fixture.products).sku(7L);
      else {
        ApiResponse<ProductClient.SkuView> response = ApiResponse.ok(product(8L, BigDecimal.TEN));
        response.code = "wrong-code";
        doReturn(response).when(fixture.products).sku(7L);
      }
      BizException failure =
          assertThrows(
              BizException.class,
              () -> fixture.service.create("missing-product-" + fault, request(false)));
      assertEquals(404, failure.getHttpStatus());
      assertEquals(ErrorCodes.NOT_FOUND, failure.getCode());
      fixture.verifyNoBusinessWrites();
    }
  }

  @Test
  void matchingIdentityKeepsOriginalPriceAndRequestSkuInStockAndPersistedLines() {
    Fixture fixture = new Fixture();
    BigDecimal originalPrice = new BigDecimal("0012.3000");
    doReturn(ApiResponse.ok(product(7L, originalPrice))).when(fixture.products).sku(7L);
    CreateOrderDTO request = request(false);
    request.items.get(0).quantity = 3;
    assertEquals("0", fixture.service.create("matching-product", request).code);
    var stockRequest = org.mockito.ArgumentCaptor.forClass(StockClient.Reservation.class);
    verify(fixture.stock).reserve(stockRequest.capture());
    assertEquals(7L, stockRequest.getValue().items().get(0).skuId());
    assertEquals(3, stockRequest.getValue().items().get(0).quantity());
    var item = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
    verify(fixture.orders).insertOrderItem(anyString(), item.capture());
    assertEquals(7L, item.getValue().skuId);
    assertEquals(3, item.getValue().quantity);
    assertSame(originalPrice, item.getValue().unitPrice);
    assertEquals(new BigDecimal("36.9000"), item.getValue().lineAmount);
    var order = org.mockito.ArgumentCaptor.forClass(OrderPO.class);
    verify(fixture.orders).insertOrder(anyString(), order.capture());
    assertEquals(new BigDecimal("36.9000"), order.getValue().totalAmount);
    assertEquals(order.getValue().totalAmount, order.getValue().payAmount);
    verifyNoInteractions(fixture.cart);
  }

  @Test
  void nullAndNegativePricesCannotAuthorizeIntentStockOrOrderWrites() {
    for (String fault : List.of("null", "negative", "later-negative")) {
      Fixture fixture = new Fixture();
      BigDecimal firstPrice =
          "null".equals(fault)
              ? null
              : "negative".equals(fault) ? new BigDecimal("-0.001") : BigDecimal.TEN;
      doReturn(ApiResponse.ok(product(7L, firstPrice))).when(fixture.products).sku(7L);
      if ("later-negative".equals(fault))
        doReturn(ApiResponse.ok(product(8L, new BigDecimal("-1E-10"))))
            .when(fixture.products)
            .sku(8L);
      BizException failure =
          assertThrows(
              BizException.class,
              () ->
                  fixture.service.create(
                      "untrusted-price-" + fault, request("later-negative".equals(fault))));
      assertEquals(500, failure.getHttpStatus());
      assertEquals(ErrorCodes.INTERNAL, failure.getCode());
      assertTrue(failure.getMessage().contains("价格"));
      fixture.verifyNoBusinessWrites();
    }
    Fixture identityFirst = new Fixture();
    doReturn(ApiResponse.ok(product(8L, null))).when(identityFirst.products).sku(7L);
    BizException failure =
        assertThrows(
            BizException.class,
            () -> identityFirst.service.create("identity-before-price", request(false)));
    assertTrue(failure.getMessage().contains("SKU"));
    identityFirst.verifyNoBusinessWrites();
  }

  @Test
  void zeroAndPositiveDecimalRepresentationsAreNotNormalizedOrRounded() {
    for (String representation : List.of("0.00", "-0.0000", "1E+1", "0012.3000")) {
      Fixture fixture = new Fixture();
      BigDecimal price = new BigDecimal(representation);
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      CreateOrderDTO request = request(false);
      request.items.get(0).quantity = 3;
      assertEquals("0", fixture.service.create("legal-price-" + representation, request).code);
      var detail = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
      var order = org.mockito.ArgumentCaptor.forClass(OrderPO.class);
      verify(fixture.orders).insertOrderItem(anyString(), detail.capture());
      verify(fixture.orders).insertOrder(anyString(), order.capture());
      assertSame(price, detail.getValue().unitPrice);
      BigDecimal expectedLine = price.multiply(BigDecimal.valueOf(3));
      assertEquals(expectedLine, detail.getValue().lineAmount);
      assertEquals(BigDecimal.ZERO.add(expectedLine), order.getValue().totalAmount);
      assertEquals(order.getValue().totalAmount, order.getValue().payAmount);
      verify(fixture.stock).reserve(any());
      verifyNoInteractions(fixture.cart);
    }
  }

  @Test
  void nonCentPricesAreRejectedBeforeIntentStockAndOrderWrites() {
    for (String representation : List.of("12.345", "0.001", "1E-3", "later")) {
      Fixture fixture = new Fixture();
      if ("later".equals(representation))
        doReturn(ApiResponse.ok(product(8L, new BigDecimal("12.345"))))
            .when(fixture.products)
            .sku(8L);
      else
        doReturn(ApiResponse.ok(product(7L, new BigDecimal(representation))))
            .when(fixture.products)
            .sku(7L);
      BizException failure =
          assertThrows(
              BizException.class,
              () ->
                  fixture.service.create(
                      "non-cent-" + representation, request("later".equals(representation))));
      assertEquals(500, failure.getHttpStatus());
      assertEquals(ErrorCodes.INTERNAL, failure.getCode());
      assertTrue(failure.getMessage().contains("精度"));
      fixture.verifyNoBusinessWrites();
    }
    Fixture tiny = new Fixture();
    BigDecimal price = new BigDecimal(java.math.BigInteger.ONE, Integer.MAX_VALUE);
    doReturn(ApiResponse.ok(product(7L, price))).when(tiny.products).sku(7L);
    assertEquals(
        500,
        assertThrows(
                BizException.class,
                () -> tiny.service.create("extreme-positive-scale", request(false)))
            .getHttpStatus());
    tiny.verifyNoBusinessWrites();
  }

  @Test
  void centExactTrailingZerosKeepTheOriginalUnitPriceAndCalculatedAmounts() {
    for (String representation : List.of("12.3400", "0.0100", "99.99000", "1E+1", "0.0000")) {
      Fixture fixture = new Fixture();
      BigDecimal price = new BigDecimal(representation);
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      CreateOrderDTO request = request(false);
      request.items.get(0).quantity = 2;
      assertEquals("0", fixture.service.create("cent-exact-" + representation, request).code);
      var detail = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
      var order = org.mockito.ArgumentCaptor.forClass(OrderPO.class);
      verify(fixture.orders).insertOrderItem(anyString(), detail.capture());
      verify(fixture.orders).insertOrder(anyString(), order.capture());
      assertSame(price, detail.getValue().unitPrice);
      BigDecimal expected = price.multiply(BigDecimal.valueOf(2));
      assertEquals(expected, detail.getValue().lineAmount);
      assertEquals(BigDecimal.ZERO.add(expected), order.getValue().totalAmount);
      assertEquals(order.getValue().totalAmount, order.getValue().payAmount);
    }
  }

  @Test
  void unitLineAndTotalCapacityFailuresPrecedeIntentStockAndOrderWrites() {
    for (String scenario : List.of("unit", "line", "total", "extreme-unit")) {
      Fixture fixture = new Fixture();
      BigDecimal price =
          "extreme-unit".equals(scenario)
              ? new BigDecimal(java.math.BigInteger.TEN, Integer.MIN_VALUE)
              : new BigDecimal(
                  "unit".equals(scenario)
                      ? "1E+16"
                      : "line".equals(scenario) ? "5000000000000000.00" : "6000000000000000.00");
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      CreateOrderDTO request = request("total".equals(scenario));
      if ("line".equals(scenario)) request.items.get(0).quantity = 2;
      else if ("total".equals(scenario)) {
        request.items.get(1).quantity = 1;
        doReturn(ApiResponse.ok(product(8L, new BigDecimal("4000000000000000.00"))))
            .when(fixture.products)
            .sku(8L);
      }
      BizException failure =
          assertThrows(
              BizException.class, () -> fixture.service.create("capacity-" + scenario, request));
      boolean unitFailure = scenario.endsWith("unit") || "unit".equals(scenario);
      assertEquals(unitFailure ? 500 : 400, failure.getHttpStatus());
      assertEquals(unitFailure ? ErrorCodes.INTERNAL : ErrorCodes.INVALID, failure.getCode());
      if (unitFailure) assertTrue(failure.getMessage().contains("容量"));
      fixture.verifyNoBusinessWrites();
    }
  }

  @Test
  void exactCapacityBoundaryPreservesOriginalAmountsAndRequestLines() {
    BigDecimal maximum = new BigDecimal("9999999999999999.99");
    for (String scenario : List.of("max", "line-boundary", "total-boundary", "max-trailing")) {
      Fixture fixture = new Fixture();
      BigDecimal price =
          new BigDecimal(
              "line-boundary".equals(scenario)
                  ? "4999999999999999.99"
                  : "total-boundary".equals(scenario)
                      ? "9999999999999999.98"
                      : "max-trailing".equals(scenario)
                          ? "9999999999999999.9900"
                          : maximum.toPlainString());
      doReturn(ApiResponse.ok(product(7L, price))).when(fixture.products).sku(7L);
      CreateOrderDTO request = request("total-boundary".equals(scenario));
      if ("line-boundary".equals(scenario)) request.items.get(0).quantity = 2;
      else if ("total-boundary".equals(scenario)) {
        request.items.get(1).quantity = 1;
        doReturn(ApiResponse.ok(product(8L, new BigDecimal("0.01"))))
            .when(fixture.products)
            .sku(8L);
      }
      assertEquals("0", fixture.service.create("boundary-" + scenario, request).code);
      var order = org.mockito.ArgumentCaptor.forClass(OrderPO.class);
      var details = org.mockito.ArgumentCaptor.forClass(OrderItemPO.class);
      verify(fixture.orders).insertOrder(anyString(), order.capture());
      verify(fixture.orders, times(request.items.size()))
          .insertOrderItem(anyString(), details.capture());
      assertSame(price, details.getAllValues().get(0).unitPrice);
      BigDecimal expectedTotal = BigDecimal.ZERO;
      for (OrderItemPO detail : details.getAllValues()) {
        assertEquals(
            detail.unitPrice.multiply(BigDecimal.valueOf(detail.quantity)), detail.lineAmount);
        expectedTotal = expectedTotal.add(detail.lineAmount);
      }
      assertEquals(expectedTotal, order.getValue().totalAmount);
      assertEquals(expectedTotal, order.getValue().payAmount);
      assertTrue(expectedTotal.compareTo(maximum) <= 0);
      if (!"line-boundary".equals(scenario)) assertEquals(0, maximum.compareTo(expectedTotal));
      var reservation = org.mockito.ArgumentCaptor.forClass(StockClient.Reservation.class);
      verify(fixture.stock).reserve(reservation.capture());
      assertEquals(request.items.size(), reservation.getValue().items().size());
      for (int index = 0; index < request.items.size(); index++) {
        assertEquals(
            request.items.get(index).skuId, reservation.getValue().items().get(index).skuId());
        assertEquals(
            request.items.get(index).quantity,
            reservation.getValue().items().get(index).quantity());
      }
    }
  }

  /** 原商品事实对象，不转换身份或规范化金额。 */
  private static ProductClient.SkuView product(Long skuId, BigDecimal price) {
    return new ProductClient.SkuView(skuId, 9L, "商品", "SKU", price, Map.of());
  }

  /** 单SKU或第一合法第二待验证SKU的普通请求。 */
  private static CreateOrderDTO request(boolean withSecond) {
    CreateOrderDTO request = new CreateOrderDTO();
    request.addressId = 9L;
    OrderItemDTO first = new OrderItemDTO();
    first.skuId = 7L;
    first.quantity = 1;
    OrderItemDTO second = new OrderItemDTO();
    second.skuId = 8L;
    second.quantity = 2;
    request.items = withSecond ? List.of(first, second) : List.of(first);
    return request;
  }

  /** 真实创建编排与独立依赖替身；不把直接调用称作真实提交事务。 */
  private static class Fixture {
    /** 原库存RPC替身。 */
    final StockClient stock = mock(StockClient.class);

    /** 当前商品事实RPC替身。 */
    final ProductClient products = mock(ProductClient.class);

    /** 原地址归属RPC替身。 */
    final UserClient users = mock(UserClient.class);

    /** 无事务时不读取或清理Cart。 */
    final CartClient cart = mock(CartClient.class);

    /** 订单读写入口替身。 */
    final OrderSqlMapper orders = mock(OrderSqlMapper.class);

    /** 原预扣意图独立事务编排替身。 */
    final OrderReservationIntentService intents = mock(OrderReservationIntentService.class);

    /** 原本地预扣意图提交入口替身。 */
    final OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);

    /** 原超时outbox持久入口替身。 */
    final OrderOutboxMapper outbox = mock(OrderOutboxMapper.class);

    /** 实际被测普通创建编排。 */
    final OrderServiceImpl service;

    private Fixture() {
      AuthContext.set(8L, "USER");
      when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
      when(products.sku(7L)).thenReturn(ApiResponse.ok(product(7L, new BigDecimal("12.30"))));
      when(intents.createIntent(any())).thenReturn(true);
      when(intentMapper.markCommitted(anyString(), any())).thenReturn(1);
      doAnswer(
              invocation -> {
                StockClient.Reservation reservation = invocation.getArgument(0);
                return ApiResponse.ok(
                    Map.of("orderNo", reservation.orderNo(), "status", "RESERVED"));
              })
          .when(stock)
          .reserve(any());
      when(orders.findOrder(anyString(), anyString()))
          .thenAnswer(
              invocation ->
                  List.of(
                      Map.of(
                          "order_no",
                          invocation.getArgument(1),
                          "user_id",
                          8L,
                          "status",
                          "PENDING_PAYMENT",
                          "total_amount",
                          BigDecimal.TEN,
                          "pay_amount",
                          BigDecimal.TEN,
                          "address_snapshot",
                          "{}")));
      service = new OrderServiceImpl(stock, products, users, cart, orders, new ObjectMapper());
      ReflectionTestUtils.setField(service, "reservationIntentService", intents);
      ReflectionTestUtils.setField(service, "reservationIntentMapper", intentMapper);
      ReflectionTestUtils.setField(service, "orderOutboxMapper", outbox);
    }

    /** 幂等投影只读允许存在，任何其他订单写或库存/意图/Cart交互均不允许。 */
    private void verifyNoBusinessWrites() {
      verify(orders).findIdempotentOrders(eq(8L), anyString());
      verifyNoMoreInteractions(orders);
      verifyNoInteractions(stock, intents, intentMapper, outbox, cart);
    }
  }
}
