package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderSqlMapper;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;

/** 存在性单次候选读取的三态及不可信原始投影边界。 */
class OrderExistenceIdentityTest {
  @Test
  void onlyExactSingleCandidateProvesExistenceAndBadEvidenceIsNeverAbsence() {
    String original = "202610recover1";
    for (Object stored :
        Arrays.asList(
            original,
            "202610RECOVER1",
            "202610récovér1",
            original + " ",
            "202610other",
            null,
            123L)) {
      JdbcTemplate jdbc = mock(JdbcTemplate.class);
      OrderSqlMapper mapper = new OrderSqlMapper(jdbc);
      when(jdbc.queryForList(anyString(), eq(original)))
          .thenReturn(List.of(Collections.singletonMap("order_no", stored)));
      if (original.equals(stored)) assertTrue(mapper.orderExists("mall_order_202610", original));
      else
        assertThrows(
            IllegalStateException.class, () -> mapper.orderExists("mall_order_202610", original));
      verify(jdbc).queryForList(anyString(), eq(original));
      verifyNoMoreInteractions(jdbc);
    }
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    OrderSqlMapper mapper = new OrderSqlMapper(jdbc);
    when(jdbc.queryForList(anyString(), eq(original))).thenReturn(List.of());
    assertFalse(mapper.orderExists("mall_order_202610", original));
    when(jdbc.queryForList(anyString(), eq(original)))
        .thenReturn(List.of(Map.of("order_no", original), Map.of("order_no", original)));
    assertThrows(
        IllegalStateException.class, () -> mapper.orderExists("mall_order_202610", original));
    verify(jdbc, times(2)).queryForList(anyString(), eq(original));
    verifyNoMoreInteractions(jdbc);
  }

  @Test
  void databaseExceptionIsPropagatedRatherThanInventingAbsence() {
    JdbcTemplate jdbc = mock(JdbcTemplate.class);
    DataAccessResourceFailureException failure =
        new DataAccessResourceFailureException("read unavailable");
    when(jdbc.queryForList(anyString(), anyString())).thenThrow(failure);
    OrderSqlMapper mapper = new OrderSqlMapper(jdbc);
    assertSame(
        failure,
        assertThrows(
            DataAccessResourceFailureException.class,
            () -> mapper.orderExists("mall_order_202610", "202610recover1")));
    verify(jdbc).queryForList(anyString(), eq("202610recover1"));
    verifyNoMoreInteractions(jdbc);
  }
}
