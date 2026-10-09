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

import com.alibaba.csp.sentinel.slots.block.RuleConstant;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRule;
import com.alibaba.csp.sentinel.slots.block.degrade.DegradeRuleManager;
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
import com.cloudmall.order.service.impl.OrderReservationIntentService;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.cloudmall.order.service.impl.OrderStockCircuitBreaker;
import com.cloudmall.order.service.impl.StockCircuitOpenException;
import com.fasterxml.jackson.databind.ObjectMapper;
import feign.FeignException;
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
    DegradeRuleManager.loadRules(List.of());
  }

  @Test
  void openReserveCircuitKeepsTheCommittedIntentForRecovery() {
    DegradeRule slowRule = new DegradeRule(OrderStockCircuitBreaker.RESERVE)
        .setGrade(RuleConstant.DEGRADE_GRADE_RT).setCount(10)
        .setSlowRatioThreshold(0.5).setMinRequestAmount(2)
        .setStatIntervalMs(1000).setTimeWindow(2);
    DegradeRuleManager.loadRules(List.of(slowRule));
    boolean opened = false;
    for (int attempt = 0; attempt < 8 && !opened; attempt++) {
      try {
        OrderStockCircuitBreaker.call(OrderStockCircuitBreaker.RESERVE, () -> {
          try {
            Thread.sleep(30L);
          } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
          }
          return ApiResponse.ok("slow-stock");
        });
      } catch (StockCircuitOpenException blocked) {
        opened = true;
      }
    }
    org.junit.jupiter.api.Assertions.assertTrue(opened);
    StockClient stockClient = mock(StockClient.class);
    ProductClient productClient = mock(ProductClient.class);
    UserClient userClient = mock(UserClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    when(orderSqlMapper.findIdempotentOrders(8L, "circuit-open")).thenReturn(List.of());
    when(productClient.sku(7L)).thenReturn(ApiResponse.ok(sku()));
    when(userClient.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
    OrderServiceImpl orderService = service(stockClient, productClient, userClient, orderSqlMapper);
    OrderReservationIntentService intentService =
        (OrderReservationIntentService) ReflectionTestUtils.getField(
            orderService, "reservationIntentService");

    AuthContext.set(8L, "USER");
    BizException result = assertThrows(BizException.class,
        () -> orderService.create("circuit-open", orderRequest()));

    assertEquals("ORDER_RESERVATION_UNKNOWN", result.getCode());
    assertEquals(202, result.getHttpStatus());
    verify(intentService).createIntent(any());
    verify(intentService).markReserveUnknown(anyString(), anyString());
    verify(intentService, never()).markReleased(anyString());
    verifyNoInteractions(stockClient);
  }

  @Test
  void ambiguous409PreservesRemoteEvidenceWithoutReleasingOrContinuingCreation() {
    for (String code :
        List.of(
            "COMMON_CONFLICT",
            "ORDER_DUPLICATE_REQUEST",
            "ORDER_RESERVATION_KEY_REUSED",
            "ORDER_STOCK_NOT_ENOUGH")) {
      StockClient stockClient = mock(StockClient.class);
      ProductClient productClient = mock(ProductClient.class);
      UserClient userClient = mock(UserClient.class);
      OrderSqlMapper orders = mock(OrderSqlMapper.class);
      when(productClient.sku(7L)).thenReturn(ApiResponse.ok(sku()));
      when(userClient.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
      FeignException remoteFailure = mock(FeignException.class);
      when(remoteFailure.status()).thenReturn(409);
      when(remoteFailure.contentUTF8()).thenReturn("{\"code\":\"" + code + "\"}");
      doThrow(remoteFailure).when(stockClient).reserve(any());
      OrderServiceImpl target = service(stockClient, productClient, userClient, orders);
      OrderReservationIntentService intents =
          (OrderReservationIntentService)
              ReflectionTestUtils.getField(target, "reservationIntentService");
      AuthContext.set(8L, "USER");
      BizException returned =
          assertThrows(BizException.class, () -> target.create("ambiguous-409", orderRequest()));
      assertEquals(202, returned.getHttpStatus());
      assertEquals("ORDER_RESERVATION_UNKNOWN", returned.getCode());
      assertEquals(1, returned.getSuppressed().length);
      org.junit.jupiter.api.Assertions.assertSame(remoteFailure, returned.getSuppressed()[0]);
      verify(stockClient).reserve(any());
      verify(stockClient, never()).reservation(anyString());
      verify(stockClient, never()).rollback(anyString());
      verify(intents).markReserveUnknown(anyString(), anyString());
      verify(intents, never()).markReleased(anyString());
      verify(intents, never()).markReserved(anyString());
      verify(orders, never()).insertOrder(anyString(), any(OrderPO.class));
    }
  }

  @Test
  void failureRecordingAnUnknownConflictDoesNotHideItsOriginal202AndCause() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient products = mock(ProductClient.class);
    UserClient users = mock(UserClient.class);
    OrderSqlMapper orders = mock(OrderSqlMapper.class);
    when(products.sku(7L)).thenReturn(ApiResponse.ok(sku()));
    when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
    FeignException remoteFailure = mock(FeignException.class);
    when(remoteFailure.status()).thenReturn(409);
    when(remoteFailure.contentUTF8()).thenReturn("{\"code\":\"COMMON_CONFLICT\"}");
    doThrow(remoteFailure).when(stockClient).reserve(any());
    OrderServiceImpl target = service(stockClient, products, users, orders);
    OrderReservationIntentService intents =
        (OrderReservationIntentService)
            ReflectionTestUtils.getField(target, "reservationIntentService");
    DataAccessResourceFailureException recordingFailure =
        new DataAccessResourceFailureException("intent write unavailable");
    doThrow(recordingFailure).when(intents).markReserveUnknown(anyString(), anyString());
    AuthContext.set(8L, "USER");
    BizException returned =
        assertThrows(
            BizException.class, () -> target.create("conflict-record-failure", orderRequest()));
    assertEquals(202, returned.getHttpStatus());
    assertEquals("ORDER_RESERVATION_UNKNOWN", returned.getCode());
    assertEquals(2, returned.getSuppressed().length);
    org.junit.jupiter.api.Assertions.assertSame(remoteFailure, returned.getSuppressed()[0]);
    org.junit.jupiter.api.Assertions.assertSame(recordingFailure, returned.getSuppressed()[1]);
    verify(intents).markReserveUnknown(anyString(), anyString());
    verify(intents, never()).markReleased(anyString());
    verify(intents, never()).markReserved(anyString());
    verify(stockClient).reserve(any());
    verify(stockClient, never()).reservation(anyString());
    verify(stockClient, never()).rollback(anyString());
    verify(orders, never()).insertOrder(anyString(), any(OrderPO.class));
  }

  @Test
  void reservationProofConversionFailureKeeps202AndItsOriginalEvidence() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient products = mock(ProductClient.class);
    UserClient users = mock(UserClient.class);
    OrderSqlMapper orders = mock(OrderSqlMapper.class);
    when(products.sku(7L)).thenReturn(ApiResponse.ok(sku()));
    when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              StockClient.Reservation request = invocation.getArgument(0);
              return ApiResponse.ok(Map.of("orderNo", request.orderNo(), "status", "RESERVED"));
            })
        .when(stockClient)
        .reserve(any());
    OrderServiceImpl target = service(stockClient, products, users, orders);
    OrderReservationIntentService intents =
        (OrderReservationIntentService)
            ReflectionTestUtils.getField(target, "reservationIntentService");
    ObjectMapper proofMapper = org.mockito.Mockito.spy(new ObjectMapper());
    IllegalArgumentException malformed =
        new IllegalArgumentException("proof cannot be represented as JSON");
    doThrow(malformed).when(proofMapper).valueToTree(any());
    ReflectionTestUtils.setField(target, "mapper", proofMapper);
    AuthContext.set(8L, "USER");
    BizException returned =
        assertThrows(
            BizException.class, () -> target.create("proof-conversion-failure", orderRequest()));
    assertEquals(202, returned.getHttpStatus());
    assertEquals("ORDER_RESERVATION_UNKNOWN", returned.getCode());
    assertEquals(1, returned.getSuppressed().length);
    org.junit.jupiter.api.Assertions.assertSame(malformed, returned.getSuppressed()[0]);
    verify(intents).markReserveUnknown(anyString(), anyString());
    verify(intents, never()).markReserved(anyString());
    verify(intents, never()).markReleased(anyString());
    verify(stockClient).reserve(any());
    verify(stockClient, never()).reservation(anyString());
    verify(stockClient, never()).rollback(anyString());
    verify(orders, never()).insertOrder(anyString(), any(OrderPO.class));
  }

  @Test
  void reservationProofDoesNotSwallowFatalErrorsOrGuessACompensation() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient products = mock(ProductClient.class);
    UserClient users = mock(UserClient.class);
    OrderSqlMapper orders = mock(OrderSqlMapper.class);
    when(products.sku(7L)).thenReturn(ApiResponse.ok(sku()));
    when(users.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              StockClient.Reservation request = invocation.getArgument(0);
              return ApiResponse.ok(Map.of("orderNo", request.orderNo(), "status", "RESERVED"));
            })
        .when(stockClient)
        .reserve(any());
    OrderServiceImpl target = service(stockClient, products, users, orders);
    OrderReservationIntentService intents =
        (OrderReservationIntentService)
            ReflectionTestUtils.getField(target, "reservationIntentService");
    ObjectMapper proofMapper = org.mockito.Mockito.spy(new ObjectMapper());
    AssertionError fatal = new AssertionError("fatal proof adapter error");
    doThrow(fatal).when(proofMapper).valueToTree(any());
    ReflectionTestUtils.setField(target, "mapper", proofMapper);
    AuthContext.set(8L, "USER");
    org.junit.jupiter.api.Assertions.assertSame(
        fatal,
        assertThrows(
            AssertionError.class, () -> target.create("fatal-proof-error", orderRequest())));
    verify(intents, never()).markReserveUnknown(anyString(), anyString());
    verify(intents, never()).markReserved(anyString());
    verify(intents, never()).markReleased(anyString());
    verify(stockClient).reserve(any());
    verify(stockClient, never()).reservation(anyString());
    verify(stockClient, never()).rollback(anyString());
    verify(orders, never()).insertOrder(anyString(), any(OrderPO.class));
  }

  @Test
  void malformedStock409CannotReleaseAnUnknownReservationIntent() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient productClient = mock(ProductClient.class);
    UserClient userClient = mock(UserClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    when(orderSqlMapper.findIdempotentOrders(8L, "malformed-409")).thenReturn(List.of());
    when(productClient.sku(7L)).thenReturn(ApiResponse.ok(sku()));
    when(userClient.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
    FeignException malformed = mock(FeignException.class);
    when(malformed.status()).thenReturn(409);
    when(malformed.contentUTF8()).thenReturn("not-json");
    doThrow(malformed).when(stockClient).reserve(any());
    OrderServiceImpl orderService = service(stockClient, productClient, userClient, orderSqlMapper);
    OrderReservationIntentService intentService =
        (OrderReservationIntentService)
            ReflectionTestUtils.getField(orderService, "reservationIntentService");

    AuthContext.set(8L, "USER");
    BizException result =
        assertThrows(
            BizException.class, () -> orderService.create("malformed-409", orderRequest()));

    assertEquals("ORDER_RESERVATION_UNKNOWN", result.getCode());
    assertEquals(202, result.getHttpStatus());
    verify(intentService).markReserveUnknown(anyString(), anyString());
    verify(intentService, never()).markReleased(anyString());
    verify(stockClient, never()).rollback(anyString());
  }

  @Test
  void invalidAddressDoesNotReserveStock() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient productClient = mock(ProductClient.class);
    UserClient userClient = mock(UserClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    when(orderSqlMapper.findIdempotentOrders(8L, "invalid-address")).thenReturn(List.of());
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
    when(orderSqlMapper.findIdempotentOrders(8L, "database-failure")).thenReturn(List.of());
    when(productClient.sku(7L)).thenReturn(ApiResponse.ok(sku()));
    when(userClient.addresses()).thenReturn(ApiResponse.ok(List.of(Map.of("id", 9L))));
    org.mockito.Mockito.doAnswer(
            invocation -> {
              StockClient.Reservation request = invocation.getArgument(0);
              return ApiResponse.ok(Map.of("orderNo", request.orderNo(), "status", "RESERVED"));
            })
        .when(stockClient)
        .reserve(any());
    doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stockClient).rollback(anyString());
    when(stockClient.reservation(anyString()))
        .thenAnswer(invocation -> legacyStockFact(invocation.getArgument(0), "ROLLED_BACK"));
    when(orderSqlMapper.insertOrder(anyString(), any(OrderPO.class)))
        .thenThrow(new DataAccessResourceFailureException("database unavailable"));
    OrderServiceImpl orderService = service(stockClient, productClient, userClient, orderSqlMapper);

    AuthContext.set(8L, "USER");
    assertThrows(
        RuntimeException.class, () -> orderService.create("database-failure", orderRequest()));

    ArgumentCaptor<StockClient.Reservation> reservationCaptor =
        ArgumentCaptor.forClass(StockClient.Reservation.class);
    verify(stockClient).reserve(reservationCaptor.capture());
    verify(stockClient).rollback(reservationCaptor.getValue().orderNo());
    verify(stockClient).reservation(reservationCaptor.getValue().orderNo());
    OrderReservationIntentService intentService =
        (OrderReservationIntentService)
            ReflectionTestUtils.getField(orderService, "reservationIntentService");
    verify(intentService).markReleased(reservationCaptor.getValue().orderNo());
    verify(intentService, never()).recordFailure(anyString(), anyString());
  }

  @Test
  void unknownReservationResultKeepsTheIntentForRecovery() {
    StockClient stockClient = mock(StockClient.class);
    ProductClient productClient = mock(ProductClient.class);
    UserClient userClient = mock(UserClient.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    when(orderSqlMapper.findIdempotentOrders(8L, "unknown-result")).thenReturn(List.of());
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
    when(orderSqlMapper.findIdempotentOrders(8L, "product-timeout")).thenReturn(List.of());
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
    stockStatus.orderNo = orderNo;
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
    stockStatus.orderNo = orderNo;
    stockStatus.status = "CONFIRMED";
    when(stockClient.reservation(orderNo)).thenReturn(ApiResponse.ok(stockStatus));
    OrderServiceImpl orderService =
        service(stockClient, mock(ProductClient.class), mock(UserClient.class), orderSqlMapper);

    ApiResponse<?> response = orderService.paymentState(orderNo, 8L);

    assertEquals("SUCCEEDED", ((Map<?, ?>) response.data).get("stockActionStatus"));
  }

  @Test
  void wrongLegacyStockIdentitiesNeverAuthorizeCompletionOrConflict() {
    String orderNo = "202609legacyidentity";
    for (String orderStatus : List.of("PAID", "COMPLETED", "CANCELLED")) {
      for (String stockStatus :
          List.of("CONFIRMED", "ROLLED_BACK", "REJECTED", "RESERVED", "INIT", "NOT_FOUND")) {
        for (String responseOrderNo :
            java.util.Arrays.asList(
                null,
                "",
                " \t",
                "202609other",
                "202609LEGACYIDENTITY",
                "202609légacyidentity",
                orderNo + " ")) {
          OrderSqlMapper sqlMapper = mock(OrderSqlMapper.class);
          orderWithStatus(sqlMapper, orderNo, orderStatus);
          StockClient stockClient = mock(StockClient.class);
          when(stockClient.reservation(orderNo))
              .thenReturn(legacyStockFact(responseOrderNo, stockStatus));
          OrderServiceImpl service =
              service(stockClient, mock(ProductClient.class), mock(UserClient.class), sqlMapper);

          ApiResponse<?> response = service.paymentState(orderNo, 8L);

          Map<?, ?> state = (Map<?, ?>) response.data;
          assertEquals(
              "UNKNOWN",
              state.get("stockActionStatus"),
              orderStatus + stockStatus + responseOrderNo);
          assertEquals(orderNo, state.get("orderNo"));
          assertEquals(orderStatus, state.get("status"));
          assertEquals(8L, state.get("userId"));
          assertEquals(new BigDecimal("12.30"), state.get("payAmount"));
          verify(stockClient).reservation(orderNo);
          verify(stockClient, never()).confirm(anyString(), any());
          verify(stockClient, never()).rollback(anyString(), any());
          verify(sqlMapper, never())
              .updateStatus(anyString(), anyString(), anyString(), anyString(), any());
        }
      }
    }
  }

  @Test
  void matchingLegacyStockIdentitiesKeepTheOriginalStateMatrix() {
    String orderNo = "202609legacymatrix";
    for (String orderStatus : List.of("PAID", "COMPLETED", "CANCELLED")) {
      String expected = "CANCELLED".equals(orderStatus) ? "ROLLED_BACK" : "CONFIRMED";
      String opposite = "CANCELLED".equals(orderStatus) ? "CONFIRMED" : "ROLLED_BACK";
      for (String stockStatus :
          List.of("CONFIRMED", "ROLLED_BACK", "REJECTED", "RESERVED", "INIT", "NOT_FOUND")) {
        OrderSqlMapper sqlMapper = mock(OrderSqlMapper.class);
        orderWithStatus(sqlMapper, orderNo, orderStatus);
        StockClient stockClient = mock(StockClient.class);
        when(stockClient.reservation(orderNo)).thenReturn(legacyStockFact(orderNo, stockStatus));
        OrderServiceImpl service =
            service(stockClient, mock(ProductClient.class), mock(UserClient.class), sqlMapper);
        String actionStatus =
            expected.equals(stockStatus)
                ? "SUCCEEDED"
                : (opposite.equals(stockStatus) || "REJECTED".equals(stockStatus))
                    ? "CONFLICT"
                    : "UNKNOWN";
        assertEquals(
            actionStatus,
            ((Map<?, ?>) service.paymentState(orderNo, 8L).data).get("stockActionStatus"));
        verify(stockClient).reservation(orderNo);
        verify(stockClient, never()).confirm(anyString(), any());
        verify(stockClient, never()).rollback(anyString(), any());
      }
    }
  }

  @Test
  void incompleteOrFailedLegacyStockRepliesStillRemainUnknown() {
    String orderNo = "202609legacyunknown";
    for (String phase : List.of("NULL_RESPONSE", "NULL_DATA", "ERROR_CODE", "EXCEPTION")) {
      OrderSqlMapper sqlMapper = mock(OrderSqlMapper.class);
      orderWithStatus(sqlMapper, orderNo, "PAID");
      StockClient stockClient = mock(StockClient.class);
      if ("NULL_DATA".equals(phase))
        when(stockClient.reservation(orderNo)).thenReturn(ApiResponse.ok(null));
      else if ("ERROR_CODE".equals(phase)) {
        var rejected = legacyStockFact(orderNo, "CONFIRMED");
        rejected.code = "500";
        when(stockClient.reservation(orderNo)).thenReturn(rejected);
      } else if ("EXCEPTION".equals(phase))
        doThrow(new IllegalStateException("Stock unknown")).when(stockClient).reservation(orderNo);
      OrderServiceImpl service =
          service(stockClient, mock(ProductClient.class), mock(UserClient.class), sqlMapper);
      assertEquals(
          "UNKNOWN", ((Map<?, ?>) service.paymentState(orderNo, 8L).data).get("stockActionStatus"));
      verify(stockClient).reservation(orderNo);
      verify(stockClient, never()).confirm(anyString(), any());
      verify(stockClient, never()).rollback(anyString(), any());
    }
  }

  @Test
  void malformedOrderCandidatesAreRejectedBeforeDetailsOrAnyAction() {
    String requestedOrderNo = "202609LOOKUP";
    for (Object storedOrderNo : java.util.Arrays.asList("202609lookup", "202609other", null, 42L)) {
      for (String endpoint :
          List.of("GET", "PAYMENT_STATE", "CANCEL", "PAID", "CONFIRM", "TIMEOUT")) {
        OrderSqlMapper sqlMapper = mock(OrderSqlMapper.class);
        Map<String, Object> row =
            new java.util.HashMap<>(
                Map.of(
                    "user_id",
                    8L,
                    "status",
                    "CONFIRM".equals(endpoint) ? "PAID" : "PENDING_PAYMENT",
                    "total_amount",
                    new BigDecimal("12.30"),
                    "pay_amount",
                    new BigDecimal("12.30"),
                    "address_snapshot",
                    "{}"));
        row.put("order_no", storedOrderNo);
        when(sqlMapper.findOrder(anyString(), eq(requestedOrderNo))).thenReturn(List.of(row));
        StockClient stockClient = mock(StockClient.class);
        OrderServiceImpl target =
            service(stockClient, mock(ProductClient.class), mock(UserClient.class), sqlMapper);
        AuthContext.set(8L, "USER");
        BizException failure =
            assertThrows(
                BizException.class,
                () -> {
                  switch (endpoint) {
                    case "GET" -> target.get(requestedOrderNo);
                    case "PAYMENT_STATE" -> target.paymentState(requestedOrderNo, 8L);
                    case "CANCEL" -> target.cancel(requestedOrderNo, 8L);
                    case "PAID" -> target.paid(requestedOrderNo);
                    case "CONFIRM" -> target.confirm(requestedOrderNo);
                    case "TIMEOUT" -> target.consumeTimeout(requestedOrderNo);
                    default -> throw new AssertionError("unknown endpoint");
                  }
                });
        assertEquals(404, failure.getHttpStatus());
        verify(sqlMapper, never()).findOrderItems(anyString(), anyString());
        verify(sqlMapper, never())
            .updateStatus(anyString(), anyString(), anyString(), anyString(), any());
        OrderStockActionMapper actions =
            (OrderStockActionMapper) ReflectionTestUtils.getField(target, "orderStockActionMapper");
        verifyNoInteractions(actions, stockClient);
      }
    }
  }

  @Test
  void missingOrderAndDatabaseLookupErrorsKeepTheirOriginalMeaning() {
    OrderSqlMapper sqlMapper = mock(OrderSqlMapper.class);
    StockClient stockClient = mock(StockClient.class);
    OrderServiceImpl target =
        service(stockClient, mock(ProductClient.class), mock(UserClient.class), sqlMapper);
    String orderNo = "202609missing";
    assertEquals(404, assertThrows(BizException.class, () -> target.get(orderNo)).getHttpStatus());
    var unavailable = new DataAccessResourceFailureException("order database unavailable");
    when(sqlMapper.findOrder(anyString(), eq(orderNo))).thenThrow(unavailable);
    org.junit.jupiter.api.Assertions.assertSame(
        unavailable,
        assertThrows(DataAccessResourceFailureException.class, () -> target.get(orderNo)));
    verify(sqlMapper, never()).findOrderItems(anyString(), anyString());
    verifyNoInteractions(stockClient);
  }

  @Test
  void unrecognizedLegacyOrderStatesNeverQueryStockOrClaimSettlement() {
    String orderNo = "202609legacybadstatus";
    for (String orderStatus :
        java.util.Arrays.asList(
            null,
            "",
            " \t",
            "paid",
            "completed",
            "cancelled",
            "PAID ",
            "COMPLETED ",
            "CANCELLED ",
            "PENDING_PAYMENT ",
            "PÁID",
            "OTHER",
            "CLOSED",
            "null")) {
      for (String stockStatus : List.of("CONFIRMED", "ROLLED_BACK", "REJECTED", "RESERVED")) {
        OrderSqlMapper sqlMapper = mock(OrderSqlMapper.class);
        Map<String, Object> row =
            new java.util.HashMap<>(
                Map.of(
                    "order_no",
                    orderNo,
                    "user_id",
                    8L,
                    "total_amount",
                    new BigDecimal("12.30"),
                    "pay_amount",
                    new BigDecimal("12.30"),
                    "address_snapshot",
                    "{}"));
        row.put("status", orderStatus);
        when(sqlMapper.findOrder(anyString(), eq(orderNo))).thenReturn(List.of(row));
        StockClient stockClient = mock(StockClient.class);
        when(stockClient.reservation(orderNo)).thenReturn(legacyStockFact(orderNo, stockStatus));
        OrderServiceImpl target =
            service(stockClient, mock(ProductClient.class), mock(UserClient.class), sqlMapper);
        Map<?, ?> state = (Map<?, ?>) target.paymentState(orderNo, 8L).data;
        assertEquals("UNKNOWN", state.get("stockActionStatus"));
        assertEquals(String.valueOf(orderStatus), state.get("status"));
        assertEquals(orderNo, state.get("orderNo"));
        verifyNoInteractions(stockClient);
        verify(sqlMapper, never())
            .updateStatus(anyString(), anyString(), anyString(), anyString(), any());
      }
    }
  }

  /** 保留原Stock响应身份，不根据请求路径补全缺失或错单字段。 */
  private static ApiResponse<StockReservationStatusVO> legacyStockFact(
      String orderNo, String stockStatus) {
    StockReservationStatusVO fact = new StockReservationStatusVO();
    fact.orderNo = orderNo;
    fact.status = stockStatus;
    fact.items = List.of();
    return ApiResponse.ok(fact);
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
    // 合法待支付超时fixture必须有可证明已过期的DB期限；缺失期限由专门真实SQLIT保护。
    when(orderSqlMapper.findOrder(anyString(), eq(orderNo)))
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
                    new BigDecimal("12.30"),
                    "pay_amount",
                    new BigDecimal("12.30"),
                    "address_snapshot",
                    "{}",
                    "expire_at",
                    java.time.LocalDateTime.of(2000, 1, 1, 0, 0))));
    when(orderSqlMapper.findOrderItems(anyString(), eq(orderNo))).thenReturn(List.of());
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
