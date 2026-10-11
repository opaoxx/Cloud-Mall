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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

/** 终态标签不能代替原操作键；只核两终态，不扩展OPEN/pending状态机。 */
class SeckillTerminalOperationKeyTest {
  /** 原订单号。 */
  private static final String ORDER_NO = "202610proof1";

  @Test
  void terminalGetWithWrongOrMissingOperationKeyIsNotCompletionEvidence() {
    List<Executable> assertions = new ArrayList<>();
    for (String state : List.of("CONFIRMED", "RELEASED"))
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
  void terminalWriteCannotGrantNoopOrDirectionDecisionWithoutOriginalKey() {
    List<Executable> assertions = new ArrayList<>();
    for (String state : List.of("CONFIRMED", "RELEASED"))
      for (String key : badKeys(state))
        for (String action : List.of("CONFIRM", "ROLLBACK"))
          assertions.add(
              () -> {
                var mapper = mock(SeckillQuotaSettlementMapper.class);
                var stocks = mock(StockSqlMapper.class);
                when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row(state, key));
                var service = new SeckillQuotaSettlementService(mapper, stocks);
                BizException failure =
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
  void exactTerminalKeyPreservesGetReplayAndOppositeConflict() {
    for (String state : List.of("CONFIRMED", "RELEASED")) {
      String direction = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      when(mapper.findSettlement(ORDER_NO)).thenReturn(row(state, ORDER_NO + ":" + direction));
      when(mapper.findMatchingAcceptanceId(any())).thenReturn(1L);
      when(mapper.findSettlementForUpdate(ORDER_NO))
          .thenReturn(row(state, ORDER_NO + ":" + direction));
      var service = new SeckillQuotaSettlementService(mapper, stocks);
      var result = service.reservation(ORDER_NO);
      assertEquals("CONFIRMED".equals(state) ? "CONFIRMED" : "ROLLED_BACK", result.status);
      assertEquals(1, result.items.size());
      assertEquals(
          true, ((java.util.Map<?, ?>) service.settle(ORDER_NO, direction).data).get("idempotent"));
      assertEquals(
          409,
          assertThrows(
                  BizException.class,
                  () ->
                      service.settle(
                          ORDER_NO, "CONFIRM".equals(direction) ? "ROLLBACK" : "CONFIRM"))
              .getHttpStatus());
    }
  }

  @Test
  void identityPriorityAndNonTerminalObservationRemainOriginalScope() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stocks = mock(StockSqlMapper.class);
    var corrupt = row("CONFIRMED", null);
    corrupt.orderNo = "other";
    when(mapper.findSettlement(ORDER_NO)).thenReturn(corrupt);
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(corrupt);
    var service = new SeckillQuotaSettlementService(mapper, stocks);
    assertEquals("UNKNOWN", service.reservation(ORDER_NO).status);
    assertEquals(
        409,
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM"))
            .getHttpStatus());
    // 原维度错误仍先于终态操作键缺证，不把既有500改写为UNKNOWN或新错误消息。
    var invalidDimensions = row("CONFIRMED", null);
    invalidDimensions.quantity = 2;
    when(mapper.findSettlement(ORDER_NO)).thenReturn(invalidDimensions);
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(invalidDimensions);
    var readFailure = assertThrows(BizException.class, () -> service.reservation(ORDER_NO));
    var writeFailure = assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM"));
    for (BizException failure : List.of(readFailure, writeFailure)) {
      assertEquals(500, failure.getHttpStatus());
      assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
      assertEquals("秒杀结算事实维度无效", failure.getMessage());
    }
    for (String state : List.of("OPEN", "RELEASE_PENDING", "PROJECTION_BLOCKED")) {
      String key =
          "RELEASE_PENDING".equals(state) || "PROJECTION_BLOCKED".equals(state)
              ? ORDER_NO + ":ROLLBACK"
              : null;
      var healthyMapper = mock(SeckillQuotaSettlementMapper.class);
      var healthy = row(state, key);
      when(healthyMapper.findSettlement(ORDER_NO)).thenReturn(healthy);
      when(healthyMapper.findMatchingAcceptanceId(same(healthy))).thenReturn(1L);
      var result =
          new SeckillQuotaSettlementService(healthyMapper, mock(StockSqlMapper.class))
              .reservation(ORDER_NO);
      assertEquals("OPEN".equals(state) ? "RESERVED" : "UNKNOWN", result.status);
      assertEquals(1, result.items.size());
      verify(healthyMapper).findSettlement(ORDER_NO);
      verify(healthyMapper).findMatchingAcceptanceId(same(healthy));
      verifyNoMoreInteractions(healthyMapper);
    }
    for (String state : List.of("CONFLICT", "unrecognized")) {
      var unknownMapper = mock(SeckillQuotaSettlementMapper.class);
      when(unknownMapper.findSettlement(ORDER_NO)).thenReturn(row(state, null));
      var result =
          new SeckillQuotaSettlementService(unknownMapper, mock(StockSqlMapper.class))
              .reservation(ORDER_NO);
      assertEquals("UNKNOWN", result.status);
      assertEquals(1, result.items.size());
      verify(unknownMapper, never()).findMatchingAcceptanceId(any());
    }
  }

  /** 原操作键错误矩阵，不使用被测helper构造结论。 */
  private static List<String> badKeys(String state) {
    String direction = "CONFIRMED".equals(state) ? "CONFIRM" : "ROLLBACK";
    return Arrays.asList(
        null,
        "",
        "other:" + direction,
        ORDER_NO + ":" + ("CONFIRM".equals(direction) ? "ROLLBACK" : "CONFIRM"),
        "202610PROOF1:" + direction,
        "202610próof1:" + direction,
        ORDER_NO + ":" + direction + " ",
        ORDER_NO + ":" + direction.toLowerCase(java.util.Locale.ROOT));
  }

  /** 原身份/维度完整，仅operation key可被变异。 */
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
    if ("RELEASED".equals(state)) row.redisRevision = 4L;
    row.operationKey = key;
    return row;
  }
}
