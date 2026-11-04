package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.seckill.SeckillQuotaSettlementService;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 非终态明细也必须观察到对应原受理来源；只检当前source，不冒称全因果。 */
class SeckillNonterminalGetAcceptanceTest {
  /** 原查询订单号。 */
  private static final String ORDER_NO = "nonterminal-source-order";

  @Test
  void missingSourceCannotExposeReservationItemsOrFallBackToOrdinaryStock() {
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      var original = row(state);
      when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
      when(mapper.findMatchingAcceptanceId(same(original))).thenReturn((Long) null);
      var result = new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO);
      assertEquals("UNKNOWN", result.status);
      assertEquals(ORDER_NO, result.orderNo);
      assertTrue(result.items.isEmpty());
      verify(mapper).findSettlement(ORDER_NO);
      verify(mapper).findMatchingAcceptanceId(same(original));
      verifyNoMoreInteractions(mapper);
      verifyNoInteractions(stocks);
    }
  }

  @Test
  void matchingSourceKeepsAllThreeStatusesAndTheOriginalItems() {
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      var original = row(state);
      when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
      when(mapper.findMatchingAcceptanceId(same(original))).thenReturn(1L);
      var result = new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO);
      assertEquals("OPEN".equals(state) ? "RESERVED" : "UNKNOWN", result.status);
      assertEquals(1, result.items.size());
      assertEquals(20L, result.items.get(0).skuId);
      assertEquals(1, result.items.get(0).quantity);
      verify(mapper).findSettlement(ORDER_NO);
      verify(mapper).findMatchingAcceptanceId(same(original));
      verifyNoMoreInteractions(mapper);
      verifyNoInteractions(stocks);
    }
  }

  @Test
  void sourceLookupFailureRemainsAnErrorForEachNewState() {
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      var original = row(state);
      when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
      var failure =
          new org.springframework.dao.DataAccessResourceFailureException("owned-source-failure");
      when(mapper.findMatchingAcceptanceId(same(original))).thenThrow(failure);
      assertSame(
          failure,
          assertThrows(
              RuntimeException.class,
              () -> new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO)));
      verify(mapper).findSettlement(ORDER_NO);
      verify(mapper).findMatchingAcceptanceId(same(original));
      verifyNoMoreInteractions(mapper);
      verifyNoInteractions(stocks);
    }
  }

  @Test
  void existingProofPriorityUnknownAndLegacyPathsRemainOutsideNewQueries() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    var service = new SeckillQuotaSettlementService(mapper, stocks);
    var original = row("OPEN");
    when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
    original.orderNo = "other";
    assertEquals("UNKNOWN", service.reservation(ORDER_NO).status);
    original.orderNo = ORDER_NO;
    original.quantity = 2;
    assertEquals(
        "秒杀结算事实维度无效",
        assertThrows(BizException.class, () -> service.reservation(ORDER_NO)).getMessage());
    original.quantity = 1;
    original.operationKey = "other:ROLLBACK";
    assertTrue(service.reservation(ORDER_NO).items.isEmpty());
    for (String state : List.of("CONFLICT", "unrecognized")) {
      when(mapper.findSettlement(ORDER_NO)).thenReturn(row(state));
      var observed = service.reservation(ORDER_NO);
      assertEquals("UNKNOWN", observed.status);
      assertTrue(observed.items.isEmpty());
    }
    when(mapper.findSettlement(ORDER_NO)).thenReturn(null);
    when(stocks.findSeckillByOrderNo(ORDER_NO))
        .thenReturn(
            Map.of(
                "status",
                "ACCEPTED",
                "order_no",
                ORDER_NO,
                "sku_id",
                20L,
                "activity_id",
                10L,
                "user_id",
                8L,
                "idempotency_key",
                "private-idem"));
    assertEquals("RESERVED", service.reservation(ORDER_NO).status);
    verify(mapper, never()).findMatchingAcceptanceId(any());
  }

  /** 合法原结算快照，pending/blocked沿原rollback键。 */
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
    settlement.operationKey = "OPEN".equals(state) ? null : ORDER_NO + ":ROLLBACK";
    return settlement;
  }
}
