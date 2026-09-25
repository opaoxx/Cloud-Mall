package com.cloudmall.order.config;

import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import com.cloudmall.order.mapper.OrderSqlMapper;
import java.time.YearMonth;
import org.junit.jupiter.api.Test;

/** 长运行订单服务跨月后仍会准备主表、明细和状态日志。 */
class OrderTableInitializerTest {
  @Test
  void rollingPreparationIncludesNextYearAndEveryCompanionTable() {
    OrderSqlMapper mapper = mock(OrderSqlMapper.class);

    new OrderTableInitializer(mapper).prepareForMonth(YearMonth.of(2026, 12));

    for (String suffix : new String[] {"202611", "202612", "202701", "202702"}) {
      verify(mapper).execute("CREATE TABLE IF NOT EXISTS mall_order_" + suffix
          + " LIKE mall_order_template");
      verify(mapper).execute("CREATE TABLE IF NOT EXISTS mall_order_item_" + suffix
          + " LIKE mall_order_item_template");
      verify(mapper).execute("CREATE TABLE IF NOT EXISTS order_status_log_" + suffix
          + " LIKE order_status_log_template");
    }
    verify(mapper).execute("CREATE TABLE IF NOT EXISTS mall_order_item_template LIKE mall_order_item_202608");
    verify(mapper).execute("CREATE TABLE IF NOT EXISTS order_status_log_template LIKE order_status_log_202608");
  }
}
