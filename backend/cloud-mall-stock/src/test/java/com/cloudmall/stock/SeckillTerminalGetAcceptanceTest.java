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

/** 终态查询必须观察到同tuple原受理来源；不把两个点读称为原子证明。 */
class SeckillTerminalGetAcceptanceTest {
  /** 请求的原订单号。 */
  private static final String ORDER_NO = "terminal-source-order";

  @Test
  void missingMatchingSourceCannotReportTerminalOrAllowOrdinaryFallback() {
    for (String state : List.of("CONFIRMED", "RELEASED")) {
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      var original = row(state);
      when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
      when(mapper.findMatchingAcceptanceId(same(original))).thenReturn((Long) null);
      var result = new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO);
      assertEquals("UNKNOWN", result.status, state);
      assertEquals(ORDER_NO, result.orderNo);
      assertTrue(result.items.isEmpty());
      verify(mapper).findSettlement(ORDER_NO);
      verify(mapper).findMatchingAcceptanceId(same(original));
      verifyNoMoreInteractions(mapper);
      verifyNoInteractions(stocks);
    }
  }

  @Test
  void matchingSourceKeepsTerminalItemsAndUsesTheOriginalSnapshot() {
    for (String state : List.of("CONFIRMED", "RELEASED")) {
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      var original = row(state);
      when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
      when(mapper.findMatchingAcceptanceId(same(original))).thenReturn(1L);
      var result = new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO);
      assertEquals("CONFIRMED".equals(state) ? "CONFIRMED" : "ROLLED_BACK", result.status);
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
  void sourceReadFailurePropagatesWithoutFalseCompletionOrFallback() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    var original = row("CONFIRMED");
    when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
    var unavailable =
        new org.springframework.dao.DataAccessResourceFailureException("owned-query-failure");
    when(mapper.findMatchingAcceptanceId(same(original))).thenThrow(unavailable);
    assertSame(
        unavailable,
        assertThrows(
            RuntimeException.class,
            () -> new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO)));
    verify(mapper).findSettlement(ORDER_NO);
    verify(mapper).findMatchingAcceptanceId(same(original));
    verifyNoMoreInteractions(mapper);
    verifyNoInteractions(stocks);
  }

  @Test
  void existingProofPriorityAndOtherGetPathsDoNotGainSourceQueries() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    var service = new SeckillQuotaSettlementService(mapper, stocks);
    var original = row("CONFIRMED");
    when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
    original.orderNo = "other";
    assertEquals("UNKNOWN", service.reservation(ORDER_NO).status);
    original.orderNo = ORDER_NO;
    original.quantity = 2;
    assertEquals(
        "秒杀结算事实维度无效",
        assertThrows(BizException.class, () -> service.reservation(ORDER_NO)).getMessage());
    original.quantity = 1;
    original.operationKey = "other:CONFIRM";
    assertEquals("UNKNOWN", service.reservation(ORDER_NO).status);
    original.status = "RELEASED";
    original.operationKey = ORDER_NO + ":ROLLBACK";
    original.redisRevision = null;
    assertEquals("UNKNOWN", service.reservation(ORDER_NO).status);
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      var healthyMapper = mock(SeckillQuotaSettlementMapper.class);
      var healthy = row(state);
      when(healthyMapper.findSettlement(ORDER_NO)).thenReturn(healthy);
      when(healthyMapper.findMatchingAcceptanceId(same(healthy))).thenReturn(1L);
      var healthyResult =
          new SeckillQuotaSettlementService(healthyMapper, mock(StockSqlMapper.class))
              .reservation(ORDER_NO);
      assertEquals("OPEN".equals(state) ? "RESERVED" : "UNKNOWN", healthyResult.status);
      assertEquals(1, healthyResult.items.size());
      verify(healthyMapper).findSettlement(ORDER_NO);
      verify(healthyMapper).findMatchingAcceptanceId(same(healthy));
      verifyNoMoreInteractions(healthyMapper);
    }
    for (String state : List.of("CONFLICT", "unrecognized")) {
      var unknownMapper = mock(SeckillQuotaSettlementMapper.class);
      when(unknownMapper.findSettlement(ORDER_NO)).thenReturn(row(state));
      var unknownResult =
          new SeckillQuotaSettlementService(unknownMapper, mock(StockSqlMapper.class))
              .reservation(ORDER_NO);
      assertEquals("UNKNOWN", unknownResult.status);
      assertTrue(unknownResult.items.isEmpty());
      verify(unknownMapper, never()).findMatchingAcceptanceId(any());
    }
    var legacyMapper = mock(SeckillQuotaSettlementMapper.class);
    var legacyStocks = mock(StockSqlMapper.class);
    when(legacyStocks.findSeckillByOrderNo(ORDER_NO))
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
    assertEquals(
        "RESERVED",
        new SeckillQuotaSettlementService(legacyMapper, legacyStocks).reservation(ORDER_NO).status);
    verify(legacyMapper, never()).findMatchingAcceptanceId(any());
  }

  /** 原基础事实合法，终态key/receipt保持原规范。 */
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
    settlement.operationKey =
        "OPEN".equals(state)
            ? null
            : ORDER_NO + ("CONFIRMED".equals(state) ? ":CONFIRM" : ":ROLLBACK");
    settlement.redisRevision = "RELEASED".equals(state) ? 4L : null;
    return settlement;
  }
}
