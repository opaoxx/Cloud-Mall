package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

/** 不支持的原状态不能输出未证明的明细；空结果仍是非null已观察候选。 */
class SeckillUnknownStatusReadTest {
  /** 当前原查询订单号。 */
  private static final String ORDER_NO = "unknown-status-order";

  @Test
  void unsupportedStatesCannotExposeItemsRegardlessOfSourcePresence() {
    for (String state : unsupportedStates())
      for (Long acceptedId : Arrays.asList(null, 1L)) {
        var mapper = mock(SeckillQuotaSettlementMapper.class);
        var stocks = mock(StockSqlMapper.class);
        var original = row(state);
        when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
        when(mapper.findMatchingAcceptanceId(any())).thenReturn(acceptedId);
        var result = new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO);
        assertNotNull(result);
        assertEquals(ORDER_NO, result.orderNo);
        assertEquals("UNKNOWN", result.status);
        assertTrue(result.items.isEmpty(), String.valueOf(state));
        assertEquals(state, original.status);
        verify(mapper).findSettlement(ORDER_NO);
        verifyNoMoreInteractions(mapper);
        verifyNoInteractions(stocks);
      }
  }

  @Test
  void unsupportedStateNeverQueriesSourceEvenIfThatQueryWouldFail() {
    for (String state : unsupportedStates()) {
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      when(mapper.findSettlement(ORDER_NO)).thenReturn(row(state));
      when(mapper.findMatchingAcceptanceId(any()))
          .thenThrow(new IllegalStateException("source must not be queried"));
      var result = new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO);
      assertEquals("UNKNOWN", result.status);
      assertTrue(result.items.isEmpty(), String.valueOf(state));
      verify(mapper).findSettlement(ORDER_NO);
      verifyNoMoreInteractions(mapper);
      verifyNoInteractions(stocks);
    }
  }

  @Test
  void originalIdentityDimensionsAndKnownProofsStillHavePriority() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    var service = new SeckillQuotaSettlementService(mapper, stocks);
    var original = row("unrecognized");
    when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
    original.orderNo = "other-order";
    original.quantity = 2;
    assertTrue(service.reservation(ORDER_NO).items.isEmpty());
    original.orderNo = ORDER_NO;
    var failure = assertThrows(BizException.class, () -> service.reservation(ORDER_NO));
    assertEquals(500, failure.getHttpStatus());
    assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
    original.quantity = 1;
    original.status = "OPEN";
    original.operationKey = "other:ROLLBACK";
    assertTrue(service.reservation(ORDER_NO).items.isEmpty());
    original.status = "RELEASED";
    original.operationKey = ORDER_NO + ":ROLLBACK";
    original.redisRevision = 0L;
    assertTrue(service.reservation(ORDER_NO).items.isEmpty());
    when(mapper.findSettlement(ORDER_NO)).thenReturn(null);
    when(stocks.findSeckillByOrderNo(ORDER_NO))
        .thenReturn(
            Map.of(
                "status",
                "ACCEPTED",
                "order_no",
                ORDER_NO,
                "activity_id",
                10L,
                "sku_id",
                20L,
                "user_id",
                8L,
                "idempotency_key",
                "private-idem"));
    assertEquals("RESERVED", service.reservation(ORDER_NO).status);
    verify(mapper, never()).findMatchingAcceptanceId(any());
  }

  @Test
  void allFiveKnownStatusesKeepOriginalItemsAndSourceObservation() {
    for (String state :
        List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED", "CONFIRMED", "RELEASED")) {
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      var original = row(state);
      when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
      when(mapper.findMatchingAcceptanceId(same(original))).thenReturn(1L);
      var result = new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO);
      assertEquals(
          "OPEN".equals(state)
              ? "RESERVED"
              : "CONFIRMED".equals(state)
                  ? "CONFIRMED"
                  : "RELEASED".equals(state) ? "ROLLED_BACK" : "UNKNOWN",
          result.status);
      assertEquals(1, result.items.size());
      assertEquals(20L, result.items.get(0).skuId);
      assertEquals(1, result.items.get(0).quantity);
      verify(mapper).findSettlement(ORDER_NO);
      verify(mapper).findMatchingAcceptanceId(same(original));
      verifyNoMoreInteractions(mapper);
      verifyNoInteractions(stocks);
    }
  }

  /** NULL仅unit；状态文本不规范化，错误大小写和首尾空白均不可当作五状态。 */
  private static List<String> unsupportedStates() {
    return Arrays.asList(
        null,
        "CONFLICT",
        "UNKNOWN",
        "unrecognized",
        "",
        " ",
        "open",
        "Open",
        "OPEN ",
        " OPEN",
        "confirmed",
        "RELEASED ",
        "release_pending",
        "projection_blocked");
  }

  /** 原基础tuple合法；只让状态越出协议，不用损坏维度掩盖待验证路径。 */
  private static SeckillQuotaSettlementPO row(String state) {
    var original = new SeckillQuotaSettlementPO();
    original.orderNo = ORDER_NO;
    original.activityId = 10L;
    original.skuId = 20L;
    original.userId = 8L;
    original.idempotencyKey = "private-idem";
    original.quantity = 1;
    original.revision = 2L;
    original.status = state;
    original.operationKey =
        "OPEN".equals(state)
            ? null
            : ORDER_NO + ("CONFIRMED".equals(state) ? ":CONFIRM" : ":ROLLBACK");
    original.redisRevision = "RELEASED".equals(state) ? 4L : null;
    return original;
  }
}
