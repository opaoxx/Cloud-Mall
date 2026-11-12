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
import com.cloudmall.order.domain.po.OrderIdempotencyPO;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.test.util.ReflectionTestUtils;

/** 普通下单公开入口的幂等键列宽门，验证非法请求无数据库或远程副作用。 */
class NormalOrderIdempotencyKeyTest {
  /** 普通下单的库存远程依赖。 */
  private StockClient stock;

  /** 普通下单的商品远程依赖。 */
  private ProductClient products;

  /** 普通下单的地址远程依赖。 */
  private UserClient users;

  /** 普通下单的购物车远程依赖。 */
  private CartClient cart;

  /** 幂等查询和订单写入入口。 */
  private OrderSqlMapper orders;

  /** 持久预扣意图入口。 */
  private OrderReservationIntentService intents;

  /** 被验证的真实普通下单编排。 */
  private OrderTestService service;

  @BeforeEach
  void setup() {
    stock = mock(StockClient.class);
    products = mock(ProductClient.class);
    users = mock(UserClient.class);
    cart = mock(CartClient.class);
    orders = mock(OrderSqlMapper.class);
    intents = mock(OrderReservationIntentService.class);
    service = new OrderTestService(stock, products, users, cart, orders, new ObjectMapper());
    ReflectionTestUtils.setField(service, "reservationIntentService", intents);
    AuthContext.set(8L, "USER");
  }

  @AfterEach
  void clearAuthentication() {
    AuthContext.clear();
  }

  @Test
  void rejects129AsciiCharactersBeforeAnyDependency() {
    assertRejected("k".repeat(129));
  }

  @Test
  void rejects129BmpCharactersBeforeAnyDependency() {
    assertRejected("中".repeat(129));
  }

  @Test
  void rejects129NonBmpCharactersBeforeAnyDependency() {
    assertRejected("😀".repeat(129));
  }

  @Test
  void nullAndBlankKeepTheirOriginal400WithoutDependencies() {
    for (String key : Arrays.asList(null, "", " \t")) assertRejected(key);
  }

  @Test
  void boundaryKeysReachIntentUnchangedAndKeepUnknownReserveSemantics() {
    for (String key : List.of("k".repeat(128), "😀".repeat(128))) {
      setup();
      when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
      when(products.sku(7L))
          .thenReturn(
              ApiResponse.ok(
                  new ProductClient.SkuView(7L, 10L, "商品", "sku", BigDecimal.ONE, Map.of())));
      when(intents.createIntent(any())).thenReturn(true);
      BizException result = assertThrows(BizException.class, () -> service.create(key, request()));
      assertEquals(202, result.getHttpStatus());
      assertEquals("ORDER_RESERVATION_UNKNOWN", result.getCode());
      verify(orders).findIdempotentOrders(8L, key);
      var captured = ArgumentCaptor.forClass(OrderReservationIntentPO.class);
      verify(intents).createIntent(captured.capture());
      assertEquals(key, captured.getValue().idempotencyKey);
      verify(stock).reserve(any());
      verify(orders, never()).insertOrder(anyString(), any());
      verify(orders, never()).insertIdempotency(any());
      verifyNoInteractions(cart);
    }
  }

  @Test
  void boundaryKeyReplayStillReturnsItsExistingOrderWithoutRemoteCalls() {
    for (String key : List.of("k".repeat(128), "😀".repeat(128))) {
      setup();
      String orderNo = "202610-existing";
      when(orders.findIdempotentOrders(8L, key)).thenReturn(List.of(storedIdentity(key, orderNo)));
      when(orders.findOrder("mall_order_202610", orderNo))
          .thenReturn(
              List.of(
                  Map.of(
                      "order_no",
                      orderNo,
                      "user_id",
                      8L,
                      "status",
                      "PENDING_PAYMENT",
                      "total_amount",
                      BigDecimal.ONE,
                      "pay_amount",
                      BigDecimal.ONE,
                      "address_snapshot",
                      "{}")));
      ApiResponse<?> response = service.create(key, request());
      assertEquals("0", response.code);
      assertEquals(orderNo, ((OrderVO) response.data).orderNo);
      verify(orders).findIdempotentOrders(8L, key);
      verifyNoInteractions(stock, products, users, cart, intents);
      verify(orders, never()).insertOrder(anyString(), any());
      verify(orders, never()).insertIdempotency(any());
    }
  }

  @Test
  void aliasesAndMissingStoredKeysFailBeforeAnyOldOrderReadOrRpc() {
    for (String storedKey : Arrays.asList("KEY-CASE", "Kéy-Case", "Key-Case ", "", null)) {
      setup();
      when(orders.findIdempotentOrders(8L, "Key-Case"))
          .thenReturn(List.of(storedIdentity(storedKey, "202610-existing")));
      BizException failure =
          assertThrows(BizException.class, () -> service.create("Key-Case", request()));
      assertEquals(409, failure.getHttpStatus());
      assertEquals(ErrorCodes.DUPLICATE, failure.getCode());
      verify(orders, never()).findOrder(anyString(), anyString());
      verify(orders, never()).insertOrder(anyString(), any());
      verify(orders, never()).insertIdempotency(any());
      verifyNoInteractions(stock, products, users, cart, intents);
    }
  }

  /** 构造已持久化的原始键与订单号，用于完整身份查询替身。 */
  private static OrderIdempotencyPO storedIdentity(String key, String orderNo) {
    OrderIdempotencyPO identity = new OrderIdempotencyPO();
    identity.id = 1L;
    identity.userId = 8L;
    identity.idempotencyKey = key;
    identity.orderNo = orderNo;
    identity.createdAt = java.time.LocalDateTime.parse("2026-10-08T12:00:00");
    return identity;
  }

  /** 非法键必须在任何依赖调用前拒绝，不能形成持久认领或库存动作。 */
  private void assertRejected(String key) {
    BizException result = assertThrows(BizException.class, () -> service.create(key, request()));
    assertEquals(400, result.getHttpStatus());
    assertEquals(ErrorCodes.INVALID, result.getCode());
    verifyNoInteractions(stock, products, users, cart, orders, intents);
  }

  /** 构造合法商品和地址，避免请求体错误遮蔽幂等键校验。 */
  private static CreateOrderDTO request() {
    OrderItemDTO item = new OrderItemDTO();
    item.skuId = 7L;
    item.quantity = 1;
    CreateOrderDTO request = new CreateOrderDTO();
    request.addressId = 9L;
    request.items = List.of(item);
    return request;
  }
}
