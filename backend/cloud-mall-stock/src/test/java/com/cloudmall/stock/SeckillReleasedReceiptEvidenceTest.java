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

/** RELEASED 必须具有原 Lua 允许的持久释放回执；不冒充 Redis 原来源证明。 */
class SeckillReleasedReceiptEvidenceTest {
  /** 请求的原订单。 */
  private static final String ORDER_NO = "receipt-order";

  @Test
  void missingOrImpossibleReceiptCannotProveReleasedGet() {
    List<Executable> assertions = new ArrayList<>();
    for (Long receipt : badReceipts())
      assertions.add(
          () -> {
            var mapper = mock(SeckillQuotaSettlementMapper.class);
            var stocks = mock(StockSqlMapper.class);
            when(mapper.findSettlement(ORDER_NO)).thenReturn(row("RELEASED", receipt));
            var result = new SeckillQuotaSettlementService(mapper, stocks).reservation(ORDER_NO);
            assertEquals("UNKNOWN", result.status, String.valueOf(receipt));
            assertEquals(ORDER_NO, result.orderNo);
            assertTrue(result.items.isEmpty());
            verify(mapper).findSettlement(ORDER_NO);
            verifyNoMoreInteractions(mapper);
            verifyNoInteractions(stocks);
          });
    assertAll(assertions);
  }

  @Test
  void badReceiptBlocksBothWriteDirectionsBeforeNoopOrConflict() {
    List<Executable> assertions = new ArrayList<>();
    for (Long receipt : badReceipts())
      for (String action : List.of("CONFIRM", "ROLLBACK"))
        assertions.add(
            () -> {
              var mapper = mock(SeckillQuotaSettlementMapper.class);
              var stocks = mock(StockSqlMapper.class);
              when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row("RELEASED", receipt));
              var service = new SeckillQuotaSettlementService(mapper, stocks);
              var failure =
                  assertThrows(BizException.class, () -> service.settle(ORDER_NO, action));
              assertEquals(500, failure.getHttpStatus());
              assertEquals("SECKILL_SETTLEMENT_CORRUPT", failure.getCode());
              assertEquals("秒杀释放回执缺证", failure.getMessage());
              verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
              verify(mapper).findSettlementForUpdate(ORDER_NO);
              verifyNoMoreInteractions(mapper);
              verifyNoInteractions(stocks);
            });
    assertAll(assertions);
  }

  @Test
  void exactReceiptBoundariesKeepReleasedReplayAndOppositeConflict() {
    for (long receipt : new long[] {1L, 4L, 1000000000000L}) {
      var mapper = mock(SeckillQuotaSettlementMapper.class);
      var stocks = mock(StockSqlMapper.class);
      when(mapper.findSettlement(ORDER_NO)).thenReturn(row("RELEASED", receipt));
      when(mapper.findMatchingAcceptanceId(any())).thenReturn(1L);
      when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row("RELEASED", receipt));
      var service = new SeckillQuotaSettlementService(mapper, stocks);
      var result = service.reservation(ORDER_NO);
      assertEquals("ROLLED_BACK", result.status);
      assertEquals(1, result.items.size());
      assertEquals(20L, result.items.get(0).skuId);
      var replay = service.settle(ORDER_NO, "ROLLBACK");
      assertEquals(true, replay.rolledBack);
      assertEquals(true, replay.idempotent);
      assertEquals(
          409,
          assertThrows(BizException.class, () -> service.settle(ORDER_NO, "CONFIRM"))
              .getHttpStatus());
    }
  }

  @Test
  void existingProofPriorityAndOtherStatusesDoNotGainReceiptRules() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var service = new SeckillQuotaSettlementService(mapper, mock(StockSqlMapper.class));
    var original = row("RELEASED", null);
    original.orderNo = "other";
    when(mapper.findSettlement(ORDER_NO)).thenReturn(original);
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(original);
    assertEquals("UNKNOWN", service.reservation(ORDER_NO).status);
    assertEquals(
        409,
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "ROLLBACK"))
            .getHttpStatus());
    original.orderNo = ORDER_NO;
    original.quantity = 2;
    assertEquals(
        "秒杀结算事实维度无效",
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "ROLLBACK")).getMessage());
    original.quantity = 1;
    original.operationKey = "other:ROLLBACK";
    assertEquals(
        "秒杀结算操作键缺证",
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "ROLLBACK")).getMessage());
    for (String state :
        List.of(
            "CONFIRMED",
            "OPEN",
            "RELEASE_PENDING",
            "PROJECTION_BLOCKED",
            "CONFLICT",
            "unrecognized"))
      for (Long receipt : badReceipts()) {
        when(mapper.findSettlement(ORDER_NO)).thenReturn(row(state, receipt));
        when(mapper.findMatchingAcceptanceId(any())).thenReturn(1L);
        var result = service.reservation(ORDER_NO);
        assertEquals(
            "CONFIRMED".equals(state) ? "CONFIRMED" : "OPEN".equals(state) ? "RESERVED" : "UNKNOWN",
            result.status);
        assertEquals(
            "CONFLICT".equals(state) || "unrecognized".equals(state) ? 0 : 1, result.items.size());
      }
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row("CONFIRMED", null));
    assertEquals(true, (service.settle(ORDER_NO, "CONFIRM")).confirmed);
  }

  /** 覆盖 SQL BIGINT 域内可持久化但不可能由原 Lua 返回的回执。 */
  private static List<Long> badReceipts() {
    return Arrays.asList(null, 0L, -1L, Long.MIN_VALUE, 1000000000001L, Long.MAX_VALUE);
  }

  /** 合法基础事实，仅回执或明确测试的字段变异。 */
  private static SeckillQuotaSettlementPO row(String state, Long receipt) {
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
    settlement.redisRevision = receipt;
    return settlement;
  }
}
