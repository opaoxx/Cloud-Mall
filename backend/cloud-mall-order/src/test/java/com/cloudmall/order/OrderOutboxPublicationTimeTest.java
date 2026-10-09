package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderOutboxMapper;
import java.time.LocalDateTime;
import java.util.Arrays;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import org.springframework.dao.InvalidDataAccessApiUsageException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 发布完成时间的合法年份、毫秒精度和同一双位置绑定契约。 */
class OrderOutboxPublicationTimeTest {
  @Test
  void invalidPublicationTimesAreRejectedBeforeAnyJdbcCall() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    OrderOutboxMapper mapper = new OrderOutboxMapper(jdbcTemplate);
    for (LocalDateTime publishedAt :
        Arrays.asList(
            null,
            LocalDateTime.of(999, 12, 31, 23, 59),
            LocalDateTime.of(10000, 1, 1, 0, 0),
            LocalDateTime.MIN,
            LocalDateTime.MAX)) {
      assertThrows(
          InvalidDataAccessApiUsageException.class,
          () -> mapper.markOutboxPublished("missing-event", publishedAt),
          String.valueOf(publishedAt));
    }
    verifyNoInteractions(jdbcTemplate);
  }

  @Test
  void publicationAndUpdateUseTheSameFlooredSuppliedTimeWithoutResampling() {
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
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            anyString(),
            anyString());
    LocalDateTime publishedAt = LocalDateTime.parse("2026-12-31T23:59:59.999999");
    assertEquals(1, mapper.markOutboxPublished("exact-event", publishedAt));
    Object[] parameters = capturedParameters.get();
    assertNotNull(parameters);
    assertEquals(4, parameters.length);
    assertEquals(LocalDateTime.parse("2026-12-31T23:59:59.999"), parameters[0]);
    assertSame(parameters[0], parameters[1]);
    assertEquals("exact-event", parameters[2]);
    assertEquals("exact-event", parameters[3]);
    assertEquals(LocalDateTime.parse("2026-12-31T23:59:59.999999"), publishedAt);
    verify(jdbcTemplate)
        .update(
            anyString(),
            any(LocalDateTime.class),
            any(LocalDateTime.class),
            anyString(),
            anyString());
    verifyNoMoreInteractions(jdbcTemplate);
  }
}
