package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.order.domain.po.OrderReservationIntentPO;
import com.cloudmall.order.domain.vo.StockReservationStatusVO;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.mapper.OrderReservationIntentMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderReservationRecovery;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

/** 回滚后新增只读证明的中断与原失败分支调用次数边界。 */
class OrderReservationRollbackProofTest {
  /** 清理本例工作线程的中断，避免影响下一例。 */
  @AfterEach
  void clearTestInterrupt() {
    Thread.interrupted();
  }

  @Test
  void interruptionDuringPostRollbackProofPreservesLeaseAndStopsTheBatch() {
    for (String interruption : List.of("flag", "wrapped")) {
      Fixture fixture = new Fixture();
      when(fixture.stock.reservation(fixture.first.orderNo))
          .thenReturn(fact(fixture.first.orderNo, "RESERVED"))
          .thenAnswer(
              invocation -> {
                if ("wrapped".equals(interruption))
                  throw new IllegalStateException(
                      "proof interrupted", new InterruptedException("stop"));
                Thread.currentThread().interrupt();
                return fact(fixture.first.orderNo, "ROLLED_BACK");
              });
      doReturn(ApiResponse.ok("accepted")).when(fixture.stock).rollback(fixture.first.orderNo);
      try {
        fixture.recovery.recoverStaleReservations();
        assertTrue(Thread.currentThread().isInterrupted(), interruption);
        verify(fixture.stock, times(2)).reservation(fixture.first.orderNo);
        verify(fixture.stock).rollback(fixture.first.orderNo);
        verify(fixture.intents, never()).markReleased(anyString(), any());
        verify(fixture.intents, never()).recordFailure(anyString(), anyString(), any());
        verify(fixture.intents, never()).claimRecovery(eq(fixture.second.orderNo), any(), any());
        verify(fixture.stock, never()).reservation(fixture.second.orderNo);
        verify(fixture.stock, never()).reserve(any());
      } finally {
        Thread.interrupted();
      }
    }
  }

  @Test
  void unsuccessfulRollbackKeepsTheOriginalSingleQueryFailurePath() {
    for (String failure : List.of("null", "nonzero", "exception")) {
      Fixture fixture = new Fixture();
      when(fixture.stock.reservation(fixture.first.orderNo))
          .thenReturn(fact(fixture.first.orderNo, "RESERVED"));
      when(fixture.stock.reservation(fixture.second.orderNo))
          .thenReturn(fact(fixture.second.orderNo, "ROLLED_BACK"));
      switch (failure) {
        case "null" -> doReturn(null).when(fixture.stock).rollback(fixture.first.orderNo);
        case "nonzero" ->
            doReturn(ApiResponse.error("503", "unknown"))
                .when(fixture.stock)
                .rollback(fixture.first.orderNo);
        case "exception" ->
            doThrow(new IllegalStateException("rollback timeout"))
                .when(fixture.stock)
                .rollback(fixture.first.orderNo);
        default -> throw new AssertionError(failure);
      }
      fixture.recovery.recoverStaleReservations();
      verify(fixture.stock).reservation(fixture.first.orderNo);
      verify(fixture.stock).rollback(fixture.first.orderNo);
      verify(fixture.intents).recordFailure(eq(fixture.first.orderNo), anyString(), any());
      verify(fixture.intents, never()).markReleased(eq(fixture.first.orderNo), any());
      verify(fixture.intents).markReleased(eq(fixture.second.orderNo), any());
      verify(fixture.stock).reservation(fixture.second.orderNo);
      verify(fixture.stock, never()).reserve(any());
    }
  }

  /** 每次使用全新替身，只有认领与已提交订单判据被替代。 */
  private static class Fixture {
    /** 原预扣意图持久层替身。 */
    final OrderReservationIntentMapper intents = mock(OrderReservationIntentMapper.class);

    /** 订单存在性查询替身。 */
    final OrderSqlMapper orders = mock(OrderSqlMapper.class);

    /** 远端库存客户端替身。 */
    final StockClient stock = mock(StockClient.class);

    /** 首条回滚结果待证明的原意图。 */
    final OrderReservationIntentPO first = intent("202610proof1");

    /** 后条候选用于核验批次中断或继续语义。 */
    final OrderReservationIntentPO second = intent("202610proof2");

    /** 实际被测恢复逻辑。 */
    final OrderReservationRecovery recovery =
        new OrderReservationRecovery(stock, intents, orders, new ObjectMapper());

    private Fixture() {
      when(intents.findRecoverable(any(), any(), anyInt())).thenReturn(List.of(first, second));
      when(intents.claimRecovery(anyString(), any(), any())).thenReturn(1);
    }
  }

  /** 构造持久原订单身份，不替换响应中的身份字段。 */
  private static OrderReservationIntentPO intent(String orderNo) {
    OrderReservationIntentPO intent = new OrderReservationIntentPO();
    intent.orderNo = orderNo;
    return intent;
  }

  /** 查询结果必须显式带原订单号与状态。 */
  private static ApiResponse<StockReservationStatusVO> fact(String orderNo, String status) {
    StockReservationStatusVO fact = new StockReservationStatusVO();
    fact.orderNo = orderNo;
    fact.status = status;
    return ApiResponse.ok(fact);
  }
}
