package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.ArgumentMatchers.isNull;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.common.api.ApiResponse;
import com.cloudmall.common.api.PageResult;
import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.order.domain.vo.OrderVO;
import com.cloudmall.order.feign.CartClient;
import com.cloudmall.order.feign.ProductClient;
import com.cloudmall.order.feign.StockClient;
import com.cloudmall.order.feign.UserClient;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderServiceImpl;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.math.BigDecimal;
import java.sql.Timestamp;
import java.util.Arrays;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.jdbc.core.JdbcTemplate;

/** 跨月列表只读取目标页，并保留精确 total 和稳定排序键。 */
class OrderMonthlyPaginationTest {
  @AfterEach
  void clearAuthentication() {
    AuthContext.clear();
  }

  @Test
  void skipsWholeNewerMonthAndReadsOnlyTheRequestedOlderPage() {
    OrderSqlMapper mapper = mock(OrderSqlMapper.class);
    Map<String, Long> counts = Map.of(
        "mall_order_202608", 3L,
        "mall_order_202609", 2L,
        "mall_order_202610", 0L);
    when(mapper.countOrders(anyString(), eq(7L), any(), any(), isNull()))
        .thenAnswer(invocation -> counts.get(invocation.getArgument(0)));
    when(mapper.findOrderPage(eq("mall_order_202608"), eq(7L), any(), any(),
            isNull(), eq(0L), eq(2)))
        .thenReturn(List.of(
            row("202608C", "2026-08-31 20:00:00.000"),
            row("202608B", "2026-08-31 19:00:00.000")));
    AuthContext.set(7L, "USER");

    ApiResponse<?> response = service(mapper).list(
        null, 2, 2,
        "2026-08-01T00:00:00+08:00",
        "2026-11-01T00:00:00+08:00");

    PageResult<?> result = (PageResult<?>) response.data;
    assertEquals(5L, result.total);
    assertEquals(2, result.items.size());
    assertEquals("202608C", ((OrderVO) result.items.get(0)).orderNo);
    assertEquals("202608B", ((OrderVO) result.items.get(1)).orderNo);
    verify(mapper, times(1)).findOrderPage(anyString(), eq(7L), any(), any(),
        isNull(), anyLong(), anyInt());
  }

  @Test
  void pageSpanningTwoMonthsKeepsNewestMonthFirst() {
    OrderSqlMapper mapper = mock(OrderSqlMapper.class);
    when(mapper.countOrders(eq("mall_order_202609"), eq(7L), any(), any(), isNull()))
        .thenReturn(1L);
    when(mapper.countOrders(eq("mall_order_202608"), eq(7L), any(), any(), isNull()))
        .thenReturn(2L);
    when(mapper.findOrderPage(eq("mall_order_202609"), eq(7L), any(), any(),
            isNull(), eq(0L), eq(1)))
        .thenReturn(List.of(row("202609A", "2026-09-01 00:00:00.000")));
    when(mapper.findOrderPage(eq("mall_order_202608"), eq(7L), any(), any(),
            isNull(), eq(0L), eq(1)))
        .thenReturn(List.of(row("202608Z", "2026-08-31 23:59:59.999")));
    AuthContext.set(7L, "USER");

    PageResult<?> result = (PageResult<?>) service(mapper).list(
        null, 1, 2,
        "2026-08-01T00:00:00+08:00",
        "2026-10-01T00:00:00+08:00").data;

    assertEquals(3L, result.total);
    assertEquals("202609A", ((OrderVO) result.items.get(0)).orderNo);
    assertEquals("202608Z", ((OrderVO) result.items.get(1)).orderNo);
  }

  @Test
  void hugePageDoesNotOverflowOrFetchAllShardRows() {
    OrderSqlMapper mapper = mock(OrderSqlMapper.class);
    when(mapper.countOrders(anyString(), eq(7L), any(), any(), isNull()))
        .thenReturn(1L);
    AuthContext.set(7L, "USER");

    PageResult<?> result = (PageResult<?>) service(mapper).list(
        null, Integer.MAX_VALUE, 100,
        "2026-08-01T00:00:00+08:00",
        "2026-10-01T00:00:00+08:00").data;

    assertEquals(2L, result.total);
    assertTrue(result.items.isEmpty());
    verify(mapper, never()).findOrderPage(anyString(), anyLong(), any(), any(),
        isNull(), anyLong(), anyInt());
  }

  @Test
  void mapperUsesStableOrderAndDatabaseLimit() {
    AtomicReference<String> executedSql = new AtomicReference<>();
    AtomicReference<Object[]> boundParameters = new AtomicReference<>();
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class, invocation -> {
      if (invocation.getMethod().getName().equals("queryForList")) {
        Object[] arguments = invocation.getArguments();
        executedSql.set(String.valueOf((Object) arguments[0]));
        boundParameters.set(Arrays.copyOfRange(arguments, 1, arguments.length));
        return List.of();
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    OrderSqlMapper mapper = new OrderSqlMapper(jdbcTemplate);

    mapper.findOrderPage(
        "mall_order_202609", 7L,
        Timestamp.valueOf("2026-09-01 00:00:00"),
        Timestamp.valueOf("2026-10-01 00:00:00"),
        null, 400L, 20);

    assertTrue(executedSql.get() != null);
    assertTrue(executedSql.get().contains("order by created_at desc,order_no desc limit ? offset ?"));
    Object[] bound = boundParameters.get();
    assertEquals(20, bound[bound.length - 2]);
    assertEquals(400L, bound[bound.length - 1]);
  }

  private static Map<String, Object> row(String orderNo, String createdAt) {
    Map<String, Object> order = new HashMap<>();
    order.put("order_no", orderNo);
    order.put("user_id", 7L);
    order.put("status", "PENDING_PAYMENT");
    order.put("total_amount", new BigDecimal("12.30"));
    order.put("pay_amount", new BigDecimal("12.30"));
    order.put("address_snapshot", "{}");
    order.put("created_at", Timestamp.valueOf(createdAt));
    order.put("expire_at", null);
    return order;
  }

  private static OrderServiceImpl service(OrderSqlMapper mapper) {
    return new OrderServiceImpl(
        mock(StockClient.class),
        mock(ProductClient.class),
        mock(UserClient.class),
        mock(CartClient.class),
        mapper,
        new ObjectMapper());
  }
}
