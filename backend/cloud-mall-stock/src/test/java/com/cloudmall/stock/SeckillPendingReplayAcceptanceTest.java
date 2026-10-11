package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 已存在待释放重试也须观察原受理来源，不把缺证包装为已受理。 */
class SeckillPendingReplayAcceptanceTest {
  /** 本次原请求订单。 */
  private static final String ORDER_NO = "pending-replay-order";

  @Test
  void missingSourceCannotAcceptExistingPendingRollbackReplay() {
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      var original = row(state);
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
      when(mapper.findMatchingAcceptanceId(same(original))).thenReturn((Long) null);
      var failure =
          assertThrows(
              BizException.class,
              () -> new SeckillQuotaSettlementService(mapper, stocks).settle(ORDER_NO, "ROLLBACK"));
      assertEquals(503, failure.getHttpStatus());
      assertEquals("SECKILL_SETTLEMENT_UNKNOWN", failure.getCode());
      verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
      verify(mapper).findSettlementForUpdate(ORDER_NO);
      verify(mapper).findMatchingAcceptanceId(same(original));
      verifyNoMoreInteractions(mapper);
      verifyNoInteractions(stocks);
    }
  }

  @Test
  void matchingSourceKeepsPendingUnknownResponseWithoutTransitionOrLeaseWrites() {
    for (String state : List.of("RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      var original = row(state);
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
      when(mapper.findMatchingAcceptanceId(same(original))).thenReturn(1L);
      var response =
          (Map<?, ?>)
              new SeckillQuotaSettlementService(mapper, stocks).settle(ORDER_NO, "ROLLBACK").data;
      assertEquals(false, response.get("rolledBack"));
      assertEquals(true, response.get("pending"));
      assertEquals("UNKNOWN", response.get("status"));
      verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
      verify(mapper).findSettlementForUpdate(ORDER_NO);
      verify(mapper).findMatchingAcceptanceId(same(original));
      verifyNoMoreInteractions(mapper);
      verifyNoInteractions(stocks);
    }
  }

  @Test
  void sourceReadErrorDoesNotBecomeAcceptedReplay() {
    var original = row("RELEASE_PENDING");
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
    var unavailable =
        new org.springframework.dao.DataAccessResourceFailureException("owned-source-failure");
    when(mapper.findMatchingAcceptanceId(same(original))).thenThrow(unavailable);
    assertSame(
        unavailable,
        assertThrows(
            RuntimeException.class,
            () -> new SeckillQuotaSettlementService(mapper, stocks).settle(ORDER_NO, "ROLLBACK")));
    verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
    verify(mapper).findSettlementForUpdate(ORDER_NO);
    verify(mapper).findMatchingAcceptanceId(same(original));
    verifyNoMoreInteractions(mapper);
    verifyNoInteractions(stocks);
  }

  @Test
  void priorProofOppositeDirectionAndInitialOpenCasDoNotGainReplayQueries() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    var service = new SeckillQuotaSettlementService(mapper, stocks);
    var original = row("RELEASE_PENDING");
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
    assertEquals(
        409,
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM"))
            .getHttpStatus());
    original.status = "PROJECTION_BLOCKED";
    original.operationKey = ORDER_NO + ":CONFIRM";
    assertEquals(
        409,
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "ROLLBACK"))
            .getHttpStatus());
    original.orderNo = "other";
    assertEquals(
        409,
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "ROLLBACK"))
            .getHttpStatus());
    original.orderNo = ORDER_NO;
    original.quantity = 2;
    assertEquals(
        "秒杀结算事实维度无效",
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "ROLLBACK")).getMessage());
    original.quantity = 1;
    original.operationKey = "other:ROLLBACK";
    assertEquals(
        "秒杀结算操作键缺证",
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "ROLLBACK")).getMessage());
    var open = row("OPEN");
    open.operationKey = null;
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(open);
    when(mapper.transitionToReleasePending(ORDER_NO, ORDER_NO + ":ROLLBACK", 2L)).thenReturn(1);
    assertEquals(true, ((Map<?, ?>) service.settle(ORDER_NO, "ROLLBACK").data).get("pending"));
    verify(mapper, never()).findMatchingAcceptanceId(any());
  }

  /** 合法待释放snapshot，仅测试明确修改的字段才变异。 */
  private static SeckillQuotaSettlementPO row(String state) {
    var settlement = new SeckillQuotaSettlementPO();
    settlement.orderNo = ORDER_NO;
    settlement.activityId = 10L;
    settlement.skuId = 20L;
    settlement.userId = 8L;
    settlement.idempotencyKey = "private-idem";
    settlement.quantity = 1;
    settlement.revision = 2L;
    settlement.status = state;
    settlement.operationKey = ORDER_NO + ":ROLLBACK";
    return settlement;
  }
}
