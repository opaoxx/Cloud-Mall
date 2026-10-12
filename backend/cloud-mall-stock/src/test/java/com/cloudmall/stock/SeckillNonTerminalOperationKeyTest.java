package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** 非终态原键必须保原订单与允许方向，不能借CI别名进入CAS。 */
class SeckillNonTerminalOperationKeyTest {
  /** 原订单身份。 */
  private static final String ORDER_NO = "202610proof1";

  @Test
  void badNonTerminalKeysCannotDescribeReservedOrPendingItems() {
    List<Executable> assertions = new ArrayList<>();
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String key : badKeys(state))
        assertions.add(
            () -> {
              var mapper = mock(SeckillQuotaSettlementMapper.class);
              var stocks = mock(StockSqlMapper.class);
              when(mapper.findSettlement(ORDER_NO)).thenReturn(row(state, key));
              var result = new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO);
              assertEquals("UNKNOWN", result.status, state + ":" + key);
              assertTrue(result.items.isEmpty());
              assertEquals(ORDER_NO, result.orderNo);
              verify(mapper).findSettlement(ORDER_NO);
              verifyNoMoreInteractions(mapper);
              verifyNoInteractions(stocks);
            });
    assertAll(assertions);
  }

  @Test
  void badNonTerminalKeysStopBothActionsBeforeCasOrConflictDecision() {
    List<Executable> assertions = new ArrayList<>();
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String key : badKeys(state))
        for (String action : List.of("CONFIRM", "ROLLBACK"))
          assertions.add(
              () -> {
                var mapper = mock(SeckillQuotaSettlementMapper.class);
                var stocks = mock(StockSqlMapper.class);
                when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row(state, key));
                var service = new SeckillQuotaSettlementService(mapper, stocks);
                var failure =
                    assertThrows(BizException.class, () -> service.settle(ORDER_NO, action));
                assertEquals(500, failure.getHttpStatus());
                assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
                assertFalse(failure.getMessage().contains("202610"));
                verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
                verify(mapper).findSettlementForUpdate(ORDER_NO);
                verifyNoMoreInteractions(mapper);
                verifyNoInteractions(stocks);
              });
    assertAll(assertions);
  }

  @Test
  void legalNullOpenExactDirectionsAndBlockedCompatibilityKeepOldProtocol() {
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String key : legalKeys(state))
        for (String action : List.of("CONFIRM", "ROLLBACK")) {
          var mapper = mock(SeckillQuotaSettlementMapper.class);
          when(mapper.findSettlement(ORDER_NO)).thenReturn(row(state, key));
          when(mapper.findMatchingAcceptanceId(any())).thenReturn(1L);
          when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row(state, key));
          var service = new SeckillQuotaSettlementService(mapper, mock(StockSqlMapper.class));
          var result = service.reservation(ORDER_NO);
          assertEquals("OPEN".equals(state) ? "RESERVED" : "UNKNOWN", result.status);
          assertEquals(1, result.items.size());
          boolean sameDirection = key == null || key.equals(ORDER_NO + ":" + action);
          if (!sameDirection || "RELEASE_PENDING".equals(state) && "CONFIRM".equals(action)) {
            var failure = assertThrows(BizException.class, () -> service.settle(ORDER_NO, action));
            assertEquals("OPEN".equals(state) ? 503 : 409, failure.getHttpStatus());
            assertEquals(
                "OPEN".equals(state) ? "SECKILL_SETTLEMENT_UNKNOWN" : "SECKILL_SETTLEMENT_CONFLICT",
                failure.getCode());
          } else {
            when(mapper.transitionOpenToConfirmed(ORDER_NO, ORDER_NO + ":CONFIRM", 2L))
                .thenReturn(1);
            when(mapper.transitionToReleasePending(ORDER_NO, ORDER_NO + ":ROLLBACK", 2L))
                .thenReturn(1);
            when(mapper.transitionBlockedToConfirmed(ORDER_NO, ORDER_NO + ":CONFIRM", 2L))
                .thenReturn(1);
            Map<?, ?> response = (Map<?, ?>) service.settle(ORDER_NO, action).data;
            if ("CONFIRM".equals(action)) assertEquals(true, response.get("confirmed"));
            else {
              assertEquals(false, response.get("rolledBack"));
              assertEquals(true, response.get("pending"));
              assertEquals("UNKNOWN", response.get("status"));
            }
          }
        }
  }

  @Test
  void originalIdentityAndDimensionsStillWinAndOtherStatesRemainUnchanged() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var service = new SeckillQuotaSettlementService(mapper, mock(StockSqlMapper.class));
    var original = row("OPEN", "private-other:CONFIRM");
    original.orderNo = "other";
    when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
    assertTrue(service.reservation(ORDER_NO).items.isEmpty());
    assertEquals(
        409,
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM"))
            .getHttpStatus());
    original.orderNo = ORDER_NO;
    original.quantity = 2;
    for (BizException failure :
        List.of(
            assertThrows(BizException.class, () -> service.reservation(ORDER_NO)),
            assertThrows(BizException.class, () -> service.settle(ORDER_NO, "ROLLBACK")))) {
      assertEquals(500, failure.getHttpStatus());
      assertEquals("秒杀结算事实维度无效", failure.getMessage());
    }
    for (String state : List.of("CONFLICT", "unrecognized")) {
      when(mapper.findSettlement(ORDER_NO)).thenReturn(row(state, null));
      var observed = service.reservation(ORDER_NO);
      assertEquals("UNKNOWN", observed.status);
      assertTrue(observed.items.isEmpty());
    }
  }

  /** OPEN允许NULL与两种原键，pending只允许rollback，blocked保两方向兼容。 */
  private static List<String> legalKeys(String state) {
    if ("OPEN".equals(state))
      return Arrays.asList(null, ORDER_NO + ":CONFIRM", ORDER_NO + ":ROLLBACK");
    if ("RELEASE_PENDING".equals(state)) return List.of(ORDER_NO + ":ROLLBACK");
    return List.of(ORDER_NO + ":CONFIRM", ORDER_NO + ":ROLLBACK");
  }

  /** 真实未规范化坏原键；OPEN NULL不属于坏键，相反合法方向也不是坏键。 */
  private static List<String> badKeys(String state) {
    List<String> keys =
        new ArrayList<>(
            List.of(
                "",
                "private-other:ROLLBACK",
                "202610PROOF1:ROLLBACK",
                "202610próof1:ROLLBACK",
                ORDER_NO + ":ROLLBACK ",
                ORDER_NO + ":rollback",
                ORDER_NO + ":OTHER"));
    if (!"OPEN".equals(state)) keys.add(null);
    if ("RELEASE_PENDING".equals(state)) keys.add(ORDER_NO + ":CONFIRM");
    return keys;
  }

  /** 原身份/维度完整，测试只变异原操作键。 */
  private static SeckillQuotaSettlementPO row(String state, String key) {
    var row = new SeckillQuotaSettlementPO();
    row.orderNo = ORDER_NO;
    row.activityId = 10L;
    row.skuId = 20L;
    row.userId = 8L;
    row.idempotencyKey = "private-idem";
    row.quantity = 1;
    row.revision = 2L;
    row.status = state;
    row.operationKey = key;
    return row;
  }
}
