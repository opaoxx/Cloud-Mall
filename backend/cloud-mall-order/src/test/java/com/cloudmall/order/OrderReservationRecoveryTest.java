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
import com.cloudmall.common.error.ErrorCodes;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderReservationRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

class OrderReservationRecoveryTest {
  @Test
  void replaysTheOriginalReservationThenRollsItBackWhenNoOrderCommitted() {
    StockClient stockClient = mock(StockClient.class);
    OrderReservationIntentMapper intentMapper = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orderSqlMapper = mock(OrderSqlMapper.class);
    OrderReservationIntentPO intent = intent("202609recover1");
    when(intentMapper.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(intent));
    when(intentMapper.claimRecovery(eq(intent.orderNo), any(), any())).thenReturn(1);
    when(orderSqlMapper.orderExists("mall_order_202609", intent.orderNo)).thenReturn(false);
    doReturn(ApiResponse.ok(Map.of("reserved", true))).when(stockClient).reserve(any());
    doReturn(ApiResponse.ok(Map.of("rolledBack", true))).when(stockClient).rollback(intent.orderNo);
    OrderReservationRecovery recovery =
        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper());

    recovery.recoverStaleReservations();

    ArgumentCaptor<StockClient.Reservation> reservationCaptor =
        ArgumentCaptor.forClass(StockClient.Reservation.class);
    verify(stockClient).reserve(reservationCaptor.capture());
    org.junit.jupiter.api.Assertions.assertEquals(intent.orderNo, reservationCaptor.getValue().orderNo());
    org.junit.jupiter.api.Assertions.assertEquals(7L, reservationCaptor.getValue().items().get(0).skuId());
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
    doReturn(ApiResponse.error(ErrorCodes.DUPLICATE, "预扣已经回滚"))
        .when(stockClient)
        .reserve(any());
    doReturn(ApiResponse.ok(Map.of("idempotent", true))).when(stockClient).rollback(intent.orderNo);
    OrderReservationRecovery recovery =
        new OrderReservationRecovery(stockClient, intentMapper, orderSqlMapper, new ObjectMapper());

    recovery.recoverStaleReservations();

    verify(stockClient).rollback(intent.orderNo);
    verify(intentMapper).markReleased(eq(intent.orderNo), any(LocalDateTime.class));
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
