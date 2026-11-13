package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.reservation.OrderReservationRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

/** 扫描窗口与逐候选认领时间必须独立，候选租约仍精确一分钟。 */
class OrderReservationClaimTimeTest {
  @Test
  void scanWindowStaysFixedWhileEachClaimUsesAFreshShanghaiTimestamp() {
    OrderReservationIntentMapper intents = mock(OrderReservationIntentMapper.class);
    OrderSqlMapper orders = mock(OrderSqlMapper.class);
    StockClient stock = mock(StockClient.class);
    OrderReservationIntentPO first = intent("202610claimtime1");
    OrderReservationIntentPO second = intent("202610claimtime2");
    AtomicReference<LocalDateTime> scanReadyAt = new AtomicReference<>();
    AtomicReference<LocalDateTime> predecessorFinishedAt = new AtomicReference<>();
    when(intents.findRecoverable(any(), any(), anyInt()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(30);
              scanReadyAt.set(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
              return List.of(first, second);
            });
    when(intents.claimRecovery(anyString(), any(), any())).thenReturn(1);
    when(orders.orderExists(anyString(), anyString())).thenReturn(true);
    when(intents.forceCommitted(eq(first.orderNo), any()))
        .thenAnswer(
            invocation -> {
              Thread.sleep(30);
              predecessorFinishedAt.set(LocalDateTime.now(ZoneId.of("Asia/Shanghai")));
              return 1;
            });
    new OrderReservationRecovery(stock, intents, orders, new ObjectMapper())
        .recoverStaleReservations();
    ArgumentCaptor<LocalDateTime> staleBefore = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> scanAt = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(intents).findRecoverable(staleBefore.capture(), scanAt.capture(), eq(50));
    assertEquals(scanAt.getValue().minusMinutes(2), staleBefore.getValue());
    ArgumentCaptor<LocalDateTime> claimedAt = ArgumentCaptor.forClass(LocalDateTime.class);
    ArgumentCaptor<LocalDateTime> expiresAt = ArgumentCaptor.forClass(LocalDateTime.class);
    verify(intents, times(2)).claimRecovery(anyString(), claimedAt.capture(), expiresAt.capture());
    assertAll(
        () -> assertFalse(claimedAt.getAllValues().get(0).isBefore(scanReadyAt.get())),
        () -> assertFalse(claimedAt.getAllValues().get(1).isBefore(predecessorFinishedAt.get())),
        () ->
            assertEquals(
                claimedAt.getAllValues().get(0).plusMinutes(1), expiresAt.getAllValues().get(0)),
        () ->
            assertEquals(
                claimedAt.getAllValues().get(1).plusMinutes(1), expiresAt.getAllValues().get(1)));
    verify(intents).forceCommitted(eq(first.orderNo), any());
    verify(intents).forceCommitted(eq(second.orderNo), any());
    verifyNoInteractions(stock);
  }

  /** 固定原候选身份，不增加Clock或状态测试接口。 */
  private static OrderReservationIntentPO intent(String orderNo) {
    OrderReservationIntentPO intent = new OrderReservationIntentPO();
    intent.orderNo = orderNo;
    return intent;
  }
}
