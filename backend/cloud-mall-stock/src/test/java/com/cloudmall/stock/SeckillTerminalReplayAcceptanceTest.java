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

/** 同向终态重放不能只凭标签成功；source观察不冒称源事实冻结。 */
class SeckillTerminalReplayAcceptanceTest {
  /** 原请求订单。 */
  private static final String ORDER_NO = "terminal-replay-order";

  @Test
  void missingSourceCannotAcknowledgeSameDirectionReplay() {
    for (String state : List.of("CONFIRMED", "RELEASED")) {
      var original = row(state);
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
      when(mapper.findMatchingAcceptanceId(same(original))).thenReturn((Long) null);
      var service = new SeckillQuotaSettlementService(mapper, stocks);
      var failure = assertThrows(BizException.class, () -> service.settle(ORDER_NO, action(state)));
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
  void matchingSourceKeepsSameDirectionNoopWithoutAnyCas() {
    for (String state : List.of("CONFIRMED", "RELEASED")) {
      var original = row(state);
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
      when(mapper.findMatchingAcceptanceId(same(original))).thenReturn(1L);
      var response =
          (Map<?, ?>)
              new SeckillQuotaSettlementService(mapper, stocks)
                  .settle(ORDER_NO, action(state))
                  .data;
      assertEquals(true, response.get("idempotent"));
      assertEquals(true, response.get("CONFIRMED".equals(state) ? "confirmed" : "rolledBack"));
      verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
      verify(mapper).findSettlementForUpdate(ORDER_NO);
      verify(mapper).findMatchingAcceptanceId(same(original));
      verifyNoMoreInteractions(mapper);
      verifyNoInteractions(stocks);
    }
  }

  @Test
  void sourceQueryFailureRemainsAnErrorWithoutFalseSuccess() {
    var original = row("CONFIRMED");
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
            () -> new SeckillQuotaSettlementService(mapper, stocks).settle(ORDER_NO, "CONFIRM")));
    verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
    verify(mapper).findSettlementForUpdate(ORDER_NO);
    verify(mapper).findMatchingAcceptanceId(same(original));
    verifyNoMoreInteractions(mapper);
    verifyNoInteractions(stocks);
  }

  @Test
  void oppositeDirectionAndEarlierProofFailuresDoNotQuerySource() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    var service = new SeckillQuotaSettlementService(mapper, stocks);
    for (String state : List.of("CONFIRMED", "RELEASED")) {
      when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row(state));
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () ->
                      service.settle(ORDER_NO, "CONFIRMED".equals(state) ? "ROLLBACK" : "CONFIRM"))
              .getHttpStatus());
    }
    var original = row("CONFIRMED");
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
    original.orderNo = "other";
    assertEquals(
        409,
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM"))
            .getHttpStatus());
    original.orderNo = ORDER_NO;
    original.quantity = 2;
    assertEquals(
        "秒杀结算事实维度无效",
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM")).getMessage());
    original.quantity = 1;
    original.operationKey = "other:CONFIRM";
    assertEquals(
        "秒杀结算操作键缺证",
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM")).getMessage());
    original.status = "RELEASED";
    original.operationKey = ORDER_NO + ":ROLLBACK";
    original.redisRevision = null;
    assertEquals(
        "秒杀释放回执缺证",
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "ROLLBACK")).getMessage());
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row("RELEASE_PENDING"));
    assertEquals(true, ((Map<?, ?>) service.settle(ORDER_NO, "ROLLBACK").data).get("pending"));
    var open = row("OPEN");
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(open);
    when(mapper.transitionOpenToConfirmed(ORDER_NO, ORDER_NO + ":CONFIRM", 2L)).thenReturn(1);
    assertEquals(true, ((Map<?, ?>) service.settle(ORDER_NO, "CONFIRM").data).get("confirmed"));
    verify(mapper, never()).findMatchingAcceptanceId(any());
  }

  /** 两种同向动作的固定原协议。 */
  private static String action(String state) {
    return "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
  }

  /** 基础身份/维度/key/receipt均合法，只在测试明确要求时变异。 */
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
    settlement.operationKey = "OPEN".equals(state) ? null : ORDER_NO + ":" + action(state);
    settlement.redisRevision = "RELEASED".equals(state) ? 4L : null;
    return settlement;
  }
}
