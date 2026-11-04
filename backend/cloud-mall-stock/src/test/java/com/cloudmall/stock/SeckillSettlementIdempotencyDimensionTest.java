package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.seckill.SeckillQuotaSettlementService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** 结算原受理幂等键必须符合既有受理范围；合法Unicode原字段不做规范化。 */
class SeckillSettlementIdempotencyDimensionTest {
  /** 原订单号。 */
  private static final String ORDER_NO = "202610proof1";

  /** 五种已知结算状态，均沿原维度门。 */
  private static final List<String> STATES =
      List.of("OPEN", "CONFIRMED", "RELEASED", "RELEASE_PENDING", "PROJECTION_BLOCKED");

  @Test
  void invalidOriginalIdempotencyKeyCannotProveAnyKnownSettlement() {
    List<Executable> assertions = new ArrayList<>();
    for (String state : STATES)
      for (String key : invalidKeys())
        assertions.add(
            () -> {
              var mapper = mock(SeckillQuotaSettlementMapper.class);
              var stocks = mock(StockSqlMapper.class);
              when(mapper.findSettlement(ORDER_NO)).thenReturn(row(state, key));
              var failure =
                  assertThrows(
                      BizException.class,
                      () ->
                          new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO));
              assertCorrupt(failure);
              verify(mapper).findSettlement(ORDER_NO);
              verifyNoMoreInteractions(mapper);
              verifyNoInteractions(stocks);
            });
    assertAll(assertions);
  }

  @Test
  void invalidOriginalIdempotencyKeyStopsBothDirectionsBeforeNoopConflictOrCas() {
    List<Executable> assertions = new ArrayList<>();
    for (String state : STATES)
      for (String key : invalidKeys())
        for (String action : List.of("CONFIRM", "ROLLBACK"))
          assertions.add(
              () -> {
                var mapper = mock(SeckillQuotaSettlementMapper.class);
                var stocks = mock(StockSqlMapper.class);
                when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row(state, key));
                var failure =
                    assertThrows(
                        BizException.class,
                        () ->
                            new SeckillQuotaSettlementService(mapper, stocks)
                                .settle(ORDER_NO, action));
                assertCorrupt(failure);
                verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
                verify(mapper).findSettlementForUpdate(ORDER_NO);
                verifyNoMoreInteractions(mapper);
                verifyNoInteractions(stocks);
              });
    assertAll(assertions);
  }

  @Test
  void validOriginalKeysPreserveWhitespaceCaseAndUnicodeCodepointLimit() {
    for (String state : STATES)
      for (String key :
          List.of(
              "private-idem",
              " private-idem ",
              "NULL",
              "null ",
              "x".repeat(128),
              "😺".repeat(128))) {
        var mapper = mock(SeckillQuotaSettlementMapper.class);
        var original = row(state, key);
        when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
        when(mapper.findMatchingAcceptanceId(any())).thenReturn(1L);
        when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
        when(mapper.transitionOpenToConfirmed(ORDER_NO, ORDER_NO + ":CONFIRM", 2L)).thenReturn(1);
        var service = new SeckillQuotaSettlementService(mapper, mock(StockSqlMapper.class));
        var observed = service.reservation(ORDER_NO);
        assertEquals(
            "OPEN".equals(state)
                ? "RESERVED"
                : "CONFIRMED".equals(state)
                    ? "CONFIRMED"
                    : "RELEASED".equals(state) ? "ROLLED_BACK" : "UNKNOWN",
            observed.status);
        assertEquals(1, observed.items.size());
        String action = "OPEN".equals(state) || "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
        assertNotNull(service.settle(ORDER_NO, action));
        assertEquals(key, original.idempotencyKey);
      }
  }

  @Test
  void originalOrderGuardPrecedesDimensionsAndDimensionsPrecedeOperationKey() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var service = new SeckillQuotaSettlementService(mapper, mock(StockSqlMapper.class));
    var original = row("CONFIRMED", "null");
    original.orderNo = "other";
    original.operationKey = "private-other:CONFIRM";
    when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
    assertEquals("UNKNOWN", service.reservation(ORDER_NO).status);
    assertTrue(service.reservation(ORDER_NO).items.isEmpty());
    assertEquals(
        409,
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM"))
            .getHttpStatus());
    original.orderNo = ORDER_NO;
    assertCorrupt(assertThrows(BizException.class, () -> service.reservation(ORDER_NO)));
    assertCorrupt(assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM")));
  }

  /** NULL及超限只模拟不合法历史/Mapper事实；正常DDL不会保存。 */
  private static List<String> invalidKeys() {
    return Arrays.asList(
        null, "", " ", "\t\n", "\u2003", "null", "x".repeat(129), "😺".repeat(129));
  }

  /** 沿旧维度500及固定消息，不泄露原幂等键。 */
  private static void assertCorrupt(BizException failure) {
    assertEquals(500, failure.getHttpStatus());
    assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
    assertEquals("秒杀结算事实维度无效", failure.getMessage());
  }

  /** 原订单/数值维度/操作键均合法，仅原受理幂等键可变。 */
  private static SeckillQuotaSettlementPO row(String state, String key) {
    var row = new SeckillQuotaSettlementPO();
    row.orderNo = ORDER_NO;
    row.activityId = 10L;
    row.skuId = 20L;
    row.userId = 8L;
    row.idempotencyKey = key;
    row.quantity = 1;
    row.revision = 2L;
    row.status = state;
    if ("RELEASED".equals(state)) row.redisRevision = 4L;
    row.operationKey =
        "OPEN".equals(state)
            ? null
            : ORDER_NO + ("CONFIRMED".equals(state) ? ":CONFIRM" : ":ROLLBACK");
    return row;
  }
}
