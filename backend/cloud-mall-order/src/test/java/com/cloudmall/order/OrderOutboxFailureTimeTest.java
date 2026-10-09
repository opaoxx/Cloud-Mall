package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderOutboxMapper;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 失败记录入口的时间参数拒绝和上海毫秒绑定契约。 */
class OrderOutboxFailureTimeTest {
  @Test
  void invalidRetryTimesAreRejectedBeforeAnyJdbcCall() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    OrderOutboxMapper mapper = new OrderOutboxMapper(jdbcTemplate);
    for (LocalDateTime retryAt :
        Arrays.asList(
            null,
            LocalDateTime.of(999, 12, 31, 23, 59),
            LocalDateTime.of(10000, 1, 1, 0, 0),
            LocalDateTime.MIN,
            LocalDateTime.MAX)) {
      assertThrows(
          InvalidDataAccessApiUsageException.class,
          () -> mapper.recordOutboxFailure("missing-event", "failure", retryAt),
          String.valueOf(retryAt));
    }
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void bothBoundTimesUseFlooredMillisecondsWithoutReplacingTheSuppliedTime() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    OrderOutboxMapper mapper = new OrderOutboxMapper(jdbcTemplate);
    AtomicReference<Object[]> capturedParameters = new AtomicReference<>();
    doAnswer(
            invocation -> {
              Object[] arguments = invocation.getArguments();
              capturedParameters.set(Arrays.copyOfRange(arguments, 1, arguments.length));
              return 1;
            })
        .when(jdbcTemplate)
        .update(
            anyString(),
            anyString(),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            anyString(),
            anyString());
    LocalDateTime retryAt = LocalDateTime.parse("2026-12-31T23:59:59.999999");
    LocalDateTime before =
        LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
    assertEquals(1, mapper.recordOutboxFailure("exact-event", "failure😀", retryAt));
    LocalDateTime after =
        LocalDateTime.now(ZoneId.of("Asia/Shanghai")).truncatedTo(ChronoUnit.MILLIS);
    Object[] parameters = capturedParameters.get();
    assertNotNull(parameters);
    assertEquals(5, parameters.length);
    assertEquals("failure😀", parameters[0]);
    assertEquals(LocalDateTime.parse("2026-12-31T23:59:59.999"), parameters[1]);
    LocalDateTime updatedAt = (LocalDateTime) parameters[2];
    assertEquals(0, updatedAt.getNano() % 1_000_000);
    assertFalse(updatedAt.isBefore(before));
    assertFalse(updatedAt.isAfter(after));
    assertEquals("exact-event", parameters[3]);
    assertEquals("exact-event", parameters[4]);
    assertEquals(LocalDateTime.parse("2026-12-31T23:59:59.999999"), retryAt);
    verify(jdbcTemplate)
        .update(
            anyString(),
            anyString(),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            anyString(),
            anyString());
    verifyNoMoreInteractions(jdbcTemplate);
  }
}
