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
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;

class OrderReservationRecoveryTest {
  /** 只清理本测试工作线程的中断，避免污染后续用例。 */
  @AfterEach
  void clearTestInterrupt() {
    Thread.interrupted();
  }

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

  @Test
  void preInterruptedRecoveryDoesNotScanOrClearItsInterruptFlag() {
    var fixture = new RecoveryFixture();
    Thread.currentThread().interrupt();
    fixture.recovery.recoverStaleReservations();
    org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
    org.mockito.Mockito.verifyNoInteractions(fixture.stock, fixture.intents, fixture.orders);
  }

  @Test
  void observableInterruptCheckpointsStopLaterActionsAndCandidates() throws Exception {
    for (String phase :
        List.of(
            "scan",
            "claim",
            "order",
            "status",
            "parse",
            "reserve",
            "rollback",
            "complete",
            "record")) {
      var fixture = new RecoveryFixture();
      switch (phase) {
        case "scan" ->
            when(fixture.intents.findRecoverable(any(), any(), anyInt()))
                .thenAnswer(
                    invocation -> {
                      Thread.currentThread().interrupt();
                      return List.of(fixture.first, fixture.second);
                    });
        case "claim" ->
            when(fixture.intents.claimRecovery(eq(fixture.first.orderNo), any(), any()))
                .thenAnswer(
                    invocation -> {
                      Thread.currentThread().interrupt();
                      return 1;
                    });
        case "order" ->
            when(fixture.orders.orderExists("mall_order_202609", fixture.first.orderNo))
                .thenAnswer(
                    invocation -> {
                      Thread.currentThread().interrupt();
                      return false;
                    });
        case "status" ->
            when(fixture.stock.reservation(fixture.first.orderNo))
                .thenAnswer(
                    invocation -> {
                      Thread.currentThread().interrupt();
                      return ApiResponse.ok(stockStatus(fixture.first.orderNo, "ROLLED_BACK"));
                    });
        case "parse" -> {
          fixture.useInitialStockFact();
          org.mockito.Mockito.doAnswer(
                  invocation -> {
                    Thread.currentThread().interrupt();
                    return List.of(new StockClient.Line(7L, 2));
                  })
              .when(fixture.snapshots)
              .readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class));
        }
        case "reserve" -> {
          fixture.useInitialStockFact();
          when(fixture.stock.reserve(any()))
              .thenAnswer(
                  invocation -> {
                    Thread.currentThread().interrupt();
                    return ApiResponse.ok(java.util.Map.of("reserved", true));
                  });
        }
        case "rollback" -> {
          doReturn(ApiResponse.ok(stockStatus(fixture.first.orderNo, "RESERVED")))
              .when(fixture.stock)
              .reservation(fixture.first.orderNo);
          when(fixture.stock.rollback(fixture.first.orderNo))
              .thenAnswer(
                  invocation -> {
                    Thread.currentThread().interrupt();
                    return ApiResponse.ok(java.util.Map.of("rolledBack", true));
                  });
        }
        case "complete" ->
            when(fixture.intents.markReleased(eq(fixture.first.orderNo), any()))
                .thenAnswer(
                    invocation -> {
                      Thread.currentThread().interrupt();
                      return 1;
                    });
        case "record" -> {
          doReturn(ApiResponse.ok(stockStatus("202609other", "ROLLED_BACK")))
              .when(fixture.stock)
              .reservation(fixture.first.orderNo);
          when(fixture.intents.recordFailure(eq(fixture.first.orderNo), anyString(), any()))
              .thenAnswer(
                  invocation -> {
                    Thread.currentThread().interrupt();
                    return 1;
                  });
        }
        default -> throw new AssertionError("Unexpected interruption phase");
      }
      try {
        fixture.recovery.recoverStaleReservations();
        org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted(), phase);
        verify(fixture.intents, never()).claimRecovery(eq(fixture.second.orderNo), any(), any());
        verify(fixture.stock, never()).reservation(fixture.second.orderNo);
        if (!"reserve".equals(phase)) verify(fixture.stock, never()).reserve(any());
        if (!"rollback".equals(phase)) verify(fixture.stock, never()).rollback(anyString());
        if (!"complete".equals(phase))
          verify(fixture.intents, never()).markReleased(anyString(), any());
        if (!"record".equals(phase))
          verify(fixture.intents, never()).recordFailure(anyString(), anyString(), any());
        if ("scan".equals(phase))
          verify(fixture.intents, never()).claimRecovery(anyString(), any(), any());
        if (List.of("scan", "claim", "order").contains(phase))
          org.mockito.Mockito.verifyNoInteractions(fixture.stock);
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void wrappedInterruptedExceptionsRestoreFlagsWithoutRecordingOrContinuing() throws Exception {
    for (String phase :
        List.of(
            "scan",
            "claim",
            "claim-runtime",
            "order",
            "status",
            "parse",
            "reserve",
            "rollback",
            "complete",
            "record",
            "rejection")) {
      var fixture = new RecoveryFixture();
      var interrupted = new InterruptedException("blocked operation interrupted");
      var databaseFailure = new DataAccessResourceFailureException("interrupted SQL", interrupted);
      var remoteFailure = new IllegalStateException("interrupted remote call", interrupted);
      switch (phase) {
        case "scan" ->
            when(fixture.intents.findRecoverable(any(), any(), anyInt()))
                .thenThrow(databaseFailure);
        case "claim" ->
            when(fixture.intents.claimRecovery(eq(fixture.first.orderNo), any(), any()))
                .thenThrow(databaseFailure);
        case "claim-runtime" ->
            when(fixture.intents.claimRecovery(eq(fixture.first.orderNo), any(), any()))
                .thenThrow(remoteFailure);
        case "order" ->
            when(fixture.orders.orderExists("mall_order_202609", fixture.first.orderNo))
                .thenThrow(databaseFailure);
        case "status" ->
            org.mockito.Mockito.doThrow(remoteFailure)
                .when(fixture.stock)
                .reservation(fixture.first.orderNo);
        case "parse" -> {
          fixture.useInitialStockFact();
          org.mockito.Mockito.doThrow(
                  com.fasterxml.jackson.databind.JsonMappingException.from(
                      (com.fasterxml.jackson.core.JsonParser) null,
                      "interrupted parse",
                      interrupted))
              .when(fixture.snapshots)
              .readValue(anyString(), any(com.fasterxml.jackson.core.type.TypeReference.class));
        }
        case "reserve" -> {
          fixture.useInitialStockFact();
          org.mockito.Mockito.doThrow(remoteFailure).when(fixture.stock).reserve(any());
        }
        case "rollback" -> {
          doReturn(ApiResponse.ok(stockStatus(fixture.first.orderNo, "RESERVED")))
              .when(fixture.stock)
              .reservation(fixture.first.orderNo);
          org.mockito.Mockito.doThrow(remoteFailure)
              .when(fixture.stock)
              .rollback(fixture.first.orderNo);
        }
        case "record" -> {
          doReturn(ApiResponse.ok(stockStatus("202609other", "ROLLED_BACK")))
              .when(fixture.stock)
              .reservation(fixture.first.orderNo);
          when(fixture.intents.recordFailure(eq(fixture.first.orderNo), anyString(), any()))
              .thenThrow(databaseFailure);
        }
        case "complete" ->
            when(fixture.intents.markReleased(eq(fixture.first.orderNo), any()))
                .thenThrow(databaseFailure);
        case "rejection" -> {
          fixture.useInitialStockFact();
          var rejection = mock(feign.FeignException.class);
          when(rejection.status()).thenReturn(409);
          when(rejection.getCause()).thenReturn(interrupted);
          org.mockito.Mockito.doThrow(rejection).when(fixture.stock).reserve(any());
        }
        default -> throw new AssertionError("Unexpected wrapped interruption phase");
      }
      try {
        fixture.recovery.recoverStaleReservations();
        org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted(), phase);
        verify(fixture.intents, never()).claimRecovery(eq(fixture.second.orderNo), any(), any());
        if (!"complete".equals(phase))
          verify(fixture.intents, never()).markReleased(anyString(), any());
        else verify(fixture.intents).markReleased(eq(fixture.first.orderNo), any());
        if (!"record".equals(phase))
          verify(fixture.intents, never()).recordFailure(anyString(), anyString(), any());
        else verify(fixture.intents).recordFailure(eq(fixture.first.orderNo), anyString(), any());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void socketTimeoutDoesNotBecomeThreadCancellation() {
    for (java.io.IOException timeout :
        List.of(
            new java.net.SocketTimeoutException("read timeout"),
            new java.io.InterruptedIOException("ordinary interrupted IO timeout"))) {
      var fixture = new RecoveryFixture();
      org.mockito.Mockito.doThrow(new IllegalStateException("ordinary timeout", timeout))
          .when(fixture.stock)
          .reservation(fixture.first.orderNo);
      fixture.recovery.recoverStaleReservations();
      org.junit.jupiter.api.Assertions.assertFalse(Thread.currentThread().isInterrupted());
      verify(fixture.intents).recordFailure(eq(fixture.first.orderNo), anyString(), any());
      verify(fixture.intents).markReleased(eq(fixture.second.orderNo), any());
    }
  }

  /** 两条候选及原依赖替身，所有测试只改变中断发生点。 */
  private static class RecoveryFixture {
    /** 库存远程调用替身。 */
    private final StockClient stock = mock(StockClient.class);

    /** 意图持久层替身。 */
    private final OrderReservationIntentMapper intents = mock(OrderReservationIntentMapper.class);

    /** 本地订单事实查询替身。 */
    private final OrderSqlMapper orders = mock(OrderSqlMapper.class);

    /** 原快照解析器，可在解析返回时注入中断。 */
    private final ObjectMapper snapshots = org.mockito.Mockito.spy(new ObjectMapper());

    /** 当前处理中断的首条原意图。 */
    private final OrderReservationIntentPO first = intent("202609firstinterrupt");

    /** 中断后禁止再认领的后续原意图。 */
    private final OrderReservationIntentPO second = intent("202609secondinterrupt");

    /** 实际被测恢复任务。 */
    private final OrderReservationRecovery recovery =
        new OrderReservationRecovery(stock, intents, orders, snapshots);

    /** 构造可正常完成的候选，注入故障时不改变原身份。 */
    private RecoveryFixture() {
      when(intents.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(first, second));
      when(intents.claimRecovery(anyString(), any(), any())).thenReturn(1);
      doReturn(ApiResponse.ok(stockStatus(first.orderNo, "ROLLED_BACK")))
          .when(stock)
          .reservation(first.orderNo);
      doReturn(ApiResponse.ok(stockStatus(second.orderNo, "ROLLED_BACK")))
          .when(stock)
          .reservation(second.orderNo);
    }

    /** 使首条进入原订单号/快照预扣重试路径。 */
    private void useInitialStockFact() {
      doReturn(
              ApiResponse.ok(stockStatus(first.orderNo, "NOT_FOUND")),
              ApiResponse.ok(stockStatus(first.orderNo, "ROLLED_BACK")))
          .when(stock)
          .reservation(first.orderNo);
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
