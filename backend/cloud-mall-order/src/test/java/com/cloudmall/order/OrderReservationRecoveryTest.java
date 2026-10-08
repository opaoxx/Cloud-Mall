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
import org.springframework.dao.DataAccessResourceFailureException;

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

  @Test
  void candidateDatabaseFailuresDoNotAbortOtherReservationsOrWriteTwice() {
    for (String phase : List.of("claim", "read", "release", "committed", "record")) {
      StockClient stockClient = mock(StockClient.class);
      OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
      OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
      OrderReservationIntentPO first = intent("202609firstfailure");
      OrderReservationIntentPO second = intent("202609secondhealthy");
      when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(first, second));
      when(intentMapper.claimRecovery(anyString(), any(), any())).thenReturn(1);
      doReturn(ApiResponse.ok(stockStatus(first.orderNo, "ROLLED_BACK")))
          .when(stockClient)
          .reservation(first.orderNo);
      doReturn(ApiResponse.ok(stockStatus(second.orderNo, "ROLLED_BACK")))
          .when(stockClient)
          .reservation(second.orderNo);
      var databaseFailure = new DataAccessResourceFailureException("candidate database failure");
      switch (phase) {
        case "claim" ->
            when(intentMapper.claimRecovery(eq(first.orderNo), any(), any()))
                .thenThrow(databaseFailure);
        case "read" ->
            when(orderSqlMapper.orderExists("mall_order_202609", first.orderNo))
                .thenThrow(databaseFailure);
        case "release" -> {
          doReturn(ApiResponse.ok(stockStatus(first.orderNo, "RESERVED")))
              .when(stockClient)
              .reservation(first.orderNo);
          doReturn(ApiResponse.ok(java.util.Map.of("rolledBack", true)))
              .when(stockClient)
              .rollback(first.orderNo);
          when(intentMapper.markReleased(eq(first.orderNo), any())).thenThrow(databaseFailure);
        }
        case "committed" -> {
          when(orderSqlMapper.orderExists("mall_order_202609", first.orderNo)).thenReturn(true);
          when(intentMapper.forceCommitted(eq(first.orderNo), any())).thenThrow(databaseFailure);
        }
        case "record" -> {
          doReturn(ApiResponse.ok(stockStatus("202609other", "ROLLED_BACK")))
              .when(stockClient)
              .reservation(first.orderNo);
          when(intentMapper.recordFailure(eq(first.orderNo), anyString(), any()))
              .thenThrow(databaseFailure);
        }
        default -> throw new AssertionError("Unexpected test phase");
      }

      org.junit.jupiter.api.Assertions.assertDoesNotThrow(
          () ->
              new OrderReservationRecovery(
                      stockClient, intentMapper, orderSqlMapper, new ObjectMapper())
                  .recoverStaleReservations());

      verify(stockClient).reservation(second.orderNo);
      verify(intentMapper).markReleased(eq(second.orderNo), any());
      verify(stockClient, never()).reserve(any());
      if ("record".equals(phase)) {
        verify(intentMapper).recordFailure(eq(first.orderNo), anyString(), any());
      } else {
        verify(intentMapper, never()).recordFailure(eq(first.orderNo), anyString(), any());
      }
      if ("release".equals(phase)) {
        verify(stockClient).rollback(first.orderNo);
        verify(intentMapper).markReleased(eq(first.orderNo), any());
      } else {
        verify(stockClient, never()).rollback(anyString());
        verify(intentMapper, never()).markReleased(eq(first.orderNo), any());
      }
    }
  }

  @Test
  void scanDatabaseFailureStillPropagatesBeforeAnyCandidateOrStockCall() {
    StockClient stockClient = mock(StockClient.class);
    OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    var failure = new DataAccessResourceFailureException("scan database failure");
    when(intentMapper.findRecoverable(any(), any(), anyInt())).thenThrow(failure);
    var recovery =
        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper());
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        org.junit.jupiter.api.Assertions.assertThrows(
            DataAccessResourceFailureException.class, recovery::recoverStaleReservations));
    org.mockito.Mockito.verifyNoInteractions(stockClient, orderSqlMapper);
    verify(intentMapper, never()).claimRecovery(anyString(), any(), any());
  }

  @Test
  void nonDatabaseClaimFailureIsNotSwallowedAsARecoverableDatabaseFault() {
    StockClient stockClient = mock(StockClient.class);
    OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    var first = intent("202609firstfailure");
    var second = intent("202609secondhealthy");
    when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(first, second));
    var failure = new IllegalStateException("programming failure");
    when(intentMapper.claimRecovery(eq(first.orderNo), any(), any())).thenThrow(failure);
    var recovery =
        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper());
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        org.junit.jupiter.api.Assertions.assertThrows(
            IllegalStateException.class, recovery::recoverStaleReservations));
    verify(intentMapper, never()).claimRecovery(eq(second.orderNo), any(), any());
    verify(intentMapper, never()).recordFailure(anyString(), anyString(), any());
    org.mockito.Mockito.verifyNoInteractions(stockClient);
  }

  @Test
  void fatalErrorStillStopsTheBatchWithoutRecordingOrContinuing() {
    StockClient stockClient = mock(StockClient.class);
    OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    var first = intent("202609firstfailure");
    var second = intent("202609secondhealthy");
    when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(first, second));
    when(intentMapper.claimRecovery(eq(first.orderNo), any(), any())).thenReturn(1);
    var failure = new AssertionError("fatal recovery failure");
    org.mockito.Mockito.doThrow(failure).when(stockClient).reservation(first.orderNo);
    var recovery =
        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper());
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        org.junit.jupiter.api.Assertions.assertThrows(
            AssertionError.class, recovery::recoverStaleReservations));
    verify(intentMapper, never()).claimRecovery(eq(second.orderNo), any(), any());
    verify(intentMapper, never()).recordFailure(anyString(), anyString(), any());
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
