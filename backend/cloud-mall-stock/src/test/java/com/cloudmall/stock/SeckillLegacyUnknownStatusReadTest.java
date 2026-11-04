package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.seckill.SeckillQuotaSettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

/** 历史未知状态只能保留非null UNKNOWN候选，不能返回未证明的SKU。 */
class SeckillLegacyUnknownStatusReadTest {
  /** 原查询订单。 */
  private static final String ORDER_NO = "legacy-unknown-order";

  @Test
  void bareLegacyUnsupportedStatesDoNotExposeItemsOrQueryOrdinaryStock() {
    for (String state : unsupportedStates()) {
      var stocks = mock(StockSqlMapper.class);
      var original = row(state);
      when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(original);
      var result =
          (StockReservationVO)
              StockTestServices.reader(stocks, new ObjectMapper()).reservation(ORDER_NO);
      assertNotNull(result);
      assertEquals(ORDER_NO, result.orderNo);
      assertEquals("UNKNOWN", result.status);
      assertTrue(result.items.isEmpty(), String.valueOf(state));
      assertEquals(state, original.get("status"));
      verify(stocks).findSeckillByOrderNo(ORDER_NO);
      verifyNoMoreInteractions(stocks);
    }
  }

  @Test
  void primaryNonAcceptedNullStillStopsAtOuterUnknownWithoutNormalFallback() {
    for (String state : unsupportedStates()) {
      var stocks = mock(StockSqlMapper.class);
      var settlements = mock(SeckillQuotaSettlementMapper.class);
      when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(row(state));
      var primary = new SeckillQuotaSettlementService(settlements, stocks);
      var outer = StockTestServices.reader(stocks, new ObjectMapper());
      ReflectionTestUtils.setField(outer, "seckillSettlementService", primary);
      var result = (StockReservationVO) outer.reservation(ORDER_NO);
      assertEquals("UNKNOWN", result.status);
      assertTrue(result.items.isEmpty(), String.valueOf(state));
      verify(settlements).findSettlement(ORDER_NO);
      verify(stocks, times(2)).findSeckillByOrderNo(ORDER_NO);
      verifyNoMoreInteractions(stocks, settlements);
    }
  }

  @Test
  void exactAcceptedAndRejectedKeepTheirOriginalDetailsAndQueryProtocol() {
    for (String state : List.of("ACCEPTED", "REJECTED")) {
      var stocks = mock(StockSqlMapper.class);
      var settlements = mock(SeckillQuotaSettlementMapper.class);
      when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(row(state));
      var bare = StockTestServices.reader(stocks, new ObjectMapper());
      var result = (StockReservationVO) bare.reservation(ORDER_NO);
      assertEquals("ACCEPTED".equals(state) ? "RESERVED" : "REJECTED", result.status);
      assertEquals(1, result.items.size());
      assertEquals(7L, result.items.get(0).skuId);
      assertEquals(1, result.items.get(0).quantity);
      clearInvocations(stocks);
      ReflectionTestUtils.setField(
          bare, "seckillSettlementService", new SeckillQuotaSettlementService(settlements, stocks));
      var configured = (StockReservationVO) bare.reservation(ORDER_NO);
      assertEquals(result.status, configured.status);
      assertEquals(7L, configured.items.get(0).skuId);
      verify(settlements).findSettlement(ORDER_NO);
      verify(stocks, times("ACCEPTED".equals(state) ? 1 : 2)).findSeckillByOrderNo(ORDER_NO);
      verifyNoMoreInteractions(stocks, settlements);
    }
  }

  @Test
  void originalFieldPriorityMissingStatusAndDependencyFailureRemain() {
    var stocks = mock(StockSqlMapper.class);
    var settlements = mock(SeckillQuotaSettlementMapper.class);
    var original = row("ACCEPTED");
    original.put("order_no", "other");
    original.put("sku_id", 0L);
    when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(original);
    var primary = new SeckillQuotaSettlementService(settlements, stocks);
    assertTrue(primary.reservation(ORDER_NO).items.isEmpty());
    original.put("order_no", ORDER_NO);
    assertEquals(
        "秒杀受理事实 sku_id 无效",
        assertThrows(BizException.class, () -> primary.reservation(ORDER_NO)).getMessage());
    original.put("sku_id", 7L);
    original.put("user_id", 0L);
    assertEquals(
        "秒杀受理事实维度无效",
        assertThrows(BizException.class, () -> primary.reservation(ORDER_NO)).getMessage());
    original.put("user_id", 8L);
    original.remove("status");
    var bare = StockTestServices.reader(stocks, new ObjectMapper());
    assertTrue(((StockReservationVO) bare.reservation(ORDER_NO)).items.isEmpty());
    var failure =
        new org.springframework.dao.DataAccessResourceFailureException(
            "owned-legacy-query-failure");
    when(stocks.findSeckillByOrderNo(ORDER_NO)).thenThrow(failure);
    assertSame(failure, assertThrows(RuntimeException.class, () -> bare.reservation(ORDER_NO)));
    verify(settlements, never()).findMatchingAcceptanceId(any());
  }

  /** NULL/缺status仅unit，不修改原DDL；文本不做trim或casefold。 */
  private static List<String> unsupportedStates() {
    return Arrays.asList(
        null,
        "PENDING",
        "PUBLISHED",
        "ORDER_CREATED",
        "UNKNOWN",
        "CONFLICT",
        "",
        " ",
        "accepted",
        "Accepted",
        "ACCEPTED ",
        " ACCEPTED",
        "rejected",
        "REJECTED ");
  }

  /** 保留原字段类型，原order/SKU/context有效时专门验证状态边界。 */
  private static Map<String, Object> row(String state) {
    Map<String, Object> original = new HashMap<>();
    original.put("order_no", ORDER_NO);
    original.put("sku_id", 7L);
    original.put("activity_id", 10L);
    original.put("user_id", 8L);
    original.put("idempotency_key", "private-idem");
    original.put("status", state);
    return original;
  }
}
