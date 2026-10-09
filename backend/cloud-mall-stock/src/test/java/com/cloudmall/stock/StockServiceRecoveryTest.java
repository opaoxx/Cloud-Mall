package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.error.BizException;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockReservationRequestService;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

/** 回归旧库存流水与新版 MySQL 事实路径的兼容语义。 */
class StockServiceRecoveryTest {
  @Test
  void legacyReservationReplayDoesNotReserveStockAgain() {
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(stockSqlMapper.findReservationRequest("old-order-1")).thenReturn(null);
    when(stockSqlMapper.findReservationSnapshot("old-order-1"))
        .thenReturn(List.of(Map.of("sku_id", 7L, "reserved_quantity", 2L, "settled_quantity", 0L)));
    when(stockSqlMapper.findReservationFlows("old-order-1"))
        .thenReturn(
            List.of(
                Map.of(
                    "sku_id",
                    7L,
                    "original_order_no",
                    "old-order-1",
                    "order_identity_count",
                    1L,
                    "invalid_flow_count",
                    0L,
                    "reserved_quantity",
                    2L,
                    "confirmed_quantity",
                    0L,
                    "rolled_back_quantity",
                    0L)));
    StockServiceImpl stockService = service(redis, stockSqlMapper);

    ApiResponse<?> response = stockService.reserve(request("old-order-1", 7L, 2));

    assertEquals("ALREADY_RESERVED", ((StockReservationVO) response.data).status);
    verify(stockSqlMapper, never()).updateAvailableWithVersion(any(), any(Integer.class), any(Integer.class), any());
    verify(redis, never()).execute(any(DefaultRedisScript.class), any(), any(Object[].class));
  }

  @Test
  void legacyReservationCannotBeReopenedAfterSettlement() {
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    when(stockSqlMapper.findReservationRequest("old-order-2")).thenReturn(null);
    when(stockSqlMapper.findReservationSnapshot("old-order-2"))
        .thenReturn(List.of(Map.of("sku_id", 7L, "reserved_quantity", 2L, "settled_quantity", 2L)));
    StockServiceImpl stockService = service(mock(StringRedisTemplate.class), stockSqlMapper);

    assertThrows(BizException.class, () -> stockService.reserve(request("old-order-2", 7L, 2)));

    verify(stockSqlMapper, never()).updateAvailableWithVersion(any(), any(Integer.class), any(Integer.class), any());
  }

  @Test
  void confirmCannotReverseAnAlreadyRolledBackRequest() {
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    when(stockSqlMapper.findReservationRequestForUpdate("order-3"))
        .thenReturn(Map.of("status", "ROLLED_BACK"));
    StockServiceImpl stockService = service(mock(StringRedisTemplate.class), stockSqlMapper);

    assertThrows(BizException.class, () -> stockService.confirm("order-3"));

    verify(stockSqlMapper, never())
        .updateSettlementWithVersion(any(), anyString(), any(Integer.class), any(Integer.class), any());
  }

  @Test
  void rollbackWritesMysqlAndDurableProjectionWithoutRedisQuantityMutation() {
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    when(stockSqlMapper.findReservationRequestForUpdate("order-4"))
        .thenReturn(Map.of("status", "RESERVED"));
    when(stockSqlMapper.findOpenReservations("order-4"))
        .thenReturn(List.of(Map.of("sku_id", 7L, "quantity", 2L)));
    when(stockSqlMapper.findStockForUpdate(7L))
        .thenReturn(Map.of("available_quantity", 8, "reserved_quantity", 2, "version", 4));
    when(stockSqlMapper.updateSettlementWithVersion(eq(7L), eq("ROLLBACK"), eq(2), eq(4), any()))
        .thenReturn(1);
    when(stockSqlMapper.updateReservationRequestStatus(
            eq("order-4"), eq("RESERVED"), eq("ROLLED_BACK"), eq(null), any()))
        .thenReturn(1);
    StockServiceImpl stockService = service(redis, stockSqlMapper);

    ApiResponse<?> response = stockService.rollback("order-4");

    assertEquals(Boolean.TRUE, ((Map<?, ?>) response.data).get("rolledBack"));
    verify(stockSqlMapper).insertCacheRepair(eq(7L), eq(10), eq(5), any(OffsetDateTime.class));
    verify(redis, never()).execute(any(DefaultRedisScript.class), any(), any(Object[].class));
  }

  private static StockReservationDTO request(String orderNo, long skuId, int quantity) {
    StockReservationDTO request = new StockReservationDTO();
    request.orderNo = orderNo;
    request.scene = "NORMAL";
    request.items = List.of(new StockLineDTO(skuId, quantity));
    return request;
  }

  private static StockServiceImpl service(StringRedisTemplate redis, StockSqlMapper stockSqlMapper) {
    StockServiceImpl stockService =
        new StockServiceImpl(redis, stockSqlMapper, mock(RabbitTemplate.class), new ObjectMapper());
    ReflectionTestUtils.setField(
        stockService,
        "reservationRequestService",
        mock(StockReservationRequestService.class));
    return stockService;
  }
}
