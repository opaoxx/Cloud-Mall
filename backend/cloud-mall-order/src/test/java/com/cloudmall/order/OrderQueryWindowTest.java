package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.config.MonthlyOrderShardingAlgorithm;
import com.cloudmall.order.domain.dto.OrderQueryWindow;
import com.cloudmall.order.feign.*;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.google.common.collect.Range;
import java.time.OffsetDateTime;
import java.time.YearMonth;
import java.util.List;
import org.apache.shardingsphere.api.sharding.standard.RangeShardingValue;
import org.junit.jupiter.api.Test;

/** 默认列表与未来隔离路由共用的半开毫秒窗口契约。 */
class OrderQueryWindowTest {
  /** 固定单次时钟采样，包含月末和纳秒尾数。 */
  private static final OffsetDateTime NOW =
      OffsetDateTime.parse("2027-03-31T12:00:00.000000001+08:00");

  @Test
  void bothBoundsCeilAndMonthSelectionUsesNormalizedEnd() {
    OrderQueryWindow window =
        window("2026-12-31T23:59:59.998000001+08:00", "2027-01-01T00:00:00.000000001+08:00");
    assertEquals(OffsetDateTime.parse("2026-12-31T23:59:59.999+08:00"), window.getStart());
    assertEquals(OffsetDateTime.parse("2027-01-01T00:00:00.001+08:00"), window.getEnd());
    assertEquals(YearMonth.of(2026, 12), window.getFirstMonth());
    assertEquals(YearMonth.of(2027, 1), window.getLastMonth());
  }

  @Test
  void exactMillisecondsAndExclusiveNewMonthRemainUnchanged() {
    OrderQueryWindow window = window("2026-12-31T23:59:59.999+08:00", "2027-01-01T00:00:00+08:00");
    assertEquals(YearMonth.of(2026, 12), window.getLastMonth());
    assertEquals(0, window.getEnd().getNano());
    assertEquals(999_000_000, window.getStart().getNano());
  }

  @Test
  void defaultsDeriveFromOneClockSampleAndNormalizeOffsets() {
    OrderQueryWindow defaults = OrderQueryWindow.from(null, null, NOW);
    assertEquals(OffsetDateTime.parse("2027-02-28T12:00:00.001+08:00"), defaults.getStart());
    assertEquals(OffsetDateTime.parse("2027-03-31T12:00:01.001+08:00"), defaults.getEnd());
    OrderQueryWindow offset =
        window("2026-12-31T16:00:00.000000001Z", "2027-01-01T01:00:00.002+09:00");
    assertEquals(OffsetDateTime.parse("2027-01-01T00:00:00.001+08:00"), offset.getStart());
    assertEquals(YearMonth.of(2027, 1), offset.getFirstMonth());
  }

  @Test
  void validNanosecondIntervalCanContainNoStoredMillisecond() {
    OrderQueryWindow empty =
        window("2026-12-31T23:59:59.999000001+08:00", "2027-01-01T00:00:00+08:00");
    assertTrue(empty.isEmpty());
    assertThrows(IllegalStateException.class, empty::getFirstMonth);
    assertThrows(IllegalStateException.class, empty::getLastMonth);
  }

  @Test
  void invalidInputsAndDatabaseYearOverflowReturn400() {
    for (String[] bounds :
        List.of(
            new String[] {"bad", NOW.toString()},
            new String[] {NOW.toString(), ""},
            new String[] {NOW.toString(), NOW.toString()},
            new String[] {NOW.plusSeconds(1).toString(), NOW.toString()},
            new String[] {"0999-12-31T23:59:59.999000001+08:00", "1000-01-01T00:00:00.001+08:00"},
            new String[] {"9999-12-31T23:59:59.999+08:00", "9999-12-31T23:59:59.999000001+08:00"},
            new String[] {"9999-12-31T23:59:59+08:00", "+10000-01-01T00:00:00+08:00"})) {
      assertEquals(
          400,
          assertThrows(BizException.class, () -> window(bounds[0], bounds[1])).getHttpStatus());
    }
  }

  @Test
  void ceilingPreservesMembershipOfEveryStoredTickAroundMonthBoundary() {
    OffsetDateTime midnight = OffsetDateTime.parse("2027-01-01T00:00:00+08:00");
    long[] edges = {-1_000_000, -999_999, -1, 0, 1, 999_999, 1_000_000, 1_000_001};
    for (long lower : edges) {
      for (long upper : edges) {
        if (lower >= upper) continue;
        OffsetDateTime rawStart = midnight.plusNanos(lower);
        OffsetDateTime rawEnd = midnight.plusNanos(upper);
        OrderQueryWindow normalized = window(rawStart.toString(), rawEnd.toString());
        for (int tick = -2; tick <= 2; tick++) {
          OffsetDateTime stored = midnight.plusNanos(tick * 1_000_000L);
          assertEquals(
              !stored.isBefore(rawStart) && stored.isBefore(rawEnd),
              !stored.isBefore(normalized.getStart()) && stored.isBefore(normalized.getEnd()));
        }
      }
    }
  }

  @Test
  void normalizedLocalValuesAgreeWithMonthlyShardingAlgorithm() {
    OrderQueryWindow window =
        window("2026-12-31T23:59:59.999000001+08:00", "2027-01-01T00:00:00.000000001+08:00");
    assertEquals(
        List.of("mall_order_202701"),
        new MonthlyOrderShardingAlgorithm()
            .doSharding(
                List.of("mall_order_202612", "mall_order_202701"),
                new RangeShardingValue<>(
                    "mall_order",
                    "created_at",
                    Range.closedOpen(
                        window.getStart().toLocalDateTime(), window.getEnd().toLocalDateTime()))));
  }

  @Test
  void emptyWindowReturnsUsualPaginationWithoutCallingMapper() {
    OrderSqlMapper mapper = mock(OrderSqlMapper.class);
    OrderServiceImpl service =
        new OrderServiceImpl(
            mock(StockClient.class),
            mock(ProductClient.class),
            mock(UserClient.class),
            mock(CartClient.class),
            mapper,
            new ObjectMapper());
    try {
      AuthContext.set(7L, "USER");
      PageResult<?> page =
          (PageResult<?>)
              service.list(
                      null,
                      5,
                      200,
                      "2026-12-31T23:59:59.999000001+08:00",
                      "2027-01-01T00:00:00+08:00")
                  .data;
      assertEquals(0, page.total);
      assertTrue(page.items.isEmpty());
      assertEquals(5, page.page);
      assertEquals(100, page.pageSize);
      verifyNoInteractions(mapper);
    } finally {
      AuthContext.clear();
    }
  }

  private OrderQueryWindow window(String start, String end) {
    return OrderQueryWindow.from(start, end, NOW);
  }
}
