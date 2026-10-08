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
