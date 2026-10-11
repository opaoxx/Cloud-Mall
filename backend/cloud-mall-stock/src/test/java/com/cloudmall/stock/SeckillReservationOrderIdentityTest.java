package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;
import org.springframework.test.util.ReflectionTestUtils;

/** 秒杀GET不得把CI/错身份候选包装成当前订单的持久事实，未注入任何写资源。 */
class SeckillReservationOrderIdentityTest {
  /** 可信请求订单号，仅用于读取原候选。 */
  private static final String ORDER_NO = "202610proof1";

  @Test
  void settlementAliasesNeverProveCurrentOrderOrFallThroughToNormalStock() {
    List<Executable> assertions = new ArrayList<>();
    for (String state : List.of("OPEN", "CONFIRMED", "RELEASED"))
      for (String original :
          java.util.Arrays.asList("202610PROOF1", "202610proof1 ", "202610próof1", "other", null))
        assertions.add(
            () -> {
              var settlements = mock(SeckillQuotaSettlementMapper.class);
              var stocks = mock(StockSqlMapper.class);
              when(settlements.findSettlement(ORDER_NO)).thenReturn(row(original, state));
              var primary = new SeckillQuotaSettlementService(settlements, stocks);
              var outer = new StockServiceImpl(null, stocks, null, new ObjectMapper());
              ReflectionTestUtils.setField(outer, "seckillSettlementService", primary);
              assertUnknown((StockReservationVO) outer.reservation(ORDER_NO).data);
              verifyNoInteractions(stocks);
              verify(settlements).findSettlement(ORDER_NO);
              verifyNoMoreInteractions(settlements);
            });
    assertAll(assertions);
  }

  @Test
  void acceptedLegacyAliasOrMissingIdentityNeverBecomesReserved() {
    List<Executable> assertions = new ArrayList<>();
    for (Object original :
        java.util.Arrays.asList("202610PROOF1", "202610proof1 ", "other", null, 7L))
      assertions.add(
          () -> {
            var settlements = mock(SeckillQuotaSettlementMapper.class);
            var stocks = mock(StockSqlMapper.class);
            when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(legacy(original, "ACCEPTED"));
            assertUnknown(
                new SeckillQuotaSettlementService(settlements, stocks).reservation(ORDER_NO));
            verify(settlements).findSettlement(ORDER_NO);
            verify(stocks).findSeckillByOrderNo(ORDER_NO);
            verifyNoMoreInteractions(settlements, stocks);
          });
    assertAll(assertions);
  }

  @Test
  void legacyOuterFallbackNeverBorrowAnotherOrderForAnyAcceptanceState() {
    List<Executable> assertions = new ArrayList<>();
    for (String state : List.of("ACCEPTED", "REJECTED", "PENDING"))
      assertions.add(
          () -> {
            var stocks = mock(StockSqlMapper.class);
            when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(legacy("202610PROOF1", state));
            var outer = new StockServiceImpl(null, stocks, null, new ObjectMapper());
            assertUnknown((StockReservationVO) outer.reservation(ORDER_NO).data);
            verify(stocks).findSeckillByOrderNo(ORDER_NO);
            verifyNoMoreInteractions(stocks);
          });
    assertAll(assertions);
  }

  @Test
  void exactOriginalIdentitiesKeepStatesQuantitiesAndMissingNormalRoute() {
    for (String state : List.of("OPEN", "CONFIRMED", "RELEASED", "PROJECTION_BLOCKED")) {
      var settlements = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      when(settlements.findSettlement(ORDER_NO)).thenReturn(row(ORDER_NO, state));
      when(settlements.findMatchingAcceptanceId(any())).thenReturn(1L);
      var result = new SeckillQuotaSettlementService(settlements, stocks).reservation(ORDER_NO);
      assertEquals(
          switch (state) {
            case "OPEN" -> "RESERVED";
            case "CONFIRMED" -> "CONFIRMED";
            case "RELEASED" -> "ROLLED_BACK";
            default -> "UNKNOWN";
          },
          result.status);
      assertEquals(1, result.items.size());
      assertEquals(7L, result.items.get(0).skuId);
      assertEquals(1, result.items.get(0).quantity);
      verifyNoInteractions(stocks);
    }
    var settlements = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    var primary = new SeckillQuotaSettlementService(settlements, stocks);
    assertNull(primary.reservation(ORDER_NO));
    when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(legacy(ORDER_NO, "ACCEPTED"));
    assertEquals("RESERVED", primary.reservation(ORDER_NO).status);
    var outer = new StockServiceImpl(null, stocks, null, new ObjectMapper());
    assertEquals("RESERVED", ((StockReservationVO) outer.reservation(ORDER_NO).data).status);
  }

  @Test
  void wrongSettlementIdentityHasPriorityOverUnprovenDimensions() {
    var settlements = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    SeckillQuotaSettlementPO broken = new SeckillQuotaSettlementPO();
    broken.orderNo = "other";
    when(settlements.findSettlement(ORDER_NO)).thenReturn(broken);
    assertUnknown(new SeckillQuotaSettlementService(settlements, stocks).reservation(ORDER_NO));
    verifyNoInteractions(stocks);
  }

  /** 原订单只作为请求标识返回；不保留错原身份或部分SKU。 */
  private static void assertUnknown(StockReservationVO result) {
    assertNotNull(result);
    assertEquals(ORDER_NO, result.orderNo);
    assertEquals("UNKNOWN", result.status);
    assertTrue(result.items.isEmpty());
  }

  /** 维度完整原settlement事实，不调用写入接口准备数据。 */
  private static SeckillQuotaSettlementPO row(String original, String state) {
    var row = new SeckillQuotaSettlementPO();
    row.orderNo = original;
    row.activityId = 10L;
    row.skuId = 7L;
    row.userId = 30L;
    row.idempotencyKey = "private-idem";
    row.quantity = 1;
    row.status = state;
    row.revision = 1L;
    if ("RELEASED".equals(state)) row.redisRevision = 4L;
    if ("CONFIRMED".equals(state)) row.operationKey = original + ":CONFIRM";
    if ("RELEASED".equals(state)
        || "RELEASE_PENDING".equals(state)
        || "PROJECTION_BLOCKED".equals(state)) row.operationKey = original + ":ROLLBACK";
    return row;
  }

  /** 使用可null的原Map模拟候选事实，不默认补原订单号。 */
  private static Map<String, Object> legacy(Object original, String state) {
    Map<String, Object> row = new HashMap<>();
    row.put("order_no", original);
    row.put("sku_id", 7L);
    row.put("status", state);
    return row;
  }
}
