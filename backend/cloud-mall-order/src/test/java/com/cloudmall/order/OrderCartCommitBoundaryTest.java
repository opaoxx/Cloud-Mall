package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.support.TransactionSynchronization;
import org.springframework.transaction.support.TransactionSynchronizationManager;

/** 购物车提交回调的事务上下文、原用户/SKU快照和致命错误边界。 */
class OrderCartCommitBoundaryTest {
  /** 清理本例模拟事务上下文和鉴权，不影响下一项真实事务验证。 */
  @AfterEach
  void clearContext() {
    if (TransactionSynchronizationManager.isSynchronizationActive())
      TransactionSynchronizationManager.clearSynchronization();
    TransactionSynchronizationManager.setActualTransactionActive(false);
    AuthContext.clear();
  }

  @Test
  void noActualTransactionNeverDeletesCartBeforeACommitCanBeProven() {
    Fixture fixture = new Fixture();
    CreateOrderDTO request = request();
    assertEquals("0", fixture.service.create("no-transaction", request).code);
    verifyNoInteractions(fixture.cart);
    verify(fixture.stock, never()).rollback(anyString());
    verify(fixture.stock, never()).reservation(anyString());
  }

  @Test
  void callbackUsesImmutableDeduplicatedSkuAndUserSnapshotsAndDoesNotSwallowError() {
    for (boolean fatal : List.of(false, true)) {
      Fixture fixture = new Fixture();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.initSynchronization();
      CreateOrderDTO request = request();
      assertEquals("0", fixture.service.create("callback-snapshot", request).code);
      verifyNoInteractions(fixture.cart);
      List<TransactionSynchronization> callbacks =
          TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, callbacks.size());
      request.items.forEach(item -> item.skuId = 999L);
      request.items.clear();
      AuthContext.set(99L, "USER");
      if (fatal) {
        AssertionError original = new AssertionError("fatal cart callback");
        doThrow(original).when(fixture.cart).deleteItem(7L, 8L);
        assertSame(
            original, assertThrows(AssertionError.class, () -> callbacks.get(0).afterCommit()));
        verify(fixture.cart).deleteItem(7L, 8L);
        verify(fixture.cart, never()).deleteItem(8L, 8L);
      } else {
        callbacks.get(0).afterCommit();
        verify(fixture.cart).deleteItem(7L, 8L);
        verify(fixture.cart).deleteItem(8L, 8L);
        verifyNoMoreInteractions(fixture.cart);
      }
      verify(fixture.stock, never()).rollback(anyString());
      verify(fixture.stock, never()).reservation(anyString());
      clearContext();
    }
  }

  /** 实际创建编排，持久层和远端明确替身，不把人工同步上下文称作真实提交。 */
  private static class Fixture {
    /** 原购物车客户端替身。 */
    final CartClient cart = mock(CartClient.class);

    /** 原库存客户端替身。 */
    final StockClient stock = mock(StockClient.class);

    /** 实际创建与提交清理编排。 */
    final OrderServiceImpl service;

    private Fixture() {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      ProductClient products = mock(ProductClient.class);
      UserClient users = mock(UserClient.class);
      OrderReservationIntentService intents = mock(OrderReservationIntentService.class);
      OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
      when(products.sku(anyLong()))
          .thenAnswer(
              invocation ->
                  ApiResponse.ok(
                      new ProductClient.SkuView(
                          invocation.getArgument(0), 9L, "商品", "SKU", BigDecimal.TEN, Map.of())));
      when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
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
      when(cart.deleteItem(anyLong(), anyLong())).thenReturn(ApiResponse.ok(null));
      service = new OrderServiceImpl(stock, products, users, cart, orders, new ObjectMapper());
      ReflectionTestUtils.setField(service, "reservationIntentService", intents);
      ReflectionTestUtils.setField(service, "reservationIntentMapper", intentMapper);
      ReflectionTestUtils.setField(service, "orderOutboxMapper", mock(OrderOutboxMapper.class));
      AuthContext.set(8L, "USER");
    }
  }

  /** 重复SKU及可变请求只用于证明回调取原快照，不重读请求或晚期鉴权。 */
  private static CreateOrderDTO request() {
    CreateOrderDTO request = new CreateOrderDTO();
    request.addressId = 9L;
    request.items = new ArrayList<>();
    for (long skuId : List.of(7L, 7L, 8L)) {
      OrderItemDTO item = new OrderItemDTO();
      item.skuId = skuId;
      item.quantity = 1;
      request.items.add(item);
    }
    return request;
  }
}
