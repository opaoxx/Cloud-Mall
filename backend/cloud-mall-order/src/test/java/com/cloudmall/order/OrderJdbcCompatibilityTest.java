package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.util.OrderJdbcLocalDateTime;
import java.sql.ResultSet;
import java.sql.ResultSetMetaData;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.sql.Types;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TimeZone;
import org.apache.shardingsphere.sharding.execute.sql.execute.result.MemoryQueryResult;
import org.apache.shardingsphere.sharding.execute.sql.execute.result.StreamQueryResult;
import org.apache.shardingsphere.sql.parser.SQLParserEngine;
import org.apache.shardingsphere.sql.parser.sql.segment.dml.expr.simple.ParameterMarkerExpressionSegment;
import org.apache.shardingsphere.sql.parser.sql.segment.dml.predicate.value.PredicateCompareRightValue;
import org.apache.shardingsphere.sql.parser.sql.statement.dml.UpdateStatement;
import org.apache.shardingsphere.underlying.executor.QueryResult;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;

/** 无Spring/连接的实际4.1.1参数、字符物化及严格DATETIME读取兼容验证。 */
class OrderJdbcCompatibilityTest {
  /** 与真实组件IT相同的固定六值，含公历切换前后及MySQL年份边界。 */
  private static final List<String> COMPONENTS =
      List.of(
          "1000-01-01 00:00:00.001",
          "1582-10-10 12:34:56.123",
          "1900-03-01 01:02:03.001",
          "2000-02-29 23:59:59.999",
          "2038-01-19 03:14:08.456",
          "9999-12-31 23:59:59.999");

  /** 受控投影别名，不读取原DATETIME列。 */
  private static final String ALIAS = "cm_local_value";

  @Test
  void statusUpdateRetainsAllParserParameterIndices() {
    CapturingJdbcTemplate jdbc = new CapturingJdbcTemplate();
    Timestamp updated = Timestamp.valueOf("2027-01-16 09:10:11.123");
    assertEquals(
        1,
        new OrderSqlMapper(jdbc)
            .updateStatus("mall_order_202701", "202701Z", "PAID", "PENDING_PAYMENT", updated));
    assertArrayEquals(
        new Object[] {
          "PAID", updated, "PAID", updated, "PAID", updated, "202701Z", "PENDING_PAYMENT"
        },
        jdbc.arguments);
    UpdateStatement physical = parse(jdbc.sql);
    assertEquals(8, physical.getParameterCount());
    assertEquals(Map.of("order_no=", 6, "status=", 7), whereIndices(physical));
    assertTrue(jdbc.sql.contains("IF(?='PAID',?,paid_at)"));
    assertTrue(jdbc.sql.contains("IF(?='CANCELLED',?,cancelled_at)"));
    UpdateStatement shadow = parse(OrderListShardingShadowMySqlIT.logicalCasSql());
    assertEquals(10, shadow.getParameterCount());
    assertEquals(
        Map.of("order_no=", 6, "status=", 7, "created_at>=", 8, "created_at<", 9),
        whereIndices(shadow));
  }

  @Test
  void projectedTextPreservesNullableCalendarComponents() throws Exception {
    TimeZone original = TimeZone.getDefault();
    try {
      for (String zone : List.of("Asia/Shanghai", "UTC")) {
        TimeZone.setDefault(TimeZone.getTimeZone(zone));
        for (String text : COMPONENTS)
          assertText(text, LocalDateTime.parse(text.replace(' ', 'T')));
        assertText("2026-10-16 01:02:03", LocalDateTime.of(2026, 10, 16, 1, 2, 3));
        assertText("2026-10-16 01:02:03.1", LocalDateTime.of(2026, 10, 16, 1, 2, 3, 100_000_000));
        assertText("2026-10-16 01:02:03.12", LocalDateTime.of(2026, 10, 16, 1, 2, 3, 120_000_000));
        ResultSet absent = mock(ResultSet.class);
        assertNull(OrderJdbcLocalDateTime.readNullable(absent, ALIAS));
        verify(absent).getString(ALIAS);
        verifyNoMoreInteractions(absent);
      }
    } finally {
      TimeZone.setDefault(original);
    }
  }

  @Test
  void invalidTextFailsAndJdbcExceptionsPropagate() throws Exception {
    for (String text :
        List.of(
            "0000-01-01 00:00:00",
            "2026-01-00 00:00:00",
            "1900-02-29 00:00:00",
            "0999-12-31 23:59:59",
            "10000-01-01 00:00:00",
            "",
            " 2026-10-16 01:02:03",
            "2026-10-16T01:02:03+08:00",
            "2026-10-16 01:02:03.1234")) {
      ResultSet invalid = mock(ResultSet.class);
      when(invalid.getString(ALIAS)).thenReturn(text);
      SQLException nullable =
          assertThrows(
              SQLException.class, () -> OrderJdbcLocalDateTime.readNullable(invalid, ALIAS));
      SQLException required =
          assertThrows(
              SQLException.class, () -> OrderJdbcLocalDateTime.readRequired(invalid, ALIAS));
      assertEquals("22007", nullable.getSQLState());
      assertEquals("22007", required.getSQLState());
      assertEquals("Invalid projected DATETIME(3) components", nullable.getMessage());
      verify(invalid, times(2)).getString(ALIAS);
      verifyNoMoreInteractions(invalid);
    }
    ResultSet absent = mock(ResultSet.class);
    assertEquals(
        "22007",
        assertThrows(SQLException.class, () -> OrderJdbcLocalDateTime.readRequired(absent, ALIAS))
            .getSQLState());
    ResultSet broken = mock(ResultSet.class);
    SQLException original = new SQLException("controlled driver failure", "HY000");
    when(broken.getString(ALIAS)).thenThrow(original);
    assertSame(
        original,
        assertThrows(SQLException.class, () -> OrderJdbcLocalDateTime.readNullable(broken, ALIAS)));
    assertSame(
        original,
        assertThrows(SQLException.class, () -> OrderJdbcLocalDateTime.readRequired(broken, ALIAS)));
    verify(broken, times(2)).getString(ALIAS);
    verifyNoMoreInteractions(broken);
  }

  @Test
  void characterMetadataSurvivesStreamAndMemoryQueryResults() throws Exception {
    for (boolean memory : List.of(false, true)) {
      for (String text : COMPONENTS) {
        ResultSet source = mock(ResultSet.class);
        ResultSetMetaData metadata = mock(ResultSetMetaData.class);
        when(source.getMetaData()).thenReturn(metadata);
        when(metadata.getColumnCount()).thenReturn(1);
        when(metadata.getColumnType(1)).thenReturn(Types.VARCHAR);
        when(metadata.getColumnLabel(1)).thenReturn(ALIAS);
        when(metadata.getColumnName(1)).thenReturn(ALIAS);
        when(source.next()).thenReturn(true, false);
        when(source.getString(1)).thenReturn(text);
        QueryResult actual = memory ? new MemoryQueryResult(source) : new StreamQueryResult(source);
        assertTrue(actual.next());
        Object observed = actual.getValue(1, String.class);
        assertInstanceOf(String.class, observed);
        assertEquals(text, observed);
        ResultSet projected = mock(ResultSet.class);
        when(projected.getString(ALIAS)).thenReturn((String) observed);
        assertEquals(
            LocalDateTime.parse(text.replace(' ', 'T')),
            OrderJdbcLocalDateTime.readRequired(projected, ALIAS));
        assertFalse(actual.next());
        verify(source, never()).getTimestamp(anyInt());
        verify(source, never()).getObject(anyInt(), eq(LocalDateTime.class));
        verify(source, never()).getObject(anyString(), eq(LocalDateTime.class));
      }
    }
  }

  private static void assertText(String text, LocalDateTime expected) throws Exception {
    ResultSet result = mock(ResultSet.class);
    when(result.getString(ALIAS)).thenReturn(text);
    assertEquals(expected, OrderJdbcLocalDateTime.readRequired(result, ALIAS));
    assertEquals(expected, OrderJdbcLocalDateTime.readNullable(result, ALIAS));
    verify(result, times(2)).getString(ALIAS);
    verifyNoMoreInteractions(result);
  }

  private static UpdateStatement parse(String sql) {
    return (UpdateStatement) new SQLParserEngine("MySQL").parse(sql, false);
  }

  private static Map<String, Integer> whereIndices(UpdateStatement statement) {
    Map<String, Integer> indices = new LinkedHashMap<>();
    for (var and : statement.getWhere().orElseThrow().getAndPredicates()) {
      for (var predicate : and.getPredicates()) {
        PredicateCompareRightValue comparison =
            (PredicateCompareRightValue) predicate.getRightValue();
        ParameterMarkerExpressionSegment marker =
            (ParameterMarkerExpressionSegment) comparison.getExpression();
        String key = predicate.getColumn().getIdentifier().getValue() + comparison.getOperator();
        assertNull(indices.put(key, marker.getParameterMarkerIndex()));
      }
    }
    return indices;
  }

  /** 捕获生产Mapper真实SQL/实参；不创建DataSource或连接。 */
  private static final class CapturingJdbcTemplate extends JdbcTemplate {
    /** 最后一次生产UPDATE原SQL。 */
    private String sql;

    /** 最后一次生产UPDATE原参数及顺序。 */
    private Object[] arguments;

    /** 仅捕获真实Mapper的调用，不执行JDBC。 */
    @Override
    public int update(String sql, Object... arguments) {
      // 1. 保留原SQL文本。
      this.sql = sql;
      // 2. 独立保存参数顺序。
      this.arguments = arguments.clone();
      // 3. 受控影响一行，仅用于本地SQL装配测试。
      return 1;
    }
  }
}
