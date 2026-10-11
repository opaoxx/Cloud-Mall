package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** 历史受理上下文必须保原类型/边界；通过业务接缝而非镜像validator证明。 */
class SeckillLegacyContextDimensionsTest {
  /** 原订单请求。 */
  private static final String ORDER_NO = "legacy-context-order";

  @Test
  void acceptedPrimaryCannotReportReservedWithMissingOrInvalidOriginalContext() {
    List<Executable> assertions = new ArrayList<>();
    for (Map<String, Object> original : badContexts())
      assertions.add(
          () -> {
            var mapper = mock(SeckillQuotaSettlementMapper.class);
            var stocks = mock(StockSqlMapper.class);
            when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(original);
            var failure =
                assertThrows(
                    BizException.class,
                    () -> new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO));
            assertEquals(500, failure.getHttpStatus());
            assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
            assertEquals("秒杀受理事实维度无效", failure.getMessage());
            verify(mapper).findSettlement(ORDER_NO);
            verify(stocks).findSeckillByOrderNo(ORDER_NO);
            verifyNoMoreInteractions(mapper, stocks);
          });
    assertAll(assertions);
  }

  @Test
  void bareFallbackCannotTrustAnyLegacyStateWithInvalidContext() {
    List<Executable> assertions = new ArrayList<>();
    for (Map<String, Object> original : badContexts())
      for (String state : List.of("ACCEPTED", "REJECTED", "PENDING"))
        assertions.add(
            () -> {
              var candidate = new HashMap<>(original);
              candidate.put("status", state);
              var stocks = mock(StockSqlMapper.class);
              when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(candidate);
              var result =
                  (StockReservationVO)
                      new StockServiceImpl(null, stocks, null, new ObjectMapper())
                          .reservation(ORDER_NO)
                          .data;
              assertEquals("UNKNOWN", result.status);
              assertTrue(result.items.isEmpty());
              assertEquals(ORDER_NO, result.orderNo);
              verify(stocks).findSeckillByOrderNo(ORDER_NO);
              verifyNoMoreInteractions(stocks);
            });
    assertAll(assertions);
  }

  @Test
  void exactNumericAndUnicodeBoundariesPreserveStatesWithoutNormalizingFields() {
    for (Number identity :
        List.of(
            (byte) 7,
            (short) 7,
            7,
            7L,
            BigInteger.valueOf(7),
            new BigDecimal("7.000"),
            Long.MAX_VALUE,
            new BigInteger("9223372036854775807"),
            new BigDecimal("9223372036854775807.000")))
      for (String key :
          List.of(
              "private-idem", " private-idem ", "NULL", "null ", "x".repeat(128), "😺".repeat(128)))
        for (String state : List.of("ACCEPTED", "REJECTED", "PENDING")) {
          var original = row();
          original.put("activity_id", identity);
          original.put("user_id", identity);
          original.put("idempotency_key", key);
          original.put("status", state);
          var stocks = mock(StockSqlMapper.class);
          when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(original);
          var expected =
              "ACCEPTED".equals(state)
                  ? "RESERVED"
                  : "REJECTED".equals(state) ? "REJECTED" : "UNKNOWN";
          var bare =
              (StockReservationVO)
                  new StockServiceImpl(null, stocks, null, new ObjectMapper())
                      .reservation(ORDER_NO)
                      .data;
          assertEquals(expected, bare.status);
          assertEquals(7L, bare.items.get(0).skuId);
          var primary =
              new SeckillQuotaSettlementService(mock(SeckillQuotaSettlementMapper.class), stocks)
                  .reservation(ORDER_NO);
          if ("ACCEPTED".equals(state)) assertEquals("RESERVED", primary.status);
          else assertNull(primary);
          assertEquals(key, original.get("idempotency_key"));
          assertEquals(identity, original.get("activity_id"));
          assertEquals(identity, original.get("user_id"));
        }
  }

  @Test
  void originalOrderAndSkuPriorityAndDependencyErrorsRemainUnchanged() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    var original = row();
    original.put("activity_id", null);
    original.put("user_id", -1L);
    original.put("idempotency_key", null);
    original.put("sku_id", 0L);
    original.put("order_no", "other");
    when(stocks.findSeckillByOrderNo(ORDER_NO)).thenReturn(original);
    var primary = new SeckillQuotaSettlementService(mapper, stocks);
    assertEquals("UNKNOWN", primary.reservation(ORDER_NO).status);
    original.put("order_no", ORDER_NO);
    assertEquals(
        "秒杀受理事实 sku_id 无效",
        assertThrows(BizException.class, () -> primary.reservation(ORDER_NO)).getMessage());
    var bare =
        (StockReservationVO)
            new StockServiceImpl(null, stocks, null, new ObjectMapper()).reservation(ORDER_NO).data;
    assertEquals("UNKNOWN", bare.status);
    assertTrue(bare.items.isEmpty());
    original.put("status", "REJECTED");
    assertNull(primary.reservation(ORDER_NO));
    var unavailable =
        new org.springframework.dao.DataAccessResourceFailureException("owned-failure");
    when(stocks.findSeckillByOrderNo(ORDER_NO)).thenThrow(unavailable);
    assertSame(
        unavailable, assertThrows(RuntimeException.class, () -> primary.reservation(ORDER_NO)));
  }

  /** Map原字段类型保留；非法组合不在夹具里先修正。 */
  private static List<Map<String, Object>> badContexts() {
    var candidates = new ArrayList<Map<String, Object>>();
    for (String field : List.of("activity_id", "user_id"))
      for (Object value :
          Arrays.asList(
              null,
              0L,
              -1L,
              new BigDecimal("7.9"),
              new BigInteger("9223372036854775808"),
              7.0f,
              7.0d,
              Double.NaN,
              Double.POSITIVE_INFINITY,
              "7",
              true)) {
        var candidate = row();
        candidate.put(field, value);
        candidates.add(candidate);
      }
    for (Object value :
        Arrays.asList(
            null, "", " ", "\u2003", "null", "x".repeat(129), "😺".repeat(129), 7L, true)) {
      var candidate = row();
      candidate.put("idempotency_key", value);
      candidates.add(candidate);
    }
    return candidates;
  }

  /** 身份/SKU/context原本合法，仅由测试变异指定字段。 */
  private static Map<String, Object> row() {
    Map<String, Object> original = new HashMap<>();
    original.put("order_no", ORDER_NO);
    original.put("status", "ACCEPTED");
    original.put("sku_id", 7L);
    original.put("activity_id", 10L);
    original.put("user_id", 8L);
    original.put("idempotency_key", "private-idem");
    return original;
  }
}
