package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderStockActionMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 非法owner在时间与数据库访问前零更新，保护标识的完整字符串。 */
class OrderStockActionOwnerTest {
  @Test
  void invalidOwnersNeverTouchJdbcEvenWhenTimeIsInvalid() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderStockActionMapper mapper = new OrderStockActionMapper(jdbc);
    LocalDateTime validTime = LocalDateTime.of(2026, 10, 8, 12, 0);
    for (String owner :
        Arrays.asList(null, "", " \t", "a".repeat(129), "中".repeat(129), "😀".repeat(129))) {
      for (LocalDateTime time : Arrays.asList(validTime, null, LocalDateTime.MAX)) {
        assertEquals(0, mapper.claim(1L, owner, time));
        assertEquals(0, mapper.finish(1L, owner, "SUCCEEDED", null, time));
        assertEquals(0, mapper.retry(1L, owner, "unknown", time, time));
      }
    }
    verifyNoInteractions(jdbc);
  }
}
