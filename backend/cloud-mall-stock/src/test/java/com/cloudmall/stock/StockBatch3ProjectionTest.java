package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.domain.vo.StockReservationVO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockCacheReconciler;
import com.cloudmall.stock.service.impl.StockReservationReader;
import com.cloudmall.stock.service.impl.StockReservationRejectedException;
import com.cloudmall.stock.service.impl.StockReservationRequestService;
import com.cloudmall.stock.service.impl.StockReservationServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.test.util.ReflectionTestUtils;

/** 普通库存以 MySQL 为事实源并用持久版本修复 Redis 的回归测试。 */
class StockBatch3ProjectionTest {
  @Test
  void responseLossReplayDoesNotUpdateMysqlTwice() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    StockReservationRequestService requestService = mock(StockReservationRequestService.class);
    AtomicReference<String> claimedHash = new AtomicReference<>();
    AtomicReference<String> claimedSnapshot = new AtomicReference<>();
    AtomicInteger requestReads = new AtomicInteger();
    AtomicInteger lockCount = new AtomicInteger();
    when(requestService.claim(eq("order-1"), eq("NORMAL"), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              claimedHash.set(invocation.getArgument(3));
              claimedSnapshot.set(invocation.getArgument(2));
              return "INIT";
            });
    when(stockSqlMapper.findReservationRequest("order-1"))
        .thenAnswer(
            invocation ->
                requestReads.getAndIncrement() == 0
                    ? null
                    : Map.of(
                        "order_no",
                        "order-1",
                        "scene",
                        "NORMAL",
                        "status",
                        "RESERVED",
                        "items_snapshot",
                        claimedSnapshot.get(),
                        "items_hash",
                        claimedHash.get()));
    when(stockSqlMapper.findReservationRequestForUpdate("order-1"))
        .thenAnswer(
            invocation ->
                Map.of(
                    "items_hash",
                    claimedHash.get(),
                    "status",
                    lockCount.getAndIncrement() == 0 ? "INIT" : "RESERVED"));
    when(stockSqlMapper.findStockForUpdate(7L))
        .thenReturn(Map.of("available_quantity", 10, "reserved_quantity", 0, "version", 4));
    when(stockSqlMapper.updateAvailableWithVersion(eq(7L), eq(2), eq(4), any()))
        .thenReturn(1);
    when(stockSqlMapper.updateReservationRequestStatus(
            eq("order-1"), eq("INIT"), eq("RESERVED"), eq(null), any()))
        .thenReturn(1);
    when(stockSqlMapper.findReservationFlows("order-1"))
        .thenReturn(
            List.of(
                Map.of(
                    "sku_id",
                    7L,
                    "original_order_no",
                    "order-1",
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
    StockReservationServiceImpl stockService = service(redis, stockSqlMapper, requestService);

    ApiResponse<?> first = stockService.reserve(request("order-1"));
    ApiResponse<?> replay = stockService.reserve(request("order-1"));

    assertEquals("RESERVED", ((StockReservationVO) first.data).status);
    assertEquals("ALREADY_RESERVED", ((StockReservationVO) replay.data).status);
    verify(stockSqlMapper).updateAvailableWithVersion(eq(7L), eq(2), eq(4), any());
    verify(stockSqlMapper).insertCacheRepair(eq(7L), eq(8), eq(5), any());
    verify(redis, never()).execute(any(DefaultRedisScript.class), any(), any(Object[].class));
  }

  @Test
  void reservationQueryReturnsConfirmedStatusFromDurableFlow() {
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    when(stockSqlMapper.findReservationRequest("old-order-2")).thenReturn(null);
    when(stockSqlMapper.findReservationFlows("old-order-2"))
        .thenReturn(
            List.of(
                Map.of(
                    "sku_id",
                    7L,
                    "original_order_no",
                    "old-order-2",
                    "order_identity_count",
                    1L,
                    "invalid_flow_count",
                    0L,
                    "reserved_quantity",
                    2L,
                    "confirmed_quantity",
                    2L,
                    "rolled_back_quantity",
                    0L)));
    StockReservationReader stockService =
        StockTestServices.reader(stockSqlMapper, new ObjectMapper());

    ApiResponse<?> response = stockService.reservation("old-order-2");

    assertEquals("CONFIRMED", ((StockReservationVO) response.data).status);
  }

  @Test
  void insufficientSecondSkuRejectsTheWholeRequestBeforeAnyStockMutation() {
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    StockReservationRequestService requestService = mock(StockReservationRequestService.class);
    AtomicReference<String> claimedHash = new AtomicReference<>();
    when(requestService.claim(eq("order-short"), eq("NORMAL"), anyString(), anyString()))
        .thenAnswer(
            invocation -> {
              claimedHash.set(invocation.getArgument(3));
              return "INIT";
            });
    when(stockSqlMapper.findReservationRequest("order-short")).thenReturn(null);
    when(stockSqlMapper.findReservationRequestForUpdate("order-short"))
        .thenAnswer(
            invocation -> Map.of("items_hash", claimedHash.get(), "status", "INIT"));
    when(stockSqlMapper.findStockForUpdate(-3L))
        .thenReturn(Map.of("available_quantity", 5, "version", 0));
    when(stockSqlMapper.findStockForUpdate(-2L))
        .thenReturn(Map.of("available_quantity", 0, "version", 0));
    when(stockSqlMapper.updateReservationRequestStatus(
            eq("order-short"), eq("INIT"), eq("REJECTED"), anyString(), any()))
        .thenReturn(1);
    StockReservationServiceImpl stockService =
        service(mock(StringRedisTemplate.class), stockSqlMapper, requestService);
    StockReservationDTO request = new StockReservationDTO();
    request.orderNo = "order-short";
    request.scene = "NORMAL";
    request.items = List.of(new StockLineDTO(-3L, 1), new StockLineDTO(-2L, 1));

    assertThrows(StockReservationRejectedException.class, () -> stockService.reserve(request));

    verify(stockSqlMapper, never())
        .updateAvailableWithVersion(any(), anyInt(), anyInt(), any());
  }

  @Test
  void projectionRejectsAnOlderVersionAndMarksItsRepairStale() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StockSqlMapper stockSqlMapper = mock(StockSqlMapper.class);
    when(stockSqlMapper.findPendingCacheRepairs(any(OffsetDateTime.class), eq(2000)))
        .thenReturn(List.of(Map.of("id", 9L, "sku_id", 7L, "target_available", 8, "source_version", 4)));
    when(stockSqlMapper.claimCacheRepair(eq(9L), anyString(), any(), any())).thenReturn(1);
    when(redis.execute(
            any(DefaultRedisScript.class),
            eq(List.of("stock:available:7")),
            eq("8"),
            eq("4")))
        .thenReturn(0L);
    StockCacheReconciler reconciler = new StockCacheReconciler(redis, stockSqlMapper);

    reconciler.repairPending();

    verify(stockSqlMapper)
        .finishCacheRepair(eq(9L), anyString(), eq("STALE"), anyString(), any(), any());
  }

  private static StockReservationDTO request(String orderNo) {
    StockReservationDTO request = new StockReservationDTO();
    request.orderNo = orderNo;
    request.scene = "NORMAL";
    request.items = List.of(new StockLineDTO(7L, 2));
    return request;
  }

  private static StockReservationServiceImpl service(
      StringRedisTemplate redis,
      StockSqlMapper stockSqlMapper,
      StockReservationRequestService requestService) {
    StockReservationServiceImpl stockService =
        StockTestServices.normal(redis, stockSqlMapper, new ObjectMapper());
    ReflectionTestUtils.setField(stockService, "reservationRequestService", requestService);
    return stockService;
  }
}
