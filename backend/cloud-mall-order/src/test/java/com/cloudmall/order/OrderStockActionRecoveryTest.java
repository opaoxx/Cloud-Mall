package com.cloudmall.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderStockActionPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import com.cloudmall.order.service.impl.OrderStockActionRecovery;
import com.cloudmall.order.service.impl.OrderStockCircuitBreaker;
import com.cloudmall.order.service.impl.StockCircuitOpenException;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 验证库存写响应丢失及相反终态不会触发错误补偿。 */
class OrderStockActionRecoveryTest {
  /** 库存动作持久层替身。 */
  private OrderStockActionMapper actionMapper;

  /** 库存事实查询及原操作键动作客户端替身。 */
  private StockClient stockClient;

  /** 实际被测订单库存动作恢复器。 */
  private OrderStockActionRecovery recovery;

  @BeforeEach
  void setUp() {
    actionMapper = mock(OrderStockActionMapper.class);
    stockClient = mock(StockClient.class);
    recovery = new OrderStockActionRecovery(actionMapper, stockClient);
    when(actionMapper.claim(eq(1L), anyString(), any(LocalDateTime.class))).thenReturn(1);
  }

  @Test
  void lostConfirmResponseIsResolvedFromDurableStockStatusWithoutAnotherWrite() {
    due("CONFIRM");
    when(stockClient.reservation("order-1")).thenReturn(status("CONFIRMED"));

    recovery.recoverDue();

    verify(actionMapper).finish(eq(1L), anyString(), eq("SUCCEEDED"), eq(null), any());
    verify(stockClient, never()).confirm(anyString(), any(StockClient.Action.class));
    verify(stockClient, never()).rollback(anyString(), any(StockClient.Action.class));
  }

  @Test
  void reservedStockIsConfirmedWithTheOriginalOperationKey() {
    due("CONFIRM");
    when(stockClient.reservation("order-1")).thenReturn(status("RESERVED"), status("CONFIRMED"));
    doReturn(ApiResponse.ok("confirmed"))
        .when(stockClient)
        .confirm(eq("order-1"), any(StockClient.Action.class));

    recovery.recoverDue();

    ArgumentCaptor<StockClient.Action> request = ArgumentCaptor.forClass(StockClient.Action.class);
    verify(stockClient).confirm(eq("order-1"), request.capture());
    org.junit.jupiter.api.Assertions.assertEquals(
        "order-1:CONFIRM", request.getValue().operationKey());
    verify(actionMapper).finish(eq(1L), anyString(), eq("SUCCEEDED"), eq(null), any());
  }

  @Test
  void cancelledOrderWithAlreadyConfirmedStockStopsAtConflict() {
    due("ROLLBACK");
    when(stockClient.reservation("order-1")).thenReturn(status("CONFIRMED"));

    recovery.recoverDue();

    verify(actionMapper).finish(eq(1L), anyString(), eq("CONFLICT"), anyString(), any());
    verify(stockClient, never()).rollback(anyString(), any(StockClient.Action.class));
  }

  @Test
  void statusCircuitOpenRetainsUnknownActionWithoutAnyStockWrite() {
    due("CONFIRM");
    doThrow(
            new StockCircuitOpenException(
                OrderStockCircuitBreaker.STATUS, new IllegalStateException("open")))
        .when(stockClient)
        .reservation("order-1");

    recovery.recoverDue();

    verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
    verify(actionMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
    verify(stockClient, never()).confirm(anyString(), any(StockClient.Action.class));
    verify(stockClient, never()).rollback(anyString(), any(StockClient.Action.class));
  }

  @Test
  void wrongOrMissingStockIdentityCannotFinishOrAuthorizeEitherAction() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      for (String responseOrder :
          java.util.Arrays.asList("order-2", "ORDER-1", "órder-1", "order-1 ", null, " ")) {
        for (String responseStatus :
            List.of("CONFIRMED", "ROLLED_BACK", "REJECTED", "RESERVED", "NOT_FOUND", "UNKNOWN")) {
          setUp();
          due(actionType);
          when(stockClient.reservation("order-1"))
              .thenReturn(stockFact(responseOrder, responseStatus));
          recovery.recoverDue();
          verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
          verify(actionMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
          verify(stockClient, never()).confirm(anyString(), any(StockClient.Action.class));
          verify(stockClient, never()).rollback(anyString(), any(StockClient.Action.class));
        }
      }
    }
  }

  @Test
  void wrongSecondQueryRetainsUnknownAfterOnlyTheOriginalAuthorizedAction() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      for (String responseStatus : List.of("CONFIRMED", "ROLLED_BACK", "REJECTED")) {
        setUp();
        due(actionType);
        when(stockClient.reservation("order-1"))
            .thenReturn(status("RESERVED"), stockFact("order-2", responseStatus));
        if ("CONFIRM".equals(actionType)) {
          doReturn(ApiResponse.ok("confirmed"))
              .when(stockClient)
              .confirm(eq("order-1"), any(StockClient.Action.class));
        } else {
          doReturn(ApiResponse.ok("rolledBack"))
              .when(stockClient)
              .rollback(eq("order-1"), any(StockClient.Action.class));
        }
        recovery.recoverDue();
        var request = ArgumentCaptor.forClass(StockClient.Action.class);
        if ("CONFIRM".equals(actionType)) {
          verify(stockClient).confirm(eq("order-1"), request.capture());
          verify(stockClient, never()).rollback(anyString(), any(StockClient.Action.class));
        } else {
          verify(stockClient).rollback(eq("order-1"), request.capture());
          verify(stockClient, never()).confirm(anyString(), any(StockClient.Action.class));
        }
        org.junit.jupiter.api.Assertions.assertEquals(
            "order-1:" + actionType, request.getValue().operationKey());
        verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
        verify(actionMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
      }
    }
  }

  @Test
  void unsupportedActionTypesCannotBeGuessedAsRollback() {
    for (String actionType :
        java.util.Arrays.asList(
            null, "", " \t", "confirm", "rollback", "CONFIRM ", "ROLLBACK ", "CANCEL", "OTHER")) {
      for (String current :
          List.of("RESERVED", "ROLLED_BACK", "CONFIRMED", "REJECTED", "UNKNOWN")) {
        setUp();
        due(actionType);
        when(stockClient.reservation("order-1")).thenReturn(status(current));
        recovery.recoverDue();
        org.mockito.Mockito.verifyNoInteractions(stockClient);
        verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
        verify(actionMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
      }
    }
  }

  @Test
  void keysMustMatchTheExactOriginalOrderAndSupportedDirection() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      String opposite = "CONFIRM".equals(actionType) ? "ROLLBACK" : "CONFIRM";
      for (String operationKey :
          java.util.Arrays.asList(
              null,
              "",
              " \t",
              "order-2:" + actionType,
              "order-1:" + opposite,
              "ORDER-1:" + actionType,
              "órder-1:" + actionType,
              "order-1:" + actionType.toLowerCase(java.util.Locale.ROOT),
              "order-1:" + actionType + " ")) {
        for (String current : List.of("RESERVED", "CONFIRMED", "ROLLED_BACK", "REJECTED")) {
          setUp();
          due(actionType, "order-1", operationKey);
          when(stockClient.reservation("order-1")).thenReturn(status(current));
          recovery.recoverDue();
          org.mockito.Mockito.verifyNoInteractions(stockClient);
          verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
          verify(actionMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
        }
      }
    }
  }

  @Test
  void missingOriginalOrderCannotBeAuthorizedByAnApparentlyMatchingKey() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      for (String orderNo : java.util.Arrays.asList(null, "", " \t")) {
        setUp();
        due(actionType, orderNo, orderNo + ":" + actionType);
        when(stockClient.reservation(orderNo))
            .thenReturn(
                stockFact(orderNo, "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK"));
        recovery.recoverDue();
        org.mockito.Mockito.verifyNoInteractions(stockClient);
        verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
        verify(actionMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
      }
    }
  }

  @Test
  void eachClaimUsesANewTokenAndCompletionUsesOnlyItsOwnToken() {
    due("CONFIRM");
    when(stockClient.reservation("order-1"))
        .thenReturn(status("CONFIRMED"))
        .thenThrow(new IllegalStateException("query unknown"))
        .thenReturn(status("CONFIRMED"));
    recovery.recoverDue();
    recovery.recoverDue();
    recovery.recoverDue();
    var claimTokens = ArgumentCaptor.forClass(String.class);
    verify(actionMapper, org.mockito.Mockito.times(3)).claim(eq(1L), claimTokens.capture(), any());
    var finishTokens = ArgumentCaptor.forClass(String.class);
    verify(actionMapper, org.mockito.Mockito.times(2))
        .finish(eq(1L), finishTokens.capture(), eq("SUCCEEDED"), eq(null), any());
    var retryToken = ArgumentCaptor.forClass(String.class);
    verify(actionMapper).retry(eq(1L), retryToken.capture(), anyString(), any(), any());
    org.junit.jupiter.api.Assertions.assertEquals(
        3, new java.util.HashSet<>(claimTokens.getAllValues()).size());
    org.junit.jupiter.api.Assertions.assertEquals(
        claimTokens.getAllValues().get(0), finishTokens.getAllValues().get(0));
    org.junit.jupiter.api.Assertions.assertEquals(
        claimTokens.getAllValues().get(1), retryToken.getValue());
    org.junit.jupiter.api.Assertions.assertEquals(
        claimTokens.getAllValues().get(2), finishTokens.getAllValues().get(1));
    for (String token : claimTokens.getAllValues())
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(() -> java.util.UUID.fromString(token));
    verify(stockClient, never()).confirm(anyString(), any());
    verify(stockClient, never()).rollback(anyString(), any());
  }

  @Test
  void aClaimDatabaseFailureDoesNotStarveTheHealthyCandidate() {
    dueWithHealthyFollower();
    when(actionMapper.claim(eq(1L), anyString(), any()))
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException("claim db unavailable"));
    org.junit.jupiter.api.Assertions.assertDoesNotThrow(recovery::recoverDue);
    verify(stockClient, never()).reservation("order-1");
    verify(actionMapper, never()).retry(eq(1L), anyString(), anyString(), any(), any());
    verify(actionMapper).finish(eq(2L), anyString(), eq("SUCCEEDED"), eq(null), any());
  }

  @Test
  void aFinishDatabaseFailureCannotBecomeAnUnknownRetry() {
    for (String stockState : List.of("CONFIRMED", "ROLLED_BACK")) {
      setUp();
      dueWithHealthyFollower();
      when(stockClient.reservation("order-1")).thenReturn(status(stockState));
      when(actionMapper.finish(eq(1L), anyString(), anyString(), any(), any()))
          .thenThrow(
              new org.springframework.dao.DataAccessResourceFailureException(
                  "finish db unavailable"));
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(recovery::recoverDue);
      verify(actionMapper, never()).retry(eq(1L), anyString(), anyString(), any(), any());
      verify(actionMapper).finish(eq(2L), anyString(), eq("SUCCEEDED"), eq(null), any());
      verify(stockClient, never()).confirm(anyString(), any());
      verify(stockClient, never()).rollback(anyString(), any());
    }
  }

  @Test
  void retryDatabaseFailurePreservesTheBusinessLogAndProcessesTheFollower() {
    dueWithHealthyFollower();
    when(stockClient.reservation("order-1"))
        .thenThrow(new IllegalStateException("original Stock unknown"));
    when(actionMapper.retry(eq(1L), anyString(), anyString(), any(), any()))
        .thenThrow(
            new org.springframework.dao.DataAccessResourceFailureException("retry db unavailable"));
    var logger =
        (ch.qos.logback.classic.Logger)
            org.slf4j.LoggerFactory.getLogger(OrderStockActionRecovery.class);
    var captured =
        new ch.qos.logback.core.read.ListAppender<ch.qos.logback.classic.spi.ILoggingEvent>();
    captured.start();
    logger.addAppender(captured);
    try {
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(recovery::recoverDue);
      verify(actionMapper)
          .retry(
              eq(1L),
              anyString(),
              org.mockito.ArgumentMatchers.contains("original Stock unknown"),
              any(),
              any());
      verify(actionMapper).finish(eq(2L), anyString(), eq("SUCCEEDED"), eq(null), any());
      var failures =
          captured.list.stream().filter(event -> event.getThrowableProxy() != null).toList();
      org.junit.jupiter.api.Assertions.assertEquals(2, failures.size());
      org.junit.jupiter.api.Assertions.assertEquals(
          "original Stock unknown", failures.get(0).getThrowableProxy().getMessage());
      org.junit.jupiter.api.Assertions.assertEquals(
          "retry db unavailable", failures.get(1).getThrowableProxy().getMessage());
    } finally {
      logger.detachAppender(captured);
      captured.stop();
    }
  }

  @Test
  void scanAndNonDatabaseClaimFailuresStillPropagate() {
    var scanFailure =
        new org.springframework.dao.DataAccessResourceFailureException("scan unavailable");
    when(actionMapper.findDue(any(), anyInt())).thenThrow(scanFailure);
    org.junit.jupiter.api.Assertions.assertSame(
        scanFailure,
        org.junit.jupiter.api.Assertions.assertThrows(
            org.springframework.dao.DataAccessException.class, recovery::recoverDue));
    org.mockito.Mockito.verifyNoInteractions(stockClient);
    verify(actionMapper, never()).claim(org.mockito.ArgumentMatchers.anyLong(), anyString(), any());
    for (Throwable failure :
        List.of(new IllegalStateException("claim bug"), new AssertionError("claim Error"))) {
      setUp();
      dueWithHealthyFollower();
      when(actionMapper.claim(eq(1L), anyString(), any())).thenThrow(failure);
      org.junit.jupiter.api.Assertions.assertSame(
          failure,
          org.junit.jupiter.api.Assertions.assertThrows(failure.getClass(), recovery::recoverDue));
      verify(actionMapper, never()).claim(eq(2L), anyString(), any());
      org.mockito.Mockito.verifyNoInteractions(stockClient);
    }
  }

  @AfterEach
  void clearTestInterruptSignal() {
    Thread.interrupted();
  }

  @Test
  void aPreInterruptedThreadDoesNotCallAnyDependency() {
    Thread.currentThread().interrupt();
    try {
      recovery.recoverDue();
      org.junit.jupiter.api.Assertions.assertTrue(Thread.currentThread().isInterrupted());
      org.mockito.Mockito.verifyNoInteractions(actionMapper, stockClient);
    } finally {
      Thread.interrupted();
    }
  }

  @Test
  void signalsAtEachCheckpointStopTheCurrentActionAndAllFollowers() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      for (String phase :
          List.of("SCAN", "CLAIM", "GET", "ACTION", "SECOND_GET", "FINISH", "RETRY")) {
        setUp();
        dueWithHealthyFollower(actionType);
        String target = "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK";
        when(stockClient.reservation("order-1"))
            .thenReturn(stockFact("order-1", "RESERVED"), stockFact("order-1", target));
        if ("CONFIRM".equals(actionType))
          doReturn(ApiResponse.ok("confirmed")).when(stockClient).confirm(eq("order-1"), any());
        else
          doReturn(ApiResponse.ok("rolledBack")).when(stockClient).rollback(eq("order-1"), any());
        switch (phase) {
          case "SCAN" -> {
            var candidates = actionMapper.findDue(LocalDateTime.now(), 50);
            org.mockito.Mockito.doAnswer(
                    invocation -> {
                      Thread.currentThread().interrupt();
                      return candidates;
                    })
                .when(actionMapper)
                .findDue(any(), anyInt());
          }
          case "CLAIM" ->
              when(actionMapper.claim(eq(1L), anyString(), any()))
                  .thenAnswer(
                      invocation -> {
                        Thread.currentThread().interrupt();
                        return 1;
                      });
          case "GET" ->
              when(stockClient.reservation("order-1"))
                  .thenAnswer(
                      invocation -> {
                        Thread.currentThread().interrupt();
                        return stockFact("order-1", "RESERVED");
                      });
          case "ACTION" -> {
            if ("CONFIRM".equals(actionType))
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        Thread.currentThread().interrupt();
                        return ApiResponse.ok("confirmed");
                      })
                  .when(stockClient)
                  .confirm(eq("order-1"), any());
            else
              org.mockito.Mockito.doAnswer(
                      invocation -> {
                        Thread.currentThread().interrupt();
                        return ApiResponse.ok("rolledBack");
                      })
                  .when(stockClient)
                  .rollback(eq("order-1"), any());
          }
          case "SECOND_GET" ->
              when(stockClient.reservation("order-1"))
                  .thenReturn(stockFact("order-1", "RESERVED"))
                  .thenAnswer(
                      invocation -> {
                        Thread.currentThread().interrupt();
                        return stockFact("order-1", target);
                      });
          case "FINISH" -> {
            doReturn(stockFact("order-1", target)).when(stockClient).reservation("order-1");
            when(actionMapper.finish(eq(1L), anyString(), anyString(), any(), any()))
                .thenAnswer(
                    invocation -> {
                      Thread.currentThread().interrupt();
                      return 1;
                    });
          }
          case "RETRY" -> {
            doThrow(new IllegalStateException("ordinary Stock unknown"))
                .when(stockClient)
                .reservation("order-1");
            when(actionMapper.retry(eq(1L), anyString(), anyString(), any(), any()))
                .thenAnswer(
                    invocation -> {
                      Thread.currentThread().interrupt();
                      return 1;
                    });
          }
          default -> throw new AssertionError("unexpected phase");
        }
        try {
          org.junit.jupiter.api.Assertions.assertDoesNotThrow(recovery::recoverDue);
          org.junit.jupiter.api.Assertions.assertTrue(
              Thread.currentThread().isInterrupted(), actionType + phase);
          verify(actionMapper, never()).claim(eq(2L), anyString(), any());
          verify(stockClient, never()).reservation("order-2");
          if (!"FINISH".equals(phase))
            verify(actionMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
          else verify(actionMapper).finish(eq(1L), anyString(), eq("SUCCEEDED"), eq(null), any());
          if (!"RETRY".equals(phase))
            verify(actionMapper, never()).retry(eq(1L), anyString(), anyString(), any(), any());
          else verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
          if (List.of("SCAN", "CLAIM").contains(phase))
            verify(stockClient, never()).reservation("order-1");
          if (List.of("ACTION", "SECOND_GET").contains(phase)) {
            if ("CONFIRM".equals(actionType)) verify(stockClient).confirm(eq("order-1"), any());
            else verify(stockClient).rollback(eq("order-1"), any());
          } else {
            verify(stockClient, never()).confirm(anyString(), any());
            verify(stockClient, never()).rollback(anyString(), any());
          }
        } finally {
          Thread.interrupted();
        }
      }
    }
  }

  @Test
  void wrappedInterruptedCausesRestoreTheSignalAndStopAllFollowers() {
    for (String actionType : List.of("CONFIRM", "ROLLBACK")) {
      for (String phase :
          List.of(
              "SCAN_DB",
              "SCAN_RUNTIME",
              "CLAIM_DB",
              "CLAIM_RUNTIME",
              "GET",
              "ACTION",
              "FINISH",
              "RETRY")) {
        setUp();
        dueWithHealthyFollower(actionType);
        var signal = new InterruptedException("operation interrupted");
        RuntimeException wrapped =
            List.of("GET", "ACTION", "SCAN_RUNTIME", "CLAIM_RUNTIME").contains(phase)
                ? new IllegalStateException("wrapped remote signal", signal)
                : new org.springframework.dao.DataAccessResourceFailureException(
                    "wrapped database signal", signal);
        switch (phase) {
          case "SCAN_DB", "SCAN_RUNTIME" ->
              org.mockito.Mockito.doThrow(wrapped).when(actionMapper).findDue(any(), anyInt());
          case "CLAIM_DB", "CLAIM_RUNTIME" ->
              when(actionMapper.claim(eq(1L), anyString(), any())).thenThrow(wrapped);
          case "GET" -> doThrow(wrapped).when(stockClient).reservation("order-1");
          case "ACTION" -> {
            doReturn(stockFact("order-1", "RESERVED")).when(stockClient).reservation("order-1");
            if ("CONFIRM".equals(actionType))
              doThrow(wrapped).when(stockClient).confirm(eq("order-1"), any());
            else doThrow(wrapped).when(stockClient).rollback(eq("order-1"), any());
          }
          case "FINISH" ->
              when(actionMapper.finish(eq(1L), anyString(), anyString(), any(), any()))
                  .thenThrow(wrapped);
          case "RETRY" -> {
            doThrow(new IllegalStateException("ordinary Stock unknown"))
                .when(stockClient)
                .reservation("order-1");
            when(actionMapper.retry(eq(1L), anyString(), anyString(), any(), any()))
                .thenThrow(wrapped);
          }
          default -> throw new AssertionError("unexpected phase");
        }
        try {
          org.junit.jupiter.api.Assertions.assertDoesNotThrow(recovery::recoverDue);
          org.junit.jupiter.api.Assertions.assertTrue(
              Thread.currentThread().isInterrupted(), actionType + phase);
          verify(actionMapper, never()).claim(eq(2L), anyString(), any());
          verify(stockClient, never()).reservation("order-2");
          if (!"RETRY".equals(phase))
            verify(actionMapper, never()).retry(eq(1L), anyString(), anyString(), any(), any());
          else verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
          if (!"FINISH".equals(phase))
            verify(actionMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
        } finally {
          Thread.interrupted();
        }
      }
    }
  }

  @Test
  void ordinaryIoTimeoutsAndCyclicCausesRemainBusinessRetries() {
    var cycleFirst = new IllegalStateException("cycle first");
    var cycleSecond = new IllegalStateException("cycle second");
    cycleFirst.initCause(cycleSecond);
    cycleSecond.initCause(cycleFirst);
    for (RuntimeException failure :
        List.of(
            new IllegalStateException(new java.net.SocketTimeoutException("read timeout")),
            new IllegalStateException(new java.io.InterruptedIOException("ordinary IO failure")),
            cycleFirst)) {
      setUp();
      dueWithHealthyFollower();
      doThrow(failure).when(stockClient).reservation("order-1");
      org.junit.jupiter.api.Assertions.assertDoesNotThrow(recovery::recoverDue);
      org.junit.jupiter.api.Assertions.assertFalse(Thread.currentThread().isInterrupted());
      verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
      verify(actionMapper).finish(eq(2L), anyString(), eq("SUCCEEDED"), eq(null), any());
    }
  }

  @Test
  void aStockErrorStillPropagatesWithoutRetryOrFollowingClaims() {
    dueWithHealthyFollower();
    var failure = new AssertionError("Stock Error");
    doThrow(failure).when(stockClient).reservation("order-1");
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        org.junit.jupiter.api.Assertions.assertThrows(AssertionError.class, recovery::recoverDue));
    verify(actionMapper, never()).retry(eq(1L), anyString(), anyString(), any(), any());
    verify(actionMapper, never()).claim(eq(2L), anyString(), any());
  }

  /** 构造首动作和后健康候选，隔离测试不依赖真实Stock或数据库。 */
  private void dueWithHealthyFollower() {
    dueWithHealthyFollower("CONFIRM");
  }

  /** 按原方向构造两个独立合法动作，不改变业务身份契约。 */
  private void dueWithHealthyFollower(String actionType) {
    OrderStockActionPO first = new OrderStockActionPO();
    first.id = 1L;
    first.orderNo = "order-1";
    first.actionType = actionType;
    first.operationKey = "order-1:" + actionType;
    first.attempts = 0;
    OrderStockActionPO healthy = new OrderStockActionPO();
    healthy.id = 2L;
    healthy.orderNo = "order-2";
    healthy.actionType = actionType;
    healthy.operationKey = "order-2:" + actionType;
    healthy.attempts = 0;
    when(actionMapper.findDue(any(), anyInt())).thenReturn(List.of(first, healthy));
    when(actionMapper.claim(eq(2L), anyString(), any())).thenReturn(1);
    String target = "CONFIRM".equals(actionType) ? "CONFIRMED" : "ROLLED_BACK";
    when(stockClient.reservation("order-1")).thenReturn(status(target));
    when(stockClient.reservation("order-2")).thenReturn(stockFact("order-2", target));
  }

  /** 构造具有指定身份的库存响应，不从请求路径补全字段。 */
  private static ApiResponse<StockReservationStatusVO> stockFact(String orderNo, String state) {
    StockReservationStatusVO fact = new StockReservationStatusVO();
    fact.orderNo = orderNo;
    fact.status = state;
    return ApiResponse.ok(fact);
  }

  private void due(String actionType) {
    due(actionType, "order-1", "order-1:" + actionType);
  }

  /** 构造原始动作元组，非法键不由测试工厂自动修正。 */
  private void due(String actionType, String orderNo, String operationKey) {
    OrderStockActionPO action = new OrderStockActionPO();
    action.id = 1L;
    action.orderNo = orderNo;
    action.actionType = actionType;
    action.operationKey = operationKey;
    action.attempts = 0;
    when(actionMapper.findDue(any(LocalDateTime.class), anyInt())).thenReturn(List.of(action));
  }

  private static ApiResponse<StockReservationStatusVO> status(String value) {
    StockReservationStatusVO view = new StockReservationStatusVO();
    view.orderNo = "order-1";
    view.status = value;
    return ApiResponse.ok(view);
  }
}
