package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.domain.po.OrderStockActionPO;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentMatchers;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** NULL原字段用持久层替身覆盖，不绕过真实MySQL的NOT NULL约束。 */
class OrderStockActionReadIntegrityTest {
  @Test
  void missingTypeOrKeyCannotAuthorizeEitherReadSignature() {
    for (boolean missingType : List.of(true, false)) {
      JdbcTemplate jdbc = mock(JdbcTemplate.class);
      OrderStockActionPO action = completeAction();
      if (missingType) action.actionType = null;
      else action.operationKey = null;
      when(jdbc.query(
              anyString(), ArgumentMatchers.<RowMapper<OrderStockActionPO>>any(), eq("order-1")))
          .thenReturn(List.of(action));
      OrderStockActionMapper mapper = new OrderStockActionMapper(jdbc);

      assertEquals("UNKNOWN", mapper.statusForOrder("order-1"));
      assertEquals("UNKNOWN", mapper.statusForOrder("order-1", "CONFIRM"));

      verify(jdbc, times(2))
          .query(anyString(), ArgumentMatchers.<RowMapper<OrderStockActionPO>>any(), eq("order-1"));
      verifyNoMoreInteractions(jdbc);
    }
  }

  @Test
  void expectedDirectionMustMatchButAnActuallyMissingActionStillReturnsNull() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    when(jdbc.query(
            anyString(), ArgumentMatchers.<RowMapper<OrderStockActionPO>>any(), eq("order-1")))
        .thenReturn(List.of(completeAction()));
    OrderStockActionMapper mapper = new OrderStockActionMapper(jdbc);

    assertEquals("SUCCEEDED", mapper.statusForOrder("order-1"));
    assertEquals("SUCCEEDED", mapper.statusForOrder("order-1", "CONFIRM"));
    for (String expected : Arrays.asList("ROLLBACK", null, "", "confirm", "CONFIRM ", "CÓNFIRM"))
      assertEquals("UNKNOWN", mapper.statusForOrder("order-1", expected));
    verify(jdbc, times(8))
        .query(anyString(), ArgumentMatchers.<RowMapper<OrderStockActionPO>>any(), eq("order-1"));
    verifyNoMoreInteractions(jdbc);

    JdbcTemplate emptyJdbc = mock(JdbcTemplate.class);
    when(emptyJdbc.query(
            anyString(), ArgumentMatchers.<RowMapper<OrderStockActionPO>>any(), eq("order-1")))
        .thenReturn(List.of());
    assertNull(new OrderStockActionMapper(emptyJdbc).statusForOrder("order-1", null));
    verify(emptyJdbc)
        .query(anyString(), ArgumentMatchers.<RowMapper<OrderStockActionPO>>any(), eq("order-1"));
    verifyNoMoreInteractions(emptyJdbc);
  }

  @Test
  void missingOrMalformedStatusNeverLooksLikeAnAbsentAction() {
    for (String status :
        Arrays.asList(null, "", " \t", "succeeded", "SUCCEEDED ", "SÚCCEEDED", "OTHER")) {
      JdbcTemplate jdbc = mock(JdbcTemplate.class);
      OrderStockActionPO action = completeAction();
      action.status = status;
      when(jdbc.query(
              anyString(), ArgumentMatchers.<RowMapper<OrderStockActionPO>>any(), eq("order-1")))
          .thenReturn(List.of(action));
      OrderStockActionMapper mapper = new OrderStockActionMapper(jdbc);
      assertEquals("UNKNOWN", mapper.statusForOrder("order-1"));
      assertEquals("UNKNOWN", mapper.statusForOrder("order-1", "CONFIRM"));
      verify(jdbc, times(2))
          .query(anyString(), ArgumentMatchers.<RowMapper<OrderStockActionPO>>any(), eq("order-1"));
      verifyNoMoreInteractions(jdbc);
    }
  }

  /** 完整合法动作仅作读取证据，不模拟订单或库存状态写入。 */
  private static OrderStockActionPO completeAction() {
    OrderStockActionPO action = new OrderStockActionPO();
    action.orderNo = "order-1";
    action.actionType = "CONFIRM";
    action.operationKey = "order-1:CONFIRM";
    action.status = "SUCCEEDED";
    return action;
  }
}
