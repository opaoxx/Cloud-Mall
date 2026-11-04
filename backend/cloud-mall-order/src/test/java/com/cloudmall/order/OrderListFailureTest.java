package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;

import com.cloudmall.common.auth.AuthContext;
import com.cloudmall.common.error.BizException;
import com.cloudmall.order.controller.OrderQueryController;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.service.impl.OrderQueryServiceImpl;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.mockito.Answers;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 跨月列表中任何一个月读取失败都不能返回缺数据的成功页。 */
class OrderListFailureTest {
  @AfterEach
  void clearAuthentication() {
    AuthContext.clear();
  }

  @Test
  void databaseReadFailureDoesNotReturnAnIncompleteSuccessfulPage() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class, invocation -> {
      if (invocation.getMethod().getName().startsWith("query"))
        throw new DataAccessResourceFailureException("monthly order table unavailable");
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    OrderSqlMapper orderSqlMapper = new OrderSqlMapper(jdbcTemplate);
    OrderQueryController orderService =
        new OrderQueryController(new OrderQueryServiceImpl(orderSqlMapper));
    AuthContext.set(7L, "USER");

    assertThrows(
        BizException.class,
        () -> orderService.list(
            null, 1, 20,
            "2026-08-31T00:00:00+08:00",
            "2026-10-01T00:00:00+08:00"));
  }

  @Test
  void queryOffsetIsConvertedToShanghaiMonthBeforeRouting() {
    List<String> executedSql = new ArrayList<>();
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class, invocation -> {
      if (invocation.getMethod().getName().startsWith("query")) {
        executedSql.add(String.valueOf((Object) invocation.getArgument(0)));
        if (invocation.getMethod().getName().equals("queryForObject")) return 0L;
        return List.of();
      }
      return Answers.RETURNS_DEFAULTS.answer(invocation);
    });
    OrderQueryController orderService =
        new OrderQueryController(new OrderQueryServiceImpl(new OrderSqlMapper(jdbcTemplate)));
    AuthContext.set(7L, "USER");

    orderService.list(
        null, 1, 20,
        "2026-09-01T00:30:00+09:00",
        "2026-09-01T00:45:00+09:00");

    assertTrue(
        executedSql.stream().anyMatch(sql -> sql.contains("mall_order_202608")),
        "该时间段在上海时区属于 2026 年 8 月");
  }
}
