package com.cloudmall.product;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

import com.cloudmall.product.domain.dto.ProductMetricApplySnapshotDTO;
import com.cloudmall.product.mapper.ProductMetricMapper;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.UncategorizedSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** Local JDBC projection contracts; no connection, transaction, or MySQL lock is simulated. */
class ProductMetricApplySnapshotMappingTest {
  @Test
  void oneBoundQueryMapsEveryAggregateColumnWithoutLosingPrecision() throws Exception {
    Map<String, Object> cells = aggregateCells();
    ResultSet result = result(cells);
    SnapshotJdbcTemplate jdbc = new SnapshotJdbcTemplate(result, true);
    ProductMetricApplySnapshotDTO snapshot =
        new ProductMetricMapper(jdbc).applySnapshot("immutable-event", 41L);

    assertEquals(1, jdbc.queryCount);
    assertArrayEquals(new Object[] {"immutable-event", 41L}, jdbc.arguments);
    assertTrue(jdbc.sql.contains("FROM product_metric_control metric_control"));
    assertTrue(
        jdbc.sql.contains("LEFT JOIN product_metric_applied_event applied ON applied.event_id=?"));
    assertTrue(jdbc.sql.contains("LEFT JOIN product_online_stat stat ON stat.product_id=?"));
    assertTrue(jdbc.sql.endsWith("WHERE metric_control.singleton_id=1 FOR UPDATE"));
    assertFalse(jdbc.sql.contains("*"));
    assertEquals(2L, jdbc.sql.chars().filter(character -> character == '?').count());
    List<String> aliases = new ArrayList<>();
    Matcher matcher = Pattern.compile(" AS ([a-z_]+)").matcher(jdbc.sql);
    while (matcher.find()) aliases.add(matcher.group(1));
    assertEquals(
        List.of(
            "applied_event_id",
            "applied_fingerprint",
            "stat_product_id",
            "stat_view_count",
            "stat_search_count",
            "stat_sold_count",
            "stat_hot_score",
            "stat_metric_version",
            "stat_last_event_id",
            "stat_created_at",
            "stat_updated_at"),
        aliases);
    assertNull(snapshot.appliedEventId);
    assertNull(snapshot.appliedFingerprint);
    assertNotNull(snapshot.stat);
    assertEquals(41L, snapshot.stat.productId);
    assertEquals(0L, snapshot.stat.viewCount);
    assertEquals(9007199254740993L, snapshot.stat.searchCount);
    assertEquals(17L, snapshot.stat.soldCount);
    assertEquals(Long.MAX_VALUE, snapshot.stat.hotScore);
    assertEquals(9007199254740995L, snapshot.stat.metricVersion);
    assertNull(snapshot.stat.lastEventId);
    assertEquals(LocalDateTime.of(2026, 10, 22, 14, 18, 9, 123000000), snapshot.stat.createdAt);
    assertEquals(LocalDateTime.of(2026, 10, 22, 14, 18, 10, 456000000), snapshot.stat.updatedAt);
    verify(jdbc.dataSource, never()).getConnection();
  }

  @Test
  void existingLedgerIgnoresBothPresentAndAbsentAggregateColumns() throws Exception {
    for (boolean aggregatePresent : List.of(true, false)) {
      Map<String, Object> cells = aggregatePresent ? aggregateCells() : new HashMap<>();
      cells.put("applied_event_id", "immutable-event");
      cells.put("applied_fingerprint", "immutable-fingerprint");
      ResultSet result = result(cells);
      doAnswer(
              invocation -> {
                String column = invocation.getArgument(0);
                if (column.startsWith("stat_"))
                  throw new SQLException("Unrelated aggregate must not be interpreted");
                return cells.get(column);
              })
          .when(result)
          .getObject(anyString());
      doAnswer(
              invocation -> {
                String column = invocation.getArgument(0);
                if (column.startsWith("stat_"))
                  throw new SQLException("Unrelated aggregate must not be interpreted");
                return cells.get(column);
              })
          .when(result)
          .getString(anyString());
      SnapshotJdbcTemplate jdbc = new SnapshotJdbcTemplate(result, true);
      ProductMetricApplySnapshotDTO snapshot =
          new ProductMetricMapper(jdbc).applySnapshot("immutable-event", 41L);
      assertEquals("immutable-event", snapshot.appliedEventId);
      assertEquals("immutable-fingerprint", snapshot.appliedFingerprint);
      assertNull(snapshot.stat);
      verify(result).getString("applied_event_id");
      verify(result).getString("applied_fingerprint");
      verifyNoMoreInteractions(result);
      assertEquals(1, jdbc.queryCount);
    }
  }

  @Test
  void aggregatePresenceUsesItsIdentityAndPreservesNullableColumns() throws Exception {
    Map<String, Object> cells = aggregateCells();
    cells.put("stat_last_event_id", "previous-event");
    ProductMetricApplySnapshotDTO present =
        new ProductMetricMapper(new SnapshotJdbcTemplate(result(cells), true))
            .applySnapshot("new-event", 41L);
    assertEquals("previous-event", present.stat.lastEventId);

    cells.put("stat_product_id", null);
    ResultSet absentResult = result(cells);
    ProductMetricApplySnapshotDTO absent =
        new ProductMetricMapper(new SnapshotJdbcTemplate(absentResult, true))
            .applySnapshot("new-event", 41L);
    assertNull(absent.appliedEventId);
    assertNull(absent.appliedFingerprint);
    assertNull(absent.stat);
    verify(absentResult).getString("applied_event_id");
    verify(absentResult).getObject("stat_product_id");
    verifyNoMoreInteractions(absentResult);
  }

  @Test
  void missingControlAnchorFailsInsteadOfInventingAnEmptySnapshot() throws Exception {
    ResultSet result = mock(ResultSet.class);
    SnapshotJdbcTemplate jdbc = new SnapshotJdbcTemplate(result, false);
    IllegalStateException failure =
        assertThrows(
            IllegalStateException.class,
            () -> new ProductMetricMapper(jdbc).applySnapshot("new-event", 41L));
    assertEquals("METRIC_CONTROL_INVALID", failure.getMessage());
    assertEquals(1, jdbc.queryCount);
    verifyNoInteractions(result);
    verify(jdbc.dataSource, never()).getConnection();
  }

  @Test
  void resultReadFailurePropagatesWithItsOriginalCause() throws Exception {
    ResultSet result = mock(ResultSet.class);
    SQLException original = new SQLException("fixed result failure", "HY000", 1234);
    when(result.getString("applied_event_id")).thenThrow(original);
    SnapshotJdbcTemplate jdbc = new SnapshotJdbcTemplate(result, true);
    UncategorizedSQLException failure =
        assertThrows(
            UncategorizedSQLException.class,
            () -> new ProductMetricMapper(jdbc).applySnapshot("new-event", 41L));
    assertSame(original, failure.getCause());
    assertEquals(1, jdbc.queryCount);
  }

  private static Map<String, Object> aggregateCells() {
    Map<String, Object> cells = new HashMap<>();
    cells.put("stat_product_id", 41L);
    cells.put("stat_view_count", 0);
    cells.put("stat_search_count", 9007199254740993L);
    cells.put("stat_sold_count", 17);
    cells.put("stat_hot_score", Long.MAX_VALUE);
    cells.put("stat_metric_version", 9007199254740995L);
    cells.put("stat_created_at", LocalDateTime.of(2026, 10, 22, 14, 18, 9, 123000000));
    cells.put("stat_updated_at", Timestamp.valueOf("2026-10-22 14:18:10.456"));
    return cells;
  }

  private static ResultSet result(Map<String, Object> cells) throws SQLException {
    ResultSet result = mock(ResultSet.class);
    when(result.getObject(anyString()))
        .thenAnswer(invocation -> cells.get(invocation.getArgument(0)));
    when(result.getString(anyString()))
        .thenAnswer(
            invocation -> {
              Object value = cells.get(invocation.getArgument(0));
              return value == null ? null : value.toString();
            });
    return result;
  }

  /** Capture the actual query and invoke only its mapper against the supplied local ResultSet. */
  private static final class SnapshotJdbcTemplate extends JdbcTemplate {
    /** Unused connection source proves this fixture performs no database connection. */
    private final DataSource dataSource;

    /** Controlled local row consumed by the production RowMapper. */
    private final ResultSet result;

    /** Whether the control anchor returned its required singleton row. */
    private final boolean anchorPresent;

    /** Last exact production statement for SQL contract assertions. */
    private String sql;

    /** Last original production bindings, retained in their JDBC order. */
    private Object[] arguments;

    /** Number of production query delegations, independent of result getter counts. */
    private int queryCount;

    private SnapshotJdbcTemplate(ResultSet result, boolean anchorPresent) {
      this(mock(DataSource.class), result, anchorPresent);
    }

    private SnapshotJdbcTemplate(DataSource dataSource, ResultSet result, boolean anchorPresent) {
      super(dataSource);
      this.dataSource = dataSource;
      this.result = result;
      this.anchorPresent = anchorPresent;
    }

    /** Execute no SQL; capture one call and retain normal unchecked JDBC failure semantics. */
    @Override
    public <T> List<T> query(String sql, RowMapper<T> rowMapper, Object... arguments) {
      this.sql = sql;
      this.arguments = arguments.clone();
      queryCount++;
      if (!anchorPresent) return List.of();
      try {
        return List.of(rowMapper.mapRow(result, 0));
      } catch (SQLException failure) {
        throw new UncategorizedSQLException("apply snapshot", sql, failure);
      }
    }
  }
}
