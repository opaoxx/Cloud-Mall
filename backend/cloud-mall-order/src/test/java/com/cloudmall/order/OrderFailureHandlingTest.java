package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.domain.dto.CreateOrderDTO;
import com.cloudmall.order.domain.dto.OrderItemDTO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.Request;
import feign.RetryableException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.amqp.core.Message;
import org.springframework.amqp.core.MessageProperties;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.util.ReflectionTestUtils;

class OrderFailureHandlingTest {
  @AfterEach
  void clearAuthentication() {
    AuthContext.clear();
  }

  @Test
  void invalidAddressDoesNotReserveStock() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient productClient = mock(ProductClient.class);
    UserClient userClient = mock(UserClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    when(orderSqlMapper.findIdempotentOrderNos(8L, "invalid-address")).thenReturn(List.of());
    when(productClient.sku(7L)).thenReturn(ApiResponse.ok(sku()));
    when(userClient.addresses()).thenReturn(ApiResponse.ok(List.of()));
    OrderServiceImpl orderService = service(stockClient, productClient, userClient, orderSqlMapper);

    AuthContext.set(8L, "USER");
    assertThrows(BizException.class, () -> orderService.create("invalid-address", orderRequest()));

    verifyNoInteractions(stockClient);
  }

  @Test
  void databaseFailureAfterReservationRollsBackTheSameOrder() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient productClient = mock(ProductClient.class);
    UserClient userClient = mock(UserClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    when(orderSqlMapper.findIdempotentOrderNos(8L, "database-failure")).thenReturn(List.of());
    when(productClient.sku(7L)).thenReturn(ApiResponse.ok(sku()));
    when(userClient.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
    doReturn(ApiResponse.ok(Map.of("reserved", true))).when(stockClient).reserve(any());
    doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stockClient).rollback(anyString());
    when(orderSqlMapper.insertOrder(anyString(), any(OrderPO.class)))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    OrderServiceImpl orderService = service(stockClient, productClient, userClient, orderSqlMapper);

    AuthContext.set(8L, "USER");
    assertThrows(RuntimeException.class, () -> orderService.create("database-failure", orderRequest()));

    ArgumentCaptor<StockClient.Reservation> reservationCaptor =
        ArgumentCaptor.forClass(StockClient.Reservation.class);
    verify(stockClient).reserve(reservationCaptor.capture());
    verify(stockClient).rollback(reservationCaptor.getValue().orderNo());
  }

  @Test
  void unknownReservationResultKeepsTheIntentForRecovery() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient productClient = mock(ProductClient.class);
    UserClient userClient = mock(UserClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    when(orderSqlMapper.findIdempotentOrderNos(8L, "unknown-result")).thenReturn(List.of());
    when(productClient.sku(7L)).thenReturn(ApiResponse.ok(sku()));
    when(userClient.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
    doThrow(new IllegalStateException("read timeout")).when(stockClient).reserve(any());
    OrderServiceImpl orderService = service(stockClient, productClient, userClient, orderSqlMapper);
    OrderReservationIntentService intentService =
        (OrderReservationIntentService)
            ReflectionTestUtils.getField(orderService, "reservationIntentService");

    AuthContext.set(8L, "USER");
    BizException unknown =
        assertThrows(BizException.class, () -> orderService.create("unknown-result", orderRequest()));

    assertEquals(202, unknown.getHttpStatus());
    ArgumentCaptor<com.cloudmall.order.domain.po.OrderReservationIntentPO> intentCaptor =
        ArgumentCaptor.forClass(com.cloudmall.order.domain.po.OrderReservationIntentPO.class);
    verify(intentService).createIntent(intentCaptor.capture());
    verify(intentService).markReserveUnknown(eq(intentCaptor.getValue().orderNo), anyString());
    verify(intentService, never()).markReleased(anyString());
    verify(stockClient, never()).rollback(anyString());
  }

  @Test
  void productReadTimeoutFailsClosedBeforeAnyReservationIntent() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient productClient = mock(ProductClient.class);
    UserClient userClient = mock(UserClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    when(orderSqlMapper.findIdempotentOrderNos(8L, "product-timeout")).thenReturn(List.of());
    when(userClient.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
    Request productRequest =
        Request.create(
            Request.HttpMethod.GET,
            "http://cloud-mall-product/api/products/skus/7",
            Map.of(),
            null,
            StandardCharsets.UTF_8);
    RetryableException remoteTimeout =
        new RetryableException(
            -1, "product read timeout", Request.HttpMethod.GET, (java.util.Date) null, productRequest);
    when(productClient.sku(7L)).thenThrow(remoteTimeout);
    OrderServiceImpl orderService = service(stockClient, productClient, userClient, orderSqlMapper);
    OrderReservationIntentService intentService =
        (OrderReservationIntentService)
            ReflectionTestUtils.getField(orderService, "reservationIntentService");

    AuthContext.set(8L, "USER");
    BizException failure =
        assertThrows(BizException.class, () -> orderService.create("product-timeout", orderRequest()));

    assertEquals(503, failure.getHttpStatus());
    verifyNoInteractions(stockClient);
    verify(intentService, never()).createIntent(any());
  }

  @Test
  void failedPaidTransitionDoesNotConfirmStock() {
    StockClient stockClient = mock(StockClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    String orderNo = "202609conflictpaid";
    pendingOrder(orderSqlMapper, orderNo);
    OrderServiceImpl orderService = service(stockClient, mock(ProductClient.class), mock(UserClient.class), orderSqlMapper);

    AuthContext.set(8L, "USER");
    assertThrows(BizException.class, () -> orderService.paid(orderNo));

    verify(orderSqlMapper).updateStatus(anyString(), eq(orderNo), eq("PAID"), eq("PENDING_PAYMENT"), any());
    verifyNoInteractions(stockClient);
  }

  @Test
  void paidTransitionPersistsConfirmIntentWithoutCallingStockInOrderTransaction() {
    StockClient stockClient = mock(StockClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    String orderNo = "202609paidintent";
    pendingOrder(orderSqlMapper, orderNo);
    when(orderSqlMapper.updateStatus(anyString(), eq(orderNo), eq("PAID"),
        eq("PENDING_PAYMENT"), any())).thenReturn(1);
    OrderServiceImpl orderService = service(stockClient, mock(ProductClient.class),
        mock(UserClient.class), orderSqlMapper);
    OrderStockActionMapper actionMapper =
        (OrderStockActionMapper) ReflectionTestUtils.getField(orderService, "orderStockActionMapper");

    AuthContext.set(8L, "USER");
    orderService.paid(orderNo);

    verify(actionMapper).insert(anyLong(), eq(orderNo), eq("CONFIRM"), any());
    verifyNoInteractions(stockClient);
  }

  @Test
  void timeoutTransitionPersistsRollbackIntentWithoutCallingStockInOrderTransaction() {
    StockClient stockClient = mock(StockClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    String orderNo = "202609timeoutintent";
    pendingOrder(orderSqlMapper, orderNo);
    when(orderSqlMapper.updateStatus(anyString(), eq(orderNo), eq("CANCELLED"),
        eq("PENDING_PAYMENT"), any())).thenReturn(1);
    OrderServiceImpl orderService = service(stockClient, mock(ProductClient.class),
        mock(UserClient.class), orderSqlMapper);
    OrderStockActionMapper actionMapper =
        (OrderStockActionMapper) ReflectionTestUtils.getField(orderService, "orderStockActionMapper");

    orderService.consumeTimeout(orderNo);

    verify(actionMapper).insert(anyLong(), eq(orderNo), eq("ROLLBACK"), any());
    verifyNoInteractions(stockClient);
  }

  @Test
  void failedTimeoutTransitionDoesNotRollbackStock() {
    StockClient stockClient = mock(StockClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    String orderNo = "202609conflicttimeout";
    pendingOrder(orderSqlMapper, orderNo);
    OrderServiceImpl orderService = service(stockClient, mock(ProductClient.class), mock(UserClient.class), orderSqlMapper);

    orderService.consumeTimeout(orderNo);

    verify(orderSqlMapper).updateStatus(anyString(), eq(orderNo), eq("CANCELLED"), eq("PENDING_PAYMENT"), any());
    verifyNoInteractions(stockClient);
  }

  @Test
  void timeoutEnvelopeAndLegacyMessageShareTheSameCasGuard() {
    StockClient stockClient = mock(StockClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    String orderNo = "202609conflictmessage";
    pendingOrder(orderSqlMapper, orderNo);
    OrderServiceImpl orderService =
        service(stockClient, mock(ProductClient.class), mock(UserClient.class), orderSqlMapper);
    Message envelope =
        message(
            "{\"eventId\":\"timeout-1\",\"eventType\":\"ORDER_TIMEOUT\","
                + "\"orderNo\":\""
                + orderNo
                + "\",\"expireAt\":\"2026-09-01T00:00:00+08:00\"}");
    Message legacy = message("\"" + orderNo + "\"");

    orderService.consumeTimeout(envelope);
    orderService.consumeTimeout(legacy);

    verify(orderSqlMapper, org.mockito.Mockito.times(2))
        .updateStatus(anyString(), eq(orderNo), eq("CANCELLED"), eq("PENDING_PAYMENT"), any());
    verifyNoInteractions(stockClient);
  }

  @Test
  void paymentStateIsReadOnlyAndScopedToTheOrderOwner() {
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    String orderNo = "202609paymentstate";
    pendingOrder(orderSqlMapper, orderNo);
    OrderServiceImpl orderService =
        service(
            mock(StockClient.class),
            mock(ProductClient.class),
            mock(UserClient.class),
            orderSqlMapper);

    ApiResponse<?> response = orderService.paymentState(orderNo, 8L);

    assertEquals("PENDING_PAYMENT", ((Map<?, ?>) response.data).get("status"));
    assertEquals(new BigDecimal("12.30"), ((Map<?, ?>) response.data).get("payAmount"));
    assertThrows(BizException.class, () -> orderService.paymentState(orderNo, 9L));
    verify(orderSqlMapper, never()).updateStatus(anyString(), anyString(), anyString(), anyString(), any());
  }

  @Test
  void legacyCancelledOrderWithConfirmedStockCannotAuthorizeRefund() {
    String orderNo = "202609legacyconflict";
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    StockClient stockClient = mock(StockClient.class);
    orderWithStatus(orderSqlMapper, orderNo, "CANCELLED");
    StockReservationStatusVO stockStatus = new StockReservationStatusVO();
    stockStatus.status = "CONFIRMED";
    when(stockClient.reservation(orderNo)).thenReturn(ApiResponse.ok(stockStatus));
    OrderServiceImpl orderService =
        service(stockClient, mock(ProductClient.class), mock(UserClient.class), orderSqlMapper);

    ApiResponse<?> response = orderService.paymentState(orderNo, 8L);

    assertEquals("CONFLICT", ((Map<?, ?>) response.data).get("stockActionStatus"));
  }

  @Test
  void legacyPaidOrderNeedsConfirmedStockBeforeReportingSettlement() {
    String orderNo = "202609legacypaid";
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    StockClient stockClient = mock(StockClient.class);
    orderWithStatus(orderSqlMapper, orderNo, "PAID");
    StockReservationStatusVO stockStatus = new StockReservationStatusVO();
    stockStatus.status = "CONFIRMED";
    when(stockClient.reservation(orderNo)).thenReturn(ApiResponse.ok(stockStatus));
    OrderServiceImpl orderService =
        service(stockClient, mock(ProductClient.class), mock(UserClient.class), orderSqlMapper);

    ApiResponse<?> response = orderService.paymentState(orderNo, 8L);

    assertEquals("SUCCEEDED", ((Map<?, ?>) response.data).get("stockActionStatus"));
  }

  private static Message message(String payload) {
    return new Message(payload.getBytes(StandardCharsets.UTF_8), new MessageProperties());
  }

  private static OrderServiceImpl service(
      StockClient stockClient,
      ProductClient productClient,
      UserClient userClient,
      OrderSqlMapper orderSqlMapper) {
    OrderServiceImpl orderService = new OrderServiceImpl(
        stockClient,
        productClient,
        userClient,
        mock(CartClient.class),
        orderSqlMapper,
        new ObjectMapper());
    OrderReservationIntentService intentService = mock(OrderReservationIntentService.class);
    when(intentService.createIntent(any())).thenReturn(true);
    ReflectionTestUtils.setField(orderService, "reservationIntentService", intentService);
    ReflectionTestUtils.setField(orderService, "reservationIntentMapper", mock(OrderReservationIntentMapper.class));
    ReflectionTestUtils.setField(orderService, "orderOutboxMapper", mock(OrderOutboxMapper.class));
    ReflectionTestUtils.setField(orderService, "orderStockActionMapper", mock(OrderStockActionMapper.class));
    return orderService;
  }

  private static ProductClient.SkuView sku() {
    return new ProductClient.SkuView(
        7L, 10L, "测试商品", "SKU-7", new BigDecimal("12.30"), Map.of());
  }

  private static CreateOrderDTO orderRequest() {
    OrderItemDTO orderItem = new OrderItemDTO();
    orderItem.skuId = 7L;
    orderItem.quantity = 1;
    CreateOrderDTO request = new CreateOrderDTO();
    request.addressId = 9L;
    request.items = List.of(orderItem);
    return request;
  }

  private static void pendingOrder(OrderSqlMapper orderSqlMapper, String orderNo) {
    orderWithStatus(orderSqlMapper, orderNo, "PENDING_PAYMENT");
  }

  private static void orderWithStatus(
      OrderSqlMapper orderSqlMapper, String orderNo, String status) {
    when(orderSqlMapper.findOrder(anyString(), eq(orderNo)))
        .thenReturn(
            List.of(
                Map.of(
                    "order_no", orderNo,
                    "user_id", 8L,
                    "status", status,
                    "total_amount", new BigDecimal("12.30"),
                    "pay_amount", new BigDecimal("12.30"),
                    "address_snapshot", "{}")));
    when(orderSqlMapper.findOrderItems(anyString(), eq(orderNo))).thenReturn(List.of());
  }
}
