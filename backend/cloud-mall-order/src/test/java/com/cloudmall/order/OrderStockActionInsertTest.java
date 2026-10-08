package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import com.cloudmall.order.mapper.OrderStockActionMapper;
import java.time.LocalDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 非法输入必须在数据库访问之前失败，不依赖sql_mode或连接是否可用。 */
class OrderStockActionInsertTest {
  @Test
  void invalidInputsNeverTouchJdbc() {
    LocalDateTime occurredAt = LocalDateTime.of(2026, 10, 8, 12, 0);
    List<Object[]> invalidInputs =
        List.of(
            new Object[] {0L, "order-1", "CONFIRM", occurredAt},
            new Object[] {-1L, "order-1", "CONFIRM", occurredAt},
            new Object[] {1L, null, "CONFIRM", occurredAt},
            new Object[] {1L, "", "CONFIRM", occurredAt},
            new Object[] {1L, " \t", "CONFIRM", occurredAt},
            new Object[] {1L, "a".repeat(65), "CONFIRM", occurredAt},
            new Object[] {1L, "😀".repeat(65), "CONFIRM", occurredAt},
            new Object[] {1L, "order-1", null, occurredAt},
            new Object[] {1L, "order-1", "", occurredAt},
            new Object[] {1L, "order-1", " \t", occurredAt},
            new Object[] {1L, "order-1", "confirm", occurredAt},
            new Object[] {1L, "order-1", "CONFIRM ", occurredAt},
            new Object[] {1L, "order-1", "OTHER", occurredAt},
            new Object[] {1L, "order-1", "a".repeat(17), occurredAt},
            new Object[] {1L, "order-1", "CONFIRM", null});
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderStockActionMapper mapper = new OrderStockActionMapper(jdbc);
    for (Object[] input : invalidInputs) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              mapper.insert(
                  (Long) input[0], (String) input[1], (String) input[2], (LocalDateTime) input[3]));
    }
    verifyNoInteractions(jdbc);
  }
}
