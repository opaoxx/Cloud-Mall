package com.cloudmall.order.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cloudmall.order.mapper.OrderSqlMapper;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** 长运行订单服务跨月后仍会准备主表、明细和状态日志。 */
class OrderTableInitializerTest {
  @Test
  void templateBootstrapFailureStopsBeforeAnyMonthDdl() {
    OrderSqlMapper mapper = mock(OrderSqlMapper.class);
    RuntimeException failure = new IllegalStateException("template missing");
    org.mockito.Mockito.doThrow(failure).when(mapper).ensureMonthlyCompanionTemplates();
    org.junit.jupiter.api.Assertions.assertSame(
        failure,
        org.junit.jupiter.api.Assertions.assertThrows(
            RuntimeException.class,
            () -> new OrderTableInitializer(mapper).prepareForMonth(YearMonth.of(2026, 12))));
    verify(mapper).ensureMonthlyCompanionTemplates();
    org.mockito.Mockito.verifyNoMoreInteractions(mapper);
  }

  @Test
  void rollingPreparationIncludesNextYearAndEveryCompanionTable() {
    OrderSqlMapper mapper = mock(OrderSqlMapper.class);

    new OrderTableInitializer(mapper).prepareForMonth(YearMonth.of(2026, 12));

    org.mockito.InOrder ordered = org.mockito.Mockito.inOrder(mapper);
    ordered.verify(mapper).ensureMonthlyCompanionTemplates();
    for (String suffix : new String[] {"202611", "202612", "202701", "202702"}) {
      ordered.verify(mapper).createMonthlyOrderTables(suffix);
    }
    ordered.verifyNoMoreInteractions();
  }
}
