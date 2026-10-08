package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderStockActionMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 非法完成目标必须在时间处理和SQL前拒绝，不清理当前租约。 */
class OrderStockActionStateTest {
  @Test
  void nonTerminalOrMalformedTargetsNeverTouchJdbc() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderStockActionMapper mapper = new OrderStockActionMapper(jdbc);
    for (String status :
        Arrays.asList(
            null,
            "",
            " \t",
            "succeeded",
            "conflict",
            "SUCCEEDED ",
            "CÓNFLICT",
            "OTHER",
            "PENDING",
            "UNKNOWN",
            "a".repeat(17))) {
      assertEquals(0, mapper.finish(1L, "valid-owner", status, "ignored", null));
      assertEquals(0, mapper.finish(1L, "valid-owner", status, "ignored", LocalDateTime.MAX));
    }
    assertEquals(0, mapper.finish(1L, null, "SUCCEEDED", null, null));
    verifyNoInteractions(jdbc);
  }
}
