package com.cloudmall.order;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
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
  private OrderStockActionMapper actionMapper;
  private StockClient stockClient;
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
    when(stockClient.reservation("order-1"))
        .thenReturn(status("RESERVED"), status("CONFIRMED"));
    doReturn(ApiResponse.ok("confirmed"))
        .when(stockClient).confirm(eq("order-1"), any(StockClient.Action.class));

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
    doThrow(new StockCircuitOpenException(OrderStockCircuitBreaker.STATUS,
        new IllegalStateException("open"))).when(stockClient).reservation("order-1");

    recovery.recoverDue();

    verify(actionMapper).retry(eq(1L), anyString(), anyString(), any(), any());
    verify(actionMapper, never()).finish(eq(1L), anyString(), anyString(), any(), any());
    verify(stockClient, never()).confirm(anyString(), any(StockClient.Action.class));
    verify(stockClient, never()).rollback(anyString(), any(StockClient.Action.class));
  }

  private void due(String actionType) {
    OrderStockActionPO action = new OrderStockActionPO();
    action.id = 1L;
    action.orderNo = "order-1";
    action.actionType = actionType;
    action.operationKey = "order-1:" + actionType;
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
