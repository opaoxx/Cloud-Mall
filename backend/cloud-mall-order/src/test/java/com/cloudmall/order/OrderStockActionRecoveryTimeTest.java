package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderStockActionMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 恢复时间非法时不接触JDBC，空owner继续保持原零更新语义。 */
class OrderStockActionRecoveryTimeTest {
  @Test
  void invalidRecoveryTimesNeverTouchJdbcAndBlankOwnersStillReturnZero() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderStockActionMapper mapper = new OrderStockActionMapper(jdbc);
    LocalDateTime validTime = LocalDateTime.of(2026, 10, 8, 12, 0);
    for (LocalDateTime invalidTime :
        Arrays.asList(
            null,
            LocalDateTime.of(999, 1, 1, 0, 0),
            LocalDateTime.of(0, 1, 1, 0, 0),
            LocalDateTime.of(10000, 1, 1, 0, 0),
            LocalDateTime.MIN,
            LocalDateTime.MAX)) {
      assertThrows(IllegalArgumentException.class, () -> mapper.findDue(invalidTime, 1));
      assertThrows(IllegalArgumentException.class, () -> mapper.claim(1L, "owner", invalidTime));
      assertThrows(
          IllegalArgumentException.class,
          () -> mapper.finish(1L, "owner", "SUCCEEDED", null, invalidTime));
      assertThrows(
          IllegalArgumentException.class,
          () -> mapper.retry(1L, "owner", "unknown", validTime, invalidTime));
      assertThrows(
          IllegalArgumentException.class,
          () -> mapper.retry(1L, "owner", "unknown", invalidTime, validTime));
    }
    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.claim(1L, "owner", LocalDateTime.of(9999, 12, 31, 23, 59, 50)));
    assertEquals(0, mapper.finish(1L, null, "SUCCEEDED", null, null));
    assertEquals(0, mapper.finish(1L, " \t", "SUCCEEDED", null, LocalDateTime.MAX));
    assertEquals(0, mapper.retry(1L, "", "unknown", null, null));
    assertEquals(0, mapper.retry(1L, null, "unknown", LocalDateTime.MAX, LocalDateTime.MIN));
    verifyNoInteractions(jdbc);
  }
}
