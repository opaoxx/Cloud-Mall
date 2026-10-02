package com.cloudmall.stock;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import com.cloudmall.stock.mapper.StockSqlMapper;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.stream.LongStream;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.jdbc.core.JdbcTemplate;

/** StockSqlMapper 缓存重建 SQL 的边界测试。 */
class StockSqlMapperCacheRepairTest {
  @Test
  void cursorReadClampsBatchToTheTwoThousandRowBound() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.queryForList(anyString(), any(Long.class), any(Integer.class)))
        .thenReturn(List.of(Map.of("sku_id", 9L, "available_quantity", 4, "version", 2)));
    StockSqlMapper mapper = new StockSqlMapper(jdbcTemplate);

    assertEquals(1, mapper.findAvailableStockBatchAfter(8L, 5000).size());

    verify(jdbcTemplate).queryForList(anyString(), org.mockito.ArgumentMatchers.eq(8L),
        org.mockito.ArgumentMatchers.eq(2000));
  }

  @Test
  void repairEnqueueUsesOneBoundedInsertSelectAndRejectsOversizedBatches() {
    JdbcTemplate jdbcTemplate = mock(JdbcTemplate.class);
    when(jdbcTemplate.update(anyString(), any(), any(), any(), any(), any())).thenReturn(2);
    StockSqlMapper mapper = new StockSqlMapper(jdbcTemplate);
    OffsetDateTime occurredAt = OffsetDateTime.parse("2026-10-02T10:00:00+08:00");

    assertEquals(2, mapper.enqueueCacheRepairsForSkuIds(List.of(7L, 9L), occurredAt));

    ArgumentCaptor<String> sql = ArgumentCaptor.forClass(String.class);
    verify(jdbcTemplate).update(sql.capture(), org.mockito.ArgumentMatchers.eq(occurredAt),
        org.mockito.ArgumentMatchers.eq(occurredAt),
        org.mockito.ArgumentMatchers.eq(occurredAt),
        org.mockito.ArgumentMatchers.eq(7L), org.mockito.ArgumentMatchers.eq(9L));
    org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("INSERT INTO stock_cache_repair"));
    org.junit.jupiter.api.Assertions.assertTrue(sql.getValue().contains("FROM stock_sku WHERE sku_id IN (?,?)"));
    assertThrows(
        IllegalArgumentException.class,
        () -> mapper.enqueueCacheRepairsForSkuIds(
            LongStream.rangeClosed(1, 2001).boxed().toList(), occurredAt));
  }
}
