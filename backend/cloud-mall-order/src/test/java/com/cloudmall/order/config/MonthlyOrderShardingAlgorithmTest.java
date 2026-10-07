package com.cloudmall.order.config;

import static org.junit.jupiter.api.Assertions.*;

import com.google.common.collect.Range;
import java.sql.Timestamp;
import java.time.LocalDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import org.apache.shardingsphere.api.sharding.standard.PreciseShardingValue;
import org.apache.shardingsphere.api.sharding.standard.RangeShardingAlgorithm;
import org.apache.shardingsphere.api.sharding.standard.RangeShardingValue;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;

/** 冻结尚未接管主链路的月分片算法与配置契约。 */
class MonthlyOrderShardingAlgorithmTest {
  /** 被测路由算法，无数据源或时钟依赖。 */
  private final MonthlyOrderShardingAlgorithm algorithm = new MonthlyOrderShardingAlgorithm();

  /** 倒序提供目标，验证算法输出不依赖配置顺序。 */
  private final List<String> targets =
      List.of("mall_order_202702", "mall_order_202701", "mall_order_202612");

  @Test
  void preciseRoutesBothJdbcTypesAtYearBoundary() {
    assertEquals("mall_order_202612", precise(time("2026-12-31T23:59:59.999")));
    assertEquals("mall_order_202701", precise(Timestamp.valueOf("2027-01-01 00:00:00")));
  }

  @Test
  void rejectsStringGuessingAndUnsupportedTypes() {
    for (Comparable<?> value :
        List.<Comparable<?>>of(
            "2027-01-invalid",
            "2027-01-01T00:00:00",
            202701,
            time("2027-01-01T00:00:00").atOffset(java.time.ZoneOffset.UTC),
            java.time.Instant.EPOCH)) {
      assertThrows(IllegalArgumentException.class, () -> precise(value));
    }
    assertThrows(IllegalArgumentException.class, () -> precise(null));
  }

  @Test
  void rejectsSubMillisecondAndOutOfDatabaseRange() {
    assertThrows(IllegalArgumentException.class, () -> precise(time("2027-01-01T00:00:00.000001")));
    assertThrows(IllegalArgumentException.class, () -> precise(time("0999-12-31T23:59:59.999")));
    assertThrows(
        IllegalArgumentException.class, () -> precise(LocalDateTime.of(10000, 1, 1, 0, 0)));
  }

  @Test
  void rejectsMixedDuplicateMalformedAndMissingTargets() {
    LocalDateTime instant = time("2027-01-01T00:00:00");
    for (List<String> invalid :
        List.of(
            List.of("mall_order_item_202701"),
            List.of("mall_order_202701", "mall_order_202701"),
            List.of("mall_order_202713"),
            List.of("ds0.mall_order_202701"),
            List.of("mall_order_202612"),
            List.<String>of())) {
      assertThrows(
          IllegalArgumentException.class,
          () ->
              algorithm.doSharding(
                  invalid, new PreciseShardingValue<>("mall_order", "created_at", instant)));
    }
  }

  @Test
  void rejectsUnknownTableOrColumnAndRoutesAllCompanionTables() {
    LocalDateTime instant = time("2027-01-01T00:00:00");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            algorithm.doSharding(
                targets, new PreciseShardingValue<>("mall_order", "updated_at", instant)));
    assertThrows(
        IllegalArgumentException.class,
        () ->
            algorithm.doSharding(
                List.of("other_202701"),
                new PreciseShardingValue<>("other", "created_at", instant)));
    for (String table : List.of("mall_order", "mall_order_item", "order_status_log")) {
      assertEquals(
          table + "_202701",
          algorithm.doSharding(
              List.of(table + "_202701"),
              new PreciseShardingValue<>(table, "created_at", instant)));
    }
  }

  @Test
  void boundedRangeIsChronologicalAndHonorsExclusiveMonthStart() {
    assertEquals(
        List.of("mall_order_202612", "mall_order_202701"),
        range(targets, Range.closedOpen(time("2026-12-01T00:00:00"), time("2027-02-01T00:00:00"))));
    assertEquals(
        List.of("mall_order_202612", "mall_order_202701"),
        range(targets, Range.closed(time("2026-12-31T23:59:59.999"), time("2027-01-01T00:00:00"))));
  }

  @Test
  void openLastMillisecondExcludesPreviousMonth() {
    assertEquals(
        List.of("mall_order_202701"),
        range(
            targets,
            Range.openClosed(time("2026-12-31T23:59:59.999"), time("2027-01-01T00:00:00"))));
  }

  @Test
  void emptyAndSingletonRangesNeverBroadcast() {
    LocalDateTime instant = time("2027-01-01T00:00:00");
    assertEmptyRange(Range.closedOpen(instant, instant));
    assertEmptyRange(Range.openClosed(instant, instant));
    assertEmptyRange(Range.open(instant, instant.plusNanos(1_000_000)));
    assertEquals(List.of("mall_order_202701"), range(targets, Range.singleton(instant)));
  }

  @Test
  void missingInteriorMonthAndUnboundedRangesFailClosed() {
    LocalDateTime start = time("2026-12-01T00:00:00");
    assertThrows(
        IllegalArgumentException.class,
        () ->
            range(
                List.of("mall_order_202612", "mall_order_202702"),
                Range.closed(start, time("2027-02-01T00:00:00"))));
    for (Range<LocalDateTime> unbounded :
        List.of(Range.atLeast(start), Range.lessThan(start), Range.<LocalDateTime>all())) {
      assertThrows(IllegalArgumentException.class, () -> range(targets, unbounded));
    }
  }

  @Test
  void timestampRangeAndExtremeEmptyRangeAreSupported() {
    assertEquals(
        List.of("mall_order_202701"),
        range(
            targets,
            Range.closedOpen(
                Timestamp.valueOf("2027-01-01 00:00:00"),
                Timestamp.valueOf("2027-02-01 00:00:00"))));
    LocalDateTime minimum = time("1000-01-01T00:00:00");
    LocalDateTime maximum = time("9999-12-31T23:59:59.999");
    assertEmptyRange(Range.closedOpen(minimum, minimum));
    assertEmptyRange(Range.openClosed(maximum, maximum));
  }

  @Test
  void dormantYamlUsesLoadablePreciseAndRangeClassesWithSameMonthWindow() throws Exception {
    YamlPropertiesFactoryBean yaml = new YamlPropertiesFactoryBean();
    yaml.setResources(new ClassPathResource("application.yml"));
    Map<Object, Object> properties = yaml.getObject();
    String expectedNodes = null;
    for (String table : List.of("mall_order", "mall_order_item", "order_status_log")) {
      String prefix = "spring.shardingsphere.sharding.tables." + table;
      for (String kind : List.of("precise", "range")) {
        String className =
            (String)
                properties.get(
                    prefix + ".table-strategy.standard." + kind + "-algorithm-class-name");
        assertEquals(MonthlyOrderShardingAlgorithm.class.getName(), className);
        assertEquals(MonthlyOrderShardingAlgorithm.class, Class.forName(className));
      }
      String nodes = (String) properties.get(prefix + ".actual-data-nodes");
      assertTrue(nodes.startsWith("ds0." + table + "_"));
      String months = nodes.substring(("ds0." + table + "_").length());
      if (expectedNodes == null) expectedNodes = months;
      assertEquals(expectedNodes, months);
    }
  }

  private void assertEmptyRange(Range<LocalDateTime> values) {
    IllegalArgumentException failure =
        assertThrows(IllegalArgumentException.class, () -> range(targets, values));
    assertEquals(
        "ORDER_SHARD_ROUTE_EMPTY: no representable DATETIME(3) value", failure.getMessage());
  }

  private String precise(Comparable<?> value) {
    return algorithm.doSharding(
        targets, new PreciseShardingValue<>("mall_order", "created_at", value));
  }

  @SuppressWarnings({"unchecked", "rawtypes"})
  private Collection<String> range(List<String> available, Range<?> values) {
    assertInstanceOf(RangeShardingAlgorithm.class, algorithm);
    RangeShardingAlgorithm rangeAlgorithm = (RangeShardingAlgorithm) (Object) algorithm;
    return rangeAlgorithm.doSharding(
        available, new RangeShardingValue("mall_order", "created_at", values));
  }

  private LocalDateTime time(String value) {
    return LocalDateTime.parse(value);
  }
}
