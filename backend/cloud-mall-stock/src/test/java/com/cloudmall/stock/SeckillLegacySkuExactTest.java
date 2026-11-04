package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
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

/** legacy SKU必须是无损正long，不允许读取时把坏历史字段截成另一商品。 */
class SeckillLegacySkuExactTest {
  /** 本次原订单号。 */
  private static final String ORDER_NO = "202610proof1";

  @Test
  void primaryAcceptedCannotTruncateWrapOrTrustFloatingIdentity() {
    List<Executable> assertions = new ArrayList<>();
    for (Object value : invalidValues())
      assertions.add(
          () -> {
            var stock = mock(StockSqlMapper.class);
            var settlements = mock(SeckillQuotaSettlementMapper.class);
            when(stock.findSeckillByOrderNo(ORDER_NO))
                .thenReturn(legacy(ORDER_NO, "ACCEPTED", value));
            var service = new SeckillQuotaSettlementService(settlements, stock);
            var failure =
                assertThrows(
                    BizException.class, () -> service.reservation(ORDER_NO), String.valueOf(value));
            assertEquals(500, failure.getHttpStatus());
            assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
            assertFalse(failure.getMessage().contains("private-secret"));
            verify(settlements).findSettlement(ORDER_NO);
            verify(stock).findSeckillByOrderNo(ORDER_NO);
            verifyNoMoreInteractions(settlements, stock);
          });
    assertAll(assertions);
  }

  @Test
  void bareLegacyFallbackNeverReportsBadSkuForAnyAcceptanceState() {
    List<Executable> assertions = new ArrayList<>();
    for (Object value : invalidValues())
      for (String state : List.of("ACCEPTED", "REJECTED", "PENDING"))
        assertions.add(
            () -> {
              var stock = mock(StockSqlMapper.class);
              when(stock.findSeckillByOrderNo(ORDER_NO)).thenReturn(legacy(ORDER_NO, state, value));
              var service = StockTestServices.reader(stock, new ObjectMapper());
              StockReservationVO result = (StockReservationVO) service.reservation(ORDER_NO);
              assertEquals("UNKNOWN", result.status, value + ":" + state);
              assertTrue(result.items.isEmpty());
              assertEquals(ORDER_NO, result.orderNo);
              verify(stock).findSeckillByOrderNo(ORDER_NO);
              verifyNoMoreInteractions(stock);
            });
    assertAll(assertions);
  }

  @Test
  void exactIntegerRepresentationsAndLongMaximumPreserveExistingSkuAndStates() {
    for (Number value :
        List.of(
            (byte) 7,
            (short) 7,
            7,
            7L,
            BigInteger.valueOf(7),
            new BigDecimal("7.000"),
            Long.MAX_VALUE,
            new BigInteger("9223372036854775807"),
            new BigDecimal("9223372036854775807.000"))) {
      long expected = value.toString().startsWith("922337") ? Long.MAX_VALUE : 7L;
      var stock = mock(StockSqlMapper.class);
      var settlements = mock(SeckillQuotaSettlementMapper.class);
      when(stock.findSeckillByOrderNo(ORDER_NO)).thenReturn(legacy(ORDER_NO, "ACCEPTED", value));
      var primary = new SeckillQuotaSettlementService(settlements, stock).reservation(ORDER_NO);
      assertEquals("RESERVED", primary.status);
      assertEquals(expected, primary.items.get(0).skuId);
      assertEquals(1, primary.items.get(0).quantity);
      for (String state : List.of("ACCEPTED", "REJECTED", "PENDING")) {
        when(stock.findSeckillByOrderNo(ORDER_NO)).thenReturn(legacy(ORDER_NO, state, value));
        var result =
            (StockReservationVO)
                StockTestServices.reader(stock, new ObjectMapper()).reservation(ORDER_NO);
        assertEquals(
            "ACCEPTED".equals(state)
                ? "RESERVED"
                : "REJECTED".equals(state) ? "REJECTED" : "UNKNOWN",
            result.status);
        if ("PENDING".equals(state)) {
          assertTrue(result.items.isEmpty());
        } else {
          assertEquals(expected, result.items.get(0).skuId);
          assertEquals(1, result.items.get(0).quantity);
        }
      }
    }
  }

  @Test
  void originalOrderIdentityStillHasPriorityOverMalformedSku() {
    var stock = mock(StockSqlMapper.class);
    var settlements = mock(SeckillQuotaSettlementMapper.class);
    when(stock.findSeckillByOrderNo(ORDER_NO))
        .thenReturn(legacy("202610PROOF1", "ACCEPTED", new BigDecimal("7.9")));
    var primary = new SeckillQuotaSettlementService(settlements, stock).reservation(ORDER_NO);
    assertEquals("UNKNOWN", primary.status);
    assertTrue(primary.items.isEmpty());
    var fallback =
        (StockReservationVO)
            StockTestServices.reader(stock, new ObjectMapper()).reservation(ORDER_NO);
    assertEquals("UNKNOWN", fallback.status);
    assertTrue(fallback.items.isEmpty());
  }

  /** 包含当前BIGINT非法值与只能由遗留列/坏Mapper返回的非整数/超long原输入。 */
  private static List<Object> invalidValues() {
    return Arrays.asList(
        null,
        0L,
        -1L,
        new BigDecimal("7.9"),
        new BigInteger("18446744073709551623"),
        7.0f,
        7.0d,
        7.9f,
        7.9d,
        Double.NaN,
        Double.POSITIVE_INFINITY,
        "private-secret",
        true);
  }

  /** 保留原字段类型与null，不在夹具中预先修正SKU。 */
  private static Map<String, Object> legacy(String orderNo, String state, Object sku) {
    Map<String, Object> row = new HashMap<>();
    row.put("order_no", orderNo);
    row.put("status", state);
    row.put("sku_id", sku);
    row.put("activity_id", 10L);
    row.put("user_id", 8L);
    row.put("idempotency_key", "private-idem");
    return row;
  }
}
