package com.cloudmall.order.config;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

import com.cloudmall.order.domain.po.OrderItemPO;
import com.cloudmall.order.domain.po.OrderPO;
import com.cloudmall.order.mapper.*;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;
import javax.sql.DataSource;
import org.apache.shardingsphere.sql.parser.SQLParserEngine;
import org.apache.shardingsphere.sql.parser.sql.segment.dml.expr.simple.ParameterMarkerExpressionSegment;
import org.apache.shardingsphere.sql.parser.sql.segment.dml.predicate.value.PredicateCompareRightValue;
import org.apache.shardingsphere.sql.parser.sql.statement.dml.UpdateStatement;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.AnnotationConfigApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Profile;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.RowMapper;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.mock.env.MockEnvironment;

/**
 * Two bounded local contracts for the real production adapter and mode guard, without connections.
 */
class OrderShardingRuntimeTest {
  /** Original test identity; its six-digit month is also the published hint. */
  private static final String NO = "202612Exact-A";

  /** Original fixed local component used in all binding comparisons. */
  private static final LocalDateTime NOW = LocalDateTime.parse("2026-12-16T09:10:11.123");

  /** Rejecting definitions must never instantiate their fake data source. */
  private static final AtomicInteger CREATED = new AtomicInteger();

  @Test
  void logicalSqlKeepsOriginalBindingsAndBoundedMonths() throws Exception {
    Capture jdbc = new Capture();
    ShardingOrderSqlMapper orders = new ShardingOrderSqlMapper(jdbc, List.of("202612"));
    LocalDateTime start = LocalDateTime.parse("2026-12-01T00:00:00");
    LocalDateTime end = start.plusMonths(1);
    OrderPO order = new OrderPO();
    order.id = 31L;
    order.orderNo = NO;
    order.userId = 7L;
    order.status = "PENDING_PAYMENT";
    order.totalAmount = new BigDecimal("16.50");
    order.payAmount = order.totalAmount;
    order.addressSnapshot = "{}";
    order.createdAt = NOW;
    order.updatedAt = NOW;
    order.expireAt = NOW.plusMinutes(30);
    assertEquals(1, orders.insertOrder("mall_order_202612", order));
    assertTrue(jdbc.sql.startsWith("insert into mall_order("));
    assertArrayEquals(
        new Object[] {
          31L,
          NO,
          7L,
          "PENDING_PAYMENT",
          order.totalAmount,
          order.payAmount,
          "{}",
          order.expireAt,
          NOW,
          NOW
        },
        jdbc.arguments);
    assertEquals(10, new SQLParserEngine("MySQL").parse(jdbc.sql, false).getParameterCount());
    OrderItemPO item = new OrderItemPO();
    item.id = 32L;
    item.orderId = 31L;
    item.orderNo = NO;
    item.productId = 101L;
    item.skuId = 11L;
    item.productNameSnapshot = "sample";
    item.skuSnapshot = "{}";
    item.unitPrice = new BigDecimal("8.25");
    item.quantity = 2;
    item.lineAmount = order.totalAmount;
    item.createdAt = NOW;
    assertEquals(1, orders.insertOrderItem("mall_order_item_202612", item));
    assertTrue(jdbc.sql.startsWith("insert into mall_order_item("));
    assertArrayEquals(
        new Object[] {
          32L, 31L, NO, 101L, 11L, "sample", "{}", item.unitPrice, 2, item.lineAmount, NOW
        },
        jdbc.arguments);
    assertEquals(11, new SQLParserEngine("MySQL").parse(jdbc.sql, false).getParameterCount());
    assertEquals(1L, orders.findOrderId("mall_order_202612", NO));
    bounded(jdbc, new Object[] {NO, start, end});
    orders.findOrder("mall_order_202612", NO);
    bounded(jdbc, new Object[] {NO, start, end});
    assertTrue(jdbc.sql.contains("CAST(created_at AS CHAR) AS cm_local_created_at"));
    assertTrue(jdbc.sql.contains("CAST(expire_at AS CHAR) AS cm_local_expire_at"));
    orders.findOrderItems("mall_order_item_202612", NO);
    bounded(jdbc, new Object[] {NO, start, end});
    assertTrue(jdbc.sql.endsWith("order by id"));
    jdbc.candidates = List.of();
    assertFalse(orders.orderExists("mall_order_202612", NO));
    jdbc.candidates = List.of(Map.of("order_no", NO));
    assertTrue(orders.orderExists("mall_order_202612", NO));
    bounded(jdbc, new Object[] {NO, start, end});
    jdbc.candidates = List.of(Map.of("order_no", NO.toLowerCase(java.util.Locale.ROOT)));
    assertThrows(IllegalStateException.class, () -> orders.orderExists("mall_order_202612", NO));
    jdbc.candidates = List.of(Map.of("order_no", NO), Map.of("order_no", NO));
    assertThrows(IllegalStateException.class, () -> orders.orderExists("mall_order_202612", NO));
    assertEquals(
        1,
        orders.countOrders(
            "mall_order_202612", 7L, start.minusMonths(1), end.plusMonths(1), "PAID"));
    bounded(jdbc, new Object[] {7L, start, end, "PAID"});
    assertTrue(jdbc.sql.startsWith("select count(*) from mall_order"));
    orders.findOrderPage(
        "mall_order_202612",
        7L,
        Timestamp.valueOf(start.minusMonths(1)),
        Timestamp.valueOf(end.plusMonths(1)),
        null,
        9L,
        3);
    bounded(jdbc, new Object[] {7L, start, end, 3, 9L});
    assertTrue(jdbc.sql.endsWith("order by created_at desc,order_no desc limit ? offset ?"));
    int executed = jdbc.executed;
    assertEquals(0, orders.countOrders("mall_order_202612", 7L, end, end.plusMonths(1), null));
    assertEquals(
        List.of(),
        orders.findOrderPage("mall_order_202612", 7L, end, end.plusMonths(1), null, 0, 1));
    assertEquals(executed, jdbc.executed);
    assertThrows(
        IllegalArgumentException.class,
        () -> orders.findOrderPage("mall_order_202612", 7L, start, end, null, -1, 1));
    assertThrows(
        IllegalArgumentException.class,
        () -> orders.countOrders("mall_order_202612", 7L, "2026-12-01", end, null));
    assertEquals(1, orders.updateStatus("mall_order_202612", NO, "PAID", "PENDING_PAYMENT", NOW));
    bounded(
        jdbc,
        new Object[] {"PAID", NOW, "PAID", NOW, "PAID", NOW, NO, "PENDING_PAYMENT", start, end});
    UpdateStatement ast = (UpdateStatement) new SQLParserEngine("MySQL").parse(jdbc.sql, false);
    // Full JDBC bindings are asserted separately; this IF statement actually exposes all ten AST
    // markers.
    assertEquals(10, ast.getParameterCount());
    Map<String, Integer> indices = new LinkedHashMap<>();
    for (var and : ast.getWhere().orElseThrow().getAndPredicates())
      for (var predicate : and.getPredicates()) {
        PredicateCompareRightValue right = (PredicateCompareRightValue) predicate.getRightValue();
        indices.put(
            predicate.getColumn().getIdentifier().getValue() + right.getOperator(),
            ((ParameterMarkerExpressionSegment) right.getExpression()).getParameterMarkerIndex());
      }
    assertEquals(
        Map.of("order_no=", 6, "status=", 7, "created_at>=", 8, "created_at<", 9), indices);
    executed = jdbc.executed;
    assertThrows(
        DataAccessResourceFailureException.class,
        () -> orders.orderExists("mall_order_202701", "202701Missing"));
    assertThrows(
        DataAccessResourceFailureException.class,
        () -> orders.countOrders("mall_order_202701", 7L, end, end.plusMonths(1), null));
    assertThrows(
        IllegalArgumentException.class, () -> orders.findOrder("mall_order_202612", "202611Wrong"));
    assertThrows(
        IllegalArgumentException.class, () -> orders.findOrder("mall_order_202613", "202613Wrong"));
    assertThrows(IllegalStateException.class, orders::ensureMonthlyCompanionTemplates);
    assertThrows(IllegalStateException.class, () -> orders.createMonthlyOrderTables("202612"));
    assertEquals(executed, jdbc.executed);
    ResultSet row = mock(ResultSet.class);
    when(row.getString("cm_local_created_at")).thenReturn("1582-10-10 12:34:56.123");
    for (String column :
        List.of("order_no", "user_id", "status", "total_amount", "pay_amount", "address_snapshot"))
      when(row.getObject(column)).thenReturn(column);
    jdbc.row = row;
    ShardingOrderSqlMapper ancient = new ShardingOrderSqlMapper(jdbc, List.of("158210"));
    for (boolean page : List.of(false, true)) {
      List<Map<String, Object>> values =
          page
              ? ancient.findOrderPage(
                  "mall_order_158210",
                  7L,
                  LocalDateTime.parse("1582-10-01T00:00:00"),
                  LocalDateTime.parse("1582-11-01T00:00:00"),
                  null,
                  0,
                  1)
              : ancient.findOrder("mall_order_158210", "158210Exact");
      assertEquals(
          Set.of(
              "order_no",
              "user_id",
              "status",
              "total_amount",
              "pay_amount",
              "address_snapshot",
              "created_at",
              "expire_at"),
          values.get(0).keySet());
      assertEquals(LocalDateTime.parse("1582-10-10T12:34:56.123"), values.get(0).get("created_at"));
      assertNull(values.get(0).get("expire_at"));
    }
    verify(row, never()).getTimestamp(anyString());
    verify(row, never()).getObject(anyString(), eq(LocalDateTime.class));

    // The full real JDBC array is independent of the parser's partial expression model.
    Capture resolvedJdbc = new Capture();
    String resolvedEvent = "cm17ej-resolved-event";
    String originalKey = "Original-Key ";
    String suppliedHash = "Ab".repeat(32);
    String canonicalHash = "ab".repeat(32);
    new SeckillMessageRecoveryMapper(resolvedJdbc)
        .markResolved(resolvedEvent, NO, Long.MAX_VALUE, originalKey, suppliedHash, NOW);
    assertArrayEquals(
        new Object[] {
          NOW,
          resolvedEvent,
          resolvedEvent,
          canonicalHash,
          NO,
          Long.toString(Long.MAX_VALUE),
          originalKey,
          resolvedEvent,
          canonicalHash
        },
        resolvedJdbc.arguments);
    assertEquals(9, resolvedJdbc.arguments.length);
    assertEquals(9L, resolvedJdbc.sql.chars().filter(character -> character == '?').count());
    assertTrue(
        resolvedJdbc.sql.startsWith(
            "UPDATE seckill_message_recovery SET"
                + " status='RESOLVED',lease_owner=NULL,lease_until=NULL,updated_at=? "));
    assertTrue(
        resolvedJdbc.sql.contains(
            " AND (JSON_TYPE(JSON_EXTRACT(payload,'$.userId'))='INTEGER'"
                + " OR JSON_TYPE(JSON_EXTRACT(payload,'$.userId'))='UNSIGNED INTEGER')"));
    assertFalse(resolvedJdbc.sql.contains("JSON_TYPE(JSON_EXTRACT(payload,'$.userId')) IN"));
    for (String guard :
        List.of(
            "WHERE event_id=? AND BINARY event_id=BINARY ?",
            " AND BINARY LOWER(request_hash)=BINARY ?",
            " AND JSON_TYPE(JSON_EXTRACT(payload,'$.orderNo'))='STRING'",
            " AND BINARY JSON_UNQUOTE(JSON_EXTRACT(payload,'$.orderNo'))=BINARY ?",
            " AND JSON_EXTRACT(payload,'$.userId')=CAST(? AS JSON)",
            " AND JSON_TYPE(JSON_EXTRACT(payload,'$.idempotencyKey'))='STRING'",
            " AND BINARY JSON_UNQUOTE(JSON_EXTRACT(payload,'$.idempotencyKey'))=BINARY ?",
            " AND JSON_TYPE(JSON_EXTRACT(payload,'$.eventId'))='STRING'",
            " AND BINARY JSON_UNQUOTE(JSON_EXTRACT(payload,'$.eventId'))=BINARY ?",
            " AND JSON_TYPE(JSON_EXTRACT(payload,'$.requestHash'))='STRING'",
            " AND BINARY LOWER(JSON_UNQUOTE(JSON_EXTRACT(payload,'$.requestHash')))=BINARY ?",
            " AND status IN ('REPLAY_PENDING','REPLAYED','QUARANTINED','VERIFYING')"))
      assertTrue(resolvedJdbc.sql.contains(guard), guard);
    UpdateStatement resolvedAst =
        (UpdateStatement) new SQLParserEngine("MySQL").parse(resolvedJdbc.sql, false);
    // Actual 4.1.1 AST exposes only two markers here; the complete JDBC nine were checked above.
    assertEquals(2, resolvedAst.getParameterCount());
  }

  @Test
  void modeSelectionKeepsDefaultAndRejectsConflictingBindings() {
    MockEnvironment environment = new MockEnvironment();
    assertEquals("dynamic-monthly", OrderRoutingConfiguration.mode(environment));
    assertDoesNotThrow(() -> OrderRoutingConfiguration.validate(environment));
    for (String value : List.of("", " ", "sharding", "sharding-shadow", "unknown")) {
      environment.setProperty("cloudmall.order.routing.mode", value);
      assertThrows(
          IllegalStateException.class, () -> OrderRoutingConfiguration.validate(environment));
    }
    environment.setProperty("cloudmall.order.routing.mode", " sharding-monthly ");
    assertThrows(
        IllegalStateException.class, () -> OrderRoutingConfiguration.validate(environment));
    environment.setProperty("seata.enabled", "false");
    assertDoesNotThrow(() -> OrderRoutingConfiguration.validate(environment));
    assertEquals("sharding-monthly", OrderRoutingConfiguration.mode(environment));
    environment.setProperty("spring.main.allow-bean-definition-overriding", "true");
    assertThrows(
        IllegalStateException.class, () -> OrderRoutingConfiguration.validate(environment));
    environment.setProperty("spring.main.allow-bean-definition-overriding", "false");
    environment.setActiveProfiles("sharding");
    assertThrows(
        IllegalStateException.class, () -> OrderRoutingConfiguration.validate(environment));
    environment.setActiveProfiles();
    environment.setDefaultProfiles("sharding");
    assertThrows(
        IllegalStateException.class, () -> OrderRoutingConfiguration.validate(environment));
    CREATED.set(0);
    try (AnnotationConfigApplicationContext context = new AnnotationConfigApplicationContext()) {
      context.getEnvironment().setActiveProfiles("cm17ej-unit");
      context
          .getEnvironment()
          .getPropertySources()
          .addFirst(
              new org.springframework.core.env.MapPropertySource(
                  "routing",
                  Map.of(
                      "cloudmall.order.routing.mode",
                      " sharding-monthly ",
                      "seata.enabled",
                      "false")));
      context.register(
          OrderRoutingConfiguration.class, OrderShardingRuntimeConfiguration.class, Conflict.class);
      RuntimeException failure = assertThrows(RuntimeException.class, context::refresh);
      assertTrue(causes(failure).contains(OrderRoutingConfiguration.UNSUPPORTED));
      assertEquals(0, CREATED.get());
      assertFalse(context.getBeanFactory().containsSingleton("orderShardingBootstrap"));
    }
    DataSource dataSource = mock(DataSource.class);
    JdbcTemplate jdbc = new JdbcTemplate(dataSource);
    assertDoesNotThrow(
        () ->
            new OrderRoutingAudit(
                dataSource,
                jdbc,
                new DataSourceTransactionManager(dataSource),
                new MockEnvironment()));
    assertThrows(
        IllegalStateException.class,
        () ->
            new OrderRoutingAudit(
                dataSource,
                jdbc,
                new DataSourceTransactionManager(dataSource),
                new MockEnvironment()
                    .withProperty("cloudmall.order.routing.mode", "sharding-monthly")));
    OrderSqlMapper original = mock(OrderSqlMapper.class);
    new OrderTableInitializer(original).prepareRollingTables();
    verify(original).ensureMonthlyCompanionTemplates();
    verify(original, times(4)).createMonthlyOrderTables(anyString());
    OrderReservationIntentMapper intent = mock(OrderReservationIntentMapper.class);
    OrderOutboxMapper outbox = mock(OrderOutboxMapper.class);
    OrderStockActionMapper action = mock(OrderStockActionMapper.class);
    SeckillOrderDedupMapper dedup = mock(SeckillOrderDedupMapper.class);
    SeckillMessageRecoveryMapper recovery = mock(SeckillMessageRecoveryMapper.class);
    new OrderReliabilityTableInitializer(intent, outbox, action, dedup, recovery).prepareTables();
    verify(intent).ensureTable();
    verify(outbox).ensureTable();
    verify(action).ensureTable();
    verify(dedup).ensureTable();
    verify(recovery).ensureTable();
    @SuppressWarnings("unchecked")
    ObjectProvider<OrderShardingBootstrap> absent = mock(ObjectProvider.class);
    MockEnvironment explicit =
        new MockEnvironment().withProperty("cloudmall.order.routing.mode", "sharding-monthly");
    assertThrows(
        IllegalStateException.class,
        () -> new OrderTableInitializer(original, explicit, absent).prepareRollingTables());
  }

  private static String causes(Throwable failure) {
    StringBuilder result = new StringBuilder();
    for (Throwable cause = failure; cause != null; cause = cause.getCause())
      result.append(cause.getMessage()).append('\n');
    return result.toString();
  }

  private static void bounded(Capture jdbc, Object[] expected) {
    assertArrayEquals(expected, jdbc.arguments);
    assertTrue(jdbc.sql.contains("created_at>=? and created_at<?"));
  }

  /** Profile prevents these deliberately conflicting definitions from entering any old app scan. */
  @TestConfiguration
  @Profile("cm17ej-unit")
  static class Conflict {
    /** Must remain unconstructed when the early guard finds the extra definition. */
    @Bean
    public DataSource unexpectedDataSource() {
      CREATED.incrementAndGet();
      return mock(DataSource.class);
    }

    /** A primary flag would not grant permission for a second transaction manager. */
    @Bean
    public DataSourceTransactionManager unexpectedTransactionManager() {
      return new DataSourceTransactionManager(mock(DataSource.class));
    }
  }

  /** Captures real production SQL and complete JDBC arrays, with a controlled row-mapping path. */
  private static final class Capture extends JdbcTemplate {
    /** Last SQL text. */
    private String sql;

    /** Full last JDBC array, independent from the parser's limited AST. */
    private Object[] arguments;

    /** Number of actual JDBC entry calls. */
    private int executed;

    /** Original existence candidates, including deliberately ambiguous values. */
    private List<Map<String, Object>> candidates = List.of();

    /** Controlled temporal row; null leaves query results empty. */
    private ResultSet row;

    private void capture(String sql, Object[] arguments) {
      this.sql = sql;
      this.arguments = arguments.clone();
      executed++;
    }

    /** Captures a real production update without a connection. */
    @Override
    public int update(String sql, Object... arguments) {
      capture(sql, arguments);
      return 1;
    }

    /** Captures a real production count/ID without a connection. */
    @Override
    @SuppressWarnings("unchecked")
    public <T> T queryForObject(String sql, Class<T> type, Object... arguments) {
      capture(sql, arguments);
      return (T) Long.valueOf(1);
    }

    /** Captures real candidate/item reads without a connection. */
    @Override
    public List<Map<String, Object>> queryForList(String sql, Object... arguments) {
      capture(sql, arguments);
      return candidates;
    }

    /** Executes the actual production RowMapper against controlled CHAR aliases. */
    @Override
    public <T> List<T> query(String sql, RowMapper<T> mapper, Object... arguments) {
      capture(sql, arguments);
      try {
        return row == null ? List.of() : List.of(mapper.mapRow(row, 0));
      } catch (java.sql.SQLException failure) {
        throw new org.springframework.jdbc.UncategorizedSQLException("controlled", sql, failure);
      }
    }
  }
}
