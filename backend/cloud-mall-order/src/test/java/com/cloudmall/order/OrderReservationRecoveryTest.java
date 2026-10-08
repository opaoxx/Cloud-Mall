package com.cloudmall.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderReservationRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OrderReservationRecoveryTest {
  @Test
  void queriesTheOriginalReservationThenRollsItBackWhenNoOrderCommitted() {
    StockClient stockClient = mock(StockClient.class);
    OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    OrderReservationIntentPO intent = intent("202609recover1");
    when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(intent));
    when(intentMapper.claimRecovery(eq(intent.orderNo), any(), any())).thenReturn(1);
    when(orderSqlMapper.orderExists("mall_order_202609", intent.orderNo)).thenReturn(false);
    doReturn(ApiResponse.ok(stockStatus(intent.orderNo, "RESERVED")))
        .when(stockClient)
        .reservation(intent.orderNo);
    doReturn(ApiResponse.ok(java.util.Map.of("rolledBack", true)))
        .when(stockClient)
        .rollback(intent.orderNo);
    OrderReservationRecovery recovery =
        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper());

    recovery.recoverStaleReservations();

    verify(stockClient).reservation(intent.orderNo);
    verify(stockClient, never()).reserve(any());
    verify(stockClient).rollback(intent.orderNo);
    verify(intentMapper).markReleased(eq(intent.orderNo), any(LocalDateTime.class));
  }

  @Test
  void doesNotReleaseStockWhenTheOrderAlreadyCommitted() {
    StockClient stockClient = mock(StockClient.class);
    OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    OrderReservationIntentPO intent = intent("202609recover2");
    when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(intent));
    when(intentMapper.claimRecovery(eq(intent.orderNo), any(), any())).thenReturn(1);
    when(orderSqlMapper.orderExists("mall_order_202609", intent.orderNo)).thenReturn(true);
    OrderReservationRecovery recovery =
        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper());

    recovery.recoverStaleReservations();

    verify(stockClient, never()).reserve(any());
    verify(stockClient, never()).rollback(anyString());
    verify(intentMapper).forceCommitted(eq(intent.orderNo), any(LocalDateTime.class));
  }

  @Test
  void previouslyRolledBackReservationCanFinishIntentRecovery() {
    StockClient stockClient = mock(StockClient.class);
    OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    OrderReservationIntentPO intent = intent("202609recover3");
    when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(intent));
    when(intentMapper.claimRecovery(eq(intent.orderNo), any(), any())).thenReturn(1);
    doReturn(ApiResponse.ok(stockStatus(intent.orderNo, "ROLLED_BACK")))
        .when(stockClient)
        .reservation(intent.orderNo);
    OrderReservationRecovery recovery =
        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper());

    recovery.recoverStaleReservations();

    verify(stockClient, never()).rollback(anyString());
    verify(intentMapper).markReleased(eq(intent.orderNo), any(LocalDateTime.class));
  }

  @Test
  void unseenReservationRetriesOnlyTheOriginalOrderAndSnapshot() {
    StockClient stockClient = mock(StockClient.class);
    OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    OrderReservationIntentPO intent = intent("202609recover4");
    when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(intent));
    when(intentMapper.claimRecovery(eq(intent.orderNo), any(), any())).thenReturn(1);
    doReturn(
            ApiResponse.ok(stockStatus(intent.orderNo, "NOT_FOUND")),
            ApiResponse.ok(stockStatus(intent.orderNo, "REJECTED")))
        .when(stockClient)
        .reservation(intent.orderNo);
    OrderReservationRecovery recovery =
        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper());

    recovery.recoverStaleReservations();

    org.mockito.ArgumentCaptor<StockClient.Reservation> replay =
        org.mockito.ArgumentCaptor.forClass(StockClient.Reservation.class);
    verify(stockClient).reserve(replay.capture());
    org.junit.jupiter.api.Assertions.assertEquals(intent.orderNo, replay.getValue().orderNo());
    org.junit.jupiter.api.Assertions.assertEquals(7L, replay.getValue().items().get(0).skuId());
    verify(stockClient, never()).rollback(anyString());
    verify(intentMapper).markReleased(eq(intent.orderNo), any(LocalDateTime.class));
  }

  @Test
  void mismatchedOrMissingStockIdentityCannotAuthorizeAnyRecoveryMutation() {
    String originalOrderNo = "202609recoveridentity";
    for (String responseOrderNo :
        java.util.Arrays.asList(
            "202609other",
            "202609RECOVERIDENTITY",
            "202609récovéridentity",
            originalOrderNo + " ",
            null,
            " ")) {
      for (String responseStatus :
          List.of("RESERVED", "ALREADY_RESERVED", "ROLLED_BACK", "REJECTED", "NOT_FOUND", "INIT")) {
        StockClient stockClient = mock(StockClient.class);
        OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
        OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
        OrderReservationIntentPO original = intent(originalOrderNo);
        when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(original));
        when(intentMapper.claimRecovery(eq(originalOrderNo), any(), any())).thenReturn(1);
        doReturn(ApiResponse.ok(stockStatus(responseOrderNo, responseStatus)))
            .when(stockClient)
            .reservation(originalOrderNo);

        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper())
            .recoverStaleReservations();

        verify(stockClient, never()).reserve(any());
        verify(stockClient, never()).rollback(anyString());
        verify(intentMapper, never()).markReleased(anyString(), any());
        verify(intentMapper, never()).forceCommitted(anyString(), any());
        verify(intentMapper).recordFailure(eq(originalOrderNo), anyString(), any());
      }
    }
  }

  @Test
  void secondStockQueryMustMatchEvenAfterAnAuthorizedOriginalReserve() {
    StockClient stockClient = mock(StockClient.class);
    OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    OrderReservationIntentPO original = intent("202609recoversecond");
    when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(original));
    when(intentMapper.claimRecovery(eq(original.orderNo), any(), any())).thenReturn(1);
    doReturn(
            ApiResponse.ok(stockStatus(original.orderNo, "NOT_FOUND")),
            ApiResponse.ok(stockStatus("202609other", "ROLLED_BACK")))
        .when(stockClient)
        .reservation(original.orderNo);
    doReturn(ApiResponse.ok(java.util.Map.of("reserved", true))).when(stockClient).reserve(any());

    new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper())
        .recoverStaleReservations();

    var replay = org.mockito.ArgumentCaptor.forClass(StockClient.Reservation.class);
    verify(stockClient).reserve(replay.capture());
    org.junit.jupiter.api.Assertions.assertEquals(original.orderNo, replay.getValue().orderNo());
    org.junit.jupiter.api.Assertions.assertEquals(7L, replay.getValue().items().get(0).skuId());
    org.junit.jupiter.api.Assertions.assertEquals(2, replay.getValue().items().get(0).quantity());
    verify(stockClient, never()).rollback(anyString());
    verify(intentMapper, never()).markReleased(anyString(), any());
    verify(intentMapper).recordFailure(eq(original.orderNo), anyString(), any());
  }

  @Test
  void matchingIdentityKeepsAlreadyReservedAndUnknownStateSemantics() {
    for (String state : List.of("ALREADY_RESERVED", "UNKNOWN")) {
      StockClient stockClient = mock(StockClient.class);
      OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
      OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
      OrderReservationIntentPO original = intent("202609recovermatching");
      when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(original));
      when(intentMapper.claimRecovery(eq(original.orderNo), any(), any())).thenReturn(1);
      doReturn(ApiResponse.ok(stockStatus(original.orderNo, state)))
          .when(stockClient)
          .reservation(original.orderNo);
      doReturn(ApiResponse.ok(java.util.Map.of("rolledBack", true)))
          .when(stockClient)
          .rollback(original.orderNo);

      new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper())
          .recoverStaleReservations();

      verify(stockClient, never()).reserve(any());
      if ("ALREADY_RESERVED".equals(state)) {
        verify(stockClient).rollback(original.orderNo);
        verify(intentMapper).markReleased(eq(original.orderNo), any());
        verify(intentMapper, never()).recordFailure(anyString(), anyString(), any());
      } else {
        verify(stockClient, never()).rollback(anyString());
        verify(intentMapper, never()).markReleased(anyString(), any());
        verify(intentMapper).recordFailure(eq(original.orderNo), anyString(), any());
      }
    }
  }

  private static StockReservationStatusVO stockStatus(String orderNo, String status) {
    StockReservationStatusVO reservation = new StockReservationStatusVO();
    reservation.orderNo = orderNo;
    reservation.status = status;
    reservation.items = List.of();
    return reservation;
  }

  private static OrderReservationIntentPO intent(String orderNo) {
    OrderReservationIntentPO intent = new OrderReservationIntentPO();
    intent.orderNo = orderNo;
    intent.userId = 8L;
    intent.idempotencyKey = "request-1";
    intent.itemsSnapshot = "[{\"skuId\":7,\"quantity\":2}]";
    intent.status = "RESERVED";
    return intent;
  }
}
