package com.cloudmall.stock;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.junit.jupiter.api.Assertions.assertThrows;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.stock.domain.dto.StockLineDTO;
import com.cloudmall.stock.domain.dto.StockReservationDTO;
import com.cloudmall.stock.mapper.StockSqlMapper;
import com.cloudmall.stock.service.impl.StockServiceImpl;
import com.cloudmall.common.error.BizException;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.amqp.rabbit.core.RabbitTemplate;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.core.script.DefaultRedisScript;
import org.springframework.transaction.support.TransactionSynchronizationManager;

class StockServiceRecoveryTest {
  @Test
  void reserveReplayUsesDurableSnapshotWhenRedisMarkerIsMissing() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findReservationSnapshot("order-1"))
        .thenReturn(List.of(Map.of("sku_id", 7L, "reserved_quantity", 2L, "settled_quantity", 0L)));
    StockServiceImpl service = service(redis, mapper);

    StockReservationDTO request = new StockReservationDTO();
    request.orderNo = "order-1";
    request.items = List.of(new StockLineDTO(7L, 2));

    ApiResponse<?> response = service.reserve(request);

    verify(redis, never()).execute(any(DefaultRedisScript.class), any(), any(Object[].class));
    verify(mapper, never()).reserveQuantity(any(), any(Integer.class), any());
    org.junit.jupiter.api.Assertions.assertEquals(Boolean.TRUE, ((Map<?, ?>) response.data).get("idempotent"));
  }

  @Test
  void rollbackRecoveryReleasesRedisOnlyOnceForRepeatedCalls() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findOpenReservations("order-2"))
        .thenReturn(
            List.of(Map.of("sku_id", 7L, "quantity", 2L)),
            List.of());
    when(mapper.settleQuantity(any(), eq("order-2"), eq("ROLLBACK"), eq(2), anyString(), any()))
        .thenReturn(1, 0);
    StockServiceImpl service = service(redis, mapper);

    service.rollback("order-2");
    service.rollback("order-2");

    verify(mapper).settleQuantity(any(), eq("order-2"), eq("ROLLBACK"), eq(2), anyString(), any());
    verify(redis).execute(any(DefaultRedisScript.class), eq(List.of("stock:available:7", "stock:rollback:released:order-2:7")), eq("2"));
  }

  @Test
  void confirmCannotFollowACompletedRollbackFromStaleRedisState() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findOpenReservations("order-3")).thenReturn(List.of());
    StockServiceImpl service = service(redis, mapper);

    service.confirm("order-3");

    verify(mapper, never()).settleQuantity(any(), eq("order-3"), eq("CONFIRM"), any(Integer.class), anyString(), any());
    verify(redis).delete("stock:reservation:order-3");
  }

  @Test
  void reserveReplayAfterSettlementReturnsConflict() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findReservationSnapshot("order-4"))
        .thenReturn(List.of(Map.of("sku_id", 7L, "reserved_quantity", 2L, "settled_quantity", 2L)));
    StockServiceImpl service = service(redis, mapper);

    StockReservationDTO request = new StockReservationDTO();
    request.orderNo = "order-4";
    request.items = List.of(new StockLineDTO(7L, 2));

    assertThrows(BizException.class, () -> service.reserve(request));
    verify(redis, never()).execute(any(DefaultRedisScript.class), any(), any(Object[].class));
  }

  @Test
  void rollbackDoesNotReleaseRedisWhenTransactionDoesNotCommit() {
    StringRedisTemplate redis = mock(StringRedisTemplate.class);
    StockSqlMapper mapper = mock(StockSqlMapper.class);
    when(mapper.findOpenReservations("order-5"))
        .thenReturn(List.of(Map.of("sku_id", 7L, "quantity", 2L)));
    when(mapper.settleQuantity(any(), eq("order-5"), eq("ROLLBACK"), eq(2), anyString(), any()))
        .thenReturn(1);
    StockServiceImpl service = service(redis, mapper);

    TransactionSynchronizationManager.initSynchronization();
    try {
      service.rollback("order-5");
    } finally {
      TransactionSynchronizationManager.clearSynchronization();
    }

    verify(redis, never()).execute(any(DefaultRedisScript.class), any(), any(Object[].class));
  }

  private static StockServiceImpl service(StringRedisTemplate redis, StockSqlMapper mapper) {
    return new StockServiceImpl(redis, mapper, mock(RabbitTemplate.class), new ObjectMapper());
  }
}
