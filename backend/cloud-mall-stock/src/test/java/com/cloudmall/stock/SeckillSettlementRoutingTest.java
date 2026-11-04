package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.dto.StockActionDTO;
import com.cloudmall.stock.domain.vo.StockActionResultVO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockReservationReader;
import com.cloudmall.stock.service.impl.StockReservationServiceImpl;
import com.cloudmall.stock.service.impl.seckill.SeckillQuotaSettlementService;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.test.util.ReflectionTestUtils;

/** 验证秒杀持久受理能够进入已有订单最终库存动作协议。 */
class SeckillSettlementRoutingTest {
  @Test
  void acceptedSeckillIsAReservedFactForOrderSettlement() {
    StockSqlMapper stockMapper = mock(StockSqlMapper.class);
    when(stockMapper.findReservationRequest("cm24-order")).thenReturn(null);
    when(stockMapper.findSeckillByOrderNo("cm24-order"))
        .thenReturn(
            Map.of(
                "status",
                "ACCEPTED",
                "order_no",
                "cm24-order",
                "sku_id",
                7L,
                "activity_id",
                10L,
                "user_id",
                8L,
                "idempotency_key",
                "private-idem"));
    StockReservationReader service = StockTestServices.reader(stockMapper, new ObjectMapper());

    StockReservationVO fact = (StockReservationVO) service.reservation("cm24-order");

    assertEquals("RESERVED", fact.status);
  }

  @Test
  void rollbackRoutesToSeckillWithoutTouchingNormalFlows() {
    StockSqlMapper stockMapper = mock(StockSqlMapper.class);
    SeckillQuotaSettlementService settlement = mock(SeckillQuotaSettlementService.class);
    doReturn(StockActionResultVO.pendingRollback())
        .when(settlement)
        .settle("cm24-order", "ROLLBACK");
    StockReservationServiceImpl service =
        StockTestServices.normal(mock(StringRedisTemplate.class), stockMapper, new ObjectMapper());
    ReflectionTestUtils.setField(service, "seckillSettlementService", settlement);

    var response = service.rollback("cm24-order");

    assertEquals(false, response.rolledBack);
    verify(stockMapper, never()).findOpenReservations(anyString());
  }

  @Test
  void unacceptedSeckillCannotReturnEmptyNormalFlowSuccess() {
    StockSqlMapper stockMapper = mock(StockSqlMapper.class);
    when(stockMapper.findSeckillByOrderNo("pending-order"))
        .thenReturn(
            Map.of(
                "status",
                "PENDING",
                "order_no",
                "pending-order",
                "sku_id",
                7L,
                "activity_id",
                10L,
                "user_id",
                8L,
                "idempotency_key",
                "private-idem"));
    StockReservationServiceImpl service =
        StockTestServices.normal(mock(StringRedisTemplate.class), stockMapper, new ObjectMapper());
    ReflectionTestUtils.setField(
        service, "seckillSettlementService", mock(SeckillQuotaSettlementService.class));

    BizException failure = assertThrows(BizException.class, () -> service.confirm("pending-order"));

    assertEquals(503, failure.getHttpStatus());
    verify(stockMapper, never()).findOpenReservations(anyString());
  }

  @Test
  void forgedActionKeyNeverReachesSeckillSettlement() {
    SeckillQuotaSettlementService settlement = mock(SeckillQuotaSettlementService.class);
    StockReservationServiceImpl service =
        StockTestServices.normal(
            mock(StringRedisTemplate.class), mock(StockSqlMapper.class), new ObjectMapper());
    ReflectionTestUtils.setField(service, "seckillSettlementService", settlement);
    StockActionDTO action = new StockActionDTO();
    action.operationKey = "another-order:ROLLBACK";

    assertThrows(BizException.class, () -> service.rollback("cm24-order", action));

    verify(settlement, never()).settle(anyString(), anyString());
  }
}
