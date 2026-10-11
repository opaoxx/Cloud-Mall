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

/** 秒杀写前必须绑定锁定原订单，不能让错误行进入CAS或终态幂等成功。 */
class SeckillSettlementWriteIdentityTest {
  /** 本次可信动作请求订单号。 */
  private static final String ORDER_NO = "202610proof1";

  @Test
  void wrongLockedOrderNeverReachesTransitionsOrIdempotentSuccess() {
    List<Executable> assertions = new ArrayList<>();
    for (String original :
        Arrays.asList("202610PROOF1", "202610proof1 ", "202610próof1", "other", null))
      for (String state :
          List.of("OPEN", "CONFIRMED", "RELEASED", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
        for (String action : List.of("CONFIRM", "ROLLBACK"))
          assertions.add(
              () -> {
                var mapper = preparedMapper(original, state, action);
                var stock = mock(StockSqlMapper.class);
                var service = new SeckillQuotaSettlementService(mapper, stock);
                BizException rejected =
                    assertThrows(
                        BizException.class,
                        () -> service.settle(ORDER_NO, action),
                        original + state + action);
                assertEquals(409, rejected.getHttpStatus());
                assertEquals("SECKILL_SETTLEMENT_CONFLICT", rejected.getCode());
                assertFalse(rejected.getMessage().contains("202610"));
                verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
                verify(mapper).findSettlementForUpdate(ORDER_NO);
                verifyNoMoreInteractions(mapper);
                verifyNoInteractions(stock);
              });
    assertAll(assertions);
  }

  @Test
  void lockedIdentityConflictHasPriorityOverCorruptDimensions() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stock = mock(StockSqlMapper.class);
    var row = new SeckillQuotaSettlementPO();
    row.orderNo = "other";
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row);
    var rejected =
        assertThrows(
            BizException.class,
            () -> new SeckillQuotaSettlementService(mapper, stock).settle(ORDER_NO, "CONFIRM"));
    assertEquals(409, rejected.getHttpStatus());
    assertEquals("SECKILL_SETTLEMENT_CONFLICT", rejected.getCode());
    verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
    verify(mapper).findSettlementForUpdate(ORDER_NO);
    verifyNoMoreInteractions(mapper);
    verifyNoInteractions(stock);
  }

  @Test
  void exactOrderPreservesCasAndSameDirectionTerminalReplay() {
    for (String state :
        List.of("OPEN", "CONFIRMED", "RELEASED", "RELEASE_PENDING", "PROJECTION_BLOCKED"))
      for (String action : List.of("CONFIRM", "ROLLBACK")) {
        var mapper = preparedMapper(ORDER_NO, state, action);
        var stock = mock(StockSqlMapper.class);
        var service = new SeckillQuotaSettlementService(mapper, stock);
        if ("CONFIRMED".equals(state) && "ROLLBACK".equals(action)
            || "RELEASED".equals(state) && "CONFIRM".equals(action)
            || "RELEASE_PENDING".equals(state) && "CONFIRM".equals(action)) {
          assertEquals(
              409,
              assertThrows(BizException.class, () -> service.settle(ORDER_NO, action))
                  .getHttpStatus());
        } else {
          Map<?, ?> response = (Map<?, ?>) service.settle(ORDER_NO, action).data;
          if ("CONFIRM".equals(action)) assertEquals(true, response.get("confirmed"));
          else assertEquals("RELEASED".equals(state), response.get("rolledBack"));
        }
        verifyNoInteractions(stock);
      }
  }

  @Test
  void noCandidateAndInvalidRequestKeepOriginalBoundaries() {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var stock = mock(StockSqlMapper.class);
    var service = new SeckillQuotaSettlementService(mapper, stock);
    assertNull(service.settle(null, "CONFIRM"));
    assertNull(service.settle(" ", "ROLLBACK"));
    assertEquals(
        400,
        assertThrows(BizException.class, () -> service.settle(ORDER_NO, "bad")).getHttpStatus());
    verifyNoInteractions(mapper, stock);
    assertNull(service.settle(ORDER_NO, "CONFIRM"));
    verify(mapper).insertOpenIfAbsent(eq(ORDER_NO), any());
    verify(mapper).findSettlementForUpdate(ORDER_NO);
    verifyNoMoreInteractions(mapper);
    verifyNoInteractions(stock);
  }

  /** 正维度原行与原CAS替身，让错误身份的旧实现确实会成功而不是缺stub失败。 */
  private static SeckillQuotaSettlementMapper preparedMapper(
      String original, String state, String action) {
    var mapper = mock(SeckillQuotaSettlementMapper.class);
    var row = new SeckillQuotaSettlementPO();
    row.orderNo = original;
    row.activityId = 10L;
    row.skuId = 20L;
    row.userId = 8L;
    row.idempotencyKey = "private-idem";
    row.quantity = 1;
    row.status = state;
    row.revision = 1L;
    row.operationKey =
        ORDER_NO
            + ":"
            + ("CONFIRMED".equals(state)
                ? "CONFIRM"
                : "RELEASED".equals(state) ? "ROLLBACK" : action);
    when(mapper.findSettlementForUpdate(ORDER_NO)).thenReturn(row);
    when(mapper.transitionOpenToConfirmed(eq(ORDER_NO), anyString(), eq(1L))).thenReturn(1);
    when(mapper.transitionBlockedToConfirmed(eq(ORDER_NO), anyString(), eq(1L))).thenReturn(1);
    when(mapper.transitionToReleasePending(eq(ORDER_NO), anyString(), eq(1L))).thenReturn(1);
    return mapper;
  }
}
