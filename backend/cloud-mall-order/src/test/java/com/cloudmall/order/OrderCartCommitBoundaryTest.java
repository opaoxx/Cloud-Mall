package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.domain.dto.CartCleanupSnapshotDTO;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
import com.cloudmall.order.domain.po.OrderIdempotencyPO;
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
import org.slf4j.LoggerFactory;
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

  /** code0/null是合法空项；先空后有效时只在提交后清理原有效证明且不误WARN。 */
  @Test
  void successfulEmptySnapshotIsQuietAndNeverAuthorizesCleanup() {
    try (CapturedOrderLogs captured = new CapturedOrderLogs()) {
      Fixture fixture = new Fixture();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.initSynchronization();
      doReturn(ApiResponse.ok(null)).when(fixture.cart).cleanupSnapshot(7L, 8L);

      assertEquals("0", fixture.service.create("empty-cart-item", request()).code);

      var requests = inOrder(fixture.cart);
      requests.verify(fixture.cart).cleanupSnapshot(7L, 8L);
      requests.verify(fixture.cart).cleanupSnapshot(8L, 8L);
      verify(fixture.cart, never()).cleanupItem(anyLong(), anyLong(), any());
      List<TransactionSynchronization> callbacks =
          TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, callbacks.size());
      callbacks.get(0).afterCommit();
      verify(fixture.cart, never()).cleanupItem(eq(7L), anyLong(), any());
      verify(fixture.cart).cleanupItem(8L, 8L, snapshot(8L));
      verify(fixture.cart, never()).deleteItem(anyLong(), anyLong());
      verifyNoMoreInteractions(fixture.cart);
      verify(fixture.stock, never()).rollback(anyString());
      assertEquals(0, captured.snapshotWarnings());
    }
  }

  @Test
  void callbackUsesImmutableDeduplicatedSkuAndUserSnapshotsAndDoesNotSwallowError() {
    for (boolean fatal : List.of(false, true)) {
      Fixture fixture = new Fixture();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.initSynchronization();
      CreateOrderDTO request = request();
      assertEquals("0", fixture.service.create("callback-snapshot", request).code);
      verify(fixture.cart).cleanupSnapshot(7L, 8L);
      verify(fixture.cart).cleanupSnapshot(8L, 8L);
      verify(fixture.cart, never()).cleanupItem(anyLong(), anyLong(), any());
      List<TransactionSynchronization> callbacks =
          TransactionSynchronizationManager.getSynchronizations();
      assertEquals(1, callbacks.size());
      request.items.forEach(item -> item.skuId = 999L);
      request.items.clear();
      AuthContext.set(99L, "USER");
      if (fatal) {
        AssertionError original = new AssertionError("fatal cart callback");
        doThrow(original).when(fixture.cart).cleanupItem(7L, 8L, snapshot(7L));
        assertSame(
            original, assertThrows(AssertionError.class, () -> callbacks.get(0).afterCommit()));
        verify(fixture.cart).cleanupItem(7L, 8L, snapshot(7L));
        verify(fixture.cart, never()).cleanupItem(8L, 8L, snapshot(8L));
      } else {
        callbacks.get(0).afterCommit();
        verify(fixture.cart).cleanupItem(7L, 8L, snapshot(7L));
        verify(fixture.cart).cleanupItem(8L, 8L, snapshot(8L));
        verifyNoMoreInteractions(fixture.cart);
      }
      verify(fixture.stock, never()).rollback(anyString());
      verify(fixture.stock, never()).reservation(anyString());
      clearContext();
    }
  }

  @Test
  void anOlderCommitCallbackNeverUsesUnconditionalDeleteAgainstAReaddedItem() {
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    assertEquals("0", fixture.service.create("readded-after-commit", request()).code);
    var currentItem = new java.util.concurrent.atomic.AtomicReference<String>("readded cart item");
    doAnswer(
            invocation -> {
              currentItem.set(null);
              return ApiResponse.ok(null);
            })
        .when(fixture.cart)
        .deleteItem(7L, 8L);
    doAnswer(
            invocation -> {
              CartCleanupSnapshotDTO original = invocation.getArgument(2);
              boolean removed = currentItem.compareAndSet(original.expectedJson(), null);
              return ApiResponse.ok(removed);
            })
        .when(fixture.cart)
        .cleanupItem(anyLong(), anyLong(), any());
    for (TransactionSynchronization callback :
        TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
    assertAll(
        () -> assertEquals("readded cart item", currentItem.get()),
        () -> verify(fixture.cart, never()).deleteItem(anyLong(), anyLong()));
    verify(fixture.cart).cleanupSnapshot(7L, 8L);
    verify(fixture.cart).cleanupSnapshot(8L, 8L);
    verify(fixture.cart).cleanupItem(7L, 8L, snapshot(7L));
    verify(fixture.cart).cleanupItem(8L, 8L, snapshot(8L));
  }

  @Test
  void invalidSnapshotsNeverAuthorizeCleanupOrTriggerAnUnconditionalFallback() {
    for (String fault :
        List.of(
            "null-response",
            "nonzero",
            "null-code",
            "null-data",
            "wrong-dto",
            "wrong-quantity",
            "blank",
            "string-sku",
            "wrong-json",
            "decimal",
            "overflow",
            "array",
            "trailing",
            "runtime")) {
      try (CapturedOrderLogs captured = new CapturedOrderLogs()) {
        Fixture fixture = new Fixture();
        TransactionSynchronizationManager.setActualTransactionActive(true);
        TransactionSynchronizationManager.initSynchronization();
        doAnswer(
                invocation -> {
                  return switch (fault) {
                    case "null-response" -> null;
                    case "nonzero" -> ApiResponse.error("503", "unavailable");
                    case "null-code" -> new ApiResponse<CartCleanupSnapshotDTO>();
                    case "null-data" -> ApiResponse.ok(null);
                    case "wrong-dto" ->
                        ApiResponse.ok(new CartCleanupSnapshotDTO(99L, "{\"skuId\":7}"));
                    case "wrong-quantity" ->
                        ApiResponse.ok(
                            new CartCleanupSnapshotDTO(7L, "{\"skuId\":7,\"quantity\":5}"));
                    case "blank" -> ApiResponse.ok(new CartCleanupSnapshotDTO(7L, " "));
                    case "string-sku" ->
                        ApiResponse.ok(new CartCleanupSnapshotDTO(7L, "{\"skuId\":\"7\"}"));
                    case "wrong-json" ->
                        ApiResponse.ok(new CartCleanupSnapshotDTO(7L, "{\"skuId\":8}"));
                    case "decimal" ->
                        ApiResponse.ok(new CartCleanupSnapshotDTO(7L, "{\"skuId\":7.0}"));
                    case "overflow" ->
                        ApiResponse.ok(
                            new CartCleanupSnapshotDTO(7L, "{\"skuId\":18446744073709551623}"));
                    case "array" -> ApiResponse.ok(new CartCleanupSnapshotDTO(7L, "[]"));
                    case "trailing" ->
                        ApiResponse.ok(new CartCleanupSnapshotDTO(7L, "{\"skuId\":7} {}"));
                    case "runtime" -> throw new IllegalStateException("snapshot unavailable");
                    default -> throw new AssertionError(fault);
                  };
                })
            .when(fixture.cart)
            .cleanupSnapshot(7L, 8L);
        assertEquals("0", fixture.service.create("bad-snapshot-" + fault, request()).code);
        for (TransactionSynchronization callback :
            TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
        verify(fixture.cart).cleanupSnapshot(7L, 8L);
        verify(fixture.cart).cleanupSnapshot(8L, 8L);
        verify(fixture.cart, never()).cleanupItem(eq(7L), anyLong(), any());
        verify(fixture.cart).cleanupItem(8L, 8L, snapshot(8L));
        verify(fixture.cart, never()).deleteItem(anyLong(), anyLong());
        verifyNoMoreInteractions(fixture.cart);
        verify(fixture.stock, never()).rollback(anyString());
        assertEquals("null-data".equals(fault) ? 0 : 1, captured.snapshotWarnings(), fault);
        clearContext();
      }
    }
  }

  @Test
  void snapshotErrorsRemainFatalAndAreNotConvertedIntoCleanupPermission() {
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    AssertionError original = new AssertionError("fatal snapshot read");
    doThrow(original).when(fixture.cart).cleanupSnapshot(7L, 8L);
    assertSame(
        original,
        assertThrows(
            AssertionError.class, () -> fixture.service.create("fatal-snapshot", request())));
    verify(fixture.cart).cleanupSnapshot(7L, 8L);
    verify(fixture.cart, never()).cleanupSnapshot(8L, 8L);
    verify(fixture.cart, never()).cleanupItem(anyLong(), anyLong(), any());
    verify(fixture.cart, never()).deleteItem(anyLong(), anyLong());
    assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
  }

  @Test
  void cartChangesDuringStockReservationAreNotCapturedAsNewCleanupPermission() {
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    var current =
        new java.util.concurrent.atomic.AtomicReference<CartCleanupSnapshotDTO>(snapshot(7L));
    CartCleanupSnapshotDTO readded =
        new CartCleanupSnapshotDTO(
            7L, "{\"skuId\":7,\"quantity\":2,\"addedAt\":\"readded-during-reserve\"}");
    doAnswer(invocation -> ApiResponse.ok(current.get()))
        .when(fixture.cart)
        .cleanupSnapshot(7L, 8L);
    doAnswer(
            invocation -> {
              StockClient.Reservation reservation = invocation.getArgument(0);
              current.set(readded);
              return ApiResponse.ok(Map.of("orderNo", reservation.orderNo(), "status", "RESERVED"));
            })
        .when(fixture.stock)
        .reserve(any());
    doAnswer(
            invocation -> {
              CartCleanupSnapshotDTO expected = invocation.getArgument(2);
              return ApiResponse.ok(current.compareAndSet(expected, null));
            })
        .when(fixture.cart)
        .cleanupItem(eq(7L), eq(8L), any());
    assertEquals("0", fixture.service.create("changed-during-reserve", request()).code);
    for (TransactionSynchronization callback :
        TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
    assertEquals(readded, current.get());
    verify(fixture.cart).cleanupSnapshot(7L, 8L);
    verify(fixture.cart).cleanupItem(7L, 8L, snapshot(7L));
    verify(fixture.cart, never()).deleteItem(anyLong(), anyLong());
  }

  @Test
  void partiallyPurchasedCartQuantityIsNeverAuthorizedForWholeItemDeletion() {
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    doReturn(ApiResponse.ok(new CartCleanupSnapshotDTO(7L, "{\"skuId\":7,\"quantity\":5}")))
        .when(fixture.cart)
        .cleanupSnapshot(7L, 8L);
    assertEquals("0", fixture.service.create("only-two-of-five", request()).code);
    for (TransactionSynchronization callback :
        TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
    verify(fixture.cart, never()).cleanupItem(eq(7L), anyLong(), any());
    verify(fixture.cart, never()).deleteItem(anyLong(), anyLong());
  }

  @Test
  void samplingPrecedesAddressAndProductsAndNeverAuthorizesAPreviouslyMissingItem() {
    for (String phase : List.of("address", "product", "initial-missing")) {
      Fixture fixture = new Fixture();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.initSynchronization();
      var current =
          new java.util.concurrent.atomic.AtomicReference<CartCleanupSnapshotDTO>(
              "initial-missing".equals(phase) ? null : snapshot(7L));
      CartCleanupSnapshotDTO readded =
          new CartCleanupSnapshotDTO(
              7L, "{\"skuId\":7,\"quantity\":2,\"addedAt\":\"new-during-" + phase + "\"}");
      doAnswer(invocation -> ApiResponse.ok(current.get()))
          .when(fixture.cart)
          .cleanupSnapshot(7L, 8L);
      if ("address".equals(phase))
        doAnswer(
                invocation -> {
                  current.set(readded);
                  return ApiResponse.ok(List.of(Map.of("id", 9L)));
                })
            .when(fixture.users)
            .addresses();
      else if ("product".equals(phase))
        doAnswer(
                invocation -> {
                  current.set(readded);
                  return ApiResponse.ok(
                      new ProductClient.SkuView(7L, 9L, "商品", "SKU", BigDecimal.TEN, Map.of()));
                })
            .when(fixture.products)
            .sku(7L);
      else
        doAnswer(
                invocation -> {
                  current.set(readded);
                  StockClient.Reservation reservation = invocation.getArgument(0);
                  return ApiResponse.ok(
                      Map.of("orderNo", reservation.orderNo(), "status", "RESERVED"));
                })
            .when(fixture.stock)
            .reserve(any());
      doAnswer(
              invocation -> {
                CartCleanupSnapshotDTO expected = invocation.getArgument(2);
                return ApiResponse.ok(current.compareAndSet(expected, null));
              })
          .when(fixture.cart)
          .cleanupItem(eq(7L), eq(8L), any());
      assertEquals("0", fixture.service.create("early-" + phase, request()).code);
      var ordered = inOrder(fixture.cart, fixture.users, fixture.products, fixture.stock);
      ordered.verify(fixture.cart).cleanupSnapshot(7L, 8L);
      ordered.verify(fixture.cart).cleanupSnapshot(8L, 8L);
      ordered.verify(fixture.users).addresses();
      ordered.verify(fixture.products, times(2)).sku(7L);
      ordered.verify(fixture.products).sku(8L);
      ordered.verify(fixture.stock).reserve(any());
      for (TransactionSynchronization callback :
          TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
      assertEquals(readded, current.get());
      if ("initial-missing".equals(phase))
        verify(fixture.cart, never()).cleanupItem(eq(7L), anyLong(), any());
      else verify(fixture.cart).cleanupItem(7L, 8L, snapshot(7L));
      verify(fixture.cart).cleanupItem(8L, 8L, snapshot(8L));
      verify(fixture.cart, never()).deleteItem(anyLong(), anyLong());
      verifyNoMoreInteractions(fixture.cart);
      clearContext();
    }
  }

  @Test
  void invalidQuantityProofsAndOutOfRangePurchaseSumsOnlySkipCleanup() {
    for (String quantity :
        List.of(
            "missing",
            "null",
            "0",
            "-1",
            "-2147483648",
            "1",
            "5",
            "2147483648",
            "4294967298",
            "2.0",
            "\"2\"",
            "true",
            "[]")) {
      Fixture fixture = new Fixture();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.initSynchronization();
      String raw =
          "missing".equals(quantity)
              ? "{\"skuId\":7}"
              : "{\"skuId\":7,\"quantity\":" + quantity + "}";
      doReturn(ApiResponse.ok(new CartCleanupSnapshotDTO(7L, raw)))
          .when(fixture.cart)
          .cleanupSnapshot(7L, 8L);
      assertEquals("0", fixture.service.create("bad-quantity-" + quantity, request()).code);
      for (TransactionSynchronization callback :
          TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
      verify(fixture.cart).cleanupSnapshot(7L, 8L);
      verify(fixture.cart).cleanupSnapshot(8L, 8L);
      verify(fixture.cart, never()).cleanupItem(eq(7L), anyLong(), any());
      verify(fixture.cart).cleanupItem(8L, 8L, snapshot(8L));
      verify(fixture.cart, never()).deleteItem(anyLong(), anyLong());
      verifyNoMoreInteractions(fixture.cart);
      clearContext();
    }
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    CreateOrderDTO request = request();
    request.items.get(0).quantity = Integer.MAX_VALUE;
    assertEquals("0", fixture.service.create("purchase-sum-over-int", request).code);
    for (TransactionSynchronization callback :
        TransactionSynchronizationManager.getSynchronizations()) callback.afterCommit();
    verify(fixture.cart, never()).cleanupSnapshot(eq(7L), anyLong());
    verify(fixture.cart, never()).cleanupItem(eq(7L), anyLong(), any());
    verify(fixture.cart).cleanupItem(8L, 8L, snapshot(8L));
    var reservation = org.mockito.ArgumentCaptor.forClass(StockClient.Reservation.class);
    verify(fixture.stock).reserve(reservation.capture());
    assertEquals(Integer.MAX_VALUE, reservation.getValue().items().get(0).quantity());
    assertEquals(1, reservation.getValue().items().get(1).quantity());
    assertEquals(7L, reservation.getValue().items().get(0).skuId());
    assertEquals(7L, reservation.getValue().items().get(1).skuId());
  }

  @Test
  void invalidLaterRequestItemsAndIdempotentReplaysDoNotSampleCart() {
    for (String invalid : List.of("null-item", "null-sku", "quantity")) {
      Fixture fixture = new Fixture();
      TransactionSynchronizationManager.setActualTransactionActive(true);
      TransactionSynchronizationManager.initSynchronization();
      CreateOrderDTO request = request();
      if ("null-item".equals(invalid)) request.items.set(2, null);
      else if ("null-sku".equals(invalid)) request.items.get(2).skuId = null;
      else request.items.get(2).quantity = 0;
      assertEquals(
          400,
          assertThrows(
                  BizException.class,
                  () -> fixture.service.create("invalid-later-" + invalid, request))
              .getHttpStatus());
      verifyNoInteractions(fixture.cart, fixture.products, fixture.users, fixture.stock);
      clearContext();
    }
    Fixture fixture = new Fixture();
    TransactionSynchronizationManager.setActualTransactionActive(true);
    TransactionSynchronizationManager.initSynchronization();
    OrderIdempotencyPO existing = new OrderIdempotencyPO();
    existing.idempotencyKey = "existing-order";
    existing.orderNo = "202610" + "a".repeat(32);
    OrderSqlMapper orders =
        (OrderSqlMapper) ReflectionTestUtils.getField(fixture.service, "orderSqlMapper");
    doReturn(List.of(existing)).when(orders).findIdempotentOrders(8L, "existing-order");
    assertEquals("0", fixture.service.create("existing-order", request()).code);
    verifyNoInteractions(fixture.cart, fixture.products, fixture.users, fixture.stock);
    assertTrue(TransactionSynchronizationManager.getSynchronizations().isEmpty());
  }

  /** 不可变原始JSON证明，不从回调时期的购物车重新读取。 */
  private static CartCleanupSnapshotDTO snapshot(Long skuId) {
    return new CartCleanupSnapshotDTO(
        skuId,
        "{\"skuId\":"
            + skuId
            + ",\"quantity\":"
            + (skuId.equals(7L) ? 2 : 1)
            + ",\"checked\":true}");
  }

  /** 只捕获本例原Order logger，不构造生产日志helper或影响其它测试配置。 */
  private static final class CapturedOrderLogs implements AutoCloseable {
    /** 被测Service实际日志通道。 */
    private final Logger logger = (Logger) LoggerFactory.getLogger(OrderServiceImpl.class);

    /** 测试前原logger级别。 */
    private final Level originalLevel = logger.getLevel();

    /** 测试前父logger传播开关。 */
    private final boolean originalAdditivity = logger.isAdditive();

    /** 只在本测试内持有的日志事件。 */
    private final ListAppender<ILoggingEvent> events = new ListAppender<>();

    private CapturedOrderLogs() {
      events.setContext(logger.getLoggerContext());
      events.start();
      logger.addAppender(events);
      logger.setLevel(Level.WARN);
      logger.setAdditive(false);
    }

    private long snapshotWarnings() {
      return events.list.stream()
          .filter(event -> event.getLevel() == Level.WARN)
          .filter(event -> event.getFormattedMessage().startsWith("订单购物车前置快照捕获失败"))
          .count();
    }

    /** 精确解除本例appender并恢复原level/additivity，即使RED断言抛错也执行。 */
    @Override
    public void close() {
      logger.detachAppender(events);
      events.stop();
      logger.setLevel(originalLevel);
      logger.setAdditive(originalAdditivity);
    }
  }

  /** 实际创建编排，持久层和远端明确替身，不把人工同步上下文称作真实提交。 */
  private static class Fixture {
    /** 原购物车客户端替身。 */
    final CartClient cart = mock(CartClient.class);

    /** 原库存客户端替身。 */
    final StockClient stock = mock(StockClient.class);

    /** 商品事实调用的独立替身。 */
    final ProductClient products = mock(ProductClient.class);

    /** 地址事实调用的独立替身。 */
    final UserClient users = mock(UserClient.class);

    /** 实际创建与提交清理编排。 */
    final OrderServiceImpl service;

    private Fixture() {
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
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
      when(cart.cleanupSnapshot(anyLong(), anyLong()))
          .thenAnswer(invocation -> ApiResponse.ok(snapshot(invocation.getArgument(0))));
      when(cart.cleanupItem(anyLong(), anyLong(), any())).thenReturn(ApiResponse.ok(true));
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
