package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.stock.domain.po.SeckillQuotaSettlementPO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.SeckillQuotaSettlementMapper;
import com.cloudmall.stock.service.impl.SeckillQuotaSettlementService;
import org.junit.jupiter.api.Test;

/** 验证秒杀结算事实的状态机和普通库存分流边界。 */
class SeckillQuotaSettlementServiceTest {
  @Test
  void confirmOnlyTransitionsOpenFact() {
    SeckillQuotaSettlementMapper mapper = mock(SeckillQuotaSettlementMapper.class);
    SeckillQuotaSettlementPO row = settlement("OPEN");
    when(mapper.findSettlementForUpdate("order-1")).thenReturn(row);
    when(mapper.transitionOpenToConfirmed("order-1", "order-1:CONFIRM", 1L)).thenReturn(1);

    ApiResponse<?> response =
        new SeckillQuotaSettlementService(
                mapper, mock(com.cloudmall.stock.mapper.StockSqlMapper.class))
            .settle("order-1", "CONFIRM");

    assertEquals(true, ((java.util.Map<?, ?>) response.data).get("confirmed"));
    verify(mapper).transitionOpenToConfirmed("order-1", "order-1:CONFIRM", 1L);
  }

  @Test
  void rollbackCommitsPendingWithoutTouchingRedis() {
    SeckillQuotaSettlementMapper mapper = mock(SeckillQuotaSettlementMapper.class);
    when(mapper.findSettlementForUpdate("order-2")).thenReturn(settlement("OPEN"));
    when(mapper.transitionToReleasePending("order-2", "order-2:ROLLBACK", 1L)).thenReturn(1);

    ApiResponse<?> response =
        new SeckillQuotaSettlementService(
                mapper, mock(com.cloudmall.stock.mapper.StockSqlMapper.class))
            .settle("order-2", "ROLLBACK");

    java.util.Map<?, ?> result = (java.util.Map<?, ?>) response.data;
    assertEquals(false, result.get("rolledBack"));
    assertEquals(true, result.get("pending"));
  }

  @Test
  void conflictingConfirmAfterReleasePendingIsRejected() {
    SeckillQuotaSettlementMapper mapper = mock(SeckillQuotaSettlementMapper.class);
    SeckillQuotaSettlementPO row = settlement("RELEASE_PENDING");
    row.operationKey = "order-3:ROLLBACK";
    when(mapper.findSettlementForUpdate("order-3")).thenReturn(row);

    assertThrows(
        com.cloudmall.common.error.BizException.class,
        () ->
            new SeckillQuotaSettlementService(
                    mapper, mock(com.cloudmall.stock.mapper.StockSqlMapper.class))
                .settle("order-3", "CONFIRM"));
  }

  @Test
  void unknownOrderFallsThroughToNormalInventory() {
    SeckillQuotaSettlementMapper mapper = mock(SeckillQuotaSettlementMapper.class);
    when(mapper.findSettlementForUpdate("normal-order")).thenReturn(null);
    when(mapper.findSettlement("normal-order")).thenReturn(null);

    assertNull(
        new SeckillQuotaSettlementService(
                mapper, mock(com.cloudmall.stock.mapper.StockSqlMapper.class))
            .settle("normal-order", "ROLLBACK"));
  }

  @Test
  void queryMapsInternalPendingStateToUnknown() {
    SeckillQuotaSettlementMapper mapper = mock(SeckillQuotaSettlementMapper.class);
    SeckillQuotaSettlementPO row = settlement("PROJECTION_BLOCKED");
    row.orderNo = "order-4";
    when(mapper.findSettlement("order-4")).thenReturn(row);

    StockReservationVO response =
        new SeckillQuotaSettlementService(
                mapper, mock(com.cloudmall.stock.mapper.StockSqlMapper.class))
            .reservation("order-4");

    assertEquals("UNKNOWN", response.status);
  }

  private static SeckillQuotaSettlementPO settlement(String status) {
    SeckillQuotaSettlementPO row = new SeckillQuotaSettlementPO();
    row.orderNo = "order-1";
    row.activityId = 10L;
    row.skuId = 20L;
    row.userId = 30L;
    row.idempotencyKey = "idem";
    row.quantity = 1;
    row.status = status;
    row.revision = 1L;
    return row;
  }
}
