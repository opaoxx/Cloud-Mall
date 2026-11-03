package com.cloudmall.order;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.domain.po.OrderIdempotencyPO;
import com.cloudmall.order.mapper.OrderOutboxMapper;
import com.cloudmall.order.mapper.OrderSqlMapper;
import com.cloudmall.order.mapper.OrderStockActionMapper;
import java.sql.ResultSet;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import org.apache.shardingsphere.sql.parser.SQLParserEngine;
import org.apache.shardingsphere.sql.parser.sql.segment.dml.expr.simple.ParameterMarkerExpressionSegment;
import org.apache.shardingsphere.sql.parser.sql.segment.dml.predicate.value.PredicateCompareRightValue;
import org.apache.shardingsphere.sql.parser.sql.statement.dml.UpdateStatement;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;

/** 17EI原生产SQL/reader的本地合同；第一阶段保留为面向修后合同的测试。 */
class OrderReplayRecoveryJdbcCompatibilityTest {
  @Test
  void idempotentProjectionReadsLocalDateTimeWithoutTypedGetter() throws Exception {
    CapturingJdbc jdbc = new CapturingJdbc();
    assertEquals(List.of(), new OrderSqlMapper(jdbc).findIdempotentOrders(7L, "Key-Case"));
    assertTrue(jdbc.sql.contains("CAST(created_at AS CHAR) AS cm_local_created_at"));
    assertArrayEquals(new Object[] {7L, "Key-Case"}, jdbc.arguments);
    ResultSet row = mock(ResultSet.class);
    when(row.getLong("id")).thenReturn(21L);
    when(row.getLong("user_id")).thenReturn(7L);
    when(row.getString("idempotency_key")).thenReturn("Key-Case");
    when(row.getString("order_no")).thenReturn("158210Original");
    when(row.getString("cm_local_created_at")).thenReturn("1582-10-10 12:34:56.123");
    OrderIdempotencyPO actual = (OrderIdempotencyPO) jdbc.reader.mapRow(row, 0);
    assertEquals(21L, actual.id);
    assertEquals(7L, actual.userId);
    assertEquals("Key-Case", actual.idempotencyKey);
    assertEquals("158210Original", actual.orderNo);
    assertEquals(LocalDateTime.parse("1582-10-10T12:34:56.123"), actual.createdAt);
    verify(row).getLong("id");
    verify(row).getLong("user_id");
    verify(row).getString("idempotency_key");
    verify(row).getString("order_no");
    verify(row).getString("cm_local_created_at");
    verifyNoMoreInteractions(row);
  }

  @Test
  void recoveryWriteSqlRetainsParserBindingsAndExactPredicates() {
    CapturingJdbc jdbc = new CapturingJdbc();
    OrderStockActionMapper action = new OrderStockActionMapper(jdbc);
    OrderOutboxMapper outbox = new OrderOutboxMapper(jdbc);
    LocalDateTime input = LocalDateTime.parse("2026-10-16T12:34:56.123456789");
    LocalDateTime floor = LocalDateTime.parse("2026-10-16T12:34:56.123");
    String owner = "Original-Owner";
    assertEquals(1, action.claim(71L, owner, input));
    check(jdbc, 6, 6, new Object[] {owner, floor.plusSeconds(30), floor, 71L, floor, floor});
    String claim = jdbc.sql;
    assertTrue(
        claim.contains("attempts=CASE WHEN attempts<2147483647 THEN attempts+1 ELSE attempts END"));
    assertTrue(claim.contains("next_retry_at<=? AND (lease_until IS NULL OR lease_until<?)"));
    assertEquals(1, action.finish(71L, owner, "SUCCEEDED", null, input));
    check(jdbc, 6, 5, new Object[] {"SUCCEEDED", null, floor, 71L, owner, floor});
    exactOwner(jdbc.sql);
    assertEquals(1, action.retry(71L, owner, "original error", input.plusSeconds(5), input));
    check(jdbc, 6, 5, new Object[] {"original error", floor.plusSeconds(5), floor, 71L, owner, floor});
    exactOwner(jdbc.sql);
    assertEquals(1, outbox.markOutboxPublished("event-original", input));
    check(jdbc, 4, 3, new Object[] {floor, floor, "event-original", "event-original"});
    exactEvent(jdbc.sql);
    assertEquals(
        1, outbox.recordOutboxFailure("event-original", "original error", input.plusSeconds(10)));
    assertEquals(5, jdbc.arguments.length);
    assertEquals("original error", jdbc.arguments[0]);
    assertEquals(floor.plusSeconds(10), jdbc.arguments[1]);
    assertInstanceOf(LocalDateTime.class, jdbc.arguments[2]);
    assertEquals(0, ((LocalDateTime) jdbc.arguments[2]).getNano() % 1_000_000);
    assertEquals("event-original", jdbc.arguments[3]);
    assertEquals("event-original", jdbc.arguments[4]);
    assertEquals(4, parse(jdbc.sql).getParameterCount());
    assertEquals(Set.of(3), whereIndices(parse(jdbc.sql)).get("event_id="));
    exactEvent(jdbc.sql);
    assertTrue(
        jdbc.sql.contains(
            "attempts=CASE WHEN attempts<2147483647 THEN attempts+1 ELSE attempts END"));
  }

  /** JDBC完整绑定与有限AST各自核验，未收录的owner/CAST精确谓词仍由原SQL和真实链保护。 */
  private static void check(
      CapturingJdbc jdbc, int jdbcCount, int astCount, Object[] arguments) {
    assertEquals(jdbcCount, jdbc.arguments.length);
    assertArrayEquals(arguments, jdbc.arguments);
    assertEquals(astCount, parse(jdbc.sql).getParameterCount());
    if (jdbcCount == 4) assertEquals(Set.of(2), whereIndices(parse(jdbc.sql)).get("event_id="));
    if (jdbcCount == 6) {
      Map<String, Set<Integer>> indices = whereIndices(parse(jdbc.sql));
      assertEquals(Set.of(3), indices.get("id="));
      assertEquals(
          Set.of(jdbc.sql.contains("SET lease_owner=?") ? 5 : 4),
          indices.get(jdbc.sql.contains("SET lease_owner=?") ? "lease_until<" : "lease_until>"));
      if (jdbc.sql.contains("SET lease_owner=?"))
        assertEquals(Set.of(4), indices.get("next_retry_at<="));
      assertTrue(jdbc.sql.contains("status IN ('PENDING','UNKNOWN')"));
      assertTrue(jdbc.sql.contains("AND status IN (BINARY 'PENDING', BINARY 'UNKNOWN')"));
      assertFalse(jdbc.sql.contains("BINARY status IN"));
      assertTrue(jdbc.sql.contains("attempts>=0"));
    }
  }

  private static void exactOwner(String sql) {
    assertTrue(sql.contains("lease_owner IS NOT NULL AND BINARY lease_owner=BINARY ?"));
    assertTrue(sql.contains("lease_until IS NOT NULL AND lease_until>?"));
  }

  private static void exactEvent(String sql) {
    assertTrue(sql.contains("event_id=? AND CAST(event_id AS BINARY)=CAST(? AS BINARY)"));
    assertTrue(sql.contains("CAST(status AS BINARY)=CAST('PENDING' AS BINARY)"));
    assertTrue(
        sql.contains("CAST(event_type AS BINARY)=CAST('ORDER_TIMEOUT' AS BINARY) AND attempts>=0"));
  }

  /** 读取实际4.1.1谓词内marker索引，重复OR分支仅去重同一个真实index。 */
  private static Map<String, Set<Integer>> whereIndices(UpdateStatement statement) {
    Map<String, Set<Integer>> result = new LinkedHashMap<>();
    for (var and : statement.getWhere().orElseThrow().getAndPredicates())
      for (var predicate : and.getPredicates())
        if (predicate.getRightValue() instanceof PredicateCompareRightValue comparison
            && comparison.getExpression() instanceof ParameterMarkerExpressionSegment marker) {
          String key = predicate.getColumn().getIdentifier().getValue() + comparison.getOperator();
          result
              .computeIfAbsent(key, ignored -> new TreeSet<>())
              .add(marker.getParameterMarkerIndex());
        }
    return result;
  }

  private static UpdateStatement parse(String sql) {
    return (UpdateStatement) new SQLParserEngine("MySQL").parse(sql, false);
  }

  /** 只捕获原MapperSQL与reader，无DataSource或网络。 */
  private static final class CapturingJdbc extends JdbcTemplate {
    /** 原SQL，不从预期字符串重建。 */
    private String sql;

    /** 原参数顺序的独立副本。 */
    private Object[] arguments;

    /** 原Mapper实际RowMapper，测试直接交入受控ResultSet。 */
    private RowMapper<?> reader;

    /** 保留原UPDATE参数，仅本地装配测试返回受控1。 */
    @Override
    public int update(String sql, Object... arguments) {
      // 1. 捕获原SQL。
      this.sql = sql;
      // 2. 保留每个原参数位置。
      this.arguments = arguments.clone();
      // 3. 不执行数据库操作。
      return 1;
    }

    /** 保存原reader，不提前读取或用替代PO伪造返回。 */
    @Override
    public <T> List<T> query(String sql, RowMapper<T> reader, Object... arguments) {
      // 1. 原查询SQL直接保存。
      this.sql = sql;
      // 2. 原reader与参数保持。
      this.reader = reader;
      this.arguments = arguments.clone();
      // 3. 测试随后显式调用该原reader。
      return List.of();
    }
  }
}
